#!/usr/bin/env bash
# Tests for the `atc` wrapper script: release-metadata parsing, checksum
# verification, cache checks and command dispatch. No network, no Java needed.
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
  assert_fails "cache with a changed jar does not match" cached_jars_match_digests "$bad_info"
  assert_succeeds "asset without digest is skipped" cached_jars_match_digests "$partial_info"
  rm -f "$APP_JAR"
  assert_fails "missing cached jar does not match" cached_jars_match_digests "$good_info"
  rm -rf "$CACHE_DIR"
else
  echo "  SKIP: no sha256 tool"
fi

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
run_out="$(PATH="$TEST_TMP/mockbin:$PATH" ATC_JAVA_OPTS="-Xmx2g -Dfoo=bar" main)"
assert_contains "ATC_JAVA_OPTS is applied" $'-Xmx2g\n-Dfoo=bar' "$run_out"

cat > "$TEST_TMP/mockbin/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "11.0.2" 2019-01-15' >&2
EOF
assert_fails "old Java is refused" env PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main"
assert_contains "old Java message" "Java 17+ is required. Found Java 11" \
  "$(env PATH="$TEST_TMP/mockbin:$PATH" bash -c "source '$WRAPPER'; main" 2>&1 >/dev/null || true)"

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
  curl() { # curl -fL <url> -o <file>
    local url="$2" out="$4"
    cp "$ASSETS_DIR/$(basename "$url")" "$out"
  }

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

# ---------------------------------------------------------------------------
echo
echo "passed: $pass_count, failed: $fail_count"
[[ "$fail_count" -eq 0 ]]
