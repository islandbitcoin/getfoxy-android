#!/usr/bin/env bash
# cardsim-test.sh — the card session end to end against the real applet (ENG-667, ENG-682).
#
# Builds a card repository's simulator at the commit a pin file names, with that
# repository's own tooling (tools/cardsim/run.sh: Maven, jCardSim, its
# CardServer), starts it on a loopback port, and runs the pin's test class
# (app/src/testDebug) against it: the bridge, CardGate and the debug build's
# SimCardLink driving CashuApplet itself. Fails if the test was skipped.
#
# Two pins, one run each (CI runs both):
#   tools/cardsim.PIN     getfoxy/card, Foxy 1.x (1.20): CardSimEndToEndTest, the default
#   tools/cardsim-2.PIN   lnflash/cashu-javacard, card protocol 2.0: CardSim2EndToEndTest
#
#   tools/cardsim-test.sh [--pin <file>]                fetch the pinned commit into a scratch folder
#   tools/cardsim-test.sh [--pin <file>] <card clone>   take the pinned commit from a local clone of the pin's repo
#                                                       (read with `git archive`; its working tree is never touched)
#
# FOXY_CARDSIM_PORT picks the port (47439 unless set: not one of the four
# ports a debug build looks on, so a simulator already running for a phone is
# left alone). Needs a JDK (17) and Maven, as the card's own tests do.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
pin="$root/tools/cardsim.PIN"
if [ "${1:-}" = "--pin" ]; then
  [ $# -ge 2 ] || { echo "cardsim-test: --pin needs a file" >&2; exit 2; }
  pin="$2"
  shift 2
fi
[ -f "$pin" ] || { echo "cardsim-test: no pin file $pin" >&2; exit 2; }
repo="$(sed -n 's/^repo=//p' "$pin")"
commit="$(sed -n 's/^commit=//p' "$pin")"
test="$(sed -n 's/^test=//p' "$pin")"
case "$test" in
  CardSimEndToEndTest|CardSim2EndToEndTest) ;;
  *) echo "cardsim-test: $pin names no test this script runs: $test" >&2; exit 2 ;;
esac
port="${FOXY_CARDSIM_PORT:-47439}"
case "$port" in ''|*[!0-9]*) echo "cardsim-test: FOXY_CARDSIM_PORT is not a port: $port" >&2; exit 2 ;; esac
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "cardsim-test: $pin has no 40-hex commit" >&2; exit 2; }

work="$(mktemp -d)"
server=""
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

if [ $# -ge 1 ]; then
  git -C "$1" cat-file -e "$commit^{commit}" || { echo "cardsim-test: $1 does not have $commit" >&2; exit 2; }
  git -C "$1" archive "$commit" applet tools | tar -x -C "$work"
else
  git init --quiet "$work/clone"
  git -C "$work/clone" fetch --quiet --depth 1 "$repo" "$commit"
  git -C "$work/clone" archive "$commit" applet tools | tar -x -C "$work"
fi

if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
  echo "cardsim-test: something already listens on 127.0.0.1:$port" >&2
  exit 2
fi

log="$work/cardsim.log"
sh "$work/tools/cardsim/run.sh" "$port" > "$log" 2>&1 &
server=$!
for _ in $(seq 1 600); do
  if grep -q "a card is on 127.0.0.1:$port" "$log"; then break; fi
  if ! kill -0 "$server" 2>/dev/null; then
    echo "cardsim-test: the card simulator did not start:" >&2
    cat "$log" >&2
    exit 1
  fi
  sleep 0.5
done
grep -q "a card is on 127.0.0.1:$port" "$log" || { echo "cardsim-test: the card simulator did not come up in 300 s" >&2; cat "$log" >&2; exit 1; }
echo "cardsim-test: ${repo#https://github.com/} ${commit:0:7} in jCardSim on 127.0.0.1:$port"

class="io.islandbitcoin.foxy.flashcard.$test"
results="$root/app/build/test-results/testDebugUnitTest"
rm -f "$results/TEST-$class.xml"
(cd "$root" && ./gradlew --no-daemon -q testDebugUnitTest --tests "$class" "-Pfoxy.cardsimPort=$port")

report="$results/TEST-$class.xml"
[ -f "$report" ] || { echo "cardsim-test: no report from $test" >&2; exit 1; }
tests="$(sed -n 's/.*<testsuite [^>]*tests="\([0-9]*\)".*/\1/p' "$report" | head -1)"
skipped="$(sed -n 's/.*<testsuite [^>]*skipped="\([0-9]*\)".*/\1/p' "$report" | head -1)"
failures="$(sed -n 's/.*<testsuite [^>]*failures="\([0-9]*\)".*/\1/p' "$report" | head -1)"
errors="$(sed -n 's/.*<testsuite [^>]*errors="\([0-9]*\)".*/\1/p' "$report" | head -1)"
if [ "${tests:-0}" -lt 1 ] || [ "${skipped:-1}" != 0 ] || [ "${failures:-1}" != 0 ] || [ "${errors:-1}" != 0 ]; then
  echo "cardsim-test: $test tests=$tests skipped=$skipped failures=$failures errors=$errors" >&2
  exit 1
fi
echo "cardsim-test: ok ($tests tests against the applet, none skipped)"
