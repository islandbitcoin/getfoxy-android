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
# a connection to a real mint (MINT, default https://mint.minibits.cash/Bitcoin:
# its keys and info, no money); for the opt-out run, CONTINUE WITHOUT TOR through
# the native dialog first. Then stops everything and runs tools/leak-check.js on
# what was captured. Nothing here touches a phone.
#
# Android is tested on real mints with real ecash, so the mint and the melt are
# opt-in, under the money rules (docs/DEVICE-TESTS.md "Real money",
# tools/real-money.js), checked before the emulator starts:
#
#   REAL_MONEY=yes MAX_SATS=64 INVOICE=<the person's own invoice, at most 62 sats> \
#     tools/leak-test.sh direct out/
#
# only with the person's explicit go for this run; MAX_SATS at most 100 unless
# OVER_DEFAULT_CAP=yes; MINT_SATS (default MAX_SATS) is the mint quote, printed
# here for the person to pay from their own wallet within PAY_TIMEOUT_SECONDS
# (default 600); the melt pays only INVOICE, refused above MAX_SATS or without
# room under MINT_SATS for the mint's fee reserve. No fake invoice is paid.
#
# Leftover sats. The emulator is wiped on every start, so ecash left in it is
# lost on the next run. Once the quote is printed, the emulator is never killed
# holding money: after a melt that paid, what is left (the change) is exported
# as a cashu token, printed on stderr and written to <out>/<mode>.leftover.token
# for the person to receive in their own wallet, and only then is the emulator
# stopped. If the quote is not paid in time, the melt fails or is still pending
# at the mint, the export fails,
# or anything else stops the run after the quote was printed, the emulator is
# LEFT RUNNING and the script says how to claim and drain it by hand. A run
# refuses to start (exit 4) while an emulator is up on its serial, so one left
# running is never wiped, or stopped undrained, by the next run. Before the quote
# is printed, a held-money marker is written next to the AVD
# (<avd home>/<AVD>.avd/foxy-money-held: mint, quote id, time) and removed only
# once the change is out; while it exists a run refuses too (exit 4), even with
# no emulator up (it died, the Mac rebooted): the ecash is in the AVD's userdata,
# and -wipe-data would destroy it. Recover by starting the AVD WITHOUT -wipe-data
# and draining it, then delete the marker.
set -euo pipefail
mode="$1"
out="$(cd "$2" && pwd)"
root="$(cd "$(dirname "$0")/.." && pwd)"
# the money rules first: a refusal here has started nothing
mint="$(node "$root/tools/real-money.js" mint)"
# asking for any part of a money leg (any money variable, even empty) without the
# go is refused (exit 3), not quietly skipped: real-money.js leg-if-asked
leg="$(node "$root/tools/real-money.js" leg-if-asked)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
avd="${AVD:-foxy_leak}"
port=5580
export ANDROID_SERIAL="emulator-$port"
apk="$root/app/build/outputs/apk/debug/app-debug.apk"
pkg=io.islandbitcoin.foxy
log() { echo "leak-test $mode: $*" >&2; }
# outlives the emulator process: written before the mint quote is printed, removed once drained
held_marker="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$avd.avd/foxy-money-held"

# an emulator already on this serial may be one a previous run LEFT RUNNING holding ecash:
# starting another would fail on the port and the rest of this run would drive, and stop, that one.
# `adb get-state` fails for an `offline` emulator, so the console port is probed directly, and
# the serial in any adb state (device, offline, unauthorized, …) counts as up. The grep reads all
# of adb's output (no -q): an early exit could SIGPIPE adb and, under pipefail, read as "not up".
if [ -e "$held_marker" ]; then
  echo "leak-test $mode: AVD $avd may hold real ecash from a previous run ($held_marker):" >&2
  sed 's/^/  /' "$held_marker" >&2 || true
  echo "leak-test $mode: not starting it with -wipe-data. Recover it first (docs/DEVICE-TESTS.md \"Leftover sats\"):" >&2
  echo "  $sdk/emulator/emulator -avd $avd -port $port -no-snapshot &   # NO -wipe-data" >&2
  echo "  adb -s $ANDROID_SERIAL wait-for-device; adb -s $ANDROID_SERIAL shell am start -n $pkg/.MainActivity" >&2
  echo "  then claim the quote if it was paid and drain the wallet into your own (the commands the held run printed), stop it (adb -s $ANDROID_SERIAL emu kill), and only then: rm '$held_marker'" >&2
  exit 4
fi
if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null || adb devices 2>/dev/null | grep "^$ANDROID_SERIAL[[:space:]]" > /dev/null; then
  echo "leak-test $mode: $ANDROID_SERIAL is already running (it may hold ecash from a previous run): drain it and stop it first (docs/DEVICE-TESTS.md \"Leftover sats\")" >&2
  exit 4
fi

"$sdk/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
  -gpu swiftshader_indirect -tcpdump "$out/$mode.pcap" > "$out/$mode.emulator.log" 2>&1 &
emu=$!
# set once the mint quote is printed, cleared once no ecash can be left in the emulator
money_held=""
# the page script that empties the wallet into one cashu token (less the mint's fee on the send)
drain_js='var b = await FoxyWallet.balanceSats(); if (!(b > 0)) return { left: 0 };
  var amt = b; while (amt > 0) { var sf = FoxyWallet.sendShortfall(amt); if (sf && !sf.short) break; amt--; }
  if (!(amt > 0)) return { left: b, err: "the balance does not cover the fee of a send" };
  var t = await FoxyWallet.sendToken(amt, { unit: "sat" }); return { left: b, sats: t.sats, token: t.token };'
held() {
  echo "leak-test $mode: real ecash may be in this emulator ($1)." >&2
  echo "leak-test $mode: it is LEFT RUNNING ($ANDROID_SERIAL, AVD $avd); the next leak-test.sh run refuses to start until it is drained and stopped." >&2
  echo "leak-test $mode: drain it by hand into your own wallet, then stop it:" >&2
  echo "  adb -s $ANDROID_SERIAL forward tcp:9444 localabstract:webview_devtools_remote_\$(adb -s $ANDROID_SERIAL shell pidof $pkg)" >&2
  echo "  node $root/tools/cdp-eval.js 9444 '$drain_js' 120000   # receive the token it returns" >&2
  echo "  adb -s $ANDROID_SERIAL emu kill" >&2
  echo "leak-test $mode: once it is drained, delete the held-money marker: rm '$held_marker'" >&2
  echo "leak-test $mode: if this emulator dies first, the ecash is still in AVD $avd's userdata: start it again WITHOUT -wipe-data (the next run refuses while the marker exists)" >&2
}
cleanup() {
  [ -n "${sampler:-}" ] && kill "$sampler" 2>/dev/null || true
  if [ -n "$money_held" ]; then
    held "the run stopped after the mint quote was printed"
    disown "$emu" 2>/dev/null || true
    return
  fi
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
log "connecting to $mint"
cdp 'var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
     for (var t = 0; t < 120 && !FoxyWallet.connected; t++) await sleep(500);
     var mint = '"$(node -e 'process.stdout.write(JSON.stringify(process.argv[1]))' "$mint")"', err = null;
     for (var c = 0; c < 5 && FoxyWallet.mintUrl !== mint; c++) { try { await FoxyWallet.connect(mint); } catch (e) { err = String(e.message || e); await sleep(2000); } }
     return { privacy: FoxyWallet.privacy(), mint: FoxyWallet.mintUrl, connected: FoxyWallet.mintUrl === mint, err: err }' 600000 | tee "$out/$mode.connect.json"
if [ -n "$leg" ]; then
  field() { node -e 'process.stdout.write(String(JSON.parse(process.argv[1])[process.argv[2]]))' "$leg" "$1"; }
  mint_sats="$(field mintSats)"; invoice="$(field invoice)"; invoice_sats="$(field invoiceSats)"; timeout="$(field payTimeoutSeconds)"
  quote="$(cdp 'var a = await FoxyWallet.invoice('"$mint_sats"', "foxy leak test"); return { bolt11: a.bolt11, hash: a.hash, sats: a.sats }' 180000)"
  bolt11="$(node -e 'process.stdout.write(JSON.parse(process.argv[1]).bolt11)' "$quote")"
  hash="$(node -e 'process.stdout.write(JSON.parse(process.argv[1]).hash)' "$quote")"
  [[ "$hash" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || { echo "the mint's quote id is not one to pass on: $hash" >&2; exit 1; }
  # the mint's own invoice is read too: the person is never shown more than MINT_SATS to pay
  quoted="$(node -e 'process.stdout.write(String(require(process.argv[1]).invoiceSats(process.argv[2])))' "$root/tools/real-money.js" "$bolt11")"
  [ "$quoted" = "$mint_sats" ] || { echo "the mint's invoice is for $quoted sats, not $mint_sats: not paying it" >&2; exit 1; }
  # from here until the change is exported, the emulator is never killed (cleanup, held), and the
  # marker, which outlives this process, keeps the next run from wiping the AVD
  printf 'mint %s\nquote %s\nsats %s\nat %s\n' "$mint" "$hash" "$mint_sats" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$held_marker"
  money_held=1
  echo "leak-test $mode: PAY THIS from your own wallet: $mint_sats sats to $mint, within $timeout s:" >&2
  echo "$bolt11" >&2
  deadline=$(( $(date +%s) + timeout ))
  claimed=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    r="$(cdp 'try { await FoxyWallet.claim("'"$hash"'"); return "claimed"; } catch (e) { return "not yet: " + String(e.message || e); }' 60000 || true)"
    if [ "$r" = '"claimed"' ]; then claimed=1; break; fi
    sleep 3
  done
  if [ -z "$claimed" ]; then
    echo "leak-test $mode: not paid within $timeout s; nothing was melted. Mint $mint, quote id $hash." >&2
    echo "leak-test $mode: this quote's ecash can only be claimed in this emulator (its seed is wiped on the next run): if you paid it, or pay it now, claim it there first (after the forward below), then drain it:" >&2
    echo "  node $root/tools/cdp-eval.js 9444 'await FoxyWallet.claim(\"$hash\"); return await FoxyWallet.balanceSats()'" >&2
    exit 1
  fi
  log "claimed $mint_sats sats; melting $invoice_sats to the person's invoice"
  cdp 'var paid = null, perr = null;
       try { paid = await FoxyWallet.pay("'"$invoice"'"); } catch (e) { perr = String(e.message || e); }
       return { privacy: FoxyWallet.privacy(), mint: FoxyWallet.mintUrl, claimed: true, paid: !!paid, pending: !!(paid && paid.pending), payErr: perr }' 600000 | tee "$out/$mode.drive.json"
  # a pending melt (FoxyWallet.pay resolves { pending: true }) is a failure here: its ecash is held
  # in this emulator until the mint settles it, so the emulator is left running (held)
  pay_err="$(node "$root/tools/real-money.js" melt-failure "$out/$mode.drive.json")"
  if [ -n "$pay_err" ]; then
    echo "leak-test $mode: the melt failed: $pay_err" >&2
    exit 1
  fi
  # the change, out of the emulator before it is stopped (and wiped on the next run)
  drained="$(cdp "$drain_js" 120000)"
  left="$(node -e 'var d = JSON.parse(process.argv[1]); process.stdout.write(d.err ? "err " + d.err : d.token ? "token" : "none")' "$drained")"
  case "$left" in
    none) log "nothing left in the emulator's wallet" ;;
    token)
      (umask 077; node -e 'process.stdout.write(JSON.parse(process.argv[1]).token + "\n")' "$drained" > "$out/$mode.leftover.token")
      sats="$(node -e 'process.stdout.write(String(JSON.parse(process.argv[1]).sats))' "$drained")"
      echo "leak-test $mode: LEFTOVER: $sats sats of ecash, as a token; receive it in your own wallet (also in $out/$mode.leftover.token):" >&2
      cat "$out/$mode.leftover.token" >&2
      ;;
    *) echo "leak-test $mode: the leftover could not be exported: ${left#err }" >&2; exit 1 ;;
  esac
  money_held=""
  rm -f "$held_marker"
else
  log "no money moved: REAL_MONEY not given (docs/DEVICE-TESTS.md \"Real money\")"
fi
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
