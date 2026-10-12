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
phone), **LATER** (onion inbox, Nostr, Bluetooth tap, NFC card).

## Components

| iOS | What it does there | Android | Phase |
|---|---|---|---|
| `Foxy/FoxyApp.swift` | SwiftUI app; starts the field log; refuses third-party keyboards (`shouldAllowExtensionPointIdentifier`) | `FoxyApplication` (field log start) + one `MainActivity`, portrait, `windowSoftInputMode=adjustResize`. Third-party keyboards cannot be refused on Android; the WebView's input connection and native text fields set `IME_FLAG_NO_PERSONALIZED_LEARNING` (and `textNoSuggestions` on the seed screen) | P1 (keyboard flags P3) |
| `Foxy/FoxyWebView.swift` | stages `Web/` into Caches; WKWebView config; content-rule network block; dead-end proxy; document-start scripts (no WebRTC, no callout, clock, debug flags, console silence, page hash); navigation lock; splash and app-switcher cover; lifecycle pushes (`_putAway`, `_tidying`, `_resumed`, `__foxyUptime`); reinstall detection; web-store backup exclusion; process-death reload | `WebHost` in `MainActivity`: `WebViewAssetLoader` serving `core/web` from APK assets (no staging copy needed); `shouldInterceptRequest` refuse-all except the loader; `setBlockNetworkLoads(true)`; `ProxyController` dead-end override; `addDocumentStartJavaScript` for the same seven scripts; `shouldOverrideUrlLoading` → deny; `onRenderProcessGone` → drop native page state, recreate; `FLAG_SECURE` on the window (covers the recents thumbnail and blocks capture, which is the cover and more); AndroidX SplashScreen held until `covered` or 8 s; lifecycle from `ProcessLifecycleOwner`/`onStop`/`onStart`; page hash computed at build time from `core/SHA256SUMS` and checked at launch against the APK's assets. Reinstall: Keystore keys and app data are deleted with the app on Android, so no marker is needed | P1 |
| `Foxy/Assets.xcassets` | icon, accent, splash art | `res/mipmap-*` adaptive icon, `res/drawable` splash, theme colours | P1 |
| `Foxy/PrivacyInfo.xcprivacy` | App Store privacy manifest: nothing collected, no tracking | Play Console Data safety form (nothing collected or shared); no SDKs to declare | P3 |
| `Foxy/Bridge/FoxyBridge.swift` | the action table, dispatch, frame gate, `_scanResult` replies, haptics, price/candles/mintRequest, clipboard/copy/share, biometric, notify, preview/paste controls, open settings/company, Orbot, privacy/torRetry/unprotected | `bridge/FoxyBridge.kt`: one `Map<String, Handler>` with the same 74 names; `WebMessageListener.onPostMessage` checks `sourceOrigin == https://appassets.androidplatform.net` and `isMainFrame`, parses JSON, dispatches as `dispatch` does (haptic / action+id / ignored); replies through one `resolve(id, text, error)` that JSON-quotes each argument and calls `evaluateJavascript` on the main thread. Each handler's argument checks are ported with its Swift tests | P1 transport and P1 actions; others by their phase |
| `Foxy/Bridge/NativeSeedBridge.swift` | the seed, counter, P2PK and card-owner actions, argument checks, one serial seed queue, the seed screens' entry points | `bridge/SeedBridge.kt`: the same checks (`exactInteger`, `keyset`, ranges), one single-thread `Executor` for every seed action | P1 (screens P3, card LATER) |
| `Foxy/Bridge/SeedScreens.swift` | native SHOW WORDS (with quiz) and ENTER WORDS screens; one at a time; closed on background; hidden while captured | `seed/ShowWordsActivity.kt`, `seed/EnterWordsActivity.kt`: two `ComponentActivity` screens in plain Views (no Compose, ENG-649 decision 4), `FLAG_SECURE`, finished in `onStop`; the words handed over in-process (`SeedScreenHost`), never in an Intent; the pure parts (`seed/SeedScreens.kt`: quiz, cells, one-at-a-time queue, 10 s pause after a failed read) ported with their tests. No capture hiding: `FLAG_SECURE` keeps every capture blank | P3 (built) |
| `Foxy/Bridge/NativePrompts.swift` | one queue of native alerts (host approval, seed replace/delete, continue without Tor) | `bridge/PromptQueue.kt`: `AlertDialog`s on the activity in front, one at a time, `FLAG_SECURE` like the activity; any way a dialog goes down without a button is no; nothing in front is no (`PromptQueueTest`) | P2 (host/Tor, built), P3 (seed) |
| `Foxy/Bridge/SeedPasteboard.swift` | a seed phrase on the clipboard reads as empty; remembers what Foxy itself copied | `bridge/ClipboardFilter.kt` (the rules) and `web/Clipboard.kt`: the same BIP-39 phrase test on read; Foxy's copies labelled `foxy:<nonce>` (the restore screen refuses them) and, when sensitive, marked `android.content.extra.IS_SENSITIVE` and cleared after 120 s while still Foxy's, looked at again when the window has focus | P3 (built) |
| `Foxy/Bridge/PasteHost.swift` | a near-invisible `UIPasteControl` over the page's PASTE button, so iOS shows no paste prompt | `web/PasteButton.kt`: a see-through native button over the page's rect, with iOS's rectangle checks (`PlatformRules.pasteAllowed`); the clipboard is read only in its tap and pushed with `_pasted`, never a seed phrase (decision 4, decided in ENG-649) | P3 (built) |
| `Foxy/Bridge/ForegroundNotifications.swift` | show banners while in the foreground | `notify/Notifier.kt`: `NotificationChannel` "Payments" (default importance, made in `FoxyApplication`), framework `Notification.Builder` with fixed text, `VISIBILITY_PRIVATE` with a public version that says only "Foxy"; `POST_NOTIFICATIONS` asked on the first `notify`, and refused the answer is still `"ok"` | P3 (built) |
| `Foxy/Bridge/FoxyBridge+Delivery.swift` | onion inbox, Nostr inbox, `onionPost`, `nostrSend` | the same over tor-android's control port (`ADD_ONION NEW:ED25519-V3 Flags=DiscardPK`) and a loopback listener; OkHttp WebSockets over Tor for Nostr | LATER |
| `Foxy/Bridge/FoxyBridge+Tap.swift` | Bluetooth tap to pay, shake to TAP | BLE GATT (`BluetoothLeAdvertiser`, `BluetoothLeScanner`, RSSI cut-off), `SensorManager` shake detector; `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`. Until then `bluetoothAsk` (the first-install screen's APPROVE) is answered `unsupported`, pushed to `__foxyBluetoothAnswer` too, and the receive screen's `tapReceiveStart` is answered `"ok"` then `_tap({side:"receive",stage:"unsupported"})`, as an iPhone without Bluetooth answers, with `tapReceiveStop` `"ok"`; no permission asked (`bridge/PlatformBridge.kt`, `bridge/TapRules.kt`, ENG-656) | LATER (`bluetoothAsk`, `tapReceiveStart`, `tapReceiveStop`, `_tap` P3) |
| `Foxy/Bridge/FoxyBridge+Flashcard.swift` | NFC card sessions, APDU gate, owner key, interim card time | `NfcAdapter.enableReaderMode` + `IsoDep`; `CardGate` ported with its tests; owner key P-256 via `java.security` from the seed | LATER |
| `Foxy/Bluetooth/` (`TapLink`, `TapSession`, `TapProtocol`, `TapCrypto`) | the tap wire protocol and its crypto | Kotlin port with the Swift tests' vectors (`TapProtocolTests`, `TapCryptoTests`, `TapSessionTests`) as JVM unit tests | LATER |
| `Foxy/Debug/FieldLog.swift` | in-memory log for the ERRORS/LOGS screens, redacting tokens, invoices, requests, onion addresses, keys and long hex; a subset saved for the next run | `FieldLog.kt`: same ring and redaction regexes (ported with tests), last-run file in `noBackupFilesDir`, never logcat in release | P1 |
| `Foxy/Debug/Print.swift` | `print` reaches only FieldLog in release | a `log()` wrapper: logcat in debug, FieldLog only in release; R8 strips `android.util.Log` calls in release | P1 |
| `Foxy/Debug/DebugLog.swift`, `ResumeDiagnostics.swift`, `KeychainSelfTest.swift` | debug-only diagnostics, a file log, the Tor check | `src/debug` source set only; none in release | P1 (debug) |
| `Foxy/Flashcard/` (`CardGate`, `CardLink`, `CardTime`) | card APDU gate (31 instructions at 8df0bb1, with `0x1A` GET_DLEQ), NFC link, interim signed time | Kotlin port; `IsoDep` link | LATER |
| `Foxy/Flashcard/CardAddress.swift`, `CardTapDelivery.swift` (since `9832f7d`) | a card held to the phone (its NDEF address `https://getfoxy.io/card`, or `foxy://card` in Debug) opens Foxy; the page is told `_cardTapped()`, kept until it can take it (not under a cover, not before `_resumed`), asked every 0.3 s, given up after 60 s, told once | an `NDEF_DISCOVERED` filter for `https://getfoxy.io/card` and a Kotlin port of the delivery rules with `CardTapDeliveryTests`; `foxy://card` is read today (`web/DeepLink.kt`) and only logged | LATER (ENG-668) |
| `Foxy/Keychain/SeedStore.swift` | the seed in the keychain, this device only; two protections (`device`: `.userPresence`; `none`); three-way read (found/absent/failed); seed kept in memory for the visit; the putting-away window | `SeedVault.kt`: the words encrypted with an AES-256-GCM Keystore key (`setUnlockedDeviceRequired(true)`, StrongBox when `FEATURE_STRONGBOX_KEYSTORE`, else TEE), ciphertext in `noBackupFilesDir`; protection `device` uses a second key (`foxy.seed.device.v1`, its own file) with `setUserAuthenticationRequired(true)`: on API 30+ per use, through the framework `BiometricPrompt` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) with a `CryptoObject`; on API 28-29 a 10 s time-bound key opened by `KeyguardManager`'s screen-lock confirmation (`keychain/DeviceAuth.kt`); not invalidated by a new fingerprint; `none` uses the key without auth. A new install, and every P1/P2 install, is `none` until `seedProtect` moves it. Read answers found/absent/failed, never "absent" on an error. A first write cut off between keygen and the file rename leaves a key, no file and `foxy-seed.creating`; that reads absent and the orphan key is removed; a key without file and without the marker stays failed (iOS has no such window: one `SecItemAdd` is atomic). Because a read can remove that orphan key, `isThere`/`read`/`write` run on the `foxy-seed` executor only. Memory copy dropped on `onStop`, on page death, on write | P1 (`none`), P3 (`device`) |
| `Foxy/Keychain/BIP39.swift`, `bip39-english.txt` | words from 128 bits of `SecRandomCopyBytes`; wordlist pinned by SHA-256 | `SecureRandom` (128 bits); the same wordlist as an asset, refused unless its SHA-256 is the pinned one; the BIP-39 vectors as JVM tests | P1 |
| `Foxy/Keychain/NUT13.swift` | NUT-13 secrets: PBKDF2-SHA512 seed; HMAC-SHA256 for `01` keysets; BIP-32 on secp256k1 for `00` | `PBKDF2WithHmacSHA512` (JCA), `HmacSHA256`; BIP-32 with `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (libsecp256k1, pinned); the spec vectors and iOS's 856 cashu-ts answers as JVM tests | P1 |
| `Foxy/Keychain/CounterRules.swift`, `CounterStore.swift` | counter file: atomic, fsynced, never lowered, capped moves, 256 keysets, one entry per derivation index | `CounterStore.kt`: `AtomicFile` in `noBackupFilesDir` with `FileDescriptor.sync()` before the rename; the same rules ported with `CounterRulesTests`/`CounterStoreTests`/`CounterRangeCheckTests` | P1 |
| `Foxy/Keychain/P2PK.swift` | P2PK lock keys at m/129373'/10'/0'/0'/i | secp256k1-kmp; `P2PKTests` vectors | P1 |
| `Foxy/Keychain/SeedActions.swift`, `SeedCandidates.swift`, `SeedMigrationWindow.swift` | the seed and counter actions' logic, typed-word candidates, the one-time migration window | ported one to one with their tests (`SeedActionsTests`, `NativeSeedTests`, `SeedMigrationWindowTests`); candidates in memory only | P1 (candidates P3) |
| `Foxy/Network/Route.swift` | the one route decision; URL/host/redirect rules; answer cap; headers; door; host approvals | `network/Route.kt` (the decision, the rules, `inet_aton`'s grammar, the door), `network/RouteHttp.kt` (OkHttp: Tor's client per circuit label and the open connection's), `network/Socks5SocketFactory.kt` (SOCKS5 to Tor by name, the label as the login; `TorDns`), `network/HostApprovals.kt` (sealed with a Keystore key in `noBackupFilesDir`); `RouteTests` ported (`RouteTest`, `RouteClientTest`, `Socks5SocketFactoryTest`, `HostApprovalsTest`) | P2 (built) |
| `Foxy/Network/NetworkKind.swift` | path monitor: wifi/cellular/wired/other/none/unknown, tunnel with nothing under it | `network/NetworkKind.kt`: `registerDefaultNetworkCallback`, `NetworkCapabilities` transports (a VPN with no other transport is none); feeds the snapshot's `network` and `vpn` and Tor's `networkWent` | P2 (built) |
| `Foxy/Network/OrbotLink.swift`, `LoopbackHTTP.swift` | Orbot's local API on 127.0.0.1:15182 and its bypass port | Not ported (decision 3): Orbot is reported as `none`, and `orbotRefresh`/`orbotAccess` are LATER in `contract.json` and answer their stubs. Under Orbot's VPN, Foxy's Tor runs inside it: Tor over Tor, slower, no leak | LATER |
| `Foxy/Network/OnionAddress.swift`, `OnionInbox.swift`, `OnionPost.swift` | v3 onion parsing, the inbox listener, posting to an onion through SOCKS | Kotlin port; listener on `127.0.0.1` with the same bounds; `OnionHTTPTests` ported | LATER |
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
   needs a route while Tor is connecting or failed (`flows.test.js`).
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
