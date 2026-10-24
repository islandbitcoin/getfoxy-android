# Vendored libraries

Three third-party libraries ship in `Web/` as single files. Two of them,
`cashu-ts.js` and `bip39.js`, are esbuild bundles wrapped in a global. They are
built by `tools/rebuild-vendor.sh` and a rebuild reproduces them byte for byte,
so a reviewer can check them by rebuilding. The third, `qrcode.js`, is the npm
release's own file, unmodified. React and ReactDOM ship inside `index.html`.
Each of these, and Tor, IPtProxy and libsecp256k1 (native code, in
`Vendor/secp256k1`; see `Vendor/README.md`), has a script in `tools/` that
checks it against its public release; see the next section. What follows is
what the libraries are, how that was established, and how to check it again.

| file | bytes | global | upstream |
|---|---|---|---|
| `cashu-ts.js` | 270,256 | `CashuTS` | `@cashu/cashu-ts@4.11.0` + bundled deps |
| `bip39.js` | 30,942 | `FoxyBip39` | `@scure/bip39@2.4.0` + English wordlist |
| `qrcode.js` | 56,694 | `qrcode` | `qrcode-generator@2.0.4` `dist/qrcode.js` (QR Code Generator for JavaScript, Kazuhiko Arase, MIT) |

## Checked against public releases

```bash
python3 tools/verify-vendor.py                # offline: hashes, the wordlist, the lockfile pins, the assets
python3 tools/verify-vendor.py --network      # the six scripts below, each without flags
python3 tools/verify-vendor.py --deep         # the same, with each script's rebuild-from-source flag
python3 tools/verify-vendor.py --deep --tor-rebuild --iptproxy   # and Tor and IPtProxy rebuilt
```

The modes prove different things. `--network` runs
`verify-cashu-ts.sh` and `verify-bip39.sh` without `--from-source` (the bundle
rebuilt from npm's tarballs, not from git), `verify-react.sh` without
`--rebuild` (compared with npm only) and `verify-tor.sh` without `--rebuild`
(sources and signatures only); `verify-qrcode.sh` and `verify-secp256k1.sh`
take no flags. `--deep` adds `--from-source` to the first two
and `--rebuild` to React; `qrcode.js` has nothing to rebuild. Tor's rebuild,
`--tor-rebuild`, stays separate: it takes hours and matches only with the
recorded Xcode, and `.github/workflows/tor.yml` runs it on GitHub's macOS 26
image. The weekly CI vendor job runs `--deep`.

Each script writes only to a mktemp directory, ends with `VERIFIED:` and exits 0
only if every step passes. All results below are from local runs (macOS arm64,
node 24.14.0, npm 11.9.0, Go 1.27.1), in the mode the script column names.

| what ships | what it is | script | result |
|---|---|---|---|
| `Web/cashu-ts.js` | esbuild 0.25.0 over `@cashu/cashu-ts@4.11.0`, @noble/curves, @noble/hashes, @scure/base, @scure/bip32 2.4.0 | `tools/vendor/verify-cashu-ts.sh --from-source` | **byte-identical** `34c1bdab…3d2e`; 19/19 bundled files identical to builds of their git tags (see below) |
| `Web/bip39.js` | esbuild 0.25.0 over `@scure/bip39@2.4.0` and `@noble/hashes@2.4.0` | `tools/vendor/verify-bip39.sh --from-source` | **byte-identical** `6a329523…dc92`; 9/9 bundled files identical to builds of their git tags |
| `Web/qrcode.js` | `qrcode-generator@2.0.4` `dist/qrcode.js` | `tools/vendor/verify-qrcode.sh` | **byte-identical** `79ec86f8…791c` to the npm file and to `js/dist/qrcode.js` at tag `js2.0.4` |
| React in `index.html` | `react@18.3.1` `umd/react.production.min.js` | `tools/vendor/verify-react.sh --rebuild` | **byte-identical** `d949f1c3…c4dd` to the npm file and to React's release build of its signed tag `v18.3.1` |
| ReactDOM in `index.html` | `react-dom@18.3.1` `umd/react-dom.production.min.js` | `tools/vendor/verify-react.sh --rebuild` | **byte-identical** `35f4f974…6f0d` to the npm file and to React's release build of its signed tag `v18.3.1` |
| Tor's `tor.xcframework` (`Vendor/TorPod`) | Tor 0.4.9.12, OpenSSL 3.6.4, libevent 2.1.13, xz 5.8.4, **built here** by `tools/build-tor.sh` from commit-pinned, signature-checked tags | `tools/vendor/verify-tor.sh` (`--rebuild`) | sources verified; a rebuild compared with `Vendor/Tor.sha256` |
| `IPtProxy.xcframework` | built here from pinned commits | `tools/vendor/verify-iptproxy.sh` | two builds **byte-identical** to each other and to `Vendor/IPtProxy.sha256` |
| `Vendor/secp256k1` (libsecp256k1, native) | v0.8.0, compiled into the app; `Vendor/README.md` has the pins | `tools/vendor/verify-secp256k1.sh` | the tag and commit ids, the tag's signature, and every file against the tag's tree |

### bip39: the recipe

Pinned in `tools/vendor/bip39/package.json` and `package-lock.json` (sha512):
`@scure/bip39` 2.4.0 (`sha512-82dxFbZU…H/r2nA==`), `@noble/hashes` 2.4.0
(`sha512-X5XaVWZI…aW+eA==`) and `esbuild` 0.25.0 (`sha512-BXq5mqc8…VNbuw==`).
Each equals the registry's `dist.integrity`, and the bip39 tarball is re-hashed
with openssl. The entry file names what the wallet uses:

```js
export { generateMnemonic, mnemonicToSeed, mnemonicToSeedSync, validateMnemonic } from '@scure/bip39';
export { wordlist } from '@scure/bip39/wordlists/english.js';
```

(the script writes it one name per line; that file's sha256 is `84557518…e1a`)

    esbuild bip39-entry.js --bundle --format=iife --global-name=FoxyBip39 \
      --platform=browser --target=es2020 --minify

This gives 30,942 bytes, identical to `Web/bip39.js`, on the first try: the
flags are the ones `tools/rebuild-vendor.sh` already used, and only the
lockfile was missing. The rebuilt bundle's wordlist is compared with
`@scure/bip39/wordlists/english.js` from the tarball, and a generated mnemonic
must validate. With `--from-source`, esbuild's metafile lists the nine files it
read. Seven are @noble/hashes (`utils`, `hmac`, `pbkdf2`, `_u64`, `_md`, `sha2`,
`webcrypto`), from `paulmillr/noble-hashes` tag `2.4.0`, commit `663c2aee`. Two
are @scure/bip39 (`index.js`, `wordlists/english.js`), from
`paulmillr/scure-bip39` tag `2.4.0`, commit `2c8cf46f`. Each repo was built
with `npm ci` and `npm run build`, and all nine files are byte-identical to the
npm copies. The paulmillr tags are not pinned to commits by the script. It
prints them.

### qrcode.js: which release, and that it is unmodified

The header names the library (Kazuhiko Arase, MIT). It is published on npm as
`qrcode-generator`. Across its 24 releases, `qrcode.js` (`dist/qrcode.js` from
1.5.1) is 56,694 bytes from 1.4.4 on. Only 2.0.4's copy hashes to the shipped
file, and it is identical byte for byte, so there is no modification to
document. The tarball's sha512 (`mZSiP6Rn…Sr8Z9g==`) equals the registry's.
The package's `gitHead` and tag `js2.0.4` are both commit `83b7e8fe3fdd`, and
`js/dist/qrcode.js` at that commit is the same bytes. Upstream commits that
file rather than generating it in a build, so nothing further is rebuilt. It
touches no key material.

### React and ReactDOM

`verify-react.sh` decodes manifest entries `8d4aa6b2…` and `93c76fbd…` from
`Web/index.html`. It downloads `react-18.3.1.tgz` (`sha512-wS+hAgJS…Vj+2iQ==`)
and `react-dom-18.3.1.tgz` (`sha512-5m4nQKp+…0snUIw==`), checks both against
the registry and with openssl, and compares the decoded bytes with the UMD
production files: identical.

With `--rebuild` it also builds both files from React's
source and they are identical again, so the chain no longer stops at npm:

- Node 14.17.6, the version React's `.nvmrc` names, from nodejs.org. Its
  `SHASUMS256.txt` is signed by a Node release key (Myles Borins,
  `C4F0 DFFF 4E8C 1A82 3640 9D08 E73B C641 CC11 F4C8`, from
  github.com/nodejs/release-keys; expired since, valid when it signed), and the
  tarball's hash is also pinned in the script.
- yarn 1.22.22 from npm, at its pinned sha512.
- React's tag `v18.3.1`, which must be commit `f1338f80`, with an SSH signature
  that verifies against Andrew Clark's signing key (`SHA256:kMgJy+7A…`, from
  api.github.com/users/acdlite/ssh_signing_keys). npm's package names another
  commit, `a87edf62` on `main`, where the publish ran; the built ReactDOM names
  `f1338f8080` itself.
- `yarn install --frozen-lockfile`, then React's own release script,
  `build-all-release-channels.js`, for the two UMD production bundles, compared
  from `oss-stable-semver/`. The script stamps a version from the commit's short
  hash and date, so git abbreviates to 10 characters as in React's CI clone, and
  dates are read in UTC. That stamp is why ReactDOM as published calls itself
  `18.3.1-next-f1338f8080-20240426`.

The keys are in `tools/vendor/keys/`. On this Mac it ran with Java 25 where
React's CI used Java 17; Closure Compiler's output was the same.
`.github/workflows/react.yml` repeats it on Linux, on GitHub's Ubuntu runner
with Java 17 and the Linux Node, and fails unless both files are identical
again.

### Tor: built here from signed sources

Tor used to be the prebuilt `tor.xcframework` of the Tor.framework
`v409.11.2` release: checked to be the maintainer's published binary, never
shown to be Tor's source. It is now built here by `tools/build-tor.sh`, into
`Vendor/TorPod`, which CocoaPods uses as a local pod with no download.
`Vendor/README.md` has the pinned tags, commits and signing keys, and where each
key came from.

`verify-tor.sh` checks, from public sources: every source is its pinned commit
and its tag's signature is valid and from the pinned key; Tor.framework's tag
`v409.11.2` is commit `727f628a`, whose `mmap-cache.patch`, `fix_includes.pl`
and Objective-C wrapper are the files in `Vendor/TorPod`; and with `--rebuild`,
a new build is byte for byte the one `Vendor/Tor.sha256` records.
`tools/vendor/check-tor-fresh.sh` fails when a newer release of a pinned series
ships; its first run found newer releases of Tor, OpenSSL and xz, all
security releases, and the pins moved to them (Tor 0.4.9.12, OpenSSL 3.6.4, xz
5.8.4).

### IPtProxy

`verify-iptproxy.sh` runs `tools/build-iptproxy.sh --check`, which builds twice
from the pinned commits, and also compares the result with the committed
checksums. That second comparison is the one `--check` alone does not make.
Both builds are identical and equal to `Vendor/IPtProxy.sha256`:
`0ae3a83d…5029` (ios-arm64) and `2cecc642…a7cb` (simulator).

### How the bundles are built

**`tools/rebuild-vendor.sh` builds `cashu-ts.js` and `bip39.js`, and a rebuild
reproduces them byte for byte.** The recipe and the artifact come from the same
place, and `bash tools/rebuild-vendor.sh` on any machine prints IDENTICAL for
both.

    esbuild 0.25.0
    @cashu/cashu-ts 4.11.0
    @scure/bip39 2.4.0
    --bundle --format=iife --platform=browser --target=es2020 --minify

Before adopting, the script loads both bundles and checks them: cashu-ts must
export Wallet, Mint, getDecodedToken and getEncodedToken; bip39's wordlist must
be 2048 words from abandon to zoo, and a generated mnemonic must validate
against it. It refuses to overwrite anything if either fails.

**What this does not establish.** The bundles are as trustworthy as npm and the
pinned versions. And `qrcode.js` is unchanged: a single file with no build
step, matched to `qrcode-generator@2.0.4` byte for byte (above).

### cashu-ts: verified end to end

For `cashu-ts.js` that caveat is now closed. `tools/vendor/` checks it from
public sources, independently of this repo's history. Details are in
`tools/vendor/README.md`.

    bash tools/vendor/verify-cashu-ts.sh                # npm registry + github.com, ~30 s
    bash tools/vendor/verify-cashu-ts.sh --from-source  # + rebuild every input from git
    python3 tools/verify-vendor.py --network            # offline checks, then the above

What it verifies, all passing on the last run:

- `tools/vendor/package-lock.json` pins the inputs by sha512: @cashu/cashu-ts
  4.11.0, @noble/curves 2.4.0, @noble/hashes 2.4.0, @scure/base 2.4.0,
  @scure/bip32 2.4.0 and esbuild 0.25.0. Each hash equals the npm registry's
  `dist.integrity`, and the cashu-ts tarball is re-hashed with openssl.
- The four dependencies have the same versions and hashes that cashu-ts's own
  `package-lock.json` pins at tag `v4.11.0`, commit `05f80b726f5f`.
- **esbuild 0.25.0 over those inputs outputs `Web/cashu-ts.js` byte for byte**
  (`34c1bdab…3d2e`). So the bundle contains nothing but those six packages'
  code, plus esbuild's own wrapper.
- With `--from-source`, every one of the 19 files esbuild reads (taken from its
  metafile) is byte-identical to a build of its git tag using that repo's own
  lockfile. That is cashu-ts `v4.11.0` with vite 8.0.10, and noble-curves,
  noble-hashes, scure-base and scure-bip32 `2.4.0` with tsc. The bundle
  therefore traces to public source without trusting npm publishers.

`python3 tools/verify-vendor.py` runs a subset on every check without the
network: the lockfile still pins those six inputs and hashes, and the bundle's
license footer names only @noble/@scure modules.

cashu-ts's own unit tests, run against the shipped bytes by
`tools/vendor/test-cashu-ts-bundle.sh` (measured for 4.11.0):
**2,524 tests, 2,444 pass against the bundle.** The 23 test files that import
only public API pass **631/631 with no cashu-ts source file loaded at all** —
that is the group that means something, because nothing in it can be passing
because of a source module standing in for the bundle.

Of the 80 failures, 78 are in the 29 files that mix bundle objects with
cashu-ts internals imported from `src/`, and 2 are in one file that mixes the
bundle with its own copy of noble (`test/crypto/NUT28.test.ts`): two copies of
a class meeting, not a behaviour difference. The README has the breakdown.

The 4.10.2 figures this replaced were 2,458 total and 704/704 in group A. Group
A got *smaller* while the suite grew: upstream added test files that reach into
`src/` for names that used to be read off the public API, which moves them from
A to C. The invariant is unchanged — group A fails nothing with zero source
modules loaded — and the failure count fell from 119 to 80.

Still not established: that upstream code is correct, and anything about the
esbuild binary beyond npm's integrity check.

### What 4.11.0 changed for Foxy

53 commits, about 45 of them `fix(…)`, described upstream as the v5
release-candidate fixes that apply to the v4 line with no breaking changes. No
dependency moved: @noble/curves, @noble/hashes, @scure/base and @scure/bip32 are
all still 2.4.0 with the same hashes, and the lockfile diff is three lines.

Four that matter here, none of which needed a change in Foxy:

- **Outputs are unblinded with the keyset the mint actually signed under**, not
  the one that was asked for (upstream #1083, #1096). That is melt change,
  restore, swap and mint — a fund-recovery bug on exactly the paths
  `recoverMeltChange` and `restoreLocked` exist for.
- **`OutputData.toProof()` writes the real blinding factor into the DLEQ `r`**
  instead of padding a zero (#1136). Foxy's `dleqAudit` reads that field.
- **A P2PK send is refused when the mint does not advertise NUT-11**, and
  support must be boolean `true` rather than merely truthy (#1177, #1196).
  Foxy's own `canLock` already gates on `/v1/info`, and both mints in
  `tools/live/p2pk.js` answer `{"supported":true}`; a mint that advertised the
  string `"true"` would now get an unlocked send rather than a lock the mint
  will not enforce, which is the safe direction.
- **A request times out after five minutes** where it was unbounded (#1227).
  Foxy's transport is the native bridge, which has had its own ceilings
  throughout, so this is a floor under them rather than a change.

Checked for the upgrade, all green: `tools/check-all.sh`; `p2pk-round-trip`,
`locked-send-lost`, `nut-vectors`, `nut13-vectors`, `onion-fallback`,
`tap-settle`, `inbox-payment`; and the live suites `flows`, `faults`, `p2pk`
and `onchain` against CDK 0.18.1 and Nutshell 0.21.0 with fake money.
`tools/live/units.js` was **not** run — it needs a Nutshell with a usd keyset on
ports of its own, and non-sat sending is unimplemented anyway (`TODO-LATER.md`).

SHA-256 of what ships today:

```
34c1bdabdbb6440ce8d9b18291b95ac8515ec54737d8e17a12e0d43451553d2e  cashu-ts.js
6a32952398760a90536a33a836e533273251ec3ef334de8bb2ec0c1993a4dc92  bip39.js
79ec86f82856005b1c887905cfccfcfbec3821ca61c7fd5a952faa5f778f791c  qrcode.js
```

`foxy-send-progress.js` (22,251,
`d74026502f92b62e66c7dfeb006af29f9946228704ba517ab2d9dd3abe0e4b66`) is not
third-party — it is this project's send-progress overlay.

---

## The BIP-39 wordlist, and how the versions were first identified

Before the byte-for-byte rebuilds above, the bundles were identified by
content. What still stands from that:

**bip39 — `@scure/bip39@2.4.0`, tree-shaken.** Our bundle exports four of the
seven upstream functions: `generateMnemonic`, `mnemonicToSeed`,
`mnemonicToSeedSync`, `validateMnemonic`. The three absent ones
(`entropyToMnemonic`, `mnemonicToEntropy`, `mnemonicToSeedWebcrypto`) are
unused by the wallet, which is what a tree-shaking bundler does.

**The English wordlist was checked word for word.** All 2048 words, in order,
are identical to `@scure/bip39`'s `wordlists/english.js`. This is the check
that matters most in the whole file: a wordlist with substituted or reordered
entries would produce seeds that look valid and are not.

```
sha256 of the wordlist, newline-joined:
187db04a869dd9bc7be80d21a86497d692c0db6abd3aa8cb6be5d618ff757fae
```

---

## Inside index.html

Three more scripts ship embedded in the page itself, base64 in the
`__bundler/manifest`. The renderer is the one that matters: it compiles the
template and runs the app class, so every screen of the wallet goes through it.

| asset | what | decoded | sha256 of decoded bytes |
|---|---|---|---|
| `64b433ba…` | **Foxy's renderer**, `build/foxy-render.js` — compiles the template and runs the app class (replaced the design-tool runtime) | 21,698 | checked against `build/foxy-render.js`, not pinned |
| `8d4aa6b2…` | react 18.3.1, production | 10,751 | `d949f1c3687aedadcedac85261865f29b17cd273997e7f6b2bfc53b2f9d4c4dd` |
| `93c76fbd…` | react-dom 18.3.1, production | 131,835 | `35f4f974f4b2bcd44da73963347f8952e341f83909e4498227d4e26b98f66f0d` |

**The renderer is Foxy's own code.** The page is rendered by
`build/foxy-render.js`, 526 lines written for Foxy in this repo, which
implements only what Foxy's template and app use — `<sc-if>`, `<sc-for>`,
`{{ name }}` and `{{ a.b }}` bindings, event and camel-case attributes, style
strings, `style-active`/`style-hover`, `<helmet>` styles — and refuses anything
else when the page starts. It renders through React and ReactDOM, loaded only
from the manifest blobs below. Streaming, component imports, iframe page
bundles, slide decks, atomics and the editor bridge are not in it.
`tools/pack-index.py` packs it into the old runtime's manifest slot (so the
page loader is unchanged) with a fixed gzip header, and `tools/verify-vendor.py`
checks the packed copy is exactly that file. `tests/render-parity.js` renders
every screen and popup with it and with the old runtime — kept, unshipped, in
`tests/reference/dc-runtime.js` for that purpose — and compares the DOM: 33
views, identical. One deliberate difference: the app's `componentDidUpdate` now
receives the previous state, which the old runtime never passed.

**The page policy.** The loader in `build/shell/head.html` turns the app class's
`<script type="text/x-dc">` text into a blob script that the renderer calls as
a factory; nothing compiles that text with `new Function`. The page's
`script-src` is `'self' file: blob:` plus the loader's own SHA-256 hash, which
`tools/pack-index.py` recomputes on every pack. Injected inline scripts, `on…=`
handlers, `javascript:` links and `eval` are refused, and smoke check 16 fails
the build if `'unsafe-inline'` or `'unsafe-eval'` comes back or the hash does
not match the loader. The loader's last trace of a JSX compiler — a call to
`Babel.transformScriptTags()` whenever a `window.Babel` existed — was removed;
nothing on the page loads Babel.

React and react-dom are the stock production builds: `tools/vendor/verify-react.sh`
compares the decoded entries with `react@18.3.1/umd/react.production.min.js`
and the react-dom equivalent from npm, and they are identical; with
`--rebuild`, React's release build of its signed tag makes the same bytes.

The remaining six manifest entries are two SVG icons and four WOFF2 fonts,
described in the next section: nine entries in all, and no image.
`tools/verify-vendor.py` pins all of them as files in `build/shell/assets`, and
decodes three of the nine from the page: the renderer against
`build/foxy-render.js`, and React and ReactDOM against the hashes above.
`tools/verify-shipped.sh` pins all nine as decoded from the page, each by type
and hash.

## Fonts, images and icons

None of these is covered by the repository's MIT licence, which is for the
code and the documents (`LICENSING.md`). Each keeps the licence the
table gives it, and one with no licence recorded is licensed by nothing here.

What each shipped font, icon and image is, where it came from, and under what
licence. It was written from the files themselves (each font's `name` table,
read with fontTools; each image's chunks and metadata) and from the
repository's history. Where neither establishes the origin, the table says
**origin not recorded** rather than guess.
`python3 tools/verify-vendor.py` pins every file below by size and SHA-256 on
every `check-all`, and fails on a binary in `Web/` or `build/shell/assets`
that is not on its list.

The repository's history says nothing about where any of them were made: the
`Web/` files, and the shell's assets inside `Web/index.html` (later files under
`build/shell/assets`), came in with no earlier history.

**Fonts.** Declared with `@font-face` in `build/markup.html` (the four packed
fonts, by manifest id) and packed into `Web/index.html`, or loaded from `Web/`.

| file | what it is | upstream | version | licence | SHA-256 |
|---|---|---|---|---|---|
| `build/shell/assets/font-94600041.woff2` (33,672 B) | Sora, variable (`wght` 100–800), **latin** subset: 223 code points; `@font-face` `Sora`, weights 300–800 | The Sora Project Authors, github.com/sora-xor/sora-font (name ID 0: "Copyright 2019 The Sora Project Authors"). The latin / latin-ext split and the `unicode-range` lists are Google Fonts' CSS subsets, so most likely Google Fonts; the file was not compared with Google's | 2.000 (`2.000;NONE;Sora-Regular`) | SIL Open Font License 1.1: name ID 14 is `https://scripts.sil.org/OFL`; the file holds no licence text (no name ID 13) | `d2909123a6a8ed2f928055f002c32f63ee93496b470c1a344873f955111fca53` |
| `build/shell/assets/font-ad30d34a.woff2` (15,556 B) | Sora, the same font, **latin-ext** subset: 143 code points | as above | 2.000 | SIL OFL 1.1, as above | `df4fca18912e29202b16286ab514798de8357c416b5e1f2dd31703994bec7a78` |
| `build/shell/assets/font-ccd2d25b.woff2` (20,184 B) | Figtree, variable (`wght` 300–900, default 300, named "Figtree Light"), **latin** subset: 222 code points; `@font-face` `Figtree`, weights 300–900 | The Figtree Project Authors, github.com/erikdkennedy/figtree (name ID 0: "Copyright 2022 The Figtree Project Authors"). Subset as Google Fonts serves it; not compared with Google's file | 2.002 (`2.002;NONE;Figtree-Light`) | SIL OFL 1.1: name ID 14 is `https://openfontlicense.org`; no licence text in the file | `8330490a01c60c196eae00b823de8102275aaa5862e7b76a7af21b8745338928` |
| `build/shell/assets/font-ef48d7d8.woff2` (10,228 B) | Figtree, the same font, **latin-ext** subset: 136 code points | as above | 2.002 | SIL OFL 1.1, as above | `f153aa07c1b16fbb12391c2512860c97819a0a9fd014f338b2b3f12496479d13` |
| `Web/satsymbol.woff2` (396 B) | "SatSymbol Regular": three glyphs, mapping only the space and U+20BF (₿). `@font-face` `SatSymbol` with `unicode-range: U+20BF`, first in the page's font stacks, so ₿ is drawn with its glyph | **origin not recorded**: the name table has no copyright, designer, vendor, URL or licence field | name ID 5 says "Version 2.000"; the `head` table's revision is 1.0 | **not recorded** | `eb0caa7147f0269e63d43201b3a5242ab178a21b09ee5e9c8b5fef595a48e460` |

The OFL lets a font ship inside an app if its copyright notice and the licence
go with it, in separate files or in metadata a user can read. The four packed
fonts carry the copyright notice and a link to the licence, not its text:
whether to ship the OFL's text (and where; `pack-index.py` refuses any file in
`build/shell/assets` that is not in the manifest) is not decided.

**Icons**, packed into `Web/index.html`:

| file | what it is | upstream | version | licence | SHA-256 |
|---|---|---|---|---|---|
| `build/shell/assets/icon-bolt.svg` (manifest `21177c5f`, 1,647 B) | SVG, `<title>Flash bolt</title>`, `aria-label` "Flash bolt on dark": a lightning bolt in white, viewBox 236.5×366; named only in `build/shell/head.html` (the `bolt` entry of its `__bundler/ext_resources`) | **origin not recorded.** The title names Flash, the prototype Foxy's screens came from (smoke check 9 keeps its vocabulary out); no author or licence in the file. If the drawing is that product's mark, whether Foxy may ship it is not settled | — | **not recorded** | `9c84c033a7279c6688c675d114c600873d8c59936674b0e3813e77b87fdd9fde` |
| `build/shell/assets/icon-0e7a9336.svg` (1,639 B) | the same drawing in near-black (`#050505`), also titled "Flash bolt"; in the QR screens' and the send spinner's centres (`build/markup.html`) | **origin not recorded**, as above | — | **not recorded** | `6c38e984fed9b05103a0319727fbdb39584d78d7f92456ee9fc2af07c84b4d98` |

**Images and video in `Web/`.** Artwork the project ships. Sizes are the files'
own, dimensions are as `sips` reports them, and the video's are as AVFoundation
reports them.

| file | what it is | upstream | licence | SHA-256 |
|---|---|---|---|---|
| `Web/snow-fur.jpg` (67,431 B) | JPEG, 640×358: a photograph of white fur, blended twice into the RECEIVE and SEND buttons — once for the hair, once for the depth | **origin not recorded**; scaled and re-encoded with `sips` from a larger picture | **not recorded** | `cebafa00a3787400f265c5b427f4219a24dd8edbcfff7ab40c11fd81a4e7b22d` |
| `Web/risk-grain.jpg` (131,515 B) | JPEG, 960×538: a fur texture, by eye the same picture as `snow-fur.jpg` at a larger size, blended (soft light) over the first-launch risk warning (`Web/foxy-tor-gate.js`) | **origin not recorded**; re-encoded with `sips` | **not recorded** | `3798d85f020e99b2d5b8306324d7636d0fcf51548fcada742ccb0b92bc96eab5` |
| `Web/foxy-intro.mp4` (976,087 B) | H.264, 600×1304, 231 frames at 24fps (9.6 s): a white fox on black, asleep, stretching, then standing. The launch intro, played once on the first launch of a day (`Web/foxy-tor-gate.js`) | **origin not recorded**; `tools/make-intro.swift` scales and re-encodes a source video, and touches no colour | **not recorded** | `2c3ef790193cc6dd179f2fed3c4707fe140d2d863208ec1bc353eb3439e6025c` |
| `Web/foxy-intro-last.png` (71,955 B) | PNG, 600×1304: the intro's last frame, the white fox standing on black. The page freezes on it while FOXY fades in, and shows it instead of the video where motion is turned down | made by `tools/make-intro.swift` from the same drawing | **not recorded** | `137a597ceaad06f1e22817d037b390973e38e03b5869b08f625d8949cedcd725` |
| `Web/foxy-splash-still.png` (61,988 B) | PNG, 600×1304: the intro's first frame, the white fox asleep on black. It is the intro layer's own background (`Web/foxy-tor-gate.js`), so the first frame of the film is this still | **origin not recorded** | **not recorded** | `d21b711786d81aa20cb79b3aa823178230eb66b71fceb7478da12c6af6626144` |
| `Web/foxy-rider.webp` (1,413,094 B) | lossy WebP with alpha, 2964×1392: a sprite sheet of all 151 frames of a drawing of a red convertible carrying two cartoon characters in sunglasses, thirteen across and twelve down, each frame 228×116. Rides the price line on the home screen (`build/app/18-lifecycle-and-chart.js`), which plays the frames forward at 25 fps (about six seconds) and loops, and does the leaning and bouncing itself | **origin not recorded**: a 151-frame animated drawing that does not loop on its own, laid out as one sheet. No author or licence is recorded inside the file | **not recorded** | `794795b5a020057774e85d1eb80c8ba25944b609beeae3eb5da0f6e540f0c2ec` |

Each of the following has a version column, which is empty where nothing says.

| file | what it is | upstream | version | licence | SHA-256 |
|---|---|---|---|---|---|
| `Web/foxy-sheet.webp` (1,751,366 B) | lossy WebP, 3360×3120: the send animation's sprite sheet (`Web/foxy-send-progress.js`): 174 frames of a cartoon red fox running on grass, fourteen across and thirteen down, each frame 240×240 | **origin not recorded** (C2PA below) | — | **not recorded** | `fb38cb859810c60efda24f7f1e4ce3c49b321fcd85dc13fcd3cafd2d1b4c10a9` |
| `Web/foxy-fur.webp` (65,776 B) | lossy WebP, 800×532: an orange fox-fur texture behind the orange buttons (`build/markup.html`) | **origin not recorded** (C2PA below) | — | **not recorded** | `b9a1c1e11785c5a45a58e6098418e16ee01afaefadc1b3e109ecf3a6e26d4684` |
| `Web/gd-cashapp.jpg` (7,489 B) | JPEG, 256×256: the Cash App app icon on the GET DOLLARS screen (`build/app/26e-loaders.js`) | Apple's App Store image server (`is1-ssl.mzstatic.com`), the icon of `com.squareup.cash` | — | **the trademark and artwork of Block, Inc.**; used to name their app, with no licence from them | `5a5a80358e280999bdb2bf3337c806669a9c04198756024efa819cac38a56b56` |
| `Web/gd-strike.jpg` (7,889 B) | JPEG, 256×256: the Strike app icon on the GET DOLLARS screen (`build/app/26e-loaders.js`) | Apple's App Store image server (`is1-ssl.mzstatic.com`), the icon of `com.jackmallers.Strike` | — | **the trademark and artwork of Zap Solutions, Inc**; used to name their app, with no licence from them | `b74180a13af2ab7c9d010a7390c25f90ad7579bb8945353582b402f012a668b0` |
| `Web/gd-river.jpg` (11,207 B) | JPEG, 256×256: the River app icon on the GET DOLLARS screen (`build/app/26e-loaders.js`) | Apple's App Store image server (`is1-ssl.mzstatic.com`), the icon of `com.river.riverapp` | — | **the trademark and artwork of River Financial Inc.**; used to name their app, with no licence from them | `abb80efc41313717d7136e66cbbda99255d09f71bf8f01dbc88c7401937bedee` |
| `Web/gd-flash.jpg` (8,530 B) | JPEG, 256×256: the Flash app icon on the GET DOLLARS screen (`build/app/26e-loaders.js`) | Apple's App Store image server (`is1-ssl.mzstatic.com`), the icon of `com.lnflash` | — | **the trademark and artwork of Island Bitcoin LLC**; used to name their app, with no licence from them | `e07ffc030a1dc9cebbadc668c9ad3e89bf6c88d358deb563f9ed9e0b67db7cfb` |

**Metadata inside the images.** Only `foxy-fur.webp` and `foxy-sheet.webp`
carry a C2PA manifest, a WebP `C2PA` chunk of 5,759 bytes with the same content
in both: claim generator "Anthropic Files" 1.0.0 (C2PA 2.4.0), one action,
`c2pa.opened`, by software agent "Claude", and an Anthropic origin-confidence
assertion of "unknown", whose description says Claude provided the file at a
user's request and may have created or modified it. That records that the
files passed through Claude's file handling; it does not say who made the
pictures, from what, or under what terms. The other images carry no C2PA
manifest. None of the images has XMP or an ICC profile. The two PNGs have an
`sRGB` chunk and a 56-byte `eXIf` chunk holding the pixel dimensions.
`snow-fur.jpg` and the four `gd-*.jpg` icons have JFIF, a small EXIF block
(resolution and dimensions, and in the icons a short comment string) and a
Photoshop `APP13` block. `risk-grain.jpg` has JFIF only, and `foxy-rider.webp`
has none.

**Used only by tests**, not shipped, pinned the same way:

| file | what it is | upstream | version | licence | SHA-256 |
|---|---|---|---|---|---|
| `tools/acorn/` (8 files) | acorn, the JavaScript parser `tools/check-undefined.js` (smoke check 2b) reads the app class with | npm `acorn`, github.com/acornjs/acorn (its `package.json`); not compared with npm's tarball here | 8.18.0 | MIT (`tools/acorn/LICENSE`, "Copyright (C) 2012-2022 by various contributors") | per file in `tools/verify-vendor.py`; `dist/acorn.js` `fc3ed7b81e58464715d0291402892f22c3d86ea75302645a330390f85d8015c9` |
| `tests/reference/dc-runtime.js` (68,278 B) | the design-tool runtime the page used before `build/foxy-render.js`, kept for `tests/render-parity.js`; modified here as "The design-tool runtime" below says. Its header says it is generated from `dc-runtime/src/*.ts` | the design tool's generated runtime, which is published nowhere this project can point to; **publisher and licence not recorded** | none in the file | **not recorded** | `7d5ae886f08efc2dc5da41b682607a60b180710bcf61e594ce4dfcd67c76355d` |

No test uses a font file of its own: the only font files in the repository are
the five above.

### The design-tool runtime (superseded)

*(History. This is the runtime `build/foxy-render.js` replaced. It no longer
ships; the copy in `tests/reference/dc-runtime.js` exists only for the render
parity test. The two paragraphs below describe it as it was, and their present
tense is the tense they were written in.)*

**The runtime no longer matches its build output.** Two remote-code paths were
disabled in the embedded copy: `ensureBabel()`, which fetched a JavaScript
compiler from unpkg.com, and the `x-import` loader's `fetch(url)` fallback,
whose result was transpiled and passed to `new Function`. Both now reject.
Nothing in this app reaches either — the only JSX import was removed — but a
dormant remote-code loader is not something a wallet should ship. The script that
made those edits is not in the repository. A third edit changed `evalDcLogic`: it calls a factory the page loader builds from the app
script instead of compiling that text with `new Function`. A fourth edit
removed the dormant remote-code machinery outright rather than
leaving it blocked: the `x-import` evaluator (read module, transpile, run with
`new Function`) and `ensureBabel` are gone, and any `x-import` is refused with
"remote code loading is disabled" and renders its error placeholder; `boot()`
no longer re-reads the page with `fetch(location.href)`; a sibling component
is read only from an embedded blob, never fetched; and `loadReactUmd` loads
React and ReactDOM only from the manifest blobs the loader maps, rejecting with
a clear error instead of falling back to unpkg.com. The two unpkg URLs remain
only as the lookup keys `__bundler/ext_resources` uses to name those blobs. The
page loader in `build/shell/head.html` also lost its `text/babel`/`text/jsx` branch,
which read such scripts' `src` (via `fetch`) and inlined them. This app uses none
of these paths. The hash above is of the modified copy (68,278 bytes; was
69,966, `d3f54213…00b5`).

**The runtime is the trust root of the web layer, and it is unaudited.** Its
header reads `GENERATED from dc-runtime/src/*.ts — do not edit`, it carries no
version, and it is not published anywhere this project can point to. It reads
the app class out of a `<script type="text/x-dc">` tag. It used to evaluate
that text with `new Function`, which forced the page policy to allow
`'unsafe-eval'` and `'unsafe-inline'`. Now the loader in `build/shell/head.html`
turns the text into a blob script, and the page's `script-src` is
`'self' file: blob:` plus the loader's own SHA-256 hash, which
`tools/pack-index.py` recomputes on every pack. Injected inline scripts,
`on…=` handlers, `javascript:` links and `eval` are refused, and smoke check 16
fails the build if either keyword comes back.

---

## Re-checking

```bash
python3 tools/verify-vendor.py
```

Confirms the sizes and hashes above, that each bundle still declares its
expected global, and that the BIP-39 wordlist is unchanged. It does not need
the network.
