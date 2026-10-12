# Test fixtures from the iOS app

Copied unchanged from getfoxy/iOS at `5581c963dd29b28ebec547473ada532873d49a77`
(the commit in `core/PIN`), `tests/fixtures/`:

| File | What | Made by (in the iOS repo) |
|---|---|---|
| `nut13-vectors.json` | the NUT-13 spec's vectors (cashubtc/nuts tests/13-tests.md) | by hand from the spec |
| `nut13-cross.json` | the bundled cashu-ts's own secrets and blinding factors for random mnemonics, both keyset versions, counters at the edges | `tools/gen-nut13-cross.js` |
| `animated-qr.json` (sha256 `2e281d15…fe752c9`) | animated QR frames (UR `ur:bytes`, fountain-coded) for six tokens, the fragments 289 mixed frames hold, and two CRC-32s, all made by `@gandlaf21/bc-ur` 1.1.12, the library cashu.me draws its animated codes with | `tests/fixtures/animated-qr-generate.js` |

`NUT13Test` checks the Kotlin derivation against both NUT-13 files, as
`FoxyTests/NUT13Tests.swift` does. `AnimatedQRReaderTest` checks the Kotlin
reader against `animated-qr.json`, as `FoxyTests/AnimatedQRTests.swift` does,
and the instrumented `QrDecodeTest` draws its frames as QR codes and reads them
back through zxing-cpp, the decoder the camera uses.
