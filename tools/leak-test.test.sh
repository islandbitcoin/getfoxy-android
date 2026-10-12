#!/usr/bin/env bash
# leak-test.test.sh — tools/leak-test.sh never starts over an emulator that is already up.
#
# A run that ends LEFT RUNNING keeps emulator-5580, which may hold ecash. A
# second run must refuse (exit 4) before it starts an emulator: otherwise its
# own start fails on the port and it drives, and stops, the old one undrained.
# adb and the emulator are stubs on PATH / ANDROID_HOME; nothing here touches a
# device or an emulator.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/sdk/emulator" "$tmp/out"
# the emulator stub records that it was started, and nothing else
printf '#!/bin/sh\necho started > "%s/emulator-started"\n' "$tmp" > "$tmp/sdk/emulator/emulator"
chmod +x "$tmp/sdk/emulator/emulator"

failures=0
run() { # run <adb get-state exit code> [<adb devices line>]; sets status and err; adb's calls in $tmp/adb.calls
  cat > "$tmp/bin/adb" <<STUB
#!/bin/sh
echo "\$*" >> "$tmp/adb.calls"
[ "\$3" = get-state ] && [ "\$2" = emulator-5580 ] && { echo device; exit $1; }
[ "\$1" = devices ] && { printf 'List of devices attached\\n%s\\n\\n' "${2:-}"; exit 0; }
exit 1
STUB
  chmod +x "$tmp/bin/adb"
  rm -f "$tmp/emulator-started" "$tmp/adb.calls"
  set +e
  err="$(env -i PATH="$tmp/bin:$PATH" HOME="$tmp" ANDROID_HOME="$tmp/sdk" "$here/leak-test.sh" direct "$tmp/out" 2>&1 > /dev/null)"
  status=$?
  set -e
}
refused() { # refused <case>: exit 4, nothing started, nothing stopped
  if [ "$status" != 4 ]; then echo "leak-test.test: $1: expected exit 4, got $status: $err" >&2; failures=$((failures + 1)); fi
  if [ -e "$tmp/emulator-started" ]; then echo "leak-test.test: $1: an emulator was started over a running one" >&2; failures=$((failures + 1)); fi
  case "$err" in *"already running"*) ;; *) echo "leak-test.test: $1: no 'already running' message: $err" >&2; failures=$((failures + 1)) ;; esac
  # the one up may hold ecash: a refusal never stops it
  if grep -q 'emu kill' "$tmp/adb.calls" 2>/dev/null; then echo "leak-test.test: $1: the refusal issued an 'emu kill'" >&2; failures=$((failures + 1)); fi
}

# the console port is probed first: nothing may listen on it here, or the cases below test nothing
if (exec 3<>/dev/tcp/127.0.0.1/5580) 2>/dev/null; then echo "leak-test.test: something listens on 127.0.0.1:5580; stop it and run again" >&2; exit 1; fi

# an emulator is up on the serial: refused, nothing started
run 0 "emulator-5580	device"
refused "an emulator up"

# an emulator on the serial that adb lists as offline (get-state fails for it): refused too
run 1 "emulator-5580	offline"
refused "an offline emulator"

# an emulator whose console port answers while adb does not list it at all (adb's server
# restarted, say): refused on the port alone
node -e 'require("net").createServer().listen(5580, "127.0.0.1")' &
listener=$!
for _ in $(seq 1 50); do (exec 3<>/dev/tcp/127.0.0.1/5580) 2>/dev/null && break; sleep 0.1; done
run 1
kill "$listener" 2>/dev/null || true
wait "$listener" 2>/dev/null || true
refused "a console port that answers"

# none up: the run goes on to start one (and stops later on the stubs; only the start is checked)
run 1 "emulator-5554	device"
if [ ! -e "$tmp/emulator-started" ]; then echo "leak-test.test: no emulator started when none was up (exit $status: $err)" >&2; failures=$((failures + 1)); fi
# and the calls are recorded: this run's clean-up stopped the emulator it started
if ! grep -q 'emu kill' "$tmp/adb.calls" 2>/dev/null; then echo "leak-test.test: adb's calls were not recorded (no 'emu kill' from the clean-up)" >&2; failures=$((failures + 1)); fi

[ "$failures" = 0 ] || exit 1
echo "leak-test.test: ok"
