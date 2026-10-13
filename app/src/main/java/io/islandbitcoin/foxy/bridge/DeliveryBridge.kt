package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.network.Inbox
import io.islandbitcoin.foxy.network.InboxAnswer
import io.islandbitcoin.foxy.network.MintCircuit
import io.islandbitcoin.foxy.network.OnionInbox
import io.islandbitcoin.foxy.network.OnionPost
import java.text.BreakIterator
import java.util.Locale

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
) {
    companion object {
        /** No Tor: no address is made and no post leaves. What a host with nothing wired answers. */
        fun none(after: (Long, () -> Unit) -> Unit = { _, _ -> }): DeliveryEnvironment = DeliveryEnvironment(
            torRunning = { false },
            newInbox = { note, _ -> NoInbox(note) },
            post = { _, _, _, _, done -> done(OnionPost.Result.Failed(OnionPost.Failure.NoTor)) },
            after = after,
        )
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
 * onion address: a port of Foxy/Bridge/FoxyBridge+Delivery.swift's onion half
 * (ENG-672).
 *
 * Receiving: `inboxOpen` hands out an onion address for the request on screen
 * ([OnionInbox]); a payment posted there reaches the page as
 * `FoxyWallet._requestPaid(body, answerId)`, the page redeems it and says how
 * that went with `inboxAnswer`; `inboxClose` ends the address, after `hold`
 * seconds if a payment may be on its way. One spare address is kept warm and
 * handed to nobody until a request asks, so a payer finds it published.
 *
 * Paying: `onionPost` sends a payment to another phone's onion address
 * ([OnionPost]). Everything here is Tor only.
 *
 * Put away, every address of this phone's ends, the spare and the retiring
 * ones too ([closeForBackground]), before Tor comes off the network, and none
 * is made again until Foxy is back ([cameBack]). The page forgets its side when
 * it comes back (`inboxWake`) and opens a fresh one.
 *
 * Not yet: the Nostr half (`nostrSend`, the `nostr` field of `inboxOpen`'s
 * answer, Nostr's `_requestPaid` with an empty answer id), which is LATER
 * (ENG-672's follow-up). `inboxOpen` answers `{"onion"}` alone, which the page
 * takes as it takes an iPhone whose relays would not listen.
 *
 * Every handler runs on the main thread and answers through `resolve`.
 */
class DeliveryBridge(
    private val env: DeliveryEnvironment,
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
) {
    companion object {
        val ACTIONS = setOf("inboxOpen", "inboxClose", "inboxAnswer", "onionPost")

        const val NO_ADDRESS = "No address: Tor is not ready."
        const val NO_ONION = "No onion address: Tor is not ready."
        const val NO_PAYMENT_WAITING = "No payment is waiting on that answer."
        const val TOO_LARGE = "That payment is too large to send."

        /** How long a payment's answer key is kept: past the inbox's own 100 s, after which it answered 504. */
        const val ANSWER_KEPT_MS = 110_000L

        /** The longest hold `inboxClose` honours, in seconds. */
        const val MOST_HOLD_S = 180.0

        /** The longest text a payer is told. */
        const val MOST_TEXT = 300

        /**
         * A string as Apple's JSONSerialization writes it, so the payer reads
         * the bytes an iPhone receiver sends: `"`, `\` and `/` escaped (Apple
         * escapes the slash), \b \f \n \r \t by name, other controls as \u00xx,
         * everything else as it is.
         */
        fun appleJsonString(value: String): String {
            val out = StringBuilder(value.length + 2).append('"')
            for (c in value) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '/' -> out.append("\\/")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }

        /** What the payer hears for the page's word (FoxyBridge+Delivery.swift `handleInboxAnswer`). */
        fun answerBody(status: Int, text: String): String =
            if (status == 200) "{\"ok\":true}" else "{\"error\":" + appleJsonString(text) + "}"

        /** Only the statuses a payer is told: 200, 409, 422; anything else is 422. */
        fun answerStatus(asked: Any?): Int {
            val n = (asked as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt() ?: 422
            return if (n == 200 || n == 409 || n == 422) n else 422
        }

        /** The first `most` characters as a person sees them (Swift's `prefix` counts grapheme clusters). */
        fun prefixCharacters(text: String, most: Int): String {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var end = 0
            for (i in 0 until most) {
                val next = it.next()
                if (next == BreakIterator.DONE) return text
                end = next
            }
            return text.substring(0, end)
        }

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

    /** The payments waiting on the page's answer, by the key the page was handed. */
    private val answers = HashMap<String, InboxAnswer>()

    /** Put away: no address is made until Foxy is back. */
    private var away = false

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "inboxOpen" -> inboxOpen(id)
            "inboxClose" -> inboxClose(id, body)
            "inboxAnswer" -> inboxAnswer(id, body)
            "onionPost" -> onionPost(id, body)
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    /** Whether an answer key is waiting: for the tests. */
    internal fun waiting(key: String): Boolean = key in answers

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
        retiring.forEach { it.close() }
        retiring.clear()
        if (inbox == null) return
        inbox?.close()
        inbox = null
        // the onion's answers go with its address (no tap answer is kept on Android yet: ENG-670)
        answers.clear()
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
        closeForBackground()
        answers.clear()
    }

    // MARK: inboxOpen

    /** `{"onion": "..."}` (the Nostr address is LATER), or the error when there is none. */
    private fun answerInboxOpen(id: String, onion: String?) {
        if (onion.isNullOrEmpty()) return resolve(id, null, NO_ADDRESS)
        resolve(id, "{\"onion\":" + FoxyBridge.literal(onion) + "}", null)
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
        resolve(id, "ok", null)
        // the next receive should not wait for Tor to publish either
        warmSpare()
    }

    // MARK: inboxAnswer and _requestPaid

    /** The page's word on a payment: {answer, status, text}. Only 200, 409 and 422, and 300 characters. */
    private fun inboxAnswer(id: String, body: Map<String, Any?>) {
        val key = body["answer"] as? String
        val answer = key?.let { answers.remove(it) } ?: return resolve(id, null, NO_PAYMENT_WAITING)
        val status = answerStatus(body["status"])
        val text = prefixCharacters(body["text"] as? String ?: "", MOST_TEXT)
        answer(status, answerBody(status, text))
        resolve(id, "ok", null)
    }

    /** A payment reached an address: the page is handed it and a key to answer by. Main thread. */
    private fun paymentArrived(payment: String, answer: InboxAnswer) {
        val key = MintCircuit.label(null)
        answers[key] = answer
        // unanswered, the inbox answers 504 on its own; the key goes too
        env.after(ANSWER_KEPT_MS) { answers.remove(key) }
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
}
