package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.network.MintCircuit
import io.islandbitcoin.foxy.network.RelayListener
import io.islandbitcoin.foxy.network.RelaySocket
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.network.RouteSocket

/** How a relay socket is opened: [RouteSocket.start] on the phone, a stand-in relay in the tests. */
typealias RelayConnect = (url: String, circuit: String, listener: RelayListener) -> RelaySocket?

/**
 * Handing a Cashu payment to a wallet that asked for it over Nostr (NUT-18;
 * Foxy/Nostr/NostrDelivery.swift at 8df0bb1).
 *
 * The payment is wrapped for the receiver ([NostrEvent.giftWrapped]) and
 * published to the relays their request names, each over Tor on a circuit of
 * its own (a fresh [MintCircuit] label per relay). What a relay sees is an
 * encrypted message addressed to the receiver's key, signed by a key made for
 * this payment and thrown away after. It never learns who sent it, what it
 * holds, or where this phone is.
 *
 * `done` once, on the main thread (through `main`), when every relay has
 * answered or timed out.
 */
class NostrDelivery(
    private val connect: RelayConnect = RouteSocket::start,
    /** Posts to the main thread. */
    private val main: (Runnable) -> Unit,
    /** Runs on the main thread after `ms`. */
    private val after: (Long, () -> Unit) -> Unit,
    private val patienceMs: Long = PATIENCE_MS,
) {
    companion object {
        /**
         * Relays Foxy falls back to when a request names none: the ones cashu.me
         * and Minibits publish to and listen on by default, so a payment still
         * reaches them.
         */
        val FALLBACK_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.minibits.cash")

        /** At most this many relays for one payment, and this long on each. */
        const val MOST_RELAYS = 5
        const val PATIENCE_MS = 25_000L

        /**
         * The relays to try: the request's own, then Foxy's fallbacks when it
         * named none. Only wss with a host, each once, at most [MOST_RELAYS]
         * (NostrDelivery.swift `relays`).
         */
        fun relays(listed: List<String>): List<String> {
            val names = listed.ifEmpty { FALLBACK_RELAYS }
            val out = mutableListOf<String>()
            for (name in names) {
                if (out.size >= MOST_RELAYS) break
                val url = name.trim(' ', '\t')
                // a URL as Foundation reads one: no space or control character, nothing outside ASCII
                if (!url.startsWith("wss://") || !url.all { it.code in 0x21..0x7e }) continue
                val host = url.removePrefix("wss://").substringBefore('/').substringBefore('?').substringBefore('#')
                if (host.isEmpty() || host.substringBefore(':').isEmpty()) continue
                if (url in out) continue
                out.add(url)
            }
            return out
        }

        /**
         * A relay's answer about this event: null when it is about something else,
         * "" when the event was taken, and the reason when it was not. NIP-01:
         * `["OK", <id>, <true|false>, <message>]`; a "duplicate:" refusal means the
         * relay already has it, which is as good as taking it.
         */
        fun reading(text: String, id: String): String? {
            val parts = try {
                Json.parse(text) as? List<*>
            } catch (e: Json.Malformed) {
                null
            } ?: return null
            if (parts.size < 3 || parts[0] != "OK" || parts[1] != id) return null
            val took = when (val t = parts[2]) {
                is Boolean -> t
                is Double -> t != 0.0
                else -> false
            }
            val said = if (parts.size > 3) parts[3] as? String ?: "" else ""
            if (took || said.startsWith("duplicate:")) return ""
            return if (said.isEmpty()) "the relay refused it" else said.take(120)
        }
    }

    /** How a payment went: which relays took it, and why the others did not. */
    data class Outcome(val accepted: List<String>, val refused: Map<String, String>)

    sealed class Result {
        data class Sent(val outcome: Outcome) : Result()

        /** Nothing was published: the request's address did not read, or the payment would not wrap. */
        data class Failed(val failure: NostrCrypto.Failure) : Result()
    }

    /** Wrap `payload` for the `nprofile` a payment request names and publish it. Main thread. */
    fun send(payload: String, nprofile: String, done: (Result) -> Unit) {
        val profile = Bech32.nprofile(nprofile) ?: return done(Result.Failed(NostrCrypto.Failure(NostrCrypto.Failure.Kind.BAD_PEER)))
        val urls = relays(profile.relays)
        if (urls.isEmpty()) return done(Result.Failed(NostrCrypto.Failure(NostrCrypto.Failure.Kind.BAD_PEER)))
        val wrap = try {
            NostrEvent.giftWrapped(payload, profile.pubkey)
        } catch (e: NostrCrypto.Failure) {
            return done(Result.Failed(e))
        }
        val message = "[\"EVENT\"," + NostrEvent.json(wrap) + "]"
        val accepted = mutableListOf<String>()
        val refused = linkedMapOf<String, String>()
        var left = urls.size
        Log.line("[foxy] nostr: a wrap to ${urls.size} relay(s)")
        for (relay in urls) {
            publish(message, wrap.id, relay) { refusal ->
                // main thread
                if (refusal == null) accepted.add(relay) else refused[relay] = refusal
                left--
                if (left == 0) done(Result.Sent(Outcome(accepted.toList(), refused.toMap())))
            }
        }
    }

    /**
     * One relay: send the event, wait for its OK. `done` once, on the main thread,
     * with null when the relay took it or why it did not. One step, never two:
     * the timeout and the socket's answer race, and a relay finished twice would
     * skip `left` past 0 (NostrDelivery.swift `publish`).
     */
    private fun publish(message: String, id: String, relay: String, done: (String?) -> Unit) {
        var finished = false
        var socket: RelaySocket? = null
        fun finish(refusal: String?) {
            // main thread only
            if (finished) return
            finished = true
            socket?.close()
            done(refusal)
        }
        val listener = object : RelayListener {
            override fun opened() {}

            override fun text(message: String) {
                val answer = reading(message, id) ?: return // something else the relay said
                main(Runnable { finish(answer.ifEmpty { null }) })
            }

            override fun ended(why: String) {
                main(Runnable { finish(why) })
            }
        }
        val s = connect(relay, MintCircuit.label(null), listener)
        if (s == null) {
            main(Runnable { finish(Route.REFUSAL) })
            return
        }
        socket = s
        if (!s.send(message)) {
            main(Runnable { finish("the relay connection closed") })
            return
        }
        after(patienceMs) { finish("no answer in time") }
    }
}
