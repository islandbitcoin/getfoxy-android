# placeholder

<!-- contract-table:begin -->
| name | direction | kind | phase | payload | reply | iOS |
|---|---|---|---|---|---|---|
| `covered` | page → native | action | P1 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:362 |
| `log` | page → native | action | P1 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:359 |
| `fieldLog` | page → native | action | P1 | `bad`: boolean | json-array | Foxy/Bridge/FoxyBridge.swift:360 |
| `fieldLogClear` | page → native | action | P1 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:361 |
| `isDebug` | page → native | action | P1 | — | "yes" / "no" | Foxy/Bridge/FoxyBridge.swift:400 |
| `awake` | page → native | action | P1 | `on`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:443 |
| `captured` | page → native | action | P3 | — | "yes" / "no" | Foxy/Bridge/FoxyBridge.swift:395 |
| `privacy` | page → native | action | P2 | — | json {tor, progress, everUp, unprotected, transport, orbot, firstSetup, vpn, orbotKey, network} | Foxy/Bridge/FoxyBridge.swift:394 |
| `torRetry` | page → native | action | P2 | — | json {tor, progress, everUp, unprotected, transport, orbot, firstSetup, vpn, orbotKey, network} | Foxy/Bridge/FoxyBridge.swift:396 |
| `unprotected` | page → native | action | P2 | `on`: boolean | json {tor, progress, everUp, unprotected, transport, orbot, firstSetup, vpn, orbotKey, network} | Foxy/Bridge/FoxyBridge.swift:397 |
| `orbotRefresh` | page → native | action | P2 | — | "none" / "needs-access" / "bypass" | Foxy/Bridge/FoxyBridge.swift:393 |
| `orbotAccess` | page → native | action | P2 | — | "opened" / "no" | Foxy/Bridge/FoxyBridge.swift:392 |
| `mintRequest` | page → native | action | P2 | `url`: string, `method`: string, `headers`: object\|null, `circuit`: string, `body`: string | status-body | Foxy/Bridge/FoxyBridge.swift:398 |
| `price` | page → native | action | P2 | `agree`: boolean? | text | Foxy/Bridge/FoxyBridge.swift:363 |
| `candles` | page → native | action | P2 | `g`: integer, `span`: integer | json-array | Foxy/Bridge/FoxyBridge.swift:364 |
| `hostsKnown` | page → native | action | P2 | `hosts`: string[] | "ok" | Foxy/Bridge/FoxyBridge.swift:399 |
| `seedStatus` | page → native | action | P1 | `quiet`: boolean? | json {exists, passcode} | Foxy/Bridge/FoxyBridge.swift:371 |
| `seedCreate` | page → native | action | P1 | — | json {created} | Foxy/Bridge/FoxyBridge.swift:372 |
| `seedMigrate` | page → native | action | P1 | `words`: string\|string[] | json {migrated, same, different} | Foxy/Bridge/FoxyBridge.swift:373 |
| `countersImport` | page → native | action | P1 | `counters`: object | json {counters} | Foxy/Bridge/FoxyBridge.swift:374 |
| `counterReserve` | page → native | action | P1 | `keysetId`: string, `count`: integer | json {keysetId, start, secrets, blindingFactors} | Foxy/Bridge/FoxyBridge.swift:375 |
| `counterReserveAt` | page → native | action | P1 | `keysetId`: string, `start`: integer, `count`: integer | json {keysetId, start, secrets, blindingFactors} | Foxy/Bridge/FoxyBridge.swift:376 |
| `counterAdvance` | page → native | action | P1 | `keysetId`: string, `next`: integer | json {keysetId, next} | Foxy/Bridge/FoxyBridge.swift:377 |
| `counterSnapshot` | page → native | action | P1 | — | json {counters} | Foxy/Bridge/FoxyBridge.swift:378 |
| `restoreSecrets` | page → native | action | P1 | `keysetId`: string, `start`: integer, `count`: integer, `candidate`: string? | json {keysetId, start, secrets, blindingFactors} | Foxy/Bridge/FoxyBridge.swift:379 |
| `p2pkReserve` | page → native | action | P1 | `count`: integer | json {start, next, pubkeys} | Foxy/Bridge/FoxyBridge.swift:382 |
| `p2pkPubkeys` | page → native | action | P1 | `start`: integer, `count`: integer | json {start, next, pubkeys} | Foxy/Bridge/FoxyBridge.swift:383 |
| `p2pkKey` | page → native | action | P1 | `index`: integer | json {index, privkey, pubkey} | Foxy/Bridge/FoxyBridge.swift:384 |
| `seedShow` | page → native | action | P3 | `verify`: boolean | json {verified, delete, revealed} | Foxy/Bridge/FoxyBridge.swift:385 |
| `seedEnter` | page → native | action | P3 | — | json {candidate} | Foxy/Bridge/FoxyBridge.swift:386 |
| `seedAdopt` | page → native | action | P3 | `candidate`: string | json {adopted, same} | Foxy/Bridge/FoxyBridge.swift:387 |
| `seedCandidateForget` | page → native | action | P3 | `candidate`: string | json {forgotten} | Foxy/Bridge/FoxyBridge.swift:388 |
| `seedWipe` | page → native | action | P3 | — | json {wiped, created} | Foxy/Bridge/FoxyBridge.swift:389 |
| `seedProtection` | page → native | action | P3 | — | json {mode, passcode} | Foxy/Bridge/FoxyBridge.swift:390 |
| `seedProtect` | page → native | action | P3 | `mode`: string | json {mode} | Foxy/Bridge/FoxyBridge.swift:391 |
| `scan` | page → native | action | P3 | `prompt`: string | text | Foxy/Bridge/FoxyBridge.swift:358 |
| `previewStart` | page → native | action | P3 | `x`: integer, `y`: integer, `w`: integer, `h`: integer | "ok" | Foxy/Bridge/FoxyBridge.swift:401 |
| `previewStop` | page → native | action | P3 | — | "" | Foxy/Bridge/FoxyBridge.swift:402 |
| `pasteStart` | page → native | action | P3 | `x`: integer, `y`: integer, `w`: integer, `h`: integer | "ok" | Foxy/Bridge/FoxyBridge.swift:403 |
| `pasteStop` | page → native | action | P3 | — | "" | Foxy/Bridge/FoxyBridge.swift:404 |
| `clipboard` | page → native | action | P3 | — | text | Foxy/Bridge/FoxyBridge.swift:365 |
| `copy` | page → native | action | P3 | `text`: string, `sensitive`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:366 |
| `share` | page → native | action | P3 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:406 |
| `notify` | page → native | action | P3 | `kind`: string | "ok" / "throttled" | Foxy/Bridge/FoxyBridge.swift:405 |
| `biometric` | page → native | action | P3 | `reason`: string, `passcode`: boolean | "yes" / "no" / "unavailable" | Foxy/Bridge/FoxyBridge.swift:367 |
| `openSettings` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:407 |
| `openCompany` | page → native | action | P3 | `which`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:408 |
| `haptic` | page → native | message | P3 | `haptic`: string | none | Foxy/Bridge/FoxyBridge.swift:456-470 |
| `username` | page → native | message | P1 | `username`: string | none | Foxy/Bridge/FoxyBridge.swift:456-470 |
| `inboxOpen` | page → native | action | LATER | — | json {onion, nostr} | Foxy/Bridge/FoxyBridge.swift:410 |
| `inboxClose` | page → native | action | LATER | `hold`: number | "ok" | Foxy/Bridge/FoxyBridge.swift:411 |
| `inboxAnswer` | page → native | action | LATER | `answer`: string, `status`: integer, `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:412 |
| `onionPost` | page → native | action | LATER | `url`: string, `body`: string, `timeout`: number | status-body | Foxy/Bridge/FoxyBridge.swift:413 |
| `nostrSend` | page → native | action | LATER | `target`: string, `body`: string | text | Foxy/Bridge/FoxyBridge.swift:414 |
| `tapReceiveStart` | page → native | action | LATER | `payload`: string, `early`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:416 |
| `tapReceiveStop` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:417 |
| `tapPayStart` | page → native | action | LATER | `now`: boolean? | json-or-text {payload, code} or "ok" | Foxy/Bridge/FoxyBridge.swift:418 |
| `tapSend` | page → native | action | LATER | `body`: string | text | Foxy/Bridge/FoxyBridge.swift:427 |
| `tapChange` | page → native | action | LATER | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:419 |
| `tapChangeKept` | page → native | action | LATER | `kept`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:420 |
| `tapAsking` | page → native | action | LATER | — | "ok" / "no link" | Foxy/Bridge/FoxyBridge.swift:421 |
| `tapChangeDue` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:422 |
| `tapQuote` | page → native | action | LATER | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:423 |
| `tapTerms` | page → native | action | LATER | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:424 |
| `tapPayStop` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:425 |
| `bluetoothAsk` | page → native | action | LATER | — | "allowed" / "denied" / "off" / "unsupported" / "unknown" | Foxy/Bridge/FoxyBridge.swift:426 |
| `shakeStart` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:441 |
| `shakeStop` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:442 |
| `cardBegin` | page → native | action | LATER | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:429 |
| `cardSend` | page → native | action | LATER | `apdu`: string | text | Foxy/Bridge/FoxyBridge.swift:430 |
| `cardSay` | page → native | action | LATER | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:431 |
| `cardAgain` | page → native | action | LATER | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:432 |
| `cardEnd` | page → native | action | LATER | `text`: string?, `error`: string? | "ok" | Foxy/Bridge/FoxyBridge.swift:433 |
| `cardOwnerKey` | page → native | action | LATER | `key`: string | json {pub} | Foxy/Bridge/FoxyBridge.swift:436 |
| `cardOwnerSign` | page → native | action | LATER | `key`: string, `label`: string, `nonce`: string, `value`: string | json {sig} | Foxy/Bridge/FoxyBridge.swift:437 |
| `cardTime` | page → native | action | LATER | — | json {time, sig} | Foxy/Bridge/FoxyBridge.swift:439 |
| `_scanResult` | native → page | push | P1 | `text`: string\|null, `error`: string\|null | none | Foxy/Bridge/FoxyBridge.swift:1488-1494 |
| `_privacy` | native → page | push | P2 | `tor`: string, `progress`: integer, `everUp`: boolean, `unprotected`: boolean, `transport`: string, `orbot`: string, `firstSetup`: boolean, `vpn`: boolean, `orbotKey`: boolean, `network`: string | none | Foxy/Bridge/FoxyBridge.swift:157-160 |
| `_captured` | native → page | push | P3 | `on`: boolean | none | Foxy/Bridge/FoxyBridge.swift:142-145 |
| `_screenshot` | native → page | push | P3 | — | none | Foxy/Bridge/FoxyBridge.swift:148-150 |
| `_previewRefused` | native → page | push | P3 | `why`: string | none | Foxy/Bridge/FoxyBridge.swift:1107 |
| `_liveScan` | native → page | push | P3 | `text`: string | none | Foxy/Bridge/FoxyBridge.swift:1135 |
| `_pasted` | native → page | push | P3 | `text`: string | none | Foxy/Bridge/FoxyBridge.swift:1225 |
| `_putAway` | native → page | push | P1 | `on`: boolean | none | Foxy/FoxyWebView.swift:1102 |
| `_tidying` | native → page | read | P1 | — | none | Foxy/FoxyWebView.swift:1068 |
| `_resumed` | native → page | push | P1 | `away`: integer | none | Foxy/FoxyWebView.swift:1146 |
| `__foxyUptime` | native → page | global | P1 | `s`: number, `p`: number | none | Foxy/FoxyWebView.swift:1166 |
| `deepLink` | native → page | global | P3 | `hash`: string | none | Foxy/FoxyWebView.swift:476-507 |
| `_requestPaid` | native → page | push | LATER | `body`: string, `answerId`: string, `via`: string? | none | Foxy/Bridge/FoxyBridge+Delivery.swift:104 |
| `_tapQuote` | native → page | push | LATER | `text`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:130 |
| `_tapChange` | native → page | push | LATER | `token`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:194 |
| `_tapTerms` | native → page | push | LATER | `text`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:200 |
| `__foxyBluetoothAnswer` | native → page | global | LATER | `state`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:259 |
| `_turned` | native → page | push | LATER | `upsideDown`: boolean | none | Foxy/Bridge/FoxyBridge+Tap.swift:303-307 |
| `_tapChangeKeptLate` | native → page | push | LATER | — | none | Foxy/Bridge/FoxyBridge+Tap.swift:380 |
| `_tap` | native → page | push | LATER | `side`: string, `stage`: string, `code`: string?, `edge`: string?, `pct`: integer? | none | Foxy/Bridge/FoxyBridge+Tap.swift:487-498 |
| `_card` | native → page | push | LATER | `stage`: string, `text`: string | none | Foxy/Flashcard/CardGate.swift:143-149 |
| `noWebRTC` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:138-141 |
| `noCallout` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:146-157 |
| `__foxyClock24` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:164 |
| `FOXY_DEBUG` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:173 |
| `FOXY_SHOW_WARNING` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:179 |
| `consoleSilence` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:188-203 |
| `__foxyPageHash` | native → page | global | P1 | `value`: string | none | Foxy/FoxyWebView.swift:377-380 |
<!-- contract-table:end -->
