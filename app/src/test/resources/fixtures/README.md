# Test fixtures from the iOS app

Copied unchanged from getfoxy/iOS at `5581c963dd29b28ebec547473ada532873d49a77`
(the commit in `core/PIN`), `tests/fixtures/`:

| File | What | Made by (in the iOS repo) |
|---|---|---|
| `nut13-vectors.json` | the NUT-13 spec's vectors (cashubtc/nuts tests/13-tests.md) | by hand from the spec |
| `nut13-cross.json` | the bundled cashu-ts's own secrets and blinding factors for random mnemonics, both keyset versions, counters at the edges | `tools/gen-nut13-cross.js` |

`NUT13Test` checks the Kotlin derivation against both, as `FoxyTests/NUT13Tests.swift` does.
