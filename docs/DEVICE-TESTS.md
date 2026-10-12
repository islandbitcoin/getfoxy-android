# DEVICE-TESTS.md — what is checked on a phone, by hand or by instrumentation

Some of the host's claims (`SECURITY-INVARIANTS.md`) can only be shown on a real
phone. This is the list for P1: the instrumented tests that run on one, and the
checks a person makes by hand. CI builds the debug APK and the test APK but has
no phone, so none of this runs there; each P1 pull request says which of these
it ran, on which phone, and what each answered.

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
| `PutAwayTest` | putting Foxy away shuts the door; coming back opens it and pushes `__foxyUptime`; a reply and a push after the WebView is gone are dropped by `WebHost.evaluate`'s guard (two `DROPPED_LINE`s in the field log) and nothing throws | S21 |
| `SeedForgottenTest` | a seed kept for the visit is gone the moment the activity is finished (Back), inside ProcessLifecycleOwner's 700 ms hold, when `PutAway` never runs; it stays gone after the hold. Needs no Keystore, so it runs on a locked phone too | S20 |

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
6. **Nothing reaches the network.** With PCAPdroid capturing Foxy's uid, a
   launch through to the gate and PROCEED OFFLINE records no packet. (S8, S9)
7. **The seed outlives a restart.** On an unlocked phone: let the page make a
   seed (`seedCreate`), `adb shell am force-stop io.islandbitcoin.foxy`, launch
   again: `seedStatus` answers `{"exists":true}` and the page shows the same
   wallet. (S3, S4; the ENG-647 device exit.) Wiping the seed is not checkable
   in P1: `seedWipe` is P3 (BRIDGE.md `seedWipe` — P3, S5).

## Runs

Each run: the commit, the phone, and what each test answered. A skipped test
is not a pass; the device exit needs a run with nothing skipped but
`KeystoreTest.onALockedPhone…` (which runs only on a locked phone), plus check 7.

| Date | Commit | Phone | Instrumented | Check 7 |
|---|---|---|---|---|
| 2026-10-11 | review fixes on `jabariennis/eng-647-shell` | Pixel 8 Pro (42201FDJG00048), **locked** | 13 run: 11 passed, 2 skipped as locked (`KeystoreTest.theVaultRoundTrips…`, `PutAwayTest.puttingAway…`); `onALockedPhone…` passed | not run: needs the phone unlocked |
| 2026-10-11 | `3de3f577e3844799e2946a7fc58323476b332cc7` | Pixel 8 Pro (42201FDJG00048), **locked** (keyguard showing; the agent cannot unlock it) | 13 run: 11 passed, 2 skipped as locked (`KeystoreTest.theVaultRoundTrips…`, `PutAwayTest.puttingAway…`); `onALockedPhone…` passed; debug build with the `foxy-seed` thread check, no check tripped | not run: needs the phone unlocked |
| 2026-10-11 | `1344bd476f0af6c31f8ff0034ffd9ee545639044` | Pixel 8 Pro (42201FDJG00048), **unlocked** | `OK (13 tests)`: 12 passed, `KeystoreTest.onALockedPhone…` skipped as designed (phone unlocked) | pass: `pm clear` → launch → `[foxy] seed: made on the phone and saved` (`no_backup/foxy-seed.v1.bin`, 101 B) → `am force-stop` → launch: no second seed made, file sha256 `61468bcc…18eb` unchanged, and the live page asked over WebView debugging answered `seedStatus` `{"exists":true}` |
