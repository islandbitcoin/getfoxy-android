#!/usr/bin/env bash
# leak-test.sh — one run of the P2 leak test on an Android emulator (docs/DEVICE-TESTS.md, "Leak test").
#
#   tools/leak-test.sh <direct|obfs4|snowflake|unprotected> <out dir> [<consensus file for unprotected>]
#
# Starts the AVD (default foxy_leak, override with AVD=) wiped, headless, with
# `-tcpdump`, so every packet the guest sends is captured; installs the debug
# APK; samples the app's sockets from /proc/net every 0.2 s; launches Foxy on
# the transport (FoxyForceTransport), or without Tor (FoxyNoTor) for the opt-out
# run; drives the page over WebView debugging (tools/cdp-eval.js): Tor up, then
# a mint and a melt on testnut.cashu.space (fake money); for the opt-out run,
# CONTINUE WITHOUT TOR through the native dialog first. Then stops everything and
# runs tools/leak-check.js on what was captured. Nothing here touches a phone.
set -euo pipefail
mode="$1"
out="$(cd "$2" && pwd)"
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
avd="${AVD:-foxy_leak}"
port=5580
export ANDROID_SERIAL="emulator-$port"
apk="$root/app/build/outputs/apk/debug/app-debug.apk"
pkg=io.islandbitcoin.foxy
log() { echo "leak-test $mode: $*" >&2; }

"$sdk/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
  -gpu swiftshader_indirect -tcpdump "$out/$mode.pcap" > "$out/$mode.emulator.log" 2>&1 &
emu=$!
cleanup() {
  [ -n "${sampler:-}" ] && kill "$sampler" 2>/dev/null || true
  adb emu kill > /dev/null 2>&1 || true
  wait "$emu" 2>/dev/null || true
}
trap cleanup EXIT

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
log "booted"
adb root > /dev/null; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 1; done
# plain DNS, so the capture can read every lookup
adb shell settings put global private_dns_mode off
# the emulator's -tcpdump sees its emulated ethernet (the cellular path), not its virtual Wi-Fi
adb shell svc wifi disable
until adb shell dumpsys connectivity | grep -q "Transports: CELLULAR.*VALIDATED\|VALIDATED.*Transports: CELLULAR"; do sleep 1; done
adb install -r "$apk" > /dev/null
uid="$(adb shell pm list packages -U $pkg | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -1)"
[ -n "$uid" ] || { echo "no uid for $pkg" >&2; exit 1; }
log "uid $uid"
adb shell 'while true; do for f in tcp tcp6 udp udp6; do sed "s/^/$f /" /proc/net/$f; done; sleep 0.2; done' > "$out/$mode.sockets" &
sampler=$!

case "$mode" in
  direct|obfs4|snowflake) adb shell am start -n $pkg/.MainActivity --es FoxyForceTransport "$mode" > /dev/null ;;
  unprotected) adb shell am start -n $pkg/.MainActivity --ez FoxyNoTor true > /dev/null ;;
  *) echo "mode: direct, obfs4, snowflake or unprotected" >&2; exit 2 ;;
esac
until pid="$(adb shell pidof $pkg | tr -d '\r')" && [ -n "$pid" ]; do sleep 1; done
adb forward tcp:9444 "localabstract:webview_devtools_remote_$pid" > /dev/null
cdp() { node "$root/tools/cdp-eval.js" 9444 "$1" "${2:-600000}"; }
until cdp 'return !!(window.FoxyWallet && window.FoxyWallet.privacy)' 5000 2>/dev/null | grep -q true; do sleep 2; done
log "the page is up"

after=""
marker="foxy-leak-test-opt-out.example.com"
if [ "$mode" = unprotected ]; then
  cdp 'FoxyWallet.setUnprotected(true).then(function () { window.__optedOut = true; }); return "asked"' 10000
  # the native dialog: its yes, tapped where Android drew it
  for _ in $(seq 1 20); do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    bounds="$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -i 'text="Continue without Tor"' | grep -o 'bounds="[^"]*"' | head -1 || true)"
    [ -n "$bounds" ] && break
    sleep 1
  done
  [ -n "$bounds" ] || { echo "no CONTINUE WITHOUT TOR dialog" >&2; exit 1; }
  read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
  # the moment, marked in the capture itself (the emulator stamps packets with a clock of its own):
  # a lookup of a name nothing else asks, just before the tap
  adb shell ping -c 1 -W 1 "$marker" > /dev/null 2>&1 || true
  after="--after-marker $marker"
  log "CONTINUE WITHOUT TOR tapped"
  adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
  cdp 'for (var i = 0; i < 50 && !window.__optedOut; i++) await new Promise(function (r) { setTimeout(r, 200); }); return FoxyWallet.privacy().unprotected' 20000
else
  cdp 'for (var i = 0; i < 1200; i++) { if (FoxyWallet.privacy().tor === "up") return "up after " + (i / 2) + " s"; await new Promise(function (r) { setTimeout(r, 500); }); } throw new Error("Tor did not come up")' 700000
fi
log "minting and melting on testnut.cashu.space"
invoice="$(node "$root/tools/fake-invoice.js" 8)"
cdp 'var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
     for (var t = 0; t < 120 && !FoxyWallet.connected; t++) await sleep(500);
     for (var c = 0; c < 5 && FoxyWallet.mintUrl !== "https://testnut.cashu.space"; c++) { try { await FoxyWallet.connect("https://testnut.cashu.space"); } catch (e) { await sleep(2000); } }
     var a = await FoxyWallet.invoice(64, "foxy leak test");
     var claimed = null, err = null;
     for (var i = 0; i < 20 && !claimed; i++) { try { claimed = await FoxyWallet.claim(a.hash); } catch (e) { err = String(e.message || e); await sleep(1500); } }
     var paid = null, perr = null;
     try { paid = await FoxyWallet.pay("'"$invoice"'"); } catch (e) { perr = String(e.message || e); }
     return { privacy: FoxyWallet.privacy(), mint: FoxyWallet.mintUrl, claimed: !!claimed, claimErr: claimed ? null : err, paid: !!paid, payErr: perr }' 600000 | tee "$out/$mode.drive.json"
sleep 3
if [ "$mode" != unprotected ]; then
  adb shell run-as $pkg cat cache/TorService/cached-microdesc-consensus > "$out/$mode.consensus"
fi
kill "$sampler" 2>/dev/null || true
sampler=""
adb emu kill > /dev/null 2>&1 || true
wait "$emu" 2>/dev/null || true
trap - EXIT
consensus="$out/$mode.consensus"
[ "$mode" = unprotected ] && consensus="${3:?the opt-out run needs a consensus file from another run}"
node "$root/tools/leak-check.js" --pcap "$out/$mode.pcap" --sockets "$out/$mode.sockets" --uid "$uid" --mode "$mode" \
  --consensus "$consensus" $after | tee "$out/$mode.report.txt"
