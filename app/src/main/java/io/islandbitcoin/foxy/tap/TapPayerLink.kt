package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.debug.Log
import java.util.UUID

/**
 * The paying phone's side of one link, from the moment its proximity verdict
 * picks a receiver, with the radio left out (Foxy/Bluetooth/TapLink.swift
 * `TapPayer`): the give-up clocks, the handshake down the doors, the offer, the
 * payment over the same link with its size first when it is large, the
 * asking wait, the result, the change and the word that it was kept, the
 * question asked again after Foxy was away, and what a write that failed means.
 *
 * Scanning, warming a quiet link, the proximity verdict ([TapProximity]) and
 * the near doors are the GATT client's (ENG-671), which plugs in underneath as
 * a [Radio] and makes a new link for every receiver it connects to: when this
 * one gives up ([Radio.giveUp]) it is spent, as iOS's `retry` starts over with
 * a new session. Everything runs on the [clock]'s thread.
 */
class TapPayerLink(
    val service: UUID,
    private val radio: Radio,
    private val clock: TapClock,
    private val onStage: (TapStage) -> Unit,
    /** The offer, once it is in: the payload and the four digits. */
    private val done: (String, String) -> Unit,
    /** Change: the token, and how the page says it has written it down. */
    private val onChange: (String, (Boolean) -> Unit) -> Unit = { _, keep -> keep(false) },
    /** M11: the receiver agreed the price and sent the request. */
    private val onTerms: (String) -> Unit = {},
    private val newSession: (UUID) -> TapSession = { TapSession(TapSession.Role.PAYER, it) },
    private val diary: (String) -> Unit = Log::line,
) {
    /** What the GATT client does for this link. Every call is made on the link's thread. */
    interface Radio {
        /** Turn notifications on for the out door; [TapPayerLink.subscribed] follows. */
        fun subscribe()

        /** One write with response on the in door; [TapPayerLink.wrote] follows. */
        fun write(piece: ByteArray)

        /** The most one write may carry. */
        fun writeLength(): Int

        /** Whether the link to the receiver is still up. */
        val connected: Boolean

        /** The link came to nothing before an offer: let it go and look again. */
        fun giveUp(why: String)

        /** The offer is in: this phone has the receiver it came for, so scanning stops. */
        fun holdLink()

        /** The receiver answered a payment: leave that phone alone a while (90 s, and 5 s of quiet). */
        fun answered()

        /** Let the connection go. */
        fun close()
    }

    /** What became of a payment handed over: the receiver's own word, or why it never got that far. */
    sealed class Handover {
        /** M6 came back. The text is the receiver's answer, as it sealed it. */
        data class Answered(val text: String) : Handover()

        /** The link went, or was never there. Nothing was confirmed: the payment falls back to its own transport. */
        data class Lost(val why: String) : Handover()
    }

    companion object {
        /** Connected and nothing came of it. */
        const val CONNECT_WAIT = 8.0

        /** Once the receiver has answered at all: its page may still be getting an invoice. */
        const val OFFER_WAIT = 30.0

        /** A person is reading a card on the other phone (M9). */
        const val PERSON_WAIT = 95.0

        /** After Foxy comes back with a payment handed over, how long the receiver has to say it again. */
        const val AGAIN_WAIT = 6.0

        /** The question is not asked again sooner than this. */
        const val AGAIN_EVERY = 5.0

        /** How often a stop waiting for a word still going out looks again. */
        const val CLOSE_WAIT = 2.0

        /** Above this many bytes on the wire, the size is said first. */
        const val SAY_SIZE_ABOVE = 16_000
    }

    private var session: TapSession? = null
    private var incoming = TapProtocol.Bytes()
    private var giveUpTimer: TapClock.Timer? = null
    private var waitingOnPerson = false
    private var nearNow = false
    private var receiverSpoke = false
    private var linked = false
    private var finished = false
    private var spent = false
    private var spoke = false
    private val outgoing = ArrayDeque<ByteArray>()
    private var writing = false
    private var sendDone: ((Handover) -> Unit)? = null
    private var paymentSent = false
    private var askedAgainAt = Double.NEGATIVE_INFINITY
    private var againTimer: TapClock.Timer? = null
    private var againOut = false
    private var ackOut = false
    private var closingAfterWrites = false
    private var sendPieces = 0
    private var sentSaid = TapProgress()

    /** How far the payment in hand has got, as a percentage, for the page. */
    var progress = 0
        private set

    private fun say(what: String) = diary("[tap] pay: $what")

    /** The verdict picked this receiver and the link is being made: eight seconds for something to come of it. */
    fun connecting() {
        if (spent || finished) return
        onStage(TapStage.CONNECTING)
        nearNow = true
        giveUpTimer?.cancel()
        giveUpTimer = clock.after(CONNECT_WAIT) { retry("timed out") }
    }

    /** The doors are found and the verdict is in: subscribe, which is the first thing the receiver can hear. */
    fun speak() {
        if (spent || finished || session != null) return
        session = newSession(service)
        radio.subscribe()
    }

    /**
     * The subscribe landed: the payer speaks first, so M1 goes out here.
     *
     * A subscribe that failed ([error] set) is ignored, as on iOS: there
     * `didUpdateNotificationStateFor` returns at `guard ch.isNotifying` before
     * its error branch is reached (TapLink.swift:1624-1625 at 8df0bb1), and a
     * failed subscribe is not notifying. The link then waits out
     * [CONNECT_WAIT] and gives up there, like an iOS payer.
     */
    fun subscribed(error: String? = null) {
        val made = session ?: return
        if (spoke || spent || finished) return
        if (error != null) {
            say("subscribing failed ($error); waiting out the connect clock, as iOS does")
            return
        }
        spoke = true
        (made.begin() as? TapSession.Step.Send)?.let { write(it.bytes) }
    }

    /** A notification on the out door: a piece of the receiver's next message. */
    fun notified(piece: ByteArray) {
        val made = session ?: return
        if (spent || finished) return
        if (!receiverSpoke) {
            receiverSpoke = true
            // longer, now that somebody is really there
            giveUpTimer?.cancel()
            giveUpTimer = clock.after(OFFER_WAIT) { retry("nothing came after the handshake") }
        }
        when (val got = incoming.add(piece)) {
            TapProtocol.Bytes.Result.More -> return
            TapProtocol.Bytes.Result.Bad -> retry("a message that would not read")
            is TapProtocol.Bytes.Result.Done -> heard(made, made.received(got.body))
        }
    }

    private fun heard(made: TapSession, step: TapSession.Step) {
        when (step) {
            is TapSession.Step.Send -> write(step.bytes)
            is TapSession.Step.Offer -> {
                // the handshake is over and the link is held: the payment goes back over it
                linked = true
                giveUpTimer?.cancel()
                giveUpTimer = null
                radio.holdLink()
                onStage(TapStage.LINKED)
                done(step.text, step.code)
            }
            is TapSession.Step.Result -> {
                say("the receiver said ${step.text.take(60)}")
                radio.answered()
                finishSend(Handover.Answered(step.text))
            }
            is TapSession.Step.Change -> {
                // money: the page answers when it has written it down, and only then does M8 go back
                say("change arrived, handing it to the page")
                onChange(step.text) { kept -> clock.after(0.0) { ackChange(kept) } }
            }
            TapSession.Step.Asking -> {
                // once only: a second M9 would let the other side hold this link open for as long as it kept sending them
                if (waitingOnPerson) {
                    say("a second asking; ignored")
                    return
                }
                say("they are asking a person; waiting")
                waitingOnPerson = true
                giveUpTimer?.cancel()
                giveUpTimer = clock.after(PERSON_WAIT) { retry("they never came back from asking a person") }
                onStage(TapStage.ASKING)
            }
            is TapSession.Step.Stop -> {
                made.stopped(step.why)
                retry(step.why)
            }
            is TapSession.Step.OtherVersion -> {
                made.stopped("the receiver speaks version ${step.theirs} of the tap; this phone speaks ${TapCrypto.VERSION}")
                onStage(TapStage.VERSION)
                retry("not a Foxy this phone can talk to (version ${step.theirs})")
            }
            is TapSession.Step.Terms -> {
                say("they agreed the price; the request is in")
                onTerms(step.text)
            }
            TapSession.Step.Waiting, is TapSession.Step.Linked, is TapSession.Step.Payment,
            TapSession.Step.ChangeTaken, is TapSession.Step.Quote, is TapSession.Step.Again,
            is TapSession.Step.PaymentSize,
            -> Unit
        }
    }

    /** The link to give up on, before an offer: quiet when nothing had been said on screen. */
    private fun retry(why: String) {
        if (finished || linked || spent) return
        if (nearNow) {
            say("gave up on that one ($why); looking again")
            onStage(TapStage.GONE)
            onStage(TapStage.SEARCHING)
        }
        giveUpTimer?.cancel()
        giveUpTimer = null
        outgoing.clear()
        writing = false
        spent = true
        session = null
        radio.giveUp(why)
    }

    /** M10: this phone's price, a question, or the word that an invoice is paid. Sent before anything is paid. */
    fun sendQuote(text: String): Boolean {
        if (!linked || !radio.connected) return false
        val made = session ?: return false
        val sealed = made.sealQuote(text) ?: return false
        write(sealed)
        say("sent the receiver a word on the link (a price, a question, or paid)")
        return true
    }

    /** M8, once the page has the change written down. A refusal is not sent: the receiver's clock answers it. */
    private fun ackChange(kept: Boolean) {
        val made = session
        val sealed = if (kept && !finished && made != null) made.sealChangeTaken() else null
        if (sealed == null) {
            if (!kept) say("the page could not keep the change")
            return
        }
        say("change written down; telling the receiver")
        ackOut = true
        write(sealed)
    }

    /**
     * Ends this link once a word still going out has gone (TapLink.swift
     * `TapPayer.stop`, 1064-1079 at 8df0bb1). Every [CLOSE_WAIT] seconds the
     * stop is asked again: while a write is still out and the link is up it
     * waits again, so the link closes when [wrote] hears the write land or
     * fail (the radio's own ATT timeout bounds that), or when the link drops.
     * This is iOS's behaviour, not its comment's "two seconds at most": a
     * word that the change was kept (M8) is never cut off mid-flight.
     */
    fun stop() {
        if ((writing || outgoing.isNotEmpty()) && radio.connected && !closingAfterWrites) {
            closingAfterWrites = true
            say("a word is still going out; the link closes once it has")
            clock.after(CLOSE_WAIT) {
                if (!closingAfterWrites) return@after
                say("the word did not finish going out in ${CLOSE_WAIT.toInt()}s; closing anyway")
                closingAfterWrites = false
                // as iOS: asked again, which waits again while the write is still out and the link up
                stop()
            }
            return
        }
        closingAfterWrites = false
        close()
    }

    private fun close() {
        if (finished) return
        finished = true
        giveUpTimer?.cancel()
        giveUpTimer = null
        outgoing.clear()
        radio.close()
        session = null
        if (sendDone != null) finishSend(Handover.Lost("Nobody is connected any more."))
    }

    /** The receiver went. Mid-payment that is said now rather than waited out. */
    fun disconnected(error: String? = null) {
        if (sendDone != null) {
            finishSend(Handover.Lost("That phone went away before it said what became of the payment."))
            return
        }
        if (nearNow) say("the link closed" + (error?.let { " — $it" } ?: ""))
        if (!finished && !linked) retry("disconnected")
    }

    // MARK: writes, one at a time and the next only when the last is acknowledged

    private fun write(message: ByteArray) {
        val frame = TapProtocol.framed(message) ?: run {
            say("a message too long to frame")
            return
        }
        outgoing.addAll(TapProtocol.pieces(frame, radio.writeLength()))
        pumpWrites()
    }

    private fun pumpWrites() {
        if (writing || outgoing.isEmpty() || !radio.connected) return
        writing = true
        radio.write(outgoing.removeFirst())
    }

    /** The last write was acknowledged, or failed with [error]. */
    fun wrote(error: String? = null) {
        writing = false
        if (error != null) {
            say("a piece would not go — $error")
            outgoing.clear()
            // which word it was decides what follows
            val failed = TapWriteFailure.after(question = againOut, payment = sendDone != null, changeKept = ackOut)
            if (ackOut) {
                ackOut = false
                say("the word that the change was kept did not get through")
            }
            if (againOut) {
                // only the question "what became of it": its answer can still come unasked, and the wait set for it gives up
                againOut = false
                if (sendDone != null) say("they could not take the question; still waiting for their answer")
                if (closingAfterWrites) {
                    closingAfterWrites = false
                    stop()
                }
                return
            }
            if (sendDone != null) finishSend(Handover.Lost("That phone stopped taking the payment."))
            if (closingAfterWrites) {
                closingAfterWrites = false
                stop()
                return
            }
            // any other word took its place in the count and did not go: the link cannot carry the next one
            if (failed == TapWriteFailure.LET_LINK_GO && !finished) {
                say("that word took its place in the count and did not go; letting this link go and making another")
                linked = false
                retry("a word did not get through")
            }
            return
        }
        if (ackOut && outgoing.isEmpty()) {
            ackOut = false
            say("the receiver was told the change was kept")
        }
        if (againOut && outgoing.isEmpty()) againOut = false
        if (closingAfterWrites && outgoing.isEmpty()) {
            closingAfterWrites = false
            stop()
            return
        }
        // a payment of many pieces says how far it has got
        if (sendDone != null && sendPieces > 12) {
            sentSaid.step(sendPieces - outgoing.size, sendPieces)?.let {
                progress = it
                onStage(TapStage.SENDING)
            }
        }
        pumpWrites()
    }

    // MARK: the payment, over the link that is already open

    /** Hand the payment to the receiver; [then] is told once what became of it. */
    fun send(body: String, then: (Handover) -> Unit) {
        if (sendDone != null) {
            then(Handover.Lost("A payment is already going over."))
            return
        }
        val made = session
        if (!linked || made == null || !radio.connected) {
            then(Handover.Lost("That phone is no longer connected."))
            return
        }
        // the size first, sealed first, when it will take a while; never for an ordinary payment
        val wire = made.paymentWireEstimate(body)
        val size = if (wire > SAY_SIZE_ABOVE && body.toByteArray(Charsets.UTF_8).size <= TapCrypto.MOST_PAYMENT_TOTAL) {
            made.sealPaymentSize(wire)
        } else {
            null
        }
        val frames = made.sealPaymentFrames(body) ?: run {
            then(Handover.Lost("That payment could not be sealed."))
            return
        }
        sendDone = then
        paymentSent = true
        size?.let { write(it) }
        frames.forEach { write(it) }
        sendPieces = outgoing.size
        progress = 0
        sentSaid = TapProgress()
    }

    private fun finishSend(how: Handover) {
        val tell = sendDone
        sendDone = null
        againTimer?.cancel()
        againTimer = null
        tell?.invoke(how)
    }

    /**
     * Foxy is back in front with a payment handed over on this link: whatever
     * the receiver said meanwhile may be gone, so it is asked for again (M12),
     * and the receiver has [AGAIN_WAIT] seconds to answer.
     */
    fun askAgain() {
        val now = clock.now()
        val made = session ?: return
        if (!paymentSent || !linked || now - askedAgainAt <= AGAIN_EVERY || !radio.connected) return
        askedAgainAt = now
        incoming = TapProtocol.Bytes()
        // only when nothing else is waiting to be written: then a refusal is this question's
        val alone = outgoing.isEmpty() && !writing
        val sealed = made.sealAgain() ?: return
        say("back in front with a payment handed over; asking the receiver to say it again")
        againOut = alone
        write(sealed)
        if (sendDone == null) return
        againTimer?.cancel()
        againTimer = clock.after(AGAIN_WAIT) {
            if (sendDone == null) return@after
            say("the receiver did not say what became of it after Foxy came back")
            finishSend(Handover.Lost("That phone did not answer after Foxy came back."))
        }
    }
}
