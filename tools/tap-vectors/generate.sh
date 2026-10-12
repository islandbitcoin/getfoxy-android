#!/usr/bin/env bash
# generate.sh: write app/src/test/resources/fixtures/tap-vectors.json from the
# iOS tap code at the commit in core/PIN (macOS only: it needs swiftc and
# CryptoKit).
#
#     tools/tap-vectors/generate.sh <ios-clone>           write the file
#     tools/tap-vectors/generate.sh <ios-clone> --check   fail if the file differs
#
# The iOS files are read with `git show <commit>:<path>`, so the clone's
# working tree is never touched. TapSession.swift gets one change, made here
# and checked to have happened exactly twice: its private key and nonce come
# from VectorSeed (Vectors.swift) instead of the system random source.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
ios="${1:?usage: generate.sh <ios-clone> [--check]}"
mode="${2:-write}"
commit="$(sed -n 's/^commit=//p' "$root/core/PIN")"
fixture="$root/app/src/test/resources/fixtures/tap-vectors.json"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for f in TapCrypto TapSession TapProtocol; do
  git -C "$ios" show "$commit:Foxy/Bluetooth/$f.swift" > "$work/$f.swift"
done

key='    private let priv = Curve25519.KeyAgreement.PrivateKey()'
nonce='    private let myNonce = TapCrypto.randomNonce()'
[ "$(grep -cxF "$key" "$work/TapSession.swift")" = 1 ] || { echo "generate: the private key line moved" >&2; exit 1; }
[ "$(grep -cxF "$nonce" "$work/TapSession.swift")" = 1 ] || { echo "generate: the nonce line moved" >&2; exit 1; }
sed -i '' \
  -e 's/^    private let priv = Curve25519\.KeyAgreement\.PrivateKey()$/    private let priv = VectorSeed.key()/' \
  -e 's/^    private let myNonce = TapCrypto\.randomNonce()$/    private let myNonce = VectorSeed.nonce()/' \
  "$work/TapSession.swift"
[ "$(grep -c 'VectorSeed\.' "$work/TapSession.swift")" = 2 ] || { echo "generate: the seed did not go in" >&2; exit 1; }

cp "$root/tools/tap-vectors/Vectors.swift" "$work/main.swift"
swiftc -O -o "$work/vectors" "$work/main.swift" "$work/TapCrypto.swift" "$work/TapSession.swift" "$work/TapProtocol.swift"
# the sessions' diary goes to standard output; the JSON to its own file
"$work/vectors" "getfoxy/iOS $commit" "$work/tap-vectors.json" > /dev/null

if [ "$mode" = "--check" ]; then
  cmp -s "$work/tap-vectors.json" "$fixture" || { echo "generate: $fixture is not what the iOS code writes" >&2; exit 1; }
  echo "generate: $fixture matches getfoxy/iOS $commit"
else
  cp "$work/tap-vectors.json" "$fixture"
  echo "generate: wrote $fixture from getfoxy/iOS $commit"
fi
