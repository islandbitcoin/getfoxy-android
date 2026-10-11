#!/bin/sh
# verify-core.sh — fail unless core/web is exactly the pinned iOS Web/ bundle.
#
#     tools/verify-core.sh                    # offline: core/ against core/SHA256SUMS and core/PIN
#     tools/verify-core.sh --against <clone>  # and against `git archive` of the pinned commit in <clone>
#
# Offline, it fails when:
#   - a file listed in core/SHA256SUMS is missing, or its bytes differ;
#   - core/web holds a file SHA256SUMS does not list (an extra script dropped in
#     is exactly what the page hash exists to catch), or anything that is not a
#     regular file or a directory (a link would be followed by the WebView);
#   - SHA256SUMS is not in the page-hash order (sorted as bytes, no duplicates);
#   - sha256(SHA256SUMS) is not the page-hash in core/PIN, or
#     core/UPSTREAM-LICENSE is not the licence hash in core/PIN.
# With --against it also re-exports Web/ at PIN's commit and compares byte for byte.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CORE=$ROOT/core
fail=0
bad() { echo "verify-core: FAIL $*" >&2; fail=1; }

if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi
hash_of() { $SHA < "$1" | cut -c1-64; }
pin() { sed -n "s/^$1=//p" "$CORE/PIN"; }

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT INT TERM

[ -f "$CORE/PIN" ] || { echo "verify-core: FAIL core/PIN missing" >&2; exit 1; }
[ -f "$CORE/SHA256SUMS" ] || { echo "verify-core: FAIL core/SHA256SUMS missing" >&2; exit 1; }
[ -d "$CORE/web" ] || { echo "verify-core: FAIL core/web missing" >&2; exit 1; }

# 1. the list itself: well formed, in order, no duplicates
if grep -Evq '^[0-9a-f]{64}  [^ ].*$' "$CORE/SHA256SUMS"; then bad "SHA256SUMS has a malformed line"; fi
cut -c67- "$CORE/SHA256SUMS" > "$W/listed"
LC_ALL=C sort -u "$W/listed" | cmp -s - "$W/listed" \
  || bad "SHA256SUMS is not sorted as bytes, or lists a path twice"

# 2. nothing but regular files and directories
( cd "$CORE/web" && find . ! -type f ! -type d ) | sed 's|^\./||' > "$W/odd"
if [ -s "$W/odd" ]; then bad "core/web holds something that is not a file: $(tr '\n' ' ' < "$W/odd")"; fi

# 3. exactly the listed files: none missing, none extra (dot-files count as extra here)
( cd "$CORE/web" && find . -type f | sed 's|^\./||' | LC_ALL=C sort ) > "$W/found"
missing=$(LC_ALL=C comm -23 "$W/listed" "$W/found")
extra=$(LC_ALL=C comm -13 "$W/listed" "$W/found")
[ -z "$missing" ] || bad "missing from core/web: $(printf '%s' "$missing" | tr '\n' ' ')"
[ -z "$extra" ] || bad "not in SHA256SUMS: $(printf '%s' "$extra" | tr '\n' ' ')"

# 4. every listed file's bytes
while IFS= read -r line; do
  want=$(printf '%s' "$line" | cut -c1-64)
  f=$(printf '%s' "$line" | cut -c67-)
  [ -f "$CORE/web/$f" ] || continue
  got=$(hash_of "$CORE/web/$f")
  [ "$got" = "$want" ] || bad "$f differs (sha256 $got, pinned $want)"
done < "$CORE/SHA256SUMS"

# 5. the page hash and the licence, as PIN records them
page=$(hash_of "$CORE/SHA256SUMS")
[ "$page" = "$(pin page-hash)" ] || bad "page hash $page is not PIN's $(pin page-hash)"
[ -f "$CORE/UPSTREAM-LICENSE" ] && [ "$(hash_of "$CORE/UPSTREAM-LICENSE")" = "$(pin upstream-license-sha256)" ] \
  || bad "core/UPSTREAM-LICENSE is missing or is not the licence PIN records"
printf '%s' "$(pin commit)" | grep -Eq '^[0-9a-f]{40}$' || bad "PIN's commit is not a full sha"

# 6. optionally, against the upstream commit itself
if [ "${1:-}" = "--against" ]; then
  [ -n "${2:-}" ] || { echo "verify-core: --against needs a clone of the iOS repository" >&2; exit 2; }
  mkdir -p "$W/upstream"
  git -C "$2" archive --format=tar "$(pin commit)" Web | tar -x -C "$W/upstream" || bad "could not export $(pin commit) from $2"
  diff -r "$W/upstream/Web" "$CORE/web" >/dev/null || bad "core/web is not Web/ at $(pin commit) in $2"
fi

if [ $fail -ne 0 ]; then exit 1; fi
echo "verify-core: OK $(wc -l < "$CORE/SHA256SUMS" | tr -d ' ') files, page hash $page, iOS $(pin commit | cut -c1-12)"
