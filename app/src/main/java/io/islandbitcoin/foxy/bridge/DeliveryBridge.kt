package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.network.Inbox
import io.islandbitcoin.foxy.network.InboxAnswer
import io.islandbitcoin.foxy.network.MintCircuit
import io.islandbitcoin.foxy.network.OnionInbox
import io.islandbitcoin.foxy.network.OnionPost
import io.islandbitcoin.foxy.nostr.NostrAddress
import io.islandbitcoin.foxy.nostr.NostrCrypto
import io.islandbitcoin.foxy.nostr.NostrDelivery

/**
 * What the onion actions need from the rest of the app, passed in so the JVM
 * tests run them with stand-in inboxes and a stand-in post.
 */
class DeliveryEnvironment(
    /** Tor carries requests now (an onion address can be made). */
    val torRunning: () -> Boolean,
    /** A new, unopened inbox: an [OnionInbox] on the phone. */
    val newInbox: (note: String, onPayment: (body: String, answer: InboxAnswer) -> Unit) -> Inbox,
    /** One [OnionPost.send]; `done` from any thread. */
    val post: (url: String, body: ByteArray, circuit: String, timeoutMs: Long, done: (OnionPost.Result) -> Unit) -> Unit,
    /** A run on the main thread after `ms`. */
    val after: (Long, () -> Unit) -> Unit,
    /**
     * Which Tor daemon is running ([io.islandbitcoin.foxy.tor.TorMachine.daemonGeneration]):
     * an address made under an older one died with that daemon, though its
     * listener is still up and the inbox still says it is open.
     */
    val daemonGeneration: () -> Int = { 0 },
    /**
     * A new, unopened Nostr key for the request on screen ([io.islandbitcoin.foxy.nostr.NostrInbox]
     * on the phone), or null when none can be made. Unset, there is none, and
     * `inboxOpen` answers the onion alone, as iOS does when no relay would listen.
     */
    val newNostrInbox: (onPayment: (body: String) -> Unit) -> NostrAddress? = { null },
    /**
     * One [NostrDelivery.send]: `done` on the main thread. Unset, no relay is
     * reached ([NostrDelivery] with no way out), as on a phone without Tor.
     */
    val nostrSend: (payload: String, target: String, done: (NostrDelivery.Result) -> Unit) -> Unit = NO_RELAY,
) {
    companion object {
        /** No Tor: no address is made and no post leaves. What a host with nothing wired answers. */
        fun none(after: (Long, () -> Unit) -> Unit = { _, _ -> }): DeliveryEnvironment = DeliveryEnvironment(
            torRunning = { false },
            newInbox = { note, _ -> NoInbox(note) },
            post = { _, _, _, _, done -> done(OnionPost.Result.Failed(OnionPost.Failure.NoTor)) },
            after = after,
        )

        /** A delivery that reaches no relay: every socket refused, as Route refuses one without Tor. */
        private val NO_RELAY: (String, String, (NostrDelivery.Result) -> Unit) -> Unit = { payload, target, done ->
            NostrDelivery(connect = { _, _, _ -> null }, main = { it.run() }, after = { _, _ -> }).send(payload, target, done)
        }
    }

    /** An inbox that never opens. */
    private class NoInbox(override var note: String) : Inbox {
        override val url: String? = null
        override val isOpen = false
        override fun open(done: (String?) -> Unit) = done(null)
        override fun close() {}
    }
}

/**
 * Cashu payment requests (NUT-18) paid straight to whoever asked, over an
 * onion address or a Nostr key: a port of Foxy/Bridge/FoxyBridge+Delivery.swift
 * (the onion in ENG-672, Nostr in ENG-677).
 *
 * Receiving: `inboxOpen` hands out an onion address for the request on screen
 * ([OnionInbox]); a payment posted there reaches the page as
 * `FoxyWallet._requestPaid(body, answerId)`, the page redeems it and says how
 * that went with `inboxAnswer` (answered from the one registry tap payments
 * wait in too, [InboxAnswers]); `inboxClose` ends the address, after `hold`
 * seconds if a payment may be on its way. One spare address is kept warm and
 * handed to nobody until a request asks, so a payer finds it published.
 *
 * The same request is also paid to a Nostr key made for it alone
 * ([io.islandbitcoin.foxy.nostr.NostrInbox], ENG-677): `inboxOpen` answers
 * `{"onion", "nostr"}`, the `nostr` an nprofile naming the key and its relays,
 * or the onion alone when no relay would listen. A payment that arrives over
 * Nostr reaches the page as `_requestPaid(body, "")`: nobody waits on an
 * answer (the payer published and walked away), so no key is put in
 * [InboxAnswers] and the page's `inboxAnswer("")` is told nothing is waiting.
 * The key ends with the request (`inboxClose`) and when Foxy is put away.
 *
 * Paying: `onionPost` sends a payment to another phone's onion address
 * ([OnionPost]), `nostrSend` to the relays a request's nprofile names
 * ([NostrDelivery]). Everything here is Tor only.
 *
 * Put away, every address of this phone's ends, the spare, the retiring ones
 * and the Nostr key too ([closeForBackground]), before Tor comes off the
 * network, and none is made again until Foxy is back ([cameBack]). The page
 * forgets its side when it comes back (`inboxWake`) and opens a fresh one.
 *
 * Every handler runs on the main thread and answers through `resolve`.
 */
class DeliveryBridge(
    private val env: DeliveryEnvironment,
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
    /** The answers the page owes, the onion's and the tap's in one table (FoxyBridge.swift `inboxAnswers`). */
    private val answers: InboxAnswers,
) {
    companion object {
        /** And `inboxAnswer`, which [InboxAnswers] answers for this bridge and [TapBridge] alike. */
        val ACTIONS = setOf("inboxOpen", "inboxClose", InboxAnswers.ACTION, "onionPost", "nostrSend")

        const val NO_ADDRESS = "No address: Tor is not ready."
        const val NO_ONION = "No onion address: Tor is not ready."
        const val TOO_LARGE = "That payment is too large to send."
        const val NO_NOSTR_ADDRESS = "That request names no Nostr address."
        const val TOO_LARGE_FOR_NOSTR = "That payment is too large to send over Nostr."
        const val NOSTR_FAILED = "The payment could not be sent over Nostr."
        const val NO_RELAY_TOOK_IT = "No relay took the payment: "

        /** How long a payment's answer key is kept: past the inbox's own 100 s, after which it answered 504. */
        const val ANSWER_KEPT_MS = 110_000L

        /** The longest hold `inboxClose` honours, in seconds. */
        const val MOST_HOLD_S = 180.0

        /** `hold`, in seconds, within 0..180 (FoxyBridge+Delivery.swift `handleInboxClose`). */
        fun holdSeconds(hold: Any?): Double {
            val h = (hold as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
            return h.coerceIn(0.0, MOST_HOLD_S)
        }

        /** The attempt's ceiling from the page's `timeout` (ms): 3..120 s, or 120 s when none is given. */
        fun postCeilingMs(timeout: Any?): Long {
            val ms = (timeout as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
            val seconds = if (ms > 0) (ms / 1000).coerceIn(3.0, 120.0) else 120.0
            return (seconds * 1000).toLong()
        }
    }

    /** The address the request on screen is paid to. */
    private var inbox: Inbox? = null

    /** An address opened ahead of being wanted, and handed to nobody. */
    private var spare: Inbox? = null

    /** The Tor daemon [inbox] and [spare] were made under: an address outlives no daemon. */
    private var inboxGeneration = 0
    private var spareGeneration = 0

    /** Made under the daemon running now: under an older one the address is gone with it. */
    private fun current(generation: Int): Boolean = generation == env.daemonGeneration()

    /** Addresses the screen finished with that still answer, because a payment may be on its way. */
    private val retiring = mutableListOf<Inbox>()

    /** Put away: no address is made until Foxy is back. */
    private var away = false

    /** The Nostr key the request on screen is also paid to; made and thrown away with it. */
    private var nostr: NostrAddress? = null

    internal val nostrInbox: NostrAddress? get() = nostr

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "inboxOpen" -> inboxOpen(id)
            "inboxClose" -> inboxClose(id, body)
            InboxAnswers.ACTION -> answers.handle(id, body)
            "onionPost" -> onionPost(id, body)
            "nostrSend" -> nostrSend(id, body)
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    /** Whether an answer key is waiting: for the tests. */
    internal fun waiting(key: String): Boolean = answers.waiting(key)

    internal val spareInbox: Inbox? get() = spare
    internal val openInbox: Inbox? get() = inbox
    internal val retiringInboxes: List<Inbox> get() = retiring.toList()

    // MARK: The warm spare

    /**
     * An address opened ahead of being wanted (FoxyBridge+Delivery.swift
     * `warmSpare`). Tor takes about half a minute to publish a new address, so
     * one is kept published and handed over when the page asks, and another
     * started at once. It costs no privacy: every address is a fresh key Tor
     * never gives Foxy, each payer still gets one of their own, and the
     * spare's has been given to nobody, so nobody can probe whether it is up.
     */
    fun warmSpare() {
        if (away) return
        spare?.let { old ->
            if (current(spareGeneration)) return
            // its address died with the Tor daemon it was made under (a relaunch): never handed out
            Log.line("[foxy] onion inbox: the spare's Tor daemon ended; making another")
            spare = null
            old.close()
        }
        if (!env.torRunning()) return
        val made = env.newInbox("the spare", ::paymentArrived)
        val generation = env.daemonGeneration()
        spare = made
        spareGeneration = generation
        made.open { url ->
            if (url == null || spare !== made || !current(generation)) {
                if (spare === made) spare = null
                made.close()
            }
        }
    }

    /** Tor or the network changed: an address is started if Tor is up and there is none. Main thread. */
    fun torChanged() = warmSpare()

    // MARK: Put away and back

    /**
     * Foxy is being put away: every address of this phone's ends now, the
     * spare, the retiring ones and the one on screen, with the answers waiting
     * on them (FoxyBridge+Delivery.swift `closeInboxForBackground`), before
     * Tor comes off the network, and none is made until [cameBack]. Main thread.
     */
    fun closeForBackground() {
        away = true
        spare?.close()
        spare = null
        nostr?.close()
        nostr = null
        retiring.forEach { it.close() }
        retiring.clear()
        if (inbox == null) return
        inbox?.close()
        inbox = null
        /* The onion's answers go with its address. A tap's does not
         * (FoxyBridge+Delivery.swift `closeInboxForBackground`, `tapAnswerKeys`):
         * the payer deletes its only copy of a tap payment on this answer, and
         * the link is still up, so the page that takes the money must still be
         * able to say so. */
        answers.dropOnion()
        Log.line("[foxy] onion inbox closing: Foxy is leaving the foreground")
    }

    /** Foxy is back: an address may be made again, and the spare starts warming. Main thread. */
    fun cameBack() {
        away = false
        warmSpare()
    }

    /**
     * The page is gone for good (its WebView destroyed): nothing of it is left
     * published. On iOS the bridge lives as long as the app; on Android it
     * lives as long as the activity's page, so its addresses go with it.
     */
    fun pageGone() {
        // the answers owed, the tap's too, go in FoxyBridge.pageGone
        closeForBackground()
    }

    // MARK: inboxOpen

    /**
     * Both addresses the request on screen is paid to, `{"onion": "...",
     * "nostr": "nprofile1..."}` (FoxyBridge+Delivery.swift `answerInboxOpen`):
     * either may be missing, the page leaves out what it did not get.
     */
    private fun answerInboxOpen(id: String, onion: String?) {
        openNostrInbox { nprofile ->
            val parts = mutableListOf<String>()
            if (!onion.isNullOrEmpty()) parts.add("\"onion\":" + FoxyBridge.literal(onion))
            if (nprofile != null) parts.add("\"nostr\":" + FoxyBridge.literal(nprofile))
            if (parts.isEmpty()) return@openNostrInbox resolve(id, null, NO_ADDRESS)
            resolve(id, "{" + parts.joinToString(",") + "}", null)
        }
    }

    /**
     * The Nostr key this request is also paid to, or null when no relay would
     * listen: the open one, or a fresh key (FoxyBridge+Delivery.swift
     * `openNostrInbox`). Never while Foxy is put away.
     */
    private fun openNostrInbox(done: (String?) -> Unit) {
        nostr?.let { open ->
            val nprofile = open.nprofile
            if (open.isOpen && nprofile != null) return done(nprofile)
        }
        nostr?.close()
        nostr = null
        if (away) return done(null)
        val made = try {
            env.newNostrInbox(::nostrPaymentArrived)
        } catch (e: NostrCrypto.Failure) {
            null
        } ?: return done(null)
        nostr = made
        made.open { ok ->
            if (nostr !== made || away) {
                made.close()
                return@open done(null)
            }
            val nprofile = made.nprofile
            if (!ok || nprofile == null) {
                nostr = null
                made.close()
                Log.line("[foxy] nostr inbox: no relay would listen; the request goes without it")
                return@open done(null)
            }
            Log.line("[foxy] nostr inbox open, a key made for this request only")
            done(nprofile)
        }
    }

    /**
     * A payment that came over Nostr. Nobody to answer: the payer published and
     * walked away, so the answer key is empty and the page's `inboxAnswer` is
     * told nothing waits on it ([InboxAnswers]). The claim watch is what tells the
     * payer, as for a token shown rather than delivered. Main thread.
     */
    private fun nostrPaymentArrived(payment: String) {
        Log.line("[foxy] nostr inbox: a payment arrived (${payment.toByteArray(Charsets.UTF_8).size} bytes)")
        val js = "window.FoxyWallet && window.FoxyWallet._requestPaid && window.FoxyWallet._requestPaid(" +
            FoxyBridge.literal(payment) + ", \"\")"
        host.post { host.evaluate(js) }
    }

    /** The address already open, the warm spare, or a new one (FoxyBridge+Delivery.swift `handleInboxOpen`). */
    private fun inboxOpen(id: String) {
        // put away: nothing of this phone's is published until it is back
        if (away) return resolve(id, null, NO_ONION)
        inbox?.let { open ->
            val url = open.url
            if (open.isOpen && url != null && current(inboxGeneration)) return answerInboxOpen(id, url)
        }
        // a spare made under a Tor daemon that has since ended is no address: replaced, below or by warmSpare
        spare?.let { old ->
            if (!current(spareGeneration)) {
                spare = null
                old.close()
            }
        }
        // the warm one, if it got as far as an address: handed over and replaced in the same breath
        spare?.let { ready ->
            val url = ready.url
            if (ready.isOpen && url != null) {
                inbox?.close()
                ready.note = "for the request on screen"
                inbox = ready
                inboxGeneration = spareGeneration
                spare = null
                Log.line("[foxy] onion inbox handed the spare, already published")
                answerInboxOpen(id, url)
                warmSpare()
                return
            }
        }
        inbox?.close()
        val made = env.newInbox("for the request on screen", ::paymentArrived)
        val generation = env.daemonGeneration()
        inbox = made
        inboxGeneration = generation
        made.open { url ->
            if (url == null || inbox !== made || !current(generation)) {
                if (inbox === made) inbox = null
                made.close()
                return@open resolve(id, null, NO_ONION)
            }
            answerInboxOpen(id, url)
        }
    }

    // MARK: inboxClose

    /**
     * The screen is done with the address; `hold` seconds keep it answering,
     * because a payment can be on the wire towards it and a DEL_ONION at once
     * leaves the payer's Tor retrying a cached descriptor for two minutes. The
     * screen gets a fresh address from the spare immediately.
     */
    private fun inboxClose(id: String, body: Map<String, Any?>) {
        val hold = holdSeconds(body["hold"])
        val going = inbox
        if (going != null && hold > 0) {
            retiring.add(going)
            Log.line("[foxy] onion inbox kept answering for ${hold.toInt()}s: a payment may be on its way")
            env.after((hold * 1000).toLong()) {
                retiring.remove(going)
                going.close()
            }
        } else {
            inbox?.close()
        }
        inbox = null
        // the key dies with the request it was made for (NostrInbox)
        nostr?.close()
        nostr = null
        resolve(id, "ok", null)
        // the next receive should not wait for Tor to publish either
        warmSpare()
    }

    // MARK: _requestPaid

    /** A payment reached an address: the page is handed it and a key to answer by. Main thread. */
    private fun paymentArrived(payment: String, answer: InboxAnswer) {
        val key = MintCircuit.label(null)
        answers.onion(key, answer)
        // unanswered, the inbox answers 504 on its own; the key goes too
        env.after(ANSWER_KEPT_MS) { answers.drop(key) }
        Log.line("[foxy] onion inbox: a payment arrived (${payment.toByteArray(Charsets.UTF_8).size} bytes)")
        val js = "window.FoxyWallet && window.FoxyWallet._requestPaid && window.FoxyWallet._requestPaid(" +
            FoxyBridge.literal(payment) + ", " + FoxyBridge.literal(key) + ")"
        host.post { host.evaluate(js) }
    }

    // MARK: onionPost

    /** {url, body, timeout, circuit}: "status\nbody" from the other phone, or an error the page shows as it is. */
    private fun onionPost(id: String, body: Map<String, Any?>) {
        val url = body["url"] as? String ?: return resolve(id, null, OnionPost.Failure.NotOnion.text)
        val payment = (body["body"] as? String ?: "").toByteArray(Charsets.UTF_8)
        if (payment.isEmpty() || payment.size > OnionInbox.LARGEST_PAYMENT) return resolve(id, null, TOO_LARGE)
        env.post(url, payment, MintCircuit.label(body["circuit"]), postCeilingMs(body["timeout"])) { result ->
            when (result) {
                is OnionPost.Result.Done -> resolve(id, "${result.status}\n" + result.body, null)
                is OnionPost.Result.Failed -> {
                    Log.line("[foxy] onion post failed: ${result.failure}")
                    resolve(id, null, result.failure.text)
                }
            }
        }
    }

    // MARK: nostrSend

    /**
     * {target, body}: a payment handed to the relays a request's nprofile names,
     * over Tor ([NostrDelivery]); answers how many took it
     * (FoxyBridge+Delivery.swift `handleNostrSend`).
     */
    private fun nostrSend(id: String, body: Map<String, Any?>) {
        val target = (body["target"] as? String)?.takeIf { it.isNotEmpty() } ?: return resolve(id, null, NO_NOSTR_ADDRESS)
        val payload = body["body"] as? String ?: ""
        if (payload.isEmpty() || payload.toByteArray(Charsets.UTF_8).size > NostrCrypto.LONGEST_MESSAGE / 2) {
            return resolve(id, null, TOO_LARGE_FOR_NOSTR)
        }
        env.nostrSend(payload, target) { result ->
            when (result) {
                is NostrDelivery.Result.Sent -> {
                    val o = result.outcome
                    Log.line("[foxy] nostr: ${o.accepted.size} relay(s) took the payment, ${o.refused.size} did not")
                    if (o.accepted.isEmpty()) {
                        resolve(id, null, NO_RELAY_TOOK_IT + (o.refused.values.firstOrNull() ?: "no relay took it"))
                    } else {
                        resolve(id, o.accepted.size.toString(), null)
                    }
                }
                is NostrDelivery.Result.Failed -> {
                    Log.line("[foxy] nostr: the payment could not be wrapped: ${result.failure.kind}")
                    resolve(id, null, NOSTR_FAILED)
                }
            }
        }
    }
}
