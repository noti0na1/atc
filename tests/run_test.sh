#!/usr/bin/env bash
# Run test: one non-interactive turn through every provider adapter against the
# mock LLM server (tests/mock-llm/server.py), which answers with thinking, a
# run_scala tool call and a final message. Exercises the whole path a real model
# takes: the SDK's HTTP client and streaming parser, the agent loop, the sandbox,
# the tool result rendering. Needs python3; no network beyond localhost.
#
#   tests/run_test.sh jar                     # out/dist.dest/{atc.jar,atc-lib.jar} on the JVM
#   tests/run_test.sh native                  # out/native/atc (needs a JDK for -Djava.home)
#   tests/run_test.sh cmd <command...>        # any launcher; atc flags are appended
#
# Environment: ATC_JAVA_HOME or JAVA_HOME (native: the JDK the sandbox compiles
# against), MOCK_PORT (default: a free port).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST="$ROOT/out/dist.dest"

fail() { echo "run_test: $*" >&2; exit 1; }

mode="${1:-jar}"; shift || true
case "$mode" in
  jar)
    [[ -f "$DIST/atc.jar" && -f "$DIST/atc-lib.jar" ]] || fail "no jars in $DIST; run ./mill dist first"
    launcher=(java -Dfile.encoding=UTF-8 "-Datc.lib.classpath=$DIST/atc-lib.jar" -jar "$DIST/atc.jar")
    ;;
  native)
    [[ -x "$ROOT/out/native/atc" ]] || fail "no binary at out/native/atc; run native/build.sh first"
    [[ -f "$DIST/atc-lib.jar" ]] || fail "no atc-lib.jar in $DIST; run ./mill dist first"
    jdk="${ATC_JAVA_HOME:-${JAVA_HOME:-}}"
    if [[ -z "$jdk" ]]; then
      jdk="$(java -XshowSettings:properties -version 2>&1 | sed -nE 's/^[[:space:]]*java\.home[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1/p' | head -n1)"
    fi
    [[ -n "$jdk" && -f "$jdk/lib/modules" ]] || fail "native mode needs a JDK (lib/modules): set ATC_JAVA_HOME"
    launcher=("$ROOT/out/native/atc" -Dfile.encoding=UTF-8 "-Djava.home=$jdk" "-Datc.lib.classpath=$DIST/atc-lib.jar")
    ;;
  cmd)
    [[ $# -gt 0 ]] || fail "cmd mode needs a command"
    launcher=("$@")
    ;;
  *) fail "unknown mode '$mode' (jar | native | cmd <command...>)" ;;
esac

command -v python3 >/dev/null 2>&1 || fail "python3 is required for the mock server"

WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

port="${MOCK_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')}"
python3 "$SCRIPT_DIR/mock-llm/server.py" --port "$port" --record "$WORK/requests" > "$WORK/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do
  curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1 && break
  sleep 0.1
done
curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1 || { cat "$WORK/server.log" >&2; fail "the mock server did not start"; }

cat > "$WORK/config.json" <<EOF
{
  "providers": {
    "mock-responses": { "api": "openai-responses", "url": "http://127.0.0.1:$port/v1", "key": "test",
                        "models": { "mock-responses": { "name": "mock", "contextWindow": "200k" } } },
    "mock-chat":      { "api": "openai", "url": "http://127.0.0.1:$port/v1", "key": "test",
                        "models": { "mock-chat": { "name": "mock", "contextWindow": "200k" } } },
    "mock-anthropic": { "api": "anthropic", "url": "http://127.0.0.1:$port", "key": "test",
                        "models": { "mock-anthropic": { "name": "mock", "contextWindow": "200k" } } }
  },
  "model": "mock-responses", "mode": "local",
  "files": [ { "path": ".", "access": "write" } ],
  "safeMode": true
}
EOF
mkdir -p "$WORK/project" && echo "hello world" > "$WORK/project/hello.txt"

passed=0; failed=0
check() { # <label> <needle> <transcript>
  if [[ "$3" == *"$2"* ]]; then
    echo "  PASS: $1"; passed=$((passed + 1))
  else
    echo "  FAIL: $1 (expected to find: $2)"; failed=$((failed + 1))
  fi
}

# HOME is redirected so the developer's ~/.atc/config.json (its models, keys and
# rules) stays out of the run; -p runs never write a config.
mkdir -p "$WORK/home"
for adapter in responses chat anthropic; do
  echo "--- $adapter ---"
  transcript="$(cd "$WORK/project" && HOME="$WORK/home" ATC_DEBUG=1 "${launcher[@]}" -c "$WORK/config.json" -m "mock-$adapter" \
    -p 'run: val n = read("hello.txt").length; val answer = n * 3 + 6; answer' 2>&1 || true)"
  check "thinking shown" "● thinking" "$transcript"
  check "tool call shown" "● run_scala" "$transcript"
  check "sandbox ran the code" "val answer: Int = 42" "$transcript"
  check "final answer quotes the result" "Result: val n: Int = 12" "$transcript"
  check "one tool call and the mock's usage" "1 tool call · 240 tokens" "$transcript"
  if [[ "$transcript" == *"Exception"* || "$transcript" == *"✗"* ]]; then
    echo "  FAIL: no error in the transcript"; failed=$((failed + 1))
    printf '%s\n' "$transcript" | grep -E "Exception|✗|^\s+at " | head -12
  else
    echo "  PASS: no error in the transcript"; passed=$((passed + 1))
  fi
done

echo
echo "requests seen by the mock:"; sed -n 's/^\[mock-llm\] //p' "$WORK/server.log" | grep -v listening
echo "passed: $passed, failed: $failed"
[[ "$failed" -eq 0 ]]
