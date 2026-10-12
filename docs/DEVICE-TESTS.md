# DEVICE-TESTS.md — what is checked on a phone, by hand or by instrumentation

Some of the host's claims (`SECURITY-INVARIANTS.md`) can only be shown on a real
phone. This is the list: the instrumented tests that run on one, the checks a
person makes by hand, the P2 device exit, and the P2 leak test (on an emulator,
where every packet can be captured). CI builds the debug APK and the test APK
but has no phone, so none of this runs there; each pull request says which of
these it ran, on which phone, and what each answered.

## Instrumented (`app/src/androidTest`)

Install both APKs and run them on an unlocked phone with nothing else of Foxy's
on it:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r io.islandbitcoin.foxy.test/androidx.test.runner.AndroidJUnitRunner
```

(`./gradlew connectedDebugAndroidTest` does the same in one step.)

| Test | Proves | Invariant |
|---|---|---|
| `PageBootTest` | the unmodified page, served by the asset loader with `setBlockNetworkLoads(true)` and the dead-end proxy on, boots to CANNOT CONNECT TO TOR / PROCEED OFFLINE; the shim is in place and locked; `__foxyPageHash` is `core/PIN`'s; no action the page asked was unknown; `location.href` and `window.open` go nowhere; `RTCPeerConnection` is undefined in the page and in blank child frames however they are reached; a subframe's message is not answered while the main frame's is | S8, S14, S15, S16, S17, S26 |
| `NetBlockTest` | the iOS `-FoxyNetBlockTest` set (fetch, WebSocket, preconnect, DNS prefetch, prefetch, `<img>`, beacon, a Worker's fetch) reaches nothing: a loopback listener in the test package's own process, which first counts a connection of its own, counts none from the page | S8 |
| `AssetsMatchPinTest` | every `SHA256SUMS` line matches the bytes the APK serves, nothing under `assets/web/` is missing or extra, and `sha256(SHA256SUMS)` is `core/PIN`'s page hash and `BuildConfig.PAGE_HASH` | S26 |
| `KeystoreTest` | the seed's key is AES-256 in StrongBox (or the TEE where the phone has none) and needs no user authentication (protection `none`); the vault round-trips through the Keystore from a second vault object, the file is not the words, and a file whose key is gone reads as failed and is never written over; on a locked phone nothing is written and nothing is left behind | S3, S4 |
| `NUT13OnDeviceTest` | the NUT-13 spec vectors and a P2PK key derive on the phone, through the APK's own libsecp256k1 | S6, S7 |
| `CounterCrashTest` | a `counterReserve` cut off after its range is written and before it is answered leaves the counter past the range for the next launch, and its secrets never left | S6 |
| `FlagSecureTest` | the window is `FLAG_SECURE` | S19 |
| `PutAwayTest` | putting Foxy away shuts the door; coming back opens it and pushes `__foxyUptime`; a reply and a push after the WebView is gone are dropped by `WebHost.evaluate`'s guard (the test's own two scripts, counted by the guard's test hook so a late reply of the page's own can land meanwhile, ENG-652; one `DROPPED_LINE` in the field log for every script dropped) and nothing throws; finishing the activity (Back on API 28-30, where PutAway's stop never comes) parks Tor and takes it off the network (needs the phone online, skips saying so otherwise) | S21 |
| `SeedForgottenTest` | a seed kept for the visit is gone the moment the activity is finished (Back), inside ProcessLifecycleOwner's 700 ms hold, when `PutAway` never runs; it stays gone after the hold. Needs no Keystore, so it runs on a locked phone too | S20 |
| `TorRouteTest` | CONTINUE WITHOUT TOR is the native dialog's: Stay protected answers `unprotected: false`, Continue sets it, off clears it; a `mintRequest` to a host Foxy has not used shows the Allow dialog and Don't Allow answers "You did not allow Foxy to contact …" with nothing sent; the approvals are sealed in `noBackupFilesDir`; a request answered over Tor, then Tor taken off the network mid-request fails it and the next is refused. The last needs the phone online (it is skipped, saying so, when Tor does not connect in 3 minutes) | S9, S11, S13 |
| `DeviceExitTest` | the opt-out leg of the device exit, below: runs only with `-e foxyExit true` | S11 |
| `QrDecodeTest` | the scanner's decode path without the camera: a cashu token and a lightning invoice drawn as QR codes (straight, at an angle in a larger frame, upside down) read back through zxing-cpp, the APK's own native decoder; every frame of the iOS animated-QR fixture read back and gathered into its token | P3 |
| `ClipboardSensitiveTest` | a sensitive copy carries `IS_SENSITIVE` and Foxy's label and is cleared once its 120 s are up (an injected clock), a plain one is not; the paste button reads the clipboard only in its tap | S23 |
| `SeedScreenStopTest` | SHOW WORDS is `FLAG_SECURE`; HOME with it open finishes it, answered as closed; a screen closed before it opened is answered and shows nothing | S19, S20 |
| `CameraUnbindTest` | the live pane binds the camera, and the activity stopping unbinds it at once; a second stop does nothing (grants CAMERA) | S25 |
| `SeedProtectTest` | the `device` key needs the person for every use (API 30+; on API 28-29 it refuses at `Cipher.init` outside the 10 s after a confirmation) and survives a new fingerprint; a move to `device` the person does not approve leaves the `none` seed readable and the protection unchanged. Needs a screen lock | S3, S5 |
| `OwnPromptTest` | API 28-29 only, with `-e foxyPin <pin>` (skipped otherwise; it types the PIN with `input`): Settings' screen-lock confirmation stops MainActivity, and that stop is not a put-away. The vault's epoch does not move, the seed read under it is kept, the door stays open, and PutAway logs that it put nothing away; taking the guard off within 10 s of that yes still asks (`fresh`). With a WebView that loads the page, a `seedShow` of a `device` seed opens SHOW WORDS once the PIN is given (skipped on an image whose WebView cannot load the page) | S3, S20, S21 |

P2 starts Tor with the page, so `PageBootTest`, `PutAwayTest` and
`NetBlockTest` wait for the page to boot in whatever state Tor is in
(`Page.STARTED`) rather than on P1's CANNOT CONNECT TO TOR; what they assert is
unchanged, and `NetBlockTest` now also asserts the app holds INTERNET: the
walls hold with the permission granted. Every instrumented run with the phone
online contacts testnut.cashu.space (a fake-money test mint) over Tor.

**A phone where the network is a permission** (GrapheneOS: the Network toggle):
an app updated from a build without INTERNET (P1) to one with it (P2) keeps the
toggle off, and Tor fails with `Socket creation failed: Operation not
permitted` in logcat; the gate says CANNOT CONNECT TO TOR, which is the fail
closed it should be. Uninstall and install (or turn Network on) before a run.

The seed's key is `setUnlockedDeviceRequired(true)`, so on a locked phone it
cannot be used; that is the point of it. The tests that need it
(`KeystoreTest.theVaultRoundTrips…`, and `PutAwayTest`'s return, which needs
the activity in front) are skipped with "the phone is locked: unlock it and run
again" rather than passing on a phone that proved nothing, and
`KeystoreTest.onALockedPhone…` runs only then. Run the suite once unlocked.

## By hand

On the debug build, then (when there is one) the release build:

1. **Recents shows no wallet, and a screenshot is refused.** Open Foxy, go to
   Recents: the card is blank. Press power + volume down: Android says the app
   does not allow screenshots. (S19)
2. **No backup.** `adb shell bmgr backupnow io.islandbitcoin.foxy` reports the
   package is not eligible; `adb backup` of the app is empty. (S1)
3. **A reinstall starts empty.** With a seed made, uninstall and install again:
   the first `seedStatus` answers `{"exists":false}` and a new seed is made; the
   old one is gone with the Keystore key. (S22)
4. **A release build says nothing.** `adb logcat --pid=$(adb shell pidof io.islandbitcoin.foxy)`
   while using a release build shows no line from Foxy. (S18)
5. **The intro video plays with the network blocked.** A fresh install shows
   the launch intro (`foxy-intro.mp4`) through to the gate. (ANDROID-MAPPING
   decision 2)
6. **Nothing reaches the network but Tor.** P1 checked this with PCAPdroid;
   from P2 it is the leak test below, on an emulator, which captures every
   packet without a third-party app on the phone. (S8, S9, S10)
7. **The seed outlives a restart.** On an unlocked phone: let the page make a
   seed (`seedCreate`), `adb shell am force-stop io.islandbitcoin.foxy`, launch
   again: `seedStatus` answers `{"exists":true}` and the page shows the same
   wallet. (S3, S4; the ENG-647 device exit.) Wiping the seed is not checkable
   in P1: `seedWipe` is P3 (BRIDGE.md `seedWipe` — P3, S5).

## The P3 checks a person makes (ENG-649)

The agent cannot point the camera, touch the fingerprint sensor or read the
screen lock; these are the person's, on the debug build, phone unlocked.
Grant the camera when asked (or `adb shell pm grant io.islandbitcoin.foxy android.permission.CAMERA`).

1. **Scan a cashu token, a lightning invoice and an animated token.** On the
   Mac: `node tools/fake-invoice.js 8` for an invoice; a token from a test
   wallet on testnut.cashu.space; an animated token from cashu.me (Receive →
   a long token shows as a loop) or the frames of a case in
   `app/src/test/resources/fixtures/animated-qr.json` shown one after another.
   Display each as a QR code on the Mac's screen. In Foxy: SEND → the camera
   pane, and the full-screen scanner (Receive → paste/scan a token). Each fills
   the page; the animated one shows `SCANNING n%` and then the token. A hand's
   width from the screen is the close-focus case.
2. **The deep link (debug only).** `adb shell am start -a android.intent.action.VIEW -d 'foxy://receive?amt=50&unit=sat'`:
   Foxy opens on RECEIVE with 50 sat. A release build does not answer the link.
3. **Share a token.** SEND → a token → SHARE: Android's share sheet with the
   token as plain text, no link preview. A second SHARE while the sheet is up
   does nothing.
4. **Haptics.** A payment received (success), a wrong PIN (error), the long
   buzz of a tap: each is felt.
5. **"Payment received".** With notifications allowed (asked on the first one),
   a receive shows a notification titled Foxy saying "Payment received", and on
   the lock screen only "Foxy".
6. **The seed's protection.** SECURE FOXY → turn the screen lock on: Android
   asks for the fingerprint or the screen lock once; `adb shell ls
   /data/data/io.islandbitcoin.foxy/no_backup` (debug, `run-as`) shows
   `foxy-seed.device.v1.bin` and no `foxy-seed.v1.bin`. Force-stop and open:
   the wallet asks for the finger before it opens. Turn it off again: asked
   once more, and the files are back the other way.
7. **SHOW WORDS and RESTORE.** BACKUP → SHOW WORDS: the native screen ("On this
   phone · not the web page"), the words behind TAP TO REVEAL, VERIFY with the
   quiz; HOME while it is up and back: the screen is gone. RESTORE: type twelve
   words; a phrase copied inside Foxy is refused when pasted.

## The P2 device exit (ENG-648)

On the phone, debug build, unlocked, online. The page is driven over WebView
debugging (`tools/cdp-eval.js`); the mint is testnut.cashu.space (fake money),
and a melt pays an invoice `tools/fake-invoice.js` makes (testnut marks its own
invoices paid as it makes them, so a melt needs one from elsewhere).

```sh
export ANDROID_SERIAL=<the phone>
adb shell pm clear io.islandbitcoin.foxy
adb shell am start -n io.islandbitcoin.foxy/.MainActivity --es FoxyForceTransport direct   # then obfs4, then snowflake (force-stop between)
adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof io.islandbitcoin.foxy)
node tools/cdp-eval.js 9333 'for (var i = 0; i < 1200; i++) { if (FoxyWallet.privacy().tor === "up") return FoxyWallet.privacy(); await new Promise(function (r) { setTimeout(r, 500); }); }'
node tools/cdp-eval.js 9333 'await FoxyWallet.connect("https://testnut.cashu.space");
  var a = await FoxyWallet.invoice(64, "exit");
  for (var i = 0; i < 20; i++) { try { await FoxyWallet.claim(a.hash); break; } catch (e) { await new Promise(function (r) { setTimeout(r, 1500); }); } }
  return await FoxyWallet.pay("<an 8-sat invoice from node tools/fake-invoice.js 8>")'
# the opt-out: the dialog is pressed by instrumentation, then a mint and a melt without Tor
adb shell am instrument -w -e class io.islandbitcoin.foxy.DeviceExitTest -e foxyExit true \
  -e invoice "$(node tools/fake-invoice.js 8)" io.islandbitcoin.foxy.test/androidx.test.runner.AndroidJUnitRunner
```

Then the instrumented suite, with nothing skipped but
`KeystoreTest.onALockedPhone…`.

## The P2 leak test (an emulator)

Every non-loopback flow of Foxy's must go to a Tor relay (direct), one of the
seven obfs4 bridges (obfs4), Snowflake's CDN front, a STUN server or a
volunteer over UDP (Snowflake: outside Tor by design, ROUTE.md §3), or, only
after the person chose CONTINUE WITHOUT TOR, the phone's resolver and Foxy's
own hosts. No DNS query for any of Foxy's names in a Tor mode, and never one for
an onion.

```sh
avdmanager create avd -n foxy_leak -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
./gradlew assembleDebug
for m in direct obfs4 snowflake; do tools/leak-test.sh $m out/; done
tools/leak-test.sh unprotected out/ out/direct.consensus
```

`tools/leak-test.sh` starts the AVD wiped and headless with `-tcpdump`, turns
private DNS and the virtual Wi-Fi off (the capture sees the emulated cellular
path; Wi-Fi bypasses it), installs the debug APK, samples the app's sockets by
uid from `/proc/net` every 0.2 s (as root, which a google_apis image allows),
launches Foxy on the transport (`FoxyForceTransport`) or without Tor
(`FoxyNoTor`, debug builds only), waits for Tor, mints and melts on testnut,
and then runs `tools/leak-check.js` on the capture, the sockets and the
consensus Tor downloaded. A socket's local port is Foxy's only on the outside
address it held (a loopback port reused by another process is that process's).
The emulator stamps the capture with a clock of its own, so the opt-out's moment
is a marker lookup made just before the tap, read from the capture. The
captures are not committed.

Snowflake's WebRTC sends ICE checks to every candidate address its volunteer
offers, private ones included (192.168.x, 10.x on the volunteer's side); that is
Snowflake's, outside Tor by design, and the leak check accepts any UDP in
snowflake mode for it.

## Runs

Each run: the commit, the phone, and what each test answered. A skipped test
is not a pass; the device exit needs a run with nothing skipped but
`KeystoreTest.onALockedPhone…` (which runs only on a locked phone), plus check 7.

| Date | Commit | Phone | Instrumented | Check 7 |
|---|---|---|---|---|
| 2026-10-11 | review fixes on `jabariennis/eng-647-shell` | Pixel 8 Pro (42201FDJG00048), **locked** | 13 run: 11 passed, 2 skipped as locked (`KeystoreTest.theVaultRoundTrips…`, `PutAwayTest.puttingAway…`); `onALockedPhone…` passed | not run: needs the phone unlocked |
| 2026-10-11 | `3de3f577e3844799e2946a7fc58323476b332cc7` | Pixel 8 Pro (42201FDJG00048), **locked** (keyguard showing; the agent cannot unlock it) | 13 run: 11 passed, 2 skipped as locked (`KeystoreTest.theVaultRoundTrips…`, `PutAwayTest.puttingAway…`); `onALockedPhone…` passed; debug build with the `foxy-seed` thread check, no check tripped | not run: needs the phone unlocked |
| 2026-10-11 | `1344bd476f0af6c31f8ff0034ffd9ee545639044` | Pixel 8 Pro (42201FDJG00048), **unlocked** | `OK (13 tests)`: 12 passed, `KeystoreTest.onALockedPhone…` skipped as designed (phone unlocked) | pass: `pm clear` → launch → `[foxy] seed: made on the phone and saved` (`no_backup/foxy-seed.v1.bin`, 101 B) → `am force-stop` → launch: no second seed made, file sha256 `61468bcc…18eb` unchanged, and the live page asked over WebView debugging answered `seedStatus` `{"exists":true}` |
| 2026-10-11 | ENG-648 (`cb0c6b2`, the app as pushed) | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17, a build where the network is a permission (2026100601) | `OK (18 tests)`: 16 passed; `DeviceExitTest` skipped without `-e foxyExit true`, `KeystoreTest.onALockedPhone…` skipped as designed (phone unlocked). `DeviceExitTest -e foxyExit true`: `OK (1 test)` | not run (P1's) |
| 2026-10-11 | ENG-648 review fixes (on `fb0444f`) | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17 | 19 run: 17 passed (the new `PutAwayTest.finishingTheActivityParksTor…` among them; with `Tor.park()` taken out of `onDestroy` it fails, "Tor is parked once the activity finished"); `DeviceExitTest` skipped without `-e foxyExit true`, `KeystoreTest.onALockedPhone…` skipped as designed (phone unlocked) | not run (P1's) |
| 2026-10-11 | ENG-648 (`80d2be1`; the app changed after it, see `5f40e90` below) | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17, APKs uninstalled and installed fresh (INTERNET granted) | first full run on the fresh install: 19 run, 2 failed (`PutAwayTest.aReplyAfterTheWebViewIsGoneDoesNothing`: 3 `DROPPED_LINE`s where 2 were expected; `TorRouteTest.torLeavingTheNetworkMidRequest…`: the first request answered "Foxy is not connected to Tor." just after `Tor.isRunning`); both classes alone: `OK (4 tests)`, `OK (3 tests)`; the full suite again: `OK (19 tests)`. `DeviceExitTest -e foxyExit true`: `OK (1 test)`. The direct, obfs4 and Snowflake legs: pass (below) | not run (P1's) |
| 2026-10-11 | ENG-648 (`5f40e90`, the app as pushed) | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17, APKs uninstalled and installed fresh (INTERNET granted) | first full run on the fresh install: 19 run, 1 failed (`TorRouteTest.torLeavingTheNetworkMidRequest…`, "answered over Tor: Foxy is not connected to Tor.": the test's `Tor.restart` landed on a set-up already running, the SOCKS port moved, `circuit established` came, then every SOCKS handshake timed out for 30 s; ENG-652, logcat kept). The second fresh-install run in a row to hit it (`80d2be1` above). `TorRouteTest` alone: `OK (4 tests)`; the full suite again: `OK (19 tests)`. `DeviceExitTest -e foxyExit true`: `OK (1 test)`. The direct, obfs4 and Snowflake legs and the put-away check: pass (below) | not run (P1's) |
| 2026-10-11 | ENG-652 diagnosis: `61bc4b7` (main) with Tor at `Log info` (not committed) | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17, APKs uninstalled and installed fresh before each run | three runs: `OK (19 tests)`, then twice 19 run, 2 failed (`PutAwayTest.aReplyAfterTheWebViewIsGoneDoesNothing` 3 `DROPPED_LINE`s for 2; `TorRouteTest.torLeavingTheNetworkMidRequest…` "answered over Tor: Foxy is not connected to Tor."). Tor's info log: each set-up's `DisableNetwork 1` killed circuits being built ("died before the first hop with no connection"), Tor recorded a failure against each one's guard, and with the primaries "not reachable" every new stream sat "waiting for circuit" (12 SOCKS handshake timeouts in each failing run, 0 in the passing one) | not run |
| 2026-10-11 | ENG-652 (`jabariennis/eng-652-tor-setup-overlap`) | Pixel 8 Pro (42201FDJG00048), **unlocked** (home screen before each run), Android 17, APKs uninstalled and installed fresh before each run | three full runs in a row: `OK (19 tests)` ×3; `DeviceExitTest` skipped without `-e foxyExit true`, `KeystoreTest.onALockedPhone…` skipped as designed (phone unlocked). 5-7 retries or restarts joined a running set-up per run (12-13 set-ups where main made 19-20); no SOCKS handshake timed out in any run | not run |
| 2026-10-11 | ENG-649 (the branch as pushed) | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17, APKs uninstalled and installed fresh | the P3 classes (`QrDecodeTest`, `ClipboardSensitiveTest`, `SeedScreenStopTest`, `CameraUnbindTest`, `SeedProtectTest`, `FlagSecureTest`): `OK (10 tests)`. The full suite: 28 run, 1 failed, `PutAwayTest.aReplyAfterTheWebViewIsGoneDoesNothing` (3 `DROPPED_LINE`s where 2 were expected, the fresh-install flake of the `80d2be1` row); `PutAwayTest` alone: `OK (3 tests)`; `DeviceExitTest` and `KeystoreTest.onALockedPhone…` skipped as designed. Over WebView debugging: `foxy://receive?amt=50&unit=sat` opened SET AMOUNT with ₿ 50 (`[deeplink] #foxy=receive&amt=50&unit=sat`); `scan` opened the camera (`closest focus 105mm, zoom 1.00`), HOME answered `Cancelled.` and let the camera go; SHOW WORDS opened and HOME closed it, answered `{"verified":false}`; `captured` no, `notify` ok, `seedProtection` none. The by-hand P3 checks above are the person's | not run (P1's) |
| 2026-10-11 | ENG-649 review fixes (on `f856566`) | AVD `foxy_api29` (API 29 `default` arm64-v8a, emulator; PIN 1234 by `locksettings set-pin`) | `OwnPromptTest -e foxyPin 1234`: `theConfirmationIsNotAPutAway…` passed, `seedShowUnderTheScreenLock…` skipped (the image's AOSP WebView lacks a feature Foxy needs, `[foxy] this phone's WebView lacks a feature Foxy needs; the page is not loaded`). With `ownPrompt = { false }` (the fix taken out) it fails: "the epoch did not move expected:<3> but was:<4>". `SeedProtectTest` alone: `OK (2 tests)`; before its fix it failed on API 29 (`UserNotAuthenticatedException` at `Cipher.init`, outside its `runCatching`), and run straight after `OwnPromptTest` it fails because the 10 s of that confirmation still open a new time-bound key. `PutAwayTest`: 3 failed, the page never loads on this image (`Page.STARTED`). SHOW WORDS under `device` on API 28-29 is still to be run on an image or phone whose WebView loads the page | not run (P1's) |

### P2 device exit, 2026-10-11 (Pixel 8 Pro 42201FDJG00048, debug builds of ENG-648)

Every leg ran on `5f40e90`, the app as pushed, with the phone unlocked
throughout (the keyguard checked before each leg). The direct leg started
from `pm clear`; obfs4 and Snowflake followed with `am force-stop` between,
so the balance carries over.

| Leg | Result |
|---|---|
| direct (`pm clear`, then launch) | Tor up 7.1 s after the page asked (`transport: "direct"`); a mint of 64 sats claimed and a melt of 8 paid on testnut.cashu.space over Tor (`{"claimed":true,"paid":true}`, balance 0 → 64 → 53) |
| obfs4 (`FoxyForceTransport obfs4`) | up in 19.2 s through obfs4 (`transport: "obfs4"`); mint and melt over Tor (53 → 117 → 106) |
| Snowflake (`FoxyForceTransport snowflake`) | up in 18.6 s through Snowflake (`transport: "snowflake"`); mint and melt over Tor (106 → 170 → 159) |
| CONTINUE WITHOUT TOR | `DeviceExitTest -e foxyExit true`: `OK (1 test)`: Tor parked, the dialog's Continue pressed, a mint and a melt over the open connection, then off again |
| put away with Tor up | Tor up (direct), then `input keyevent KEYCODE_HOME`: `[foxy] tor: off the network while Foxy is in the background` 5.8 s later; 30 s away, then `am start` brought the task back: `setting up a private connection (back after 29s)`, a new SOCKS port, `circuit established` and the page's `tor: "up"` 1.5 s after the return; a mint of 8 claimed over Tor (212 → 220) |
| a Tor that stops while away | not triggerable on the phone with the allowed actions (ending Tor's thread without ending the app needs more than `am`/`pm`/`input`): covered by unit tests only (`TorMachineTest`: `aStoppedTorPutAwayIsLaunchedAgainByTheNextActivity`, `aRetryWhileAwayLaunchesAStoppedTorOffTheNetwork`, `aStartWhileAwayLaunchesAStoppedTorOffTheNetwork`, `aRetryWhileAwayLaunchesATorThatStoppedWhileParkedOffTheNetwork`, `aTorLaunchedWhileAwayBootsOffTheNetwork`) |
| instrumented suite | `OK (19 tests)` above (the second full run; the first run's failure is in the row above) |

### P2 leak test, 2026-10-11 (AVD foxy_leak: API 35 google_apis arm64, emulator 37.1.11; the debug build of ENG-648)

| Mode | Capture | Foxy's flows | Result |
|---|---|---|---|
| direct | 65,508 packets, 48 DNS queries (the OS's) | 4: Tor relays (guards and directory) | no leak; Tor up in 13 s, mint and melt over Tor |
| obfs4 | 70,291 packets, 52 DNS queries | 16: the seven obfs4 bridges, nothing else | no leak; up in 23 s, mint and melt |
| Snowflake | 77,765 packets, 69 DNS queries | 71: the CDN front (TCP 443, by the front's name), STUN (UDP 3478, by the servers' names), the volunteer's ICE candidates (UDP) | no leak; up in 78 s, mint and melt |
| CONTINUE WITHOUT TOR | 13,325 packets, 51 DNS queries | 10, every one after the opt-out marker: the resolver, minibits (the page's default mint), Kraken and Bitstamp (the price), testnut | no leak; nothing before the opt-out; mint and melt over the open connection |

No Tor-mode run looked up any of Foxy's names or any onion.
