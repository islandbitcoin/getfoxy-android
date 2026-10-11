#!/bin/sh
# sync-core.sh — import the iOS app's Web/ directory, byte for byte, into core/web.
#
#     tools/sync-core.sh <commit> [repo]
#
# <commit>  the full 40-character commit of the iOS repository to import from.
# [repo]    a local clone of the iOS repository, or a URL to clone. Default:
#           $FOXY_IOS_REPO, else https://github.com/getfoxy/iOS.git
#
# What it writes, and nothing else:
#   core/web/            every file under Web/ at that commit (git's bytes, no
#                        checkout filters), replacing what was there. A Web/
#                        holding a dot-file or dot-folder is refused, and core/
#                        left as it was.
#   core/UPSTREAM-LICENSE the iOS repository's LICENSE at that commit
#   core/PIN             the repository URL, the commit, the page hash and the
#                        licence's hash
#   core/SHA256SUMS      "<sha256>  <path from core/web>" for every file under
#                        core/web, sorted by path as bytes. This is exactly the
#                        list of lines the iOS app hashes into the "page hash"
#                        its menu drawer shows (Foxy/FoxyWebView.swift
#                        manifestHash, tools/page-hash.py), so
#                        sha256(SHA256SUMS) is that page hash.
#
# The source is read with `git archive`, never a working tree, so a dirty clone
# cannot leak an edit into core/. Run tools/verify-core.sh afterwards.
set -eu

die() { echo "sync-core: $*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: tools/sync-core.sh <commit> [repo]"
COMMIT=$1
REPO=${2:-${FOXY_IOS_REPO:-https://github.com/getfoxy/iOS.git}}
printf '%s' "$COMMIT" | grep -Eq '^[0-9a-f]{40}$' || die "the commit must be a full 40-character sha, not '$COMMIT'"

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CORE=$ROOT/core
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi

# A local clone is read in place; anything else is cloned into the temp dir.
if [ -d "$REPO" ] && git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1; then
  SRC=$(cd "$REPO" && pwd)
  URL=$(git -C "$SRC" remote get-url origin 2>/dev/null || echo "$SRC")
else
  git clone --quiet --filter=blob:none --no-checkout "$REPO" "$TMP/src" || die "could not clone $REPO"
  SRC=$TMP/src
  URL=$REPO
fi
git -C "$SRC" cat-file -e "$COMMIT^{commit}" 2>/dev/null || die "$COMMIT is not a commit in $REPO"

mkdir -p "$TMP/out"
git -C "$SRC" archive --format=tar "$COMMIT" Web LICENSE | tar -x -C "$TMP/out"
[ -f "$TMP/out/Web/index.html" ] || die "no Web/index.html at $COMMIT"
# The page-hash rule leaves dot-files out of SHA256SUMS, and verify-core.sh
# counts any file the list does not name as extra, so a dot-file under Web/
# could only produce a core/ that fails verification. Refuse it here, before
# core/ is touched, and say which.
dots=$(cd "$TMP/out/Web" && find . -name '.*' ! -name . | sed 's|^\./||' | LC_ALL=C sort)
[ -z "$dots" ] || die "Web/ at $COMMIT holds dot-files, which the page hash leaves out and core/ cannot carry: $(printf '%s' "$dots" | tr '\n' ' ')"

rm -rf "$CORE/web"
mkdir -p "$CORE"
cp -R "$TMP/out/Web" "$CORE/web"
cp "$TMP/out/LICENSE" "$CORE/UPSTREAM-LICENSE"

# The page-hash rule: every regular file, dot-files and dot-folders left out,
# named from core/web, sorted as bytes.
( cd "$CORE/web" && find . -type f -not -path '*/.*' | sed 's|^\./||' | LC_ALL=C sort \
    | while IFS= read -r f; do $SHA "$f"; done ) | sed 's/ \*/  /' > "$CORE/SHA256SUMS"

PAGE=$($SHA < "$CORE/SHA256SUMS" | cut -c1-64)
LIC=$($SHA < "$CORE/UPSTREAM-LICENSE" | cut -c1-64)
cat > "$CORE/PIN" <<PIN
repo=$URL
commit=$COMMIT
path=Web
page-hash=$PAGE
upstream-license-sha256=$LIC
PIN

echo "sync-core: imported $(wc -l < "$CORE/SHA256SUMS" | tr -d ' ') files from $COMMIT"
echo "sync-core: page hash $PAGE"
