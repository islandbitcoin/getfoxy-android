# Test fixtures from the iOS app

Copied unchanged from getfoxy/iOS at `5581c963dd29b28ebec547473ada532873d49a77`,
`tests/fixtures/`; the same bytes at `8df0bb19992cceda44d04e947bce211e1415975e`,
the commit in `core/PIN`:

| File | What | Made by (in the iOS repo) |
|---|---|---|
| `nut13-vectors.json` | the NUT-13 spec's vectors (cashubtc/nuts tests/13-tests.md) | by hand from the spec |
| `nut13-cross.json` | the bundled cashu-ts's own secrets and blinding factors for random mnemonics, both keyset versions, counters at the edges | `tools/gen-nut13-cross.js` |
| `card-owner-vectors.json` (sha256 `26f196d4…7a0e99fd`, copied at `8df0bb19992cceda44d04e947bce211e1415975e`) | a Foxy card's owner key for three seeds and cards (scalar, public key), a signature by it for each of the six labels, and the reduction mod P-256's n at its edges, all worked out with Node's own crypto | `tools/gen-card-owner-vectors.js` |
| `animated-qr.json` (sha256 `2e281d15…fe752c9`) | animated QR frames (UR `ur:bytes`, fountain-coded) for six tokens, the fragments 289 mixed frames hold, and two CRC-32s, all made by `@gandlaf21/bc-ur` 1.1.12, the library cashu.me draws its animated codes with | `tests/fixtures/animated-qr-generate.js` |
| `nip44.vectors.json` (sha256 `269ed0f6…3ce25040`, copied at `8df0bb19992cceda44d04e947bce211e1415975e`) | the NIP-44 reference vectors (paulmillr/nip44 `nip44.vectors.json`, the file and hash NIP-44 itself names): conversation keys, message keys, padding, encrypt/decrypt and the long messages, valid and invalid | paulmillr/nip44's own file, unchanged (its sha256 is the one NIP-44 names) |

One file from the card's own repository, getfoxy/card at
`88840aaea8b2c55ac1b6588d82818dfd2326008e`, `spec/vectors/owner.json`, copied
unchanged as `card-owner-proofs.json` (sha256 `bfbac2d9…9db099fc`): an owner
key and nine proofs the card's applet accepted (written by
`applet/src/test/java/me/flashapp/cashu/CashuAppletTest.java`
`testWriteOwnerVectors`), one for each label the card verifies, `lock` and
`tag` among them.

`NUT13Test` checks the Kotlin derivation against both NUT-13 files, as
`FoxyTests/NUT13Tests.swift` does. `AnimatedQRReaderTest` checks the Kotlin
reader against `animated-qr.json`, as `FoxyTests/AnimatedQRTests.swift` does,
and the instrumented `QrDecodeTest` draws its frames as QR codes and reads them
back through zxing-cpp, the decoder the camera uses.

`NostrCryptoTest` replays `nip44.vectors.json` byte for byte, as
`FoxyTests/NostrCryptoTests.swift` does, and its three long messages too, and
checks the file's sha256 is the one NIP-44 names.

`CardOwnerTest` and `SeedActionsTest` check the owner key and its
signatures against `card-owner-vectors.json`, as `FoxyTests/NUT13Tests.swift`
and `FoxyTests/SeedActionsTests.swift` do, and against `card-owner-proofs.json`:
the message the phone signs is the one the card verified, and `lock` and `tag`
are labels the phone refuses.

One file made from the iOS code itself rather than copied:
`tap-vectors.json`, written by `tools/tap-vectors/generate.sh` from
getfoxy/iOS's `Foxy/Bluetooth/TapCrypto.swift`, `TapSession.swift` and
`TapProtocol.swift` at the commit in `core/PIN` (read with `git show`, compiled
with `swiftc` against CryptoKit on macOS). `FoxyTests/TapCryptoTests.swift` and
`TapSessionTests.swift` run on random keys and carry no fixed bytes, so the
generator seeds the session's private key and nonce (one mechanical change to
`TapSession.swift`, made and checked by the script) and writes down what the
iOS code does: the derived doors, X25519 (RFC 7748's vector, and the
small-order points CryptoKit refuses), the commitment, the transcript at
versions 2 to 4, the keys and the code, sealing, padding, the first version's
code, and five whole conversations with every frame each side wrote and every
answer each side gave (above 8 KB a frame is pinned by its length and
SHA-256). `TapVectorsTest` replays all of it byte for byte;
`tools/tap-vectors/generate.sh <ios-clone> --check` says whether the file is
still what the iOS code writes.
