#!/usr/bin/env bash
# Tests for the `atc` wrapper script: release-metadata parsing, checksum
# verification, cache checks, startup updates, dispatch and start.sh environment loading.
# No network or Java required.
#
#   bash tests/atc_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WRAPPER="$REPO_ROOT/atc"

TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT

# Point every location the wrapper touches at the temp dir before sourcing it.
export HOME="$TEST_TMP/home"
export ATC_CACHE_DIR="$TEST_TMP/cache"
export ATC_INSTALL_DIR="$TEST_TMP/bin"
mkdir -p "$HOME"

# shellcheck source=../atc
source "$WRAPPER" # the guarded main does not run

FIXTURE_JSON="$(cat "$SCRIPT_DIR/fixtures/release.json")"

pass_count=0
fail_count=0

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  PASS: $label"; pass_count=$((pass_count + 1))
  else
    echo "  FAIL: $label"; echo "    expected: $(printf '%q' "$expected")"; echo "    actual:   $(printf '%q' "$actual")"
    fail_count=$((fail_count + 1))
  fi
}

assert_contains() {
  local label="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "  PASS: $label"; pass_count=$((pass_count + 1))
  else
    echo "  FAIL: $label"; echo "    expected to contain: $needle"; echo "    in: $haystack"
    fail_count=$((fail_count + 1))
  fi
}

# Run in a subshell; pass when it exits non-zero (fail paths call exit).
assert_fails() {
  local label="$1"; shift
  local rc=0
  ("$@") >/dev/null 2>&1 || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    echo "  PASS: $label (exit $rc)"; pass_count=$((pass_count + 1))
  else
    echo "  FAIL: $label (expected non-zero exit, got 0)"; fail_count=$((fail_count + 1))
  fi
}

assert_succeeds() {
  local label="$1"; shift
  local rc=0
  ("$@") >/dev/null 2>&1 || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "  PASS: $label"; pass_count=$((pass_count + 1))
  else
    echo "  FAIL: $label (exit $rc)"; fail_count=$((fail_count + 1))
  fi
}

# stderr of a command run in a subshell (for messages of fail paths).
stderr_of() {
  ("$@" 2>&1 >/dev/null) || true
}

# A curl stub that "downloads" a release asset by copying it from $ASSETS_DIR
# (set by each download test before use). Parses whatever flags the wrapper's
# real curl invocation passes, so it stays in step with one edit, not several.
fake_asset_curl() { # <flags> <url> -o <file>
  local url="" out=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -o) out="$2"; shift 2 ;;
      http*) url="$1"; shift ;;
      *) shift ;;
    esac
  done
  cp "$ASSETS_DIR/$(basename "$url")" "$out"
}

APP_URL="https://github.com/noti0na1/atc/releases/download/v0.2.0/atc.jar"
LIB_URL="https://github.com/noti0na1/atc/releases/download/v0.2.0/atc-lib.jar"
APP_DIGEST="sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
LIB_DIGEST="sha256:fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"

# ---------------------------------------------------------------------------
echo "--- release metadata ---"

assert_eq "release id" "12345678" "$(extract_release_id "$FIXTURE_JSON")"
assert_eq "tag_name" "v0.2.0" "$(extract_release_field "$FIXTURE_JSON" tag_name)"
assert_eq "release key" "12345678|v0.2.0" "$(release_key_from_json "$FIXTURE_JSON")"
assert_eq "tag of key" "v0.2.0" "$(release_tag_of_key "12345678|v0.2.0")"
assert_fails "release key fails without tag" release_key_from_json '{"id": 1}'

# The grep fallback: order as printed (app first, then lib).
grep_assets="$(extract_assets_grep "$FIXTURE_JSON")"
assert_eq "grep: two lines" "2" "$(printf '%s\n' "$grep_assets" | wc -l | tr -d ' ')"
assert_eq "grep: app line" "$(printf 'atc.jar\t%s\t%s' "$APP_URL" "$APP_DIGEST")" "$(printf '%s\n' "$grep_assets" | sed -n 1p)"
assert_eq "grep: lib line" "$(printf 'atc-lib.jar\t%s\t%s' "$LIB_URL" "$LIB_DIGEST")" "$(printf '%s\n' "$grep_assets" | sed -n 2p)"
assert_fails "grep: fails when atc.jar is missing" extract_assets_grep "${FIXTURE_JSON//\"atc.jar\"/\"other.jar\"}"
assert_fails "grep: fails when atc-lib.jar is missing" extract_assets_grep "${FIXTURE_JSON//\"atc-lib.jar\"/\"other.jar\"}"

# The grep fallback must not need the digest (it may be absent).
no_digest_json="$(printf '%s' "$FIXTURE_JSON" | grep -v '"digest"')"
assert_eq "grep: empty digest when absent" "$(printf 'atc.jar\t%s\t' "$APP_URL")" "$(extract_assets_grep "$no_digest_json" | sed -n 1p)"

if command -v jq >/dev/null 2>&1; then
  jq_assets="$(extract_assets_jq "$FIXTURE_JSON")"
  assert_eq "jq: two lines" "2" "$(printf '%s\n' "$jq_assets" | wc -l | tr -d ' ')"
  assert_contains "jq: app line" "$(printf 'atc.jar\t%s\t%s' "$APP_URL" "$APP_DIGEST")" "$jq_assets"
  assert_contains "jq: lib line" "$(printf 'atc-lib.jar\t%s\t%s' "$LIB_URL" "$LIB_DIGEST")" "$jq_assets"
  assert_fails "jq: fails when atc.jar is missing" extract_assets_jq "${FIXTURE_JSON//\"atc.jar\"/\"other.jar\"}"
  assert_fails "jq: fails on invalid JSON" extract_assets_jq '{not json'
  # Both parsers agree, line order aside.
  assert_eq "jq and grep agree" "$(printf '%s\n' "$grep_assets" | sort)" "$(printf '%s\n' "$jq_assets" | sort)"
else
  echo "  SKIP: jq not installed"
fi

# ---------------------------------------------------------------------------
echo "--- startup update versions and eligibility ---"

assert_succeeds "new patch release" release_is_newer v1.2.4 v1.2.3
assert_succeeds "version comparison is numeric" release_is_newer v0.10.0 v0.9.9
assert_succeeds "new major release" release_is_newer 2.0.0 v1.99.99
assert_succeeds "version digits are decimal" release_is_newer v1.2.09 v1.2.08
assert_fails "same version with or without v is not newer" release_is_newer v1.2.3 1.2.3
assert_fails "older releases are not offered" release_is_newer v1.2.9 v1.3.0
assert_fails "unknown installed version is not upgraded automatically" release_is_newer v1.2.3 dev
assert_fails "prereleases are not offered" release_is_newer v2.0.0-RC1 v1.0.0
assert_fails "invalid release tags are ignored" release_is_newer $'v1.2.4\nother' v1.2.3

interactive_update_check() (
  update_terminal_available() { return 0; }
  should_check_update "$@"
)
assert_succeeds "interactive launch checks for updates" interactive_update_check
assert_succeeds "working directory and model flags still check" interactive_update_check -C /work -m model
assert_succeeds "option values are not mistaken for flags" interactive_update_check -m --version
for flag in -p --prompt -h --help -v --version --init --init-global; do
  assert_fails "startup skips $flag" interactive_update_check "$flag"
done
assert_fails "startup skips an incomplete option" interactive_update_check -C

echo "--- checkout launcher ---"

start_dir="$TEST_TMP/start"
mkdir -p "$start_dir"
cat > "$start_dir/java" <<'JAVA'
#!/bin/sh
printf 'arg=<%s>\n' "$@"
printf 'loaded=<%s> kept=<%s> malformed=<%s> literal=<%s>\n' \
  "${ATC_TEST_LOADED-}" "${ATC_TEST_KEPT-}" "${ATC_TEST_MALFORMED-}" "${ATC_TEST_LITERAL-}"
JAVA
chmod +x "$start_dir/java"
printf '%s\r\n' 'ATC_TEST_LOADED="from file"' 'ATC_TEST_KEPT=file' \
  'ATC_TEST_MALFORMED' 'ATC_TEST_LITERAL=$(must_stay_literal)' > "$start_dir/test.env"
start_output="$(env -i PATH="$start_dir:/usr/bin:/bin" ATC_ENV_FILE="$start_dir/test.env" \
  ATC_SKIP_BUILD=1 ATC_TEST_KEPT=exported /bin/sh "$REPO_ROOT/start.sh" -p 'quoted "prompt"')"
assert_contains "start.sh reads CRLF and preserves exported values" \
  'loaded=<from file> kept=<exported> malformed=<> literal=<$(must_stay_literal)>' "$start_output"
assert_contains "start.sh preserves argument boundaries" 'arg=<quoted "prompt">' "$start_output"

echo "--- java version ---"

assert_eq "JDK 21" "21" "$(java_major_from_line 'openjdk version "21.0.1" 2023-10-17')"
assert_eq "JDK 17 short" "17" "$(java_major_from_line 'openjdk version "17" 2021-09-14')"
assert_eq "JDK 17.0.2" "17" "$(java_major_from_line 'openjdk version "17.0.2" 2022-01-18')"
assert_eq "legacy 1.8" "8" "$(java_major_from_line 'java version "1.8.0_292"')"
assert_fails "unparseable" java_major_from_line 'something weird'

# ---------------------------------------------------------------------------
echo "--- checksums ---"

if have_sha256_tool; then
  sample="$TEST_TMP/sample.jar"
  printf 'hello atc\n' > "$sample"
  sample_hex="$(sha256_of "$sample")"
  assert_succeeds "matching digest verifies" verify_jar "$sample" "sha256:${sample_hex}"
  assert_succeeds "uppercase hexadecimal digest verifies" verify_jar "$sample" \
    "sha256:$(printf '%s' "$sample_hex" | tr 'a-f' 'A-F')"
  assert_fails "mismatching digest fails" verify_jar "$sample" "sha256:$(printf '0%.0s' $(seq 1 64))"
  assert_fails "empty digest fails closed" verify_jar "$sample" ""
  assert_fails "unknown digest format fails" verify_jar "$sample" "md5:abc"
  assert_contains "mismatch message names the file" "Checksum mismatch for sample.jar" \
    "$(stderr_of verify_jar "$sample" "sha256:$(printf '0%.0s' $(seq 1 64))")"

  # cached_jars_match_digests reads jars from CACHE_DIR
  mkdir -p "$CACHE_DIR"
  printf 'app\n' > "$APP_JAR"
  printf 'lib\n' > "$LIB_JAR"
  good_info="$(printf 'atc.jar\t%s\tsha256:%s\natc-lib.jar\t%s\tsha256:%s\n' \
    "$APP_URL" "$(sha256_of "$APP_JAR")" "$LIB_URL" "$(sha256_of "$LIB_JAR")")"
  bad_info="$(printf 'atc.jar\t%s\tsha256:%s\natc-lib.jar\t%s\tsha256:%s\n' \
    "$APP_URL" "$(sha256_of "$LIB_JAR")" "$LIB_URL" "$(sha256_of "$LIB_JAR")")"
  partial_info="$(printf 'atc.jar\t%s\t\natc-lib.jar\t%s\tsha256:%s\n' "$APP_URL" "$LIB_URL" "$(sha256_of "$LIB_JAR")")"
  assert_succeeds "cache matches its digests" cached_jars_match_digests "$good_info"
  upper_info="$(printf '%s' "$good_info" | awk -F '\t' 'BEGIN { OFS="\t" } { sub(/^sha256:/, "", $3); $3="sha256:" toupper($3); print }')"
  assert_succeeds "cache accepts uppercase hexadecimal digests" cached_jars_match_digests "$upper_info"
  assert_fails "cache with a changed jar does not match" cached_jars_match_digests "$bad_info"
  assert_fails "an asset without a digest makes the cache untrusted (fail closed)" cached_jars_match_digests "$partial_info"
  rm -f "$APP_JAR"
  assert_fails "missing cached jar does not match" cached_jars_match_digests "$good_info"
  rm -rf "$CACHE_DIR"
else
  echo "  SKIP: no sha256 tool"
fi

# Without any sha256 tool the cache cannot be verified: 'cannot verify' (exit 2),
# not 'verified' — the caller then keeps a matching install rather than deleting it.
no_sha256_cache() {
  have_sha256_tool() { return 1; }
  cached_jars_match_digests "atc.jar	x	sha256:whatever"
  [[ $? -eq 2 ]]
}
assert_succeeds "no sha256 tool: cache is 'cannot verify' (exit 2)" no_sha256_cache

# ---------------------------------------------------------------------------
echo "--- cache marker ---"

assert_fails "no cache: no match" cached_release_matches "12345678|v0.2.0"
mkdir -p "$CACHE_DIR"
printf 'app\n' > "$APP_JAR"
printf 'lib\n' > "$LIB_JAR"
assert_fails "jars but no marker: no match" cached_release_matches "12345678|v0.2.0"
printf '12345678|v0.2.0\n' > "$RELEASE_MARKER"
assert_succeeds "same release: match" cached_release_matches "12345678|v0.2.0"
assert_fails "other release: no match" cached_release_matches "12345679|v0.3.0"
rm -f "$LIB_JAR"
assert_fails "missing lib jar: no match" cached_release_matches "12345678|v0.2.0"
rm -rf "$CACHE_DIR"

# ---------------------------------------------------------------------------
echo "--- PATH snippet ---"

snippet="$(path_snippet)"
assert_contains "snippet has begin marker" "$PATH_MARKER_BEGIN" "$snippet"
assert_contains "snippet has end marker" "$PATH_MARKER_END" "$snippet"
assert_contains "snippet exports PATH" 'export PATH="$HOME/.local/bin:$PATH"' "$snippet"
assert_contains "zsh profile candidates" ".zshrc" "$(SHELL=/bin/zsh profile_candidates)"
assert_contains "bash profile candidates" ".bashrc" "$(SHELL=/bin/bash profile_candidates)"
assert_contains "other shells use .profile" ".profile" "$(SHELL=/usr/bin/fish profile_candidates)"

profile="$HOME/.zshrc"
printf '# my zshrc\n' > "$profile"
(SHELL=/bin/zsh update_profile_path >/dev/null)
assert_contains "setup appends the snippet" "$PATH_MARKER_BEGIN" "$(cat "$profile")"
(SHELL=/bin/zsh update_profile_path >/dev/null)
assert_eq "second setup does not duplicate it" "1" "$(grep -c -F "$PATH_MARKER_BEGIN" "$profile")"
(SHELL=/bin/zsh remove_profile_path >/dev/null)
assert_eq "uninstall restores the profile" "# my zshrc" "$(cat "$profile")"

# ---------------------------------------------------------------------------
echo "--- locations ---"

assert_eq "jars default to ~/.atc/jars" "/h/.atc/jars" \
  "$(env -u ATC_CACHE_DIR HOME=/h bash -c "source '$WRAPPER'; printf '%s' \"\$CACHE_DIR\"")"
assert_eq "wrapper defaults to ~/.local/bin/atc" "/h/.local/bin/atc" \
  "$(env -u ATC_INSTALL_DIR HOME=/h bash -c "source '$WRAPPER'; printf '%s' \"\$INSTALL_PATH\"")"

# Uninstall with the default layout removes ~/.atc/jars but keeps the config
# and keys that live beside it.
(
  export ATC_CACHE_DIR="$HOME/.atc/jars"
  source "$WRAPPER"
  mkdir -p "$CACHE_DIR" "$INSTALL_DIR"
  printf 'app\n' > "$APP_JAR"
  printf '{}\n' > "$HOME/.atc/config.json"
  printf 'KEY=x\n' > "$HOME/.atc/keys.properties"
  printf '#!/bin/sh\n' > "$INSTALL_PATH"
  mkdir -p "$STARTUP_CACHE_DIR"
  printf 'cache\n' > "$STARTUP_CACHE_DIR/atc.aot"
  printf 'key\n' > "$STARTUP_CACHE_KEY"
  cmd_uninstall >/dev/null
  [[ ! -e "$CACHE_DIR" && ! -e "$INSTALL_PATH" && -f "$HOME/.atc/config.json" && -f "$HOME/.atc/keys.properties" ]]
) && echo "  PASS: uninstall removes ~/.atc/jars and keeps config.json/keys.properties" && pass_count=$((pass_count + 1)) \
  || { echo "  FAIL: uninstall removes ~/.atc/jars and keeps config.json/keys.properties"; fail_count=$((fail_count + 1)); }
rm -rf "$HOME/.atc"

# ---------------------------------------------------------------------------
echo "--- dispatch ---"

assert_contains "help prints usage" "atc setup" "$(main help)"
assert_contains "--help prints wrapper usage" "atc self update" "$(main --help)"
assert_contains "self help" "atc self uninstall" "$(main self)"
assert_fails "unknown command fails" main bogus
assert_contains "unknown command names it" "Unknown command: bogus" "$(stderr_of main bogus)"
assert_fails "unknown self command fails" main self bogus
assert_fails "setup rejects extra arguments" main setup extra
assert_fails "update rejects extra arguments" main update extra
assert_fails "self update rejects extra arguments" main self update extra

# Running without cached jars: every run form fails with the setup hint,
# before Java is even looked for.
assert_fails "run without jars fails" main run
assert_fails "bare invocation without jars fails" main
assert_fails "flags without jars fail" main -C "$TEST_TMP"
assert_contains "run hint mentions setup" "Run 'atc setup'" "$(stderr_of main -C "$TEST_TMP")"

# With jars cached, `atc <args>` execs java with the lib classpath and the args.
mkdir -p "$CACHE_DIR" "$TEST_TMP/mockbin"
printf 'app\n' > "$APP_JAR"
printf 'lib\n' > "$LIB_JAR"
cat > "$TEST_TMP/mockbin/java" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "-version" ]]; then echo 'openjdk version "17.0.2" 2022-01-18' >&2; exit 0; fi
printf '%s\n' "$@"
EOF
chmod +x "$TEST_TMP/mockbin/java"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main -C /work -p 'hi there')"
assert_contains "run passes the lib classpath" "-Datc.lib.classpath=$LIB_JAR" "$run_out"
assert_contains "run uses the app jar" "$APP_JAR" "$run_out"
assert_contains "run forwards flags" $'-C\n/work\n-p\nhi there' "$run_out"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main run --version)"
assert_contains "run subcommand forwards flags" "--version" "$run_out"
DEFAULT_FLAGS=$'-Xms256m\n-Xmx2g\n-Xss4m\n-XX:-UsePerfData'
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" ATC_JAVA_OPTS="-Xmx3g -Dfoo=bar" main)"
assert_contains "ATC_JAVA_OPTS is applied after the JVM defaults" "$DEFAULT_FLAGS"$'\n-Xmx3g\n-Dfoo=bar' "$run_out"

# -Xmx/-Xms among the arguments go to java, in order, and are not forwarded to ATC.
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main -Xmx4g -C /work -Xms512m)"
assert_contains "-Xmx/-Xms go to java after the defaults, before the app flags" \
  "$DEFAULT_FLAGS"$'\n-Xmx4g\n-Xms512m\n-Dfile.encoding=UTF-8' "$run_out"
assert_contains "-Xmx/-Xms keep the other flags" $'-jar\n'"$APP_JAR"$'\n-C\n/work' "$run_out"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main run --version -Xmx512m)"
assert_contains "run subcommand takes -Xmx after other flags" $'-Xmx512m\n-Dfile.encoding=UTF-8' "$run_out"
assert_contains "run subcommand keeps the other flags" "--version" "$run_out"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" ATC_JAVA_OPTS="-Xmx3g" main -Xmx4g)"
assert_contains "-Xmx comes after the defaults and ATC_JAVA_OPTS" "$DEFAULT_FLAGS"$'\n-Xmx3g\n-Xmx4g' "$run_out"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main)"
assert_eq "no heap flag leaves only the defaults" $'-Xms256m\n-Xmx2g\n-Xss4m' "$(printf '%s\n' "$run_out" | grep -- '-X[ms]' || true)"
for bad in -Xmx -Xmxlots -Xms4gg -Xmx=4g; do
  assert_fails "$bad is refused" env PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main $bad -C /work"
  assert_contains "$bad message" "Invalid JVM heap size '$bad'" \
    "$(env PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main $bad -C /work" 2>&1 >/dev/null || true)"
done

# ---------------------------------------------------------------------------
echo "--- startup cache: AOT cache (Java 25+) / CDS archive (Java 19-24), keyed by jars + JDK ---"

# A java mock that reports the given version, logs every invocation to $JAVA_CALLS, and
# creates the cache file named by the creating flag (unless told not to).
mock_java() { # $1 = version line, $2 = creates|fails
cat > "$TEST_TMP/mockbin/java" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "-version" ]]; then echo '$1' >&2; echo 'OpenJDK Runtime Environment (build test)' >&2; exit 0; fi
printf '%s\n' "\$@" >> "\$JAVA_CALLS"; echo '---' >> "\$JAVA_CALLS"
for a in "\$@"; do
  case "\$a" in -XX:AOTCacheOutput=*|-XX:ArchiveClassesAtExit=*) [[ '$2' == creates ]] && echo cache > "\${a#*=}" ;; esac
done
printf '%s\n' "\$@"
EOF
chmod +x "$TEST_TMP/mockbin/java"
}
export JAVA_CALLS="$TEST_TMP/java-calls.log"
java_calls() { grep -c '^---$' "$JAVA_CALLS" 2>/dev/null || echo 0; }
STARTUP_DIR="$CACHE_DIR/startup"
rm -rf "$STARTUP_DIR"; : > "$JAVA_CALLS"

mock_java 'openjdk version "25.0.4" 2026-07-21 LTS' creates
run_err="$(PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main -C /work" 2>&1 >/dev/null)"
assert_contains "first run announces the cache build" "preparing the JVM startup cache" "$run_err"
assert_eq "first run = training run + real run" "2" "$(java_calls)"
training="$(awk 'BEGIN{RS="---\n"} NR==1' "$JAVA_CALLS")"
assert_contains "training run creates the AOT cache" "-XX:AOTCacheOutput=$STARTUP_DIR/atc.aot" "$training"
assert_contains "training run is one echo-model prompt turn" $'-p\nrun: 1 + 1' "$training"
assert_contains "training run uses the JVM defaults" "$DEFAULT_FLAGS"$'\n--sun-misc-unsafe-memory-access=allow' "$training"
assert_contains "training run uses a throwaway config and cwd" "/train.json" "$training"
real="$(awk 'BEGIN{RS="---\n"} NR==2' "$JAVA_CALLS")"
assert_contains "real run uses the AOT cache after the defaults" \
  "$DEFAULT_FLAGS"$'\n--sun-misc-unsafe-memory-access=allow\n-XX:AOTCache='"$STARTUP_DIR/atc.aot"$'\n-Dfile.encoding' "$real"
assert_contains "real run keeps the app flags" $'-C\n/work' "$real"
assert_eq "training left no throwaway config behind" "" "$(ls "${TMPDIR:-/tmp}" | grep 'atc-startup-cache' || true)"
assert_contains "key records the JDK" 'openjdk version "25.0.4"' "$(cat "$STARTUP_DIR/key.txt")"

(PATH="$TEST_TMP/mockbin:$PATH" main) >/dev/null 2>&1
assert_eq "second run reuses the cache" "3" "$(java_calls)"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" ATC_JAVA_OPTS="-Dfoo=bar" main -Xmx4g)"
assert_contains "cache flag precedes ATC_JAVA_OPTS and the command line" $'-XX:AOTCache='"$STARTUP_DIR/atc.aot"$'\n-Dfoo=bar\n-Xmx4g' "$run_out"

touch -t 203501010000 "$APP_JAR"
(PATH="$TEST_TMP/mockbin:$PATH" main) >/dev/null 2>&1
assert_eq "a newer jar rebuilds the cache" "6" "$(java_calls)"
(PATH="$TEST_TMP/mockbin:$PATH" main) >/dev/null 2>&1
assert_eq "a future-dated jar does not rebuild it again" "7" "$(java_calls)"

mock_java 'openjdk version "25.0.5" 2026-10-21 LTS' creates
(PATH="$TEST_TMP/mockbin:$PATH" main) >/dev/null 2>&1
assert_eq "a different JDK rebuilds the cache" "9" "$(java_calls)"

run_out="$(PATH="$TEST_TMP/mockbin:$PATH" ATC_STARTUP_CACHE=0 main)"
assert_eq "ATC_STARTUP_CACHE=0 runs without the cache" "" "$(printf '%s\n' "$run_out" | grep -- 'AOTCache' || true)"
assert_eq "ATC_STARTUP_CACHE=0 does not train" "10" "$(java_calls)"

rm -rf "$STARTUP_DIR"
mock_java 'openjdk version "21.0.4" 2024-07-16 LTS' creates
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main)"
assert_contains "Java 21 trains a CDS archive" "-XX:ArchiveClassesAtExit=$STARTUP_DIR/atc.jsa" "$(awk 'BEGIN{RS="---\n"} NR==11' "$JAVA_CALLS")"
assert_contains "Java 21 runs with the CDS archive" "-XX:SharedArchiveFile=$STARTUP_DIR/atc.jsa" "$run_out"
assert_eq "Java 21 gets no Unsafe opt-in (Java 23+ only)" "" "$(printf '%s\n' "$run_out" | grep -- 'sun-misc-unsafe' || true)"

rm -rf "$STARTUP_DIR"
mock_java 'openjdk version "17.0.2" 2022-01-18' creates
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main)"
assert_eq "Java 17 has no startup cache" "" "$(printf '%s\n' "$run_out" | grep -- 'AOTCache\|SharedArchiveFile' || true)"
assert_eq "Java 17 does not train" "13" "$(java_calls)"
assert_eq "Java 17 gets no Unsafe opt-in" "" "$(printf '%s\n' "$run_out" | grep -- 'sun-misc-unsafe' || true)"

rm -rf "$STARTUP_DIR"
mock_java 'openjdk version "25.0.4" 2026-07-21 LTS' fails
run_err="$(PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main" 2>&1 >/dev/null)"
assert_contains "a failed build is reported" "could not build the startup cache" "$run_err"
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" main)"
assert_eq "a failed build runs without the cache" "" "$(printf '%s\n' "$run_out" | grep -- 'AOTCache' || true)"
assert_eq "a failed build is not retried until the key changes" "16" "$(java_calls)"
rm -rf "$STARTUP_DIR"

cat > "$TEST_TMP/mockbin/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "11.0.2" 2019-01-15' >&2
EOF
assert_fails "old Java is refused" env PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main"
assert_contains "old Java message" "Java 17+ is required. Found Java 11" \
  "$(env PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main" 2>&1 >/dev/null || true)"

# ---------------------------------------------------------------------------
echo "--- dev: a checkout's local build in place of the release ---"

# Java 17 again (the section above left the old-Java mock in place).
cat > "$TEST_TMP/mockbin/java" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "-version" ]]; then echo 'openjdk version "17.0.2" 2022-01-18' >&2; exit 0; fi
printf '%s\n' "$@"
EOF
chmod +x "$TEST_TMP/mockbin/java"
export PATH="$TEST_TMP/mockbin:$PATH"

CHECKOUT="$TEST_TMP/checkout"
DIST="$CHECKOUT/out/dist.dest"
mkdir -p "$CHECKOUT/app/src" "$CHECKOUT/lib/src" "$DIST"
printf 'object build\n' > "$CHECKOUT/build.mill"
rm -rf "$CACHE_DIR"

assert_contains "help mentions dev" "atc dev <checkout>" "$(main help)"
assert_fails "dev without a path fails" main dev
assert_contains "dev without a path says what it needs" "atc dev <checkout>" "$(stderr_of main dev)"
assert_fails "dev rejects extra arguments" main dev "$CHECKOUT" extra
assert_fails "dev rejects a missing directory" main dev "$TEST_TMP/nope"
assert_fails "dev needs a build" main dev "$CHECKOUT"
assert_contains "missing build names the dist dir" "no local build in $DIST" "$(stderr_of main dev "$CHECKOUT")"
assert_fails "dev without a build leaves no cache behind" test -e "$CACHE_DIR"

printf 'dev app\n' > "$DIST/atc.jar"
printf 'dev lib\n' > "$DIST/atc-lib.jar"
out="$(cd "$TEST_TMP" && main dev "$CHECKOUT")"
assert_contains "dev says what it installed" "Installed the local build from $DIST" "$out"
assert_eq "dev copies the app jar" "dev app" "$(cat "$APP_JAR")"
assert_eq "dev copies the lib jar" "dev lib" "$(cat "$LIB_JAR")"
assert_eq "dev marks the cache with the checkout" "dev|$CHECKOUT" "$(cat "$RELEASE_MARKER")"
assert_eq "dev_source reads the marker" "$CHECKOUT" "$(dev_source)"
(cd "$CHECKOUT" && main dev . >/dev/null)
assert_eq "a relative path is stored absolute" "dev|$CHECKOUT" "$(cat "$RELEASE_MARKER")"

run_out="$(main -C /work 2>/dev/null)"
assert_contains "atc runs the copied jars" $'-jar\n'"$APP_JAR" "$run_out"
assert_contains "atc says it runs a local build" "running the local build from $CHECKOUT" "$(stderr_of main -C /work)"

# A build older than the sources is noted, not refused.
touch -t 202001010000 "$DIST/atc.jar" "$DIST/atc-lib.jar"
assert_contains "stale build is noted" "sources in $CHECKOUT changed since this build" "$(stderr_of main dev "$CHECKOUT")"
assert_eq "stale build is still installed" "dev app" "$(cat "$APP_JAR")"

# 'atc update' treats the dev marker as "not the latest release" and restores it.
if have_sha256_tool; then
  ASSETS_DIR="$TEST_TMP/assets-dev"
  mkdir -p "$ASSETS_DIR"
  printf 'released app\n' > "$ASSETS_DIR/atc.jar"
  printf 'released lib\n' > "$ASSETS_DIR/atc-lib.jar"
  RELEASE_JSON_OVERRIDE="$(printf '%s' "$FIXTURE_JSON" \
    | sed -e "s/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890/$(sha256_of "$ASSETS_DIR/atc.jar")/" \
          -e "s/fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321/$(sha256_of "$ASSETS_DIR/atc-lib.jar")/")"
  release_json() { printf '%s\n' "$RELEASE_JSON_OVERRIDE"; }
  curl() { fake_asset_curl "$@"; }
  out="$(main update)"
  assert_contains "update downloads the release over a dev install" "Downloading ATC v0.2.0" "$out"
  assert_eq "update restores the released app jar" "released app" "$(cat "$APP_JAR")"
  assert_eq "update restores the release marker" "12345678|v0.2.0" "$(cat "$RELEASE_MARKER")"
  assert_eq "no dev source after update" "" "$(dev_source)"
  assert_eq "no local-build notice after update" "" "$(stderr_of main -C /work | grep 'local build' || true)"
  unset -f release_json curl
fi
rm -rf "$CACHE_DIR" "$CHECKOUT"

# ---------------------------------------------------------------------------
echo "--- download flow (stubbed GitHub) ---"

if have_sha256_tool; then
  rm -rf "$CACHE_DIR"
  # Serve the release: the JSON from a variable, the assets from local files.
  # jars are downloaded from ATC_TEST_ASSETS/<name>; digests come from RELEASE_JSON_OVERRIDE.
  ASSETS_DIR="$TEST_TMP/assets"
  mkdir -p "$ASSETS_DIR"
  printf 'the app jar\n' > "$ASSETS_DIR/atc.jar"
  printf 'the lib jar\n' > "$ASSETS_DIR/atc-lib.jar"
  make_release_json() { # $1 = id, $2 = tag, $3 = app digest hex, $4 = lib digest hex
    printf '%s' "$FIXTURE_JSON" \
      | sed -e "s/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890/$3/" \
            -e "s/fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321/$4/" \
            -e "s/\"id\": 12345678,/\"id\": $1,/" -e "s/v0\.2\.0/$2/g"
  }
  RELEASE_JSON_OVERRIDE="$(make_release_json 1001 v1.0.0 "$(sha256_of "$ASSETS_DIR/atc.jar")" "$(sha256_of "$ASSETS_DIR/atc-lib.jar")")"
  release_json() { printf '%s\n' "$RELEASE_JSON_OVERRIDE"; }
  curl() { fake_asset_curl "$@"; }

  out="$(download_latest_release)"
  assert_contains "downloads the release" "Downloading ATC v1.0.0" "$out"
  assert_contains "verifies both jars" "atc-lib.jar: verified (sha256)" "$out"
  assert_eq "installs the app jar" "the app jar" "$(cat "$APP_JAR")"
  assert_eq "installs the lib jar" "the lib jar" "$(cat "$LIB_JAR")"
  assert_eq "writes the marker" "1001|v1.0.0" "$(cat "$RELEASE_MARKER")"
  assert_eq "no temp dir left behind" "" "$(ls -d "$CACHE_DIR"/download.* 2>/dev/null || true)"

  out="$(download_latest_release)"
  assert_contains "second run is a no-op" "v1.0.0 is already up to date" "$out"

  # A tampered cached jar is re-downloaded.
  printf 'tampered\n' > "$APP_JAR"
  out="$(download_latest_release)"
  assert_contains "tampered cache is re-downloaded" "failed checksum verification; downloading again" "$out"
  assert_eq "tampered cache is repaired" "the app jar" "$(cat "$APP_JAR")"

  # A new release replaces the cache.
  printf 'the app jar v2\n' > "$ASSETS_DIR/atc.jar"
  RELEASE_JSON_OVERRIDE="$(make_release_json 1002 v2.0.0 "$(sha256_of "$ASSETS_DIR/atc.jar")" "$(sha256_of "$ASSETS_DIR/atc-lib.jar")")"
  out="$(download_latest_release)"
  assert_contains "new release is downloaded" "Downloading ATC v2.0.0" "$out"
  assert_eq "new release replaces the app jar" "the app jar v2" "$(cat "$APP_JAR")"
  assert_eq "marker moves to the new release" "1002|v2.0.0" "$(cat "$RELEASE_MARKER")"

  # A release whose digest does not match the download is refused, and the
  # previously installed jars stay in place.
  RELEASE_JSON_OVERRIDE="$(make_release_json 1003 v3.0.0 "$(printf '1%.0s' $(seq 1 64))" "$(sha256_of "$ASSETS_DIR/atc-lib.jar")")"
  assert_fails "bad digest refuses the release" download_latest_release
  assert_contains "bad digest names the mismatch" "Checksum mismatch for atc.jar" "$(stderr_of download_latest_release)"
  assert_eq "previous jars are kept" "the app jar v2" "$(cat "$APP_JAR")"
  assert_eq "previous marker is kept" "1002|v2.0.0" "$(cat "$RELEASE_MARKER")"
  assert_eq "no temp dir left after failure" "" "$(ls -d "$CACHE_DIR"/download.* 2>/dev/null || true)"

  # A release without digests is refused (fail closed).
  RELEASE_JSON_OVERRIDE="$(make_release_json 1004 v4.0.0 x x | grep -v '"digest"')"
  assert_fails "missing digest refuses the release" download_latest_release
  assert_contains "missing digest message" "refusing to install an unverified jar" "$(stderr_of download_latest_release)"

  unset -f release_json curl
  rm -rf "$CACHE_DIR"
else
  echo "  SKIP: no sha256 tool"
fi

# 'atc update' with a matching cache but NO sha256 tool must KEEP the working
# install, not delete it and then fail the re-download's own verification.
no_tool_keeps_matching_cache() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/notool-cache"
    export ATC_INSTALL_DIR="$TEST_TMP/notool-bin"
    source "$WRAPPER"
    mkdir -p "$CACHE_DIR"
    release_json() { printf '%s\n' "$FIXTURE_JSON"; }
    have_sha256_tool() { return 1; } # pretend no sha256sum/shasum on PATH
    local key
    key="$(release_key_from_json "$FIXTURE_JSON")"
    printf 'app\n' > "$APP_JAR"
    printf 'lib\n' > "$LIB_JAR"
    printf '%s\n' "$key" > "$RELEASE_MARKER" # the cache matches the latest release
    local out
    out="$(download_latest_release 2>&1)"
    [[ -f "$APP_JAR" && -f "$LIB_JAR" ]] || { echo "jars were deleted: $out"; return 1; }
    printf '%s' "$out" | grep -q "keeping the existing install" || { echo "no keep message: $out"; return 1; }
  )
}
assert_succeeds "update without a sha256 tool keeps a matching install" no_tool_keeps_matching_cache

# Exercise the same status-2 branch in a fresh `bash -e`: calling a function
# from assert_succeeds's `||` context suppresses errexit inside that function
# and would otherwise hide an unsafe bare status capture.
errexit_script="$TEST_TMP/errexit-update.sh"
cat > "$errexit_script" <<'EOF'
set -euo pipefail
export HOME="$ERREXIT_TMP/home"
export ATC_CACHE_DIR="$ERREXIT_TMP/cache"
export ATC_INSTALL_DIR="$ERREXIT_TMP/bin"
source "$WRAPPER_UNDER_TEST"
mkdir -p "$CACHE_DIR"
FIXTURE_JSON="$(cat "$FIXTURE_PATH")"
release_json() { printf '%s\n' "$FIXTURE_JSON"; }
have_sha256_tool() { return 1; }
key="$(release_key_from_json "$FIXTURE_JSON")"
printf 'app\n' > "$APP_JAR"
printf 'lib\n' > "$LIB_JAR"
printf '%s\n' "$key" > "$RELEASE_MARKER"
download_latest_release > "$ERREXIT_TMP/output" 2>&1
grep -q 'keeping the existing install' "$ERREXIT_TMP/output"
EOF
assert_succeeds "matching-cache status handling works under bash -e" \
  env ERREXIT_TMP="$TEST_TMP/errexit" WRAPPER_UNDER_TEST="$WRAPPER" FIXTURE_PATH="$SCRIPT_DIR/fixtures/release.json" \
  bash -e "$errexit_script"

# ---------------------------------------------------------------------------
echo "--- hardening: hostile JSON, quoting, guards, self update ---"

# Fake asset tokens in the free-text release body must not override the real
# assets in the grep fallback (first match wins: the body follows the assets).
fake_tokens='\"name\": \"atc.jar\", \"digest\": \"sha256:1111111111111111111111111111111111111111111111111111111111111111\", \"browser_download_url\": \"http://evil.example/atc.jar\"'
hostile_json="${FIXTURE_JSON/Release notes/$fake_tokens}"
hostile_out="$(extract_assets_grep "$hostile_json")"
assert_contains "hostile body: real app URL wins" "$APP_URL" "$hostile_out"
assert_contains "hostile body: real app digest wins" "$APP_DIGEST" "$hostile_out"
assert_eq "hostile body: evil URL absent" "0" "$(printf '%s' "$hostile_out" | grep -c 'evil.example' || true)"
if command -v jq >/dev/null 2>&1; then
  assert_eq "hostile body: jq unaffected" "$jq_assets" "$(extract_assets_jq "$hostile_json")"
fi

# A quote in ATC_CACHE_DIR must not break the cleanup trap into an arbitrary rm -rf.
trap_injection_safe() {
  local sentinel="$TEST_TMP/victim"
  mkdir -p "$sentinel"
  printf 'keep\n' > "$sentinel/file"
  (
    # shellcheck disable=SC2088
    export ATC_CACHE_DIR="$TEST_TMP/x' $sentinel #"
    source "$WRAPPER"
    mkdir -p "$CACHE_DIR"
    release_json() { printf '%s\n' "$FIXTURE_JSON"; }
    curl() { return 1; } # the download fails; fail() exits through the EXIT trap
    download_latest_release >/dev/null 2>&1
  )
  [[ -f "$sentinel/file" ]]
}
assert_succeeds "a quote in ATC_CACHE_DIR cannot weaponize the cleanup trap" trap_injection_safe

# Download cleanup runs in its own subshell and must not replace a sourcing
# application's EXIT trap.
download_preserves_exit_trap() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/trap-cache"
    source "$WRAPPER"
    trap 'true # caller-owned-trap' EXIT
    release_json() { printf '%s\n' "$FIXTURE_JSON"; }
    curl() { return 1; }
    download_latest_release >/dev/null 2>&1 || true
    [[ "$(trap -p EXIT)" == *caller-owned-trap* ]]
  )
}
assert_succeeds "download cleanup preserves a caller's EXIT trap" download_preserves_exit_trap

assert_succeeds "a normal GitHub token is accepted" validate_github_token "github_pat_ABC123"
assert_fails "curl-config syntax is refused in GITHUB_TOKEN" validate_github_token $'abc"\noutput = "/tmp/x"'

# A non-https asset URL is refused before any download.
non_https_refused() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/cache-http"
    source "$WRAPPER"
    local http_json="${FIXTURE_JSON//https:\/\/github.com/http:\/\/github.com}"
    release_json() { printf '%s\n' "$http_json"; }
    download_latest_release >/dev/null 2>&1
  )
}
assert_fails "a non-https asset URL is refused" non_https_refused
non_https_msg() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/cache-http2"
    source "$WRAPPER"
    local http_json="${FIXTURE_JSON//https:\/\/github.com/http:\/\/github.com}"
    release_json() { printf '%s\n' "$http_json"; }
    download_latest_release 2>&1 >/dev/null
  ) || true
}
assert_contains "non-https refusal is explained" "refusing a non-https download URL" "$(non_https_msg)"

# Uninstall must not remove a directory that does not look like the jar cache.
uninstall_guard_keeps() { # $1 = the cache dir to try, $2 = empty|marker|jars
  (
    export ATC_CACHE_DIR="$1"
    export ATC_INSTALL_DIR="$TEST_TMP/guard-bin"
    source "$WRAPPER"
    mkdir -p "$CACHE_DIR"
    printf 'precious\n' > "$CACHE_DIR/keep.txt"
    case "${2:-}" in
      marker) printf '12345678|v0.2.0\n' > "$RELEASE_MARKER" ;;
      jars) printf 'app\n' > "$APP_JAR"; printf 'lib\n' > "$LIB_JAR" ;;
    esac
    ( cmd_uninstall ) >/dev/null 2>&1 && exit 1 # must refuse (fail exits that nested subshell)
    [[ -f "$CACHE_DIR/keep.txt" ]]
  )
}
assert_succeeds "uninstall refuses ATC_CACHE_DIR=HOME" uninstall_guard_keeps "$HOME"
assert_succeeds "a marker cannot authorize deleting HOME" uninstall_guard_keeps "$HOME" marker
assert_succeeds "jar filenames cannot authorize deleting HOME" uninstall_guard_keeps "$HOME" jars
assert_succeeds "uninstall refuses ATC_CACHE_DIR=~/.atc" uninstall_guard_keeps "$HOME/.atc" marker
assert_succeeds "uninstall refuses a cache dir not named jars" uninstall_guard_keeps "$TEST_TMP/notjars"
# and the refusal is explained
uninstall_guard_msg() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/notjars2"
    export ATC_INSTALL_DIR="$TEST_TMP/guard-bin2"
    source "$WRAPPER"
    mkdir -p "$CACHE_DIR"
    cmd_uninstall 2>&1 >/dev/null
  ) || true
}
assert_contains "uninstall guard message" "no valid ATC release marker or jar pair" "$(uninstall_guard_msg)"

# But a custom ATC_CACHE_DIR that IS our cache (holds the release marker) is removed,
# so a non-default cache name is not a permanent obstacle to uninstalling.
uninstall_removes_custom_cache() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/mycache"
    export ATC_INSTALL_DIR="$TEST_TMP/guard-bin3"
    source "$WRAPPER"
    mkdir -p "$CACHE_DIR"
    printf '12345678|v0.2.0\n' > "$RELEASE_MARKER" # marks it as ours
    cmd_uninstall >/dev/null 2>&1
    [[ ! -d "$CACHE_DIR" ]]
  )
}
assert_succeeds "uninstall removes a custom cache that holds the ATC marker" uninstall_removes_custom_cache

# Even a valid custom cache marker owns only ATC's artifacts, not arbitrary
# neighbours that happen to share the override directory.
uninstall_keeps_unrelated_custom_files() {
  (
    export ATC_CACHE_DIR="$TEST_TMP/shared-custom-cache"
    export ATC_INSTALL_DIR="$TEST_TMP/guard-bin4"
    source "$WRAPPER"
    mkdir -p "$CACHE_DIR/dev.notes" "$CACHE_DIR/download.notes"
    printf '12345678|v0.2.0\n' > "$RELEASE_MARKER"
    printf 'precious\n' > "$CACHE_DIR/keep.txt"
    printf 'precious\n' > "$CACHE_DIR/dev.notes/keep.txt"
    printf 'precious\n' > "$CACHE_DIR/download.notes/keep.txt"
    cmd_uninstall >/dev/null 2>&1
    [[ -f "$CACHE_DIR/keep.txt" && ! -e "$RELEASE_MARKER" &&
       -f "$CACHE_DIR/dev.notes/keep.txt" && -f "$CACHE_DIR/download.notes/keep.txt" ]]
  )
}
assert_succeeds "uninstall removes only owned artifacts from a shared custom cache" uninstall_keeps_unrelated_custom_files

# self update, with a copy of the wrapper so self_path points at the copy.
mkdir -p "$TEST_TMP/selfbin"
cp "$WRAPPER" "$TEST_TMP/selfbin/atc"
self_update_case() { # $1 = same|broken|changed
  (
    source "$TEST_TMP/selfbin/atc"
    case "$1" in
      same)    download_latest_self() { cp "$TEST_TMP/selfbin/atc" "$1"; } ;;
      broken)  download_latest_self() { printf 'not bash (((\n' > "$1"; } ;;
      changed) download_latest_self() { printf '#!/usr/bin/env bash\n# newer\n' > "$1"; } ;;
    esac
    cmd_self_update
  )
}
assert_succeeds "self update with an identical script is a no-op" self_update_case same
assert_contains "no-op says so" "already up to date" "$(self_update_case same)"
assert_fails "an invalid downloaded script is refused" self_update_case broken
assert_contains "invalid script message" "not a valid Bash" "$(self_update_case broken 2>&1 || true)"
assert_succeeds "the refused update kept the original" cmp -s "$WRAPPER" "$TEST_TMP/selfbin/atc"
assert_eq "no temp file left" "" "$(ls "$TEST_TMP/selfbin"/atc.self-update.* 2>/dev/null || true)"
assert_succeeds "a changed script replaces the wrapper" self_update_case changed
assert_contains "the copy was replaced" "# newer" "$(cat "$TEST_TMP/selfbin/atc")"
self_perms="$(stat -c %a "$TEST_TMP/selfbin/atc" 2>/dev/null || stat -f %Lp "$TEST_TMP/selfbin/atc")"
assert_eq "the installed wrapper is world-readable +x" "755" "$self_perms"
assert_eq "no temp file left after replacement" "" "$(ls "$TEST_TMP/selfbin"/atc.self-update.* 2>/dev/null || true)"

# ---------------------------------------------------------------------------
echo "--- startup update flow (stubbed GitHub and Java) ---"

startup_bin="$TEST_TMP/startup-bin"
mkdir -p "$startup_bin"
cat > "$startup_bin/java" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "-version" ]]; then echo 'openjdk version "17.0.2"' >&2; exit 0; fi
printf 'app:%s\n' "$(cat "$ATC_CACHE_DIR/atc.jar")"
printf 'arg:%s\n' "$@"
next_input=""
read -r next_input || true
printf 'stdin:%s\n' "$next_input"
EOF
chmod +x "$startup_bin/java"
startup_driver="$TEST_TMP/startup-driver.sh"
cat > "$startup_driver" <<'EOF'
set -euo pipefail
source "$WRAPPER_UNDER_TEST"
update_terminal_available() { [[ "$STARTUP_SCENARIO" != "redirected" ]]; }
release_json() {
  printf '%s\n' "$*" >> "$STARTUP_CASE_DIR/checks"
  [[ "$STARTUP_SCENARIO" != "offline" ]] || return 1
  cat "$STARTUP_CASE_DIR/release.json"
}
curl() {
  [[ "$STARTUP_SCENARIO" != "download-failure" ]] || return 1
  local url="" output=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -o) output="$2"; shift 2 ;;
      https://*) url="$1"; shift ;;
      *) shift ;;
    esac
  done
  cp "$STARTUP_CASE_DIR/assets/${url##*/}" "$output"
}
main "$@"
EOF

startup_case() {
  local scenario="$1" input="request" marker="1|v0.1.0" check_updates=1 json="$FIXTURE_JSON"
  shift
  STARTUP_CASE_DIR="$TEST_TMP/startup-$scenario"
  mkdir -p "$STARTUP_CASE_DIR/cache" "$STARTUP_CASE_DIR/assets"
  printf 'old app\n' > "$STARTUP_CASE_DIR/cache/atc.jar"
  printf 'old lib\n' > "$STARTUP_CASE_DIR/cache/atc-lib.jar"
  printf 'new app\n' > "$STARTUP_CASE_DIR/assets/atc.jar"
  printf 'new lib\n' > "$STARTUP_CASE_DIR/assets/atc-lib.jar"
  case "$scenario" in
    current) marker="12345678|v0.2.0" ;;
    newer-installed) marker="99999999|v1.0.0" ;;
    development) marker="dev|/checkout" ;;
    unknown) marker="custom" ;;
    disabled) check_updates=0 ;;
    invalid-json) json='{}' ;;
    incomplete) json="${json//\"atc.jar\"/\"other.jar\"}" ;;
    decline) input=$'n\nrequest' ;;
    default-no) input=$'\nrequest' ;;
    qualified-yes) input=$'yes, later\nrequest' ;;
    accept|bad-digest|download-failure) input=$'yes\nrequest' ;;
  esac
  if have_sha256_tool && [[ "$scenario" != "bad-digest" ]]; then
    json="$(printf '%s' "$json" | sed \
      -e "s/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890/$(sha256_of "$STARTUP_CASE_DIR/assets/atc.jar")/" \
      -e "s/fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321/$(sha256_of "$STARTUP_CASE_DIR/assets/atc-lib.jar")/")"
  fi
  printf '%s\n' "$marker" > "$STARTUP_CASE_DIR/cache/release.txt"
  if [[ "$scenario" == "missing-marker" ]]; then rm "$STARTUP_CASE_DIR/cache/release.txt"; fi
  printf '%s\n' "$json" > "$STARTUP_CASE_DIR/release.json"
  printf '%s\n' "$input" > "$STARTUP_CASE_DIR/input"
  if [[ "$scenario" == "eof" ]]; then : > "$STARTUP_CASE_DIR/input"; fi
  STARTUP_RC=0
  env PATH="$startup_bin:$PATH" ATC_CACHE_DIR="$STARTUP_CASE_DIR/cache" ATC_CHECK_UPDATES="$check_updates" \
    STARTUP_SCENARIO="$scenario" STARTUP_CASE_DIR="$STARTUP_CASE_DIR" WRAPPER_UNDER_TEST="$WRAPPER" \
    bash "$startup_driver" "$@" < "$STARTUP_CASE_DIR/input" \
      > "$STARTUP_CASE_DIR/stdout" 2> "$STARTUP_CASE_DIR/stderr" || STARTUP_RC=$?
}

for scenario in current newer-installed development unknown disabled redirected missing-marker offline invalid-json incomplete; do
  startup_case "$scenario"
  assert_eq "$scenario: startup succeeds" "0" "$STARTUP_RC"
  assert_contains "$scenario: installed app starts" "app:old app" "$(cat "$STARTUP_CASE_DIR/stdout")"
  assert_eq "$scenario: no update prompt" "" "$(sed -n '/Upgrade now/p' "$STARTUP_CASE_DIR/stderr")"
  assert_contains "$scenario: stdin is untouched" "stdin:request" "$(cat "$STARTUP_CASE_DIR/stdout")"
done
for scenario in development unknown disabled redirected missing-marker; do
  assert_fails "$scenario: no release lookup" test -e "$TEST_TMP/startup-$scenario/checks"
done

# The wrapper's own startup offer: an installed copy (ATC_INSTALL_DIR) of the wrapper is
# the script under test, GitHub's copy is assets/atc, the jars are current.
self_case() { # $1 = scenario, $2 = input
  local scenario="$1" input="$2" dir
  dir="$TEST_TMP/self-$scenario"
  mkdir -p "$dir/bin" "$dir/cache" "$dir/assets"
  cp "$WRAPPER" "$dir/bin/atc"
  printf 'old app\n' > "$dir/cache/atc.jar"
  printf 'old lib\n' > "$dir/cache/atc-lib.jar"
  printf '12345678|v0.2.0\n' > "$dir/cache/release.txt"
  printf '%s\n' "$FIXTURE_JSON" > "$dir/release.json"
  case "$scenario" in
    same) cp "$WRAPPER" "$dir/assets/atc" ;;
    broken) printf 'not bash (((\n' > "$dir/assets/atc" ;;
    *) { cat "$WRAPPER"; printf '\n# newer wrapper\n'; } > "$dir/assets/atc" ;;
  esac
  [[ "$scenario" != "throttled" ]] || touch "$dir/cache/self-check"
  local wrapper="$dir/bin/atc" install_dir="$dir/bin"
  [[ "$scenario" != "not-installed" ]] || install_dir="$dir/elsewhere"
  printf '%s\n' "$input" > "$dir/input"
  STARTUP_CASE_DIR="$dir" STARTUP_RC=0
  env PATH="$startup_bin:$PATH" ATC_CACHE_DIR="$dir/cache" ATC_INSTALL_DIR="$install_dir" ATC_CHECK_UPDATES=1 \
    STARTUP_SCENARIO="$scenario" STARTUP_CASE_DIR="$dir" WRAPPER_UNDER_TEST="$wrapper" \
    bash "$startup_driver" < "$dir/input" > "$dir/stdout" 2> "$dir/stderr" || STARTUP_RC=$?
}
self_prompt="A newer atc wrapper is available. Update it now? [y/N]"

self_case accept $'y\nrequest'
assert_eq "self accept: startup succeeds" "0" "$STARTUP_RC"
assert_contains "self accept: offer shown" "$self_prompt" "$(cat "$STARTUP_CASE_DIR/stderr")"
assert_contains "self accept: wrapper replaced" "# newer wrapper" "$(cat "$STARTUP_CASE_DIR/bin/atc")"
assert_contains "self accept: replacement reported" "used from the next start" "$(cat "$STARTUP_CASE_DIR/stderr")"
assert_contains "self accept: app still starts with the rest of stdin" $'app:old app' "$(cat "$STARTUP_CASE_DIR/stdout")"
assert_contains "self accept: stdin after the answer reaches the app" "stdin:request" "$(cat "$STARTUP_CASE_DIR/stdout")"
assert_succeeds "self accept: check stamp written" test -f "$STARTUP_CASE_DIR/cache/self-check"
assert_eq "self accept: no temp file left" "" "$(ls "$STARTUP_CASE_DIR/bin"/atc.self-update.* 2>/dev/null || true)"

self_case decline $'n\nrequest'
assert_contains "self decline: offer shown" "$self_prompt" "$(cat "$STARTUP_CASE_DIR/stderr")"
assert_succeeds "self decline: wrapper kept" cmp -s "$WRAPPER" "$STARTUP_CASE_DIR/bin/atc"
assert_contains "self decline: how to update later" "atc self update" "$(cat "$STARTUP_CASE_DIR/stderr")"
assert_contains "self decline: app starts" "stdin:request" "$(cat "$STARTUP_CASE_DIR/stdout")"
assert_eq "self decline: no temp file left" "" "$(ls "$STARTUP_CASE_DIR/bin"/atc.self-update.* 2>/dev/null || true)"

for scenario in same broken throttled not-installed; do
  self_case "$scenario" request
  assert_eq "self $scenario: startup succeeds" "0" "$STARTUP_RC"
  assert_eq "self $scenario: no offer" "" "$(grep -F "$self_prompt" "$STARTUP_CASE_DIR/stderr" || true)"
  assert_succeeds "self $scenario: wrapper kept" cmp -s "$WRAPPER" "$STARTUP_CASE_DIR/bin/atc"
  assert_contains "self $scenario: stdin is untouched" "stdin:request" "$(cat "$STARTUP_CASE_DIR/stdout")"
done
assert_fails "self not-installed: no check stamp" test -e "$TEST_TMP/self-not-installed/cache/self-check"
assert_succeeds "self broken: stamp written, so no retry within a day" test -f "$TEST_TMP/self-broken/cache/self-check"

for scenario in decline default-no qualified-yes eof; do
  startup_case "$scenario"
  assert_eq "$scenario: startup succeeds" "0" "$STARTUP_RC"
  assert_contains "$scenario: upgrade prompt shows both versions" \
    "ATC v0.2.0 is available (installed: v0.1.0). Upgrade now? [y/N]" "$(cat "$STARTUP_CASE_DIR/stderr")"
  assert_contains "$scenario: installed app starts" "app:old app" "$(cat "$STARTUP_CASE_DIR/stdout")"
done

startup_case scripted -p 'hello there'
assert_eq "scripted run does not check releases" "0" "$STARTUP_RC"
assert_fails "scripted run makes no lookup" test -e "$STARTUP_CASE_DIR/checks"
assert_contains "scripted prompt is forwarded" "arg:hello there" "$(cat "$STARTUP_CASE_DIR/stdout")"

if have_sha256_tool; then
  startup_case accept run -C /work
  assert_eq "accept: update and launch succeed" "0" "$STARTUP_RC"
  assert_contains "accept: new app starts" "app:new app" "$(cat "$STARTUP_CASE_DIR/stdout")"
  assert_contains "accept: next input is preserved" "stdin:request" "$(cat "$STARTUP_CASE_DIR/stdout")"
  assert_contains "accept: original args are preserved" $'arg:-C\narg:/work' "$(cat "$STARTUP_CASE_DIR/stdout")"
  assert_eq "accept: matching library is installed" "new lib" "$(cat "$STARTUP_CASE_DIR/cache/atc-lib.jar")"
  assert_eq "accept: release marker is updated" "12345678|v0.2.0" "$(cat "$STARTUP_CASE_DIR/cache/release.txt")"
  assert_eq "accept: one bounded lookup, approved metadata reused" \
    "--connect-timeout 2 --max-time 5" "$(cat "$STARTUP_CASE_DIR/checks")"
  for scenario in bad-digest download-failure; do
    startup_case "$scenario"
    assert_eq "$scenario: failed upgrade is reported" "1" "$STARTUP_RC"
    assert_eq "$scenario: Java does not start after failed upgrade" "" "$(cat "$STARTUP_CASE_DIR/stdout")"
    assert_eq "$scenario: old app remains" "old app" "$(cat "$STARTUP_CASE_DIR/cache/atc.jar")"
    assert_eq "$scenario: old library remains" "old lib" "$(cat "$STARTUP_CASE_DIR/cache/atc-lib.jar")"
    assert_eq "$scenario: old marker remains" "1|v0.1.0" "$(cat "$STARTUP_CASE_DIR/cache/release.txt")"
  done
fi

# ---------------------------------------------------------------------------
echo
echo "passed: $pass_count, failed: $fail_count"
[[ "$fail_count" -eq 0 ]]
