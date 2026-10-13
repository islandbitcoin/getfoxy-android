# ANDROID-MAPPING.md — each iOS component, and what it becomes

The iOS app (getfoxy/iOS `8df0bb19992cceda44d04e947bce211e1415975e`) is a
WKWebView host around the page plus a Swift bridge. The Android app is the same
shape: a Kotlin host around an Android System WebView running `core/web`
unchanged, and a Kotlin bridge implementing `docs/BRIDGE.md`. This table takes
every directory and top-level file under `Foxy/` (and the vendored native code
it depends on) and says what replaces it, with the phase it lands in.

**Decided:** Kotlin; androidx.webkit `addWebMessageListener` with an origin
allowlist (never `addJavascriptInterface`) and a document-start shim that
exposes `window.webkit.messageHandlers.foxy.postMessage`, so `core/web` is not
edited; `WebViewAssetLoader` at `https://appassets.androidplatform.net`; OkHttp
for every native request, through Tor's SOCKS port; Guardian Project
`tor-android`; the IPtProxy AAR for obfs4 and Snowflake; Android Keystore
AES-GCM (StrongBox where available) for the seed and the counter files;
CameraX with zxing-cpp; `minSdk 28`, `targetSdk 36`.

Phases: **P1** shell, **P2** Tor, **P3** platform (the first build for a
phone; since ENG-666 and ENG-667 the NFC card's owner key, time and session
too, since ENG-670 tap to pay's receiving side, since ENG-671 its paying
side, and since ENG-672 the onion inbox and post), **LATER** (Nostr).

## Components

| iOS | What it does there | Android | Phase |
|---|---|---|---|
| `Foxy/FoxyApp.swift` | SwiftUI app; starts the field log; refuses third-party keyboards (`shouldAllowExtensionPointIdentifier`) | `FoxyApplication` (field log start) + one `MainActivity`, portrait, `windowSoftInputMode=adjustResize`. Third-party keyboards cannot be refused on Android; the WebView's input connection and native text fields set `IME_FLAG_NO_PERSONALIZED_LEARNING` (and `textNoSuggestions` on the seed screen) | P1 (keyboard flags P3) |
| `Foxy/FoxyWebView.swift` | stages `Web/` into Caches; WKWebView config; content-rule network block; dead-end proxy; document-start scripts (no WebRTC, no callout, clock, debug flags, console silence, page hash); navigation lock; splash and app-switcher cover; lifecycle pushes (`_putAway`, `_tidying`, `_resumed`, `__foxyUptime`); reinstall detection; web-store backup exclusion; process-death reload | `WebHost` in `MainActivity`: `WebViewAssetLoader` serving `core/web` from APK assets (no staging copy needed); `shouldInterceptRequest` refuse-all except the loader; `setBlockNetworkLoads(true)`; `ProxyController` dead-end override; `addDocumentStartJavaScript` for the same seven scripts; `shouldOverrideUrlLoading` → deny; `onRenderProcessGone` → drop native page state, recreate; `FLAG_SECURE` on the window (covers the recents thumbnail and blocks capture, which is the cover and more); AndroidX SplashScreen fades (200 ms) onto `SplashCover`, the intro's still over the web view until `covered` or 8 s, then a 350 ms fade (as `addSplash`/`hideSplash`; ENG-665); lifecycle from `ProcessLifecycleOwner`/`onStop`/`onStart`; page hash computed at build time from `core/SHA256SUMS` and checked at launch against the APK's assets. Reinstall: Keystore keys and app data are deleted with the app on Android, so no marker is needed | P1 |
| `Foxy/Assets.xcassets` | icon, accent, splash art | `res/mipmap-*` adaptive icon (foreground and monochrome layers cut from the iOS icon), `res/drawable-nodpi/splash_fox.png` (Android 12+ splash icon, from `Web/foxy-splash-still.png`), the full-screen still over the page until `covered` (`SplashCover`, as `FoxyWebView.addSplash`), `res/drawable-*/ic_notify.png`, theme colours; cut by `tools/make-art.py`, sources and licence in `app/art/NOTICE.md` (ENG-665) | P1, ENG-665 |
| `Foxy/PrivacyInfo.xcprivacy` | App Store privacy manifest: nothing collected, no tracking | Play Console Data safety form (nothing collected or shared); no SDKs to declare | P3 |
| `Foxy/Bridge/FoxyBridge.swift` | the action table, dispatch, frame gate, `_scanResult` replies, haptics, price/candles/mintRequest, clipboard/copy/share, biometric, notify, preview/paste controls, open settings/company, Orbot, privacy/torRetry/unprotected | `bridge/FoxyBridge.kt`: one `Map<String, Handler>` with the same 74 names; `WebMessageListener.onPostMessage` checks `sourceOrigin == https://appassets.androidplatform.net` and `isMainFrame`, parses JSON, dispatches as `dispatch` does (haptic / action+id / ignored); replies through one `resolve(id, text, error)` that JSON-quotes each argument and calls `evaluateJavascript` on the main thread. Each handler's argument checks are ported with its Swift tests | P1 transport and P1 actions; others by their phase |
| `Foxy/Bridge/NativeSeedBridge.swift` | the seed, counter, P2PK and card-owner actions, argument checks, one serial seed queue, the seed screens' entry points | `bridge/SeedBridge.kt`: the same checks (`exactInteger`, `keyset`, ranges), one single-thread `Executor` for every seed action | P1 (screens P3; card owner P3, ENG-666) |
| `Foxy/Bridge/SeedScreens.swift` | native SHOW WORDS (with quiz) and ENTER WORDS screens; one at a time; closed on background; hidden while captured | `seed/ShowWordsActivity.kt`, `seed/EnterWordsActivity.kt`: two `ComponentActivity` screens in plain Views (no Compose, ENG-649 decision 4), `FLAG_SECURE`, finished in `onStop`; the words handed over in-process (`SeedScreenHost`), never in an Intent; the pure parts (`seed/SeedScreens.kt`: quiz, cells, one-at-a-time queue, 10 s pause after a failed read) ported with their tests. No capture hiding: `FLAG_SECURE` keeps every capture blank | P3 (built) |
| `Foxy/Bridge/NativePrompts.swift` | one queue of native alerts (host approval, seed replace/delete, continue without Tor) | `bridge/PromptQueue.kt`: `AlertDialog`s on the activity in front in `Theme.Foxy.Dialog` (the page's card, heavy spaced orange buttons, a dangerous yes in red; ENG-665), one at a time, `FLAG_SECURE` like the activity; any way a dialog goes down without a button is no; nothing in front is no (`PromptQueueTest`) | P2 (host/Tor, built), P3 (seed) |
| `Foxy/Bridge/SeedPasteboard.swift` | a seed phrase on the clipboard reads as empty; remembers what Foxy itself copied | `bridge/ClipboardFilter.kt` (the rules) and `web/Clipboard.kt`: the same BIP-39 phrase test on read; Foxy's copies labelled `foxy:<nonce>` (the restore screen refuses them) and, when sensitive, marked `android.content.extra.IS_SENSITIVE` and cleared after 120 s while still Foxy's, looked at again when the window has focus | P3 (built) |
| `Foxy/Bridge/PasteHost.swift` | a near-invisible `UIPasteControl` over the page's PASTE button, so iOS shows no paste prompt | `web/PasteButton.kt`: a see-through native button over the page's rect, with iOS's rectangle checks (`PlatformRules.pasteAllowed`); the clipboard is read only in its tap and pushed with `_pasted`, never a seed phrase (decision 4, decided in ENG-649) | P3 (built) |
| `Foxy/Bridge/ForegroundNotifications.swift` | show banners while in the foreground | `notify/Notifier.kt`: `NotificationChannel` "Payments" (`payments_heads_up`, IMPORTANCE_HIGH so it shows as a heads-up as iOS's foreground banner; made in `FoxyApplication`, which deletes the default-importance `payments` of earlier builds, ENG-665), framework `Notification.Builder` with fixed text, `VISIBILITY_PRIVATE` with a public version that says only "Foxy"; `POST_NOTIFICATIONS` asked on the first `notify`, and refused the answer is still `"ok"` | P3 (built) |
| `Foxy/Bridge/FoxyBridge+Delivery.swift` | onion inbox, Nostr inbox, `onionPost`, `nostrSend` | the onion half in `bridge/DeliveryBridge.kt` (ENG-672): `inboxOpen` (the open inbox, the warm spare, or a new one), `inboxClose{hold}`, `inboxAnswer` (from `bridge/InboxAnswers.kt`, shared with the tap), `onionPost`, `_requestPaid`, and every address closed when Foxy is put away (`closeForBackground`, from `PutAway` through `WebHost.letGo`) and warmed again on the return (`cameBack`) and on Tor's changes (`Tor.onChange` → `torChanged`); addresses over tor-android's control link (`ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach`, `TorMachine.addOnion`, jtorctl). The Nostr half (`nostrSend`, the `nostr` field) is the follow-up: secp256k1-kmp Schnorr, NIP-44 v2, OkHttp WebSockets over Tor | onion P3 (ENG-672); Nostr LATER |
| `Foxy/Bridge/FoxyBridge+Tap.swift` | Bluetooth tap to pay, shake to TAP | `bridge/TapBridge.kt` (ENG-670): the receiving side. `bluetoothAsk` puts the system's question up for `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` (API 31+; `BLUETOOTH`/`BLUETOOTH_ADMIN` to API 30) and answers `allowed`/`denied`/`off`/`unsupported`/`unknown`, pushed to `__foxyBluetoothAnswer` first; `tapReceiveStart`/`tapReceiveStop` drive a `tap/TapReceiver.kt` (a GATT server and an advertiser, `tap/AndroidTapBluetooth.kt`) and push `_tap` as iOS's `pushTap`; a payment is `_requestPaid(…, "tap")` answered by `inboxAnswer`, from the one registry the onion's payments wait in too (`bridge/InboxAnswers.kt`, FoxyBridge.swift `inboxAnswers`; a tap's answer is kept when Foxy is put away, as `tapAnswerKeys`); `tapChange`, `tapAsking`, `tapChangeDue`, `tapTerms`, `_tapQuote`, `_tapChangeKeptLate`; shake to TAP (`SensorManager` accelerometer, `tap/Motion.kt` `ShakeDetector`, only while armed and in front) and `_turned` (`OrientationEventListener`, `TurnDetector`). A phone without a Bluetooth LE peripheral answers `unsupported` as ENG-656 did. Nothing advertises while the activity is stopped. The paying side (ENG-671): `tapPayStart` listens with a `tap/TapPayer.kt` over `tap/AndroidTapCentral.kt` (`BluetoothLeScanner` filtered on any service UUID, then iOS's rules for a Foxy advertisement, `connectGatt` LE, an MTU of 517, one GATT operation at a time, CCCD writes, long writes by the stack, `readRemoteRssi`) and is answered `{payload, code}`, never "Stopped." for a radio that cannot listen (ENG-658); `tapPayStop`, `tapSend`, `tapQuote`, `tapChangeKept`, `_tapChange`, `_tapTerms`; `BLUETOOTH_SCAN` (neverForLocation) and `BLUETOOTH_CONNECT`, asked in the APPROVE question; the payer runs from API 31 only and is `unsupported` on 28-30 (a scan there needs location, which Foxy never asks for); nothing scans while the activity is stopped | P3 (ENG-670 receiving, ENG-671 paying) |
| `Foxy/Bridge/FoxyBridge+Flashcard.swift` | NFC card sessions, APDU gate, owner key, interim card time | Sessions in ENG-667: `bridge/CardBridge.kt` (`cardBegin`, `cardSend`, `cardSay`, `cardAgain`, `cardEnd`, one at a time, the gate before the link, the applet chosen again after a card comes back, `_card` only from the session in hand) over `flashcard/NfcCardLink.kt` (`NfcAdapter.enableReaderMode` NFC-A/B with the NDEF check skipped, `IsoDep` with a 10 s answer, on the resumed activity only, 60 s wait, Cancel/Back, put away ends it) and Foxy's own card sheet (`flashcard/CardSheetView.kt`: Android has no system NFC sheet). Owner key and interim time done in ENG-666: `keychain/CardOwner.kt` (P-256 through JCA from the seed, via `keychain/P256.kt`), `cardOwnerKey`/`cardOwnerSign` in `bridge/SeedBridge.kt`, `cardTime` in `bridge/FoxyBridge.kt` | P3 (sessions ENG-667; owner key and time ENG-666) |
| `Foxy/Bluetooth/` (`TapCrypto`, `TapSession`, `TapProtocol`, and `TapLink`'s state with no radio in it) | the tap wire protocol and its crypto: the doors, the X25519 handshake with the payer's commitment, HKDF keys and the four digits, ChaCha20-Poly1305 bound to `th ‖ kind`, padding, M1 to M12, the proximity rule, change handed over, and every wait the links set | `tap/`: `TapCrypto.kt` (X25519 by Google Tink 1.23.0, since the platform has XDH only from API 33; the rest JCA), `TapSession.kt`, `TapProtocol.kt` (framing; what an offer may be is `bridge/TapRules.kt`), `TapProximity.kt`, and `TapReceiverLink.kt` / `TapPayerLink.kt`, the two sides' state machines with their clocks (`TapClock`), which the GATT server (`TapGattServer.kt`: iOS's four doors, a CCCD on each notify door, long writes, one notification at a time, the near doors and their byte; ENG-670) and the client (`TapPayer.kt` over `TapCentral`: the scan, the verdict, the quiet link, the near doors and the receiver's model, ENG-671) plug into as a `Radio`. JVM tests port `TapCryptoTests`, `TapSessionTests` and `TapProtocolTests` (`TapCryptoTest`, `TapSessionTest`, `TapProtocolTest`), run the two links against each other (`TapLinkTest`), and replay the iOS code's own bytes (`TapVectorsTest`, `fixtures/tap-vectors.json`, made by `tools/tap-vectors/generate.sh`). The receiver's radio is ENG-670 (`TapGattServerTest` runs it against a paying `TapPayerLink` over a fake GATT stack); the payer's is ENG-671 (`TapPayerTest` runs `TapPayer` against Android's own receiver, both at the ATT level, `PayerAir`) | P3 (ENG-669, ENG-670, ENG-671) |
| `Foxy/Debug/FieldLog.swift` | in-memory log for the ERRORS/LOGS screens, redacting tokens, invoices, requests, onion addresses, keys and long hex; a subset saved for the next run | `FieldLog.kt`: same ring and redaction regexes (ported with tests), last-run file in `noBackupFilesDir`, never logcat in release | P1 |
| `Foxy/Debug/Print.swift` | `print` reaches only FieldLog in release | a `log()` wrapper: logcat in debug, FieldLog only in release; R8 strips `android.util.Log` calls in release | P1 |
| `Foxy/Debug/DebugLog.swift`, `ResumeDiagnostics.swift`, `KeychainSelfTest.swift` | debug-only diagnostics, a file log, the Tor check | `src/debug` source set only; none in release | P1 (debug) |
| `Foxy/Flashcard/` (`CardGate`, `CardLink`, `CardTime`) | card APDU gate (31 instructions at 8df0bb1, with `0x1A` GET_DLEQ), NFC link, interim signed time | `flashcard/CardGate.kt` (the 31, byte for byte; SET_TAG `52` left out as upstream; `CardGateTests` ported, and every class, instruction and P1/P2 tried); `flashcard/CardLink.kt` + `NfcCardLink.kt` (reader mode and IsoDep, ENG-667); iOS's Simulator stand-in `SimCardLink` is `src/debug/.../flashcard/SimCardLink.kt` (getfoxy/card's jCardSim CardServer on loopback ports 47431-47434, debug builds only: the emulator, or the `FoxyCardSim` extra; `tools/cardsim-test.sh` drives the real applet through it); `CardTime` is `flashcard/CardTime.kt` (`InterimCardTime`, iOS's key pair byte for byte, `CardTimeTests` ported) | P3 (gate and link ENG-667; `CardTime` ENG-666) |
| `Foxy/Flashcard/CardAddress.swift`, `CardTapDelivery.swift` (since `9832f7d`) | a card held to the phone (its NDEF address `https://getfoxy.io/card`, or `foxy://card` in Debug) opens Foxy; the page is told `_cardTapped()`, kept until it can take it (not under a cover, not before `_resumed`), asked every 0.3 s, given up after 60 s, told once | `flashcard/CardAddress.kt` + `CardTapDelivery.kt` (ports, with `CardAddressTest`/`CardTapDeliveryTest` from iOS's tests), `CardTapIntake.kt` (Android only: the two intent actions, an intent replayed from Recents, no tap while a card session is open or 3 s after reader mode goes off); `CardTapActivity` (Theme.NoDisplay, no history, a task of its own) filters `NDEF_DISCOVERED` and an unverified https link for exactly `https://getfoxy.io/card` and routes the tap (`CardTapRoute.kt`: a new page, the page on top of its task via singleTop, or dropped while anything is over the page, so never a second MainActivity); the splash cover, `onPageFinished`, `onRenderProcessGone` and PutAway's `_resumed` drive the delivery; `foxy://card` (`web/DeepLink.kt`) goes to the same delivery | P3 (ENG-668) |
| `Foxy/Keychain/SeedStore.swift` | the seed in the keychain, this device only; two protections (`device`: `.userPresence`; `none`); three-way read (found/absent/failed); seed kept in memory for the visit; the putting-away window | `SeedVault.kt`: the words encrypted with an AES-256-GCM Keystore key (`setUnlockedDeviceRequired(true)`, StrongBox when `FEATURE_STRONGBOX_KEYSTORE`, else TEE), ciphertext in `noBackupFilesDir`; protection `device` uses a second key (`foxy.seed.device.v1`, its own file) with `setUserAuthenticationRequired(true)`: on API 30+ per use, through the framework `BiometricPrompt` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) with a `CryptoObject`; on API 28-29 a 10 s time-bound key opened by `KeyguardManager`'s screen-lock confirmation (`keychain/DeviceAuth.kt`); not invalidated by a new fingerprint; `none` uses the key without auth. A new install, and every P1/P2 install, is `none` until `seedProtect` moves it. Read answers found/absent/failed, never "absent" on an error. A first write cut off between keygen and the file rename leaves a key, no file and `foxy-seed.creating`; that reads absent and the orphan key is removed; a key without file and without the marker stays failed (iOS has no such window: one `SecItemAdd` is atomic). Because a read can remove that orphan key, `isThere`/`read`/`write` run on the `foxy-seed` executor only. Memory copy dropped on `onStop`, on page death, on write | P1 (`none`), P3 (`device`) |
| `Foxy/Keychain/BIP39.swift`, `bip39-english.txt` | words from 128 bits of `SecRandomCopyBytes`; wordlist pinned by SHA-256 | `SecureRandom` (128 bits); the same wordlist as an asset, refused unless its SHA-256 is the pinned one; the BIP-39 vectors as JVM tests | P1 |
| `Foxy/Keychain/NUT13.swift` | NUT-13 secrets: PBKDF2-SHA512 seed; HMAC-SHA256 for `01` keysets; BIP-32 on secp256k1 for `00` | `PBKDF2WithHmacSHA512` (JCA), `HmacSHA256`; BIP-32 with `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (libsecp256k1, pinned); the spec vectors and iOS's 856 cashu-ts answers as JVM tests | P1 |
| `Foxy/Keychain/CounterRules.swift`, `CounterStore.swift` | counter file: atomic, fsynced, never lowered, capped moves, 256 keysets, one entry per derivation index | `CounterStore.kt`: `AtomicFile` in `noBackupFilesDir` with `FileDescriptor.sync()` before the rename; the same rules ported with `CounterRulesTests`/`CounterStoreTests`/`CounterRangeCheckTests` | P1 |
| `Foxy/Keychain/P2PK.swift` | P2PK lock keys at m/129373'/10'/0'/0'/i | secp256k1-kmp; `P2PKTests` vectors | P1 |
| `Foxy/Keychain/SeedActions.swift`, `SeedCandidates.swift`, `SeedMigrationWindow.swift` | the seed and counter actions' logic, typed-word candidates, the one-time migration window | ported one to one with their tests (`SeedActionsTests`, `NativeSeedTests`, `SeedMigrationWindowTests`); candidates in memory only | P1 (candidates P3) |
| `Foxy/Network/Route.swift` | the one route decision; URL/host/redirect rules; answer cap; headers; door; host approvals | `network/Route.kt` (the decision, the rules, `inet_aton`'s grammar, the door), `network/RouteHttp.kt` (OkHttp: Tor's client per circuit label and the open connection's), `network/Socks5SocketFactory.kt` (SOCKS5 to Tor by name, the label as the login; `TorDns`), `network/HostApprovals.kt` (sealed with a Keystore key in `noBackupFilesDir`); `RouteTests` ported (`RouteTest`, `RouteClientTest`, `Socks5SocketFactoryTest`, `HostApprovalsTest`) | P2 (built) |
| `Foxy/Network/NetworkKind.swift` | path monitor: wifi/cellular/wired/other/none/unknown, tunnel with nothing under it | `network/NetworkKind.kt`: `registerDefaultNetworkCallback`, `NetworkCapabilities` transports (a VPN with no other transport is none); feeds the snapshot's `network` and `vpn` and Tor's `networkWent` | P2 (built) |
| `Foxy/Network/OrbotLink.swift`, `LoopbackHTTP.swift` | Orbot's local API on 127.0.0.1:15182 and its bypass port | Not ported (decision 3): Orbot is reported as `none`, and `orbotRefresh`/`orbotAccess` are LATER in `contract.json` and answer their stubs. Under Orbot's VPN, Foxy's Tor runs inside it: Tor over Tor, slower, no leak | LATER |
| `Foxy/Network/OnionAddress.swift`, `OnionInbox.swift`, `OnionPost.swift` | v3 onion parsing, the inbox listener, posting to an onion through SOCKS | `network/OnionAddress.kt`, `network/OnionInbox.kt` (a `ServerSocket` on `127.0.0.1` port 0, the same bounds and answers byte for byte), `network/OnionHttp.kt` (`HTTPRequestHead`, `HTTPAnswer`), `network/OnionPost.kt` (SOCKS5 through `Socks5SocketFactory`, iOS's request byte for byte; `OnionPost.get` for the price's onion is not needed: that goes through OkHttp). `OnionHTTPTests` ported as `OnionHttpTest` (ENG-672) | P3 (ENG-672) |
| `Foxy/Network/Locked.swift` | a lock-protected value | `AtomicReference` / `Mutex` | P1 |
| `Foxy/Nostr/` | NIP-17/59 gift wrap, NIP-44, inbox | secp256k1-kmp Schnorr; NIP-44 with JCA `ChaCha20` (API 28+) and HMAC-SHA256; `NostrCryptoTests` vectors | LATER |
| `Foxy/Scanner/QRScannerViewController.swift`, `LiveScanner.swift` | full-screen scanner; live preview laid over a page rectangle | CameraX 1.5.3 `PreviewView` + `ImageAnalysis` (4K, else the closest below) with zxing-cpp 3.0.2 (`scanner/QrDecoder.kt`); `scanner/ScannerActivity.kt` (full screen) and `scanner/LivePreview.kt` (an overlay at the page's CSS rect × density, unbound on every stop); close-focus zoom from Camera2's minimum focus distance; the same rect checks | P3 (built) |
| `Foxy/Scanner/AnimatedQR.swift` | animated (UR fountain) QR assembly | `scanner/AnimatedQR.kt`, a byte-for-byte port, checked against iOS's `animated-qr.json` (`AnimatedQRReaderTest`) and, drawn as QR codes, through zxing-cpp on the phone (`QrDecodeTest`) | P3 (built) |
| `Foxy/Tor/TorService.swift`, `TorTransport.swift` | Tor in-process: start, ready (circuit + directory + SOCKS port read back), transport ladder (direct, obfs4, Snowflake), stuck detection, park/resume, restart | `tor/TorMachine.kt` (the state machine, ported: ready, the port and its generation guard, the ladder and its patience, the stall watch, stuck, park/resume, the phone's network) over `tor/Tor.kt` (tor-android's `TorService`, bound and never started, so never in the foreground; its jtorctl control link; the main looper), `tor/TorConfig.kt` (the torrc), `tor/TorTransport.kt` (order, patience, the day's bridge memory, `TorStuck`). Not ported, being iOS's: the control-link reconnects and the reclaimed-listener recovery (tor-android owns one Unix control socket) | P2 (built) |
| `Foxy/Tor/Bridges.swift` | built-in obfs4 and Snowflake lines; IPtProxy controller | `tor/Bridges.kt`: the lines between the markers byte for byte (pinned by SHA-256 in `TorConfigTest`), and the IPtProxy controller wrapped as iOS wraps it (state dir 0700) | P2 (built) |
| `Foxy/Tor/TorSeed.swift`, `Foxy/TorSeed/` | a bundled relay directory so a first start does not fetch the whole consensus | `app/tor-seed/tor-microdescs.xz` is iOS's file byte for byte; the build unpacks it (`gradle/tor-seed.gradle.kts`, pinned in `app/tor-seed/PIN`) and the APK carries the plain file, because a Kotlin xz decoder on the phone took 33-73 s in a debug build; `tor/TorSeed.kt` copies it into tor-android's cache directory on a first run, checking its SHA-256, with iOS's unproven marker | P2 (built) |
| `Vendor/TorPod`, `Vendor/Tor.sha256` | Tor 0.4.9.12 built from signed sources | `info.guardianproject:tor-android:0.4.9.12` (and its `jtorctl` 0.4.5.7, declared too) pinned by version and SHA-256 in Gradle dependency verification; its AAR asks for compile SDK 37.1. A reproducible build of it is a follow-up | P2 (built) |
| `Vendor/IPtProxy.sha256` | IPtProxy built here, byte-identical twice | `com.netzarchitekten:IPtProxy:5.5.0` pinned the same way (iOS builds 5.5 too) | P2 (built) |
| `Vendor/secp256k1` | libsecp256k1 v0.8.0 | secp256k1-kmp (wraps libsecp256k1), pinned and verified | P1 |
| `FoxyTests/` | 382 XCTest cases for the native rules | JVM unit tests (`src/test`) for every ported rule, using the same vectors and fixtures; instrumented tests (`src/androidTest`) for WebView, Keystore and backup behaviour; `conformance/` for the bridge | P1 onwards |

## Decisions still open for the architect

1. **Per-job Tor circuits from OkHttp.** iOS passes the circuit label as the
   SOCKS5 username. Java's SOCKS client reads credentials from the process-wide
   `java.net.Authenticator`, which is not per request. **Decided and built
   (ENG-648):** a SOCKS5 `SocketFactory` of Foxy's (`network/Socks5SocketFactory.kt`:
   domain-name CONNECT, username = password = label, 127.0.0.1 by its bytes)
   and a `Dns` that never resolves, one factory and one connection pool per
   label (OkHttp's `Address` does not compare socket factories). Tested against
   a fake SOCKS server that records ATYP 0x03 and the exact name
   (`Socks5SocketFactoryTest`, `RouteClientTest`; ROUTE.md §5).
2. **`setBlockNetworkLoads(true)` with `WebViewAssetLoader`.** Checked on a
   device in P1 (ENG-647, 2026-10-11, Pixel 8 Pro, API 37, the debug build):
   with `setBlockNetworkLoads(true)`, the dead-end proxy (`127.0.0.1:9`, no
   direct fallback, implicit loopback bypass removed) and no `INTERNET`
   permission, the asset loader still serves every file of the page, and the
   unmodified page boots to CANNOT CONNECT TO TOR (`PageBootTest`); the iOS
   net-block set (fetch, WebSocket, preconnect, DNS prefetch, prefetch, `<img>`,
   beacon, a Worker's fetch) reaches nothing (`NetBlockTest`, against a loopback
   listener in the test package's own process). **Decided: kept as the second
   wall.** Found on the way: `addDocumentStartJavaScript` does not reach a
   same-origin `about:blank` or `srcdoc` frame, where WKWebView's
   all-frames user scripts do, so the `noWebRTC` script carries the removal into
   each child frame itself (`web/Scripts.kt`; `PageBootTest` checks a blank frame
   reached through `contentWindow`, through `window[n]` straight after the
   append, and one frame further down). The intro video with the network
   blocked is a manual check (`docs/DEVICE-TESTS.md`).
3. **Orbot on Android.** Different API and VPN model from iOS. **Decided
   (ENG-648): not in P2.** Orbot is reported as `none`; `orbotRefresh` and
   `orbotAccess` are LATER in `contract.json` and keep answering their stubs.
   Under Orbot's VPN, Foxy's own Tor runs inside it: Tor over Tor, slower, no
   leak.
4. **`pasteStart`.** **Decided (ENG-649):** a see-through native button over the
   page's rect, as iOS lays its paste control, with the same rectangle checks;
   the clipboard is read only in its tap (`ClipboardSensitiveTest`).
5. **Screen capture.** `FLAG_SECURE` always; `captured` answers `"no"`;
   `_screenshot` from `Activity.ScreenCaptureCallback` on API 34+ only (built
   in ENG-649, `DETECT_SCREEN_CAPTURE`).
6. **Clipboard sync.** Android has no device-only flag like iOS's `.localOnly`;
   some vendors sync the clipboard across devices. `EXTRA_IS_SENSITIVE` keeps a
   copy out of previews and keyboard suggestions; the 120 s clear is Foxy's own.
7. **The phone's font size and display size (ENG-661).** Android's WebView
   scales page text by the system font scale (`WebSettings.textZoom` starts at
   fontScale × 100); WKWebView does not apply Dynamic Type to the page, so the
   pinned page's fixed-size buttons were only ever laid out at 100 %. At
   font_scale 1.3 the backup sheet's button text overflowed its buttons.
   **Decided: `textZoom = 100`** (`WebHost.PAGE_TEXT_ZOOM`, `TextZoomTest`),
   the page laid out as on iOS. The trade-off: a person who raised the font
   size for legibility gets no larger text in the page, as on iOS today; the
   native screens (SHOW WORDS, ENTER WORDS, the dialogs) still follow it, and
   pinch zoom is not offered by the page. Larger text in the page would be an
   upstream change (a large-text mode in `core/web` whose layout holds it),
   not a host setting. Display size is left alone: it changes the density, and
   a CSS px is one dp, so it narrows the page's viewport as iOS's Display Zoom
   does (the Pixel 8 Pro's 1344 px are 448 CSS px at 480 dpi and about 395 at
   544, inside the widths the page meets on iPhones, 375 to 440); on a phone
   whose largest display size takes the width below 375 CSS px the page lays
   out narrower than on any iPhone. Checked by arithmetic here; the look on a
   phone is DEVICE-TESTS' P3 check 9.

## The seven High audit findings, closed by design

An audit of an earlier iOS build (2026-09) raised seven High findings. Each is
closed on Android by construction, not by a later fix:

1. **The seed and proofs went into backups.** Android: `android:allowBackup="false"`,
   `android:fullBackupContent="false"` (API 28-30) and
   `android:dataExtractionRules` excluding every domain for both `cloud-backup`
   and `device-transfer` (API 31+); the seed ciphertext, counters, P2PK index,
   host approvals and Tor state live in `noBackupFilesDir`; the WebView's
   storage is app-private and covered by the same rules; Keystore keys never
   leave the device. Proved by a manifest test and an instrumented
   `bmgr backupnow` check (SECURITY-INVARIANTS.md S1).
2. **A Lightning-address invoice was paid without checking its amount.** This
   is the JS core's, and it is **already fixed at 5581c96** (and still at 8df0bb1): `payLnurl`
   refuses an invoice whose amount is not exactly the one asked, to the
   millisat, and one whose description hash does not match the metadata
   (`Web/foxy-wallet.js:18696-18721`; iOS REVIEW.md row 64, `tests/run.js`).
   Android ships that code byte for byte (`tools/verify-core.sh`).
3. **A failed Tor check left traffic flowing.** Android: there is no page-side
   switch to forget; `Route.available` is false unless Tor is running or the
   person said yes in a native dialog, and every native request goes through
   `Route` (ROUTE.md §2, §5). When Tor fails, nothing has a route; nothing
   falls back to direct. Conformance proves the page asks for nothing that
   needs a route while Tor is connecting or failed (`flows-p1-p2.test.js`).
4. **Background pollers and the NUT-17 socket were not gated.** At 5581c96 (and 8df0bb1) the
   page opens no socket (`Web/foxy-wallet.js:17248`) and its sweeps check
   `routeOpen()` first (`Web/foxy-wallet.js:17485`); and native refuses every
   request without a route, shuts the door while the app is put away, and runs
   no background job. Android adds no `WorkManager`, alarm or foreground service.
5. **Native price, chart and Tor-check requests had no tunnel precondition.**
   Android has one HTTP client, inside `Route`, and a CI check that fails the
   build on any other (ROUTE.md §5). The Tor check exists only in debug builds.
6. **Tokens were copied to the general pasteboard with no expiry.** Android:
   `copy` with `sensitive: true` sets `ClipDescription.EXTRA_IS_SENSITIVE`
   and clears the clipboard after 120 s if it still holds Foxy's copy
   (`ClipboardManager.clearPrimaryClip`, API 28+).
7. **No navigation policy.** Android: `shouldOverrideUrlLoading` denies every
   navigation, no new windows, and only the asset origin is served
   (ROUTE.md §4).
