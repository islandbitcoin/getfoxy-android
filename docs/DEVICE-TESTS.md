# DEVICE-TESTS.md — what is checked on a phone, by hand or by instrumentation

Some of the host's claims (`SECURITY-INVARIANTS.md`) can only be shown on a real
phone. This is the list: the instrumented tests that run on one, the checks a
person makes by hand, the P2 device exit, and the P2 leak test (on an emulator,
where every packet can be captured). CI builds the debug APK and the test APK
but has no phone, so none of this runs there; each pull request says which of
these it ran, on which phone, and what each answered.

## Real money

Android is tested on **real mints with real ecash** (the person's decision,
2026-10-12, ENG-662). testnut.cashu.space (fake money) is no longer the test
mint. The default is the page's own default mint,
`https://mint.minibits.cash/Bitcoin`. Every mint or melt in this document moves
real sats, so these rules hold for every leg, by hand or scripted:

1. **The person's explicit go, every run.** No real-money leg runs without the
   person saying go for that run. `DeviceExitTest` refuses without
   `-e realMoney yes`, and `tools/leak-test.sh` without `REAL_MONEY=yes`. No
   agent runs a real-money leg: the person and the architect do.
2. **Small amounts: 100 sats or less a leg by default.** A leg names its cap
   (`-e maxSats <n>`, `MAX_SATS=<n>`) and anything above it is refused. A cap
   above 100 needs the person to raise it by name for that run
   (`-e overDefaultCap yes`, `OVER_DEFAULT_CAP=yes`).
3. **The person pays the mint quotes from their own wallet.** The leg prints
   the mint's invoice (in `am instrument`'s output and logcat tag `foxy-exit`,
   or on the leak test's stderr), waits for it to be paid (`payTimeoutSeconds`,
   default 600) and stops, melting nothing, if it is not. On a phone the
   quote stays in Foxy; on the leak test's emulator, which is wiped on every
   start, the run leaves the emulator running instead (see "Leftover sats"
   under the leak test).
4. **Melts pay only an invoice the person supplies** from their own wallet
   (`-e invoice <bolt11>`, `INVOICE=<bolt11>`). It is read before anything
   starts: a mainnet BOLT11 with an amount, a whole number of sats, at most the
   cap, and leaving room under the amount minted for the mint's Lightning fee
   reserve (Foxy's melt owes the amount and the reserve): the invoice plus 2
   sats, or plus 1% of the amount minted rounded up when that is more, is at
   most the amount minted. 64 sats minted pays an invoice of at most 62.
   Anything else is refused.
5. **No fake invoices on real mints.** `tools/fake-invoice.js` makes an invoice
   that pays no one: it is for the scanner check below (scanned, never paid)
   and the tools' own tests, never a melt.

The rules are code as well as words: `RealMoney`
(`app/src/sharedTest/java/io/islandbitcoin/foxy/RealMoney.kt`, tested on the
JVM by `RealMoneyTest`, applied by `DeviceExitTest`) and `tools/real-money.js`
(tested by `tools/real-money.test.js`, applied by `tools/leak-test.sh`).

The rest of the instrumented suite moves no money: `TorRouteTest` reads the
real mint's `GET /v1/info` (its description) over Tor, nothing else.

## The package name

Debug and release builds install as `io.islandbitcoin.getfoxy`, the Google Play
app's package (ENG-680), and the test APK as `io.islandbitcoin.getfoxy.test`.
Class names keep the Kotlin namespace, so an explicit component is
`io.islandbitcoin.getfoxy/io.islandbitcoin.foxy.MainActivity` (the short
`/.MainActivity` would look for `io.islandbitcoin.getfoxy.MainActivity`, which
does not exist), and `-e class` takes `io.islandbitcoin.foxy.<Test>`. A phone
with an older dev build installed as `io.islandbitcoin.foxy` keeps it as a
separate app with its own wallet: restore the wallet from its words in the new
app, then `adb uninstall io.islandbitcoin.foxy`. Rows in the results table
below from before ENG-680 ran the old package.

## Instrumented (`app/src/androidTest`)

Install both APKs and run them on an unlocked phone with nothing else of Foxy's
on it:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r io.islandbitcoin.getfoxy.test/androidx.test.runner.AndroidJUnitRunner
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
| `DeviceExitTest` | the opt-out leg of the device exit, below, on a real mint: runs only with `-e foxyExit true`, and then only under the money rules above (refused, before anything is launched, without `-e realMoney yes` or with an invoice above `-e maxSats`) | S11 |
| `DeviceExitRefusalTest` | `DeviceExitTest`'s money rules read from instrumentation arguments: no money without `realMoney yes`, no invoice above `maxSats`. Launches nothing, contacts nothing; `RealMoneyTest` has the rules in full on the JVM. ENG-662; compiled, not yet run on a phone | ENG-662 |
| `QrDecodeTest` | the scanner's decode path without the camera: a cashu token and a lightning invoice drawn as QR codes (straight, at an angle in a larger frame, upside down) read back through zxing-cpp, the APK's own native decoder; every frame of the iOS animated-QR fixture read back and gathered into its token | P3 |
| `ClipboardSensitiveTest` | a sensitive copy carries `IS_SENSITIVE` and Foxy's label and is cleared once its 120 s are up (an injected clock), a plain one is not; the paste button reads the clipboard only in its tap | S23 |
| `SeedScreenStopTest` | SHOW WORDS is `FLAG_SECURE`; HOME with it open finishes it, answered as closed; a screen closed before it opened is answered and shows nothing | S19, S20 |
| `CameraUnbindTest` | the live pane binds the camera, and the activity stopping unbinds it at once; a second stop does nothing (grants CAMERA) | S25 |
| `SeedProtectTest` | the `device` key needs the person for every use (API 30+; on API 28-29 it refuses at `Cipher.init` outside the 10 s after a confirmation) and survives a new fingerprint; a move to `device` the person does not approve leaves the `none` seed readable and the protection unchanged. Needs a screen lock | S3, S5 |
| `OwnPromptTest` | API 28-29 only, with `-e foxyPin <pin>` (skipped otherwise; it types the PIN with `input`): Settings' screen-lock confirmation stops MainActivity, and that stop is not a put-away. The vault's epoch does not move, the seed read under it is kept, the door stays open, and PutAway logs that it put nothing away; taking the guard off within 10 s of that yes still asks (`fresh`); Back on the confirmation after 2.5 s is not a yes, so the skipped put-away runs (the epoch moves) and PutAway logs a return. With a WebView that loads the page, a `seedShow` of a `device` seed opens SHOW WORDS once the PIN is given (skipped on an image whose WebView cannot load the page) | S3, S20, S21 |
| `BluetoothApproveTest` | what the first-install Bluetooth screen's APPROVE posts (`bluetoothAsk`) comes back through the real shim twice, in iOS's order: `window.__foxyBluetoothAnswer(state)`, which closes the screen, then the reply `state` on its id; and the gate's own screen, launched with `FOXY_SHOW_WARNING` (debug builds) and raised through the banner's `leaveOffline` on a phone with a wallet, closes after PROCEED then APPROVE with `foxy.bt.asked` = `approved, iOS said <state>` (the two keys are put back). Since ENG-670 the state is the phone's: BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT are granted to the app first (API 31+, so no dialog comes up), and the answer must be what `BluetoothAnswer` reads from this phone's adapter: `allowed` with Bluetooth on, `off` with it off, `unsupported` on a phone that cannot advertise. Run it once with Bluetooth on and once off. The ENG-656 form (`unsupported` on every phone) passed on a Pixel 8 Pro (Android 17) 2026-10-12; the ENG-670 form is compiled, not yet run on a phone | ENG-656, ENG-670 |
| `TapGattOnDeviceTest` | the receiver's GATT service as Android's own objects (`AndroidTapPeripheral.service`): one primary service, the four doors in iOS's order with iOS's properties and permissions, a CCCD (read, write, no encryption) on each notify door and none on the in door; and Android's `BluetoothGattCharacteristic`/`BluetoothGatt`/`BluetoothGattDescriptor` constants equal to the ones `TapGatt` spells out for the JVM tests; and the advertisement (`AndroidTapPeripheral.advertiseData`, `advertiseSettings`) as the iPhone payer accepts it (TapLink.swift 1154-1157): exactly one service UUID, the service's, no device name, no TX power level, no manufacturer or service data, connectable, no timeout. Opens no GATT server and needs no permission. ENG-670; compiled, not yet run on a phone | ENG-670 |
| `TapCentralOnDeviceTest` | the payer's scan as Android's own objects (`AndroidTapCentral.scanFilters`, `scanSettings`): no hardware filter at all (ENG-679: the per-invoice UUID cannot be named before it is heard, and the all-zero-mask "any service UUID" filter of ENG-671 matched nothing on the Pixel 8 Pro's controller); low latency, every advertisement reported, no batching; and Bluetooth's Device Information UUIDs and the MTU asked for. Opens no scanner and needs no permission. ENG-671, ENG-679; compiled, not yet run on a phone | ENG-671, ENG-679 |
| `TapCryptoOnDeviceTest` | tap to pay's crypto through the phone's own providers: Tink's X25519 in the APK and Conscrypt's ChaCha20-Poly1305 (which the desk JVM does not have) give iOS's bytes from `fixtures/tap-vectors.json` (the keys, the code, every seal, and the first conversation's handshake and offer); the JVM's `TapVectorsTest` checks the whole file. ENG-669; passed on the Pixel 8 Pro (Android 17) 2026-10-12 at main `d856d3b`, `OK (3 tests)`, recorded under "Runs": the gate below is met for ENG-670 | ENG-669 |
| `TextZoomTest` | with the system font scale set to 1.3 through the shell (the phone's value put back afterwards), a WebView the activity makes and leaves alone starts at `textZoom` 130 (the control), and Foxy's is `WebHost.PAGE_TEXT_ZOOM`, 100: the page is laid out as WKWebView lays it out on iOS, whatever the font size. Skipped, saying why, when the scale cannot be set or a plain WebView does not follow it. ENG-661; compiled, not yet run on a phone | ENG-661 |
| `NotifyChannelTest` | the payments channel `payments_heads_up` is `IMPORTANCE_HIGH` (heads-up, as iOS's foreground banner), and a phone holding the default-importance `payments` an earlier build made loses it when the channel is made (ENG-665). A channel the person turned off or down in Settings fails the first test: turn it back on | — |
| `SplashCoverViewTest` | MainActivity's splash cover: the intro's still is an `ImageView` (`CENTER_CROP`, the still read from the APK) as the container's last child, drawn above every sibling (its `translationZ`), a view added after it included; the page's `covered` takes it down and it is out of the view tree within `SplashCover.FADE_MS` plus 1 s; a second `covered` is no news. Fails when MainActivity made no cover; skipped, saying why, only when the cover was made and the page said `covered` before the test could look. ENG-665; compiled, not yet run on a phone | ENG-665 |

**A hard gate for tap to pay's radio (ENG-670, ENG-671).** `TapCryptoOnDeviceTest`
must pass on a phone, and the run be recorded under "Runs" below, before any
radio code that depends on `TapCrypto`'s seal and open merges. CI and the desk
JVM never run the branch Android takes: the JDK has no
`ChaCha20/Poly1305/NoPadding`, so `TapCrypto.cipher()` falls back to the JDK's
`ChaCha20-Poly1305`, and Conscrypt's handling (the `IvParameterSpec`,
`updateAAD` before `doFinal`, `AEADBadTagException` on a bad tag) is checked
nowhere else. If it differed from the JDK's, every sealed message from M4 on
would fail on a phone while every JVM test stayed green. Putting Conscrypt on
the JVM test classpath (`org.conscrypt:conscrypt-openjdk-uber`) would move the
check into CI, but it is a new pin and needs the person's supply-chain sign-off
first, as Tink did; until then this run is the gate (ENG-669 review).
**Met 2026-10-12**: `TapCryptoOnDeviceTest` passed on the Pixel 8 Pro
(42201FDJG00048, Android 17) at main `d856d3b`, whose `TapCrypto` and
`TapCryptoOnDeviceTest` ENG-670 leaves unchanged; the row is under "Runs". A
change to `TapCrypto` or its providers needs the run again.

P2 starts Tor with the page, so `PageBootTest`, `PutAwayTest` and
`NetBlockTest` wait for the page to boot in whatever state Tor is in
(`Page.STARTED`) rather than on P1's CANNOT CONNECT TO TOR; what they assert is
unchanged, and `NetBlockTest` now also asserts the app holds INTERNET: the
walls hold with the permission granted. Every instrumented run with the phone
online contacts mint.minibits.cash (the page's default, a real-money mint) over
Tor: the page's own connect, and `TorRouteTest`'s `GET /v1/info`. Neither
moves money.

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
2. **No backup.** `adb shell bmgr backupnow io.islandbitcoin.getfoxy` reports the
   package is not eligible; `adb backup` of the app is empty. (S1)
3. **A reinstall starts empty.** With a seed made, uninstall and install again:
   the first `seedStatus` answers `{"exists":false}` and a new seed is made; the
   old one is gone with the Keystore key. (S22)
4. **A release build says nothing.** `adb logcat --pid=$(adb shell pidof io.islandbitcoin.getfoxy)`
   while using a release build shows no line from Foxy. (S18)
5. **The intro video plays with the network blocked.** A fresh install shows
   the launch intro (`foxy-intro.mp4`) through to the gate. (ANDROID-MAPPING
   decision 2)
6. **Nothing reaches the network but Tor.** P1 checked this with PCAPdroid;
   from P2 it is the leak test below, on an emulator, which captures every
   packet without a third-party app on the phone. (S8, S9, S10)
7. **The seed outlives a restart.** On an unlocked phone: let the page make a
   seed (`seedCreate`), `adb shell am force-stop io.islandbitcoin.getfoxy`, launch
   again: `seedStatus` answers `{"exists":true}` and the page shows the same
   wallet. (S3, S4; the ENG-647 device exit.) Wiping the seed is not checkable
   in P1: `seedWipe` is P3 (BRIDGE.md `seedWipe` — P3, S5).

## The P3 checks a person makes (ENG-649)

The agent cannot point the camera, touch the fingerprint sensor or read the
screen lock; these are the person's, on the debug build, phone unlocked.
Grant the camera when asked (or `adb shell pm grant io.islandbitcoin.getfoxy android.permission.CAMERA`).

**Real money.** These checks run on the page's default mint,
mint.minibits.cash, with real sats, under the rules in "Real money" above: the
person's go, 100 sats or less a leg, quotes and invoices from the person's own
wallet. (This replaces ENG-661's advice to use testnut.cashu.space.)

1. **Scan a cashu token, a lightning invoice and an animated token.** On the
   Mac: `node tools/fake-invoice.js 8` for an invoice (scan it only: it pays
   no one and is never paid on a real mint), or an invoice of a few sats from
   the person's own wallet; a token of a few sats from the person's own wallet
   on mint.minibits.cash; an animated token from cashu.me (Receive →
   a long token shows as a loop) or the frames of a case in
   `app/src/test/resources/fixtures/animated-qr.json` shown one after another.
   Display each as a QR code on the Mac's screen. In Foxy: SEND → the camera
   pane, and the full-screen scanner (Receive → paste/scan a token). Each fills
   the page; the animated one shows `SCANNING n%` and then the token. A hand's
   width from the screen is the close-focus case.
2. **The deep link (debug only).** `adb shell "am start -a android.intent.action.VIEW -d 'foxy://receive?amt=50&unit=sat'"`:
   Foxy opens on RECEIVE with 50 sat. A release build does not answer the link.
   The command goes to the phone's shell as one string, so the inner quotes
   reach it: without the outer double quotes the phone's shell sees a bare
   `&`, runs the link without `&unit=sat` and Foxy opens on 50 in another unit
   (USD 50.00 in the 2026-10-12 check).
3. **Share a token.** SEND → a token → SHARE: Android's share sheet with the
   token as plain text, no link preview. A second SHARE while the sheet is up
   does nothing.
4. **Haptics.** A payment received (success), a wrong PIN (error), the long
   buzz of a tap: each is felt.
5. **"Payment received".** With notifications allowed (asked on the first one),
   a receive shows a notification titled Foxy saying "Payment received", and on
   the lock screen only "Foxy".
6. **The seed's protection.** SECURE FOXY → turn the screen lock on: Android
   asks for the fingerprint or the screen lock once; `adb shell run-as
   io.islandbitcoin.getfoxy ls -l no_backup` (debug builds only; a plain
   `adb shell ls /data/data/io.islandbitcoin.getfoxy/no_backup` is
   `Permission denied`) shows `foxy-seed.device.v1.bin` and no
   `foxy-seed.v1.bin`. Force-stop and open:
   the wallet asks for the finger before it opens. Turn it off again: asked
   once more, and the files are back the other way. Since the pin to
   getfoxy/iOS `e35eacf7f9941f9a00b611d0a580d08d6defb426` the menu's switch
   reads FACE / FINGER LOCK; the page's other labels still say Face ID on
   Android (core/web, ENG-663). The system prompt is what this checks.
7. **SHOW WORDS and RESTORE.** BACKUP → SHOW WORDS: the native screen ("On this
   phone · not the web page"), the words behind TAP TO REVEAL, VERIFY with the
   quiz; HOME while it is up and back: the screen is gone. RESTORE: type twelve
   words; a phrase copied inside Foxy is refused when pasted.

8. **The receive screen's TAP (ENG-656). Pending: the architect's on-device
   check; no phone was used for this change.** Since ENG-670 this is what a
   phone *without* a Bluetooth LE peripheral shows (an emulator without
   Bluetooth will do); on the Pixel the receive screen goes on the air, which
   is the check *Tap to pay: the Pixel receives* below. RECEIVE → an amount → NEXT, and
   press TAP on the invoice screen: the TAP HERE card says HOLD A PHONE HERE,
   never UNKNOWN ACTION: TAPRECEIVESTART, and the TAP button keeps the accent
   colour (not greyed). On a debug build,
   `adb logcat --pid=$(adb shell pidof io.islandbitcoin.getfoxy)` shows
   `[tap] receive: no tap to pay on Android yet; telling the page unsupported`
   once as the receive screen arms itself (again after leaving it, but not
   after putting Foxy away and coming back, which keeps the receiver as iOS
   does), and never the page's `tap: not advertising` warning.
   Nothing asks for a Bluetooth permission. The conformance flow *P3: the
   receive screen's TAP …* and `TapReceiveTest` cover the same in jsdom and the
   JVM; this is the check on the real WebView.
   The 2026-10-12 run was inconclusive for this check ("not sure what
   happened"): the field log or logcat for `[tap] receive:` and
   `tap: not advertising` is still to be read, so there is no result yet.
9. **Large text (ENG-661). Pending: the architect's on-device check; no phone
   was used for this change.** With the phone's font size raised (Settings →
   Display → Font size; the Pixel 8 Pro of the report ran font_scale 1.3 with
   display size at 544 dpi) and display size raised too: BACKUP's sheet that
   rises from the bottom, the receive screen and the send screen show every
   button's text inside its button, as on iOS. Foxy's text does not grow with
   the font size (`WebHost.PAGE_TEXT_ZOOM`, ANDROID-MAPPING decision 7);
   native screens (SHOW WORDS, the dialogs) still do.
10. **A card's owner key from the same words (ENG-666). Pending: the
    architect's on-device check; no phone was used for this change.** The JVM
    tests hold the Kotlin to iOS's vectors (`CardOwnerTest`,
    `SeedActionsTest`, `CardBridgeTest`); `CardOwnerOnDeviceTest`
    (`./gradlew connectedDebugAndroidTest`) checks the same keys through the
    phone's own providers (Conscrypt, not the desk JVM's). Then by hand: on the
    Pixel restored from the iPhone's twelve words, and on the iPhone, post
    `cardOwnerKey` with the key of a card the iPhone set up, as the page's
    `cardOwnerPub` does (WebView debugging and `tools/cdp-eval.js` on the
    Pixel, Safari's Web Inspector on the iPhone): the two `pub` answers are the
    same 130 characters. `cardTime` answers the phone's clock and a signature.
    No card is needed and nothing moves; the card itself is A5 (the NFC link).

## Tap to pay: the Pixel receives (ENG-670)

**Pending: the person's, with two phones. No phone was used for this change,
and nothing here moves money unless the person chooses to (step 6).** The JVM
tests run the receiver against a paying `TapPayerLink` over a fake GATT stack
(`TapGattServerTest`, `TapBridgeTest`): every message, long write, CCCD and
wait. Only two phones prove that CoreBluetooth on an iPhone and Android's GATT
server agree. **The hard gate above holds first**: `TapCryptoOnDeviceTest`
must have passed on the Pixel and be recorded under "Runs", or every sealed
message from M4 on may fail on the phone while the JVM stays green. It has:
2026-10-12, `OK (3 tests)` (the row under "Runs"). Still pending, the
person's: `TapGattOnDeviceTest`, `BluetoothApproveTest`'s ENG-670 form, and
every two-phone step below.

The phones: the iPhone **Max** with Foxy (getfoxy/iOS at `core/PIN`'s commit
or later, a debug build so its diary prints the `[tap] pay:` lines), funded
with a few sats at the mint the Pixel's Foxy uses; the **Pixel 8 Pro** with the
debug build of this branch. Both unlocked, Bluetooth on, Foxy in front on each.
On the Pixel, follow the diary with
`adb logcat --pid=$(adb shell pidof io.islandbitcoin.getfoxy) | grep '\[tap\]'`.

1. **The question.** On a fresh install of the Pixel build, PROCEED, then
   APPROVE on the Bluetooth screen: Android's own dialog asks for "Nearby
   devices" (API 31+). Allow: the screen closes, and `foxy.bt.asked` (read over
   WebView debugging, `tools/cdp-eval.js`) is `approved, iOS said allowed`. A
   second install where the dialog is refused: `approved, iOS said denied`, and
   the receive screen's TAP button is greyed (the page took `denied`). With
   Bluetooth off before APPROVE: `approved, iOS said off`.
2. **On the air, and nothing else.** RECEIVE → an amount → NEXT on the Pixel.
   The diary says `[tap] receive: on the air as <UUID>`. With a BLE scanner on a
   third device (nRF Connect, or the iPhone's own scan in step 3), the Pixel's
   advertisement carries that 128-bit service UUID and nothing else: no name, no
   TX power, no manufacturer data, connectable. Leave the screen: the
   advertisement stops (`tapReceiveStop`). Put Foxy away on the invoice screen
   (HOME): it stops within a second (`Foxy was put away; off the air`), and
   comes back with a **new** UUID when Foxy is in front again.
3. **The iPhone finds the Pixel.** Pixel on the invoice screen, on the air.
   Bring the iPhone, Foxy on its home screen, towards it: the iPhone's diary
   says `[tap] pay: something at <n> dBm, not close enough` as it nears, then
   TAP TO PAY on its screen, and the Pixel's TAP card shows CONNECT TO PAY at
   the edge the iPhone chose (`nearby` with `left` or `right`). A filter line
   `[tap] pay: the filter passed by …` that counts the Pixel as not Foxy is a
   failure: the advertisement is not what iOS expects.
4. **The link.** Touch the phones (back to back, tops together, as two iPhones
   are held): both screens show the same four digits (the Pixel's page heard
   `connecting`, then `code`), the Pixel's diary says `a phone is listening;
   its first word takes the place`, and the iPhone shows the Pixel's offer. The iPhone's writes are 512 bytes, so the Pixel's
   GATT server took prepared writes (Android logs nothing for them: a link that
   reaches the four digits has carried M1 and M3 that way).
5. **A payment, only with the person's go (docs/DEVICE-TESTS.md "Real money":
   at most 100 sats, the person's own mint).** The iPhone pays the Pixel's
   invoice screen for a small amount with change due (the iPhone's ecash larger
   than the amount): PAYMENT RECEIVED on the Pixel, the iPhone's change
   written down (`tapChange` answered `ok`, not the 7 s "did not say it kept
   the change"), and both balances agree with what was paid.
6. **Shake and turn.** On the Pixel's invoice screen with something to offer,
   shake the phone twice, sharply: TAP is pressed (`[foxy] tap: shaken; pressing
   TAP`). Off that screen a shake does nothing. Turn the Pixel upside down and
   back: the invoice screen turns round and back (`_turned`).

**The near threshold on the Pixel (for the upstream table, A12).** iOS takes a
payer as touching at a median of **−33 dBm** (`nearDbm`,
`Foxy/Bluetooth/TapProtocol.swift:278`), measured between two iPhones; the
Pixel advertises at `AndroidTapPeripheral.TX_POWER` (`ADVERTISE_TX_POWER_HIGH`)
through its own antenna, so the same distance reads differently. Measure it and
record the method with the numbers:

- Receiver: the Pixel on its invoice screen, on the air, lying face up on a
  wooden table, nothing metal within 30 cm, Wi-Fi left as it is (note it).
- Payer: the iPhone Max, Foxy on its home screen, its diary followed (Xcode's
  console or the LOGS screen), reading the `[tap] pay: something at <n> dBm`
  and `a receiver at <n> dBm (needs …)` lines, which are the medians the
  proximity rule decides on.
- Positions, each held still for 10 s, three times, phones swapped round
  between runs: back to back touching; touching, tops together, the iPhone's
  top edge over the Pixel's camera bar; 1, 2, 5, 10 and 20 cm apart, faces
  parallel; the iPhone in a case and without.
- Record per position the median and the spread of the readings, and the
  closest position that reaches −33 dBm. Repeat once with the Pixel on
  `ADVERTISE_TX_POWER_MEDIUM` (a debug build with `TX_POWER` changed; do not
  commit it) so the table says what the setting buys.
- Write the table under "Runs" below with the date, both phones' OS versions,
  the commit, and whether each position counted as touching on the iPhone
  (TAP TO PAY taken without pressing it). It feeds the edge and threshold
  table upstream (`TapEdges`, `TapProximity` thresholds per receiver model):
  the iPhone has to learn the Pixel as a receiver, which is an upstream
  change, not this one.

## Tap to pay: the Pixel pays (ENG-671)

**Pending: the person's, with two phones (three for Pixel to Pixel). No phone
was used for this change, and nothing here moves money unless the person
chooses to (the payment steps, under "Real money": at most 100 sats, the
person's own mint).** The JVM tests run Android's payer (`TapPayer` over a fake
GATT client, `TapPayerTest`, `TapPayBridgeTest`) against Android's own
receiver at the ATT level: the scan rules, the RSSI gates, the quiet link, the
near doors, every message (M1 to M12, a payment in many parts as long writes,
change and M8), and every way a link ends. Only phones prove that Android's
GATT client and CoreBluetooth's peripheral agree, and that the thresholds mean
touching on a Pixel. **The hard gate above holds** (`TapCryptoOnDeviceTest`,
met 2026-10-12). Also pending, the person's: `TapCentralOnDeviceTest` (the scan
filter and settings as Android builds them; compiled, not yet run). ENG-679:
the ENG-671 build's scan heard nothing on the Pixel 8 Pro (its all-zero
service filter matched no advertisement); since then the scan has no hardware
filter, and the JVM's fake controller matches nothing with an all-zero mask.
The same build then heard the iPhone Max and had its first word refused
(ATT error 8, iOS's answer to a write from a phone that does not hold the
place). Bluetooth's own log on the Pixel showed why: on every connect Android
17's stack runs its own service discovery (about 2.4 s) and holds the app's
MTU question, discovery and signal reading behind it, so the quiet link looked
silent about its strength, warming went off, the payer never subscribed to a
near door, never heard that the iPhone was still on its amount screen, and
spoke to it anyway. Since then the silence is counted from when the services
are found, and a late GATT answer (the MTU's `onMtuChanged`, 0.3 s after it
was given up on) is never taken for the operation in flight. The JVM air
models the slow discovery (`TapPayerTest.aSlowDiscovery…`, which reproduced
the Pixel's diary line for line before the change). Step 4 below is still the
person's: a touch with the Max on its amount screen shows `that phone is still
on its amount screen; no card yet` and no `ATT error`, and after NEXT the tap
goes through.

The phones: the **Pixel 8 Pro** with the debug build of this branch, the
iPhone **Max** with Foxy (getfoxy/iOS at `core/PIN`'s commit or later, a debug
build so its diary prints `[tap] receive:` lines), and, for Pixel to Pixel, a
second Android phone (API 31+; one at 28-30 checks only that it does not pay)
with the same build. Both unlocked, Bluetooth on, Foxy in front on each, each funded
with a few sats at the mint the other uses. Follow the Pixel's diary with
`adb logcat --pid=$(adb shell pidof io.islandbitcoin.getfoxy) | grep '\[tap\]'`.

1. **The question, once (API 31+).** On a fresh install, PROCEED, then APPROVE
   on the Bluetooth screen: one Android dialog, "Nearby devices". Allow. Then in
   Settings › Apps › Foxy › Permissions, "Nearby devices" is allowed and
   **Location is not listed at all** (BLUETOOTH_SCAN is `neverForLocation`).
   Fund the wallet and go home: no second dialog. On a phone at API 28-30 (if
   one is at hand) APPROVE puts up **no** dialog at all (the receiver's
   permissions are install-time there, and the payer does not run below 31,
   `BluetoothAnswer.pays`, because a scan there needs location), Settings lists
   no Location permission for Foxy, and the home screen's listen says
   `this Android cannot pay by tap (API 31+)` and is `_tap` `unsupported` on
   the pay side, once. Refused at APPROVE on API 31+ and then allowed in
   Settings › Apps › Foxy › Permissions: back in Foxy the diary says `Foxy is
   back and the Bluetooth permissions are granted now` and the listen scans,
   with no second `tapPayStart`.
2. **ENG-658: one listen, not a re-ask loop.** Home screen of a funded wallet,
   Bluetooth **off**: the diary says `[tap] pay:` nothing, the page shows
   Bluetooth off, and over two minutes `adb logcat | grep -c tapPayStart` (with
   the bridge's debug log) or the field log shows **one** `tapPayStart`, not
   one every few seconds. Turn Bluetooth on: the same listen starts scanning
   (`dumpsys bluetooth_manager | grep -A3 -i "le scan"` lists Foxy's scan),
   still with no second `tapPayStart`.
3. **Nothing scans while Foxy is away.** Home screen, listening. HOME: within a
   second the diary says `Foxy was put away; not listening` and
   `dumpsys bluetooth_manager` no longer lists Foxy's scan. Back to Foxy:
   `Foxy is back; listening again`, and the scan is listed again.
4. **The Pixel pays the iPhone Max.** The Max on RECEIVE → an amount → NEXT
   (on the air). Bring the Pixel, Foxy on its home screen, towards it: the
   Pixel's diary says `something at <n> dBm, not close enough (…)` as it nears;
   a hand apart, TAP TO PAY on the Pixel and CONNECT TO PAY on the Max, the
   Max's mark on the edge the Pixel chose (the Pixel reads the Max's model,
   `that phone is a iPhone…; this one's <edge> edge to its <edge>`). A line
   `the filter passed by …` at the end of a listen that counts nothing as Foxy
   while the Max was on the air is a failure (its advertisement was refused).
   Touch the phones: `a receiver at <n> dBm (needs -33, advertised), already
   linked`, then both screens show the same four digits and the Pixel's
   confirmation opens with the Max's amount. Pay only with the person's go: a
   small amount with change due, PAYMENT RECEIVED on the Max, the change kept on
   the Pixel (`change written down; telling the receiver`, and the Max's
   `tapChange` answered `ok`), balances agree. Then TAP TO PAY pressed from a
   hand apart (not touching) takes the Max within five seconds.
5. **The iPhone Max pays the Pixel.** As *Tap to pay: the Pixel receives*
   steps 3 to 5 above, which this change leaves as they were: the Pixel on
   the air, the Max listening from its home screen.
6. **Pixel to Pixel** (a second Android at hand). One receives, the other
   pays, then swap: the four digits match, a payment with change crosses, and
   each diary shows the other's service UUID. With no Device Information
   service on an Android receiver the edges are the default (payer left,
   receiver right) until A12 gives Pixels their own.
7. **The receiver backs out while the Pixel's card is up.** The Max on its
   invoice screen, the Pixel a hand apart until TAP TO PAY is up on it and
   CONNECT TO PAY on the Max. Back out on the Max (BACK to its amount
   screen): within a second the Pixel's diary says `the receiver went off the
   air; the card comes down`, its TAP TO PAY goes (`far`), and it does not come
   back while the Max stays off its invoice screen (Android's
   `onServiceChanged`, API 31+, which is every API the payer runs on). A card
   that stays up is a failure: say so with the diary from both phones, since
   it means Android never told the payer the Max's service went.
8. **A link that goes.** Mid-tap, before the four digits, pull the phones
   apart and put the receiver away: the payer's screen goes back (`gone`, then
   `searching`) and the next touch links afresh. With a payment handed over,
   put the payer away for ten seconds and bring it back: `back in front with a
   payment handed over; asking the receiver to say it again` and the answer
   comes (M12).

**The payer's near threshold and the Pixel's edges (for the upstream table,
A12).** The thresholds Android uses are iOS's as they are
(`tap/TapProximity.kt`: touching at a median of **−33 dBm** advertised,
TAP TO PAY from **−54**, a quiet link from **−64**, the open link's **−60**),
and the edge table is iOS's (`tap/TapProtocol.kt` `TapEdges`: only iPhone 17
Pro and later receivers are held on their left edge). Both were measured
iPhone to iPhone; **the Pixel's values are to be measured**, with the Pixel as
the payer this time:

- Payer: the Pixel 8 Pro, Foxy on its home screen, its diary followed: the
  `something at <n> dBm` lines (the advertised median the verdict decides on)
  and, once a quiet link is open, `the open link answers at <n> dBm (its
  advertisement said <m>)`, which is the two scales side by side.
- Receivers, one at a time: the iPhone Max on its invoice screen, and a second
  Pixel on its invoice screen (`ADVERTISE_TX_POWER_HIGH`), each face up on a
  wooden table, nothing metal within 30 cm.
- Positions, each held still for 10 s, three times, phones swapped round
  between runs: back to back touching; touching tops together; touching with
  the Pixel's camera bar over the receiver's top edge, then over its bottom
  edge, then over its left and its right edges; 1, 2, 5, 10 and 20 cm apart;
  the Pixel in its case and without.
- Record per position the median and the spread, the closest position that
  reaches −33 dBm (and whether any 20 cm reading does, which would be a
  stranger's phone across a counter), the open link's reading beside the
  advertised one, and which edge pairing reads strongest (that is where the
  Pixel's antenna is, for `TapEdges`).
- Write the table under "Runs" with the date, both phones' OS versions, the
  commit, and, per position, whether the Pixel connected without TAP TO PAY.
  Thresholds per payer model, and Pixel models in `TapEdges`, are an upstream
  change (getfoxy/iOS `TapProtocol.swift`) that Android then follows; nothing
  here changes the numbers.

## The card (ENG-667)

**Pending: the person's and the architect's on-device check; no phone and no
card were used for this change.** What runs without them: `CardGateTest`,
`NfcCardLinkTest`, `CardSessionTest`, `CardSheetModelTest` and
`SimCardLinkTest` (JVM); `tools/cardsim-test.sh`, the bridge and the debug
build's `SimCardLink` against getfoxy/card's own applet in jCardSim (a card
set up, loaded, read, paid from, pulled away and found again), and against
cashu-javacard's 2.0 applet (ENG-682); and the
conformance flows *P3 (ENG-667) …*, the page's own card code against iOS's
model of the card. What only a phone, a radio and a hand can show is below.

**Card protocol 2.0 (ENG-682).** A card set up by Flash or on cashu-javacard
2.0 (applet `F0 43 41 53 48 55 43 41 52 44 01`, CASHUCARD) does not read in
this build: the page at the pin chooses Foxy 1.x's applet, which a 2.0 card
answers `6A82`, and the page says "That is not a Foxy card." The native gate
already carries 2.0 (`CardGateTest`, and `tools/cardsim-test.sh` against
cashu-javacard at `tools/cardsim-2.PIN`). A 2.0 card reads only after the
v0.1.3 re-pin to the getfoxy/iOS page that chooses CASHUCARD; these steps then
take a 2.0 card, and the simulator in step 0 is cashu-javacard's
(`sh tools/cardsim/run.sh` in a clone of lnflash/cashu-javacard, the same line
protocol on the same port). ANDROID-MAPPING.md, "Card protocol 2.0", has the
plan.

Needs the debug build on the Pixel 8 Pro (`./gradlew assembleDebug`, `adb
install -r app/build/outputs/apk/debug/app-debug.apk`), NFC on (Settings →
Connected devices → Connection preferences → NFC), the person's provisioned
Flashcard, and for step 7 the iPhone. **Real money:** ADD FUNDS and a payment
move real sats on the page's default mint, so the rules of "Real money" above
hold: the person's go for the run, 100 sats or less a leg, the mint's quote
paid from the person's own wallet. Steps 1 and 2 move nothing. Read the field
log or `adb logcat --pid=$(adb shell pidof io.islandbitcoin.getfoxy)` for the
`[card] sheet:` lines; no command, answer or PIN is ever logged.

0. **The simulator first, on the Pixel, with no card (optional, moves
   nothing).** On the Mac: `git clone https://github.com/getfoxy/card`,
   `sh tools/cardsim/run.sh` in it (port 47431), then
   `adb reverse tcp:47431 tcp:47431` and
   `adb shell am start -n io.islandbitcoin.getfoxy/io.islandbitcoin.foxy.MainActivity --ez FoxyCardSim true`.
   The menu → FLASHCARD → TAP CARD. **Pass:** no sheet (the simulator stands in
   for the radio only, as on iOS's Simulator), the FLASHCARD screen shows a
   new card with SET UP THIS CARD; the simulator's terminal prints each
   instruction and status, never data. Force-stop Foxy before step 1, so the
   next start has no extra.
1. **The sheet, and a card read.** The menu → FLASHCARD → TAP CARD. **Pass:**
   Foxy's own sheet rises from the bottom over a dimmed page: "Ready to Scan",
   the page's line, a phone-and-card mark, Cancel. Hold the Flashcard flat to
   the back of the phone, near the middle (the Pixel's antenna). The line
   changes to "Reading the card" as it connects, then the sheet shows a tick
   and its word and goes by itself after about a second; the FLASHCARD screen
   shows the card as the iPhone does (its balance, its limits, its owner's
   buttons if this phone's words are its owner's). Note how long the read took
   from the card touching the phone to the tick (the iPhone's 23a note to
   compare with), and whether the phone buzzed or chimed on contact.
2. **Cancel, Back, the minute, put away (moves nothing).** (a) TAP CARD, then
   Cancel on the sheet: the sheet goes at once and the page says the card was
   not tapped. (b) TAP CARD, then the phone's Back: the same. (c) TAP CARD and
   hold nothing for a minute: the sheet goes and the page says no card was
   tapped (`[card] sheet: ended, nobody held a card up`). (d) TAP CARD, then
   Home while the sheet waits, and come back: the sheet is gone and the page
   is not waiting on it (`Foxy was put away`). (e) Hold a bank card or a
   transit card to the phone with the sheet up: the sheet does not take it
   (the line says "Hold one card to the back of the phone" if the card is not
   one that speaks commands, or the page refuses it as NOT A FOXY CARD), and no
   other app or browser opens. (f) With the sheet down and Foxy in front, hold
   the Flashcard to the phone: Foxy opens its Flashcard screen and the card
   sheet (ENG-668; the full checks are in "A card held to the phone" below).
3. **ADD FUNDS (real money: the person's go, 100 sats or less).** With the
   card on the FLASHCARD screen: ADD FUNDS, an amount of 100 or less, the PIN
   if the card has one, TAP CARD, hold the card until the tick. **Pass:** the
   balance in the pill counts up by the amount; the phone's balance is down by
   it and the mint's fee; HISTORY has one To card entry. A card of software
   1.20 says `Verified At <time>` afterwards (the mint's proof kept with each
   piece, iOS 23f).
4. **A payment from the card (real money).** On the iPhone at the same mint
   (or on the Pixel itself, RECEIVE → an amount of 50 or less → CARD): the
   card's PIN if asked, TAP CARD, hold the card to the Pixel. **Pass:** the
   sheet says what the page says as it signs, the tick, and the payment lands;
   the card's balance is down by what left it. Note the time from contact to
   the tick (the signing on the card).
5. **The card pulled away part way.** Start a WITHDRAW or a payment and lift
   the card while the sheet counts. **Pass:** the sheet stays up and asks for
   the card again (TAP THE CARD AGAIN on the page behind it); hold it back:
   the session goes on and finishes; nothing is on the card twice and nothing
   is lost (the page's own accounting, as on iOS 23b step 6). Then lift it
   again and leave it away a minute: the sheet goes, and the page says what is
   left to finish on the next tap.
6. **A system dialog over a tap.** With the sheet up waiting for a card, pull
   the notification shade down and push it back up. **Pass:** the sheet is
   still there and still finds the card (reader mode comes back on with Foxy
   in front: `ReaderModeRadio.resumed`).
7. **A card set up on the iPhone, operated from the Pixel.** With the Pixel
   restored from the iPhone's twelve words (ENG-666): TAP CARD on the Pixel.
   **Pass:** the Pixel shows the card as its owner's (the owner's buttons), and
   LIMITS → a change with the owner's proof goes through on the Pixel's tap.
   Read it on the iPhone afterwards: the same card, the same limits.
8. **NFC off.** Settings → NFC off, then TAP CARD. **Pass:** no sheet; the
   page says this phone cannot read a card (NO CARD READER). Turn NFC back on.

Report each step's pass or fail, the read and signing times, the phone, the
card's software (`01 14` is 1.20) and the build's commit.

## A card held to the phone (ENG-668)

**Pending: the person's on-device check; no phone and no card were used for
this change, and nothing here moves money.** What runs without them:
`CardAddressTest` and `CardTapDeliveryTest` (iOS's `CardAddressTests` and
`CardTapDeliveryTests`, case for case, plus a question out across a dead page
and a slow answer never asked twice), `CardTapIntakeTest` (the two intent
actions, a replayed intent, no tap while a card is being read),
`CardTapRouteTest` (a new page, the page on top of its task, or dropped with a
seed screen or the scanner over it), `ReaderModeRadioTest` (reader mode off is
said once per look ended), `PageHooksTest` (the reload order and the wiring),
`PutAwayCardTapTest` (`_resumed` before the delivery hears of the return),
`ManifestTest` (the two filters on `CardTapActivity` only, exactly
`https://getfoxy.io/card`; MainActivity takes neither), and the
conformance flows *the card tap (`_cardTapped`, P3) …* (the page's answer and
`cardTapRule`). What only a phone, a card and a hand can show is below.

Needs the debug build on the Pixel 8 Pro (`./gradlew assembleDebug`, `adb
install -r app/build/outputs/apk/debug/app-debug.apk`), NFC on, the person's
provisioned Flashcard of software 1.19 or later with its tag switched on (the
default; FLASHCARD → the owner's settings if it was switched off), a wallet
already set up on the Pixel, and the field log or `adb logcat
--pid=$(adb shell pidof io.islandbitcoin.getfoxy)` open for the `[foxy] card tap:`
lines (`a card's address arrived`, `told the page`, `given up …`, `… not a
tap`; the address itself is never logged). Hold the card flat to the back of
the phone near the middle, for about a second, and lift it, unless a step
says to leave it. Read the page's own `[foxy] card tap:` console lines (the
page's `cardTapRule`) in the field log too.

1. **Foxy closed (cold start).** Force-stop Foxy (Settings → Apps → Foxy →
   Force stop), the screen on and unlocked on the home screen. Hold the card.
   **Pass:** Foxy starts by itself (no browser, no chooser; if Android shows a
   chooser, another installed app also claims the address: note which), the
   splash and the PIN lock if one is set, and once unlocked the FLASHCARD
   screen opens with the card sheet up, as MENU → FLASHCARD → TAP CARD would.
   The log has `a card's address arrived` then `told the page` once.
2. **Foxy in the background.** Open Foxy, go Home, wait ten seconds, hold the
   card. **Pass:** Foxy comes to the front (the same activity: no second
   splash), the PIN lock comes up first if one is set (the page heard
   `_resumed` before the tap), then the Flashcard screen and the sheet. One
   `told the page` line.
3. **Foxy open on HOME.** Hold the card with Foxy in front on HOME. **Pass:**
   the Flashcard screen and the sheet at once; the card on the phone is then
   read by the sheet (lift and hold again if it is not), and the screen shows
   the card.
4. **Foxy open on a send or a payment in progress (ignored).** Start a SEND
   (or RECEIVE with an amount) and hold the card at the confirmation. **Pass:**
   nothing moves on screen; the page logs that it ignored the tap; the send
   goes on as before.
5. **A card being read never re-opens the screen.** MENU → FLASHCARD → TAP
   CARD, hold the card until the tick and **leave it on the phone** for ten
   seconds after the sheet goes. **Pass:** the sheet does not come back and
   the Flashcard screen stays as it is; the log may show `… not a tap` (the
   card found again as reader mode went off). Lift the card, wait five
   seconds, hold it again: a tap again (the screen acts as in step 3).
6. **Locked.** Lock the phone (screen off), wake it to the lock screen
   without unlocking, hold the card. Note what Android does: with "Require
   device unlock for NFC" on (the default on Android 12+), nothing until
   unlock. Then unlock within a minute and hold it again. **Pass:** nothing
   opens on the lock screen, and the tap after unlocking opens Foxy as in
   step 1 or 2. Separately: hold the card while unlocked, then lock the phone
   at once before Foxy is up, and unlock within a minute: the kept tap opens
   the Flashcard screen; after more than a minute it does not (`given up`).
7. **Twice quickly.** On HOME, hold the card, lift, hold again within a
   second. **Pass:** one Flashcard screen and one sheet (the page treats the
   second as the same tap or as a card for the sheet already up).
8. **Recents does not replay it.** After step 1, Back out of Foxy (the
   activity finishes), then open Foxy from Recents. **Pass:** Foxy opens on
   HOME, not the Flashcard screen; no `a card's address arrived` line.
9. **The address in a browser.** On the Pixel, open
   `https://getfoxy.io/card` in Chrome from a note or a message. On the debug
   build the App Link is never verified (debug key), so Chrome stays on the
   page; Settings → Apps → Foxy → Open by default → add `getfoxy.io` and try
   again: Foxy opens as in step 3. On a Play install, Foxy opens straight
   away with no chooser ("The App Link" below).
10. **foxy://card (debug only).** `adb shell am start -a
   android.intent.action.VIEW -d foxy://card` with Foxy on HOME. **Pass:** as
   step 3, and the log has `[deeplink] a card tap`.
11. **A card held while the seed words or the scanner is up (dropped, never a
   second page).** a) MENU → the seed words (SHOW WORDS, past the screen
   lock), and with the twelve words on screen hold the card. b) Start a scan
   (SEND → SCAN), and with the camera up hold the card. **Pass:** in both, the
   screen in front stays exactly as it is: no splash, no second Foxy over it,
   no Flashcard screen; the log has `[foxy] card tap: dropped`. Close the
   screen (Back): Foxy's page is under it, as it was, and Recents shows one
   Foxy. Then on HOME hold the card: a tap as in step 3 (`delivered`).
   `dropped` holds only while the words are live on screen (the hold in a).
   Then repeat a) with the card left resting on the phone, turn the screen
   off and on and unlock. **Pass:** the seed screen closed when the screen
   went off (S20, the log has `[foxy] a seed screen was left; closed`), so on
   unlock the words are gone, the tap is `delivered` and handled as in step
   3, and Recents still shows one Foxy. A seed screen still up after unlock
   is a fail (S20), and so is a second Foxy.
12. **A card resting on the phone while another app is in front.** Put the card
   in the phone's case (or tape it to the back) so it rests on the reader. Open
   Foxy on HOME, then open another app (Settings or Chrome) so Foxy is in the
   background. Lock the phone, wake it and unlock with that app in front; do it
   three times. **Record what Android does**: whether Foxy jumps to the front
   over the other app on each unlock (expected: Android's tag dispatch finds
   the card on every wake and `CardTapActivity` brings the page back,
   `delivered`), whether a chooser shows, and what the page then does
   (Flashcard screen, or nothing). Repeat with Foxy force-stopped
   (`launched`). On iOS a background read only raises a banner the person must
   tap, so Foxy never comes over another app by itself; the spec
   (sandbox/plans/foxy-card-android.md) says nothing about a resting card. If
   Foxy does jump to the front, raise it with the iOS team (Mike Greff) as a
   parity decision, with this step's notes: keep it, or ask before opening
   (a notification the person taps, as iOS's banner). Do not leave it
   undecided.

Report each step's pass or fail, the phone, the Android version, the card's
software and the build's commit.

### The App Link (0.1.2)

The link filter is `autoVerify="true"` (`ManifestTest`). Android verifies it
against `https://getfoxy.io/.well-known/assetlinks.json`, live since
2026-10-13 (200, `application/json`, no redirect), which lists the package
`io.islandbitcoin.getfoxy` with two SHA-256 fingerprints:

- the Play **app signing** certificate (`docs/RELEASE.md` §4), which signs what
  Play installs:
  `13:FE:CE:6E:F5:84:B3:7D:35:A6:95:7D:2C:16:27:0B:41:93:21:56:5B:A0:A6:77:64:EC:81:9B:43:B4:01:D1`
- the **upload key** (`docs/RELEASE.md` §1), for a build signed on the release
  machine and installed by hand:
  `61:08:F8:6D:DE:5D:94:9C:CE:66:07:1B:DA:D9:CA:5A:69:D8:45:9B:DA:B7:CB:A5:44:DE:DB:4F:A8:78:00:53`

A debug build is signed with the debug keystore, which the file does not name,
so it never verifies; that is expected, and step 9 above covers it by hand.
To check a Play install or an upload-key build: `adb shell pm verify-app-links
--re-verify io.islandbitcoin.getfoxy`, then `adb shell pm get-app-links
io.islandbitcoin.getfoxy` shows `getfoxy.io: verified`. Then open
`https://getfoxy.io/card` from a note: Foxy opens with no chooser, as in step 3.
**Pending: no phone was used for this change.** Verification only skips the
chooser. The NDEF filter never depended on it, and either way only
`CardAddress.isCard`'s exact address is acted on.

## The native design checks (ENG-665)

Foxy's windows are FLAG_SECURE, so a screenshot or a screen recording of them
is black: these are looked at by the person, on the debug build, with the iOS
app (getfoxy/iOS at `core/PIN`'s commit) beside it where one is to hand. The
art is the iOS art (`app/art/NOTICE.md`); `ArtTest`, `NotifierTest`,
`SplashCoverTest`, `SeedTypeTest`, `SeedColoursTest` and `PromptQueueTest` check the files and the
numbers; what follows is what only eyes can check. **Pending: no phone was used
for this change.**

1. **The launcher icon.** On the home screen and in the app drawer: the white
   fox head of the iOS icon on black, ears whole (no tip cut by the launcher's
   circle or squircle), the neck running off the bottom edge with no line or
   grey band across it. Long-press and drag it a little: the fox moves over the
   black (the two layers), and nothing white shows outside the mask.
2. **The themed icon (Android 13+).** Settings → Wallpaper & style → Themed
   icons on: Foxy's icon is the same fox in the theme's colours, its eyes, nose
   and mouth open (drawn in the theme's background colour), not a plain blob and
   not the old two-eared placeholder.
3. **The cold start (the splash).** Force-stop Foxy (`adb shell am force-stop
   io.islandbitcoin.getfoxy`), then tap the icon. Android's splash is black with
   the sleeping white fox in the middle (the intro's fox, smaller); it fades in
   about a fifth of a second into the same fox full size, filling the screen as
   the iOS launch screen does. From there to the intro there is **no jump**: the
   fox does not move, resize or flash when the intro starts playing (on iOS the
   same). On a launch with no intro (it has played already), the still holds
   until the page is up (CONNECTING, the PIN lock or the home screen), then
   fades out in about a third of a second. Never the old orange two-eared fox,
   never a white or grey frame. Repeat with the phone in dark and light mode:
   the same.
4. **The cold start on Android 9-11 (API 28-30), if a phone or an emulator is to
   hand.** The splash is AndroidX's: the same fox on black, then the same fade.
5. **"Payment received" as a heads-up.** With notifications allowed, Foxy open,
   receive a payment (under "Real money" above, as in P3 check 5): a
   banner drops from the top of the screen over Foxy for a few seconds, with
   the default sound, as iOS's banner does; its small icon in the status bar
   and on the banner is the white fox head (not a lightning bolt), tinted the
   page's orange where Android tints it. Settings → Apps → Foxy →
   Notifications lists one category, "Payments", set to make sound and pop on
   screen. On a phone that had an earlier build installed (update with
   `adb install -r`, do not uninstall): still one category, not two.
6. **The native dialogs.** BACKUP → SHOW WORDS → RESTORE A WALLET → paste or
   type a different phrase → the replace question; and the allow-a-new-host
   question (a mint the page has not used). Each dialog is a near-black card
   with rounded corners and a faint light edge, over a dimmed page; the title in
   heavy, slightly spaced white; the message in a softer white; the buttons in
   heavy spaced capitals, the no on the left in the orange, the yes on the right
   in the orange, or in red when it replaces or deletes the seed. No teal or
   pink (Android's Material default) anywhere.
7. **The seed screens' type.** SHOW WORDS, VERIFY and RESTORE beside the iOS
   app's: the titles (YOUR SEED PHRASE, VERIFY, BACKED UP, RESTORE A WALLET) in
   the heaviest weight, letters spaced a little wider than before; the body in
   a semibold, lighter than the titles; the twelve words and their numbers bold;
   the orange button's label heavy and spaced; TAP TO REVEAL and HOLD TO DELETE
   ALL DATA heavy and spaced. On RESTORE the typed words are in the phone's own
   font (no longer a typewriter one). Then with font_scale 1.3
   (`adb shell settings put system font_scale 1.3`; back with `1.0`): every
   title and button label still fits its line or wraps cleanly inside its
   button, nothing clipped.

## The onion inbox and post between two phones (ENG-672)

Payment requests paid phone to phone over Tor, between the iPhone "Max"
(Foxy for iOS at `core/PIN`'s commit or later) and the Pixel 8 Pro (this
branch's debug build). The JVM tests hold Android to iOS's bytes
(`OnionHttpTest`, `OnionInboxTest`, `OnionPostTest`, `DeliveryBridgeTest`,
`TorOnionWireTest`) and the conformance flow *P3 two phones* pays one page
from another through the Android host; only two real phones and the real Tor
network show that an iPhone and an Android phone find each other's addresses
and read each other's answers. **Pending: written for the person, not run. No
phone was used for this change.**

These checks move real ecash between the person's own two wallets, under
"Real money" above: the person's go for the run, 100 sats or less a payment,
both phones on the same mint (the default,
`https://mint.minibits.cash/Bitcoin`), each funded beforehand with a mint of
its own (P3 check 5) paid from the person's wallet. Nothing is melted. The
Pixel's log is the debug build's LOGS screen (or `adb logcat -s foxy` from the
person's machine); the iPhone's is its own LOGS screen.

Before each: both phones show CONNECTED (Tor up), the Pixel's Foxy has been
open for a minute (so its warm spare is published), and neither is on Orbot.

1. **iPhone pays a Pixel request over the onion.** Pixel: RECEIVE, 21 sats,
   the ecash request (the QR that is a `creqA…`). Its log says
   `onion inbox handed the spare, already published` (or, the first time
   after Tor came up, `onion inbox open — for the request on screen`), and
   never `Unknown action: inboxOpen`; leave the screen up for 30 s and the log
   shows no new `inboxOpen` (it is not asked again). iPhone: SEND, scan the
   Pixel's QR, confirm. Expected: within about ten seconds (a live rendezvous
   measured about three on iOS) the iPhone says the payment was delivered and
   the Pixel says 21 sats received; the Pixel's log has
   `onion inbox: a payment arrived (<n> bytes)` and no `504`; the iPhone's has
   no `onion http:` failure. Both balances move by 21 (the iPhone's by 21 plus
   any mint fee).
2. **Pixel pays an iPhone request over the onion.** iPhone: RECEIVE, 21 sats,
   the ecash request (it names a Nostr key first and the onion second). Pixel:
   SEND, scan it, confirm. Expected: delivered within about ten seconds; the
   Pixel posts to the onion, not to Nostr (its page prefers the onion,
   `deliveryFor`, whatever the order the request lists them in); the Pixel's
   log has no `onion post failed` and no `nostr:` line; the iPhone shows 21
   received.
3. **The hold after the screen closes.** Pixel: RECEIVE, 13 sats, the ecash
   request. iPhone: scan it and confirm, and **at once** on the Pixel tap back
   to the home screen. Expected: the Pixel's log says
   `onion inbox kept answering for 90s: a payment may be on its way`, the
   payment still arrives (the Pixel's balance moves by 13), and about
   ninety seconds later the log says `onion inbox closed — for the request on
   screen`. Repeat 3 the other way round (iPhone receiving, Pixel paying,
   iPhone back to home at once): the same.
4. **Put away ends the address.** Pixel: RECEIVE, 8 sats, the ecash request.
   iPhone: SEND, scan it and stop at the confirmation (do not confirm yet).
   Pixel: press Home. Expected in the Pixel's log, before
   `tor: off the network while Foxy is in the background`: `onion inbox
   closing: Foxy is leaving the foreground` and an `onion inbox closed` for the
   request's address and for the spare. Wait ten seconds, then confirm on the
   iPhone: it fails with "Their phone could not be reached over Tor." (or
   "That request is no longer open on their phone."), within about thirty
   seconds, and the 8 sats stay in the iPhone's history as a token (the money
   is never lost; claim it back on the iPhone). Back on the Pixel: the receive screen draws a new QR naming a
   new address (the log: a new `onion inbox open` after `back after <n>s`);
   pay that one from the iPhone: delivered.
5. **No Tor, no address.** Pixel in airplane mode, then RECEIVE, 8 sats, the
   ecash request: the QR still appears (a request with no address in it: the
   payer shows a token to scan instead), the log has no `onion inbox open`,
   and the screen does not ask `inboxOpen` more than once every four seconds
   (no burst of `onion inbox closed` lines). Airplane mode off: within a
   minute the screen's request names an address again.
6. **Nostr, both ways:** see the next section (ENG-677).

## Payment requests over Nostr between two phones (ENG-677)

The Nostr transport between the iPhone "Max" (Foxy for iOS at `core/PIN`'s
commit or later) and the Pixel 8 Pro (this branch's debug build): a request
that names a Nostr key (NIP-17 over NIP-59 gift wraps, NIP-44 v2), paid by
publishing to relays over Tor, and the key's inbox listening on them. The JVM
tests hold Android to iOS's code and the specs' own vectors (`NostrCryptoTest`
replays iOS's `NostrCryptoTests`, RFC 8439 and the official NIP-44 v2 vectors
byte for byte; `NostrInboxTest`, `NostrDeliveryTest`, `DeliveryNostrTest`,
`RouteSocketTest`), and the conformance flows of `flows-nostr.test.js` pay one
page from another through the Android host; only two real phones, real relays
and the real Tor network show that an iPhone opens what a Pixel wraps and the
other way round. **Pending: written for the person, not run. No phone was used
for this change.**

Under "Real money" above, as the onion checks: the person's go for the run,
100 sats or less a payment, both phones on the default mint
(`https://mint.minibits.cash/Bitcoin`), each funded beforehand. Nothing is
melted. Both phones CONNECTED (Tor up), neither on Orbot. The Pixel's log is
the debug build's LOGS screen (or `adb logcat -s foxy` from the person's
machine); the iPhone's is its own LOGS screen.

A request paid over Nostr needs its onion out of the way, because a Foxy payer
always prefers the onion (`deliveryFor`). Two ways, either is fine: pay from a
wallet that cannot reach an onion (cashu.me in the phone's browser, which pays
the first transport it knows, and the request lists Nostr first), or spoil the
onion by hand: decode the request (`creqA…`) on a computer with cashu-ts,
replace the `post` transport's target with another valid v3 address (or drop
that transport), re-encode it and show it as a QR.

1. **The Pixel's request names a Nostr key.** Pixel: RECEIVE, 21 sats, the
   ecash request. Its log says `nostr inbox open, a key made for this request
   only` (after the onion's line). Decode the QR (on the iPhone, or with
   cashu-ts): two transports, `nostr` first with the tag `["n","17"]` and an
   `nprofile1…` target naming four relays (`wss://relay.damus.io`,
   `wss://nos.lol`, `wss://relay.primal.net`, `wss://relay.minibits.cash`),
   then `post` with the onion. Back out and RECEIVE again: the nprofile is a
   different one (a key per request).
2. **iPhone pays a Pixel request over Nostr.** Pixel: RECEIVE, 21 sats, the
   ecash request with its onion spoiled as above (or pay it from cashu.me).
   iPhone: SEND, scan it, confirm. Expected: the iPhone's log says how many
   relays took the payment (at least one); within about half a minute the
   Pixel's log says `nostr inbox: a payment arrived` and the Pixel shows 21
   sats received, with no `a wrap did not open` line. The page's answer to it
   is told nothing waits (nobody does: the payer published and walked away),
   which the log may show as an `inboxAnswer` refusal and is expected. The
   iPhone's history shows the payment as claimed once its claim watch sees the
   proofs spent.
3. **Pixel pays an iPhone request over Nostr.** iPhone: RECEIVE, 21 sats, the
   ecash request, its onion spoiled the same way. Pixel: SEND, scan it,
   confirm. Expected: the Pixel's log says `nostr: <n> relay(s) took the
   payment, <m> did not` with n at least 1, and no `could not be wrapped`; the
   iPhone shows 21 received (its log: `nostr inbox: a payment arrived`).
   Both balances move by 21 (the payer's by 21 plus any mint fee).
4. **A relay that refuses, and one that does not answer.** As 3, with the
   iPhone's request naming, before the four usual relays, one that is not a
   relay (edit the nprofile with cashu-ts or nak to list `wss://example.com`
   first). Expected: the Pixel still pays (n at least 1); the log counts the
   bad relay among those that did not take it, and the whole send ends within
   about 25 s of the confirmation.
5. **The key ends with the request, and when Foxy is put away.** Pixel:
   RECEIVE, 8 sats, the ecash request (spoiled onion); note its nprofile. Back
   out of the screen. Pay that request from the iPhone: the iPhone says relays
   took it, but the Pixel's balance does not move and its log has no `nostr
   inbox: a payment arrived` (the key was thrown away with the request; the 8
   sats stay in the iPhone's history as a token: claim them back there). Then
   Pixel: RECEIVE again, press Home: its log says `nostr inbox closed` before
   `tor: off the network while Foxy is in the background`, and no relay is
   contacted while Foxy is away (the leak test below shows no flow while it is
   in the background). Back on the Pixel, the screen's request names a new
   nprofile.
6. **Over Tor only.** Run the leak test (`tools/leak-test.sh`, below) with a
   Nostr leg: the Pixel receiving one Nostr payment and paying one. Every flow
   to a relay goes through Tor (the relays are never contacted from the
   phone's own address, and no DNS query names one), on each transport; with
   CONTINUE WITHOUT TOR on and Tor off, a Nostr payment from the Pixel fails
   with "No relay took the payment: Foxy is not connected to Tor." and
   nothing is sent.

## The P2 device exit (ENG-648)

On the phone, debug build, unlocked, online, with the person's go for the run
(see "Real money": these legs move real sats). The page is driven over WebView
debugging (`tools/cdp-eval.js`); the mint is the page's default,
`https://mint.minibits.cash/Bitcoin`. Each leg mints a few sats (the person pays
the mint's invoice from their own wallet) and melts to an invoice the person
made in their own wallet, for less than the mint and at most the leg's cap.

```sh
export ANDROID_SERIAL=<the phone>
adb shell pm clear io.islandbitcoin.getfoxy
adb shell am start -n io.islandbitcoin.getfoxy/io.islandbitcoin.foxy.MainActivity --es FoxyForceTransport direct   # then obfs4, then snowflake (force-stop between)
adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof io.islandbitcoin.getfoxy)
node tools/cdp-eval.js 9333 'for (var i = 0; i < 1200; i++) { if (FoxyWallet.privacy().tor === "up") return FoxyWallet.privacy(); await new Promise(function (r) { setTimeout(r, 500); }); }'
# the leg's invoice, checked against the cap before anything moves (refused: exit 3)
REAL_MONEY=yes MAX_SATS=64 INVOICE=<the person's invoice, at most 62 sats> node tools/real-money.js leg
node tools/cdp-eval.js 9333 'await FoxyWallet.connect("https://mint.minibits.cash/Bitcoin");
  var a = await FoxyWallet.invoice(64, "exit"); return a'          # the person pays a.bolt11 from their own wallet
node tools/cdp-eval.js 9333 'return await FoxyWallet.claim("<a.hash>")'   # again until it is claimed
node tools/cdp-eval.js 9333 'return await FoxyWallet.pay("<the invoice, as checked>")'
# the opt-out: the dialog is pressed by instrumentation, then a mint and a melt without Tor
adb shell am instrument -w -e class io.islandbitcoin.foxy.DeviceExitTest -e foxyExit true \
  -e realMoney yes -e maxSats 64 -e invoice <the person's invoice, at most 62 sats> \
  io.islandbitcoin.getfoxy.test/androidx.test.runner.AndroidJUnitRunner
```

`DeviceExitTest` takes `-e mint <url>` (default minibits), `-e mintSats <n>`
(default `maxSats`) and `-e payTimeoutSeconds <n>` (default 600); it prints the
mint's invoice as `foxy-exit: PAY THIS …` and waits for the person to pay it.
Unpaid in time, it fails saying so, having melted nothing; a quote paid later
is claimed by Foxy's sweep on its next connect to that mint. Sats minted and
not melted stay in the phone's Foxy wallet. A melt the mint answers PENDING
(or with no state) fails the test, saying the melt is still pending: Foxy
holds its ecash until it settles, so it is not recorded as a melt that went
through.

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
# with the person's go, a mint and a melt in the capture too (one invoice of the person's per run):
REAL_MONEY=yes MAX_SATS=64 INVOICE=<the person's invoice, at most 62 sats> tools/leak-test.sh direct out/
```

Leftover sats: the AVD is wiped on every start, so ecash left in it would be
lost on the next run. Once the mint's quote is printed, `tools/leak-test.sh`
never stops the emulator holding money. After a melt that paid, it exports
what is left (the change, less the mint's fee on the send) as a cashu token,
prints it on stderr and writes it to `<out>/<mode>.leftover.token` for the
person to receive in their own wallet (the file is ecash: `.gitignore` keeps
`*.leftover.token` out of git), then stops the emulator. A melt the mint
answers PENDING, or answers with no state, has not gone through: `FoxyWallet.pay`
resolves with `pending: true` and its ecash stays held in the emulator until
the mint settles it (the fee reserve's change if it pays, all of it if it
fails), so the script counts it as a failed melt. If the quote is not paid in
time, the melt fails or is still pending, the export fails, or anything else
stops the run after the quote was printed, it exits non-zero and leaves the
emulator running, printing the mint, the quote id and the commands to claim
and drain it by hand; a quote paid later is claimed only there. While an
emulator is up on the script's serial (`emulator-5580`), the next run refuses
to start (exit 4) instead of wiping it, or driving and stopping it undrained:
drain it and stop it first.
Before it prints the quote, it also writes a held-money marker next to the AVD
(`~/.android/avd/<AVD>.avd/foxy-money-held`, under `ANDROID_AVD_HOME` when set:
the mint, the quote id and the time) and deletes it only once the change is
out. While the marker exists the next run refuses too (exit 4), even with no
emulator up (it died, the terminal closed, the Mac rebooted): the ecash is in the
AVD's userdata and `-wipe-data` would destroy it. If the emulator is still up
(the usual case: the run left it running), claim and drain it by hand as above,
stop it, then delete the marker. If it is not, start the AVD again without
`-wipe-data` first; the refusal prints which of the two applies.

`tools/leak-test.sh` starts the AVD wiped and headless with `-tcpdump`, turns
private DNS and the virtual Wi-Fi off (the capture sees the emulated cellular
path; Wi-Fi bypasses it), installs the debug APK, samples the app's sockets by
uid from `/proc/net` every 0.2 s (as root, which a google_apis image allows),
launches Foxy on the transport (`FoxyForceTransport`) or without Tor
(`FoxyNoTor`, debug builds only), waits for Tor, connects to the mint (`MINT`,
default `https://mint.minibits.cash/Bitcoin`: its keys and info, no money), and
only with `REAL_MONEY=yes` and the rest of the money rules above (checked by
`tools/real-money.js` before the emulator starts) mints `MINT_SATS` (printed
for the person to pay from their own wallet) and melts to the person's
`INVOICE`; then it runs `tools/leak-check.js` on the capture, the sockets and the
consensus Tor downloaded. A socket's local port is Foxy's only on the outside
address it held (a loopback port reused by another process is that process's).
The emulator stamps the capture with a clock of its own, so the opt-out's moment
is a marker lookup made just before the tap, read from the capture. The
captures are not committed.

Snowflake's WebRTC sends ICE checks to every candidate address its volunteer
offers, private ones included (192.168.x, 10.x on the volunteer's side); that is
Snowflake's, outside Tor by design, and the leak check accepts any UDP in
snowflake mode for it.

## Store screenshots (an emulator)

Foxy's windows are FLAG_SECURE (S19), so a screenshot of them is black. A debug
build launched with the `FoxyAllowCapture` extra leaves FLAG_SECURE off the
page's window (`MainActivity`) for that run, so the Play listing's screenshots
(`docs/store/screenshots/`) can be taken. The seed screens, the scanner and the
native dialogs keep FLAG_SECURE: they show black, and no seed word can be
captured. A release build never reads the extra (`MainActivity.onCreate` sets
FLAG_SECURE before any branch), and `ReleaseBuildTest.aReleaseCannotLiftFlagSecure`
reads the release classes to prove it: FLAG_SECURE set on all four windows, set
first thing in `MainActivity.onCreate` with no branch or intent read before it,
never cleared, and the extra's name loaded by no release class.

```sh
avdmanager create avd -n foxy_store -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_2   # 1080 x 1920, 9:16
emulator -avd foxy_store -no-snapshot -no-audio -port 5580 -gpu host &   # -gpu host: the default left stale WebView tiles
./gradlew assembleDebug
export ANDROID_SERIAL=emulator-5580   # never the phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.islandbitcoin.getfoxy/io.islandbitcoin.foxy.MainActivity --ez FoxyAllowCapture true
# a clean status bar: System UI demo mode
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
adb exec-out screencap -p > docs/store/screenshots/phone-NN-<name>.png
adb emu kill
```

The 2026-10-13 set was taken this way on a fresh wallet with Tor up: the home
screen, a receive amount and its Lightning invoice (a quote from the default
mint, never paid), the tap to pay card, the send screen (camera refused, so its
pane is empty), the Tor sheet, the Flashcard screen and the mint notice. No
money moved and no seed word was on screen.

## Runs

Each run: the commit, the phone, and what each test answered. The rows are
history: they record what ran then, and runs before 2026-10-12 minted and
melted on testnut.cashu.space (fake money), with `tools/fake-invoice.js`
invoices. A skipped test
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
| 2026-10-11 | ENG-649 review round 2 (yes-only confirmation rule) | AVD `foxy_api29` (API 29 `default` arm64-v8a), `/product/app/webview/webview.apk` replaced (`-writable-system`, `adb remount`) with Chromium snapshot 1716552's `SystemWebView.apk` (`com.android.webview` 157.0.8101.0; `dumpsys webviewupdate` shows it current); PIN 1234 | **SHOW WORDS under `device` on API 28-29: still no passing run.** With WebView 157 the page loads on this image (`seedShowUnderTheScreenLock…` got past its `Page.STARTED` assumption for the first time) but the run did not complete: its `guardOn` confirmation never came up (state left by an earlier interrupted run; `pm clear` afterwards), and every later `ActivityScenario.launch` hung in `Instrumentation.startActivitySync` with the main thread drawing frames without end (`-gpu host`, `-no-window` and `swiftshader_indirect` alike), so the main looper never went idle. `theConfirmationIsNotAPutAway…` and the new `aCancelledConfirmationIsAReturnAndPutsAway` hung the same way. The yes-only rule is covered by the unit tests in `PutAwayLeavingTest` and `ScreenLockConfirmationTest` only. **The API 28-29 screen-lock confirmation path (`DeviceAuth.confirm`, PutAway's yes-only rule, MainActivity's ActivityResult launcher) is unproven on a device: it has never run on an Android 9 or 10 phone. Tracked in Linear ENG-654 (High; blocks the store release, not this PR): run `OwnPromptTest -e foxyPin <pin>` on a physical Android 9/10 phone and record it here.** |
| 2026-10-11 | ENG-649 rebased on main `43dc3e5` (ENG-652), app at `39938f6` | Pixel 8 Pro (42201FDJG00048), **unlocked**, Android 17, APKs uninstalled and installed fresh, CAMERA and POST_NOTIFICATIONS granted | first full run on the fresh install: 31 run, 1 failed, `TorRouteTest.torLeavingTheNetworkMidRequest…` ("answered over Tor: Foxy is not connected to Tor."): the retry and the test's restart joined the running set-up (one SOCKS listener, `circuit established`), the page's first requests went through, then every SOCKS handshake timed out (7 `SocketTimeoutException`s) until the test's own deadline; logcat kept. The full suite again: `OK (31 tests)`, no SOCKS timeout. Skipped as designed both times: `DeviceExitTest` (no `-e foxyExit true`), `KeystoreTest.onALockedPhone…` (phone unlocked), the three `OwnPromptTest`s (API 28-29 only). Then `pm clear` and a launch: Tor up (`transport: "direct"`) 11 s after `SOCKS on`, and a mint of 8 on testnut.cashu.space over Tor claimed (`8`, after one `Quote not paid` retry) | not run |
| 2026-10-12 | main `d856d3b78456fe69a891135363bab3d25f70787c` (ENG-669's `TapCrypto` and `TapCryptoOnDeviceTest`, unchanged on ENG-670), run by the architect | Pixel 8 Pro (42201FDJG00048), Android 17 | `am instrument -e class io.islandbitcoin.foxy.TapCryptoOnDeviceTest`: `OK (3 tests)`. Tink's X25519 and Conscrypt's ChaCha20-Poly1305 give iOS's bytes from `fixtures/tap-vectors.json`: the tap to pay hard gate (ENG-670, ENG-671) is met. `TapGattOnDeviceTest`, `BluetoothApproveTest`'s ENG-670 form and the two-phone steps: pending, the person's | not run |
| 2026-10-12 | ENG-679 (`jabariennis/eng-679-scan-filter` on main `916411d`; the scan with no hardware filter) | Pixel 8 Pro (42201FDJG00048), **unlocked** (no keyguard), Android 17, debug build installed over the person's (`install -r`, wallet kept) | not run (no instrumentation). The payer's scan, `dumpsys bluetooth_manager`, Foxy's scanner: **before** (main `916411d`, Foxy on its home screen 595 s): LowLatency, filter `[ ServiceUuid=00000000-… ServiceUuidMask=00000000-… ]`, **Number of results 0 / 0 / 0**. **After** (this build, `force-stop`, launched, home screen 37 s): LowLatency, ALL_MATCHES, **no filter line**, **Number of results 0 / 924 / 924** (1273 a minute later). The iPhone Max that was on the air beside it was heard at once: `[tap] pay: a receiver at -26 dBm (needs -33, advertised), connecting`, `that phone is a iPhone14,3`, `handshake: promising a key`, then **`a piece would not go — ATT error 8`** (the first write to the iPhone's in door refused, Insufficient Authorization) and the link let go, twice. Nothing was paid; that write is the next thing the two-phone test (`Tap to pay: the Pixel pays`, step 4) has to get past | not run |
| 2026-10-12 | ENG-679 second change (the quiet link's silence counted from its services; late GATT answers dropped), `jabariennis/eng-679-scan-filter` | Pixel 8 Pro (42201FDJG00048), **unlocked** (no keyguard), Android 17, debug build installed over the person's (`install -r`, wallet kept) | not run (no instrumentation). Foxy on its home screen for 1 min 45 s after `force-stop` and launch: the scan unfiltered (no filter line), Number of results 0 / 2413 / 2413 in its first two minutes; **no `[tap]` line at all**: no Foxy receiver was on the air near the Pixel (the iPhone Max was not receiving), so whether the payer now gets past `handshake: promising a key` is the person's two-phone test (step 4 of "Tap to pay: the Pixel pays"). Nothing paid | not run |

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
