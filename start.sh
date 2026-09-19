#!/usr/bin/env sh
# Start ATC: loads .env, (re)builds the distribution when sources changed, runs it.
#
#   ./start.sh                     # interactive, in the current directory
#   ./start.sh -C ~/proj -m gpt    # any atc flag is passed through
#   ./start.sh -p "run: 1 + 1"     # one non-interactive turn
#   ./start.sh -Xmx4g              # -Xmx/-Xms go to the JVM, everything else to atc
#
# Environment (see .env.example): ANTHROPIC_API_KEY, OPENAI_API_KEY, ATC_MODEL,
# ATC_CONFIG, ATC_CWD, ATC_JAVA_OPTS, ATC_DEBUG, ATC_ENV_FILE, ATC_SKIP_BUILD.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=${ATC_ENV_FILE:-$ROOT/.env}

# Load literal KEY=value entries without replacing non-empty exported values.
if [ -f "$ENV_FILE" ]; then
  cr=$(printf '\r')
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%"$cr"}
    case "$line" in ''|'#'*) continue ;; esac
    line=${line#export }
    case "$line" in *=*) ;; *) continue ;; esac
    key=${line%%=*}
    value=${line#*=}
    case "$key" in ''|[0-9]*|*[!A-Za-z0-9_]*) continue ;; esac
    # strip surrounding quotes
    case "$value" in
      \"*\") value=${value#\"}; value=${value%\"} ;;
      \'*\') value=${value#\'}; value=${value%\'} ;;
    esac
    [ -z "$value" ] && continue
    eval "current=\${$key:-}"
    if [ -z "$current" ]; then
      export "$key=$value"
    fi
  done < "$ENV_FILE"
fi

DIST="$ROOT/out/dist.dest"
JAR="$DIST/atc.jar"
LIBJAR="$DIST/atc-lib.jar"

needs_build=0
if [ "${ATC_SKIP_BUILD:-0}" != "1" ]; then
  if [ ! -f "$JAR" ] || [ ! -f "$LIBJAR" ]; then
    needs_build=1
  elif [ -n "$(find "$ROOT/build.mill" "$ROOT/app" "$ROOT/lib" -type f -newer "$JAR" | head -1)" ]; then
    needs_build=1
  fi
fi
if [ "$needs_build" = 1 ]; then
  echo "[start.sh] building distribution (./mill dist)..." >&2
  (cd "$ROOT" && ./mill dist >/dev/null)
fi

# `-Xmx<size>` / `-Xms<size>` are the JVM's flags, not ATC's: take them out for java. The
# value of an ATC option that takes one (-p 'text', -C dir, ...) is forwarded untouched even
# when it starts with -Xm: the list of those options mirrors `FlagsWithValues` in
# app/src/atc/Cli.scala (`atc` and the PowerShell launchers carry the same list).
JVM_OPTS=
expect_value=
for arg in "$@"; do
  shift
  if [ -n "$expect_value" ]; then
    expect_value=
    set -- "$@" "$arg"
    continue
  fi
  case "$arg" in
    -c|--config|-C|--cwd|-m|--model|-p|--prompt|--mode)
      expect_value=1
      set -- "$@" "$arg" ;;
    -Xmx*|-Xms*)
      size=${arg#-Xm?}
      case "${size%[kKmMgG]}" in
        ''|*[!0-9]*)
          echo "[start.sh] Invalid JVM heap size '$arg': use a number with an optional k, m or g suffix, e.g. -Xmx4g or -Xms512m" >&2
          exit 1 ;;
      esac
      JVM_OPTS="$JVM_OPTS $arg" ;;
    *) set -- "$@" "$arg" ;;
  esac
done

[ -n "${ATC_MODEL:-}" ]  && set -- -m "$ATC_MODEL" "$@"
[ -n "${ATC_CONFIG:-}" ] && set -- -c "$ATC_CONFIG" "$@"
[ -n "${ATC_CWD:-}" ]    && set -- -C "$ATC_CWD" "$@"

# JVM defaults as in the `atc` wrapper (doc/development.md "JVM settings"); the same list
# is repeated in atc, start.ps1, windows/atc.ps1 and the dist script in build.mill.
DEFAULT_JVM_OPTS="-Xms256m -Xmx2g -Xss4m -XX:-UsePerfData"

# Startup cache as in the `atc` wrapper: an AOT cache (Java 25+) or a CDS archive (Java
# 19-24) under out/dist.dest/startup/, rebuilt by one echo-model run when the jars (every
# ./mill dist) or the JDK change. ATC_STARTUP_CACHE=0 disables it.
java_version=$(java -version 2>&1)
java_major=$(printf '%s\n' "$java_version" | head -n1 | sed -n 's/^[^"]*"\([0-9]*\).*/\1/p')
case "$java_major" in ''|*[!0-9]*) java_major=0 ;; esac
# Scala's LazyVals still use sun.misc.Unsafe; Java 23+ warns about it on every run (JEP 471).
VERSIONED_JVM_OPTS=
[ "$java_major" -lt 23 ] || VERSIONED_JVM_OPTS="--sun-misc-unsafe-memory-access=allow"

STARTUP_OPTS=
if [ "${ATC_STARTUP_CACHE:-1}" != 0 ]; then
  cache_dir="$DIST/startup"
  cache_key="$cache_dir/key.txt"
  if [ "$java_major" -ge 25 ]; then
    cache="$cache_dir/atc.aot"; create="-XX:AOTCacheOutput=$cache"; use="-XX:AOTCache=$cache"
  elif [ "$java_major" -ge 19 ]; then
    cache="$cache_dir/atc.jsa"; create="-XX:ArchiveClassesAtExit=$cache"; use="-XX:SharedArchiveFile=$cache"
  else
    cache=
  fi
  if [ -n "$cache" ]; then
    if [ ! -f "$cache_key" ] || [ "$(cat "$cache_key")" != "$java_version" ] || [ -n "$(find "$JAR" "$LIBJAR" -newer "$cache_key")" ]; then
      echo "[start.sh] preparing the JVM startup cache (a few seconds)..." >&2
      mkdir -p "$cache_dir"
      rm -f "$cache"
      tmp=$(mktemp -d "${TMPDIR:-/tmp}/atc-startup-cache.XXXXXX")
      printf '%s\n' '{"providers":{"echo":{"api":"echo","models":{"echo":{}}}},"model":"echo"}' > "$tmp/train.json"
      # shellcheck disable=SC2086
      java $DEFAULT_JVM_OPTS $VERSIONED_JVM_OPTS "$create" -Dfile.encoding=UTF-8 -Datc.lib.classpath="$LIBJAR" -jar "$JAR" \
        -c "$tmp/train.json" -C "$tmp" -p 'run: 1 + 1' >/dev/null 2>&1 || true
      rm -rf "$tmp"
      printf '%s\n' "$java_version" > "$cache_key"
      for jar in "$JAR" "$LIBJAR"; do
        [ -z "$(find "$jar" -newer "$cache_key")" ] || touch -r "$jar" "$cache_key"
      done
      [ -s "$cache" ] || { rm -f "$cache"; echo "[start.sh] could not build the startup cache; running without it." >&2; }
    fi
    [ -s "$cache" ] && STARTUP_OPTS=$use
  fi
fi

# Later flags win: the defaults, then ATC_JAVA_OPTS, then -Xmx/-Xms from the command line.
# shellcheck disable=SC2086
exec java $DEFAULT_JVM_OPTS $VERSIONED_JVM_OPTS $STARTUP_OPTS ${ATC_JAVA_OPTS:-} $JVM_OPTS -Dfile.encoding=UTF-8 -Datc.lib.classpath="$LIBJAR" -jar "$JAR" "$@"
