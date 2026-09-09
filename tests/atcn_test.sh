#!/usr/bin/env bash
# Tests for the `atcn` wrapper (the native image's installer/launcher): the
# platform asset name, the overrides it applies to the shared `atc` code, digest
# bookkeeping, JDK detection and dispatch. No network or Java required.
#
#   bash tests/atcn_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT

export HOME="$TEST_TMP/home"
export ATC_INSTALL_DIR="$TEST_TMP/bin"
export ATCN_CACHE_DIR="$TEST_TMP/native"
unset ATC_JAVA_HOME JAVA_HOME ATC_CACHE_DIR
mkdir -p "$HOME" "$ATC_INSTALL_DIR"

# shellcheck source=../atcn
source "$REPO_ROOT/atcn" # loads the checkout's `atc` beside it; the guarded main does not run
apply_native_overrides

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

stderr_of() {
  ("$@" 2>&1 >/dev/null) || true
}

# ---------------------------------------------------------------------------
echo "--- library and overrides ---"

assert_eq "library is the checkout's atc" "$REPO_ROOT/atc" "$(atc_library_path)"
assert_eq "wrapper name" "atcn" "$WRAPPER_NAME"
assert_eq "install path" "$ATC_INSTALL_DIR/atcn" "$INSTALL_PATH"
assert_eq "cache dir honours ATCN_CACHE_DIR" "$ATCN_CACHE_DIR" "$CACHE_DIR"
assert_eq "marker lives in the native cache" "$ATCN_CACHE_DIR/release.txt" "$RELEASE_MARKER"
assert_eq "self-update URL is atcn's" "https://raw.githubusercontent.com/noti0na1/atc/refs/heads/main/atcn" "$SCRIPT_SOURCE_URL"
assert_contains "help names atcn" "atcn setup" "$(print_help)"

echo "--- platform and assets ---"

platform="$(native_platform)"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) assert_eq "platform (host)" "macos-arm64" "$platform" ;;
  Darwin-x86_64) assert_eq "platform (host)" "macos-x64" "$platform" ;;
  Linux-x86_64) assert_eq "platform (host)" "linux-x64" "$platform" ;;
  Linux-aarch64) assert_eq "platform (host)" "linux-arm64" "$platform" ;;
  *) assert_contains "platform has os-arch shape" "-" "$platform" ;;
esac
assert_eq "asset name" "atc-native-${platform}.tar.gz" "$(native_asset_name)"
assert_eq "assets: library jar first" "atc-lib.jar" "${ASSET_NAMES[0]}"
assert_eq "assets: the platform archive" "atc-native-${platform}.tar.gz" "${ASSET_NAMES[1]}"
assert_eq "installed paths" "$(printf '%s\n' "$CACHE_DIR/atc" "$CACHE_DIR/atc-lib.jar" "$CACHE_DIR/digests.txt")" "$(installed_paths)"

# Release metadata parsing with the native asset list: a fixture with the
# platform's archive, using the shared grep fallback and jq path.
release_json_fixture() {
  cat <<EOF
{
  "id": 4242,
  "tag_name": "v0.3.0",
  "assets": [
    { "name": "atc.jar", "digest": "sha256:$(printf 'a%.0s' {1..64})", "browser_download_url": "https://github.com/noti0na1/atc/releases/download/v0.3.0/atc.jar" },
    { "name": "atc-lib.jar", "digest": "sha256:$(printf 'b%.0s' {1..64})", "browser_download_url": "https://github.com/noti0na1/atc/releases/download/v0.3.0/atc-lib.jar" },
    { "name": "atc-native-${platform}.tar.gz", "digest": "sha256:$(printf 'c%.0s' {1..64})", "browser_download_url": "https://github.com/noti0na1/atc/releases/download/v0.3.0/atc-native-${platform}.tar.gz" }
  ],
  "body": "notes mentioning atc-lib.jar and https://example.com/atc-lib.jar"
}
EOF
}
fixture="$(release_json_fixture)"
expected_assets="$(printf 'atc-lib.jar\thttps://github.com/noti0na1/atc/releases/download/v0.3.0/atc-lib.jar\tsha256:%s\natc-native-%s.tar.gz\thttps://github.com/noti0na1/atc/releases/download/v0.3.0/atc-native-%s.tar.gz\tsha256:%s' "$(printf 'b%.0s' {1..64})" "$platform" "$platform" "$(printf 'c%.0s' {1..64})")"
assert_eq "grep: lib and archive, in order" "$expected_assets" "$(extract_assets_grep "$fixture")"
if command -v jq >/dev/null 2>&1; then
  assert_eq "jq: same two assets" "$(printf '%s\n' "$expected_assets" | sort)" "$(extract_assets_jq "$fixture" | sort)"
fi
assert_fails "grep: a release without the archive fails" extract_assets_grep '{"assets":[{"name":"atc-lib.jar","digest":"sha256:x","browser_download_url":"https://x/atc-lib.jar"}]}'
assert_contains "missing archive names the asset" "atc-native-${platform}.tar.gz" \
  "$(stderr_of extract_assets_grep '{"assets":[{"name":"atc-lib.jar","digest":"sha256:x","browser_download_url":"https://x/atc-lib.jar"}]}')"

echo "--- installing downloaded assets ---"

mkdir -p "$CACHE_DIR"
dl="$TEST_TMP/dl"; mkdir -p "$dl/bin"
printf 'jar bytes' > "$dl/atc-lib.jar"
printf '#!/bin/sh\necho native ok\n' > "$dl/bin/atc"
tar -czf "$dl/atc-native-${platform}.tar.gz" -C "$dl/bin" atc
lib_digest="sha256:$(sha256_of "$dl/atc-lib.jar")"
archive_digest="sha256:$(sha256_of "$dl/atc-native-${platform}.tar.gz")"
install_downloaded_asset "atc-lib.jar" "$dl/atc-lib.jar"
install_downloaded_asset "atc-native-${platform}.tar.gz" "$dl/atc-native-${platform}.tar.gz"
assert_eq "jar installed" "jar bytes" "$(cat "$CACHE_DIR/atc-lib.jar")"
assert_eq "binary extracted and executable" "native ok" "$("$CACHE_DIR/atc")"
assert_eq "archive removed after extraction" "" "$(ls "$CACHE_DIR" | grep 'tar.gz' || true)"
assert_eq "digests recorded" "$(printf 'atc-lib.jar\t%s\natc-native-%s.tar.gz\t%s' "$lib_digest" "$platform" "$archive_digest")" "$(cat "$CACHE_DIR/digests.txt")"

assets_info="$(printf 'atc-lib.jar\thttps://x/atc-lib.jar\t%s\natc-native-%s.tar.gz\thttps://x/a.tar.gz\t%s' "$lib_digest" "$platform" "$archive_digest")"
rc=0; cached_jars_match_digests "$assets_info" || rc=$?
assert_eq "cache matches the release's digests" "0" "$rc"
changed="$(printf 'atc-lib.jar\thttps://x/atc-lib.jar\t%s\natc-native-%s.tar.gz\thttps://x/a.tar.gz\tsha256:%s' "$lib_digest" "$platform" "$(printf 'd%.0s' {1..64})")"
rc=0; cached_jars_match_digests "$changed" || rc=$?
assert_eq "a re-uploaded archive is a mismatch (re-download)" "1" "$rc"
rc=0; cached_jars_match_digests "$(printf 'atc-lib.jar\thttps://x/atc-lib.jar\t\n')" || rc=$?
assert_eq "an asset without digest cannot be verified" "2" "$rc"
printf '4242|v0.3.0\n' > "$RELEASE_MARKER"
assert_succeeds "cached release matches with every installed path present" cached_release_matches "4242|v0.3.0"
assert_succeeds "require_cached_jars passes" require_cached_jars
rm -f "$CACHE_DIR/digests.txt"
assert_fails "cached release needs the digests file" cached_release_matches "4242|v0.3.0"
rc=0; cached_jars_match_digests "$assets_info" || rc=$?
assert_eq "no digests file: cannot verify" "2" "$rc"

echo "--- an archive without the binary ---"
bad="$TEST_TMP/bad"; mkdir -p "$bad/x"; printf 'nope' > "$bad/x/other"
tar -czf "$bad/atc-native-${platform}.tar.gz" -C "$bad/x" other
assert_fails "refuses an archive without atc" install_downloaded_asset "atc-native-${platform}.tar.gz" "$bad/atc-native-${platform}.tar.gz"
assert_eq "no extraction directory left behind" "" "$(ls -d "$CACHE_DIR"/extract.* 2>/dev/null || true)"

echo "--- dev builds ---"
checkout="$TEST_TMP/checkout"; mkdir -p "$checkout/out/native" "$checkout/out/dist.dest"
printf '#!/bin/sh\necho dev build\n' > "$checkout/out/native/atc"; printf 'lib' > "$checkout/out/dist.dest/atc-lib.jar"
assert_succeeds "dev install" install_dev_build "$checkout"
assert_eq "dev binary runs" "dev build" "$("$CACHE_DIR/atc")"
assert_eq "dev marker" "dev|$checkout" "$(cat "$RELEASE_MARKER")"
assert_eq "dev source" "$checkout" "$(dev_source)"
assert_fails "dev without a native build fails" install_dev_build "$TEST_TMP/home"
rm "$checkout/out/native/atc"
assert_contains "dev names native/build.sh" "native/build.sh" "$(stderr_of install_dev_build "$checkout")"

echo "--- JDK detection ---"
settings=$'    java.class.path =\n    java.home = /opt/jdks/temurin-17\n    java.io.tmpdir = /tmp\n'
assert_eq "java.home from -XshowSettings output" "/opt/jdks/temurin-17" "$(java_home_from_settings "$settings")"
assert_eq "java.home with spaces" "/Library/Java/JVM 17/Contents/Home" "$(java_home_from_settings $'  java.home = /Library/Java/JVM 17/Contents/Home  \n')"
assert_fails "no java.home line" java_home_from_settings $'java.version = 17\n'
fakejdk="$TEST_TMP/jdk"; mkdir -p "$fakejdk/lib"; : > "$fakejdk/lib/modules"
assert_eq "ATC_JAVA_HOME wins" "$fakejdk" "$(ATC_JAVA_HOME="$fakejdk" jdk_home)"
assert_eq "JAVA_HOME next" "$fakejdk" "$(JAVA_HOME="$fakejdk" jdk_home)"
assert_fails "a JRE (no lib/modules) is refused" env ATC_JAVA_HOME="$TEST_TMP/home" bash -c 'source "'"$REPO_ROOT"'/atcn"; apply_native_overrides; jdk_home'
assert_contains "JRE refusal names lib/modules" "lib/modules" "$(ATC_JAVA_HOME="$TEST_TMP/home" stderr_of jdk_home)"

echo "--- dispatch ---"
assert_contains "help" "atcn: install, update and run" "$(main help)"
assert_fails "unknown command" main frobnicate
assert_fails "setup rejects arguments" main setup extra
assert_fails "run needs an install (empty cache)" env ATCN_CACHE_DIR="$TEST_TMP/empty" bash -c 'source "'"$REPO_ROOT"'/atcn"; apply_native_overrides; ensure_java() { :; }; cmd_run --version'
assert_fails "run without the atc library fails clearly" env ATC_INSTALL_DIR="$TEST_TMP/nolib" bash -c 'cd "'"$TEST_TMP"'" && cp "'"$REPO_ROOT"'/atcn" atcn-alone && bash atcn-alone --version'
assert_contains "the message says to run setup" "atcn setup" "$(cd "$TEST_TMP" && ATC_INSTALL_DIR="$TEST_TMP/nolib" bash atcn-alone --version 2>&1 || true)"

echo
echo "passed: $pass_count, failed: $fail_count"
[[ "$fail_count" -eq 0 ]]
