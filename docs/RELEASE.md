# RELEASE.md — Foxy for Android on Google Play internal testing

Foxy Android 0.1.0 goes to **Google Play internal testing** under the Island
Bitcoin developer account (ENG-680). The package (`applicationId`) is
`io.islandbitcoin.getfoxy`: the Play Console app is bound to that name, so a
bundle with any other package is refused. The Kotlin `namespace` and the source
packages stay `io.islandbitcoin.foxy` (so `BuildConfig`, `R` and every class
name, such as `io.islandbitcoin.foxy.MainActivity`, keep that name), and iOS
keeps its bundle id `io.islandbitcoin.foxy`. `ReleaseBuildTest` pins the
applicationId.
The GitHub release is the tag `v0.1.0` and its notes only: no APK or AAB is
attached anywhere public.

| | debug | release (Play) |
|---|---|---|
| versionCode / versionName | 1 / 0.1.0 | 1 / 0.1.0 |
| minSdk | 28 (Android 9) | **30 (Android 11)** until ENG-654 proves the API 28-29 screen-lock confirmation on a phone |
| targetSdk | 36 | 36 |
| signed by | the debug key | the **upload key** (Play App Signing re-signs it for the store) |
| R8 / minify | off | **off** (below) |
| debuggable, WebView debugging, foxy:// link, simulator, logcat | yes | **none** (below) |

`ReleaseBuildTest` (JVM, runs with `testDebugUnitTest`) reads the release
build's own merged manifest and compiled classes and fails if any of the rows
above is not so.

## The package on a phone

Debug and release builds both install as `io.islandbitcoin.getfoxy` (the test
APK as `io.islandbitcoin.getfoxy.test`). Builds from before ENG-680 installed
as `io.islandbitcoin.foxy`; Android treats that as a different app, so a phone
that has one keeps it alongside the new one, each with its own wallet. Restore
the wallet from its words in the new app, check the balance, then uninstall
the old one (`adb uninstall io.islandbitcoin.foxy`). Uninstalling first loses
nothing only if the words are written down.

## 1. The upload key (once, by a person)

Play App Signing holds the key the store signs Foxy with. What this repository
builds is signed with an **upload key** only, which Play can reset if it is
lost. Nothing of it is ever in the repository: not the keystore, not the alias,
not a password. The build reads them from the environment at build time.

Make it outside the repository, on the release machine:

```sh
mkdir -p ~/keys && chmod 700 ~/keys
keytool -genkeypair -keystore ~/keys/foxy-android-upload.p12 -storetype PKCS12 \
  -alias foxy-upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Island Bitcoin, O=Island Bitcoin"
# keytool asks for the password; PKCS12 uses one password for the store and the key.
# Keep the password in the macOS Keychain, not in a file or the shell history:
security add-generic-password -s foxy-android-upload -a "$USER" -w
# (-w with no value asks for it.) Back up the .p12 and the password together, offline.
```

Then, for each release build:

```sh
export FOXY_UPLOAD_STORE_FILE=~/keys/foxy-android-upload.p12
export FOXY_UPLOAD_KEY_ALIAS=foxy-upload
# passwords: FOXY_UPLOAD_STORE_PASSWORD / FOXY_UPLOAD_KEY_PASSWORD, or left unset
# to be read from the Keychain item foxy-android-upload
./gradlew verifyReleaseSigning   # fails clearly if anything is missing or wrong; prints the certificate's SHA-256
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab
```

How the build reads them (`app/build.gradle.kts`):

- `FOXY_UPLOAD_STORE_FILE` and `FOXY_UPLOAD_KEY_ALIAS` from the environment
  only. Without both, `bundleRelease` and `assembleRelease` still build, **unsigned**,
  and end with `WARNING: UNSIGNED …` (that is what CI builds), and
  `verifyReleaseSigning` fails, naming what to set.
- The passwords from `FOXY_UPLOAD_STORE_PASSWORD` and `FOXY_UPLOAD_KEY_PASSWORD`,
  or, for either that is unset, from `security find-generic-password -s
  foxy-android-upload -w`. They are read only when a release is signed (a build
  that names `bundleRelease`, `assembleRelease`, `packageRelease`,
  `signRelease…`, `installRelease`, or `assemble`/`bundle`/`build`) or by
  `verifyReleaseSigning`. A debug build or the unit tests never read a
  password, nor ask the Keychain.
- `verifyReleaseSigning` opens the keystore with the store password, the
  alias's private key with the key password, and prints the alias and the
  certificate's SHA-256 (public: Play Console shows the same). It never prints
  a password.

`tools/release-signing.test.sh` checks all of this with a throwaway keystore it
makes in a temporary folder and a stub in place of the Keychain tool
(`FOXY_SECURITY_TOOL`), and signs and verifies a bundle with it. It never reads
the real Keychain, and it deletes the bundles it built.

## 2. R8 is off for 0.1.0

`isMinifyEnabled = false` in the release build type. R8 renames and removes
code it cannot see used, and Foxy has several things it cannot see: the
WebView bridge is reached from the page by name, secp256k1-kmp loads its JNI
library and calls into it by reflection, Tink loads its key managers by
reflection, and Tor, IPtProxy and zxing-cpp are native libraries with Java
entry points. `proguard-rules.pro` keeps secp256k1 only. No minified build has
been through the device suite (`docs/DEVICE-TESTS.md`), and a wallet that
breaks only in its store build is the worst place to find out.

What it costs: R8 also stripped every `android.util.Log` call, in Foxy's code
and in its dependencies' (SECURITY-INVARIANTS.md S18). Foxy's own code needs no
stripping: its one log call, `debug/Log`, writes to the field log only in a
release (the release `DebugConsole` sets no logcat sink), and
`ReleaseBuildTest.foxysReleaseCodeNeverCallsLogcat` fails if any Foxy class in
the release build calls `android.util.Log`. A dependency's own logcat lines
(CameraX, OkHttp, the Tor libraries) are not stripped in 0.1.0. Tor itself logs
errors only, to a stream nothing reads (`TorConfig`, `Log err stderr`).

Turning R8 on is its own change: shrink with the keep rules each of the above
needs, run the whole device suite on the minified build, then check S18 by
hand (`adb logcat --pid` through a payment shows nothing).

## 3. What a release build does not carry

Every debug-only path, and how the release build leaves it out:

| Debug-only path | Where | In a release |
|---|---|---|
| the card simulator's loopback link `SimCardLink` | `app/src/debug` | not compiled; `CardSim.ENABLED = false`, `CardSim.link` returns null |
| logcat (`DebugConsole` sets `Log.console`) | `app/src/debug` | the release `DebugConsole.install` does nothing |
| the `foxy://` deep link | `app/src/debug/AndroidManifest.xml`; `MainActivity.receiveDeepLink`, `WebHost.deepLink` | no filter in the merged manifest (`ManifestTest`); both methods compile to `return` |
| WebView debugging | `WebHost.configure`: `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` | called with the constant `false` |
| `window.FOXY_DEBUG`, the `FOXY_SHOW_WARNING` extra | `WebHost.create` | not loaded; the release console silence is added instead |
| the `FoxyNoTor` and `FoxyForceTransport` extras | `MainActivity.onCreate` | not read |
| the `FoxyAllowCapture` extra (FLAG_SECURE off the page's window, for the store screenshots) | `MainActivity.onCreate` | not read; FLAG_SECURE is set unconditionally (`ReleaseBuildTest.aReleaseCannotLiftFlagSecure`) |
| the `FoxyCardSim` extra | `WebHost.cardLink` (through `CardSim.ENABLED`) | not read |
| the seed thread's self-check, the bridge's `isDebug` | `FoxyServices`, `FoxyBridge` | `BuildConfig.DEBUG` is `false`: off, and `isDebug` answers `no` |
| the tests' hooks (`droppedScript`, `afterRename`, `afterWriteBeforeDelete`, `afterSeal`, `Log.console`) | `internal var … = null` in main | never set by any release class |
| `android:debuggable` | AGP | not set |

`BuildConfig.DEBUG` is a compile-time constant, so the Kotlin compiler removes
each gated branch from the release classes; `ReleaseBuildTest` reads those
classes with `javap` and checks each row.

## 4. Play Console (by a person)

1. **Create the app.** Play Console (Island Bitcoin account) → *Create app*:
   name *Foxy*, default language English (United States), *App*, *Free*,
   accept the declarations. The package name is fixed by the first upload:
   `io.islandbitcoin.getfoxy` (the app already exists under that name, ENG-680).
2. **Play App Signing.** *Test and release → Setup → App signing*: let Google
   generate and hold the app signing key (the default). The first AAB uploaded
   registers its signer as the **upload key**: upload only a bundle signed by
   the key in §1. Note both certificates' SHA-256 from this page. The App Link
   (`docs/DEVICE-TESTS.md`, "Follow-up: the App Link") needs the **app signing**
   certificate's SHA-256 in `assetlinks.json`, not the upload key's.
3. **Internal testing.** *Test and release → Testing → Internal testing →
   Create new release*: upload `app/build/outputs/bundle/release/app-release.aab`,
   release name `0.1.0 (1)`, release notes from the GitHub release. Every
   upload needs a new versionCode: bump it in `app/build.gradle.kts` for the
   next one.
4. **Testers.** *Internal testing → Testers*: create an email list (up to 100
   Google accounts), save, and send the testers the opt-in link shown there.
   A tester opens the link, accepts, then installs from Play.
5. **App content** (*Policy and programs → App content*), before the first
   release can roll out: the privacy policy (§6), app access (*all
   functionality is available without special access*: there is no account),
   ads (*no ads*), content rating questionnaire, target audience (18 and over:
   it moves money), Data safety (§5), financial features (§7).

## 5. Data safety answers

Grounded in the code at this commit (`docs/ROUTE.md` §3 lists every host the
app contacts; `tools/check-no-network.sh` holds every request to `network/`;
`ManifestTest` holds the permissions).

**What the code does.** There is no server of Foxy's or Island Bitcoin's,
no account, no analytics, no crash reporter, no ads, no push service, no
Firebase and no Google Play services library. The app sends nothing to the
developer. What it sends goes to services the person uses through it, over the
app's own Tor:

- the Cashu **mints** the wallet uses (the defaults, or ones the person adds after
  an approval dialog): ecash requests, Lightning invoices to pay or to be paid;
- **Lightning address / LNURL** servers, when the person pays one (after an
  approval dialog);
- **price and chart** hosts (a mempool onion, Kraken, Bitstamp,
  blockchain.info, Gemini, Coinbase): public tickers, nothing of the person's;
- **block explorers** (mempool and blockstream onions, then mempool.space,
  blockstream.info): the newest block header, for the Foxy card's clock;
- **Nostr relays** and another phone's **.onion**, when the person pays or is
  paid by a payment request: encrypted to the other party (NIP-44/NIP-59, or
  inside Tor's onion service);
- **Tor** relays, the obfs4 bridges and Snowflake, which carry the above.

Every one of these goes through Tor, except: the Tor network itself and its
transports; requests the person sends after choosing *Continue without Tor*
in a native dialog (this run of the app only; mints, prices and the rest over
HTTPS, never onions or Nostr); and the four *GET DOLLARS* links, which open in
the system browser, outside Foxy.

On the phone only: the seed and wallet (Keystore-sealed, excluded from backups),
camera frames (decoded on the phone for QR codes, never stored or sent),
Bluetooth (tap to pay between two phones, no location permission, BLUETOOTH_SCAN
`neverForLocation`), NFC (the Foxy card), the biometric or screen lock
(Android's own prompt), local notifications.

**The answers to give** (the ticket's position; read the note):

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Yes (Tor, and HTTPS outside it) |
| Do you provide a way for users to request that their data is deleted? | Not applicable: no data is held by the developer. Uninstalling removes everything on the phone. |

**Note for whoever submits (a judgement call, not settled by the code).** Google
counts data the app transmits off the device as *collected* even when it goes
to a third party rather than the developer, with exemptions for transfers the
user initiates and expects. Payment requests and invoices sent to a mint or
an LNURL server the person chose, to make the payment the person asked for,
are the closest thing Foxy has to collected data. If Play's review or the
submitter reads them as collected, the matching declaration is *Financial
info → Other financial info*: collected, not shared, processed ephemerally,
required, for *App functionality*, not linked to an identity (there is none).

## 6. Privacy policy

Play requires a privacy-policy URL (Foxy asks for the camera, and it is a
financial app). It must be a public web page, not a PDF, and say what §5 says.

    Privacy policy URL: https://github.com/islandbitcoin/getfoxy-android/blob/main/docs/PRIVACY.md

The policy is `docs/PRIVACY.md`; it is public once this repository's `main`
carries it. The store listing's text, graphics, screenshots and the App content
answers are in `docs/store/` (its README says what goes where).

## 7. The crypto wallet (financial features) declaration

Play asks every app to declare its financial features (*App content →
Financial features*), and has a separate policy for cryptocurrency exchanges
and software wallets. The facts to declare from:

- Foxy is a **software wallet** for bitcoin over Lightning and Cashu ecash. It
  is not an exchange: there is no trading, no fiat on-ramp or off-ramp, no
  order book in the app. The *GET DOLLARS* links open Cash App, Strike, River
  and Flash in the system browser; the app has no relationship in code with
  them.
- **Custody:** the seed is generated and kept on the phone and never leaves it.
  Island Bitcoin holds no funds and no keys. Cashu ecash is a bearer claim on
  the **mint** that issued it: the mint (a third party the person chooses)
  holds the bitcoin behind it. Say so plainly in the listing.
- No loans, no investment products, no payments to the developer, no in-app
  purchases.

Which declaration options apply, and whether any country listing needs a
registration for a software wallet, is for the account owner to decide on
Play's current form; this repository cannot settle it.

## 8. Release checklist

Before the upload:

- [ ] `main` is green in CI, including the unsigned `bundleRelease`.
- [ ] ENG-654 still open? Then the release minSdk stays 30.
- [ ] The upload key exists outside the repository and is backed up (§1);
      `./gradlew verifyReleaseSigning` passes on the release machine.
- [ ] `./gradlew bundleRelease` from a clean checkout of the commit to be
      tagged, with the upload key set: no `UNSIGNED` warning.
- [ ] Device smoke of the release build on the Pixel (the ticket's last item;
      needs a phone): make the APK set from the AAB with bundletool
      (`bundletool build-apks --bundle app-release.aab --output foxy.apks --ks …
      --ks-key-alias …`, then `bundletool install-apks --apks foxy.apks`),
      then: Tor comes up, receive a small amount, pay a small invoice (the money
      rules of `docs/DEVICE-TESTS.md` "Real money"), and `adb logcat --pid` shows
      no line from Foxy. Record it in `docs/DEVICE-TESTS.md`.
- [ ] The privacy policy is published and its URL set in Play Console (§6).
- [ ] Data safety (§5), financial features (§7) and the rest of *App content*
      are filled in.

The upload:

- [ ] Play Console steps 1-4 (§4).
- [ ] Testers added and the opt-in link sent.

The tag (by the human or the architect, not by an agent):

- [ ] Tag the exact commit the AAB was built from: `git tag -s v0.1.0 <commit>`
      and `git push origin v0.1.0`.
- [ ] `gh release create v0.1.0 --title "Foxy Android 0.1.0" --notes-file <notes>`:
      notes only, **no APK or AAB attached**.
- [ ] Bump `versionCode` (and `versionName` as needed) on `main` for the next
      upload.
