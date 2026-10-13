# BRIDGE.md — the contract between the page and native

The Android app runs the iOS app's page (`core/web/`, byte for byte, pinned in
`core/PIN`) in an Android System WebView. Everything the page cannot do itself
— the network, the seed, the camera, the clipboard, notifications, Bluetooth,
NFC — it asks native for over one channel, and native answers or pushes back
into the page. This document is that channel, exhaustively, as the iOS app
implements it at getfoxy/iOS `8df0bb19992cceda44d04e947bce211e1415975e`.
Android implements the same contract; nothing in `core/web` changes.

`conformance/contract.json` is the machine-readable form, and it is the
source: the per-item section and the closing table below are generated from it
(`node conformance/tools/contract-table.js --write`) and the suite fails when
they drift. Every `file:line` cited is checked against the pinned commit by
`conformance/test/contract.test.js` (refs into `Web/` on every run against
`core/web`; refs into `Foxy/` and `build/` when `FOXY_IOS_REF` names a clone,
which CI fetches).

**Counts at the pinned commit:** 74 actions and 2 actionless messages the page
posts; 22 pushes and reads native makes into the running page plus 7 scripts
and globals it injects before the page runs (29 native → page items); 105
contract entries in all.

Phases: **P1** shell (WebView, bridge, lifecycle, seed and counters in the
Keystore), **P2** Tor (Route, tor-android, IPtProxy, mint/price/chart
requests), **P3** platform (scanner, clipboard, share, notifications, haptics,
biometrics, native seed screens, since ENG-666 and ENG-667 the NFC card's
owner key, signed time and session, since ENG-668 a card held to the phone
opening Foxy, since ENG-670 and ENG-671 Bluetooth tap to pay, both sides, and
since ENG-672 the onion inbox and post), **LATER** (Nostr).

## 1. How a message travels

### Page → native

The page posts plain objects to `window.webkit.messageHandlers.foxy.postMessage`
(`build/wallet/03-seed-counters-logs.js` `bridgeAsk`/`bridgeSeed`, and direct
calls). There are exactly three shapes, and native classifies them in this
order (`Foxy/Bridge/FoxyBridge.swift:456-466`, `dispatch`):

1. **`{ haptic: kind }`** — no action, no id. Buzz, answer nothing.
2. **`{ action, id, ...fields }`** — an action. `action` names a handler in the
   one table (`FoxyBridge.swift:357-444`, `handlers`); an action not in the
   table is answered `error: "Unknown action: <name>"`. `id` is a string the page
   made (`'b'|'s'|'p'|'c'|'q'|'n' + Date.now() + random`, or `'log-'`, `'copy-'`,
   `'covered-'`, `'bt-'` + time) and is echoed in the reply.
3. **anything else** — ignored, with no answer. The page does post one such
   message: `{ username }` from `pushToNative()` (`build/app/05-device-shell.js:153`),
   a leftover of a widget that does not exist. Android must drop it silently, not
   answer it as an unknown action.

Before dispatch, every message is refused unless it came from the **main frame**
of the staged page (`FoxyBridge.swift:472-474`, `answers`: main frame and a
`file` origin; `FoxyBridge.swift:476-494`). On Android the equivalent is
androidx.webkit `WebViewCompat.addWebMessageListener` with the allowed-origin
rule `https://appassets.androidplatform.net` only, and `isMainFrame` checked in
`onPostMessage` (`docs/ANDROID-MAPPING.md`).

**The Android shim.** `addWebMessageListener` injects an object (call it
`foxyNative`) whose `postMessage` takes a string. A document-start script
defines `window.webkit = { messageHandlers: { foxy: { postMessage: m =>
foxyNative.postMessage(JSON.stringify(m)) } } }`, so the page is unchanged. It
must be injected with `WebViewCompat.addDocumentStartJavaScript` for the same
origin, before any page script. Every message the page posts survives a JSON
round trip (the conformance host carries them exactly that way and fails on a
function, `NaN`, `Infinity` or a non-plain object). WKWebView's own conversion
is structured; JSON is stricter, and the suite shows the page never relies on
the difference.

### Native → page

Native never calls the page except by evaluating JavaScript in its main frame
(`FoxyBridge.swift:1143-1145`, `evaluate`; `WebView.evaluateJavascript` on
Android, always on the main thread). Every evaluation is guarded
`window.FoxyWallet && window.FoxyWallet._x && …`, so a push into a page still
booting, or reloading, does nothing rather than throw.

**Replies.** Every action with an id is answered exactly once, through
`window.FoxyWallet._scanResult(id, text, error)` (`FoxyBridge.swift:1488-1494`):
`text` on success, `error` on refusal, the other `null`. Each argument is
written as a JSON string literal by serialising `[value]` and dropping the
brackets (`FoxyBridge.swift:1513-1519`), so a scanned string with quotes,
newlines or `</script>` cannot break out. Android must do the same
(`org.json.JSONArray().put(value).toString()` minus the brackets, or
`JSONObject.quote`). `null` is the literal `null`. The text of a JSON reply is
itself a string: the page parses it (`nativeJson`), and anything that is not a
JSON object is a refusal.

**Timeouts are the page's.** Every page call arms its own timer and gives up
(`bridgeAsk` 20 s by default; `bridgeSeed` 120 s; a person-facing screen 30
min; the per-action values are under *Timing* below). A reply after that is
dropped by the page (`_scanResult` finds no waiter). Native never times out on
the page's behalf except where stated (the seed-replace alert, 120 s).

**Threads.** iOS answers from whichever queue finished the work, hopping to
main to evaluate. The seed and counter actions run serially on one queue
(`NativeSeedBridge.swift`, `seedQueue`), which is what makes a reservation
atomic; Android must give them one single-threaded executor.

### What the page reads before it runs

Seven scripts and globals are installed at document start
(`Foxy/FoxyWebView.swift:136-203`, `377-380`), listed under *Lifecycle*,
*Debug* and *Network* below: no WebRTC in any frame, no text-selection callout,
the 12/24-hour clock, the debug flags, the release console silence, and the
page hash. On Android: `WebViewCompat.addDocumentStartJavaScript`, origin
allowlisted, plus the WebRTC removal in every frame.

## 2. Ordering rules

- **Boot, observed** (conformance, a wallet with a seed and Tor up, `log`
  lines left out): `{username}` › `hostsKnown` › `captured` › `awake` ›
  `seedStatus` (quiet) › [`seedCreate` on a fresh install, or `seedMigrate` then
  `countersImport` when page storage holds `foxy.seed.v1` / `foxy.counter.v1`] ›
  `privacy` › `price` › `mintRequest` (GET `/v1/info`, `/v1/keysets`, `/v1/keys`)
  › `seedProtection` › `p2pkReserve` › `covered` (several). `candles` comes as
  the chart draws. With Tor still connecting, nothing after `privacy` that needs
  a route is asked at all.
- **P1 answers.** The page asks `hostsKnown` (P2), `captured` (P3) and
  `privacy` (P2) before anything else, and its Tor gate offers `torRetry`,
  `unprotected`, `orbotRefresh` and `orbotAccess`. A P1 host does not leave
  those as "Unknown action": each such entry in `contract.json` carries a `p1`
  stub, shown as **In P1** under §3, and the P1 host answers it. `privacy`,
  `torRetry` and `unprotected` answer the snapshot iOS answers in a build with
  no Tor (`tor: "none"`, `Foxy/Network/Route.swift:485-491`), so the gate shows
  CANNOT CONNECT TO TOR with PROCEED OFFLINE; `price`, `candles` and
  `mintRequest` answer the refusal (`ROUTE.md`, *P1, before Tor exists*). P1
  pushes no `_privacy`. The flow *P1: a host serving only P1 actions and the p1
  stubs* (`conformance/test/flows-p1-p2.test.js`) boots on exactly that host and is
  the one to run against the Kotlin bridge in P1; an action of a later phase
  with no stub fails it.
- **P2 answers.** The P2 host (ENG-648) serves the 18 P1 actions and the seven
  P2 ones (`privacy`, `torRetry`, `unprotected`, `mintRequest`, `price`,
  `candles`, `hostsKnown`) and answers the `p1` stub of every P3 and LATER
  action that has one: `captured`; `seedProtection`, `tapPayStart`,
  `pasteStart` and `pasteStop`, stubbed in P2 because a host that connects
  reaches the wallet's screens, which ask them with no one acting (the home
  screen asks the protection, a funded one listens for a tap, a PASTE button on
  screen asks for the native control, without waiting on the answer); and
  `orbotRefresh` and `orbotAccess`, which are LATER on Android (ANDROID-MAPPING
  decision 3, Orbot is `none`). The Kotlin table emits only those stubs (`ContractStubs`, from
  this file). The flow *P2: a host serving P1 and P2 actions …*
  (`conformance/test/flows-p1-p2.test.js`) goes from connecting to up, mints and
  melts, and fails on any later action with no stub.
- **P3 answers.** The P3 host (ENG-649) serves the P1, P2 and P3 actions,
  each P3 action by its real handler (the five with a `p1` stub, `captured`,
  `seedProtection`, `pasteStart`, `pasteStop` and, since ENG-671,
  `tapPayStart`, never by the stub), and answers the `p1` stub of every LATER
  action that has one (`orbotRefresh`, `orbotAccess`). The flow *P3: a host
  serving P1, P2 and P3 actions …* (`conformance/test/flows-p3.test.js`) asks
  every P3 action and fails on any LATER action with no stub.
- **The Bluetooth screen (ENG-656, ENG-670).** `bluetoothAsk` and
  `__foxyBluetoothAnswer` are P3, not LATER: the first-install Bluetooth
  screen's APPROVE posts `bluetoothAsk`, sets `asking` and leaves only when
  native calls `window.__foxyBluetoothAnswer(state)`, so an "Unknown action"
  reply left the screen up for good. ENG-656 answered `"unsupported"` on every
  phone; since tap to pay's receiver (ENG-670, `bridge/TapBridge.kt`) Android
  puts the system's question up for `BLUETOOTH_ADVERTISE` and
  `BLUETOOTH_CONNECT` (API 31+; API 28-30 holds `BLUETOOTH` and
  `BLUETOOTH_ADMIN` from the install) and answers as iOS's `BluetoothAsk`
  does: `allowed`, `denied`, `off`, `unsupported`, or `unknown` after a minute
  with no state, pushed to `__foxyBluetoothAnswer` first and then replied
  (`Foxy/Bridge/FoxyBridge+Tap.swift:254-265`, `504-539`). A phone with no
  adapter, or one whose adapter is on and cannot advertise, is `unsupported`
  and is asked nothing (`tap/TapBluetooth.kt` `BluetoothAnswer`; iOS reads the
  authorization first, but Android cannot tell whether an adapter can
  advertise while it is off, and a permission the phone could not use is not
  asked for). The flow *P3: APPROVE on the first-install Bluetooth screen …*
  presses it on a P3 host without Bluetooth, and *P3 (ENG-670): APPROVE …
  allowed* on one with it.
- **The receive screen's TAP (ENG-656, ENG-670).** `tapReceiveStart`,
  `tapReceiveStop` and the `_tap` push are P3 too. The page asks
  `tapReceiveStart` by itself on every receive screen (auto-arm, `foxy.tap.auto`
  on by default), and took an "Unknown action" reply as a refusal: it put
  UNKNOWN ACTION: TAPRECEIVESTART in the TAP card and asked again every 1.2 s
  while the screen was up (`build/app/26d-tap.js` `syncTap`). A payload iOS
  would refuse is refused with its words; otherwise `"ok"`, and, when that
  makes the receiver, its radio's state after the reply, as iOS's peripheral
  manager reports its state after the handler has answered
  (`Foxy/Bridge/FoxyBridge+Tap.swift:22-147`, `Foxy/Bluetooth/TapLink.swift:26-33`,
  `506-509`). On a phone without a Bluetooth LE peripheral that is
  `_tap({side: "receive", stage: "unsupported"})`, as ENG-656 answered
  everywhere: the page at the pin takes only `off` and `denied` from a
  receive-side `_tap`, so the TAP card says HOLD A PHONE HERE, as on an iPhone
  without Bluetooth. On a phone with one (ENG-670) the receiver is
  `tap/TapReceiver.kt`: a GATT server with iOS's four doors and a CCCD on each
  notify door, an advertisement of the service UUID and nothing else, and the
  ENG-669 link (`TapReceiverLink`) on top; `searching` once on the air, then
  `nearby`/`far`, `connecting`, `code`, `receiving`, `gone` and `version` as iOS
  says them. `tapReceiveStop` lets the receiver go, or holds it off the air
  while a payment or change is crossing, and answers `"ok"`. The flows *P3:
  the receive screen's TAP …* (a phone without Bluetooth) and *P3 (ENG-670):
  the receive screen on the air …* (a phone with it) hold the screen up on a
  P3 host.
- **Tap to pay's receiving side (ENG-670).** `tapChange`, `tapAsking`,
  `tapChangeDue`, `tapTerms`, `shakeStart`, `shakeStop` and `inboxAnswer`, and
  the pushes `_requestPaid(…, "tap")`, `_tapQuote`, `_tapChangeKeptLate` and
  `_turned`, are P3. A payment that has crossed the link is pushed as
  `_requestPaid(body, key, "tap")` and the page's `inboxAnswer` for that key
  becomes M6, iOS's one registry for the onion and the tap
  (`Foxy/Bridge/FoxyBridge+Tap.swift:77-122`,
  `Foxy/Bridge/FoxyBridge+Delivery.swift:234-246`); the onion inbox's keys join
  it with ENG-672, and until then a key nothing waits on is "No payment is
  waiting on that answer.". Change goes back over the same link (`tapChange`,
  answered when the payer says it kept it, 7 s, or late as
  `_tapChangeKeptLate`). Shake to TAP listens to the accelerometer only while
  armed and Foxy is in front; `_turned` comes from an
  `OrientationEventListener` while Foxy is in front (`Foxy/FoxyWebView.swift:38-48`).
  Nothing advertises while Foxy is not in front: the activity's stop takes the
  receiver off the air and lets it go (unless a payment is crossing), and its
  start puts a new receiver, with a new service UUID, on the air with what the
  page last offered.
- **Tap to pay's paying side (ENG-671, ENG-658).** `tapPayStart`,
  `tapPayStop`, `tapSend`, `tapChangeKept`, `tapQuote` and the pushes
  `_tapChange` and `_tapTerms` are P3 (`bridge/TapBridge.kt`,
  `tap/TapPayer.kt`, `tap/AndroidTapCentral.kt`; `Foxy/Bridge/FoxyBridge+Tap.swift:167-245`,
  `Foxy/Bridge/FoxyBridge+Tap.swift:267-272`, `Foxy/Bridge/FoxyBridge+Tap.swift:429-438`,
  `Foxy/Bridge/FoxyBridge+Tap.swift:474-485`, and `TapPayer` in `Foxy/Bluetooth/TapLink.swift:899-1955`).
  The home and send screens listen with no one acting: `tapPayStart` starts a
  BLE scan (no hardware filter, since the receiver's service is new for every
  invoice and the "has a service UUID" filter, an all-zero mask, matched
  nothing on the Pixel 8 Pro, ENG-679; iOS's own rules then decide in
  software what is a Foxy: connectable, no
  manufacturer data, one full 128-bit service UUID), the proximity verdict
  (`TapProximity`, iOS's thresholds), the quiet link and the near doors, and
  answers `{payload, code}` once the nearest receiver's offer is in. It is
  never answered for a radio that is off, refused or missing: that is pushed
  once as `_tap({side: "pay", stage})` and the listen waits for the radio, as
  iOS's central manager does. Until ENG-671 it was answered "Stopped." at once
  and the page, reading a search that had ended, listened again with a backoff
  from one second to a minute for as long as Foxy was open (ENG-658). A second
  `tapPayStart` answers the first "Started again."; `tapPayStop` and the page
  going answer a waiting one "Stopped.". `now: true` (TAP TO PAY on the toast)
  answers "ok" at once and lets the listen take the receiver it hears from
  eight below the toast's line for five seconds. Over the held link: `tapSend`
  answers the receiver's M6 as it sealed it, or why the link went first;
  `tapQuote` is M10; change arrives as `_tapChange` and the page's
  `tapChangeKept` is M8; M11 arrives as `_tapTerms`. Nothing scans while Foxy is
  put away: the scan and a quiet link stop, a link holding the offer stays up,
  and back in front a payer with a payment handed over asks the receiver again
  (M12) after 0.4 s, as iOS's `tapWoke`. The flows in
  `conformance/test/flows-tap-pay.test.js` hold the listen open on a P3 host
  with each Bluetooth state, and run one tap through.
- **A card's owner key and its signed time (ENG-666).** `cardOwnerKey`,
  `cardOwnerSign` and `cardTime` are P3: none needs NFC, and a card set up
  from an iPhone has to be operable from an Android phone restored from the
  same twelve words. `cardOwnerKey` and `cardOwnerSign` are seed actions on
  the seed executor (`SeedBridge`, `keychain/CardOwner.kt`, the port of
  `CardOwner` in `Foxy/Keychain/NUT13.swift:365-526`): the owner key is
  HMAC-SHA256(seed, "FoxyCard/owner" ‖ 0x00 ‖ the card's compressed key
  [‖ counter]) mod n on P-256, and the owner signs ECDSA P-256/SHA-256, DER,
  over "FoxyCard/" ‖ label ‖ nonce ‖ value for the six labels iOS signs
  (`change-pin`, `set-limit`, `set-owner`, `set-card`, `load`, `reset`), each
  with its value shape; `lock`, `tag`, `time` and every other shape are
  "bad request" before the seed is read. `cardTime` is iOS's INTERIM signer
  (`Foxy/Flashcard/CardTime.swift`), the same key pair byte for byte, for a
  card of software 1.14 and before. A card of 1.15 and on takes block headers
  instead, which the page fetches over Tor itself (`mintRequest`) and sends
  through the card session: nothing of that is native's. The JVM tests hold
  Kotlin to iOS's `tests/fixtures/card-owner-vectors.json` and to the card's
  own `spec/vectors/owner.json`.
- **A card session (ENG-667).** `cardBegin`, `cardSend`, `cardSay`,
  `cardAgain`, `cardEnd` and the `_card` push are P3, ported from
  `Foxy/Bridge/FoxyBridge+Flashcard.swift` and `Foxy/Flashcard/CardGate.swift`,
  `CardLink.swift` (`bridge/CardBridge.kt`, `flashcard/CardGate.kt`,
  `flashcard/NfcCardLink.kt`). One session at a time; every command is read by
  the gate before it is carried: the SELECT of Foxy's applet by its whole name
  (`F0 46 4F 58 59 43 41 52 44 01`) first, then only that applet's 31
  instructions under class `B0` (SET_TAG `52` is not one, as upstream), and
  the applet chosen again after a card comes back. Android has no system NFC
  sheet: Foxy draws its own over the page (`flashcard/CardSheetView.kt`, "Ready
  to Scan", the page's line, Cancel; a tick and a word or the reason in red),
  and reads the card in reader mode on the resumed activity only
  (`NfcAdapter.enableReaderMode`, NFC-A/B, IsoDep). The sheet waits 60 s for a
  card; Cancel or Back cancels; Foxy put away ends it. Progress is pushed
  (`_card`) only from the session in hand, before the reply it goes with. A
  debug build with no NFC (the emulator), or started with the `FoxyCardSim`
  extra, reaches getfoxy/card's applet in jCardSim over loopback instead
  (`SimCardLink`, ports 47431-47434), as iOS's Simulator build does.
  ENG-668 adds the card held to the phone with no session open: its NDEF
  address, `https://getfoxy.io/card`, reaches `CardTapActivity` (an invisible
  door, never MainActivity itself) from Android's tag dispatch (or as a link),
  is held to `CardAddress.isCard`, and goes to a new page, to the page on top of
  its task, or nowhere while a seed screen or the scanner is over the page
  (`CardTapRoute`: a card never makes a second page), and the page is told
  only `_cardTapped()`, by `CardTapDelivery` as iOS tells it (not under the
  splash, not before `_resumed`, every 0.3 s until it answers true, given up
  60 s after the latest address). An address while a session is open, or within
  3 s of reader mode going off, is the card being read and is dropped.
- **The onion inbox and post (ENG-672).** `inboxOpen`, `inboxClose`,
  `inboxAnswer`, `onionPost` and the `_requestPaid` push are P3: a port of the
  onion half of `Foxy/Bridge/FoxyBridge+Delivery.swift:13-258` and of
  `Foxy/Network/OnionInbox.swift`, `OnionPost.swift` and `OnionAddress.swift`
  (`bridge/DeliveryBridge.kt`, `network/OnionInbox.kt`, `network/OnionPost.kt`,
  `network/OnionHttp.kt`, `network/OnionAddress.kt`). The page asks
  `inboxOpen` on every render of a receive screen until it has an address, and
  after a refusal every 4 s (`syncInbox`), so the "Unknown action" Android
  answered had a receive screen ask again for as long as it was up. Now:
  - `inboxOpen` answers `{"onion": "http://<56>.onion/<32 hex>"}`: the
    address already open, else the warm spare (handed over, and another
    started at once), else a new one: a listener on 127.0.0.1 port 0 and
    `ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach Port=80,127.0.0.1:<port>`
    on tor-android's control link (jtorctl), iOS's command
    (`Foxy/Tor/TorService.swift:1775-1789`). Tor makes the key and discards
    it, so every request has an address never seen before; nothing is
    persisted, as on iOS. No `nostr` field: the Nostr half is LATER (the
    ENG-672 follow-up), and the page takes an answer without one as it takes
    an iPhone whose relays would not listen.
  - The inbox's bounds and bytes are iOS's: four connections, 8 kB of head,
    256 kB of body, the request path (128 random bits) checked before
    anything else (404), POST only (405), one Content-Length of digits and no
    Transfer-Encoding, 30 s to send the request, 100 s for the page's answer
    (then 504), a payment arriving at a closed inbox 410; the answer is
    `HTTP/1.1 <status> <reason>`, `Content-Length`, `Connection: close` and,
    with a body, `Content-Type: application/json`, then the write side shut.
    `OnionHttpTest` is `FoxyTests/OnionHTTPTests.swift` case for case,
    fuzzing included.
  - `inboxAnswer` lets only 200, 409 and 422 through (anything else is 422)
    and 300 characters of text; the payer reads `{"ok":true}` or
    `{"error": text}` as Apple's JSONSerialization writes it. The answers
    owed are one table (`bridge/InboxAnswers.kt`, FoxyBridge.swift
    `inboxAnswers`), shared with tap to pay's receiving side (ENG-670), whose
    answer reads the reason back out of that JSON for M6.
  - `inboxClose{hold}` keeps a retiring address answering 0..180 s, then
    `DEL_ONION`; the screen gets the spare at once.
  - `onionPost{url, body, timeout, circuit}` takes `http://<v3>.onion` only
    (no user, query or fragment, the path as encoded), speaks SOCKS5 to Tor
    through `Socks5SocketFactory` (the circuit label as the login, the onion
    by name), writes iOS's request byte for byte and reads one answer, cut at
    64 kB; the attempt's ceiling is the page's `timeout`, 3..120 s. Tor only:
    never the open connection, even with CONTINUE WITHOUT TOR on, and not
    while Route's door is shut as Foxy is put away.
  - Put away, every address of this phone's ends (the one on screen, the
    spare, the retiring ones, with their answers; a tap's answer is kept,
    as iOS keeps `tapAnswerKeys`, since the payer deletes its only copy of a
    tap payment on it) before Tor parks, and none
    is made until Foxy is back (`DeliveryBridge.closeForBackground`/`cameBack`,
    `Foxy/FoxyWebView.swift:1022`); the page reopens one on its return
    (`inboxWake`). The bridge going with its page (`WebHost.destroy`) closes
    them too: on Android the bridge lives with the activity's page.
  The flows *P3: the receive screen's onion inbox …* (asked once, the request
  on screen names the address), its control (an "Unknown action" re-asked),
  and *P3 two phones: a payment request paid over the onion …* (two pages, the
  payer's `onionPost` carried to the receiver's inbox, redeemed and answered
  200) run on P3 hosts.
  Nostr stays LATER: `nostrSend` is a request the page awaits with a timeout
  and a catch, not a push it waits for, so an "Unknown action" reply ends it.
  `tapPayStart` keeps its `p1` stub (`Stopped.`) for P1 and P2 hosts only.
- **Native pushes the route first.** iOS starts Tor before the page loads and
  pushes `_privacy` on every change (`FoxyBridge.swift:89-118`, `startTor`); the
  page also asks `privacy`. Android: `_privacy` is pushed on every change of
  Tor's or of the network (`Tor.onChange`, `NetworkKind`), through the bridge's
  one evaluate path; there is no Orbot to push.
- **One seed change at a time.** `seedCreate`, `seedMigrate`, `seedAdopt` and
  `seedWipe` are refused while another waits (`FoxyBridge.swift:225-234`), with
  "Another change to the seed is still waiting. Nothing was changed.".
- **A reservation is written before it is answered.** `counterReserve`,
  `counterReserveAt` and `p2pkReserve` persist the new counter (fsync, rename)
  before the secrets or keys leave native.
- **Going to the background** (`FoxyWebView.swift:999-1132`): cover the screen,
  drop typed words and the unlock, close seed screens, save the field log,
  release `awake`, close the onion and Nostr inboxes, then push `_putAway(true)`;
  after 0.4 s poll `_tidying` and the requests in flight every 0.25 s; leave
  the network once no money request is out (20 s at most) and other requests
  have had 3 s (`Route.swift:200-206`); shut the door (nothing new leaves); hold
  Tor while the OS allows, then park it. Android has no such allowance: Tor is
  parked as soon as the waiting ends (`web/PutAway.kt`). Android only, API
  28-29: the screen-lock confirmation Foxy itself starts is Settings' activity
  over Foxy and stops it; that stop drops nothing until the confirmation
  answers (120 s at most). A yes carries on with nothing put away; a no, a
  cancel, or no answer within 2 s of the return puts away what was skipped
  and is a return like any other below (`_resumed(secondsAway)`, so the page
  PIN-locks).
- **Coming back** (`FoxyWebView.swift:1134-1179`): open the door, drop the
  seed kept for the putting-away, push `__foxyUptime`, tell Tor
  (`torResumed`), then push `_resumed(secondsAway)`. Take the cover off at once
  for an absence under a second, otherwise when the page says `covered` (4 s
  backstop).
- **The page's process dies** (`FoxyWebView.swift:1351-1358`): forget the
  unlock, drop native state held for the page (camera, paste control, seed in
  memory, seed screens, tap), reload. Android: `onRenderProcessGone` returns true
  and recreates the WebView.

## 3. Every message, by domain

Generated from `conformance/contract.json`. "Called from" cites the shipped
page (`Web/`) where the call is in a shipped file, or the page's source under
`build/app/` where it is packed inside `index.html`.

<!-- contract-detail:begin -->
### The reply path

#### `_scanResult` — P1

- **Shape:** native → page, push: `window.FoxyWallet._scanResult(id, text, error)`
- **Payload:** `text`: string|null, `error`: string|null
- **Called from:** `Web/foxy-wallet.js:24088`
- **Native:** The one reply path for every action with an id. Exactly one of text/error is non-null. Each argument is a JSON string literal (JSONSerialization of [value], brackets dropped), so a scanned string cannot break out. An id the page no longer waits for is ignored by the page.
- **Timing:** Async, on the main thread. Late replies after the page's own timeout are dropped by the page.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:1488-1494`, `Foxy/Bridge/FoxyBridge.swift:1513-1519`

### Lifecycle, splash and app-switcher cover

#### `covered` — P1

- **Shape:** page → native, action: `{ action: "covered", id }`
- **Payload:** —
- **Called from:** `Web/foxy-tor-gate.js:195`
- **Native:** The page has the screen covered (launch intro, CONNECTING, gate screen): native takes the splash and the app-switcher cover down. Nothing is read from the message. Sent on every gate screen, twice per showing is normal.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Timing:** Before the 8 s splash backstop. Native treats a second one as no news.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:362`, `Foxy/Bridge/FoxyBridge.swift:542-545`, `Foxy/FoxyWebView.swift:302`, `Foxy/FoxyWebView.swift:309`

#### `awake` — P1

- **Shape:** page → native, action: `{ action: "awake", id, on }`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:24143`
- **Native:** Hold the screen lit while a screen is looked at rather than touched (an invoice held up, the gate). Released by native whenever the app leaves the foreground.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:443`, `Foxy/Bridge/FoxyBridge.swift:519-530`, `Foxy/Bridge/FoxyBridge.swift:535-539`

#### `captured` — P3

- **Shape:** page → native, action: `{ action: "captured", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:26338`
- **Native:** Whether the screen is being recorded or mirrored right now, for a page that starts while it already is; changes are pushed with _captured.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "yes" / "no"
- **In P1:** `no`. No capture detection until P3.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:395`, `Foxy/Bridge/FoxyBridge.swift:861-863`

#### `username` — P1

- **Shape:** page → native, message: `{ username }` (no action, no id)
- **Payload:** `username`: string
- **Called from:** `build/app/05-device-shell.js:153`
- **Native:** pushToNative(): { username } with no action and no id, on every change, left over from a widget that does not exist. Native dispatch reads it as `.ignored` and does nothing. Android must ignore it the same way (and must not treat it as an unknown action).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:456-466`

#### `_captured` — P3

- **Shape:** native → page, push: `window.FoxyWallet._captured(bool)`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:26308`
- **Native:** Screen recording/mirroring started or stopped: the page hides the seed and secrets while it lasts.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:142-145`, `Foxy/FoxyWebView.swift:987-989`

#### `_screenshot` — P3

- **Shape:** native → page, push: `window.FoxyWallet._screenshot()`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:26315`
- **Native:** A screenshot was taken (cannot be stopped on iOS).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:148-150`, `Foxy/FoxyWebView.swift:991-993`

#### `_putAway` — P1

- **Shape:** native → page, push: `window.FoxyWallet._putAway && window.FoxyWallet._putAway(true)`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:25821`
- **Native:** The app went to the background: the page starts its small-change top-up. Native then polls _tidying and Route.out every 0.25 s and keeps Tor up for up to 20 s while money moves, 3 s for other requests, then shuts the door.
- **iOS:** `Foxy/FoxyWebView.swift:1130`, `Foxy/FoxyWebView.swift:999-1132`, `Foxy/Network/Route.swift:200-206`

#### `_tidying` — P1

- **Shape:** native → page, read: `!!(window.FoxyWallet && window.FoxyWallet._tidying)`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25822`
- **Native:** Read by native (evaluateJavaScript with a result) while putting away: true while the change top-up is waiting or with the mint.
- **iOS:** `Foxy/FoxyWebView.swift:1096`

#### `_resumed` — P1

- **Shape:** native → page, push: `window.FoxyWallet._resumed(secondsAway)`
- **Payload:** `away`: integer
- **Called from:** `Web/foxy-wallet.js:26429`
- **Native:** Back from the background after `away` whole seconds (0 for focus loss without backgrounding). Sent after Tor is told (torResumed) and __foxyUptime is pushed.
- **iOS:** `Foxy/FoxyWebView.swift:1174`, `Foxy/FoxyWebView.swift:1134-1179`

#### `__foxyUptime` — P1

- **Shape:** native → page, global: `window.__foxyUptime = { s: systemUptimeSeconds, p: performance.now() }`
- **Payload:** `s`: number, `p`: number
- **Called from:** `Web/foxy-wallet.js:26168`
- **Native:** Monotonic device uptime, for the PIN back-off clock (22-screen-lock.js). Pushed on didFinish and on every return. Android: SystemClock.elapsedRealtime()/1000.
- **iOS:** `Foxy/FoxyWebView.swift:1198`

#### `deepLink` — P3

- **Shape:** native → page, global: `location.hash = 'foxy=<screen>&amt=..&unit=..&rail=..'`
- **Payload:** `hash`: string
- **Called from:** native
- **Native:** DEBUG BUILDS ONLY: foxy://receive?amt=50&unit=sat&rail=cashu rebuilt from a whitelist (home, receive, send, token, nfc, split) into the page's own hash. foxy://card (since getfoxy/iOS 9832f7d) sets no hash and takes no parameters: it is a card tap said by hand, delivered as `_cardTapped` (CardTapDelivery). Release registers no scheme and has no handler; the page reads the hash only where FOXY_DEBUG is set. Android (web/DeepLink.kt): foxy://card is recognised and handed to MainActivity's CardTapDelivery, as a card's own address is (ENG-668); nothing of the link reaches the page. The card's https address is not a deep link (see `_cardTapped`).
- **iOS:** `Foxy/FoxyWebView.swift:492-531`, `Foxy/FoxyWebView.swift:544`, `Foxy/FoxyWebView.swift:497`, `Foxy/FoxyWebView.swift:504-508`

#### `noCallout` — P1

- **Shape:** native → page, script: `a <style>: no touch callout / user-select outside inputs; document end, main frame`
- **Called from:** native
- **Native:** Look Up / Translate / Search Web would hand selected text to services on the open network.
- **iOS:** `Foxy/FoxyWebView.swift:146-156`

#### `__foxyClock24` — P1

- **Shape:** native → page, global: `window.__foxyClock24 = true|false`
- **Payload:** `value`: boolean
- **Called from:** native
- **Native:** The phone's 12/24-hour setting (Android: DateFormat.is24HourFormat).
- **iOS:** `Foxy/FoxyWebView.swift:164`

#### `__foxyPageHash` — P1

- **Shape:** native → page, global: `window.__foxyPageHash = '<64 hex>'`
- **Payload:** `value`: string
- **Called from:** native
- **Native:** sha256 of the staged page manifest (= sha256(core/SHA256SUMS)); the drawer shows its first 12 characters so a person can compare with the reviewed commit.
- **iOS:** `Foxy/FoxyWebView.swift:393-396`, `Foxy/FoxyWebView.swift:876-898`

### Debug and the field log

#### `log` — P1

- **Shape:** page → native, action: `{ action: "log", id, text }`
- **Payload:** `text`: string
- **Called from:** `build/app/13-console-and-qr.js:36`
- **Native:** Every console.log/warn/error of the page, mirrored in every build (mirrorConsole). Native writes it to its log; in Release iOS print() reaches only FieldLog, which redacts tokens, invoices, payment requests, onion addresses, keys and long hex on the way in.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:359`, `Foxy/Bridge/FoxyBridge.swift:560-563`, `Foxy/Debug/FieldLog.swift:231-243`

#### `fieldLog` — P1

- **Shape:** page → native, action: `{ action: "fieldLog", id, bad }`
- **Payload:** `bad`: boolean
- **Called from:** `Web/foxy-wallet.js:25677`
- **Native:** The ERRORS (bad=true) and LOGS screens: native and page lines, redacted, newest last.
- **Reply:** FoxyWallet._scanResult(id, text, error) — JSON array of {at: integer, text: string, bad: boolean}
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:360`, `Foxy/Bridge/FoxyBridge.swift:568-570`, `Foxy/Debug/FieldLog.swift:214-221`

#### `fieldLogClear` — P1

- **Shape:** page → native, action: `{ action: "fieldLogClear", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25683`
- **Native:** Forgets every FieldLog line.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:361`, `Foxy/Bridge/FoxyBridge.swift:572-576`

#### `isDebug` — P1

- **Shape:** page → native, action: `{ action: "isDebug", id }`
- **Payload:** —
- **Called from:** nothing in the page at the pinned commit
- **Native:** Whether this is a debug build. Served, but nothing in the page at the pinned commit asks it: the page reads window.FOXY_DEBUG, injected at document start.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "yes" / "no"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:400`, `Foxy/Bridge/FoxyBridge.swift:965-971`

#### `FOXY_DEBUG` — P1

- **Shape:** native → page, global: `window.FOXY_DEBUG = true (debug builds only)`
- **Payload:** `value`: boolean
- **Called from:** native
- **Native:** Debug build marker read synchronously by the page.
- **iOS:** `Foxy/FoxyWebView.swift:173`

#### `FOXY_SHOW_WARNING` — P1

- **Shape:** native → page, global: `window.FOXY_SHOW_WARNING = true (debug, env FOXY_SHOW_WARNING=1)`
- **Payload:** `value`: boolean
- **Called from:** `Web/foxy-tor-gate.js:1860`
- **Native:** Show the first-launch warning again.
- **iOS:** `Foxy/FoxyWebView.swift:179`

#### `consoleSilence` — P1

- **Shape:** native → page, script: `console.log/warn/info/debug = noop (release builds only)`
- **Called from:** native
- **Native:** Release builds silence the page console except console.error. Note: mirrorConsole() still forwards log/warn/error to the `log` action in every build.
- **iOS:** `Foxy/FoxyWebView.swift:191-204`

### Tor gate

#### `privacy` — P2

- **Shape:** page → native, action: `{ action: "privacy", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:15195`
- **Native:** Reads the route snapshot (Route.snapshot). The same object is pushed with _privacy on every change.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string}
- **In P1:** `{"tor":"none","progress":0,"everUp":false,"unprotected":false,"transport":"direct","orbot":"none","firstSetup":false,"vpn":false,"orbotKey":false,"network":"unknown"}`. No Tor in P1: the snapshot a build without Tor answers (Route.snapshot, the #else branch), so the gate shows CANNOT CONNECT TO TOR.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:394`, `Foxy/Bridge/FoxyBridge.swift:855-857`, `Foxy/Network/Route.swift:479-512`

#### `torRetry` — P2

- **Shape:** page → native, action: `{ action: "torRetry", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:15201`
- **Native:** RETRY / RESTART TOR: a full Tor restart (TorService.restart), answered with the snapshot.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string}
- **In P1:** `{"tor":"none","progress":0,"everUp":false,"unprotected":false,"transport":"direct","orbot":"none","firstSetup":false,"vpn":false,"orbotKey":false,"network":"unknown"}`. Nothing to restart: the same no-Tor snapshot as privacy.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:396`, `Foxy/Bridge/FoxyBridge.swift:868-873`

#### `unprotected` — P2

- **Shape:** page → native, action: `{ action: "unprotected", id, on }`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:15218`
- **Native:** CONTINUE WITHOUT TOR. on=true asks a native alert ("Continue without Tor?") that no script can answer, and is ignored while Tor is running; on=false clears it with no question. Session only; Tor connecting clears it. Pushes _privacy and answers the snapshot.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string}
- **In P1:** `{"tor":"none","progress":0,"everUp":false,"unprotected":false,"transport":"direct","orbot":"none","firstSetup":false,"vpn":false,"orbotKey":false,"network":"unknown"}`. The unprotected path is not built until P2 (ROUTE.md): on is ignored, and the no-Tor snapshot answers.
- **Timing:** The page waits up to 600 s for an on=true answer (a person is reading an alert).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:397`, `Foxy/Bridge/FoxyBridge.swift:878-902`, `Foxy/Bridge/FoxyBridge.swift:252-264`, `Foxy/Network/Route.swift:42-45`

#### `orbotRefresh` — LATER

- **Shape:** page → native, action: `{ action: "orbotRefresh", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:15210`
- **Native:** Poll Orbot now instead of on its 15 s timer; answers OrbotLink.name.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "none" / "needs-access" / "bypass"
- **In P1:** `none`. No Orbot link on Android yet (ANDROID-MAPPING decision 3): Orbot is reported as none.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:393`, `Foxy/Bridge/FoxyBridge.swift:847-851`, `Foxy/Network/OrbotLink.swift:79-85`

#### `orbotAccess` — LATER

- **Shape:** page → native, action: `{ action: "orbotAccess", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:15214`
- **Native:** Opens Orbot's own permission flow for a key with bypass.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "opened" / "no"
- **In P1:** `no`. No Orbot link on Android yet (ANDROID-MAPPING decision 3): nothing is opened.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:392`, `Foxy/Bridge/FoxyBridge.swift:834-838`

#### `_privacy` — P2

- **Shape:** native → page, push: `window.FoxyWallet._privacy(snapshot)`
- **Payload:** `tor`: string, `progress`: integer, `everUp`: boolean, `unprotected`: boolean, `transport`: string, `orbot`: string, `firstSetup`: boolean, `vpn`: boolean, `orbotKey`: boolean, `network`: string
- **Called from:** `Web/foxy-wallet.js:15055`
- **Native:** Route snapshot on every Tor, Orbot or network change (and once at start without Tor). The page whitelists these fields.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:157-160`, `Foxy/Bridge/FoxyBridge.swift:89-118`

### Network (Route)

#### `mintRequest` — P2

- **Shape:** page → native, action: `{ action: "mintRequest", id, url, method, headers, circuit, body }`
- **Payload:** `url`: string, `method`: string, `headers`: object|null, `circuit`: string, `body`: string
- **Called from:** `Web/foxy-wallet.js:25939`
- **Native:** Every mint, LNURL / lightning-address and block-explorer request the page makes (cashu-ts customRequest, nativeRequest). In order: url parses; Route.urlProblem (https, or http to .onion; no localhost, IP literal, single label, .local family; .onion needs Tor); method GET or POST only; Route.available or the refusal; HostApprovals (an unknown host is asked about in a native alert first); then sent on the Tor circuit labelled `circuit` (16-64 lowercase hex, else a fresh one). Only content-type and accept headers are passed; generic Tor-Browser User-Agent. Plain http to an .onion goes through Tor SOCKS by hand (GET only). Answer capped at 4 MB. Redirects re-checked and never to another host. NSURLErrorBadURL retried once after 0.4 s.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "<HTTP status>\n<body>"
- **Errors:** "That is not a mint address Foxy will use."; "That is not a request Foxy will send."; "Foxy is not connected to Tor."; "You did not allow Foxy to contact <host>."; "The answer from <host> was too large."; "An .onion address needs Tor, and Foxy is not connected to Tor."; "<host> could not be asked over Tor: <why>"; "Foxy's private connection was rebuilt mid-request. Try again."
- **In P1:** the error "Foxy is not connected to Tor.". Route.available is false in P1 (ROUTE.md): the refusal, nothing contacted.
- **Timing:** The page waits 65 s. A swap/melt/mint/restore POST is counted in moneyInFlight so backgrounding waits up to 20 s for it.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:398`, `Foxy/Bridge/FoxyBridge.swift:909-944`, `Foxy/Bridge/FoxyBridge.swift:950-953`, `Foxy/Bridge/FoxyBridge.swift:1382-1432`, `Foxy/Bridge/FoxyBridge.swift:1447-1486`, `Foxy/Bridge/FoxyBridge.swift:1505-1510`, `Foxy/Network/Route.swift:318-324`, `Foxy/Network/Route.swift:287-309`, `Foxy/Network/Route.swift:16-26`

#### `price` — P2

- **Shape:** page → native, action: `{ action: "price", id, agree }`
- **Payload:** `agree`: boolean?
- **Called from:** `Web/foxy-wallet.js:15649`
- **Native:** BTC/USD from six operators in order (mempool onion, Kraken, Bitstamp, blockchain.info, Gemini, Coinbase), two at a time, first usable answer wins; agree=true needs two within 2% (a payment typed in dollars). 4 s per source direct, 20 s on a bridge, 10/25 s for the onion; whole walk 12/30 s (25/45 s with agree).
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (USD per BTC as a decimal string; "" with an error when none answered)
  The text is USD per BTC as a decimal string; "" with an error when none answered.
- **Errors:** "Foxy is not connected to Tor."; "no price source answered"; "the price sources did not agree"
- **In P1:** the error "Foxy is not connected to Tor.". Route.available is false in P1 (ROUTE.md): the refusal, nothing contacted.
- **Timing:** Page waits 14 s (34 s bridged; 29/49 s with agree).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:363`, `Foxy/Bridge/FoxyBridge.swift:601-690`, `Foxy/Bridge/FoxyBridge.swift:593`

#### `candles` — P2

- **Shape:** page → native, action: `{ action: "candles", id, g, span }`
- **Payload:** `g`: integer, `span`: integer
- **Called from:** `Web/foxy-wallet.js:15728`
- **Native:** Chart history from Kraken OHLC, interval chosen from the fixed set so the range fits 720 candles. g clamped to 60..86400 s, span to 3600 s..4 years.
- **Reply:** FoxyWallet._scanResult(id, text, error) — JSON array of array of [unixSeconds, close] number pairs, oldest first
- **Errors:** "Foxy is not connected to Tor."; "unreadable candles"; "could not encode candles"
- **In P1:** the error "Foxy is not connected to Tor.". Route.available is false in P1 (ROUTE.md): the refusal, nothing contacted.
- **Timing:** Page waits 16 s; native times the request out at 12 s.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:364`, `Foxy/Bridge/FoxyBridge.swift:696-748`, `Foxy/Bridge/FoxyBridge.swift:715`

#### `hostsKnown` — P2

- **Shape:** page → native, action: `{ action: "hostsKnown", id, hosts }`
- **Payload:** `hosts`: string[]
- **Called from:** `Web/foxy-wallet.js:26785`
- **Native:** Once, the first time a version with host approvals runs: the mints and contact domains the wallet already used are carried over as approved (each approves only itself). Later calls change nothing.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **In P1:** `ok`. No host approvals until P2, so nothing to carry over.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:399`, `Foxy/Bridge/FoxyBridge.swift:957-960`, `Foxy/Network/Route.swift:744`

#### `noWebRTC` — P1

- **Shape:** native → page, script: `every RTC* constructor defined as undefined, non-writable, non-configurable; document start, ALL frames`
- **Called from:** native
- **Native:** WebRTC STUN is UDP no request block sees; it is removed before any page script so it cannot leak the IP.
- **iOS:** `Foxy/FoxyWebView.swift:138-141`, `Foxy/FoxyWebView.swift:450-458`

### Seed and keychain

#### `seedStatus` — P1

- **Shape:** page → native, action: `{ action: "seedStatus", id, quiet }`
- **Payload:** `quiet`: boolean?
- **Called from:** `Web/foxy-wallet.js:2481`, `Web/foxy-wallet.js:22228`
- **Native:** Whether a seed is saved. quiet=true answers from presence alone (no biometric prompt); without it the seed is read (may prompt). "passcode": false only on a phone with no device credential. Finding a seed closes the seedMigrate window.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {exists: boolean, passcode: boolean?}
- **Errors:** "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:371`, `Foxy/Bridge/NativeSeedBridge.swift:254-257`, `Foxy/Keychain/SeedActions.swift:154-168`

#### `seedCreate` — P1

- **Shape:** page → native, action: `{ action: "seedCreate", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:2488`
- **Native:** Twelve words made natively (128 bits from the platform CSPRNG, the bundled wordlist used only if its SHA-256 is pinned) and saved; never replaces a saved seed; counters left from a gone seed are moved aside first. One seed change at a time.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {created: boolean}
- **Errors:** "a seed already exists"; "the seed could not be read"; "the wordlist could not be checked"; "the seed could not be made"; "Another change to the seed is still waiting. Nothing was changed."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:372`, `Foxy/Bridge/NativeSeedBridge.swift:273-279`, `Foxy/Keychain/SeedActions.swift:188-214`, `Foxy/Bridge/FoxyBridge.swift:225-234`

#### `seedMigrate` — P1

- **Shape:** page → native, action: `{ action: "seedMigrate", id, words }`
- **Payload:** `words`: string|string[]
- **Called from:** `Web/foxy-wallet.js:2582`
- **Native:** The one-time move of words a pre-keychain page still holds (localStorage foxy.seed.v1). Only a valid BIP-39 phrase (<=1000 bytes, or <=24 words), written only inside the one-time window and only where no seed is saved; otherwise same/different, changing nothing. Never answers words.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {migrated: boolean, same: boolean?, different: boolean?}
- **Errors:** "bad request"; "no migration here"; "the wordlist could not be checked"; "Another change to the seed is still waiting. Nothing was changed."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:373`, `Foxy/Bridge/NativeSeedBridge.swift:294-310`, `Foxy/Bridge/NativeSeedBridge.swift:201`, `Foxy/Keychain/SeedActions.swift:253-260`

#### `seedShow` — P3

- **Shape:** page → native, action: `{ action: "seedShow", id, verify }`
- **Payload:** `verify`: boolean
- **Called from:** `Web/foxy-wallet.js:19547`
- **Native:** Native screen showing the words (behind biometric/device credential), with the verify quiz when verify=true. Answers only whether the quiz was finished, whether delete was asked, whether the words were revealed. One seed screen at a time; none within 10 s of a cancelled read. A cancelled/closed screen answers {"verified": false}.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {verified: boolean, delete: boolean?, revealed: boolean?}
- **Errors:** "a seed screen is already open"; "try again in a moment"; "no seed"; "the seed could not be read"; "Nothing to present the screen from."
- **Timing:** Page waits 30 min (PERSON_MS).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:385`, `Foxy/Bridge/NativeSeedBridge.swift:425-478`

#### `seedEnter` — P3

- **Shape:** page → native, action: `{ action: "seedEnter", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:19563`
- **Native:** Native screen where the person types words to restore. Answers a random 128-bit candidate id, never the words. Two candidates at most, dropped on unlock-forget, page reload or background.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {candidate: string}
- **Errors:** "a seed screen is already open"; "too many candidates"; "cancelled"; "the words could not be read"; "the wordlist could not be checked"; "Nothing to present the screen from."
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:386`, `Foxy/Bridge/NativeSeedBridge.swift:481-518`

#### `seedAdopt` — P3

- **Shape:** page → native, action: `{ action: "seedAdopt", id, candidate }`
- **Payload:** `candidate`: string
- **Called from:** `Web/foxy-wallet.js:3115`
- **Native:** Replace the saved seed with a candidate: native alert "Replace this wallet's seed?" (gives up after 120 s as no), then device credential; counters set aside and raised to 1000 short of the furthest range served.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {adopted: boolean, same: boolean?}
- **Errors:** "bad request"; "unknown candidate"; "Nothing was changed."; "Another change to the seed is still waiting. Nothing was changed."
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:387`, `Foxy/Bridge/NativeSeedBridge.swift:526-549`, `Foxy/Bridge/FoxyBridge.swift:198-220`

#### `seedCandidateForget` — P3

- **Shape:** page → native, action: `{ action: "seedCandidateForget", id, candidate }`
- **Payload:** `candidate`: string
- **Called from:** `Web/foxy-wallet.js:19583`
- **Native:** Forget a candidate id.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {forgotten: boolean}
- **Errors:** "bad request"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:388`, `Foxy/Bridge/NativeSeedBridge.swift:566-574`

#### `seedWipe` — P3

- **Shape:** page → native, action: `{ action: "seedWipe", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:15000`, `Web/foxy-wallet.js:25876`
- **Native:** Native alert "Delete this wallet's seed?", then device credential; the seed is checked gone before counters move, and a new seed is always made.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {wiped: boolean, created: boolean}
- **Errors:** "Nothing was erased."; "Another change to the seed is still waiting. Nothing was changed."
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:389`, `Foxy/Bridge/NativeSeedBridge.swift:585-604`, `Foxy/Keychain/SeedActions.swift:586-600`

#### `seedProtection` — P3

- **Shape:** page → native, action: `{ action: "seedProtection", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25850`
- **Native:** Which protection the person chose: "device" (behind biometric/credential) or "none".
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {mode: string, passcode: boolean}
- **In P1:** `{"mode":"none","passcode":true}`. Before P3 the seed is kept under protection none only (SeedVault, P1); device protection and the passcode question come with BiometricPrompt in P3. Asked as the wallet's home screen opens, so a host that connects (P2) meets it.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:390`, `Foxy/Bridge/NativeSeedBridge.swift:260-262`, `Foxy/Keychain/SeedStore.swift:521-524`

#### `seedProtect` — P3

- **Shape:** page → native, action: `{ action: "seedProtect", id, mode }`
- **Payload:** `mode`: string
- **Called from:** `Web/foxy-wallet.js:25857`
- **Native:** Move the seed between the two protections. Taking protection off reads the seed from behind it first; written and read back where it is going before the old copy is deleted.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {mode: string}
- **Errors:** "bad request"; "the seed could not be read"
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:391`, `Foxy/Bridge/NativeSeedBridge.swift:267-270`, `Foxy/Keychain/SeedActions.swift:578-584`

#### `biometric` — P3

- **Shape:** page → native, action: `{ action: "biometric", id, reason, passcode }`
- **Payload:** `reason`: string, `passcode`: boolean
- **Called from:** `Web/foxy-wallet.js:26107`
- **Native:** Biometric (or device credential when passcode=true, the screen lock only) for the PIN lock. The reason shown is one of native's ("Unlock Foxy", "Leave POS mode"); anything else shows "Unlock Foxy". A yes is remembered so the following seed read does not ask again.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "yes" / "no" / "unavailable"
- **Timing:** Page treats no answer in 60 s as "no".
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:367`, `Foxy/Bridge/FoxyBridge.swift:804-822`, `Foxy/Bridge/FoxyBridge.swift:826`

### Counters, NUT-13 secrets and P2PK lock keys

#### `countersImport` — P1

- **Shape:** page → native, action: `{ action: "countersImport", id, counters }`
- **Payload:** `counters`: object
- **Called from:** `Web/foxy-wallet.js:2680`
- **Native:** Once per install: the page's old NUT-13 counters ({keysetId: next}) raised into the native file (max of each). At most 256 keysets.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {counters: object}
- **Errors:** "bad request"; "counters were already imported"; "too many keysets"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:374`, `Foxy/Bridge/NativeSeedBridge.swift:316-321`, `Foxy/Bridge/NativeSeedBridge.swift:173`

#### `counterReserve` — P1

- **Shape:** page → native, action: `{ action: "counterReserve", id, keysetId, count }`
- **Payload:** `keysetId`: string, `count`: integer
- **Called from:** `Web/foxy-wallet.js:3001`, `Web/foxy-wallet.js:3010`, `Web/foxy-wallet.js:3089`
- **Native:** Reserve `count` (0..1000) counters for a 00/01 keyset and answer their NUT-13 secrets and blinding factors (hex). The range is written to the counter file BEFORE the secrets are answered. count=0 is a peek: answers start=next with empty lists and needs no seed.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"; "too many keysets"; "outside the keyset's counters"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:375`, `Foxy/Bridge/NativeSeedBridge.swift:339-344`, `Foxy/Bridge/NativeSeedBridge.swift:76`, `Foxy/Keychain/SeedActions.swift:305-320`, `Foxy/Keychain/NUT13.swift:167-172`

#### `counterReserveAt` — P1

- **Shape:** page → native, action: `{ action: "counterReserveAt", id, keysetId, start, count }`
- **Payload:** `keysetId`: string, `start`: integer, `count`: integer
- **Called from:** `Web/foxy-wallet.js:3010`
- **Native:** Reserve an explicit range: refused if it starts below next (already issued) or more than 100 past it.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]}
- **Errors:** "bad request"; "range already issued"; "too far ahead"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:376`, `Foxy/Bridge/NativeSeedBridge.swift:349-354`, `Foxy/Keychain/SeedActions.swift:325-339`

#### `counterAdvance` — P1

- **Shape:** page → native, action: `{ action: "counterAdvance", id, keysetId, next }`
- **Payload:** `keysetId`: string, `next`: integer
- **Called from:** `Web/foxy-wallet.js:3004`, `Web/foxy-wallet.js:3053`
- **Native:** Move a counter up, never down; at most 100 past next, or to the end of a range restoreSecrets served this session.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, next: integer}
- **Errors:** "bad request"; "too far ahead"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:377`, `Foxy/Bridge/NativeSeedBridge.swift:330-335`, `Foxy/Keychain/SeedActions.swift:295-302`

#### `counterSnapshot` — P1

- **Shape:** page → native, action: `{ action: "counterSnapshot", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:3034`
- **Native:** Reads every keyset's next counter; moves nothing; needs no seed.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {counters: object}
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:378`, `Foxy/Bridge/NativeSeedBridge.swift:324-326`

#### `restoreSecrets` — P1

- **Shape:** page → native, action: `{ action: "restoreSecrets", id, keysetId, start, count, candidate }`
- **Payload:** `keysetId`: string, `start`: integer, `count`: integer, `candidate`: string?
- **Called from:** `Web/foxy-wallet.js:2739`, `Web/foxy-wallet.js:3197`, `Web/foxy-wallet.js:3198`
- **Native:** Secrets for a restore scan, moving no counter: for the saved seed only ranges ending at most 1000 past next; for a typed-words candidate (seedEnter) contiguously from 0 up to 20000. Notes the furthest range served (counterAdvance may reach it; seedAdopt raises counters to 1000 short of it).
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]}
- **Errors:** "bad request"; "outside the restore window"; "unknown candidate"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:379`, `Foxy/Bridge/NativeSeedBridge.swift:361-366`, `Foxy/Bridge/NativeSeedBridge.swift:104`, `Foxy/Keychain/SeedActions.swift:347-366`

#### `p2pkReserve` — P1

- **Shape:** page → native, action: `{ action: "p2pkReserve", id, count }`
- **Payload:** `count`: integer
- **Called from:** `Web/foxy-wallet.js:6930`
- **Native:** Reserve 0..64 lock-key indexes on NUT-13's P2PK path m/129373'/10'/0'/0'/i (last level normal) and answer their compressed PUBLIC keys; the index is written first. count=0 is a peek.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {start: integer, next: integer, pubkeys: string[]}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:382`, `Foxy/Bridge/NativeSeedBridge.swift:372-377`, `Foxy/Keychain/SeedActions.swift:377-391`, `Foxy/Keychain/P2PK.swift:206-209`

#### `p2pkPubkeys` — P1

- **Shape:** page → native, action: `{ action: "p2pkPubkeys", id, start, count }`
- **Payload:** `start`: integer, `count`: integer
- **Called from:** `Web/foxy-wallet.js:6903`, `Web/foxy-wallet.js:8543`
- **Native:** Public keys only, 1..300 at a time, no index past 20000; moves nothing but notes the scan's reach (which p2pkKey may then answer).
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {start: integer, next: integer, pubkeys: string[]}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:383`, `Foxy/Bridge/NativeSeedBridge.swift:381-386`, `Foxy/Keychain/SeedActions.swift:407-417`

#### `p2pkKey` — P1

- **Shape:** page → native, action: `{ action: "p2pkKey", id, index }`
- **Payload:** `index`: integer
- **Called from:** `Web/foxy-wallet.js:7018`, `Web/foxy-wallet.js:8556`, `Web/foxy-wallet.js:24899`
- **Native:** One lock key's private half, only within 1000 of the last index reserved or inside a range this session's scan served. The chain code is never answered.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {index: integer, privkey: string, pubkey: string}
- **Errors:** "bad request"; "outside the lock-key window"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:384`, `Foxy/Bridge/NativeSeedBridge.swift:391-396`, `Foxy/Keychain/SeedActions.swift:427-456`, `Foxy/Keychain/P2PK.swift:215-217`

### Clipboard, paste and share

#### `pasteStart` — P3

- **Shape:** page → native, action: `{ action: "pasteStart", id, x, y, w, h }`
- **Payload:** `x`: integer, `y`: integer, `w`: integer, `h`: integer
- **Called from:** `Web/foxy-wallet.js:26400`
- **Native:** iOS: a near-invisible UIPasteControl over the page's PASTE button (paste with no system prompt). Rect must be >30x20, <=90% width, <=50% height, inside the view, below its top third. The pasted text (never a seed phrase) is pushed with _pasted.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Nothing to attach the button to."; "That is not a place a paste button goes."
- **In P1:** the error "Nothing to attach the button to.". No native paste control before P3 (BRIDGE.md §4 item 4 decides it there): the page's own PASTE button stays. The page posts this without waiting on the answer, whenever a PASTE button is on screen, which a host that connects (P2) shows.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:403`, `Foxy/Bridge/FoxyBridge.swift:1163-1237`

#### `pasteStop` — P3

- **Shape:** page → native, action: `{ action: "pasteStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:26410`
- **Native:** Removes the paste control (kept a moment longer, not taking touches, while a paste prompt is up).
- **Reply:** FoxyWallet._scanResult(id, text, error) — ""
- **In P1:** ``. Nothing to take down before P3; posted without waiting on the answer, as pasteStart is.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:404`, `Foxy/Bridge/FoxyBridge.swift:983-1016`

#### `clipboard` — P3

- **Shape:** page → native, action: `{ action: "clipboard", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:26267`
- **Native:** Reads the clipboard, text only; a seed phrase reads as empty (SeedPasteboard.forPage).
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the clipboard text, "" when empty or when it is a seed phrase)
  The text is the clipboard text, "" when empty or when it is a seed phrase.
- **Timing:** Page gives up after 4 s with "".
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:365`, `Foxy/Bridge/FoxyBridge.swift:750-752`, `Foxy/Bridge/FoxyBridge.swift:1346-1357`

#### `copy` — P3

- **Shape:** page → native, action: `{ action: "copy", id, text, sensitive }`
- **Payload:** `text`: string, `sensitive`: boolean
- **Called from:** `build/app/17-split-bill-and-copy.js:436`
- **Native:** Copy text. sensitive=true (tokens, anything that is money): device-only and expires after 120 s.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "nothing to copy"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:366`, `Foxy/Bridge/FoxyBridge.swift:760-783`

#### `share` — P3

- **Shape:** page → native, action: `{ action: "share", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:26292`
- **Native:** System share sheet with plain text only (no URL item, no link preview fetched).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Nothing to share."; "Something else is already open."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:406`, `Foxy/Bridge/FoxyBridge.swift:1018-1020`, `Foxy/Bridge/FoxyBridge.swift:1311-1339`

#### `_pasted` — P3

- **Shape:** native → page, push: `window.FoxyWallet._pasted(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:26415`
- **Native:** Text pasted through the paste control; a seed phrase arrives as "".
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:1225`

### Scanner

#### `scan` — P3

- **Shape:** page → native, action: `{ action: "scan", id, prompt }`
- **Payload:** `prompt`: string
- **Called from:** `Web/foxy-wallet.js:24077`
- **Native:** Full-screen camera scanner. The prompt is shown only if it is one of native's fixed prompts ("Scan an ecash token", "Scan the mint address"), else blank.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the decoded text)
  The text is the decoded text.
- **Errors:** "Nothing to present the scanner from."; "<camera error>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:358`, `Foxy/Bridge/FoxyBridge.swift:548-550`, `Foxy/Bridge/FoxyBridge.swift:1083`, `Foxy/Bridge/FoxyBridge.swift:1062-1075`

#### `previewStart` — P3

- **Shape:** page → native, action: `{ action: "previewStart", id, x, y, w, h }`
- **Payload:** `x`: integer, `y`: integer, `w`: integer, `h`: integer
- **Called from:** `Web/foxy-wallet.js:26360`
- **Native:** Live camera laid over the page at a rectangle (CSS px) the page measured: finite, larger than 40x40, inside the web view. Each decoded code is pushed with _liveScan; a refusal is also pushed with _previewRefused.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Nothing to show the camera in."; "That pane is too small for a camera (<w>x<h>)."; "The camera pane must be on screen (...)"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:401`, `Foxy/Bridge/FoxyBridge.swift:1099-1141`

#### `previewStop` — P3

- **Shape:** page → native, action: `{ action: "previewStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:26370`
- **Native:** Stops the live preview.
- **Reply:** FoxyWallet._scanResult(id, text, error) — ""
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:402`, `Foxy/Bridge/FoxyBridge.swift:973-976`

#### `_previewRefused` — P3

- **Shape:** native → page, push: `window.FoxyWallet._previewRefused(why)`
- **Payload:** `why`: string
- **Called from:** `Web/foxy-wallet.js:26376`
- **Native:** previewStart was refused (as well as its error reply).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:1107`

#### `_liveScan` — P3

- **Shape:** native → page, push: `window.FoxyWallet._liveScan(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:26382`
- **Native:** A code the live preview decoded.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:1135`

### Haptics

#### `haptic` — P3

- **Shape:** page → native, message: `{ haptic }` (no action, no id)
- **Payload:** `haptic`: string
- **Called from:** `build/app/05-device-shell.js:80`, `build/app/26d-tap.js:1261`
- **Native:** A message with no action and no id: { haptic: kind }, dispatched before the action guard. Kinds seen: success, error, warning, pulse, long, heavy, triple; anything else (tap, light) is a light impact. The page rate-limits (one per kind per 1.2 s).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:456-466`, `Foxy/Bridge/FoxyBridge.swift:304-347`

### Notifications

#### `notify` — P3

- **Shape:** page → native, action: `{ action: "notify", id, kind }`
- **Payload:** `kind`: string
- **Called from:** `Web/foxy-wallet.js:26349`
- **Native:** A local notification. kind is "sent" or "received"; the words are native's ("Payment sent"/"Payment received", titled "Foxy"), no amount. At most one every 3 s.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok" / "throttled"
- **Errors:** "unknown notification kind"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:405`, `Foxy/Bridge/FoxyBridge.swift:1254-1305`, `Foxy/Bridge/FoxyBridge.swift:1247`

### Other platform doors

#### `openSettings` — P3

- **Shape:** page → native, action: `{ action: "openSettings", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:26280`
- **Native:** Opens this app's own system settings page and no other URL (a camera or Bluetooth permission refused once).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "No settings page to open."; "Settings would not open."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:407`, `Foxy/Bridge/FoxyBridge.swift:1026-1036`

#### `openCompany` — P3

- **Shape:** page → native, action: `{ action: "openCompany", id, which }`
- **Payload:** `which`: string
- **Called from:** `Web/foxy-wallet.js:26286`
- **Native:** Opens one of four fixed sites by name (cashapp, strike, river, flash) in the system browser, outside Foxy and outside Tor. The page never supplies a URL.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Not one of the listed sites."; "The browser would not open."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:408`, `Foxy/Bridge/FoxyBridge.swift:1049-1060`, `Foxy/Bridge/FoxyBridge.swift:1042-1047`

### Onion inbox and post

#### `inboxOpen` — P3

- **Shape:** page → native, action: `{ action: "inboxOpen", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:22514`
- **Native:** An ephemeral v3 onion address (key made and discarded by Tor) forwarding to a 127.0.0.1 listener, plus a Nostr nprofile listening on up to four relays over Tor, for the payment request on screen. A spare is kept warm. Android (ENG-672): DeliveryBridge.inboxOpen, a port of handleInboxOpen: the open inbox, else the warm spare handed over (and another started), else a new OnionInbox (network/OnionInbox.kt) on a 127.0.0.1 listener with ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach Port=80,127.0.0.1:<port> through tor-android's control link (TorMachine.addOnion; null unless Tor is up). The answer is {"onion"} alone: the Nostr half is LATER (ENG-672 follow-up), and the page takes an answer without nostr as it takes an iPhone whose relays would not listen. While Foxy is put away no address is made and the answer is "No onion address: Tor is not ready."
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {onion: string?, nostr: string?}
- **Errors:** "No address: Tor is not ready."; "No onion address: Tor is not ready."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:410`, `Foxy/Bridge/FoxyBridge+Delivery.swift:111-150`, `Foxy/Bridge/FoxyBridge+Delivery.swift:61-73`, `Foxy/Bridge/FoxyBridge+Delivery.swift:31-50`, `Foxy/Network/OnionInbox.swift:71-136`, `Foxy/Tor/TorService.swift:1775-1789`

#### `inboxClose` — P3

- **Shape:** page → native, action: `{ action: "inboxClose", id, hold }`
- **Payload:** `hold`: number
- **Called from:** `Web/foxy-wallet.js:22532`
- **Native:** End the address (after `hold` seconds, 0..180, if a payment may be on its way) and the Nostr inbox. Android (ENG-672): DeliveryBridge.inboxClose: hold clamped to 0..180 s (a number only), the retiring inbox closed (DEL_ONION) when the hold ends, then the warm spare restarted. Putting Foxy away closes every inbox (the one on screen, the spare and the retiring ones) and drops their answers before Tor parks, as closeInboxForBackground; the bridge going with its page (WebHost.destroy) does the same.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:411`, `Foxy/Bridge/FoxyBridge+Delivery.swift:208-230`, `Foxy/Bridge/FoxyBridge+Delivery.swift:157-183`, `Foxy/FoxyWebView.swift:1022`, `Foxy/Tor/TorService.swift:1793-1798`

#### `inboxAnswer` — P3

- **Shape:** page → native, action: `{ action: "inboxAnswer", id, answer, status, text }`
- **Payload:** `answer`: string, `status`: integer, `text`: string
- **Called from:** `Web/foxy-wallet.js:22836`
- **Native:** Answer a payment that arrived (_requestPaid): status 200, 409 or 422 only, 300 characters of text. Android (ENG-670, ENG-672): one table of the answers owed, by key, the onion's and the tap's (InboxAnswers, as FoxyBridge.swift's inboxAnswers with tapAnswerKeys); a key nothing is waiting on is "No payment is waiting on that answer." The answer's JSON is {"ok":true} for 200, else {"error":<text>} written as Apple's JSONSerialization writes it (the slash escaped), the text cut at 300 characters as a person counts them. An onion payment's key is 32 lowercase hex, kept 110 s, and the inbox writes iOS's answer byte for byte (HTTP/1.1 <status> <reason>, Content-Length, Connection: close, Content-Type: application/json when there is a body); its keys go when Foxy is put away. A tap's (_requestPaid(.., "tap")) is kept across Foxy being put away and dropped 30 s after it was pushed, and its status and the JSON's reason become the M6 the payer reads (status 200 with no reason; any other status clamped to 422).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "No payment is waiting on that answer."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:412`, `Foxy/Bridge/FoxyBridge+Delivery.swift:234-246`, `Foxy/Bridge/FoxyBridge+Delivery.swift:248-258`, `Foxy/Network/OnionInbox.swift:229-243`

#### `onionPost` — P3

- **Shape:** page → native, action: `{ action: "onionPost", id, url, body, timeout }`
- **Payload:** `url`: string, `body`: string, `timeout`: number
- **Called from:** `Web/foxy-wallet.js:8873`
- **Native:** POST a payment to another phone's onion: http:// v3 .onion only, no user/query/fragment, through Tor SOCKS, never the open connection; body <=256 kB, answer cut at 64 kB. timeout in ms, clamped to 3..120 s. Android (ENG-672): DeliveryBridge.onionPost and network/OnionPost.kt: the URL is read by hand (http only as written, a v3 onion host, no user, query or fragment, the path as encoded and printable ASCII, a port that fits), SOCKS5 through Socks5SocketFactory with the circuit label as the login and the onion by name (ATYP 3), then iOS's request byte for byte (POST <path> HTTP/1.1, Host, Content-Type: application/json, Content-Length, Connection: close) and one answer read to the close, cut at 64 kB. Tor only: refused with Route's refusal when Tor is not up, even with CONTINUE WITHOUT TOR on, and (Android) while Route's door is shut as Foxy is put away.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "<HTTP status>\n<body>"
- **Errors:** "That is not an onion address."; "That payment is too large to send."; "Foxy is not connected to Tor."; "Their phone could not be reached over Tor."; "Tor refused the connection."; "Their phone did not answer in time."; "The connection to their phone broke."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:413`, `Foxy/Bridge/FoxyBridge+Delivery.swift:292-323`, `Foxy/Network/OnionPost.swift:18-47`, `Foxy/Network/OnionPost.swift:50-63`, `Foxy/Network/OnionPost.swift:205-241`

#### `_requestPaid` — P3

- **Shape:** native → page, push: `window.FoxyWallet._requestPaid(body, answerId[, "tap"])`
- **Payload:** `body`: string, `answerId`: string, `via`: string?
- **Called from:** `Web/foxy-wallet.js:22823`
- **Native:** A payment arrived for the request on screen (onion inbox, Nostr inbox with answerId "", or a tap with "tap"). The page redeems it and answers with inboxAnswer. Android: pushed with a fresh 32-hex answer key for a payment posted to an onion inbox (DeliveryBridge, ENG-672), and as `_requestPaid(body, key, "tap")` the moment a tap payment has crossed the link (TapBridge, ENG-670). Not yet the Nostr form (answerId "", the ENG-672 follow-up).
- **iOS:** `Foxy/Bridge/FoxyBridge+Delivery.swift:104`, `Foxy/Bridge/FoxyBridge+Delivery.swift:256`, `Foxy/Bridge/FoxyBridge+Tap.swift:121`

### Nostr (LATER)

#### `nostrSend` — LATER

- **Shape:** page → native, action: `{ action: "nostrSend", id, target, body }`
- **Payload:** `target`: string, `body`: string
- **Called from:** `Web/foxy-wallet.js:8900`
- **Native:** NIP-17/59 gift-wrap the payment for the nprofile in a request and publish to at most five wss relays it names (fallback: damus, nos.lol, primal, minibits), each over Tor on its own circuit; never the open connection.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (how many relays accepted it, as a decimal string)
  The text is how many relays accepted it, as a decimal string.
- **Errors:** "That request names no Nostr address."; "That payment is too large to send over Nostr."; "No relay took the payment: <why>"; "The payment could not be sent over Nostr."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:414`, `Foxy/Bridge/FoxyBridge+Delivery.swift:262-288`, `Foxy/Nostr/NostrDelivery.swift:18-19`

### Bluetooth tap to pay

#### `tapReceiveStart` — P3

- **Shape:** page → native, action: `{ action: "tapReceiveStart", id, payload, early }`
- **Payload:** `payload`: string, `early`: boolean
- **Called from:** `Web/foxy-wallet.js:24159`
- **Native:** Advertise the invoice/payment request on screen over BLE (service UUID only). Payment, quote and stage events come back as _requestPaid(.., "tap"), _tapQuote, _tap. Android (ENG-670, bridge/TapBridge.kt, tap/TapReceiver.kt): a non-empty payload iOS would refuse (TapProtocol.isPayload) is refused with its words and nothing else. Otherwise "ok", and a receiver held since the last tapReceiveStop takes the payload and early flag and stays on the air; one that has handed its offer to a payer is refused "That payment is still going through. A moment." while a payment or change is crossing, keeps that payer's offer and answers "ok" while the payer holds it, and is otherwise retired for a new one. A new receiver (a fresh random service UUID) reports its radio after the reply, as iOS's peripheral manager does: _tap receive stage unsupported (no adapter, or one that cannot advertise), denied (BLUETOOTH_ADVERTISE/CONNECT refused; asked by the system's own question if never answered), off, or, once armed, the GATT server up and searching when the advertisement is on the air. The GATT service is iOS's: out (notify), in (write with response), near left and near right (notify), UUIDs SHA-256(label ‖ service)[0..16], a CCCD on each notify door, no encryption or bonding, every read refused; long (prepared) writes are queued per central and taken on the execute; notifications go one at a time, at most MTU - 3 bytes. The advertisement is the service UUID and nothing else: connectable, no name, no TX power, no manufacturer data. Nothing advertises while Foxy is not in front: the activity stopping takes the receiver off the air (and lets it go unless a payment is crossing), and its start puts a new one on the air with what the page last offered. The page asks this by itself on every receive screen (auto-arm); an "Unknown action" reply put UNKNOWN ACTION: TAPRECEIVESTART in the TAP card and was retried every 1.2 s while the screen was up (ENG-656).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Not something a payer could pay."; "That payment is still going through. A moment."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:416`, `Foxy/Bridge/FoxyBridge+Tap.swift:22-147`, `Foxy/Bluetooth/TapLink.swift:26-33`, `Foxy/Bluetooth/TapLink.swift:506-509`, `Foxy/Bluetooth/TapLink.swift:481-504`, `Foxy/Bluetooth/TapLink.swift:669-698`, `Foxy/Bluetooth/TapLink.swift:850-872`

#### `tapReceiveStop` — P3

- **Shape:** page → native, action: `{ action: "tapReceiveStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24162`
- **Native:** Stop advertising. Android (ENG-670): the receiver is stopped and let go, unless a payment or change is crossing, when it is held (off the air) until that is answered or the result wait (25 s) and 2 s more have passed; "ok" either way.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:417`, `Foxy/Bridge/FoxyBridge+Tap.swift:149-165`

#### `tapPayStart` — P3

- **Shape:** page → native, action: `{ action: "tapPayStart", id, now }`
- **Payload:** `now`: boolean?
- **Called from:** `Web/foxy-wallet.js:24178`, `Web/foxy-wallet.js:24182`
- **Native:** Search for a receiver close enough to be touching and answer its offer plus the four-digit code both phones show; now=true only nudges a search already running and answers "ok". Android (ENG-671): the payer listens (TapPayer: a BLE scan with no hardware filter (ENG-679: the all-zero service filter matched nothing on the Pixel), then iOS's rules for a Foxy advert, the near verdict, the quiet link, the near doors) and answers {payload, code} once the nearest receiver's offer is in. A radio that is off, refused or missing is pushed as _tap({side:"pay",stage}) and the call is not answered, so the page has nothing to re-ask (ENG-658); it is answered "Started again." by the next tapPayStart and "Stopped." by tapPayStop or the page going. now=true answers "ok" at once. Nothing scans while Foxy is put away. The p1 stub is for P1 and P2 hosts only.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json-or-text {payload: string, code: string} or "ok"
- **Errors:** "Started again."; "Stopped."
- **In P1:** the error "Stopped.". No Bluetooth tap on Android before LATER: every search ends at once, as a stopped one does, and the page backs off its re-listen (up to a minute). A funded wallet's home screen listens with no one acting, so a host that connects (P2) meets it.
- **Timing:** Page waits 30 min for a payer to touch.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:418`, `Foxy/Bridge/FoxyBridge+Tap.swift:167-218`

#### `tapSend` — P3

- **Shape:** page → native, action: `{ action: "tapSend", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:24221`
- **Native:** The payment, over the link this tap already holds, <=64 KB. Android (ENG-671): "There is nothing to hand over." for an empty body; "That phone is no longer connected." with no listen or no held link; otherwise the receiver's M6 text as it sealed it, or, when the link went first, why (the TapPayerLink.Handover.Lost words).
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the receiver's answer)
  The text is the receiver's answer.
- **Errors:** "There is nothing to hand over."; "That phone is no longer connected."; "A payment is already going over."; "That payment could not be sealed."; "That phone went away before it said what became of the payment."; "That phone stopped taking the payment."; "That phone did not answer after Foxy came back."; "Nobody is connected any more."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:427`, `Foxy/Bridge/FoxyBridge+Tap.swift:223-245`

#### `tapChange` — P3

- **Shape:** page → native, action: `{ action: "tapChange", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:24273`
- **Native:** Change token back to the payer over the same link; resolves when the payer says it kept it (7 s), or late via _tapChangeKeptLate. Android (ENG-670): "There is no change to send." for an empty body, "That phone is no longer connected." with no receiver or no link (nothing sealed), "The payer's phone did not say it kept the change." after 7 s, and a later "kept" as _tapChangeKeptLate.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "There is no change to send."; "That phone is no longer connected."; "The payer's phone did not say it kept the change."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:419`, `Foxy/Bridge/FoxyBridge+Tap.swift:348-399`

#### `tapChangeKept` — P3

- **Shape:** page → native, action: `{ action: "tapChangeKept", id, kept }`
- **Payload:** `kept`: boolean
- **Called from:** `Web/foxy-wallet.js:24370`
- **Native:** The payer's page saying it wrote the change down (M8). Android (ENG-671): "ok" and M8 sent when kept is true (nothing is sent for false: the receiver's own 7 s wait answers it); "No change is waiting to be kept." when no _tapChange is waiting.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "No change is waiting to be kept."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:420`, `Foxy/Bridge/FoxyBridge+Tap.swift:474-485`

#### `tapAsking` — P3

- **Shape:** page → native, action: `{ action: "tapAsking", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24285`
- **Native:** The receiver is asking its person about an offline payment (M9). Android (ENG-670): "ok" when M9 went to the payer (the result wait becomes 90 s), "no link" when there is none.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok" / "no link"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:421`, `Foxy/Bridge/FoxyBridge+Tap.swift:412-421`

#### `tapChangeDue` — P3

- **Shape:** page → native, action: `{ action: "tapChangeDue", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24359`
- **Native:** This payment owes change: keep the link. Android (ENG-670): "ok" always; a linked receiver is held (busy) until the payer says it kept the change or goes.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:422`, `Foxy/Bridge/FoxyBridge+Tap.swift:463-468`

#### `tapQuote` — P3

- **Shape:** page → native, action: `{ action: "tapQuote", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:24294`
- **Native:** Payer's price quote (M10). Android (ENG-671): "ok" when M10 was sealed and sent over the held link, else "That phone is no longer connected."
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "That phone is no longer connected."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:423`, `Foxy/Bridge/FoxyBridge+Tap.swift:429-438`

#### `tapTerms` — P3

- **Shape:** page → native, action: `{ action: "tapTerms", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:24299`
- **Native:** Receiver agreeing the price (M11). Android (ENG-670): "ok" when M11 was sealed and sent to the payer, else "That phone is no longer connected."
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "That phone is no longer connected."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:424`, `Foxy/Bridge/FoxyBridge+Tap.swift:445-454`

#### `tapPayStop` — P3

- **Shape:** page → native, action: `{ action: "tapPayStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24432`
- **Native:** Stop searching/connecting; a waiting tapPayStart is answered with the error "Stopped.". Android (ENG-671): "ok" always; the listen and its link let go (a word still going out goes first).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:425`, `Foxy/Bridge/FoxyBridge+Tap.swift:267-272`

#### `bluetoothAsk` — P3

- **Shape:** page → native, action: `{ action: "bluetoothAsk", id }`
- **Payload:** —
- **Called from:** `Web/foxy-tor-gate.js:2071`
- **Native:** First-install screen: put the OS Bluetooth permission question up, answer the state (also pushed to window.__foxyBluetoothAnswer). Scans and advertises nothing. Android (ENG-670, bridge/TapBridge.kt): the system's question for BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (API 31+; API 28-30 has them at install), never BLUETOOTH_SCAN or location. The state, read once the question is answered: no Bluetooth adapter, or one that is on and cannot advertise (BluetoothAdapter.isMultipleAdvertisementSupported false), is "unsupported" and nothing is asked; a permission refused is "denied"; the adapter off or turning off is "off"; on is "allowed"; one turning on is waited for, and "unknown" after 60 s with no state. Native pushes window.__foxyBluetoothAnswer(state) and then answers state, in iOS's order, so the page writes foxy.bt.asked and closes the screen. A reply without the push left the screen up for good (ENG-656): the page leaves only on the push, and every later APPROVE returns early on `asking`.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "allowed" / "denied" / "off" / "unsupported" / "unknown"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:426`, `Foxy/Bridge/FoxyBridge+Tap.swift:254-265`, `Foxy/Bridge/FoxyBridge+Tap.swift:526-539`

#### `shakeStart` — P3

- **Shape:** page → native, action: `{ action: "shakeStart", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24150`
- **Native:** While set, a shake is pushed as _tap({side:"receive",stage:"shake"}). Android (ENG-670): the accelerometer is listened to only while set and Foxy is in front; two jolts of 1.6 g or more within 0.6 s are a shake, then 1.2 s of quiet (tap/Motion.kt ShakeDetector). Idempotent, "ok".
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:441`, `Foxy/Bridge/FoxyBridge+Tap.swift:279-285`, `Foxy/Bridge/FoxyBridge+Tap.swift:310-318`, `Foxy/FoxyWebView.swift:45-48`

#### `shakeStop` — P3

- **Shape:** page → native, action: `{ action: "shakeStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24150`
- **Native:** Clears the shake flag. Android (ENG-670): and lets the accelerometer go. Idempotent, "ok".
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:442`, `Foxy/Bridge/FoxyBridge+Tap.swift:287-293`

#### `_tapQuote` — P3

- **Shape:** native → page, push: `window.FoxyWallet._tapQuote(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:24305`
- **Native:** M10 arrived on the receiver. Android (ENG-670): pushed with the payer's text as it opened.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:130`

#### `_tapChange` — P3

- **Shape:** native → page, push: `window.FoxyWallet._tapChange(token)`
- **Payload:** `token`: string
- **Called from:** `Web/foxy-wallet.js:24374`
- **Native:** Change arrived on the payer; it answers with tapChangeKept. Android (ENG-671): pushed with the token as it opened, for the listen that made the link; the answer is tapChangeKept.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:194`

#### `_tapTerms` — P3

- **Shape:** native → page, push: `window.FoxyWallet._tapTerms(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:24342`
- **Native:** M11 arrived on the payer. Android (ENG-671): pushed with the receiver's text as it opened.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:200`

#### `__foxyBluetoothAnswer` — P3

- **Shape:** native → page, global: `window.__foxyBluetoothAnswer(state)`
- **Payload:** `state`: string
- **Called from:** `Web/foxy-tor-gate.js:2060`
- **Native:** The Bluetooth question was answered (defined by the gate's first-install screen). Android (ENG-670): pushed with the state bluetoothAsk answers (allowed, denied, off, unsupported or unknown) on every bluetoothAsk, before its reply; "unsupported" on a phone with no Bluetooth LE peripheral, as ENG-656 answered on every phone.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:259`

#### `_turned` — P3

- **Shape:** native → page, push: `window.FoxyWallet._turned(upsideDown)`
- **Payload:** `upsideDown`: boolean
- **Called from:** `Web/foxy-wallet.js:24446`
- **Native:** Device turned portrait/upside-down (the receive screen turns itself for the payer across the table). Android (ENG-670): OrientationEventListener while Foxy is in front (the activity stays portrait): pushed each time the phone comes to portrait (false) or upside down (true), within 30° of each; face up, face down and landscape leave it as it was (tap/Motion.kt TurnDetector).
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:303-307`, `Foxy/FoxyWebView.swift:42`, `Foxy/FoxyWebView.swift:38-43`

#### `_tapChangeKeptLate` — P3

- **Shape:** native → page, push: `window.FoxyWallet._tapChangeKeptLate()`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24248`
- **Native:** The payer said it kept the change after tapChange had already answered. Android (ENG-670): pushed once, for a "kept" that comes after tapChange's 7 s.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:380`

#### `_tap` — P3

- **Shape:** native → page, push: `window.FoxyWallet._tap({side, stage, code?, edge?, pct?})`
- **Payload:** `side`: string, `stage`: string, `code`: string?, `edge`: string?, `pct`: integer?
- **Called from:** `Web/foxy-wallet.js:24454`
- **Native:** Tap progress. side receive|pay; stage one of searching, far, connecting, linked, sending, receiving, code, asking, gone, shake, nearby, version, off, denied, unsupported. code only for four digits (stage code), edge left|right only with nearby, pct 0-100 only with sending or receiving. Android (ENG-670): the receive side, as iOS's TapReceiver says it: off/denied/unsupported for the radio, searching when on the air, nearby (edge) and far for payers on the near doors, connecting at a payer's first word, code with the four digits, receiving (pct) for a large payment, gone, version, and shake while shake to TAP is armed. A phone with no Bluetooth LE peripheral says {side:"receive",stage:"unsupported"} once per receiver, after the reply to the tapReceiveStart that made it (ENG-656). The pay side (ENG-671), as iOS's TapPayer says it: off/denied/unsupported for the radio (the listen goes on), nearby (edge: this screen's) and far for a receiver near enough to be told about, connecting when the verdict picks one, gone then searching when that link comes to nothing, linked with the offer, sending (pct) for a large payment, asking while a person decides (M9), and version.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:487-498`, `Foxy/Bluetooth/TapLink.swift:5-24`

### NFC card

#### `cardBegin` — P3

- **Shape:** page → native, action: `{ action: "cardBegin", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:25566`
- **Native:** Open the NFC session with one printable line (<=120 chars) of the page's text; answers when a card is there. One session at a time. Android (ENG-667): bridge/CardBridge.kt over a flashcard/CardLink: on a phone with NFC, reader mode (NfcAdapter.enableReaderMode, NFC-A/B, the NDEF check skipped) with Foxy's own card sheet over the page (Android has no system one: "Ready to Scan", the line, Cancel), on the resumed activity only; in a debug build with no NFC (the emulator), or started with the FoxyCardSim extra, the getfoxy/card simulator over loopback (SimCardLink, ports 47431-47434). The sheet waits 60 s for a card ("the session timed out"); Cancel or Back is "the session was cancelled"; no NFC adapter, or NFC switched off, is "NFC is not available on this phone". With no line from the page the sheet says "Hold the card to the back of the phone" (iOS: the top). Foxy put away while the sheet waits ends it "Foxy was put away" (the begin answered "the session was cancelled"); with a card in hand the card is lost.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "<CardLinkError words>"
- **Timing:** Page waits 63 s.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:429`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:27-64`, `Foxy/Flashcard/CardLink.swift:26-44`, `Foxy/Flashcard/CardLink.swift:177-182`, `Foxy/Flashcard/CardLink.swift:288-290`

#### `cardSend` — P3

- **Shape:** page → native, action: `{ action: "cardSend", id, apdu }`
- **Payload:** `apdu`: string
- **Called from:** `Web/foxy-wallet.js:25491`
- **Native:** One APDU (hex) and its answer. Only SELECT of Foxy's own applet AID (F0 46 4F 58 59 43 41 52 44 01), then only that applet's 31 instructions (CardGate.instructions, CLA B0): 01, 10, 11, 13-1A, 20, 22-26, 30-32, 34-37, 40-45, 51. 1A (GET_DLEQ, card software 1.20) reads back the mint's NUT-12 proof a card keeps with an unspent piece, two places per command; 30 with P1 = 1 (LOAD_PROOF) writes a piece with its proof, 96 more bytes of data (e, s, r), still a short APDU. 12, 33 and 50, which the applet has, stay out. Never logged: one carries the PIN. Android (ENG-667): flashcard/CardGate.kt is CardGate.swift's 31 byte for byte (CardGateTest also holds it to the page's CARD_INS, both ways, and tries every class, instruction and P1/P2). SET_TAG (52, card software 1.19) is not carried, as CardGate.swift at the pin does not carry it, though the card's FOXY-CARD-SPEC.md section 7 (docs/FOXY-CARD-SPEC.md:1070-1072) says a phone's gate should. That section's list is stale against iOS in ten more: 17, 18, 19, 22-26, 35 and 51 (RESET_CARD) are carried here and by CardGate.swift, and the page sends them, but section 7 does not name them. All eleven are for getfoxy/iOS and getfoxy/card to settle together; Android follows iOS. The command is carried over IsoDep (10 s for an answer) or the simulator's "apdu HEX"; an answer under two bytes, a dropped card or the simulator's "gone" is "the tag was lost" with a `_card` lost push, and nothing more is carried until cardAgain finds a card and the applet is chosen again.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the response APDU, hex)
  The text is the response APDU, hex.
- **Errors:** "That is not a command for a card."; "That is not a command Foxy sends to a card."; "<CardLinkError words>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:430`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:69-95`, `Foxy/Flashcard/CardGate.swift:23-51`, `Foxy/Flashcard/CardGate.swift:33`

#### `cardSay` — P3

- **Shape:** page → native, action: `{ action: "cardSay", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:25495`
- **Native:** Change the line on the NFC sheet. Android (ENG-667): the line on Foxy's card sheet (CardGate.line: one printable line, 120 characters), and a `_card` say push; "ok" with or without a session.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:431`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:122-128`

#### `cardAgain` — P3

- **Shape:** page → native, action: `{ action: "cardAgain", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:25512`
- **Native:** The card left part way: look for it again; the applet must be selected again. Android (ENG-667): reader mode is turned off and on again, so a card still in the field is found afresh (iOS: restartPolling), with a new 60 s wait; the sheet shows the page's line ("Hold the card here again" with none). No session: "the session was cancelled".
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "<CardLinkError words>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:432`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:101-119`, `Foxy/Flashcard/CardLink.swift:109`

#### `cardEnd` — P3

- **Shape:** page → native, action: `{ action: "cardEnd", id, text, error }`
- **Payload:** `text`: string?, `error`: string?
- **Called from:** `Web/foxy-wallet.js:25473`, `Web/foxy-wallet.js:25504`
- **Native:** Close the sheet with a word or a reason. Android (ENG-667): a tick and the word (1.2 s), or the reason in red (2.4 s), then the sheet goes; reader mode off and the card let go. A sheet still waiting for a card is answered "the session was cancelled". "ok" with or without a session.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:433`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:133-145`

#### `cardOwnerKey` — P3

- **Shape:** page → native, action: `{ action: "cardOwnerKey", id, key }`
- **Payload:** `key`: string
- **Called from:** `Web/foxy-wallet.js:10044`
- **Native:** The P-256 owner PUBLIC key (04||X||Y hex) derived from the seed for one card (its 33-byte compressed key, hex). Android (ENG-666): SeedBridge.cardOwnerKey on the seed executor, refused "bad request" before the seed is read for anything but a 66-hex compressed key; the same key, byte for byte, as an iPhone restored from the same words (app/src/test CardOwnerTest, the iOS tests/fixtures/card-owner-vectors.json). P-256 through the platform's JCA only.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {pub: string}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:436`, `Foxy/Bridge/NativeSeedBridge.swift:402-407`, `Foxy/Keychain/SeedActions.swift:467-477`, `Foxy/Bridge/NativeSeedBridge.swift:156-159`, `Foxy/Keychain/NUT13.swift:495-513`

#### `cardOwnerSign` — P3

- **Shape:** page → native, action: `{ action: "cardOwnerSign", id, key, label, nonce, value }`
- **Payload:** `key`: string, `label`: string, `nonce`: string, `value`: string
- **Called from:** `Web/foxy-wallet.js:10068`
- **Native:** ECDSA P-256 DER signature over "FoxyCard/"+label, nonce, value for one of six labels (change-pin, set-limit, set-owner, set-card, load, reset), each with its value shape. Android (ENG-666): SeedBridge.cardOwnerSign on the seed executor; the six labels and their value shapes are CardOwner.Label, and anything else (lock, tag, time, a bad nonce or value) is "bad request" before the seed is read. Signatures verify against the key cardOwnerKey answers, as iOS's do.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {sig: string}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:437`, `Foxy/Bridge/NativeSeedBridge.swift:412-417`, `Foxy/Keychain/SeedActions.swift:489-499`, `Foxy/Bridge/NativeSeedBridge.swift:165-168`, `Foxy/Keychain/NUT13.swift:459-469`, `Foxy/Keychain/NUT13.swift:516-525`

#### `cardTime` — P3

- **Shape:** page → native, action: `{ action: "cardTime", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:10191`
- **Native:** The phone clock (u32 seconds) signed by the INTERIM time key built into the app, for cards of software <=1.14. Android (ENG-666): FoxyBridge answers it with InterimCardTime, iOS's interim key pair byte for byte, over "FoxyCard/time" and the four-byte time. A card of software 1.15 and on takes block headers instead, which the page fetches itself over mintRequest and sends with cardSend; nothing of that is native's.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {time: integer, sig: string}
- **Errors:** "The clock on this phone is not a time a card can be told."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:439`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:152-158`, `Foxy/Flashcard/CardTime.swift:110-113`, `Foxy/Flashcard/CardTime.swift:43-46`, `Foxy/Flashcard/CardTime.swift:53-56`

#### `_card` — P3

- **Shape:** native → page, push: `window.FoxyWallet._card({stage, text})`
- **Payload:** `stage`: string, `text`: string
- **Called from:** `Web/foxy-wallet.js:25446`
- **Native:** NFC session progress: stage connected|say|end|lost. Android (ENG-667): pushed by CardBridge through CardGate.progressScript (the text cut to a line and quoted as JSON, `<` `>` `&` and every control escaped) only while the link is the session in hand: connected ("Reading the card") when a card answers, say on cardSay, end when the page closes it, lost when the card leaves mid-command, the wait runs out, the sheet is cancelled or Foxy is put away with a card in hand.
- **iOS:** `Foxy/Flashcard/CardGate.swift:144-150`

#### `_cardTapped` — P3

- **Shape:** native → page, push: `window.FoxyWallet && window.FoxyWallet._cardTapped && window.FoxyWallet._cardTapped()`
- **Payload:** —
- **Called from:** `Web/index.html:393`, `build/app/06-wallet-boot-and-restore.js:202`, `build/app/26f-flashcard.js:207`
- **Native:** A Foxy card was held to the phone (its NDEF address https://getfoxy.io/card, CardAddress), or foxy://card in a debug build. Nothing of the address is passed. The page decides by its screen (`cardTapRule`): act on a home-like screen (opens the Flashcard screen and the card sheet), keep for up to 60 s while locked or starting, ignore anywhere a payment is in progress. The evaluated value is the page's answer: the JS value true once the app has registered it on the wallet (after the lock is decided), anything else means not yet. Native asks again every 0.3 s and gives up 60 s after the latest address arrived (an address that arrives while one waits is the same tap and renews the 60 s); never while the splash or app-switcher cover is up, never after a return before `_resumed` has been told, and never while the page's process is gone: after it ends (pageGone) nothing is asked until the reload has finished (pageLoaded); told once per tap. Set by the app (index.html), not declared in foxy-wallet.js. Android (ENG-668), in every build: CardTapActivity, an invisible door (Theme.NoDisplay, no history, no Recents entry, a task of its own), filters Android's tag dispatch (NDEF_DISCOVERED) and links (VIEW, BROWSABLE, autoVerify false until getfoxy.io publishes an assetlinks.json for the release key) for exactly https://getfoxy.io/card, never MainActivity: flashcard/CardTapRoute.kt starts a new page when none is alive, hands the tap to the page when MainActivity is the top of its task (singleTop, onNewIntent, a one-shot extra with nothing of the address), and drops it while anything is over the page (a seed screen, the scanner, the screen-lock confirmation), as iOS ignores a tap while anything is up, so a card never makes a second page; flashcard/CardTapIntake.kt holds each to flashcard/CardAddress.kt (the iOS rules byte for byte) and drops an intent replayed from Recents, an address while the page has a card session open, and one within 3 s of reader mode going off (the card just read, still resting on the phone): a card being read never re-opens the Flashcard screen. flashcard/CardTapDelivery.kt is the iOS state machine (0.3 s, 60 s from the latest address, once), per MainActivity: the cover is the splash (Android has no app-switcher cover, FLAG_SECURE), the return is told by PutAway after `_resumed`, pageGone is onRenderProcessGone and pageLoaded onPageFinished. The page's answer is read with evaluateJavascript, with no timer on it, as on iOS: a page slow to answer is not asked again, so `_cardTapped()` is never queued twice; a question out to a page whose process ended is forgotten (pageGone) and the reloaded page is asked.
- **Timing:** Retried every 0.3 s until the page answers true; given up 60 s after the latest address arrived; a tap while one waits renews it (CardTapDelivery.retryAfter, lapse; cardTapped sets since = now() on every tap, Foxy/Flashcard/CardTapDelivery.swift:64-67). The page keeps a tap it cannot act on yet for up to 60 s (CARD_TAP_KEEP_MS). Android: the same numbers (CardTapDelivery.LAPSE_MS, RETRY_AFTER_MS), and no timeout on a question, as on iOS.
- **iOS:** `Foxy/Flashcard/CardAddress.swift:64`, `Foxy/Flashcard/CardTapDelivery.swift:33`, `Foxy/Flashcard/CardTapDelivery.swift:35`, `Foxy/Flashcard/CardTapDelivery.swift:64-67`, `Foxy/Flashcard/CardTapDelivery.swift:77-79`, `Foxy/Flashcard/CardTapDelivery.swift:102-122`, `Foxy/FoxyWebView.swift:282-285`, `Foxy/FoxyWebView.swift:1178`, `Foxy/FoxyWebView.swift:1355`, `Foxy/FoxyWebView.swift:1414`
<!-- contract-detail:end -->

## 4. What the contract leaves to the architect

Found while writing this, and not decided here:

1. **`isDebug` has no caller.** iOS serves it; the page reads
   `window.FOXY_DEBUG` instead. Android can serve it for parity or drop it;
   the contract keeps it (P1, trivial).
2. **`{username}` is posted and ignored.** It is harmless today. If Android
   ever answers unknown shapes with an error, the page would not notice, but a
   strict listener that throws would. Keep it ignored.
3. **`log` carries every console line in every build.** The page's
   `mirrorConsole()` posts `log`, `warn` and `error` in Release too. iOS keeps
   them only in its redacting FieldLog. Android must redact the same way
   (`SECURITY-INVARIANTS.md` S10) and must not send them to logcat in release.
4. **`pasteStart` exists because of an iOS-only control.** Android has no
   `UIPasteControl` and no paste prompt to avoid: reading the clipboard on a
   tap is allowed in the foreground. The decision for P3 is whether `pasteStart`
   answers `"ok"` and does nothing (the page's own PASTE button then calls
   `clipboard`), or lays a native button over the rect as iOS does. The
   rectangle checks must be kept either way. **Decided (ENG-649):** a native
   button over the rect, which reads the clipboard only in its tap and pushes
   `_pasted`.
5. **`captured` and `_captured` / `_screenshot`.** Android cannot report
   mirroring the way `sceneCaptureState` does; `FLAG_SECURE` blocks capture
   instead, and Android 14's `Activity.ScreenCaptureCallback` reports
   screenshots. Proposed: `FLAG_SECURE` always, `captured` answers `"no"`,
   `_screenshot` pushed from the callback on API 34+. **Built (ENG-649).**
6. **`biometric` reasons are iOS wording.** The fixed list ("Unlock Foxy",
   "Leave POS mode") maps to `BiometricPrompt` titles; keep the list fixed.
7. **`openCompany` leaves Tor by design.** It opens a browser outside the app.
   Android must use an `ACTION_VIEW` intent with the fixed URL table and nothing
   from the page.
8. **Debug-only deep links** (`foxy://receive?...`): iOS registers the scheme in
   Debug builds only. Android should register the intent filter only in the
   debug source set. **Built (ENG-649):** `app/src/debug/AndroidManifest.xml`;
   the release manifest has no scheme (`ManifestTest`). `foxy://card` (since
   getfoxy/iOS `9832f7d`) names no screen: it is a card tap said by hand, which
   iOS passes on as `_cardTapped`. Android reads it (`web/DeepLink.kt`,
   `DeepLink.Link.CardTap`) and hands it to `CardTapDelivery` like a card's own
   address (ENG-668). The release manifest's only https filters are the card's
   address (NDEF and an unverified link, `ManifestTest`); nothing of it reaches
   the page.
9. **`seedProtection` "device" vs "none".** iOS has two keychain items;
   Android's equivalent is two Keystore keys, one requiring user authentication
   (`setUserAuthenticationRequired`, StrongBox where available). The wording of
   the protection answer is unchanged.
10. **The P1 network stub.** P1 ships before Tor. The contract's answer for
    `mintRequest`, `price` and `candles` with no route is the refusal
    "Foxy is not connected to Tor.", and `unprotected` can only be turned on
    through a native dialog. P1 must answer exactly that and contact nothing
    (`ROUTE.md`).

## 5. The table

One row per entry of `conformance/contract.json` (array of `{ name, direction,
kind, domain, phase, payloadSchema, reply, iosRef, pageRef, behaviour, timing? }`).

<!-- contract-table:begin -->
| name | direction | kind | phase | payload | reply | iOS |
|---|---|---|---|---|---|---|
| `covered` | page → native | action | P1 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:362 |
| `log` | page → native | action | P1 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:359 |
| `fieldLog` | page → native | action | P1 | `bad`: boolean | JSON array of {at: integer, text: string, bad: boolean} | Foxy/Bridge/FoxyBridge.swift:360 |
| `fieldLogClear` | page → native | action | P1 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:361 |
| `isDebug` | page → native | action | P1 | — | "yes" / "no" | Foxy/Bridge/FoxyBridge.swift:400 |
| `awake` | page → native | action | P1 | `on`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:443 |
| `captured` | page → native | action | P3 | — | "yes" / "no" | Foxy/Bridge/FoxyBridge.swift:395 |
| `privacy` | page → native | action | P2 | — | json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string} | Foxy/Bridge/FoxyBridge.swift:394 |
| `torRetry` | page → native | action | P2 | — | json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string} | Foxy/Bridge/FoxyBridge.swift:396 |
| `unprotected` | page → native | action | P2 | `on`: boolean | json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string} | Foxy/Bridge/FoxyBridge.swift:397 |
| `orbotRefresh` | page → native | action | LATER | — | "none" / "needs-access" / "bypass" | Foxy/Bridge/FoxyBridge.swift:393 |
| `orbotAccess` | page → native | action | LATER | — | "opened" / "no" | Foxy/Bridge/FoxyBridge.swift:392 |
| `mintRequest` | page → native | action | P2 | `url`: string, `method`: string, `headers`: object\|null, `circuit`: string, `body`: string | "<HTTP status>\n<body>" | Foxy/Bridge/FoxyBridge.swift:398 |
| `price` | page → native | action | P2 | `agree`: boolean? | text (USD per BTC as a decimal string; "" with an error when none answered) | Foxy/Bridge/FoxyBridge.swift:363 |
| `candles` | page → native | action | P2 | `g`: integer, `span`: integer | JSON array of array of [unixSeconds, close] number pairs, oldest first | Foxy/Bridge/FoxyBridge.swift:364 |
| `hostsKnown` | page → native | action | P2 | `hosts`: string[] | "ok" | Foxy/Bridge/FoxyBridge.swift:399 |
| `seedStatus` | page → native | action | P1 | `quiet`: boolean? | json {exists: boolean, passcode: boolean?} | Foxy/Bridge/FoxyBridge.swift:371 |
| `seedCreate` | page → native | action | P1 | — | json {created: boolean} | Foxy/Bridge/FoxyBridge.swift:372 |
| `seedMigrate` | page → native | action | P1 | `words`: string\|string[] | json {migrated: boolean, same: boolean?, different: boolean?} | Foxy/Bridge/FoxyBridge.swift:373 |
| `countersImport` | page → native | action | P1 | `counters`: object | json {counters: object} | Foxy/Bridge/FoxyBridge.swift:374 |
| `counterReserve` | page → native | action | P1 | `keysetId`: string, `count`: integer | json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]} | Foxy/Bridge/FoxyBridge.swift:375 |
| `counterReserveAt` | page → native | action | P1 | `keysetId`: string, `start`: integer, `count`: integer | json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]} | Foxy/Bridge/FoxyBridge.swift:376 |
| `counterAdvance` | page → native | action | P1 | `keysetId`: string, `next`: integer | json {keysetId: string, next: integer} | Foxy/Bridge/FoxyBridge.swift:377 |
| `counterSnapshot` | page → native | action | P1 | — | json {counters: object} | Foxy/Bridge/FoxyBridge.swift:378 |
| `restoreSecrets` | page → native | action | P1 | `keysetId`: string, `start`: integer, `count`: integer, `candidate`: string? | json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]} | Foxy/Bridge/FoxyBridge.swift:379 |
| `p2pkReserve` | page → native | action | P1 | `count`: integer | json {start: integer, next: integer, pubkeys: string[]} | Foxy/Bridge/FoxyBridge.swift:382 |
| `p2pkPubkeys` | page → native | action | P1 | `start`: integer, `count`: integer | json {start: integer, next: integer, pubkeys: string[]} | Foxy/Bridge/FoxyBridge.swift:383 |
| `p2pkKey` | page → native | action | P1 | `index`: integer | json {index: integer, privkey: string, pubkey: string} | Foxy/Bridge/FoxyBridge.swift:384 |
| `seedShow` | page → native | action | P3 | `verify`: boolean | json {verified: boolean, delete: boolean?, revealed: boolean?} | Foxy/Bridge/FoxyBridge.swift:385 |
| `seedEnter` | page → native | action | P3 | — | json {candidate: string} | Foxy/Bridge/FoxyBridge.swift:386 |
| `seedAdopt` | page → native | action | P3 | `candidate`: string | json {adopted: boolean, same: boolean?} | Foxy/Bridge/FoxyBridge.swift:387 |
| `seedCandidateForget` | page → native | action | P3 | `candidate`: string | json {forgotten: boolean} | Foxy/Bridge/FoxyBridge.swift:388 |
| `seedWipe` | page → native | action | P3 | — | json {wiped: boolean, created: boolean} | Foxy/Bridge/FoxyBridge.swift:389 |
| `seedProtection` | page → native | action | P3 | — | json {mode: string, passcode: boolean} | Foxy/Bridge/FoxyBridge.swift:390 |
| `seedProtect` | page → native | action | P3 | `mode`: string | json {mode: string} | Foxy/Bridge/FoxyBridge.swift:391 |
| `scan` | page → native | action | P3 | `prompt`: string | text (the decoded text) | Foxy/Bridge/FoxyBridge.swift:358 |
| `previewStart` | page → native | action | P3 | `x`: integer, `y`: integer, `w`: integer, `h`: integer | "ok" | Foxy/Bridge/FoxyBridge.swift:401 |
| `previewStop` | page → native | action | P3 | — | "" | Foxy/Bridge/FoxyBridge.swift:402 |
| `pasteStart` | page → native | action | P3 | `x`: integer, `y`: integer, `w`: integer, `h`: integer | "ok" | Foxy/Bridge/FoxyBridge.swift:403 |
| `pasteStop` | page → native | action | P3 | — | "" | Foxy/Bridge/FoxyBridge.swift:404 |
| `clipboard` | page → native | action | P3 | — | text (the clipboard text, "" when empty or when it is a seed phrase) | Foxy/Bridge/FoxyBridge.swift:365 |
| `copy` | page → native | action | P3 | `text`: string, `sensitive`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:366 |
| `share` | page → native | action | P3 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:406 |
| `notify` | page → native | action | P3 | `kind`: string | "ok" / "throttled" | Foxy/Bridge/FoxyBridge.swift:405 |
| `biometric` | page → native | action | P3 | `reason`: string, `passcode`: boolean | "yes" / "no" / "unavailable" | Foxy/Bridge/FoxyBridge.swift:367 |
| `openSettings` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:407 |
| `openCompany` | page → native | action | P3 | `which`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:408 |
| `haptic` | page → native | message | P3 | `haptic`: string | none | Foxy/Bridge/FoxyBridge.swift:456-466 |
| `username` | page → native | message | P1 | `username`: string | none | Foxy/Bridge/FoxyBridge.swift:456-466 |
| `inboxOpen` | page → native | action | P3 | — | json {onion: string?, nostr: string?} | Foxy/Bridge/FoxyBridge.swift:410 |
| `inboxClose` | page → native | action | P3 | `hold`: number | "ok" | Foxy/Bridge/FoxyBridge.swift:411 |
| `inboxAnswer` | page → native | action | P3 | `answer`: string, `status`: integer, `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:412 |
| `onionPost` | page → native | action | P3 | `url`: string, `body`: string, `timeout`: number | "<HTTP status>\n<body>" | Foxy/Bridge/FoxyBridge.swift:413 |
| `nostrSend` | page → native | action | LATER | `target`: string, `body`: string | text (how many relays accepted it, as a decimal string) | Foxy/Bridge/FoxyBridge.swift:414 |
| `tapReceiveStart` | page → native | action | P3 | `payload`: string, `early`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:416 |
| `tapReceiveStop` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:417 |
| `tapPayStart` | page → native | action | P3 | `now`: boolean? | json-or-text {payload: string, code: string} or "ok" | Foxy/Bridge/FoxyBridge.swift:418 |
| `tapSend` | page → native | action | P3 | `body`: string | text (the receiver's answer) | Foxy/Bridge/FoxyBridge.swift:427 |
| `tapChange` | page → native | action | P3 | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:419 |
| `tapChangeKept` | page → native | action | P3 | `kept`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:420 |
| `tapAsking` | page → native | action | P3 | — | "ok" / "no link" | Foxy/Bridge/FoxyBridge.swift:421 |
| `tapChangeDue` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:422 |
| `tapQuote` | page → native | action | P3 | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:423 |
| `tapTerms` | page → native | action | P3 | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:424 |
| `tapPayStop` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:425 |
| `bluetoothAsk` | page → native | action | P3 | — | "allowed" / "denied" / "off" / "unsupported" / "unknown" | Foxy/Bridge/FoxyBridge.swift:426 |
| `shakeStart` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:441 |
| `shakeStop` | page → native | action | P3 | — | "ok" | Foxy/Bridge/FoxyBridge.swift:442 |
| `cardBegin` | page → native | action | P3 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:429 |
| `cardSend` | page → native | action | P3 | `apdu`: string | text (the response APDU, hex) | Foxy/Bridge/FoxyBridge.swift:430 |
| `cardSay` | page → native | action | P3 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:431 |
| `cardAgain` | page → native | action | P3 | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:432 |
| `cardEnd` | page → native | action | P3 | `text`: string?, `error`: string? | "ok" | Foxy/Bridge/FoxyBridge.swift:433 |
| `cardOwnerKey` | page → native | action | P3 | `key`: string | json {pub: string} | Foxy/Bridge/FoxyBridge.swift:436 |
| `cardOwnerSign` | page → native | action | P3 | `key`: string, `label`: string, `nonce`: string, `value`: string | json {sig: string} | Foxy/Bridge/FoxyBridge.swift:437 |
| `cardTime` | page → native | action | P3 | — | json {time: integer, sig: string} | Foxy/Bridge/FoxyBridge.swift:439 |
| `_scanResult` | native → page | push | P1 | `text`: string\|null, `error`: string\|null | none | Foxy/Bridge/FoxyBridge.swift:1488-1494 |
| `_privacy` | native → page | push | P2 | `tor`: string, `progress`: integer, `everUp`: boolean, `unprotected`: boolean, `transport`: string, `orbot`: string, `firstSetup`: boolean, `vpn`: boolean, `orbotKey`: boolean, `network`: string | none | Foxy/Bridge/FoxyBridge.swift:157-160 |
| `_captured` | native → page | push | P3 | `on`: boolean | none | Foxy/Bridge/FoxyBridge.swift:142-145 |
| `_screenshot` | native → page | push | P3 | — | none | Foxy/Bridge/FoxyBridge.swift:148-150 |
| `_previewRefused` | native → page | push | P3 | `why`: string | none | Foxy/Bridge/FoxyBridge.swift:1107 |
| `_liveScan` | native → page | push | P3 | `text`: string | none | Foxy/Bridge/FoxyBridge.swift:1135 |
| `_pasted` | native → page | push | P3 | `text`: string | none | Foxy/Bridge/FoxyBridge.swift:1225 |
| `_putAway` | native → page | push | P1 | `on`: boolean | none | Foxy/FoxyWebView.swift:1130 |
| `_tidying` | native → page | read | P1 | — | none | Foxy/FoxyWebView.swift:1096 |
| `_resumed` | native → page | push | P1 | `away`: integer | none | Foxy/FoxyWebView.swift:1174 |
| `__foxyUptime` | native → page | global | P1 | `s`: number, `p`: number | none | Foxy/FoxyWebView.swift:1198 |
| `deepLink` | native → page | global | P3 | `hash`: string | none | Foxy/FoxyWebView.swift:492-531 |
| `_requestPaid` | native → page | push | P3 | `body`: string, `answerId`: string, `via`: string? | none | Foxy/Bridge/FoxyBridge+Delivery.swift:104 |
| `_tapQuote` | native → page | push | P3 | `text`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:130 |
| `_tapChange` | native → page | push | P3 | `token`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:194 |
| `_tapTerms` | native → page | push | P3 | `text`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:200 |
| `__foxyBluetoothAnswer` | native → page | global | P3 | `state`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:259 |
| `_turned` | native → page | push | P3 | `upsideDown`: boolean | none | Foxy/Bridge/FoxyBridge+Tap.swift:303-307 |
| `_tapChangeKeptLate` | native → page | push | P3 | — | none | Foxy/Bridge/FoxyBridge+Tap.swift:380 |
| `_tap` | native → page | push | P3 | `side`: string, `stage`: string, `code`: string?, `edge`: string?, `pct`: integer? | none | Foxy/Bridge/FoxyBridge+Tap.swift:487-498 |
| `_card` | native → page | push | P3 | `stage`: string, `text`: string | none | Foxy/Flashcard/CardGate.swift:144-150 |
| `_cardTapped` | native → page | push | P3 | — | none | Foxy/Flashcard/CardAddress.swift:64 |
| `noWebRTC` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:138-141 |
| `noCallout` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:146-156 |
| `__foxyClock24` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:164 |
| `FOXY_DEBUG` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:173 |
| `FOXY_SHOW_WARNING` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:179 |
| `consoleSilence` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:191-204 |
| `__foxyPageHash` | native → page | global | P1 | `value`: string | none | Foxy/FoxyWebView.swift:393-396 |
<!-- contract-table:end -->
