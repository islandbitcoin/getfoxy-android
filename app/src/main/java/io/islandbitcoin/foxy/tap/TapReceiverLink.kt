package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.debug.Log
import java.text.BreakIterator
import java.util.UUID

/**
 * The receiving phone's side of a tap, with the radio left out
 * (Foxy/Bluetooth/TapLink.swift `TapReceiver`): who holds the one place, the
 * handshake's clock, the offer when the page has one, the payment handed to the
 * page and M6 sealed from its answer, M9, M11, the change leg, M12, and a stop
 * that waits for a payment in flight.
 *
 * The GATT server (ENG-670) plugs in underneath as a [Radio]: it puts the
 * service up and on the air, says who subscribed to and wrote on which door,
 * and carries notifications. The near doors (CONNECT TO PAY, the turned and
 * early byte) are the radio's and are not in here.
 *
 * One payment's worth of radio: the service UUID is made once, and a receiver
 * that has handed an offer to somebody ([takenOne]) is never armed again.
 * Everything runs on the [clock]'s thread.
 */
class TapReceiverLink(
    private var payload: String,
    private val radio: Radio,
    private val clock: TapClock,
    private val onEvent: (TapStage, String?) -> Unit,
    /** The payment, and how the page answers it: a status (200, 409, 422, 504) and a reason. */
    private val onPaid: (String, (Int, String) -> Unit) -> Unit = { _, _ -> },
    /** M10: the payer's price or word, for the page to decide on. */
    private val onQuote: (String) -> Unit = {},
    /** New every time. A scanner that saw the last one learns nothing here. */
    val service: UUID = TapCrypto.newService(),
    private val newSession: (UUID) -> TapSession = { TapSession(TapSession.Role.RECEIVER, it) },
    private val diary: (String) -> Unit = Log::line,
) {
    /**
     * What the GATT server does for this link. Every call is made on the link's thread.
     *
     * After [close] the radio calls nothing more on the link: no advertising,
     * radio state, subscribe, unsubscribe, write or ready-to-update. iOS gets
     * that from `m.delegate = nil` in `reallyStop` (TapLink.swift:465 at
     * 8df0bb1); on Android a callback already posted to the link's thread can
     * still arrive, so the link also ignores every one once it has stopped.
     */
    interface Radio {
        /**
         * Put the service up (once) and on the air: the service UUID and nothing
         * else, connectable, no name, no power level, no manufacturer data.
         */
        fun advertise()

        fun stopAdvertising()

        /** One notification on the out door to one central; false when it cannot take it now ([readyToUpdate] follows). */
        fun notify(central: String, piece: ByteArray): Boolean

        /** The most one notification to [central] may carry: the negotiated MTU less three. */
        fun notifyLength(central: String): Int

        /** Off the air, the service taken down, every connection let go. */
        fun close()
    }

    companion object {
        /** A phone that has said its first word has this long to finish the handshake. */
        const val HANDSHAKE_WAIT = 3.0

        /** How long the page has to answer a payment before M6 says so (shorter than the payer's own wait). */
        const val RESULT_WAIT = 25.0

        /** And once a person is deciding (M9). */
        const val ASKING_WAIT = 90.0

        /** How long the page waits for the payer to say it kept change (FoxyBridge+Tap.swift `handleTapChange`). */
        const val CHANGE_ANSWER_WAIT = 7.0

        /** The beat between M6 and a teardown that was waiting on it. */
        const val AFTER_RESULT = 2.0

        /** A reason in M6 is cut to this many characters, so it always fits its one size. */
        const val MOST_WHY = 160
    }

    var early = false
    private var session: TapSession? = null
    private var talkingTo: String? = null
    private var arriving = TapProtocol.Bytes()
    private val queued = ArrayDeque<Pair<String, ByteArray>>()
    private var armed = false
    private var radioOn = false
    private var linked = false
    private var finished = false
    private var handshakeTimer: TapClock.Timer? = null
    private var offerSent = false
    private var linkedCentral: String? = null
    private var changeOwed = false
    private var changeTaken = false
    private val subscribed = ArrayList<String>()
    private var resultTimer: TapClock.Timer? = null
    private var resultSent = false
    private var paymentHeard = false
    private var expecting = 0L
    private var arrivedBefore = 0L
    private var arrivedSaid = TapProgress()
    private var stopping = false

    /**
     * What became of change that was sent: true when the payer says it kept it
     * (M8), false when the payer went, or this receiver stopped, first. Called once.
     */
    var onChangeAnswered: ((Boolean) -> Unit)? = null

    private fun changeAnswered(kept: Boolean) {
        val tell = onChangeAnswered
        onChangeAnswered = null
        tell?.invoke(kept)
    }

    private fun say(what: String) = diary("[tap] receive: $what")

    /** What this screen is offering. It may arrive after the payer does, and goes out as M4 the moment it is real. */
    fun update(payload: String) {
        this.payload = payload
        sendOfferIfReady()
    }

    private fun sendOfferIfReady() {
        val made = session ?: return
        val c = linkedCentral ?: return
        if (!linked || offerSent || finished || !TapProtocol.isPayload(payload)) return
        val offer = made.sealOffer(payload) ?: run {
            say("the offer would not seal")
            return
        }
        offerSent = true
        push(offer, c)
    }

    /** On the air. Nothing is advertised before this. */
    fun arm() {
        if (armed) return
        armed = true
        advertise()
    }

    /** The radio's state: null when it is on, or what to tell the page (off, denied, unsupported). */
    fun radioState(bad: TapStage?) {
        if (finished) return
        if (bad != null) {
            radioOn = false
            onEvent(bad, null)
            return
        }
        radioOn = true
        advertise()
    }

    /** The radio says the advertisement went up. */
    fun advertising() {
        if (finished) return
        onEvent(TapStage.SEARCHING, null)
        say("on the air as ${service.toString().uppercase()}")
    }

    private fun advertise() {
        if (!armed || finished || !radioOn) return
        radio.advertise()
    }

    /** Whether this receiver is in the middle of something a teardown would break: a payment, or change owed. */
    val busy: Boolean
        get() {
            if (changeOwed && !changeTaken) return true
            return !resultSent && (resultTimer != null || arriving.buffer.isNotEmpty())
        }

    /** Whether this one has handed an offer to somebody. It cannot be armed again. */
    val takenOne: Boolean get() = offerSent

    /** A payer has the offer and has not paid yet. */
    val withPayer: Boolean get() = linked && offerSent && !resultSent

    /** Change is owed, and the link must be kept for it: told when the page reads the amount. */
    fun expectChange() {
        if (!linked || finished) return
        changeOwed = true
        changeTaken = false
        say("change is owed, so this link stays up for it")
    }

    private fun payerCentral(): String? = subscribed.firstOrNull { it == talkingTo }

    /** M11: the price was agreed; here is the request, now in sats. */
    fun sendTerms(text: String): Boolean {
        if (!linked || finished) return false
        val made = session ?: return false
        val central = payerCentral() ?: return false
        val sealed = made.sealTerms(text) ?: return false
        push(sealed, central)
        say("the price was agreed; the request is on its way")
        return true
    }

    /** M9: a person here is being asked about the payment. The page's clock goes to ninety seconds. */
    fun sendAsking(): Boolean {
        if (!linked || finished || resultSent) return false
        val made = session ?: return false
        val central = payerCentral() ?: return false
        val sealed = made.sealAsking() ?: return false
        resultTimer?.cancel()
        resultTimer = clock.after(ASKING_WAIT) { sealResult(504, "They did not answer in time.") }
        push(sealed, central)
        say("telling the payer a person is deciding")
        return true
    }

    /** M7: change back to the payer, over the link the payment came in on. */
    fun sendChange(text: String): Boolean {
        val made = session
        val central = payerCentral()
        if (!linked || finished || made == null || central == null) {
            say("no link to hand the change over: linked $linked, finished $finished, a payer subscribed ${central != null}")
            return false
        }
        val sealed = made.sealChange(text) ?: run {
            say("the change would not seal (${text.toByteArray(Charsets.UTF_8).size} bytes)")
            return false
        }
        changeOwed = true
        changeTaken = false
        push(sealed, central)
        say("change sent, waiting for the payer to keep it")
        return true
    }

    /**
     * The change leg as the bridge runs it (FoxyBridge+Tap.swift
     * `handleTapChange`): sent, and answered once, KEPT when the payer says so
     * or NOT_HANDED after [CHANGE_ANSWER_WAIT] seconds; a "kept" after that is
     * LATE. False, with nothing told, when there is no link to send it on.
     */
    fun handOverChange(token: String, tell: (ChangeHandover.Word) -> Unit): Boolean {
        if (token.isEmpty()) return false
        val wait = ChangeHandover()
        val answer: (Boolean) -> Unit = { kept -> wait.heard(kept)?.let(tell) }
        onChangeAnswered = answer
        if (!sendChange(token)) {
            onChangeAnswered = null
            wait.heard(false)
            return false
        }
        clock.after(CHANGE_ANSWER_WAIT) {
            if (wait.answered) return@after
            answer(false)
            // still listening: a late "kept" comes through the same door
            onChangeAnswered = answer
        }
        return true
    }

    /**
     * Ends the listen. False when it did not, because a payment is mid-flight:
     * the link outlives the screen by as long as the answer may take, then
     * [whenIdle] runs.
     */
    fun stop(whenIdle: (() -> Unit)? = null): Boolean {
        if (busy && !stopping) {
            stopping = true
            say("a payment is crossing; the link stays until it is answered")
            clock.after(RESULT_WAIT + 2) {
                reallyStop()
                whenIdle?.invoke()
            }
            return false
        }
        reallyStop()
        return true
    }

    private fun reallyStop() {
        changeAnswered(false)
        val wasUp = !finished
        finished = true
        stopping = false
        armed = false
        resultTimer?.cancel()
        resultTimer = null
        handshakeTimer?.cancel()
        handshakeTimer = null
        if (wasUp) radio.close()
        session = null
        talkingTo = null
        subscribed.clear()
        queued.clear()
    }

    /** A payer subscribed to the out door. Nothing is said: the payer speaks first. */
    fun subscribed(central: String) {
        if (finished || linked) return
        if (early) {
            say("a payer tried to join on the amount screen; not yet")
            return
        }
        if (talkingTo != null && talkingTo != central) {
            say("another phone tried to join; ignored")
            return
        }
        if (central !in subscribed) subscribed.add(central)
        // a subscribe takes nothing: the first word does (TapPlace)
        if (talkingTo != central) {
            say("a phone is listening; its first word takes the place")
            return
        }
        // the payer already talking, subscribing again: its handshake starts over
        takePlace(central)
    }

    /** The place is this phone's: a session for it, off the air, and a clock on the rest of the handshake. */
    private fun takePlace(central: String) {
        talkingTo = central
        arriving = TapProtocol.Bytes()
        session = newSession(service)
        radio.stopAdvertising()
        onEvent(TapStage.CONNECTING, null)
        handshakeTimer?.cancel()
        handshakeTimer = clock.after(HANDSHAKE_WAIT) {
            if (linked || finished || talkingTo != central || arriving.buffer.isNotEmpty()) return@after
            say("that phone began and did not finish in ${HANDSHAKE_WAIT.toInt()}s; back on the air")
            subscribed.remove(central)
            talkingTo = null
            session = null
            linkedCentral = null
            offerSent = false
            onEvent(TapStage.SEARCHING, null)
            advertise()
        }
    }

    /** A central let go of the out door. */
    fun unsubscribed(central: String) {
        if (finished) return
        subscribed.remove(central)
        if (central != talkingTo) return
        if (busy) {
            say("the payer went away with a payment in flight")
            if (!resultSent) onEvent(TapStage.GONE, null)
            // nothing more can come over a link that is gone
            if (!paymentHeard) {
                resultTimer?.cancel()
                resultTimer = null
                arriving = TapProtocol.Bytes()
            }
            // nor can a word that change was kept
            if (changeOwed && !changeTaken) {
                changeOwed = false
                say("the payer has gone, so the change is no longer waited for")
                changeAnswered(false)
            }
            return
        }
        if (linked) {
            if (!resultSent) {
                say("the payer went away after the link was made")
                onEvent(TapStage.GONE, null)
            }
            return
        }
        // back on the air: the person is still holding a code up
        say("that phone went away before the handshake finished")
        talkingTo = null
        session = null
        linkedCentral = null
        offerSent = false
        advertise()
    }

    /**
     * Writes from [central]: the pieces of one request (several for a long
     * write). True for success, false for the GATT layer to refuse
     * (insufficient authorization), as iOS answers a write on another door or
     * from a phone that does not hold the place.
     */
    fun write(central: String, pieces: List<ByteArray>, inDoor: Boolean = true): Boolean {
        // a write queued before the stop, arriving after it: refused, and nothing taken
        if (finished) return false
        if (inDoor && TapPlace.taken(central, talkingTo, central in subscribed, linked, early)) takePlace(central)
        if (!inDoor || central != talkingTo) {
            say("a write from ${if (central == talkingTo) "the payer on another characteristic" else "a central that is not the payer"}; refused")
            return false
        }
        for (piece in pieces) {
            val made = session
            if (made == null) {
                say("a write arrived with no session to read it")
                continue
            }
            when (val got = arriving.add(piece)) {
                TapProtocol.Bytes.Result.More -> {
                    // how far a large payment has got, for the screen
                    val p = arriving.progress
                    if (p != null && TapSession.carriesPayment(p.kind) && (p.want > 6000 || expecting > 0)) {
                        val total = if (expecting > 0) expecting else p.want.toLong()
                        arrivedSaid.step(clampInt(arrivedBefore + p.have), clampInt(total), cap = 99)
                            ?.let { onEvent(TapStage.RECEIVING, it.toString()) }
                    }
                }
                TapProtocol.Bytes.Result.Bad -> {
                    arriving = TapProtocol.Bytes()
                    made.stopped("a message that would not read")
                }
                is TapProtocol.Bytes.Result.Done -> {
                    arriving = TapProtocol.Bytes()
                    val body = got.body
                    // a part that is in counts towards the whole; anything else ends the count
                    if (TapSession.carriesPayment(body.firstOrNull()?.toInt()?.and(0xff)) && expecting > 0) {
                        arrivedBefore += body.size + 2
                    }
                    heard(central, made, made.received(body))
                }
            }
        }
        return true
    }

    private fun clampInt(n: Long): Int = n.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private fun heard(central: String, made: TapSession, step: TapSession.Step) {
        when (step) {
            is TapSession.Step.PaymentSize -> {
                expecting = step.bytes
                arrivedBefore = 0
                arrivedSaid = TapProgress()
                say("a payment of ${step.bytes} bytes is on its way")
            }
            is TapSession.Step.Send -> push(step.bytes, central)
            is TapSession.Step.Linked -> {
                handshakeTimer?.cancel()
                handshakeTimer = null
                linked = true
                linkedCentral = central
                onEvent(TapStage.CODE, step.code)
                sendOfferIfReady()
            }
            is TapSession.Step.Payment -> {
                // asked of the payment, not of the clock: M9 starts the clock too
                if (resultSent || paymentHeard) return
                paymentHeard = true
                expecting = 0
                arrivedBefore = 0
                arrivedSaid = TapProgress()
                // the page's word drives M6; nothing is sealed until it comes
                resultTimer?.cancel()
                resultTimer = clock.after(RESULT_WAIT) { sealResult(504, "Their wallet did not answer in time.") }
                onPaid(step.text) { status, why -> clock.after(0.0) { sealResult(status, why) } }
            }
            is TapSession.Step.Again -> {
                val frames = made.framesSince(step.from)
                say("the payer was away; saying ${frames.size} message(s) again")
                for (f in frames) push(f, central)
            }
            TapSession.Step.ChangeTaken -> {
                changeOwed = false
                changeTaken = true
                say("the payer kept the change")
                changeAnswered(true)
            }
            is TapSession.Step.Stop -> {
                made.stopped(step.why)
                session = null
                talkingTo = null
                linked = false
                linkedCentral = null
                offerSent = false
                advertise()
            }
            is TapSession.Step.OtherVersion -> {
                step.reply?.let { push(it, central) }
                made.stopped("the payer speaks version ${step.theirs} of the tap; this phone speaks ${TapCrypto.VERSION}")
                handshakeTimer?.cancel()
                handshakeTimer = null
                session = null
                talkingTo = null
                linked = false
                linkedCentral = null
                offerSent = false
                onEvent(TapStage.VERSION, step.theirs.toString())
                advertise()
            }
            is TapSession.Step.Quote -> {
                say("a word from the payer arrived (a price, a question, or paid), handing it to the page")
                onQuote(step.text)
            }
            // the payer's half of the conversation arriving at the wrong door: nothing to act on
            TapSession.Step.Waiting, is TapSession.Step.Offer, is TapSession.Step.Result,
            is TapSession.Step.Change, TapSession.Step.Asking, is TapSession.Step.Terms,
            -> Unit
        }
    }

    /** What became of the payment, sealed as M6. Said once: the page's answer and the clock race. */
    private fun sealResult(code: Int, why: String) {
        if (resultSent) return
        resultTimer?.cancel()
        resultTimer = null
        val made = session
        val central = payerCentral()
        if (made == null || central == null) {
            resultSent = true
            say("the page answered $code and the payer has gone; nobody to tell")
            return
        }
        resultSent = true
        val ok = code == 200
        val json = if (ok) "{\"v\":2,\"ok\":true,\"code\":200}"
        else "{\"v\":2,\"ok\":false,\"code\":$code,\"why\":${Json.quote(prefix(why, MOST_WHY))}}"
        // and a bare one if even that would not seal, so M6 is never silence
        val sealed = made.sealResult(json) ?: made.sealResult("{\"v\":2,\"ok\":$ok,\"code\":$code}") ?: run {
            say("the result would not seal; the payer will time out")
            return
        }
        say("the page answered $code; telling the payer")
        push(sealed, central)
        // a stop that was waiting on this can have its way now, after a beat, unless change is owed
        if (stopping && !(changeOwed && !changeTaken)) {
            clock.after(AFTER_RESULT) {
                if (changeOwed && !changeTaken) return@after
                reallyStop()
            }
        }
    }

    /** The first [n] characters as a person reads them (Swift's `prefix` on a String counts those). */
    private fun prefix(s: String, n: Int): String {
        val chars = BreakIterator.getCharacterInstance()
        chars.setText(s)
        var end = 0
        repeat(n) {
            val next = chars.next()
            if (next == BreakIterator.DONE) return s
            end = next
        }
        return s.substring(0, end)
    }

    private fun push(message: ByteArray, central: String) {
        val frame = TapProtocol.framed(message) ?: run {
            say("a message too long to frame")
            return
        }
        for (piece in TapProtocol.pieces(frame, radio.notifyLength(central))) queued.addLast(central to piece)
        pump()
    }

    private fun pump() {
        while (queued.isNotEmpty()) {
            val (central, piece) = queued.first()
            if (!radio.notify(central, piece)) return
            queued.removeFirst()
        }
    }

    /** The radio can take notifications again. */
    fun readyToUpdate() {
        if (finished) return
        pump()
    }
}
