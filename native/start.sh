#!/usr/bin/env sh
# Run the native atc binary (native/build.sh): loads .env, finds a JDK for the
# compiler's class metadata, passes every atc flag through.
#
#   native/start.sh                    # interactive, in the current directory
#   native/start.sh -C ~/proj -m gpt   # any atc flag is passed through
#   native/start.sh -p "run: 1 + 1"    # one non-interactive turn
#
# Environment: everything start.sh reads (ANTHROPIC_API_KEY, ATC_MODEL, ATC_CONFIG,
# ATC_CWD, ATC_DEBUG, ATC_ENV_FILE) plus
#   ATC_JAVA_HOME     the JDK (17+) whose classes the sandbox compiles against;
#                     falls back to JAVA_HOME, /usr/libexec/java_home, then the
#                     `java` on PATH
#   ATC_NATIVE_OPTS   extra runtime options for the binary (-D..., -Xmx...)
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
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

BIN="$ROOT/out/native/atc"
LIBJAR="$ROOT/out/dist.dest/atc-lib.jar"
if [ ! -x "$BIN" ] || [ ! -f "$LIBJAR" ]; then
  echo "native/start.sh: $BIN or $LIBJAR is missing; run ./mill dist and GRAALVM_HOME=... native/build.sh first" >&2
  exit 1
fi

# The JDK the sandbox compiles against (its jrt: file system) and that runtime
# class loading falls back to for JDK classes outside the image.
JDK=${ATC_JAVA_HOME:-${JAVA_HOME:-}}
if [ -z "$JDK" ] && [ -x /usr/libexec/java_home ]; then
  JDK=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "$JDK" ] && command -v java >/dev/null 2>&1; then
  java_bin=$(command -v java)
  while [ -L "$java_bin" ]; do
    target=$(readlink "$java_bin")
    case "$target" in /*) java_bin=$target ;; *) java_bin=$(dirname "$java_bin")/$target ;; esac
  done
  JDK=$(cd "$(dirname "$java_bin")/.." && pwd)
fi
if [ -z "$JDK" ] || [ ! -f "$JDK/lib/modules" ]; then
  echo "native/start.sh: no JDK found (need <jdk>/lib/modules); set ATC_JAVA_HOME or JAVA_HOME to a JDK 17+" >&2
  exit 1
fi

VERSION=$(cat "$ROOT/out/dist.dest/version.txt" 2>/dev/null || echo dev)

[ -n "${ATC_MODEL:-}" ]  && set -- -m "$ATC_MODEL" "$@"
[ -n "${ATC_CONFIG:-}" ] && set -- -c "$ATC_CONFIG" "$@"
[ -n "${ATC_CWD:-}" ]    && set -- -C "$ATC_CWD" "$@"

# shellcheck disable=SC2086
exec "$BIN" ${ATC_NATIVE_OPTS:-} \
  -Dfile.encoding=UTF-8 \
  -Djava.home="$JDK" \
  -Datc.version="$VERSION" \
  -Datc.lib.classpath="$LIBJAR" \
  "$@"
