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
    mkdir -p "$CACHE_DIR"
    printf '12345678|v0.2.0\n' > "$RELEASE_MARKER"
    printf 'precious\n' > "$CACHE_DIR/keep.txt"
    cmd_uninstall >/dev/null 2>&1
    [[ -d "$CACHE_DIR" && -f "$CACHE_DIR/keep.txt" && ! -e "$RELEASE_MARKER" ]]
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
echo
echo "passed: $pass_count, failed: $fail_count"
[[ "$fail_count" -eq 0 ]]
