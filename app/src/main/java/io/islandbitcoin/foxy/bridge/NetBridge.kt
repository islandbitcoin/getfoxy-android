package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.TextStore
import io.islandbitcoin.foxy.network.HostApprovals
import io.islandbitcoin.foxy.network.MintCircuit
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.network.RouteAnswer
import io.islandbitcoin.foxy.network.RouteHttp
import io.islandbitcoin.foxy.network.RouteRequest
import io.islandbitcoin.foxy.network.RouteUrl
import io.islandbitcoin.foxy.tor.Snapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What the P2 actions need from the rest of the app, passed in so the JVM
 * tests run them with a fake Tor, a fake route and a queue that answers.
 */
class NetEnvironment(
    /** Tor carries requests now. */
    val torRunning: () -> Boolean,
    /** Tor is up over a bridge (obfs4 or Snowflake): the price sources get longer. */
    val bridged: () -> Boolean,
    /** What the page is told: Route.snapshot. */
    val snapshot: () -> String,
    /** RESTART TOR (TorService.restart). */
    val restartTor: (String) -> Unit,
    /** The person's choice for this session (Route.unprotected). */
    val unprotected: () -> Boolean,
    val setUnprotected: (Boolean) -> Unit,
    /** Tor or the person's yes: a request would go somewhere (Route.available). */
    val available: () -> Boolean,
    val approvals: HostApprovals,
    val prompts: PromptQueue,
    /**
     * One request on the route as it is now (Route.start): how to call it off,
     * or null with nothing sent when there is no route.
     */
    val send: (RouteRequest, String, (RouteAnswer) -> Unit) -> (() -> Unit)?,
    /** A run on the main thread after `ms`. */
    val after: (Long, () -> Unit) -> Unit,
    /** Wall-clock seconds, for the chart's range. */
    val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * A mint request's whole time, its one retry included (iOS's 60 s request
     * timeout, TorService.swift:1831-1832), counted from when it is sent, after
     * any host-approval alert: within the page's 65 s wait (foxy-wallet.js
     * `bridgeAsk('mintRequest', ..., 65000)`, counted from when the page asked)
     * only when that alert took under 5 s, as on iOS. Tests shorten it.
     */
    val mintRequestMs: Long = 60_000,
) {
    companion object {
        private val timers = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "foxy-net-timer").apply { isDaemon = true }
        }

        /**
         * No Tor and no route: the no-Tor snapshot, every request refused before
         * anything is sent, no dialog to ask in (a no). What a host with nothing
         * wired answers; the JVM tests start from it.
         */
        fun noRoute(prompts: PromptQueue = PromptQueue { _, _ -> null }): NetEnvironment = NetEnvironment(
            torRunning = { false },
            bridged = { false },
            snapshot = { Snapshot.noTor() },
            restartTor = {},
            unprotected = { false },
            setUnprotected = {},
            available = { false },
            approvals = HostApprovals(TextStore.Memory(), TextStore.Memory(), prompts),
            prompts = prompts,
            send = { _, _, _ -> null },
            after = { ms, r -> timers.schedule(r, ms, java.util.concurrent.TimeUnit.MILLISECONDS) },
        )
    }
}

/**
 * The seven P2 actions (docs/BRIDGE.md, phase P2): `privacy`, `torRetry`,
 * `unprotected`, `mintRequest`, `price`, `candles`, `hostsKnown`. Ports of
 * Foxy/Bridge/FoxyBridge.swift's handlers, with its rules:
 *
 * - `mintRequest`, in this order: the URL parses; [Route.urlProblem]; GET or
 *   POST; [Route.available] or the refusal; [HostApprovals] (an unknown host is
 *   asked about in a native dialog first); then sent on the circuit the page
 *   labelled. Only `content-type` and `accept` are passed. A request whose
 *   SOCKS handshake failed (no byte of it left) is sent once more after 0.4 s;
 *   nothing else is, so a POST whose bytes left is never sent twice.
 * - a swap, melt, mint or restore POST is counted in [moneyInFlight] until it
 *   is answered, so putting Foxy away waits for it.
 *
 * Every handler is called on the main thread (the message listener's) and
 * answers through `resolve`, from any thread.
 */
class NetBridge(
    private val env: NetEnvironment,
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
) {
    companion object {
        val ACTIONS = setOf("privacy", "torRetry", "unprotected", "mintRequest", "price", "candles", "hostsKnown")

        const val NOT_A_REQUEST = "That is not a request Foxy will send."
        const val RETRY_AFTER_MS = 400L

        /**
         * A retry with less time than this left is not sent: a handshake that
         * waited out the request's time for Tor is answered, not asked again
         * on a circuit that would have no time to be built (ENG-655).
         */
        const val LEAST_RETRY_MS = 5_000L

        /** The methods a mint, a lightning-address server or an LNURL callback is asked with (FoxyBridge.swift:950-953). */
        fun mintMethod(body: Map<String, Any?>): String? {
            val method = ((body["method"] as? String) ?: "GET").uppercase()
            return if (method == "GET" || method == "POST") method else null
        }

        /** A swap, a melt, a mint of new pieces or a restore: money that is waited for (FoxyBridge.swift:1505-1510). */
        fun movesMoney(method: String, path: String): Boolean {
            if (method != "POST") return false
            if (path.contains("/quote") || path.endsWith("/checkstate")) return false
            return path.endsWith("/v1/swap") || path.endsWith("/v1/restore") || path.contains("/v1/melt/") || path.contains("/v1/mint/")
        }

        /** The page's headers, only `content-type` and `accept` (FoxyBridge.swift:1418-1423), and JSON for a body with no type. */
        fun passedHeaders(given: Any?, hasBody: Boolean): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            (given as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String && v is String && k.lowercase() in setOf("content-type", "accept") && '\n' !in v && '\r' !in v) out[k] = v
            }
            if (hasBody && out.keys.none { it.lowercase() == "content-type" }) out["Content-Type"] = "application/json"
            return out
        }

        const val MEMPOOL_ONION = "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"

        /**
         * One circuit label for the price's onion, made as the app starts, and
         * one for every request the page did not label (iOS's unlabelled
         * session): within a run the asks ride circuits already built, never a
         * mint's.
         */
        val PRICE_CIRCUIT: String = MintCircuit.label(null)
        val SHARED_CIRCUIT: String = MintCircuit.label(null)

        /** Six operators, in order (FoxyBridge.swift:601-650): mempool's onion leads, Kraken follows, Coinbase is last. */
        val PRICE_SOURCES: List<Pair<String, (Any?) -> Double?>> = listOf(
            "http://$MEMPOOL_ONION/api/v1/prices" to { j -> ((j as? Map<*, *>)?.get("USD") as? Double) },
            "https://api.kraken.com/0/public/Ticker?pair=XBTUSD" to { j ->
                val r = (j as? Map<*, *>)?.get("result") as? Map<*, *>
                val pair = r?.values?.firstOrNull() as? Map<*, *>
                ((pair?.get("c") as? List<*>)?.firstOrNull() as? String)?.toDoubleOrNull()
            },
            "https://www.bitstamp.net/api/v2/ticker/btcusd/" to { j -> ((j as? Map<*, *>)?.get("last") as? String)?.toDoubleOrNull() },
            "https://blockchain.info/ticker" to { j -> (((j as? Map<*, *>)?.get("USD") as? Map<*, *>)?.get("last") as? Double) },
            "https://api.gemini.com/v1/pubticker/btcusd" to { j -> ((j as? Map<*, *>)?.get("last") as? String)?.toDoubleOrNull() },
            "https://api.coinbase.com/v2/prices/BTC-USD/spot" to { j ->
                (((j as? Map<*, *>)?.get("data") as? Map<*, *>)?.get("amount") as? String)?.toDoubleOrNull()
            },
        )

        /** A price as iOS's String(Double) writes it: "65000.5", "65000.0". */
        fun priceText(p: Double): String = if (p < 1e7) p.toString() else java.math.BigDecimal(p).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

        /** Kraken's intervals, chosen so the range fits 720 candles (FoxyBridge.swift:696-748). */
        fun candleInterval(g: Int, span: Int): Int {
            val allowed = listOf(1, 5, 15, 30, 60, 240, 1440, 10080, 21600)
            var interval = allowed.firstOrNull { it >= maxOf(1, g / 60) } ?: 21600
            while (span / (interval * 60) > 720) interval = allowed.firstOrNull { it > interval } ?: break
            return interval
        }

        /** [[t, close], …], oldest first, from Kraken's OHLC answer; null when it is not one. */
        fun candleRows(text: String): List<Pair<Double, Double>>? {
            val json = try {
                Json.parse(text) as? Map<*, *>
            } catch (e: Json.Malformed) {
                null
            } ?: return null
            val result = json["result"] as? Map<*, *> ?: return null
            // the pair's key varies (XXBTZUSD), so the first array value
            val series = result.values.firstOrNull { it is List<*> } as? List<*> ?: emptyList<Any?>()
            return series.mapNotNull { row ->
                val r = row as? List<*> ?: return@mapNotNull null
                if (r.size <= 4) return@mapNotNull null
                val t = r[0] as? Double ?: return@mapNotNull null
                val c = when (val v = r[4]) {
                    is String -> v.toDoubleOrNull()
                    is Double -> v
                    else -> null
                } ?: return@mapNotNull null
                t to c
            }
        }

        fun candleJson(rows: List<Pair<Double, Double>>): String =
            rows.joinToString(",", "[", "]") { (t, c) -> "[${number(t)},${number(c)}]" }

        private fun number(d: Double): String =
            if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    /** The requests that move money and are not yet answered, by id: putting Foxy away waits for them. */
    val moneyInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var confirming = false

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "privacy" -> resolve(id, env.snapshot(), null)
            "torRetry" -> {
                env.restartTor("the person asked")
                resolve(id, env.snapshot(), null)
            }
            "unprotected" -> unprotected(id, body)
            "hostsKnown" -> {
                val hosts = (body["hosts"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                env.approvals.carryOver(hosts)
                resolve(id, "ok", null)
            }
            "mintRequest" -> mintRequest(id, body)
            "price" -> price(id, body)
            "candles" -> candles(id, body)
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    /** What has been said to the page: the _privacy push, through the one evaluate path. */
    fun pushPrivacy() {
        val js = "window.FoxyWallet && window.FoxyWallet._privacy && window.FoxyWallet._privacy(${env.snapshot()})"
        host.post { host.evaluate(js) }
    }

    // MARK: CONTINUE WITHOUT TOR

    /** Turning it on needs a yes in a native dialog; off needs nothing; ignored while Tor runs (FoxyBridge.swift:878-902). */
    private fun unprotected(id: String, body: Map<String, Any?>) {
        val on = body["on"] as? Boolean ?: false
        if (!on) {
            env.setUnprotected(false)
            pushPrivacy()
            return resolve(id, env.snapshot(), null)
        }
        if (env.torRunning()) return resolve(id, env.snapshot(), null)
        confirmUnprotected { yes ->
            env.setUnprotected(yes && !env.torRunning())
            Log.line("[foxy] unprotected: ${env.unprotected()} ${if (yes) "(confirmed in the dialog)" else "(declined)"}")
            pushPrivacy()
            resolve(id, env.snapshot(), null)
        }
    }

    private fun confirmUnprotected(done: (Boolean) -> Unit) {
        if (confirming) return done(false)
        confirming = true
        env.prompts.ask(
            Prompt(
                title = "Continue without Tor?",
                message = "The mint, and anyone you pay, will see this phone's IP address. Foxy keeps trying Tor and switches to it as soon as it connects.",
                no = "Stay protected",
                yes = "Continue without Tor",
                destructive = true,
            ),
        ) { yes ->
            confirming = false
            done(yes)
        }
    }

    // MARK: mintRequest

    private fun mintRequest(id: String, body: Map<String, Any?>) {
        val url = (body["url"] as? String)?.let { Route.parse(it) } ?: return resolve(id, null, Route.NOT_A_MINT)
        Route.urlProblem(url, env.torRunning())?.let { return resolve(id, null, it) }
        val method = mintMethod(body) ?: return resolve(id, null, NOT_A_REQUEST)
        if (!env.available()) return resolve(id, null, Route.REFUSAL)
        val host = url.host
        if (!env.approvals.isApproved(host)) {
            env.approvals.ask(host) { allowed ->
                if (allowed) send(id, body, url, method) else resolve(id, null, "You did not allow Foxy to contact ${HostApprovals.normal(host)}.")
            }
            return
        }
        send(id, body, url, method)
    }

    private fun send(id: String, body: Map<String, Any?>, url: RouteUrl, method: String) {
        val payload = (body["body"] as? String)?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val headers = passedHeaders(body["headers"], payload != null)
        if (movesMoney(method, url.encodedPath)) moneyInFlight.add(id)
        val circuit = MintCircuit.label(body["circuit"])
        // the retry shares this time rather than starting its own: answered within it however it went,
        // counted from here, after any host approval (the page's own wait started before the alert)
        val endsBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(env.mintRequestMs)
        val request = RouteRequest(url, method, headers, if (method == "POST") payload else null, timeoutMs = env.mintRequestMs, endsBy = endsBy)
        attempt(id, request, circuit, triesLeft = 1, firstDialled = 0)
    }

    /** `firstDialled`: the SOCKS port the first try's failed handshake used, 0 on the first try. */
    private fun attempt(id: String, request: RouteRequest, circuit: String, triesLeft: Int, firstDialled: Int) {
        val host = request.url.host
        val call = env.send(request, circuit) { answer ->
            when (answer) {
                is RouteAnswer.Done -> resolve(id, "${answer.status}\n" + answer.text, null)
                RouteAnswer.TooLarge -> resolve(id, null, "The answer from $host was too large.")
                is RouteAnswer.Failed -> {
                    val left = TimeUnit.NANOSECONDS.toMillis(request.endsBy - System.nanoTime()) - RETRY_AFTER_MS
                    if (answer.beforeRequest && triesLeft > 0 && left >= LEAST_RETRY_MS) {
                        Log.line("[foxy] mint request to $host never left (Tor's SOCKS handshake failed: ${answer.error.message}); asking again")
                        env.after(RETRY_AFTER_MS) { attempt(id, request, circuit, triesLeft - 1, answer.dialled) }
                        return@send
                    }
                    Log.line("[foxy] mint request to $host failed: ${answer.error.javaClass.simpleName} (${answer.error.message})")
                    // Tor answered the stream with a refusal of its own (host unreachable, an onion not found):
                    // that names a real thing, as iOS's onion path names it; anything else before the request
                    // is Tor's listener moving under it when the retry dialled another port, and Tor not
                    // being there when it dialled the same one
                    val code = RouteHttp.torRefusal(answer.error)
                    val moved = RouteHttp.portMoved(firstDialled, answer.dialled)
                    val words = RouteHttp.failureWords(answer.error, answer.beforeRequest, portMoved = moved)
                    resolve(id, null, when {
                        code != null -> "$host could not be asked over Tor: $code"
                        host.endsWith(".onion") && !answer.beforeRequest -> "$host could not be asked over Tor: $words"
                        else -> words
                    })
                }
            }
        }
        if (call == null) resolve(id, null, Route.REFUSAL)
    }

    // MARK: price and candles

    private fun price(id: String, body: Map<String, Any?>) {
        if (!env.available()) return resolve(id, "", Route.REFUSAL)
        val bridged = env.bridged()
        val agree = body["agree"] as? Boolean ?: false
        PriceWalk(
            sources = PRICE_SOURCES, together = 2,
            perSourceMs = if (bridged) 20_000 else 4_000, onionMs = if (bridged) 25_000 else 10_000,
            agree = agree, env = env, host = host,
        ) { price, error ->
            resolve(id, price?.let { priceText(it) } ?: "", if (price == null) error else null)
        }.start(limitMs = if (agree) (if (bridged) 45_000 else 25_000) else (if (bridged) 30_000 else 12_000))
    }

    private fun candles(id: String, body: Map<String, Any?>) {
        if (!env.available()) return resolve(id, "", Route.REFUSAL)
        // within what the chart offers: a number from the page must not overflow the arithmetic
        val want = ((body["g"] as? Double)?.takeIf { it.isFinite() }?.toLong() ?: 3600L).coerceIn(60, 86_400).toInt()
        val span = ((body["span"] as? Double)?.takeIf { it.isFinite() }?.toLong() ?: 86_400L).coerceIn(3_600, 1_461L * 86_400).toInt()
        val interval = candleInterval(want, span)
        val since = env.nowSeconds() - span
        val url = Route.parse("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=$interval&since=$since")!!
        val call = env.send(RouteRequest(url, timeoutMs = 12_000), SHARED_CIRCUIT) { answer ->
            when (answer) {
                is RouteAnswer.Failed -> resolve(id, "", RouteHttp.failureWords(answer.error, false))
                RouteAnswer.TooLarge -> resolve(id, "", "unreadable candles")
                is RouteAnswer.Done -> {
                    val rows = candleRows(answer.text) ?: return@send resolve(id, "", "unreadable candles")
                    Log.line("[foxy] candles: ${rows.size} at $interval min")
                    resolve(id, candleJson(rows), null)
                }
            }
        }
        if (call == null) resolve(id, "", Route.REFUSAL)
    }
}

/**
 * One `price` request (FoxyBridge.swift:1525-1680): the sources in order, two
 * at a time, each started on the route as it is at that moment, the first
 * usable answer winning; with `agree`, two within 2% of each other. Main thread.
 */
class PriceWalk(
    private val sources: List<Pair<String, (Any?) -> Double?>>,
    together: Int,
    private val perSourceMs: Long,
    private val onionMs: Long,
    private val agree: Boolean,
    private val env: NetEnvironment,
    private val host: PageHost,
    private val finish: (Double?, String) -> Unit,
) {
    private val together = maxOf(1, together)
    private val answers = mutableListOf<Double>()
    /** How to call off each source still asking. */
    private val running = HashMap<Int, () -> Unit>()
    private var next = 0
    private var over = false

    private val nothing: String
        get() = if (agree && answers.isNotEmpty()) "the price sources did not agree" else "no price source answered"

    fun start(limitMs: Long) {
        env.after(limitMs) { end(null, nothing) }
        fill()
    }

    private fun fill() {
        while (!over && running.size < together && next < sources.size) {
            val i = next
            next += 1
            val url = Route.parse(sources[i].first) ?: continue
            val onion = url.host.endsWith(".onion")
            val circuit = if (onion) NetBridge.PRICE_CIRCUIT else NetBridge.SHARED_CIRCUIT
            val call = env.send(RouteRequest(url, timeoutMs = if (onion) onionMs else perSourceMs), circuit) { answer ->
                host.post { answered(i, answer) }
            }
            if (call == null) {
                end(null, Route.REFUSAL)
                return
            }
            running[i] = call
        }
        if (!over && running.isEmpty()) end(null, nothing)
    }

    private fun answered(i: Int, answer: RouteAnswer) {
        running.remove(i)
        if (over) return
        val text = (answer as? RouteAnswer.Done)?.takeIf { it.status == 200 }?.text
        if (answer is RouteAnswer.Failed) Log.line("[foxy] price source failed: ${sources[i].first.substringBefore('?')} (${answer.error.javaClass.simpleName})")
        val price = text?.let {
            try {
                sources[i].second(Json.parse(it))
            } catch (e: Json.Malformed) {
                null
            }
        }
        if (price != null && price > 0 && price.isFinite()) {
            Log.line("[foxy] price $price from ${sources[i].first.substringBefore('?')}")
            if (!agree) return end(price, "")
            val other = answers.firstOrNull { Math.abs(it - price) / minOf(it, price) <= 0.02 }
            if (other != null) {
                Log.line("[foxy] price: two sources agree")
                return end((other + price) / 2, "")
            }
            answers.add(price)
        }
        fill()
    }

    private fun end(price: Double?, error: String) {
        if (over) return
        over = true
        if (price == null) Log.line("[foxy] price: none answered, $error")
        val others = running.values.toList()
        running.clear()
        others.forEach { it() }
        finish(price, error)
    }
}
