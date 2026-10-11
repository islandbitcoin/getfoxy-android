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

## Checks

```sh
tools/verify-core.sh
cd conformance && npm ci && npm test
# with a clone of getfoxy/iOS, every iOS file:line in the contract and the docs is checked too:
FOXY_IOS_REF=/path/to/ios-clone npm test
```

CI (`.github/workflows/ci.yml`) runs both on every push and pull request, with
the iOS repository fetched at the pinned commit.

## Phases

| Phase | What lands |
|---|---|
| **P0** (this) | the contract, the pinned core, the conformance suite |
| **P1** shell | Kotlin app: WebView at the asset origin with no network, the bridge and shim, lifecycle, seed and counters in the Keystore, the field log; every network action refused |
| **P2** Tor | tor-android + IPtProxy, `Route` over OkHttp through Tor's SOCKS port, mint/price/chart requests, the Tor gate, host approvals, continue-without-Tor |
| **P3** platform | scanner (CameraX + zxing-cpp), clipboard and share, notifications, haptics, biometrics, native seed screens: the first build for a phone |
| **LATER** | onion inbox and post, Nostr, Bluetooth tap to pay, NFC card |
