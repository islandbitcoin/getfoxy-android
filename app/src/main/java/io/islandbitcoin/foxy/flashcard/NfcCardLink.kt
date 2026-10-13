package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.debug.Log
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The phone's radio in reader mode, as [NfcCardLink] needs it ([ReaderModeRadio]
 * on a phone, a fake in the JVM tests).
 */
interface CardRadio {
    /** Whether this phone can read a card now: an NFC adapter, switched on. */
    fun available(): Boolean

    /** Start looking (reader mode on); each card that comes into the field is handed to `found`, on the radio's own thread. False when it could not start. */
    fun start(found: (CardTag) -> Unit): Boolean

    /** Stop looking (reader mode off). Nothing when not looking. */
    fun stop()
}

/** One card in the field ([ReaderModeRadio]'s IsoDep, or a fake). Blocking: never called on the main thread. */
interface CardTag {
    /** An ISO 14443-4 card that speaks commands (IsoDep); anything else is not a Foxy card. */
    val speaksCommands: Boolean

    @Throws(IOException::class)
    fun connect()

    /** The answer with its two status bytes on the end. */
    @Throws(IOException::class)
    fun transceive(apdu: ByteArray): ByteArray

    fun close()
}

/**
 * Foxy's own card sheet: Android has no system one, so this is drawn by the app
 * over the page ([CardSheetView]), in the states and with the words of iOS's
 * (the CoreNFC sheet: "Ready to Scan", the page's line, Cancel; a tick and a
 * word, or the reason in red). Main thread.
 */
interface CardSheet {
    /** Up, with `text` and a Cancel that calls `cancel`. */
    fun open(text: String, cancel: () -> Unit)

    /** The line changed. */
    fun show(text: String)

    /** A tick and `text`, then gone. */
    fun finish(text: String)

    /** `text` in red, then gone. */
    fun fail(text: String)

    /** Gone now, with nothing said (cancelled, timed out, the card lost, Foxy put away). */
    fun dismiss()
}

/** The main looper, as [NfcCardLink] needs it: a post, and a post later that can be taken back. */
interface CardMain {
    fun post(action: Runnable)

    /** `action` after `ms`; the function returned cancels it. */
    fun later(ms: Long, action: Runnable): () -> Unit
}

/**
 * A card over the phone's NFC radio (CardLink.swift `NFCCardLink`, the same
 * session rules with Android's parts): reader mode while the sheet waits for a
 * card, IsoDep for the commands, and Foxy's own sheet in place of iOS's.
 *
 * - A sheet waits [WAIT_MS] for a card, as iOS's waits a minute (and as its
 *   SimCardLink does, measured from each `begin` and `again`); then it ends,
 *   `timed out`.
 * - Cancel on the sheet ends the session, `cancelled`, as iOS's Cancel.
 * - A card that is not one that speaks commands is passed over; the sheet says
 *   [ONE_CARD] and goes on looking.
 * - Putting Foxy away ends it ([putAway]): reader mode is the resumed
 *   activity's only.
 * - The page closing it ([end]) answers a wait still open `cancelled`, and
 *   says nothing lost.
 *
 * Blocking work (connect, transceive) runs on [io], one command at a time;
 * everything else, and every callback, on the main thread through [main].
 * [io] may be shut down under a link that is still finishing (WebHost.destroy:
 * the renderer gone, or Foxy put away and destroyed at once); work it then
 * refuses is not done, and a card is let go here instead (closing is safe on
 * any thread), never a RejectedExecutionException on the main thread.
 */
class NfcCardLink(
    private val radio: CardRadio,
    private val sheet: CardSheet,
    private val main: CardMain,
    private val io: Executor,
) : CardLink {
    companion object {
        /** How long the sheet waits for a card. */
        const val WAIT_MS = 60_000L

        /**
         * What the sheet says when what is held to the phone is not a card that
         * speaks commands (iOS: "Hold one card to the top of the phone"; an
         * Android phone's antenna is on its back).
         */
        const val ONE_CARD = "Hold one card to the back of the phone"

        /** What a sheet still waiting for a card ends with when Foxy is put away (CardLink.swift `wentAway`). */
        const val PUT_AWAY = "Foxy was put away"
    }

    override var onGone: (() -> Unit)? = null
    override var onProgress: ((String, String) -> Unit)? = null

    /** The session is open: the sheet is up. */
    private var open = false
    private var card: CardTag? = null
    private var found: ((CardLinkError?) -> Unit)? = null
    private var stopWaiting: (() -> Unit)? = null

    /** Which look this is: a card found by an earlier one, or after the session ended, is let go. */
    private var look = 0

    override fun begin(text: String, found: (CardLinkError?) -> Unit) {
        this.found = found
        if (!radio.available()) {
            Log.line("[card] sheet: this phone has no NFC, or it is switched off")
            tell(CardLinkError.UNAVAILABLE)
            return
        }
        open = true
        sheet.open(text) { invalidate(CardLinkError.CANCELLED, "cancelled on the sheet") }
        Log.line("[card] sheet: open")
        looking()
    }

    override fun again(text: String, found: (CardLinkError?) -> Unit) {
        if (!open) return found(CardLinkError.CANCELLED)
        letCardGo()
        this.found = found
        sheet.show(text)
        Log.line("[card] sheet: looking for the card again")
        // off and on: a card still in the field is found afresh, as iOS's restartPolling finds it
        radio.stop()
        looking()
    }

    private fun looking() {
        val mine = ++look
        stopWaiting?.invoke()
        stopWaiting = main.later(WAIT_MS) {
            if (mine == look && open && card == null) invalidate(CardLinkError.TIMED_OUT, "nobody held a card up")
        }
        val started = radio.start { tag -> main.post { tagFound(tag, mine) } }
        if (!started) invalidate(CardLinkError.UNAVAILABLE, "reader mode would not start")
    }

    private fun tagFound(tag: CardTag, mine: Int) {
        if (mine != look || !open || card != null || found == null) {
            // not this look's, or no one is waiting for a card: it is let go, and the page asks again (cardAgain) if it wants it
            release(tag)
            return
        }
        if (!tag.speaksCommands) {
            sheet.show(ONE_CARD)
            Log.line("[card] sheet: something in the field that is not a card that speaks commands")
            return
        }
        onIo(instead = { closeHere(tag) }) {
            val connected = try {
                tag.connect()
                true
            } catch (e: IOException) {
                false
            } catch (e: RuntimeException) {
                false
            }
            main.post {
                if (mine != look || !open || card != null) {
                    release(tag)
                } else if (!connected) {
                    // as iOS: a card that would not connect is left, and the sheet goes on looking
                    Log.line("[card] sheet: a card that would not connect; still looking")
                } else {
                    card = tag
                    stopWaiting?.invoke()
                    stopWaiting = null
                    sheet.show(CardGate.SCANNING)
                    onProgress?.invoke("connected", CardGate.SCANNING)
                    tell(null)
                }
            }
        }
    }

    override fun send(apdu: ByteArray, done: (ByteArray?, CardLinkError?) -> Unit) {
        val tag = card
        if (tag == null || !open) return done(null, CardLinkError.LOST)
        val answered = { answer: ByteArray? ->
            if (answer == null) {
                if (card === tag) letCardGo()
                onProgress?.invoke("lost", CardLinkError.LOST.words)
                done(null, CardLinkError.LOST)
            } else {
                done(answer, null)
            }
        }
        // no io to carry it on: the card is as good as lost
        onIo(instead = { answered(null) }) {
            val answer = try {
                tag.transceive(apdu).takeIf { it.size >= 2 }
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            main.post { answered(answer) }
        }
    }

    override fun say(text: String) {
        if (text.isEmpty()) return
        if (open) sheet.show(text)
        onProgress?.invoke("say", text)
    }

    override fun end(error: String?, text: String?) {
        if (!open) {
            // closed while nothing was open: a wait still told is ended
            tell(CardLinkError.CANCELLED)
            return
        }
        close()
        // a sheet still waiting for a card is told it is over here, as iOS's `end` tells it
        tell(CardLinkError.CANCELLED)
        if (!error.isNullOrEmpty()) {
            onProgress?.invoke("end", error)
            sheet.fail(error)
        } else {
            onProgress?.invoke("end", text ?: "")
            sheet.finish(text ?: "")
        }
        Log.line("[card] sheet: closed by the page")
        gone()
    }

    override fun putAway() {
        if (!open) return
        if (card == null && found != null) {
            Log.line("[card] sheet: Foxy was put away while it waited for a card; the wait ends")
            end(PUT_AWAY, null)
        } else {
            invalidate(CardLinkError.CANCELLED, "Foxy was put away")
        }
    }

    /** The session ends under the page (Cancel, the wait, no radio, Foxy away): it is told the card is lost, and why. */
    private fun invalidate(why: CardLinkError, note: String) {
        if (!open) return
        close()
        sheet.dismiss()
        Log.line("[card] sheet: ended, $note")
        onProgress?.invoke("lost", why.words)
        tell(why)
        gone()
    }

    private fun close() {
        open = false
        look++
        stopWaiting?.invoke()
        stopWaiting = null
        radio.stop()
        letCardGo()
    }

    private fun letCardGo() {
        val tag = card ?: return
        card = null
        release(tag)
    }

    /** A card let go: closed on [io] behind any command still on it, or here when [io] is shut down. */
    private fun release(tag: CardTag) {
        onIo(instead = { closeHere(tag) }) { closeHere(tag) }
    }

    private fun closeHere(tag: CardTag) {
        try {
            tag.close()
        } catch (e: RuntimeException) {
            // closing a card that has gone says nothing worth hearing
        }
    }

    /** [task] on [io]; when [io] refuses it (shut down), [instead] runs on this thread. */
    private fun onIo(instead: () -> Unit, task: () -> Unit) {
        try {
            io.execute(task)
        } catch (e: RejectedExecutionException) {
            instead()
        }
    }

    private fun tell(why: CardLinkError?) {
        val f = found ?: return
        found = null
        f(why)
    }

    private fun gone() {
        val g = onGone
        onGone = null
        g?.invoke()
    }
}
