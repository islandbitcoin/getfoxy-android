# ROUTE.md — every byte leaves through Tor, or not at all

**The invariant.** Every network request Foxy makes goes through the app's own
Tor, or it is refused before a socket opens. The one exception is a choice the
person makes in a native dialog, for the current session only: continue
without Tor. The page itself has no network at all. This is the iOS app's
design at getfoxy/iOS `5581c963dd29b28ebec547473ada532873d49a77`, and the
Android port keeps it unchanged. Line references are to that commit
(`conformance/test/contract.test.js` checks each one).

## 1. How the page asks for anything

The page never fetches. Everything it wants from outside goes over the bridge
(`BRIDGE.md`), and native decides the route per request:

| What | How the page asks | Page code |
|---|---|---|
| every mint call (info, keysets, keys, quotes, mint, swap, melt, checkstate, restore) | cashu-ts `Mint` built with `customRequest: FoxyWallet.nativeRequest`, which posts `mintRequest` | `Web/foxy-wallet.js:3301-3322` (`newWallet`), `Web/foxy-wallet.js:25193` (`nativeRequest`), `Web/foxy-wallet.js:25222` (the post) |
| a Lightning address / LNURL lookup and callback | `FoxyWallet.nativeRequest`, so `mintRequest` | `build/wallet/16-sending.js:422` |
| the newest Bitcoin block header (a card's clock) | `nativeRequest` to two explorer onions, then their clearnet names | `build/wallet/08b-block-headers.js:45-57`, `:170` |
| the BTC/USD price | `price` | `Web/foxy-wallet.js:15139` |
| the price chart | `candles` | `Web/foxy-wallet.js:15215` |
| a payment to another phone's onion | `onionPost` (LATER) | `Web/foxy-wallet.js:8637` |
| a payment over Nostr | `nostrSend` (LATER) | `Web/foxy-wallet.js:8664` |

**No WebSocket.** NUT-17 subscriptions are not used: cashu-ts would open
`wss://<mint>/v1/ws` from the page, which no native route could carry, so the
wallet polls the quote over `mintRequest` instead (`Web/foxy-wallet.js:16674`).
The only WebSockets in the app are native ones to Nostr relays, over Tor
(`Foxy/Network/Route.swift:248-259`, `startSocket`; LATER).

**The page's own fetch paths are dead code in the app.** `rate()` and
`candles()` fall back to `fetch` only when `window.webkit.messageHandlers.foxy`
does not exist, as in a desktop browser (`Web/foxy-wallet.js:15175`,
`Web/foxy-wallet.js:15230`). On Android the shim defines it at document start,
before any page script, so those branches never run; and if they did, the
WebView's network block below refuses them. The conformance suite fails on any
`fetch`, XHR, WebSocket, EventSource, beacon, WebRTC, subresource or DOM URL
outside the asset origin (`conformance/lib/page.js`).

**The page gates itself too, and the gate is not what holds.** The wallet
checks the route before work that needs one (`routeOpen`,
`Web/foxy-wallet.js:2146`; `assertRoute`, `Web/foxy-wallet.js:2225`), so its
background sweeps and invoice watches ask nothing while there is no route
(`sweepQuotes`, `Web/foxy-wallet.js:16911`). That only makes the screens
friendlier. Native refuses regardless; the page is not trusted to have asked.
Conformance proves the page's half: with Tor connecting, then failed, the page
posts no `mintRequest`, `price` or `candles` until the person opts out
(`conformance/test/flows.test.js`, "route closed").

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
| `mint.minibits.cash`, `mint.westernbtc.com`, `mint.macadamia.cash`, `nofee.testnut.cashu.space`, `testnut.cashu.space` | the default mints (no approval alert) | `mintRequest` | Tor or unprotected | `Foxy/Network/Route.swift:661-664`, `build/wallet/10-connection.js:14`, `build/app/06-wallet-boot-and-restore.js:579-591` |
| any mint the person adds | the wallet | `mintRequest` | Tor or unprotected, after an approval alert | `Route.swift:659-805` |
| any Lightning-address / LNURL server and its callback host | paying an address | `mintRequest` | Tor or unprotected, after an approval alert | `build/wallet/16-sending.js:422` |
| `mempoolhq…onion`, `explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion`, then `mempool.space`, `blockstream.info` | a card's clock: the newest block header | `mintRequest` | Tor (onions), Tor or unprotected (names) | `Route.swift:672-678`, `build/wallet/08b-block-headers.js:45-57` |
| `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.primal.net`, `wss://relay.minibits.cash`, or the relays a request names (at most five) | Nostr payment delivery and the Nostr inbox | `nostrSend`, `inboxOpen` (LATER) | Tor only | `Foxy/Nostr/NostrDelivery.swift:18-19` |
| another phone's `.onion` | paying a request over Tor | `onionPost` (LATER) | Tor only | `Foxy/Bridge/FoxyBridge+Delivery.swift:292-323` |
| this phone's own ephemeral `.onion` | receiving a payment | `inboxOpen` (LATER) | Tor hidden service → `127.0.0.1` listener | `FoxyBridge+Delivery.swift:111-150` |
| Tor relays and directory authorities | Tor itself | always, from launch | direct (that is Tor) | `Foxy/Tor/TorService.swift`, `Foxy/TorSeed/` |
| 7 obfs4 bridges (fixed IPs) | Tor through obfs4 when direct fails | transport ladder | direct to the bridge | `Foxy/Tor/Bridges.swift:24-32` |
| Snowflake broker via CDN front (`1098762253.rsc.cdn77.org`, fronts `app.datapacket.com`, `www.datapacket.com`), public STUN servers, a volunteer proxy | Tor through Snowflake, last resort | transport ladder | **outside Tor, by how Snowflake works** | `Bridges.swift:34-37` |
| `127.0.0.1:15182` | Orbot's local API (is Orbot running, its bypass port) | at launch and every 15 s in the foreground | loopback | `Foxy/Network/OrbotLink.swift:26`, `:126` |
| `https://cash.app`, `https://strike.me`, `https://river.com`, `https://getflash.io` | GET DOLLARS links | `openCompany` | **the system browser, outside Foxy and Tor** | `FoxyBridge.swift:1042-1047` |
| `https://check.torproject.org/api/ip` | resume diagnostics | **Debug builds only** | Tor | `Foxy/Debug/ResumeDiagnostics.swift:83` |

Nothing else. There is no server of Foxy's, no analytics, no crash reporter,
no push service. Android must add none: no Firebase, no Play Services, no
WebView metrics (opt out in the manifest), no Safe Browsing (it calls Google;
off in the manifest and in `WebSettingsCompat`).

## 4. The WebView has no network

iOS builds four walls (`Foxy/FoxyWebView.swift`):

1. **A content rule list** blocking `http`, `https`, `ws`, `wss` and `ftp` for
   every load the page's engine makes (`FoxyWebView.swift:354-360`); the page is
   not loaded until it compiles (`FoxyWebView.swift:389-406`).
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
   the above. And no navigation (`FoxyWebView.swift:1338-1359`), no link
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
| Prove it | a P1 instrumented test equivalent to iOS `-FoxyNetBlockTest` (`FoxyWebView.swift:529-585`): from the page, `fetch`, WebSocket, preconnect, DNS prefetch, prefetch, `<img>`, beacon and a Worker's fetch to a host whose listener records arrivals, with a local listener that must see nothing. |

## 5. Native requests on Android

- **One HTTP client, built in one place.** `Route.kt` owns OkHttp and is the only
  code that makes a network call. No `HttpURLConnection`, `URL.openStream`,
  `DownloadManager`, Cronet, Volley, Ktor, `WebView.loadUrl` of a remote URL or
  `InetAddress` lookup anywhere else; a CI check (`tools/check-no-network.sh`,
  P1) greps the Kotlin sources for each and fails the build.
- **Through Tor's SOCKS port, with Tor resolving names.** OkHttp with
  `Proxy(Proxy.Type.SOCKS, 127.0.0.1:<port read from Tor's control port>)`, so
  OkHttp passes the hostname unresolved and Tor resolves it; `SafeSocks 1` makes
  Tor refuse any request that arrives with an IP. Port 9 while Tor's port is
  unknown. A client per request (or per circuit label), made with the port as it
  is now, as iOS does.
- **Per-job circuits.** iOS puts the circuit label in the SOCKS5
  username/password. Java's built-in SOCKS client takes credentials only from
  the process-wide `java.net.Authenticator`, which cannot carry a per-request
  label safely across threads. Proposed for P2: a small SOCKS5 client
  (`SocketFactory` that performs the handshake with the domain name and the
  label as username), with OkHttp given a `Dns` that never resolves (returns a
  placeholder address the SOCKS factory ignores). This is listed for the
  architect in `ANDROID-MAPPING.md`.
- **No fallback.** When Tor is not running, `Route.start` returns no call and
  the caller answers the refusal. There is no "try direct". Unprotected uses a
  separate client with the same rules, created only while `unprotected` is set.
- **Cleartext off, except onions.** `network_security_config.xml`:
  `cleartextTrafficPermitted="false"` in `base-config`, and a `domain-config`
  with `<domain includeSubdomains="true">onion</domain>` permitting cleartext,
  because an onion speaks plain http over Tor (OkHttp consults this policy). No
  user-added CAs (`<certificates src="system"/>` only).
- **The same URL rules** (`hostProblem`, `urlProblem`, `redirectProblem`),
  ported with their tests from `FoxyTests/RouteTests.swift`; redirects followed
  by Route itself (`followRedirects(false)`), each hop checked.
- **The same answer cap** (4 MB, while reading), headers, methods and host
  approvals.
- **WebSockets** (Nostr relays, LATER): OkHttp `newWebSocket` on the Tor client
  only, `wss` only, host rules applied; never unprotected.
- **The door and backgrounding.** `onStop` starts the putting-away sequence of
  `BRIDGE.md` §2; Android gives no fixed background allowance, so Tor is parked
  when the work is done or after 20 s, whichever first, and nothing new leaves
  meanwhile. No `WorkManager` job, alarm or foreground service makes requests
  while the app is away.
- **P1, before Tor exists:** `Route.available` is always false, so `price`,
  `candles` and `mintRequest` answer the refusal and nothing is contacted. The
  unprotected path is not built until P2.
