# getfoxy-android
Send and receive bitcoin, lightning and cashu privately, easily and semi-offline - Android Fork

## What this is

The Android port of [Foxy](https://github.com/getfoxy/iOS). Foxy's wallet is a
web page (cashu-ts underneath) hosted by a thin native shell that does what a
page cannot: Tor, the seed, the camera, the clipboard, notifications. The iOS
page ships here **byte for byte**; the Android app is a Kotlin host and an
Android System WebView implementing the same bridge. Every network request goes
through the app's own Tor or is refused, unless the person chooses, in a native
dialog, to continue without Tor for the session.

## Layout

| Path | What |
|---|---|
| `core/web/` | the iOS app's `Web/` directory, unmodified, at the commit in `core/PIN` |
| `core/PIN`, `core/SHA256SUMS`, `core/UPSTREAM-LICENSE` | where it came from, the sha256 of every file (in the iOS page-hash order, so `sha256(SHA256SUMS)` is the hash the app's menu shows), and the upstream licence |
| `tools/sync-core.sh <commit> [repo]` | re-import `core/web` from an iOS commit and regenerate the pins |
| `tools/verify-core.sh [--against <ios-clone>]` | fail if any file in `core/web` differs from `SHA256SUMS`, is missing, extra or a link |
| `docs/BRIDGE.md` | the page ↔ native contract: every message, payload, reply, error, ordering rule and iOS `file:line` |
| `docs/ROUTE.md` | the network invariant: how requests leave, every host, the WebView's network block, what Android must do |
| `docs/ANDROID-MAPPING.md` | each iOS component and its Android replacement, by phase; the audit findings closed by design |
| `docs/SECURITY-INVARIANTS.md` | every host-dependent security claim, restated for Android with the test that proves it |
| `conformance/` | `contract.json` and a node + jsdom suite that boots `core/web/index.html` against a fake native host built from it |
| `app/` | the Kotlin app (P2): `MainActivity` and `web/WebHost` (the WebView and its walls), `bridge/` (the contract's table, the shim's other half, the seed actions' checks, the P2 actions in `NetBridge`, the one queue of native dialogs), `keychain/` (the seed vault, BIP-39, NUT-13, P2PK, the counter file, sealed files), `network/` (`Route`, OkHttp through Tor's SOCKS port with a SOCKS5 factory per circuit label, host approvals, the phone's network), `tor/` (tor-android in process, the transport ladder, the bridges, the directory seed), `debug/` (the field log); JVM tests in `app/src/test`, phone tests in `app/src/androidTest` |
| `app/tor-seed/` | iOS's relay directory seed (`tor-microdescs.xz`, byte for byte) and its pins; `gradle/tor-seed.gradle.kts` unpacks it into the APK |
| `tools/check-no-network.sh` | fail if anything under `app/src` can reach the network other than through `network/` (with its self-test, `tools/check-no-network.test.sh`) |
| `tools/leak-test.sh`, `tools/leak-check.js` | the P2 leak test: an emulator capture through each transport, and the analysis that holds every flow of the app's to Tor (with its tests, `tools/leak-check.test.js`) |
| `tools/cdp-eval.js`, `tools/real-money.js`, `tools/fake-invoice.js` | drive the page of a debug build over WebView debugging; the money rules of a leg on a real mint (the person's go, 100 sats or less, the person's own invoice: docs/DEVICE-TESTS.md "Real money"); an invoice that pays no one, for the scanner check only, never paid |
| `tools/cardsim-test.sh`, `tools/cardsim.PIN` | the card session end to end: getfoxy/card's applet at the pinned commit, built with that repository's own tooling and run in jCardSim, driven through the bridge and the debug build's simulator link (`CardSimEndToEndTest`) |
| `docs/DEVICE-TESTS.md` | what is checked on a phone: the instrumented tests and the checks by hand |
| `app/art/NOTICE.md`, `tools/make-art.py` | the app's icons and splash, cut from the iOS art at `core/PIN`'s commit: each source, its sha256 and the upstream licence; the script that cuts them (Pillow, by hand) |

## Checks

```sh
tools/verify-core.sh
cd conformance && npm ci && npm test
# with a clone of getfoxy/iOS, every iOS file:line in the contract and the docs is checked too:
FOXY_IOS_REF=/path/to/ios-clone npm test

# the leak test's analysis and the money rules
node --test tools/leak-check.test.js tools/fake-invoice.test.js tools/real-money.test.js

# the app (JDK 17, Android SDK platform 37.1: tor-android's AAR asks for it)
tools/check-no-network.sh && tools/check-no-network.test.sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
# the card session against getfoxy/card's applet in jCardSim (JDK 17 and Maven; tools/cardsim.PIN)
tools/cardsim-test.sh [path/to/getfoxy-card-clone]
# on a phone (docs/DEVICE-TESTS.md)
./gradlew connectedDebugAndroidTest
```

Dependencies are pinned to exact versions in `gradle/libs.versions.toml` and by
SHA-256 in `gradle/verification-metadata.xml`, checked strictly on every build;
after changing a pin, regenerate it on macOS and on Linux:
`./gradlew --write-verification-metadata sha256 help testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.

CI (`.github/workflows/ci.yml`) runs the core and conformance checks, with the
iOS repository fetched at the pinned commit, and the app's checks and builds,
on every push and pull request.

## Phases

| Phase | What lands |
|---|---|
| **P0** | the contract, the pinned core, the conformance suite |
| **P1** shell | Kotlin app: WebView at the asset origin with no network, the bridge and shim, lifecycle, seed and counters in the Keystore, the field log; every network action refused |
| **P2** Tor (this) | tor-android + IPtProxy, `Route` over OkHttp through Tor's SOCKS port, mint/price/chart requests, the Tor gate, host approvals, continue-without-Tor |
| **P3** platform | scanner (CameraX + zxing-cpp), clipboard and share, notifications, haptics, biometrics, native seed screens: the first build for a phone; the NFC card's owner key and signed time (ENG-666) and its session over reader mode with Foxy's own card sheet (ENG-667), and a card held to the phone opening Foxy (`_cardTapped`, ENG-668) |
| **LATER** | onion inbox and post, Nostr, Bluetooth tap to pay |
