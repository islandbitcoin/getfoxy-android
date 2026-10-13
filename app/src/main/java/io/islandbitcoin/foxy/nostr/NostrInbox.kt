package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.network.MintCircuit
import io.islandbitcoin.foxy.network.RelayListener
import io.islandbitcoin.foxy.network.RelaySocket
import io.islandbitcoin.foxy.network.RouteSocket

/** The Nostr key a payment request is also paid to: what the bridge holds (a [NostrInbox], or a test's stand-in). */
interface NostrAddress {
    /** What the request names: the key and the relays it is listened on; null once closed. */
    val nprofile: String?

    /** Listening on at least one relay. */
    val isOpen: Boolean

    /** Ask the relays; `done(true)` once at least one listens, `done(false)` when none will. Main thread. */
    fun open(done: (Boolean) -> Unit)

    /** End the key and the sockets. Main thread. */
    fun close()
}

/**
 * The second address a Cashu payment request is paid to: a Nostr key, for the
 * wallets that cannot reach the onion (Foxy/Nostr/NostrInbox.swift at 8df0bb1).
 *
 * A browser cannot resolve `.onion`, so a wallet like cashu.me cannot pay an
 * onion address, and was found swapping the ecash at the mint first and only
 * then finding it had nowhere to deliver it. So a request names a Nostr
 * transport as well, and this listens on it: NIP-17 over NIP-59, the payer
 * wraps the payment for this key and publishes it to the relays the request
 * names, over Tor here on a circuit of its own per relay.
 *
 * **The key is made for one request and thrown away with it.** It is never
 * derived from the twelve words: a key that outlived the screen would name the
 * person holding the phone, and every request they showed would be linkable.
 * What is left behind is the relays' copy of a ciphertext addressed to that key;
 * the onion leaves nothing, which is why the page prefers it.
 *
 * Made, opened, fed and closed on the main thread; the sockets' callbacks are
 * posted there through `main`.
 */
class NostrInbox(
    /** The payment JSON a payer sent, on the main thread, once per distinct wrap that opens and checks out. */
    private val onPayment: (body: String) -> Unit,
    private val connect: RelayConnect = RouteSocket::start,
    /** Posts to the main thread. */
    private val main: (Runnable) -> Unit,
    private val now: () -> Long = { NostrEvent.now() },
) : NostrAddress {
    companion object {
        /** The relays this inbox listens on, and its `nprofile` names: the ones NostrDelivery falls back to. */
        val RELAYS: List<String> get() = NostrDelivery.FALLBACK_RELAYS

        /** At most this many sockets for one request. */
        const val MOST_RELAYS = 4

        /** A relay's message larger than this is dropped unread: the onion inbox's cap. */
        const val LARGEST_PAYMENT = 256 * 1024

        /** How many opened wraps are remembered, to take each once. */
        private const val MOST_SEEN = 128
    }

    private val secret: ByteArray = NostrCrypto.newKey()

    /** Internal, so that a test can wrap a payment for this inbox. */
    internal val pubkey: ByteArray = NostrCrypto.publicKey(secret)

    /** A name for this subscription, unrelated to the key; internal, so a test can hand [took] a relay's line. */
    internal val subscription: String = NostrEvent.hex(NostrCrypto.newKey().copyOf(8))

    private val sockets = mutableListOf<RelaySocket>()

    /** The sockets still up: a relay that ended is no longer listening. */
    private var live = 0
    private val seen = HashSet<String>()
    private var closed = false

    override val nprofile: String?
        get() = if (closed) null else Bech32.nprofile(pubkey, RELAYS.take(MOST_RELAYS))

    override val isOpen: Boolean get() = !closed && live > 0

    /** The subscription: gift wraps addressed to this key, as far back as NIP-59's backdating reaches. */
    internal fun ask(): String {
        /* `since` has to cover the whole of NIP-59's backdating, not the moment
         * this inbox opened. A wrap's created_at is random up to two days in the
         * past on purpose (NostrEvent.backdated), so a relay asked for the last
         * minute quite correctly returns nothing: on iOS a real 50 sat payment
         * from cashu.me was never delivered that way. It stays bounded, because
         * this key is minutes old and published nowhere else: within the window
         * there is nothing to replay at us but the payment we are waiting for. */
        val backdating = NostrEvent.BACKDATING_S
        val since = now() - backdating - 3600
        val filter = "{\"kinds\":[1059],\"#p\":[\"${NostrEvent.hex(pubkey)}\"],\"since\":$since,\"limit\":64}"
        return "[\"REQ\",\"$subscription\",$filter]"
    }

    override fun open(done: (Boolean) -> Unit) {
        if (closed) return done(false)
        val urls = NostrDelivery.relays(RELAYS.take(MOST_RELAYS))
        if (urls.isEmpty()) return done(false)
        val ask = ask()
        var answered = false
        var left = urls.size
        fun report(ok: Boolean) {
            if (closed) return
            if (ok && !answered) {
                answered = true
                return done(true)
            }
            if (ok) return
            left--
            if (left == 0 && !answered) {
                answered = true
                done(false)
            }
        }
        for (relay in urls) {
            var up = false
            val listener = object : RelayListener {
                override fun opened() {
                    main(Runnable {
                        if (closed) return@Runnable
                        up = true
                        live++
                        report(true)
                    })
                }

                override fun text(message: String) {
                    main(Runnable { took(message) })
                }

                override fun ended(why: String) {
                    main(Runnable {
                        if (closed) return@Runnable
                        if (up) {
                            // a socket that had been listening is simply done
                            up = false
                            live--
                            Log.line("[foxy] nostr inbox: a relay stopped listening ($why)")
                        } else {
                            Log.line("[foxy] nostr inbox: a relay would not take the ask ($why)")
                            report(false)
                        }
                    })
                }
            }
            val socket = connect(relay, MintCircuit.label(null), listener)
            if (socket == null) {
                main(Runnable { report(false) })
                continue
            }
            sockets.add(socket)
            // queued until the socket is up, then sent first
            socket.send(ask)
        }
    }

    /**
     * One line from a relay. `["EVENT", <sub>, <event>]` is the only kind that
     * carries anything; EOSE, NOTICE, CLOSED and the rest are ignored. Main thread.
     */
    fun took(text: String) {
        if (closed || text.length > LARGEST_PAYMENT || text.toByteArray(Charsets.UTF_8).size > LARGEST_PAYMENT) return
        val parts = try {
            Json.parse(text) as? List<*>
        } catch (e: Json.Malformed) {
            null
        } ?: return
        if (parts.size < 3 || parts[0] != "EVENT" || parts[1] != subscription) return
        val wrap = NostrEvent.fromJson(parts[2]) ?: return
        /* Every relay sends the same wrap, and the payer sent it to them all: one
         * that has opened is not opened again. An id is remembered only once its
         * wrap has opened and checked out, so a relay sending a wrap that only
         * claims the id (not checked until unwrap) cannot make the genuine one,
         * from every other relay, drop as a copy. */
        if (wrap.id.isEmpty() || wrap.id in seen) return
        val rumor = NostrEvent.unwrap(wrap, secret) ?: run {
            Log.line("[foxy] nostr inbox: a wrap did not open, or did not check out")
            return
        }
        if (seen.size >= MOST_SEEN) seen.clear()
        seen.add(wrap.id)
        Log.line("[foxy] nostr inbox: a payment arrived")
        onPayment(rumor.content)
    }

    /**
     * End the key and the sockets. The relays keep whatever they were given;
     * nothing here can take that back, which is the part of this transport the
     * onion does not have.
     */
    override fun close() {
        if (closed) return
        closed = true
        sockets.forEach { it.close() }
        sockets.clear()
        live = 0
        seen.clear()
        secret.fill(0)
        Log.line("[foxy] nostr inbox closed")
    }
}
