#!/usr/bin/env sh
# Start ATC: loads .env, (re)builds the distribution when sources changed, runs it.
#
#   ./start.sh                     # interactive, in the current directory
#   ./start.sh -C ~/proj -m gpt    # any atc flag is passed through
#   ./start.sh -p "run: 1 + 1"     # one non-interactive turn
#
# Environment (see .env.example): ANTHROPIC_API_KEY, OPENAI_API_KEY, ATC_MODEL,
# ATC_CONFIG, ATC_CWD, ATC_JAVA_OPTS, ATC_DEBUG, ATC_ENV_FILE, ATC_SKIP_BUILD.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=${ATC_ENV_FILE:-$ROOT/.env}

# Load .env without clobbering variables already exported in the shell.
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    line=${line#export }
    key=${line%%=*}
    value=${line#*=}
    case "$key" in *[!A-Za-z0-9_]*|'') continue ;; esac
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

set -- "$@"
[ -n "${ATC_MODEL:-}" ]  && set -- -m "$ATC_MODEL" "$@"
[ -n "${ATC_CONFIG:-}" ] && set -- -c "$ATC_CONFIG" "$@"
[ -n "${ATC_CWD:-}" ]    && set -- -C "$ATC_CWD" "$@"

# shellcheck disable=SC2086
exec java ${ATC_JAVA_OPTS:-} -Datc.lib.classpath="$LIBJAR" -jar "$JAR" "$@"
