# ROUTE.md: every byte leaves through Tor, or not at all

**The invariant.** Every network request Foxy makes goes through the app's own
Tor, or it is refused before a socket opens. The one exception is a choice the
person makes in a native dialog, for the current session only: continue
without Tor. The page itself has no network at all. This is the iOS app's
design at getfoxy/iOS `8df0bb19992cceda44d04e947bce211e1415975e`, and the
Android port keeps it unchanged. Line references are to that commit
(`conformance/test/contract.test.js` checks each one).

## 1. How the page asks for anything

The page never fetches. Everything it wants from outside goes over the bridge
(`BRIDGE.md`), and native decides the route per request:

| What | How the page asks | Page code |
|---|---|---|
| every mint call (info, keysets, keys, quotes, mint, swap, melt, checkstate, restore) | cashu-ts `Mint` built with `customRequest: FoxyWallet.nativeRequest`, which posts `mintRequest` | `Web/foxy-wallet.js:3319-3340` (`newWallet`), `Web/foxy-wallet.js:25910` (`nativeRequest`), `Web/foxy-wallet.js:25939` (the post) |
| a Lightning address / LNURL lookup and callback | `FoxyWallet.nativeRequest`, so `mintRequest` | `build/wallet/16-sending.js:442` |
| the newest Bitcoin block header (a card's clock) | `nativeRequest` to two explorer onions, then their clearnet names | `build/wallet/08b-block-headers.js:45-57`, `:170` |
| the BTC/USD price | `price` | `Web/foxy-wallet.js:15649` |
| the price chart | `candles` | `Web/foxy-wallet.js:15728` |
| a payment to another phone's onion | `onionPost` (P3, ENG-672) | `Web/foxy-wallet.js:8873` |
| a payment over Nostr | `nostrSend` (P3, ENG-677) | `Web/foxy-wallet.js:8900` |

**No WebSocket.** NUT-17 subscriptions are not used: cashu-ts would open
`wss://<mint>/v1/ws` from the page, which no native route could carry, so the
wallet polls the quote over `mintRequest` instead (`Web/foxy-wallet.js:17248`).
The only WebSockets in the app are native ones to Nostr relays, over Tor
(`Foxy/Network/Route.swift:248-259`, `startSocket`; on Android
`network/RouteSocket.kt`, ENG-677).
**Android keeps this (ENG-648, decided):** no NUT-17 WebSocket. `core/web`
stays pinned byte for byte and no bridge action is added for one; the wallet
polls its quotes through `mintRequest`, as on iOS.

**The page's own fetch paths are dead code in the app.** `rate()` and
`candles()` fall back to `fetch` only when `window.webkit.messageHandlers.foxy`
does not exist, as in a desktop browser (`Web/foxy-wallet.js:15685`,
`Web/foxy-wallet.js:15743`). On Android the shim defines it at document start,
before any page script, so those branches never run; and if they did, the
WebView's network block below refuses them. The conformance suite fails on any
`fetch`, XHR, WebSocket, EventSource, beacon, WebRTC, subresource or DOM URL
outside the asset origin (`conformance/lib/page.js`).

**The page gates itself too, and the gate is not what holds.** The wallet
checks the route before work that needs one (`routeOpen`,
`Web/foxy-wallet.js:2164`; `assertRoute`, `Web/foxy-wallet.js:2243`), so its
background sweeps and invoice watches ask nothing while there is no route
(`sweepQuotes`, `Web/foxy-wallet.js:17485`). That only makes the screens
friendlier. Native refuses regardless; the page is not trusted to have asked.
Conformance proves the page's half: with Tor connecting, then failed, the page
posts no `mintRequest`, `price` or `candles` until the person opts out
(`conformance/test/flows-p1-p2.test.js`, "route closed").

## 2. How native decides: Route

One decision for every native request (`Foxy/Network/Route.swift:38-513`):

- **`Route.available`** (`Route.swift:88-93`): Tor is running, or the person
  chose to continue unprotected. Nothing else.
- **`Route.start`** (`Route.swift:113-128`): the request goes on a Tor SOCKS
  session made for that request with Tor's SOCKS port as it is now; or on the
  one ephemeral open-connection session (no cookies, no cache, no URL cache,
  60 s timeouts) only when unprotected; or nowhere, returning nil, which every
  caller answers with the refusal **"Foxy is not connected to Tor."**
  (`Route.swift:48`).
- **The door** (`Route.swift:166-176`): while Foxy is being put away, `start`
  answers nil for anything new, for 5 s at a time, re-shut every 0.5 s while
  Tor is held; a return opens it.
- **Per-request Tor session** (`Foxy/Tor/TorService.swift:1812-1839`): SOCKS5
  to `127.0.0.1:<Tor's port>` with `allowFailover = false`; port 9 (nothing
  listens) while Tor's port is unknown. The circuit label goes in as the SOCKS
  username and password, and Tor keeps different logins on different circuits
  (`IsolateSOCKSAuth`).
- **Tor's own settings** (`TorService.swift:802-819`): `SocksPort auto
  IsolateDestAddr ExtendedErrors`, `SafeSocks 1`, `ClientRejectInternalAddresses
  1`, `ConfluxEnabled 0`, errors-only logging. With `SafeSocks` Tor refuses a
  SOCKS request that carries an IP address instead of a name, so a client that
  resolved DNS itself fails rather than leaks.
- **Circuit labels** (`Route.swift:16-26`, `MintCircuit.label`): the page names
  a job (one payment, one invoice's polls) with 16-64 lowercase hex; anything
  else gets a fresh random label, never a shared circuit.
- **What a URL must be** (`Route.swift:287-309`, `hostProblem`;
  `Route.swift:318-324`, `urlProblem`): `https`, or `http` only to a `.onion`;
  never `localhost`, an IPv4 or IPv6 literal in any form `inet_aton` takes, a
  single-label name, or `.local`, `.localhost`, `.localdomain`, `.internal`,
  `.lan`, `.home`, `.home.arpa`, `.corp`; a `.onion` only while Tor runs (an
  onion looked up without Tor would go to the phone's resolver in the clear).
- **Redirects** (`Route.swift:346-353`, `redirectProblem`): every hop meets the
  same rules and must stay on the same host.
- **Answers are bounded** (`Route.swift:55`): 4 MB, enforced as bytes arrive
  and refused up front from a larger `Content-Length`.
- **Headers** (`Route.swift:62-65`): Tor Browser's desktop User-Agent and
  Accept-Language on every request; `mintRequest` passes only the page's
  `content-type` and `accept`.
- **Host approvals** (`Route.swift:659-805`, `HostApprovals`): a host Foxy has
  not used is asked about in a native alert before the first request
  (`FoxyBridge.swift:930-941`). Approved without asking: the five default mints
  (`Route.swift:661-664`), the two explorers by onion and by name
  (`Route.swift:672-678`), subdomains of an approved host, and the hosts carried
  over once by `hostsKnown`. Approvals live in the keychain, never in backups.
- **Methods** (`Foxy/Bridge/FoxyBridge.swift:950-953`): `GET` and `POST`; any
  other method is refused, not sent.

### The opt-out: CONTINUE WITHOUT TOR

- Asked by the page (`unprotected`, `on: true`) from the gate's failure screen.
  Native puts up its own alert ("Continue without Tor?", `FoxyBridge.swift:252-264`)
  that no script can answer; a yes sets `Route.unprotected`. While Tor is
  running the ask is ignored (`FoxyBridge.swift:878-902`).
- **Lifetime: this process, until Tor connects.** It is a static in memory
  (`Route.swift:42-46`), never persisted; Tor establishing a circuit clears it
  (`Foxy/Tor/TorService.swift:1269`). `on: false` clears it with no question.
- Unprotected requests use the ephemeral session with the same headers, the
  same URL rules and the same 4 MB cap; `.onion` is still refused.
- Never unprotected: `onionPost`, `nostrSend` and the onion inbox
  (Tor-only by construction).

## 3. Every host native contacts

| Host | Why | When | Route | iOS |
|---|---|---|---|---|
| `mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion` `/api/v1/prices` | price, source 1 | `price` | Tor (onion, plain http through SOCKS) | `Foxy/Bridge/FoxyBridge.swift:593`, `:622` |
| `api.kraken.com` `/0/public/Ticker` | price, source 2 | `price` | Tor or unprotected | `FoxyBridge.swift:625` |
| `www.bitstamp.net` | price, source 3 | `price` | Tor or unprotected | `FoxyBridge.swift:632` |
| `blockchain.info` `/ticker` | price, source 4 | `price` | Tor or unprotected | `FoxyBridge.swift:636` |
| `api.gemini.com` | price, source 5 | `price` | Tor or unprotected | `FoxyBridge.swift:640` |
| `api.coinbase.com` | price, source 6 | `price` | Tor or unprotected | `FoxyBridge.swift:644` |
| `api.kraken.com` `/0/public/OHLC` | chart | `candles` | Tor or unprotected | `FoxyBridge.swift:715` |
| `mint.minibits.cash`, `mint.westernbtc.com`, `mint.macadamia.cash`, `nofee.testnut.cashu.space`, `testnut.cashu.space` | the default mints (no approval alert) | `mintRequest` | Tor or unprotected | `Foxy/Network/Route.swift:661-664`, `build/wallet/10-connection.js:14`, `build/app/06-wallet-boot-and-restore.js:585-597` |
| any mint the person adds | the wallet | `mintRequest` | Tor or unprotected, after an approval alert | `Route.swift:659-805` |
| any Lightning-address / LNURL server and its callback host | paying an address | `mintRequest` | Tor or unprotected, after an approval alert | `build/wallet/16-sending.js:442` |
| `mempoolhq…onion`, `explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion`, then `mempool.space`, `blockstream.info` | a card's clock: the newest block header | `mintRequest` | Tor (onions), Tor or unprotected (names) | `Route.swift:672-678`, `build/wallet/08b-block-headers.js:45-57` |
| `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.primal.net`, `wss://relay.minibits.cash`, or the relays a request names (at most five) | Nostr payment delivery and the Nostr inbox | `nostrSend`, `inboxOpen` (P3, ENG-677) | Tor only, a circuit per relay | `Foxy/Nostr/NostrDelivery.swift:18-19` |
| another phone's `.onion` | paying a request over Tor | `onionPost` (P3, ENG-672) | Tor only | `Foxy/Bridge/FoxyBridge+Delivery.swift:292-323` |
| this phone's own ephemeral `.onion` | receiving a payment | `inboxOpen` (P3, ENG-672) | Tor hidden service → `127.0.0.1` listener | `FoxyBridge+Delivery.swift:111-150` |
| Tor relays and directory authorities | Tor itself | always, from launch | direct (that is Tor) | `Foxy/Tor/TorService.swift`, `Foxy/TorSeed/` |
| 7 obfs4 bridges (fixed IPs) | Tor through obfs4 when direct fails | transport ladder | direct to the bridge | `Foxy/Tor/Bridges.swift:24-32` |
| Snowflake broker via CDN front (`1098762253.rsc.cdn77.org`, fronts `app.datapacket.com`, `www.datapacket.com`), public STUN servers, a volunteer proxy | Tor through Snowflake, last resort | transport ladder | **outside Tor, by how Snowflake works** | `Bridges.swift:34-37` |
| `127.0.0.1:15182` | Orbot's local API (is Orbot running, its bypass port) | at launch and every 15 s in the foreground | loopback | `Foxy/Network/OrbotLink.swift:26`, `:126`. **Android: never contacted** (Orbot is `none`, `orbotRefresh` and `orbotAccess` are LATER: ANDROID-MAPPING decision 3) |
| `https://cash.app`, `https://strike.me`, `https://river.com`, `https://getflash.io` | GET DOLLARS links | `openCompany` | **the system browser, outside Foxy and Tor** | `FoxyBridge.swift:1042-1047` |
| `https://check.torproject.org/api/ip` | resume diagnostics | **Debug builds only** | Tor | `Foxy/Debug/ResumeDiagnostics.swift:83`. Android has no resume diagnostics: never contacted |

Nothing else. There is no server of Foxy's, no analytics, no crash reporter,
no push service. Android must add none: no Firebase, no Play Services, no
WebView metrics (opt out in the manifest), no Safe Browsing (it calls Google;
off in the manifest and in `WebSettingsCompat`).

## 4. The WebView has no network

iOS builds four walls (`Foxy/FoxyWebView.swift`):

1. **A content rule list** blocking `http`, `https`, `ws`, `wss` and `ftp` for
   every load the page's engine makes (`FoxyWebView.swift:370-376`); the page is
   not loaded until it compiles (`FoxyWebView.swift:405-422`).
2. **A dead-end proxy** for the web view's own network process: SOCKS5 to
   `127.0.0.1:9` with no failover (`FoxyWebView.swift:89-103`), which catches
   what a rule list does not see (a `<link rel="preconnect">`).
3. **The page's CSP** (`Web/index.html:5`): `default-src 'self' file: blob:
   data:`, `connect-src 'self' file: blob: data:`, `object-src 'none'`,
   `base-uri 'none'`, `frame-src 'none'`, `form-action 'none'`; scripts only
   from the page and one hashed inline loader. On Android `'self'` is
   `https://appassets.androidplatform.net`, which the asset loader answers, so
   the policy keeps its meaning.
4. **No WebRTC** in any frame, removed before the page runs
   (`FoxyWebView.swift:138-141`, `:434-442`), because its UDP is seen by none of
   the above. And no navigation (`FoxyWebView.swift:1371-1392`), no link
   previews (`:124`), no text-selection services (`:146-156`).

### What Android must do to keep it

| Wall | Android |
|---|---|
| Serve only the page | `WebViewAssetLoader` with one `AssetsPathHandler` at `https://appassets.androidplatform.net/assets/`; the page loads at `…/assets/web/index.html`. `shouldInterceptRequest` returns the loader's response for those paths and **a 403 with an empty body for every other URL**, never `null` (null lets the WebView fetch it). |
| No network for anything the engine starts | `WebSettings.setBlockNetworkLoads(true)` as a second wall (verify on a device in P1 that asset-loader responses still load with it on), and `ProxyController.setProxyOverride` to a dead-end proxy at `127.0.0.1:9` with no direct fallback (androidx.webkit `PROXY_OVERRIDE`), so a preconnect or prefetch that never reaches `shouldInterceptRequest` still fails. |
| No navigation | `shouldOverrideUrlLoading` returns `true` (deny) for every URL; the page is loaded once with `loadUrl` from native. `setSupportMultipleWindows(false)`, `onCreateWindow` returns `false`, `setJavaScriptCanOpenWindowsAutomatically(false)`. |
| No file or content URLs | `setAllowFileAccess(false)`, `setAllowContentAccess(false)`, no `file://` load at all. |
| No WebRTC, camera or microphone for the page | the iOS `noWebRTC` script via `addDocumentStartJavaScript` (all frames); `WebChromeClient.onPermissionRequest` denies everything. |
| No geolocation | `setGeolocationEnabled(false)`; `onGeolocationPermissionsShowPrompt` denies. |
| No Google services in the WebView | manifest `android.webkit.WebView.EnableSafeBrowsing=false`, `android.webkit.WebView.MetricsOptOut=true`; `WebSettingsCompat.setSafeBrowsingEnabled(false)`. |
| Bridge only from the page | `addWebMessageListener` with the allowed-origin rule `https://appassets.androidplatform.net` only; `isMainFrame` required. Never `addJavascriptInterface`. |
| Prove it | a P1 instrumented test equivalent to iOS `-FoxyNetBlockTest` (`FoxyWebView.swift:553-609`): from the page, `fetch`, WebSocket, preconnect, DNS prefetch, prefetch, `<img>`, beacon and a Worker's fetch to a host whose listener records arrivals, with a local listener that must see nothing. |

## 5. Native requests on Android

As built in P2 (ENG-648). Kotlin paths are under
`app/src/main/java/io/islandbitcoin/foxy/`.

- **One HTTP client, built in one place.** `network/` owns OkHttp
  (`network/Route.kt` decides, `network/RouteHttp.kt` builds the two clients
  and runs the calls) and is the only code that makes a network call; the
  bridge carries a parsed URL as `RouteUrl` and never names OkHttp. No
  `HttpURLConnection`, `URL.openStream`, `DownloadManager`, Cronet, Volley,
  Ktor, `WebView.loadUrl` of a remote URL or `InetAddress` lookup anywhere else,
  no `getByName`/`getAllByName` anywhere at all, and no `java.net.URL` built or
  named; a socket is opened, and an `InetAddress` made, only in
  `network/Socks5SocketFactory.kt`, and a socket is listened on only in
  `network/OnionInbox.kt` (the onion inbox, ENG-672: bound to 127.0.0.1, where
  only Tor's onion service forwards from outside the phone; it dials nothing).
  `tools/check-no-network.sh` greps the sources for each and fails the build
  (its self-test plants every case).
- **A payment posted to another phone's onion (ENG-672).** `onionPost` does
  not go through OkHttp: `network/OnionPost.kt` writes iOS's one HTTP/1.1 POST
  byte for byte down a `Socks5SocketFactory` socket (the circuit label as the
  login, the `.onion` by name, ATYP `0x03`). It takes `http://<v3>.onion` only,
  and only while Tor runs: never the open connection, even with CONTINUE
  WITHOUT TOR on, and not while the door is shut (Foxy being put away). The
  inbox's addresses are made and ended on tor-android's control link
  (`ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach`, `DEL_ONION`), only while
  Tor is up, and every one is ended when Foxy is put away, before Tor parks.
- **Through Tor's SOCKS port, with Tor resolving names.** OkHttp is given no
  proxy of Java's, a `Dns` that never resolves (`TorDns`: the name kept on a
  0.0.0.0 placeholder, no lookup), and a SOCKS5 socket factory of Foxy's
  (`Socks5SocketFactory`) that ignores the address and dials
  `127.0.0.1:<Tor's port>` (IPv4 loopback by its bytes: Android's
  `getLoopbackAddress()` is `::1`, where Tor does not listen). Port 9 while
  Tor's port is unknown. The CONNECT carries ATYP `0x03` and the name as
  OkHttp was given it (`InetSocketAddress.hostString`, never a reverse
  lookup); an IP literal is never handed on, and Tor's `SafeSocks 1` would
  refuse one. Tor's port is read from `net/listeners/socks` (loopback only, 20
  tries a second apart, a generation guard) and forgotten before anything that
  can move the listener (`tor/TorMachine.kt`).
- **Per-job circuits (ANDROID-MAPPING decision 1, decided).** The circuit
  label is the SOCKS5 username and password (RFC 1929), so Tor keeps each label
  on its own circuit (`IsolateSOCKSAuth`). Each label has its own socket
  factory and its own connection pool: OkHttp's `Address` does not compare
  socket factories, so with one pool a request on one label rode a connection
  another label had opened (`RouteClientTest`). The price's onion has one label
  per run; requests the page did not label share one per run, as iOS's
  unlabelled session did.
- **One retry, and only before the request.** A failure of the SOCKS handshake
  is thrown from the socket's `connect`, before OkHttp writes a byte of the
  request; that, and only that, is retried, once, after 0.4 s, on Tor's port
  as it is then (`FoxyBridge.swift:1447-1460`), within the time the first try
  left (iOS's retry, `FoxyBridge.swift:1460`, is a new task with its own
  60 s; Android shares the first try's time so the request stays inside the
  page's 65 s wait): a mint request is answered within 60 s of being sent
  however it went, and a retry with under 5 s left is not sent. The 60 s
  start when the request is sent, after any host-approval alert; the page's
  65 s wait (`foxy-wallet.js` `bridgeAsk('mintRequest', ..., 65000)`) starts
  when it asked, so an alert left open for more than 5 s can outlast the
  page's wait, as on iOS, whose request timeout also starts after the alert.
  The handshake waits for Tor's answer to its CONNECT as long as the request
  may, at most 60 s, iOS's request timeout (`TorService.swift:1831-1832`): a
  new label is a new circuit, and right after "circuit established" Tor took
  longer than the 30 s the handshake used to allow, so every first request on
  a label timed out, and its retry, for about two minutes (ENG-655,
  `MintRequestRetryTest`). OkHttp's own retry is off
  (`retryOnConnectionFailure(false)`), and a failure on a redirect's later hop
  is never "before the request". So a POST that reached the mint is never sent
  twice (`MintRequestRetryTest`, `RouteClientTest`). Two failed handshakes on
  two different ports (Tor's listener moved under the request) are
  "Foxy's private connection was rebuilt mid-request. Try again."; two on the
  same port, or the retry on port 9, are "Foxy is not connected to Tor."
  (`NetBridgeTest`); a handshake that ran out of the request's time waiting
  for Tor's answer (Tor connected, the label's circuit still being built),
  with or without a retry, is "The request timed out.", URLSession's words
  (`FoxyBridge.swift` `mintFailureWords`, `MintRequestRetryTest`); a refusal
  Tor itself answered (host unreachable, an onion not found) is
  "<host> could not be asked over Tor: <why>".
- **No fallback.** When Tor is not running, `Route.start` returns no call and
  the caller answers the refusal. There is no "try direct". Unprotected uses a
  separate client (the phone's resolver, `.onion` refused) with the same rules,
  made only while `unprotected` is set and dropped when it is cleared.
- **Cleartext off, except onions.** `network_security_config.xml`:
  `cleartextTrafficPermitted="false"` in `base-config`, and a `domain-config`
  with `<domain includeSubdomains="true">onion</domain>` permitting cleartext,
  because an onion speaks plain http over Tor (OkHttp consults this policy). No
  user-added CAs (`<certificates src="system"/>` only). Plain http to an onion
  goes through the same SOCKS factory as every other request.
- **The same URL rules** (`hostProblem` with a port of `inet_aton`'s grammar,
  `urlProblem`, `redirectProblem`), ported with their tests from
  `FoxyTests/RouteTests.swift`; redirects followed by Route itself
  (`followRedirects(false)`), each hop checked, a 307/308 keeping its method
  and body, anything else a GET.
- **The same answer cap** (4 MB: refused up front by `Content-Length`, cut off
  while reading), headers (Tor Browser's User-Agent and Accept-Language; only
  the page's `content-type` and `accept`), methods (GET and POST), no cookies,
  no cache, and host approvals (`network/HostApprovals.kt`, sealed with a
  Keystore key in `noBackupFilesDir`).
- **Tor itself** is tor-android's `TorService` in this process, bound from the
  application context and never started, so never in the foreground (its
  service is unexported, with no intent filter). tor-android fixes its data
  directory (`app_TorService/data`, 0700) and cache directory
  (`cache/TorService`) on its own command line, which a torrc cannot move, so
  Tor's state is not in `noBackupFilesDir` as iOS's equivalent is; the app's
  backup rules exclude every domain (S1), so neither leaves in a backup. The
  torrc is `tor/TorConfig.kt`'s; obfs4 and Snowflake are IPtProxy's, in
  process, with iOS's bridge lines byte for byte.
- **What leaves outside Tor, by design.** Tor's own connections (relays and
  directory authorities, or the seven obfs4 bridges), and for Snowflake its
  broker's CDN front and the STUN servers (looked up with the phone's resolver)
  and a volunteer proxy over UDP. The leak test (`tools/leak-test.sh`,
  `tools/leak-check.js`, `docs/DEVICE-TESTS.md`) holds every flow of the app's
  to exactly that, per transport, and to the mint only after the person chose
  to continue without Tor.
- **WebSockets** (Nostr relays, ENG-677): `network/RouteSocket.kt`, OkHttp
  `newWebSocket` on the Tor client only (`RouteHttp.torClient(label)`: the
  SOCKS factory with a fresh circuit label per relay as the login, the relay's
  name handed to Tor, `TorDns` looking nothing up), `wss` only, a host
  `Route.hostProblem` passes, not while the door is shut; never unprotected,
  even with CONTINUE WITHOUT TOR on. `tools/check-no-network.sh` fails on a
  WebSocket anywhere else. A Nostr inbox's sockets close with its request and
  when Foxy is put away, and none opens while it is.
- **The door and backgrounding.** `onStop` starts the putting-away sequence of
  `BRIDGE.md` §2: it waits up to 20 s while a swap, melt, mint or restore is
  out (the bridge's money in flight) or the change top-up runs, 3 s for any
  other request, then shuts the door and parks Tor (`DisableNetwork 1`: Tor has
  no connection to lose and holds nothing against its guards). The 20 s is a
  ceiling the page cannot hold open: a read of `_tidying` that has not
  answered in 1 s counts as not tidying (a WebView whose JS is throttled may
  never call back). That timeout is a main-looper post like the looks
  themselves, so a look is decided every 1.25 s at the latest whatever the
  page does, and the first past 20 s leaves: Tor is parked by 21.25 s at the
  latest (20.15 s for a page that never answers).
  There is no separate watchdog; the read timeout is what keeps the ceiling
  (`PutAwayLeavingTest`). Android gives no fixed background allowance, so Tor
  is not held on the network past that. Back finishing the activity (API 28-30) removes the
  observer before `onStop` arrives, so `MainActivity.onDestroy` shuts the door
  and parks Tor itself; the next activity sets Tor up again. A Tor whose
  thread has ended is marked away without a `DisableNetwork` (there is no
  daemon to send it to). Coming back (the return, or the next activity)
  launches it again. A RETRY while away (the page's `torRetry` keeps running
  in the background and fires on a network change) launches it off the
  network until Foxy comes back: its torrc carries `DisableNetwork 1`, so it
  boots with no connection at all (even if its control link never comes), and
  `DisableNetwork 1` is sent again when the link is up. A park that
  comes while Tor is still starting, before its control link is up, is held:
  iOS skips that case (`TorService.swift` `backgrounded`) because it suspends
  the process and a late bootstrap never runs, but Android keeps the process
  running, so `TorMachine` marks Tor parked and sends `DisableNetwork 1` the
  moment the link comes up, with no set-up, until Foxy comes back. A return sets Tor
  up again. No `WorkManager` job, alarm or foreground service
  makes requests while the app is away.
- **Set-ups that overlap** (ENG-652). Every set-up starts with
  `DisableNetwork 1`, which kills the circuits the set-up before it was
  building, and Tor records each as a failure of that circuit's guard. Three
  stacked inside a second (a return, the next activity's start, a restart)
  marked every primary guard unreachable on the Pixel: Tor still said
  `CIRCUIT_ESTABLISHED`, but held every new stream "waiting for circuit" and
  each SOCKS handshake timed out at 30 s, so the wallet was refused for half a
  minute. A RETRY or RESTART TOR within 4 s of a set-up that is still going
  (not failed, not parked, not off for want of a network) therefore joins it
  instead of starting another. A return from being put away always sets up,
  however soon: Tor is off the network then. Nothing a superseded set-up asked
  for acts after it: its settings' reply does not switch the network back on
  (which would put a parked Tor online, S21), and a `CIRCUIT_ESTABLISHED`
  heard between a `DisableNetwork 1` and the answer to the `DisableNetwork 0`
  after it is about a circuit that switch closed, so it does not make Foxy
  ready. The port Route dials is read per request and every pooled
  connection is dropped whenever the port is forgotten (`RouteHttp.forgetTorConnections`).
- **The phone's network.** `network/NetworkKind.kt` watches the default
  network (wifi, cellular, wired, other, none; a VPN with nothing under it is
  none); with none, Tor comes off the network after 1.5 s and the ladder learns
  nothing until a network is back.
- **P1, before Tor existed** (ENG-647): `Route.available` was always false, so
  `price`, `candles` and `mintRequest` answered the refusal and nothing was
  contacted. The answers P1 gave in place of a later phase's are the `p1`
  stubs in `conformance/contract.json` (`BRIDGE.md` §2, *P1 answers*); a P2
  host answers them only for P3 and LATER actions.
