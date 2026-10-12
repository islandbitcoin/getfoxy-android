# BRIDGE.md — the contract between the page and native

The Android app runs the iOS app's page (`core/web/`, byte for byte, pinned in
`core/PIN`) in an Android System WebView. Everything the page cannot do itself
— the network, the seed, the camera, the clipboard, notifications, Bluetooth,
NFC — it asks native for over one channel, and native answers or pushes back
into the page. This document is that channel, exhaustively, as the iOS app
implements it at getfoxy/iOS `5581c963dd29b28ebec547473ada532873d49a77`.
Android implements the same contract; nothing in `core/web` changes.

`conformance/contract.json` is the machine-readable form, and it is the
source: the per-item section and the closing table below are generated from it
(`node conformance/tools/contract-table.js --write`) and the suite fails when
they drift. Every `file:line` cited is checked against the pinned commit by
`conformance/test/contract.test.js` (refs into `Web/` on every run against
`core/web`; refs into `Foxy/` and `build/` when `FOXY_IOS_REF` names a clone,
which CI fetches).

**Counts at the pinned commit:** 74 actions and 2 actionless messages the page
posts; 21 pushes and reads native makes into the running page plus 7 scripts
and globals it injects before the page runs (28 native → page items); 104
contract entries in all.

Phases: **P1** shell (WebView, bridge, lifecycle, seed and counters in the
Keystore), **P2** Tor (Route, tor-android, IPtProxy, mint/price/chart
requests), **P3** platform (scanner, clipboard, share, notifications, haptics,
biometrics, native seed screens), **LATER** (onion inbox, Nostr, Bluetooth tap,
NFC card).

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
  stubs* (`conformance/test/flows.test.js`) boots on exactly that host and is
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
  (`conformance/test/flows.test.js`) goes from connecting to up, mints and
  melts, and fails on any later action with no stub.
- **P3 answers.** The P3 host (ENG-649) serves the P1, P2 and P3 actions,
  each P3 action by its real handler (the four with a `p1` stub, `captured`,
  `seedProtection`, `pasteStart` and `pasteStop`, never by the stub), and
  answers the `p1` stub of every LATER action that has one (`orbotRefresh`,
  `orbotAccess`, `tapPayStart`). The flow *P3: a host serving P1, P2 and P3
  actions …* (`conformance/test/flows.test.js`) asks every P3 action and fails
  on any LATER action with no stub.
- **The Bluetooth screen (ENG-656).** `bluetoothAsk` and
  `__foxyBluetoothAnswer` are P3, not LATER: the first-install Bluetooth
  screen's APPROVE posts `bluetoothAsk`, sets `asking` and leaves only when
  native calls `window.__foxyBluetoothAnswer(state)`, so an "Unknown action"
  reply left the screen up for good. Until BLE tap to pay lands (ENG-650) the
  Android host asks nothing and declares no Bluetooth permission: it pushes
  `__foxyBluetoothAnswer("unsupported")` and then answers `"unsupported"`,
  in iOS's order (`Foxy/Bridge/FoxyBridge+Tap.swift:254-265`). The flow
  *P3: APPROVE on the first-install Bluetooth screen …* presses it on a P3 host.
  The rest of the Bluetooth, onion, Nostr and card actions stay LATER: each is
  a request the page awaits with a timeout and a catch, not a push it waits
  for, so an "Unknown action" reply ends it.
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
- **Going to the background** (`FoxyWebView.swift:973-1104`): cover the screen,
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
- **Coming back** (`FoxyWebView.swift:1106-1147`): open the door, drop the
  seed kept for the putting-away, push `__foxyUptime`, tell Tor
  (`torResumed`), then push `_resumed(secondsAway)`. Take the cover off at once
  for an absence under a second, otherwise when the page says `covered` (4 s
  backstop).
- **The page's process dies** (`FoxyWebView.swift:1319-1325`): forget the
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
- **Called from:** `Web/foxy-wallet.js:23418`
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
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:362`, `Foxy/Bridge/FoxyBridge.swift:542-545`, `Foxy/FoxyWebView.swift:293`, `Foxy/FoxyWebView.swift:300`

#### `awake` — P1

- **Shape:** page → native, action: `{ action: "awake", id, on }`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:23473`
- **Native:** Hold the screen lit while a screen is looked at rather than touched (an invoice held up, the gate). Released by native whenever the app leaves the foreground.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:443`, `Foxy/Bridge/FoxyBridge.swift:519-530`, `Foxy/Bridge/FoxyBridge.swift:535-539`

#### `captured` — P3

- **Shape:** page → native, action: `{ action: "captured", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25621`
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
- **Called from:** `Web/foxy-wallet.js:25591`
- **Native:** Screen recording/mirroring started or stopped: the page hides the seed and secrets while it lasts.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:142-145`, `Foxy/FoxyWebView.swift:961-963`

#### `_screenshot` — P3

- **Shape:** native → page, push: `window.FoxyWallet._screenshot()`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25598`
- **Native:** A screenshot was taken (cannot be stopped on iOS).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:148-150`, `Foxy/FoxyWebView.swift:965-967`

#### `_putAway` — P1

- **Shape:** native → page, push: `window.FoxyWallet._putAway && window.FoxyWallet._putAway(true)`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:25104`
- **Native:** The app went to the background: the page starts its small-change top-up. Native then polls _tidying and Route.out every 0.25 s and keeps Tor up for up to 20 s while money moves, 3 s for other requests, then shuts the door.
- **iOS:** `Foxy/FoxyWebView.swift:1102`, `Foxy/FoxyWebView.swift:973-1104`, `Foxy/Network/Route.swift:200-206`

#### `_tidying` — P1

- **Shape:** native → page, read: `!!(window.FoxyWallet && window.FoxyWallet._tidying)`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25105`
- **Native:** Read by native (evaluateJavaScript with a result) while putting away: true while the change top-up is waiting or with the mint.
- **iOS:** `Foxy/FoxyWebView.swift:1068`

#### `_resumed` — P1

- **Shape:** native → page, push: `window.FoxyWallet._resumed(secondsAway)`
- **Payload:** `away`: integer
- **Called from:** `Web/foxy-wallet.js:25712`
- **Native:** Back from the background after `away` whole seconds (0 for focus loss without backgrounding). Sent after Tor is told (torResumed) and __foxyUptime is pushed.
- **iOS:** `Foxy/FoxyWebView.swift:1146`, `Foxy/FoxyWebView.swift:1106-1147`

#### `__foxyUptime` — P1

- **Shape:** native → page, global: `window.__foxyUptime = { s: systemUptimeSeconds, p: performance.now() }`
- **Payload:** `s`: number, `p`: number
- **Called from:** `Web/foxy-wallet.js:25451`
- **Native:** Monotonic device uptime, for the PIN back-off clock (22-screen-lock.js). Pushed on didFinish and on every return. Android: SystemClock.elapsedRealtime()/1000.
- **iOS:** `Foxy/FoxyWebView.swift:1166`

#### `deepLink` — P3

- **Shape:** native → page, global: `location.hash = 'foxy=<screen>&amt=..&unit=..&rail=..'`
- **Payload:** `hash`: string
- **Called from:** native
- **Native:** DEBUG BUILDS ONLY: foxy://receive?amt=50&unit=sat&rail=cashu rebuilt from a whitelist into the page's own hash. Release registers no scheme and has no handler; the page reads it only where FOXY_DEBUG is set.
- **iOS:** `Foxy/FoxyWebView.swift:476-507`, `Foxy/FoxyWebView.swift:520`

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
- **iOS:** `Foxy/FoxyWebView.swift:377-380`, `Foxy/FoxyWebView.swift:852-874`

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
- **Called from:** `Web/foxy-wallet.js:24960`
- **Native:** The ERRORS (bad=true) and LOGS screens: native and page lines, redacted, newest last.
- **Reply:** FoxyWallet._scanResult(id, text, error) — JSON array of {at: integer, text: string, bad: boolean}
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:360`, `Foxy/Bridge/FoxyBridge.swift:568-570`, `Foxy/Debug/FieldLog.swift:214-221`

#### `fieldLogClear` — P1

- **Shape:** page → native, action: `{ action: "fieldLogClear", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:24966`
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
- **Called from:** `Web/foxy-wallet.js:14685`
- **Native:** Reads the route snapshot (Route.snapshot). The same object is pushed with _privacy on every change.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string}
- **In P1:** `{"tor":"none","progress":0,"everUp":false,"unprotected":false,"transport":"direct","orbot":"none","firstSetup":false,"vpn":false,"orbotKey":false,"network":"unknown"}`. No Tor in P1: the snapshot a build without Tor answers (Route.snapshot, the #else branch), so the gate shows CANNOT CONNECT TO TOR.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:394`, `Foxy/Bridge/FoxyBridge.swift:855-857`, `Foxy/Network/Route.swift:479-512`

#### `torRetry` — P2

- **Shape:** page → native, action: `{ action: "torRetry", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:14691`
- **Native:** RETRY / RESTART TOR: a full Tor restart (TorService.restart), answered with the snapshot.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string}
- **In P1:** `{"tor":"none","progress":0,"everUp":false,"unprotected":false,"transport":"direct","orbot":"none","firstSetup":false,"vpn":false,"orbotKey":false,"network":"unknown"}`. Nothing to restart: the same no-Tor snapshot as privacy.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:396`, `Foxy/Bridge/FoxyBridge.swift:868-873`

#### `unprotected` — P2

- **Shape:** page → native, action: `{ action: "unprotected", id, on }`
- **Payload:** `on`: boolean
- **Called from:** `Web/foxy-wallet.js:14708`
- **Native:** CONTINUE WITHOUT TOR. on=true asks a native alert ("Continue without Tor?") that no script can answer, and is ignored while Tor is running; on=false clears it with no question. Session only; Tor connecting clears it. Pushes _privacy and answers the snapshot.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {tor: string, progress: integer, everUp: boolean, unprotected: boolean, transport: string, orbot: string, firstSetup: boolean, vpn: boolean, orbotKey: boolean, network: string}
- **In P1:** `{"tor":"none","progress":0,"everUp":false,"unprotected":false,"transport":"direct","orbot":"none","firstSetup":false,"vpn":false,"orbotKey":false,"network":"unknown"}`. The unprotected path is not built until P2 (ROUTE.md): on is ignored, and the no-Tor snapshot answers.
- **Timing:** The page waits up to 600 s for an on=true answer (a person is reading an alert).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:397`, `Foxy/Bridge/FoxyBridge.swift:878-902`, `Foxy/Bridge/FoxyBridge.swift:252-264`, `Foxy/Network/Route.swift:42-45`

#### `orbotRefresh` — LATER

- **Shape:** page → native, action: `{ action: "orbotRefresh", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:14700`
- **Native:** Poll Orbot now instead of on its 15 s timer; answers OrbotLink.name.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "none" / "needs-access" / "bypass"
- **In P1:** `none`. No Orbot link on Android yet (ANDROID-MAPPING decision 3): Orbot is reported as none.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:393`, `Foxy/Bridge/FoxyBridge.swift:847-851`, `Foxy/Network/OrbotLink.swift:79-85`

#### `orbotAccess` — LATER

- **Shape:** page → native, action: `{ action: "orbotAccess", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:14704`
- **Native:** Opens Orbot's own permission flow for a key with bypass.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "opened" / "no"
- **In P1:** `no`. No Orbot link on Android yet (ANDROID-MAPPING decision 3): nothing is opened.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:392`, `Foxy/Bridge/FoxyBridge.swift:834-838`

#### `_privacy` — P2

- **Shape:** native → page, push: `window.FoxyWallet._privacy(snapshot)`
- **Payload:** `tor`: string, `progress`: integer, `everUp`: boolean, `unprotected`: boolean, `transport`: string, `orbot`: string, `firstSetup`: boolean, `vpn`: boolean, `orbotKey`: boolean, `network`: string
- **Called from:** `Web/foxy-wallet.js:14545`
- **Native:** Route snapshot on every Tor, Orbot or network change (and once at start without Tor). The page whitelists these fields.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:157-160`, `Foxy/Bridge/FoxyBridge.swift:89-118`

### Network (Route)

#### `mintRequest` — P2

- **Shape:** page → native, action: `{ action: "mintRequest", id, url, method, headers, circuit, body }`
- **Payload:** `url`: string, `method`: string, `headers`: object|null, `circuit`: string, `body`: string
- **Called from:** `Web/foxy-wallet.js:25222`
- **Native:** Every mint, LNURL / lightning-address and block-explorer request the page makes (cashu-ts customRequest, nativeRequest). In order: url parses; Route.urlProblem (https, or http to .onion; no localhost, IP literal, single label, .local family; .onion needs Tor); method GET or POST only; Route.available or the refusal; HostApprovals (an unknown host is asked about in a native alert first); then sent on the Tor circuit labelled `circuit` (16-64 lowercase hex, else a fresh one). Only content-type and accept headers are passed; generic Tor-Browser User-Agent. Plain http to an .onion goes through Tor SOCKS by hand (GET only). Answer capped at 4 MB. Redirects re-checked and never to another host. NSURLErrorBadURL retried once after 0.4 s.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "<HTTP status>\n<body>"
- **Errors:** "That is not a mint address Foxy will use."; "That is not a request Foxy will send."; "Foxy is not connected to Tor."; "You did not allow Foxy to contact <host>."; "The answer from <host> was too large."; "An .onion address needs Tor, and Foxy is not connected to Tor."; "<host> could not be asked over Tor: <why>"; "Foxy's private connection was rebuilt mid-request. Try again."
- **In P1:** the error "Foxy is not connected to Tor.". Route.available is false in P1 (ROUTE.md): the refusal, nothing contacted.
- **Timing:** The page waits 65 s. A swap/melt/mint/restore POST is counted in moneyInFlight so backgrounding waits up to 20 s for it.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:398`, `Foxy/Bridge/FoxyBridge.swift:909-944`, `Foxy/Bridge/FoxyBridge.swift:950-953`, `Foxy/Bridge/FoxyBridge.swift:1382-1432`, `Foxy/Bridge/FoxyBridge.swift:1447-1486`, `Foxy/Bridge/FoxyBridge.swift:1505-1510`, `Foxy/Network/Route.swift:318-324`, `Foxy/Network/Route.swift:287-309`, `Foxy/Network/Route.swift:16-26`

#### `price` — P2

- **Shape:** page → native, action: `{ action: "price", id, agree }`
- **Payload:** `agree`: boolean?
- **Called from:** `Web/foxy-wallet.js:15139`
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
- **Called from:** `Web/foxy-wallet.js:15215`
- **Native:** Chart history from Kraken OHLC, interval chosen from the fixed set so the range fits 720 candles. g clamped to 60..86400 s, span to 3600 s..4 years.
- **Reply:** FoxyWallet._scanResult(id, text, error) — JSON array of array of [unixSeconds, close] number pairs, oldest first
- **Errors:** "Foxy is not connected to Tor."; "unreadable candles"; "could not encode candles"
- **In P1:** the error "Foxy is not connected to Tor.". Route.available is false in P1 (ROUTE.md): the refusal, nothing contacted.
- **Timing:** Page waits 16 s; native times the request out at 12 s.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:364`, `Foxy/Bridge/FoxyBridge.swift:696-748`, `Foxy/Bridge/FoxyBridge.swift:715`

#### `hostsKnown` — P2

- **Shape:** page → native, action: `{ action: "hostsKnown", id, hosts }`
- **Payload:** `hosts`: string[]
- **Called from:** `Web/foxy-wallet.js:26068`
- **Native:** Once, the first time a version with host approvals runs: the mints and contact domains the wallet already used are carried over as approved (each approves only itself). Later calls change nothing.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **In P1:** `ok`. No host approvals until P2, so nothing to carry over.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:399`, `Foxy/Bridge/FoxyBridge.swift:957-960`, `Foxy/Network/Route.swift:744`

#### `noWebRTC` — P1

- **Shape:** native → page, script: `every RTC* constructor defined as undefined, non-writable, non-configurable; document start, ALL frames`
- **Called from:** native
- **Native:** WebRTC STUN is UDP no request block sees; it is removed before any page script so it cannot leak the IP.
- **iOS:** `Foxy/FoxyWebView.swift:138-141`, `Foxy/FoxyWebView.swift:434-442`

### Seed and keychain

#### `seedStatus` — P1

- **Shape:** page → native, action: `{ action: "seedStatus", id, quiet }`
- **Payload:** `quiet`: boolean?
- **Called from:** `Web/foxy-wallet.js:2463`, `Web/foxy-wallet.js:21574`
- **Native:** Whether a seed is saved. quiet=true answers from presence alone (no biometric prompt); without it the seed is read (may prompt). "passcode": false only on a phone with no device credential. Finding a seed closes the seedMigrate window.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {exists: boolean, passcode: boolean?}
- **Errors:** "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:371`, `Foxy/Bridge/NativeSeedBridge.swift:254-257`, `Foxy/Keychain/SeedActions.swift:154-168`

#### `seedCreate` — P1

- **Shape:** page → native, action: `{ action: "seedCreate", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:2470`
- **Native:** Twelve words made natively (128 bits from the platform CSPRNG, the bundled wordlist used only if its SHA-256 is pinned) and saved; never replaces a saved seed; counters left from a gone seed are moved aside first. One seed change at a time.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {created: boolean}
- **Errors:** "a seed already exists"; "the seed could not be read"; "the wordlist could not be checked"; "the seed could not be made"; "Another change to the seed is still waiting. Nothing was changed."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:372`, `Foxy/Bridge/NativeSeedBridge.swift:273-279`, `Foxy/Keychain/SeedActions.swift:188-214`, `Foxy/Bridge/FoxyBridge.swift:225-234`

#### `seedMigrate` — P1

- **Shape:** page → native, action: `{ action: "seedMigrate", id, words }`
- **Payload:** `words`: string|string[]
- **Called from:** `Web/foxy-wallet.js:2564`
- **Native:** The one-time move of words a pre-keychain page still holds (localStorage foxy.seed.v1). Only a valid BIP-39 phrase (<=1000 bytes, or <=24 words), written only inside the one-time window and only where no seed is saved; otherwise same/different, changing nothing. Never answers words.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {migrated: boolean, same: boolean?, different: boolean?}
- **Errors:** "bad request"; "no migration here"; "the wordlist could not be checked"; "Another change to the seed is still waiting. Nothing was changed."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:373`, `Foxy/Bridge/NativeSeedBridge.swift:294-310`, `Foxy/Bridge/NativeSeedBridge.swift:201`, `Foxy/Keychain/SeedActions.swift:253-260`

#### `seedShow` — P3

- **Shape:** page → native, action: `{ action: "seedShow", id, verify }`
- **Payload:** `verify`: boolean
- **Called from:** `Web/foxy-wallet.js:18903`
- **Native:** Native screen showing the words (behind biometric/device credential), with the verify quiz when verify=true. Answers only whether the quiz was finished, whether delete was asked, whether the words were revealed. One seed screen at a time; none within 10 s of a cancelled read. A cancelled/closed screen answers {"verified": false}.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {verified: boolean, delete: boolean?, revealed: boolean?}
- **Errors:** "a seed screen is already open"; "try again in a moment"; "no seed"; "the seed could not be read"; "Nothing to present the screen from."
- **Timing:** Page waits 30 min (PERSON_MS).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:385`, `Foxy/Bridge/NativeSeedBridge.swift:425-478`

#### `seedEnter` — P3

- **Shape:** page → native, action: `{ action: "seedEnter", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:18919`
- **Native:** Native screen where the person types words to restore. Answers a random 128-bit candidate id, never the words. Two candidates at most, dropped on unlock-forget, page reload or background.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {candidate: string}
- **Errors:** "a seed screen is already open"; "too many candidates"; "cancelled"; "the words could not be read"; "the wordlist could not be checked"; "Nothing to present the screen from."
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:386`, `Foxy/Bridge/NativeSeedBridge.swift:481-518`

#### `seedAdopt` — P3

- **Shape:** page → native, action: `{ action: "seedAdopt", id, candidate }`
- **Payload:** `candidate`: string
- **Called from:** `Web/foxy-wallet.js:3097`
- **Native:** Replace the saved seed with a candidate: native alert "Replace this wallet's seed?" (gives up after 120 s as no), then device credential; counters set aside and raised to 1000 short of the furthest range served.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {adopted: boolean, same: boolean?}
- **Errors:** "bad request"; "unknown candidate"; "Nothing was changed."; "Another change to the seed is still waiting. Nothing was changed."
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:387`, `Foxy/Bridge/NativeSeedBridge.swift:526-549`, `Foxy/Bridge/FoxyBridge.swift:198-220`

#### `seedCandidateForget` — P3

- **Shape:** page → native, action: `{ action: "seedCandidateForget", id, candidate }`
- **Payload:** `candidate`: string
- **Called from:** `Web/foxy-wallet.js:18939`
- **Native:** Forget a candidate id.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {forgotten: boolean}
- **Errors:** "bad request"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:388`, `Foxy/Bridge/NativeSeedBridge.swift:566-574`

#### `seedWipe` — P3

- **Shape:** page → native, action: `{ action: "seedWipe", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:14490`, `Web/foxy-wallet.js:25159`
- **Native:** Native alert "Delete this wallet's seed?", then device credential; the seed is checked gone before counters move, and a new seed is always made.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {wiped: boolean, created: boolean}
- **Errors:** "Nothing was erased."; "Another change to the seed is still waiting. Nothing was changed."
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:389`, `Foxy/Bridge/NativeSeedBridge.swift:585-604`, `Foxy/Keychain/SeedActions.swift:586-600`

#### `seedProtection` — P3

- **Shape:** page → native, action: `{ action: "seedProtection", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25133`
- **Native:** Which protection the person chose: "device" (behind biometric/credential) or "none".
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {mode: string, passcode: boolean}
- **In P1:** `{"mode":"none","passcode":true}`. Before P3 the seed is kept under protection none only (SeedVault, P1); device protection and the passcode question come with BiometricPrompt in P3. Asked as the wallet's home screen opens, so a host that connects (P2) meets it.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:390`, `Foxy/Bridge/NativeSeedBridge.swift:260-262`, `Foxy/Keychain/SeedStore.swift:521-524`

#### `seedProtect` — P3

- **Shape:** page → native, action: `{ action: "seedProtect", id, mode }`
- **Payload:** `mode`: string
- **Called from:** `Web/foxy-wallet.js:25140`
- **Native:** Move the seed between the two protections. Taking protection off reads the seed from behind it first; written and read back where it is going before the old copy is deleted.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {mode: string}
- **Errors:** "bad request"; "the seed could not be read"
- **Timing:** Page waits 30 min.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:391`, `Foxy/Bridge/NativeSeedBridge.swift:267-270`, `Foxy/Keychain/SeedActions.swift:578-584`

#### `biometric` — P3

- **Shape:** page → native, action: `{ action: "biometric", id, reason, passcode }`
- **Payload:** `reason`: string, `passcode`: boolean
- **Called from:** `Web/foxy-wallet.js:25390`
- **Native:** Biometric (or device credential when passcode=true, the screen lock only) for the PIN lock. The reason shown is one of native's ("Unlock Foxy", "Leave POS mode"); anything else shows "Unlock Foxy". A yes is remembered so the following seed read does not ask again.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "yes" / "no" / "unavailable"
- **Timing:** Page treats no answer in 60 s as "no".
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:367`, `Foxy/Bridge/FoxyBridge.swift:804-822`, `Foxy/Bridge/FoxyBridge.swift:826`

### Counters, NUT-13 secrets and P2PK lock keys

#### `countersImport` — P1

- **Shape:** page → native, action: `{ action: "countersImport", id, counters }`
- **Payload:** `counters`: object
- **Called from:** `Web/foxy-wallet.js:2662`
- **Native:** Once per install: the page's old NUT-13 counters ({keysetId: next}) raised into the native file (max of each). At most 256 keysets.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {counters: object}
- **Errors:** "bad request"; "counters were already imported"; "too many keysets"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:374`, `Foxy/Bridge/NativeSeedBridge.swift:316-321`, `Foxy/Bridge/NativeSeedBridge.swift:173`

#### `counterReserve` — P1

- **Shape:** page → native, action: `{ action: "counterReserve", id, keysetId, count }`
- **Payload:** `keysetId`: string, `count`: integer
- **Called from:** `Web/foxy-wallet.js:2983`, `Web/foxy-wallet.js:2992`, `Web/foxy-wallet.js:3071`
- **Native:** Reserve `count` (0..1000) counters for a 00/01 keyset and answer their NUT-13 secrets and blinding factors (hex). The range is written to the counter file BEFORE the secrets are answered. count=0 is a peek: answers start=next with empty lists and needs no seed.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"; "too many keysets"; "outside the keyset's counters"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:375`, `Foxy/Bridge/NativeSeedBridge.swift:339-344`, `Foxy/Bridge/NativeSeedBridge.swift:76`, `Foxy/Keychain/SeedActions.swift:305-320`, `Foxy/Keychain/NUT13.swift:167-172`

#### `counterReserveAt` — P1

- **Shape:** page → native, action: `{ action: "counterReserveAt", id, keysetId, start, count }`
- **Payload:** `keysetId`: string, `start`: integer, `count`: integer
- **Called from:** `Web/foxy-wallet.js:2992`
- **Native:** Reserve an explicit range: refused if it starts below next (already issued) or more than 100 past it.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]}
- **Errors:** "bad request"; "range already issued"; "too far ahead"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:376`, `Foxy/Bridge/NativeSeedBridge.swift:349-354`, `Foxy/Keychain/SeedActions.swift:325-339`

#### `counterAdvance` — P1

- **Shape:** page → native, action: `{ action: "counterAdvance", id, keysetId, next }`
- **Payload:** `keysetId`: string, `next`: integer
- **Called from:** `Web/foxy-wallet.js:2986`, `Web/foxy-wallet.js:3035`
- **Native:** Move a counter up, never down; at most 100 past next, or to the end of a range restoreSecrets served this session.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, next: integer}
- **Errors:** "bad request"; "too far ahead"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:377`, `Foxy/Bridge/NativeSeedBridge.swift:330-335`, `Foxy/Keychain/SeedActions.swift:295-302`

#### `counterSnapshot` — P1

- **Shape:** page → native, action: `{ action: "counterSnapshot", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:3016`
- **Native:** Reads every keyset's next counter; moves nothing; needs no seed.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {counters: object}
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:378`, `Foxy/Bridge/NativeSeedBridge.swift:324-326`

#### `restoreSecrets` — P1

- **Shape:** page → native, action: `{ action: "restoreSecrets", id, keysetId, start, count, candidate }`
- **Payload:** `keysetId`: string, `start`: integer, `count`: integer, `candidate`: string?
- **Called from:** `Web/foxy-wallet.js:2721`, `Web/foxy-wallet.js:3179`, `Web/foxy-wallet.js:3180`
- **Native:** Secrets for a restore scan, moving no counter: for the saved seed only ranges ending at most 1000 past next; for a typed-words candidate (seedEnter) contiguously from 0 up to 20000. Notes the furthest range served (counterAdvance may reach it; seedAdopt raises counters to 1000 short of it).
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {keysetId: string, start: integer, secrets: string[], blindingFactors: string[]}
- **Errors:** "bad request"; "outside the restore window"; "unknown candidate"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:379`, `Foxy/Bridge/NativeSeedBridge.swift:361-366`, `Foxy/Bridge/NativeSeedBridge.swift:104`, `Foxy/Keychain/SeedActions.swift:347-366`

#### `p2pkReserve` — P1

- **Shape:** page → native, action: `{ action: "p2pkReserve", id, count }`
- **Payload:** `count`: integer
- **Called from:** `Web/foxy-wallet.js:6694`
- **Native:** Reserve 0..64 lock-key indexes on NUT-13's P2PK path m/129373'/10'/0'/0'/i (last level normal) and answer their compressed PUBLIC keys; the index is written first. count=0 is a peek.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {start: integer, next: integer, pubkeys: string[]}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:382`, `Foxy/Bridge/NativeSeedBridge.swift:372-377`, `Foxy/Keychain/SeedActions.swift:377-391`, `Foxy/Keychain/P2PK.swift:206-209`

#### `p2pkPubkeys` — P1

- **Shape:** page → native, action: `{ action: "p2pkPubkeys", id, start, count }`
- **Payload:** `start`: integer, `count`: integer
- **Called from:** `Web/foxy-wallet.js:6667`, `Web/foxy-wallet.js:8307`
- **Native:** Public keys only, 1..300 at a time, no index past 20000; moves nothing but notes the scan's reach (which p2pkKey may then answer).
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {start: integer, next: integer, pubkeys: string[]}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:383`, `Foxy/Bridge/NativeSeedBridge.swift:381-386`, `Foxy/Keychain/SeedActions.swift:407-417`

#### `p2pkKey` — P1

- **Shape:** page → native, action: `{ action: "p2pkKey", id, index }`
- **Payload:** `index`: integer
- **Called from:** `Web/foxy-wallet.js:6782`, `Web/foxy-wallet.js:8320`, `Web/foxy-wallet.js:24229`
- **Native:** One lock key's private half, only within 1000 of the last index reserved or inside a range this session's scan served. The chain code is never answered.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {index: integer, privkey: string, pubkey: string}
- **Errors:** "bad request"; "outside the lock-key window"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:384`, `Foxy/Bridge/NativeSeedBridge.swift:391-396`, `Foxy/Keychain/SeedActions.swift:427-456`, `Foxy/Keychain/P2PK.swift:215-217`

### Clipboard, paste and share

#### `pasteStart` — P3

- **Shape:** page → native, action: `{ action: "pasteStart", id, x, y, w, h }`
- **Payload:** `x`: integer, `y`: integer, `w`: integer, `h`: integer
- **Called from:** `Web/foxy-wallet.js:25683`
- **Native:** iOS: a near-invisible UIPasteControl over the page's PASTE button (paste with no system prompt). Rect must be >30x20, <=90% width, <=50% height, inside the view, below its top third. The pasted text (never a seed phrase) is pushed with _pasted.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Nothing to attach the button to."; "That is not a place a paste button goes."
- **In P1:** the error "Nothing to attach the button to.". No native paste control before P3 (BRIDGE.md §4 item 4 decides it there): the page's own PASTE button stays. The page posts this without waiting on the answer, whenever a PASTE button is on screen, which a host that connects (P2) shows.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:403`, `Foxy/Bridge/FoxyBridge.swift:1163-1237`

#### `pasteStop` — P3

- **Shape:** page → native, action: `{ action: "pasteStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25693`
- **Native:** Removes the paste control (kept a moment longer, not taking touches, while a paste prompt is up).
- **Reply:** FoxyWallet._scanResult(id, text, error) — ""
- **In P1:** ``. Nothing to take down before P3; posted without waiting on the answer, as pasteStart is.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:404`, `Foxy/Bridge/FoxyBridge.swift:983-1016`

#### `clipboard` — P3

- **Shape:** page → native, action: `{ action: "clipboard", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25550`
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
- **Called from:** `Web/foxy-wallet.js:25575`
- **Native:** System share sheet with plain text only (no URL item, no link preview fetched).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Nothing to share."; "Something else is already open."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:406`, `Foxy/Bridge/FoxyBridge.swift:1018-1020`, `Foxy/Bridge/FoxyBridge.swift:1311-1339`

#### `_pasted` — P3

- **Shape:** native → page, push: `window.FoxyWallet._pasted(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:25698`
- **Native:** Text pasted through the paste control; a seed phrase arrives as "".
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:1225`

### Scanner

#### `scan` — P3

- **Shape:** page → native, action: `{ action: "scan", id, prompt }`
- **Payload:** `prompt`: string
- **Called from:** `Web/foxy-wallet.js:23407`
- **Native:** Full-screen camera scanner. The prompt is shown only if it is one of native's fixed prompts ("Scan an ecash token", "Scan the mint address"), else blank.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the decoded text)
  The text is the decoded text.
- **Errors:** "Nothing to present the scanner from."; "<camera error>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:358`, `Foxy/Bridge/FoxyBridge.swift:548-550`, `Foxy/Bridge/FoxyBridge.swift:1083`, `Foxy/Bridge/FoxyBridge.swift:1062-1075`

#### `previewStart` — P3

- **Shape:** page → native, action: `{ action: "previewStart", id, x, y, w, h }`
- **Payload:** `x`: integer, `y`: integer, `w`: integer, `h`: integer
- **Called from:** `Web/foxy-wallet.js:25643`
- **Native:** Live camera laid over the page at a rectangle (CSS px) the page measured: finite, larger than 40x40, inside the web view. Each decoded code is pushed with _liveScan; a refusal is also pushed with _previewRefused.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Nothing to show the camera in."; "That pane is too small for a camera (<w>x<h>)."; "The camera pane must be on screen (...)"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:401`, `Foxy/Bridge/FoxyBridge.swift:1099-1141`

#### `previewStop` — P3

- **Shape:** page → native, action: `{ action: "previewStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25653`
- **Native:** Stops the live preview.
- **Reply:** FoxyWallet._scanResult(id, text, error) — ""
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:402`, `Foxy/Bridge/FoxyBridge.swift:973-976`

#### `_previewRefused` — P3

- **Shape:** native → page, push: `window.FoxyWallet._previewRefused(why)`
- **Payload:** `why`: string
- **Called from:** `Web/foxy-wallet.js:25659`
- **Native:** previewStart was refused (as well as its error reply).
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:1107`

#### `_liveScan` — P3

- **Shape:** native → page, push: `window.FoxyWallet._liveScan(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:25665`
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
- **Called from:** `Web/foxy-wallet.js:25632`
- **Native:** A local notification. kind is "sent" or "received"; the words are native's ("Payment sent"/"Payment received", titled "Foxy"), no amount. At most one every 3 s.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok" / "throttled"
- **Errors:** "unknown notification kind"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:405`, `Foxy/Bridge/FoxyBridge.swift:1254-1305`, `Foxy/Bridge/FoxyBridge.swift:1247`

### Other platform doors

#### `openSettings` — P3

- **Shape:** page → native, action: `{ action: "openSettings", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:25563`
- **Native:** Opens this app's own system settings page and no other URL (a camera or Bluetooth permission refused once).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "No settings page to open."; "Settings would not open."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:407`, `Foxy/Bridge/FoxyBridge.swift:1026-1036`

#### `openCompany` — P3

- **Shape:** page → native, action: `{ action: "openCompany", id, which }`
- **Payload:** `which`: string
- **Called from:** `Web/foxy-wallet.js:25569`
- **Native:** Opens one of four fixed sites by name (cashapp, strike, river, flash) in the system browser, outside Foxy and outside Tor. The page never supplies a URL.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Not one of the listed sites."; "The browser would not open."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:408`, `Foxy/Bridge/FoxyBridge.swift:1049-1060`, `Foxy/Bridge/FoxyBridge.swift:1042-1047`

### Onion inbox and post (LATER)

#### `inboxOpen` — LATER

- **Shape:** page → native, action: `{ action: "inboxOpen", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:21860`
- **Native:** An ephemeral v3 onion address (key made and discarded by Tor) forwarding to a 127.0.0.1 listener, plus a Nostr nprofile listening on up to four relays over Tor, for the payment request on screen. A spare is kept warm.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {onion: string?, nostr: string?}
- **Errors:** "No address: Tor is not ready."; "No onion address: Tor is not ready."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:410`, `Foxy/Bridge/FoxyBridge+Delivery.swift:111-150`, `Foxy/Bridge/FoxyBridge+Delivery.swift:61-73`

#### `inboxClose` — LATER

- **Shape:** page → native, action: `{ action: "inboxClose", id, hold }`
- **Payload:** `hold`: number
- **Called from:** `Web/foxy-wallet.js:21878`
- **Native:** End the address (after `hold` seconds, 0..180, if a payment may be on its way) and the Nostr inbox.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:411`, `Foxy/Bridge/FoxyBridge+Delivery.swift:208-230`

#### `inboxAnswer` — LATER

- **Shape:** page → native, action: `{ action: "inboxAnswer", id, answer, status, text }`
- **Payload:** `answer`: string, `status`: integer, `text`: string
- **Called from:** `Web/foxy-wallet.js:22182`
- **Native:** Answer a payment that arrived (_requestPaid): status 200, 409 or 422 only, 300 characters of text.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "No payment is waiting on that answer."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:412`, `Foxy/Bridge/FoxyBridge+Delivery.swift:234-246`

#### `onionPost` — LATER

- **Shape:** page → native, action: `{ action: "onionPost", id, url, body, timeout }`
- **Payload:** `url`: string, `body`: string, `timeout`: number
- **Called from:** `Web/foxy-wallet.js:8637`
- **Native:** POST a payment to another phone's onion: http:// v3 .onion only, no user/query/fragment, through Tor SOCKS, never the open connection; body <=256 kB, answer cut at 64 kB. timeout in ms, clamped to 3..120 s.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "<HTTP status>\n<body>"
- **Errors:** "That payment is too large to send."; "<OnionPost failure text>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:413`, `Foxy/Bridge/FoxyBridge+Delivery.swift:292-323`

#### `_requestPaid` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._requestPaid(body, answerId[, "tap"])`
- **Payload:** `body`: string, `answerId`: string, `via`: string?
- **Called from:** `Web/foxy-wallet.js:22169`
- **Native:** A payment arrived for the request on screen (onion inbox, Nostr inbox with answerId "", or a tap with "tap"). The page redeems it and answers with inboxAnswer.
- **iOS:** `Foxy/Bridge/FoxyBridge+Delivery.swift:104`, `Foxy/Bridge/FoxyBridge+Delivery.swift:256`, `Foxy/Bridge/FoxyBridge+Tap.swift:121`

### Nostr (LATER)

#### `nostrSend` — LATER

- **Shape:** page → native, action: `{ action: "nostrSend", id, target, body }`
- **Payload:** `target`: string, `body`: string
- **Called from:** `Web/foxy-wallet.js:8664`
- **Native:** NIP-17/59 gift-wrap the payment for the nprofile in a request and publish to at most five wss relays it names (fallback: damus, nos.lol, primal, minibits), each over Tor on its own circuit; never the open connection.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (how many relays accepted it, as a decimal string)
  The text is how many relays accepted it, as a decimal string.
- **Errors:** "That request names no Nostr address."; "That payment is too large to send over Nostr."; "No relay took the payment: <why>"; "The payment could not be sent over Nostr."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:414`, `Foxy/Bridge/FoxyBridge+Delivery.swift:262-288`, `Foxy/Nostr/NostrDelivery.swift:18-19`

### Bluetooth tap to pay (LATER)

#### `tapReceiveStart` — LATER

- **Shape:** page → native, action: `{ action: "tapReceiveStart", id, payload, early }`
- **Payload:** `payload`: string, `early`: boolean
- **Called from:** `Web/foxy-wallet.js:23489`
- **Native:** Advertise the invoice/payment request on screen over BLE (service UUID only). Payment, quote and stage events come back as _requestPaid(.., "tap"), _tapQuote, _tap.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "Not something a payer could pay."; "That payment is still going through. A moment."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:416`, `Foxy/Bridge/FoxyBridge+Tap.swift:22-147`

#### `tapReceiveStop` — LATER

- **Shape:** page → native, action: `{ action: "tapReceiveStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23492`
- **Native:** Stop advertising.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:417`, `Foxy/Bridge/FoxyBridge+Tap.swift:149-165`

#### `tapPayStart` — LATER

- **Shape:** page → native, action: `{ action: "tapPayStart", id, now }`
- **Payload:** `now`: boolean?
- **Called from:** `Web/foxy-wallet.js:23508`, `Web/foxy-wallet.js:23512`
- **Native:** Search for a receiver close enough to be touching and answer its offer plus the four-digit code both phones show; now=true only nudges a search already running and answers "ok".
- **Reply:** FoxyWallet._scanResult(id, text, error) — json-or-text {payload: string, code: string} or "ok"
- **Errors:** "Started again."; "Stopped."
- **In P1:** the error "Stopped.". No Bluetooth tap on Android before LATER: every search ends at once, as a stopped one does, and the page backs off its re-listen (up to a minute). A funded wallet's home screen listens with no one acting, so a host that connects (P2) meets it.
- **Timing:** Page waits 30 min for a payer to touch.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:418`, `Foxy/Bridge/FoxyBridge+Tap.swift:167-218`

#### `tapSend` — LATER

- **Shape:** page → native, action: `{ action: "tapSend", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:23551`
- **Native:** The payment, over the link this tap already holds, <=64 KB.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the receiver's answer)
  The text is the receiver's answer.
- **Errors:** "There is nothing to hand over."; "That phone is no longer connected."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:427`, `Foxy/Bridge/FoxyBridge+Tap.swift:223-245`

#### `tapChange` — LATER

- **Shape:** page → native, action: `{ action: "tapChange", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:23603`
- **Native:** Change token back to the payer over the same link; resolves when the payer says it kept it (7 s), or late via _tapChangeKeptLate.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "There is no change to send."; "That phone is no longer connected."; "The payer's phone did not say it kept the change."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:419`, `Foxy/Bridge/FoxyBridge+Tap.swift:348-399`

#### `tapChangeKept` — LATER

- **Shape:** page → native, action: `{ action: "tapChangeKept", id, kept }`
- **Payload:** `kept`: boolean
- **Called from:** `Web/foxy-wallet.js:23700`
- **Native:** The payer's page saying it wrote the change down (M8).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "No change is waiting to be kept."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:420`, `Foxy/Bridge/FoxyBridge+Tap.swift:474-485`

#### `tapAsking` — LATER

- **Shape:** page → native, action: `{ action: "tapAsking", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23615`
- **Native:** The receiver is asking its person about an offline payment (M9).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok" / "no link"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:421`, `Foxy/Bridge/FoxyBridge+Tap.swift:412-421`

#### `tapChangeDue` — LATER

- **Shape:** page → native, action: `{ action: "tapChangeDue", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23689`
- **Native:** This payment owes change: keep the link.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:422`, `Foxy/Bridge/FoxyBridge+Tap.swift:463-468`

#### `tapQuote` — LATER

- **Shape:** page → native, action: `{ action: "tapQuote", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:23624`
- **Native:** Payer's price quote (M10).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "That phone is no longer connected."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:423`, `Foxy/Bridge/FoxyBridge+Tap.swift:429-438`

#### `tapTerms` — LATER

- **Shape:** page → native, action: `{ action: "tapTerms", id, body }`
- **Payload:** `body`: string
- **Called from:** `Web/foxy-wallet.js:23629`
- **Native:** Receiver agreeing the price (M11).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "That phone is no longer connected."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:424`, `Foxy/Bridge/FoxyBridge+Tap.swift:445-454`

#### `tapPayStop` — LATER

- **Shape:** page → native, action: `{ action: "tapPayStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23762`
- **Native:** Stop searching/connecting; a waiting tapPayStart is answered with the error "Stopped.".
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:425`, `Foxy/Bridge/FoxyBridge+Tap.swift:267-272`

#### `bluetoothAsk` — P3

- **Shape:** page → native, action: `{ action: "bluetoothAsk", id }`
- **Payload:** —
- **Called from:** `Web/foxy-tor-gate.js:2071`
- **Native:** First-install screen: put the OS Bluetooth permission question up, answer the state (also pushed to window.__foxyBluetoothAnswer). Scans and advertises nothing. Android (ENG-656, until BLE tap to pay in ENG-650): no question is asked and no Bluetooth permission is declared or requested; native pushes window.__foxyBluetoothAnswer("unsupported") and then answers "unsupported", in iOS's order, so the page writes foxy.bt.asked and closes the screen. A reply without the push left the screen up for good: the page leaves only on the push, and every later APPROVE returns early on `asking`.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "allowed" / "denied" / "off" / "unsupported" / "unknown"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:426`, `Foxy/Bridge/FoxyBridge+Tap.swift:254-265`

#### `shakeStart` — LATER

- **Shape:** page → native, action: `{ action: "shakeStart", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23480`
- **Native:** While set, a shake is pushed as _tap({side:"receive",stage:"shake"}).
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:441`, `Foxy/Bridge/FoxyBridge+Tap.swift:279-285`

#### `shakeStop` — LATER

- **Shape:** page → native, action: `{ action: "shakeStop", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23480`
- **Native:** Clears the shake flag.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:442`, `Foxy/Bridge/FoxyBridge+Tap.swift:287-293`

#### `_tapQuote` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._tapQuote(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:23635`
- **Native:** M10 arrived on the receiver.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:130`

#### `_tapChange` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._tapChange(token)`
- **Payload:** `token`: string
- **Called from:** `Web/foxy-wallet.js:23704`
- **Native:** Change arrived on the payer; it answers with tapChangeKept.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:194`

#### `_tapTerms` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._tapTerms(text)`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:23672`
- **Native:** M11 arrived on the payer.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:200`

#### `__foxyBluetoothAnswer` — P3

- **Shape:** native → page, global: `window.__foxyBluetoothAnswer(state)`
- **Payload:** `state`: string
- **Called from:** `Web/foxy-tor-gate.js:2060`
- **Native:** The Bluetooth question was answered (defined by the gate's first-install screen). Android (ENG-656): pushed with "unsupported" on every bluetoothAsk, before its reply, until BLE tap to pay (ENG-650).
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:259`

#### `_turned` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._turned(upsideDown)`
- **Payload:** `upsideDown`: boolean
- **Called from:** `Web/foxy-wallet.js:23776`
- **Native:** Device turned portrait/upside-down (the receive screen turns itself for the payer across the table).
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:303-307`, `Foxy/FoxyWebView.swift:42`

#### `_tapChangeKeptLate` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._tapChangeKeptLate()`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:23578`
- **Native:** The payer said it kept the change after tapChange had already answered.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:380`

#### `_tap` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._tap({side, stage, code?, edge?, pct?})`
- **Payload:** `side`: string, `stage`: string, `code`: string?, `edge`: string?, `pct`: integer?
- **Called from:** `Web/foxy-wallet.js:23784`
- **Native:** Tap progress. side receive|pay; stage one of searching, far, connecting, linked, sending, receiving, code, asking, gone, shake, nearby, version, off, denied, unsupported.
- **iOS:** `Foxy/Bridge/FoxyBridge+Tap.swift:487-498`, `Foxy/Bluetooth/TapLink.swift:5-24`

### NFC card (LATER)

#### `cardBegin` — LATER

- **Shape:** page → native, action: `{ action: "cardBegin", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:24870`
- **Native:** Open the NFC session with one printable line (<=120 chars) of the page's text; answers when a card is there. One session at a time.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "<CardLinkError words>"
- **Timing:** Page waits 63 s.
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:429`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:27-64`

#### `cardSend` — LATER

- **Shape:** page → native, action: `{ action: "cardSend", id, apdu }`
- **Payload:** `apdu`: string
- **Called from:** `Web/foxy-wallet.js:24795`
- **Native:** One APDU (hex) and its answer. Only SELECT of Foxy's own applet AID, then only that applet's 27 instructions (CardGate). Never logged: one carries the PIN.
- **Reply:** FoxyWallet._scanResult(id, text, error) — text (the response APDU, hex)
  The text is the response APDU, hex.
- **Errors:** "That is not a command for a card."; "That is not a command Foxy sends to a card."; "<CardLinkError words>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:430`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:69-95`

#### `cardSay` — LATER

- **Shape:** page → native, action: `{ action: "cardSay", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:24799`
- **Native:** Change the line on the NFC sheet.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:431`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:122-128`

#### `cardAgain` — LATER

- **Shape:** page → native, action: `{ action: "cardAgain", id, text }`
- **Payload:** `text`: string
- **Called from:** `Web/foxy-wallet.js:24816`
- **Native:** The card left part way: look for it again; the applet must be selected again.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **Errors:** "<CardLinkError words>"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:432`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:101-119`

#### `cardEnd` — LATER

- **Shape:** page → native, action: `{ action: "cardEnd", id, text, error }`
- **Payload:** `text`: string?, `error`: string?
- **Called from:** `Web/foxy-wallet.js:24777`, `Web/foxy-wallet.js:24808`
- **Native:** Close the sheet with a word or a reason.
- **Reply:** FoxyWallet._scanResult(id, text, error) — "ok"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:433`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:133-145`

#### `cardOwnerKey` — LATER

- **Shape:** page → native, action: `{ action: "cardOwnerKey", id, key }`
- **Payload:** `key`: string
- **Called from:** `Web/foxy-wallet.js:9784`
- **Native:** The P-256 owner PUBLIC key (04||X||Y hex) derived from the seed for one card (its 33-byte compressed key, hex).
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {pub: string}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:436`, `Foxy/Bridge/NativeSeedBridge.swift:402-407`, `Foxy/Keychain/SeedActions.swift:467-477`

#### `cardOwnerSign` — LATER

- **Shape:** page → native, action: `{ action: "cardOwnerSign", id, key, label, nonce, value }`
- **Payload:** `key`: string, `label`: string, `nonce`: string, `value`: string
- **Called from:** `Web/foxy-wallet.js:9808`
- **Native:** ECDSA P-256 DER signature over "FoxyCard/"+label, nonce, value for one of six labels (change-pin, set-limit, set-owner, set-card, load, reset), each with its value shape.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {sig: string}
- **Errors:** "bad request"; "no seed"; "the seed could not be read"
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:437`, `Foxy/Bridge/NativeSeedBridge.swift:412-417`, `Foxy/Keychain/SeedActions.swift:489-499`

#### `cardTime` — LATER

- **Shape:** page → native, action: `{ action: "cardTime", id }`
- **Payload:** —
- **Called from:** `Web/foxy-wallet.js:9931`
- **Native:** The phone clock (u32 seconds) signed by the INTERIM time key built into the app, for cards of software <=1.14.
- **Reply:** FoxyWallet._scanResult(id, text, error) — json {time: integer, sig: string}
- **Errors:** "The clock on this phone is not a time a card can be told."
- **iOS:** `Foxy/Bridge/FoxyBridge.swift:439`, `Foxy/Bridge/FoxyBridge+Flashcard.swift:152-158`, `Foxy/Flashcard/CardTime.swift:110-113`

#### `_card` — LATER

- **Shape:** native → page, push: `window.FoxyWallet._card({stage, text})`
- **Payload:** `stage`: string, `text`: string
- **Called from:** `Web/foxy-wallet.js:24750`
- **Native:** NFC session progress: stage connected|say|end|lost.
- **iOS:** `Foxy/Flashcard/CardGate.swift:143-149`
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
   the release manifest has no scheme (`ManifestTest`).
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
| `inboxOpen` | page → native | action | LATER | — | json {onion: string?, nostr: string?} | Foxy/Bridge/FoxyBridge.swift:410 |
| `inboxClose` | page → native | action | LATER | `hold`: number | "ok" | Foxy/Bridge/FoxyBridge.swift:411 |
| `inboxAnswer` | page → native | action | LATER | `answer`: string, `status`: integer, `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:412 |
| `onionPost` | page → native | action | LATER | `url`: string, `body`: string, `timeout`: number | "<HTTP status>\n<body>" | Foxy/Bridge/FoxyBridge.swift:413 |
| `nostrSend` | page → native | action | LATER | `target`: string, `body`: string | text (how many relays accepted it, as a decimal string) | Foxy/Bridge/FoxyBridge.swift:414 |
| `tapReceiveStart` | page → native | action | LATER | `payload`: string, `early`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:416 |
| `tapReceiveStop` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:417 |
| `tapPayStart` | page → native | action | LATER | `now`: boolean? | json-or-text {payload: string, code: string} or "ok" | Foxy/Bridge/FoxyBridge.swift:418 |
| `tapSend` | page → native | action | LATER | `body`: string | text (the receiver's answer) | Foxy/Bridge/FoxyBridge.swift:427 |
| `tapChange` | page → native | action | LATER | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:419 |
| `tapChangeKept` | page → native | action | LATER | `kept`: boolean | "ok" | Foxy/Bridge/FoxyBridge.swift:420 |
| `tapAsking` | page → native | action | LATER | — | "ok" / "no link" | Foxy/Bridge/FoxyBridge.swift:421 |
| `tapChangeDue` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:422 |
| `tapQuote` | page → native | action | LATER | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:423 |
| `tapTerms` | page → native | action | LATER | `body`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:424 |
| `tapPayStop` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:425 |
| `bluetoothAsk` | page → native | action | P3 | — | "allowed" / "denied" / "off" / "unsupported" / "unknown" | Foxy/Bridge/FoxyBridge.swift:426 |
| `shakeStart` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:441 |
| `shakeStop` | page → native | action | LATER | — | "ok" | Foxy/Bridge/FoxyBridge.swift:442 |
| `cardBegin` | page → native | action | LATER | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:429 |
| `cardSend` | page → native | action | LATER | `apdu`: string | text (the response APDU, hex) | Foxy/Bridge/FoxyBridge.swift:430 |
| `cardSay` | page → native | action | LATER | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:431 |
| `cardAgain` | page → native | action | LATER | `text`: string | "ok" | Foxy/Bridge/FoxyBridge.swift:432 |
| `cardEnd` | page → native | action | LATER | `text`: string?, `error`: string? | "ok" | Foxy/Bridge/FoxyBridge.swift:433 |
| `cardOwnerKey` | page → native | action | LATER | `key`: string | json {pub: string} | Foxy/Bridge/FoxyBridge.swift:436 |
| `cardOwnerSign` | page → native | action | LATER | `key`: string, `label`: string, `nonce`: string, `value`: string | json {sig: string} | Foxy/Bridge/FoxyBridge.swift:437 |
| `cardTime` | page → native | action | LATER | — | json {time: integer, sig: string} | Foxy/Bridge/FoxyBridge.swift:439 |
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
| `__foxyBluetoothAnswer` | native → page | global | P3 | `state`: string | none | Foxy/Bridge/FoxyBridge+Tap.swift:259 |
| `_turned` | native → page | push | LATER | `upsideDown`: boolean | none | Foxy/Bridge/FoxyBridge+Tap.swift:303-307 |
| `_tapChangeKeptLate` | native → page | push | LATER | — | none | Foxy/Bridge/FoxyBridge+Tap.swift:380 |
| `_tap` | native → page | push | LATER | `side`: string, `stage`: string, `code`: string?, `edge`: string?, `pct`: integer? | none | Foxy/Bridge/FoxyBridge+Tap.swift:487-498 |
| `_card` | native → page | push | LATER | `stage`: string, `text`: string | none | Foxy/Flashcard/CardGate.swift:143-149 |
| `noWebRTC` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:138-141 |
| `noCallout` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:146-156 |
| `__foxyClock24` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:164 |
| `FOXY_DEBUG` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:173 |
| `FOXY_SHOW_WARNING` | native → page | global | P1 | `value`: boolean | none | Foxy/FoxyWebView.swift:179 |
| `consoleSilence` | native → page | script | P1 | — | none | Foxy/FoxyWebView.swift:191-204 |
| `__foxyPageHash` | native → page | global | P1 | `value`: string | none | Foxy/FoxyWebView.swift:377-380 |
<!-- contract-table:end -->
