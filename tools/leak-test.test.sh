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
run() { # run <adb get-state exit code>; sets status and err
  printf '#!/bin/sh\n[ "$3" = get-state ] && [ "$2" = emulator-5580 ] && { echo device; exit %s; }\nexit 1\n' "$1" > "$tmp/bin/adb"
  chmod +x "$tmp/bin/adb"
  rm -f "$tmp/emulator-started"
  set +e
  err="$(env -i PATH="$tmp/bin:$PATH" HOME="$tmp" ANDROID_HOME="$tmp/sdk" "$here/leak-test.sh" direct "$tmp/out" 2>&1 > /dev/null)"
  status=$?
  set -e
}

# an emulator is up on the serial: refused, nothing started
run 0
if [ "$status" != 4 ]; then echo "leak-test.test: expected exit 4 with an emulator up, got $status: $err" >&2; failures=$((failures + 1)); fi
if [ -e "$tmp/emulator-started" ]; then echo "leak-test.test: an emulator was started over a running one" >&2; failures=$((failures + 1)); fi
case "$err" in *"already running"*) ;; *) echo "leak-test.test: no 'already running' message: $err" >&2; failures=$((failures + 1)) ;; esac

# none up: the run goes on to start one (and stops later on the stubs; only the start is checked)
run 1
if [ ! -e "$tmp/emulator-started" ]; then echo "leak-test.test: no emulator started when none was up (exit $status: $err)" >&2; failures=$((failures + 1)); fi

[ "$failures" = 0 ] || exit 1
echo "leak-test.test: ok"
