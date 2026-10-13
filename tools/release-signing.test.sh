#!/usr/bin/env bash
# release-signing.test.sh — the release's upload key is read from outside the
# repository, only when a release is signed, and a missing or wrong one fails
# clearly (ENG-680, app/build.gradle.kts, docs/RELEASE.md).
#
# Uses a throwaway keystore made here, in a temporary folder, with a random
# password, removed on exit. Never reads the macOS Keychain: every run points
# FOXY_SECURITY_TOOL at a stub that records how it was called and answers the
# throwaway password (or nothing), and FOXY_UPLOAD_* from the caller's
# environment are dropped. No password is printed.
#
# Builds the release bundle twice (signed with the throwaway key, then unsigned)
# and deletes app/build/outputs/bundle/release/app-release.aab afterwards, so no
# bundle signed with a throwaway key is left to upload.
#
#   tools/release-signing.test.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
aab="$root/app/build/outputs/bundle/release/app-release.aab"
trap 'rm -rf "$tmp"; rm -f "$aab"' EXIT

password="$(openssl rand -hex 16)"
keytool -genkeypair -noprompt -keystore "$tmp/upload.p12" -storetype PKCS12 \
  -storepass "$password" -keypass "$password" -alias throwaway \
  -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Foxy throwaway test key" > /dev/null 2>&1
fingerprint="$(keytool -list -v -keystore "$tmp/upload.p12" -storepass "$password" -alias throwaway \
  | sed -n 's/^[[:space:]]*SHA256: //p')"
[ -n "$fingerprint" ] || { echo "release-signing.test: no fingerprint for the throwaway key" >&2; exit 2; }

# the Keychain stub: records its arguments; answers the password, or exits 44 (no such item) with keychain=none
cat > "$tmp/security" <<STUB
#!/bin/sh
echo "\$*" >> "$tmp/security.calls"
[ "\${STUB_KEYCHAIN:-}" = none ] && exit 44
printf '%s\n' "$password"
STUB
chmod +x "$tmp/security"

failures=0
fail() { echo "release-signing.test: $1" >&2; failures=$((failures + 1)); }

# gradle <env assignments...> -- <tasks...>: sets status and out; the stub's calls in $tmp/security.calls
gradle() {
  local vars=()
  while [ "$1" != -- ]; do vars+=("$1"); shift; done
  shift
  rm -f "$tmp/security.calls"
  set +e
  out="$(env -u FOXY_UPLOAD_STORE_FILE -u FOXY_UPLOAD_KEY_ALIAS -u FOXY_UPLOAD_STORE_PASSWORD -u FOXY_UPLOAD_KEY_PASSWORD \
    FOXY_SECURITY_TOOL="$tmp/security" ${vars[@]+"${vars[@]}"} \
    "$root/gradlew" -p "$root" --no-daemon --console=plain "$@" 2>&1)"
  status=$?
  set -e
}
has() { case "$out" in *"$2"*) ;; *) fail "$1: no '$2' in the output: $(printf '%s' "$out" | grep -iE 'error|warn|upload' | head -5)" ;; esac; }
lacks() { case "$out" in *"$2"*) fail "$1: '$2' is in the output" ;; esac; }
ok() { [ "$status" = 0 ] || fail "$1: expected success, got $status: $(printf '%s' "$out" | grep -iE 'error|What went wrong' -A3 | head -8)"; }
failed() { [ "$status" != 0 ] || fail "$1: expected a failure, got success"; }
keychainNotRead() { [ ! -e "$tmp/security.calls" ] || fail "$1: the Keychain was asked: $(cat "$tmp/security.calls")"; }
keychainRead() {
  [ -e "$tmp/security.calls" ] || { fail "$1: the Keychain was not asked"; return; }
  [ "$(sort -u "$tmp/security.calls")" = "find-generic-password -s foxy-android-upload -w" ] \
    || fail "$1: the Keychain was asked something else: $(cat "$tmp/security.calls")"
}
store=(FOXY_UPLOAD_STORE_FILE="$tmp/upload.p12" FOXY_UPLOAD_KEY_ALIAS=throwaway)
secrets=(FOXY_UPLOAD_STORE_PASSWORD="$password" FOXY_UPLOAD_KEY_PASSWORD="$password")

# no key at all: verifyReleaseSigning fails and says what to set
gradle -- :app:verifyReleaseSigning
failed "no key"; has "no key" "FOXY_UPLOAD_STORE_FILE"; keychainNotRead "no key"

# the key from the environment: it opens, and the certificate printed is the throwaway one's
gradle "${store[@]}" "${secrets[@]}" -- :app:verifyReleaseSigning
ok "env"; has "env" "certificate SHA-256 $fingerprint"; keychainNotRead "env"; lacks "env" "$password"

# no password in the environment: the Keychain's foxy-android-upload, asked exactly so
gradle "${store[@]}" -- :app:verifyReleaseSigning
ok "keychain"; has "keychain" "certificate SHA-256 $fingerprint"; keychainRead "keychain"; lacks "keychain" "$password"

# no password in the environment nor the Keychain
gradle "${store[@]}" STUB_KEYCHAIN=none -- :app:verifyReleaseSigning
failed "no password"; has "no password" "the macOS Keychain has no foxy-android-upload password"

# a wrong store password, a wrong key password, an alias that is not there, a path that is not a file
gradle "${store[@]}" FOXY_UPLOAD_STORE_PASSWORD=wrong-password FOXY_UPLOAD_KEY_PASSWORD="$password" -- :app:verifyReleaseSigning
failed "wrong store password"; has "wrong store password" "does not open with its store password"; lacks "wrong store password" "$password"
gradle FOXY_UPLOAD_STORE_FILE="$tmp/upload.p12" FOXY_UPLOAD_KEY_ALIAS=nobody "${secrets[@]}" -- :app:verifyReleaseSigning
failed "wrong alias"; has "wrong alias" "has no key under the alias nobody"
gradle FOXY_UPLOAD_STORE_FILE="$tmp/nothing.p12" FOXY_UPLOAD_KEY_ALIAS=throwaway "${secrets[@]}" -- :app:verifyReleaseSigning
failed "no file"; has "no file" "FOXY_UPLOAD_STORE_FILE names no file"

# a build that signs nothing never reads a password, with the key configured and none in the environment
gradle "${store[@]}" -- :app:processReleaseMainManifest
ok "no signing"; keychainNotRead "no signing"

# the bundle signed with the key: its signer is the throwaway certificate
rm -f "$aab"
gradle "${store[@]}" "${secrets[@]}" -- :app:bundleRelease
ok "signed bundle"; lacks "signed bundle" "UNSIGNED"; keychainNotRead "signed bundle"
if [ -f "$aab" ]; then
  mkdir -p "$tmp/aab"
  (cd "$tmp/aab" && unzip -q -o "$aab" 'META-INF/*')
  signer="$(find "$tmp/aab/META-INF" -name '*.RSA' -o -name '*.EC' | head -1)"
  if [ -z "$signer" ]; then
    fail "signed bundle: no signature block in META-INF"
  else
    got="$(keytool -printcert -file "$signer" | sed -n 's/^[[:space:]]*SHA256: //p')"
    [ "$got" = "$fingerprint" ] || fail "signed bundle: signed by $got, not the throwaway key $fingerprint"
  fi
  jarsigner -verify "$aab" | grep -q '^jar verified' || fail "signed bundle: jarsigner does not verify it"
else
  fail "signed bundle: no $aab"
fi

# the bundle without the key: built, unsigned, and the build says so
rm -f "$aab"
gradle -- :app:bundleRelease
ok "unsigned bundle"; has "unsigned bundle" "WARNING: UNSIGNED bundleRelease"; keychainNotRead "unsigned bundle"
if [ -f "$aab" ]; then
  jarsigner -verify "$aab" | grep -q 'jar is unsigned' || fail "unsigned bundle: it carries a signature"
else
  fail "unsigned bundle: no $aab"
fi

if [ "$failures" != 0 ]; then
  echo "release-signing.test: $failures failed" >&2
  exit 1
fi
echo "release-signing.test: ok"
