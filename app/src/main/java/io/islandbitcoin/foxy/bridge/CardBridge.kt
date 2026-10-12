package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.flashcard.CardGate
import io.islandbitcoin.foxy.flashcard.CardLink
import io.islandbitcoin.foxy.flashcard.CardLinkError

/**
 * A card that holds ecash, held to the phone: the session actions of
 * Foxy/Bridge/FoxyBridge+Flashcard.swift (getfoxy/iOS 8df0bb1), ported.
 *
 * Five actions for the session, and one session at a time. The page opens a
 * session (`cardBegin`), talks to the card through it (`cardSend`), may change
 * the line of text on the phone's sheet (`cardSay`), looks for a card that left
 * part way (`cardAgain`), and closes it (`cardEnd`). What it may say to the
 * card is [CardGate]'s to decide, command by command: this app's applet,
 * chosen by name, and then that applet's own instructions. Nothing a card says
 * or is told is kept or logged here. One of the commands carries the card's PIN.
 *
 * The session ends by itself when the page closes it, when the person cancels
 * the sheet, after a minute with no card, and when Foxy leaves the foreground
 * ([putAway]). How the session reports itself to the page is the `_card` push
 * ([CardGate.progressScript]), only while it is the session in hand.
 *
 * The link is the host's ([PageHost.cardLink]): the radio on a phone, the card
 * simulator in a debug build without one, none in the JVM tests unless a test
 * gives one. Main thread.
 */
class CardBridge(
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
) {
    companion object {
        val ACTIONS = setOf("cardBegin", "cardSend", "cardSay", "cardAgain", "cardEnd")

        const val NOT_A_COMMAND = "That is not a command for a card."
        const val NOT_FOXYS = "That is not a command Foxy sends to a card."

        /** The sheet's line when the page gives none (FoxyBridge+Flashcard.swift); a phone's antenna is on its back. */
        const val BEGIN_LINE = "Hold the card to the back of the phone"
        const val AGAIN_LINE = "Hold the card here again"
    }

    /** The session in hand, and whether its applet has answered that it was chosen. */
    private var link: CardLink? = null
    private var selected = false

    /** Whether a session is open (the tests). */
    val open: Boolean get() = link != null

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "cardBegin" -> begin(id, body)
            "cardSend" -> send(id, body)
            "cardSay" -> say(id, body)
            "cardAgain" -> again(id, body)
            "cardEnd" -> end(id, body)
            else -> error("not a card action: $action")
        }
    }

    /**
     * {text}: open the phone's card sheet with this line on it. Answers "ok"
     * when a card is there to talk to, and otherwise why not: no NFC, the
     * sheet dismissed, or nobody held a card up.
     */
    private fun begin(id: String, body: Map<String, Any?>) {
        val text = CardGate.line(body["text"] as? String)
        host.post {
            // one at a time: a session the page left open is closed before the next opens
            link?.let { old ->
                link = null
                selected = false
                old.end(null, null)
            }
            val made = host.cardLink()
            if (made == null) {
                resolve(id, null, CardLinkError.UNAVAILABLE.words)
                return@post
            }
            link = made
            selected = false
            made.onGone = {
                if (link === made) {
                    link = null
                    selected = false
                }
            }
            // what the sheet shows, shown on the page too: only while this is the session in hand
            made.onProgress = { stage, said ->
                if (link === made) CardGate.progressScript(stage, said)?.let { host.evaluate(it) }
            }
            made.begin(text.ifEmpty { BEGIN_LINE }) { why ->
                if (why != null) {
                    if (link === made) link = null
                    resolve(id, null, why.words)
                } else {
                    resolve(id, "ok", null)
                }
            }
        }
    }

    /**
     * {apdu}: one command for the card, as hex. Answers the card's reply as hex,
     * its two status bytes last. Refused, with nothing sent, when it is not a
     * command this app sends to its own applet.
     */
    private fun send(id: String, body: Map<String, Any?>) {
        val apdu = (body["apdu"] as? String)?.let { CardGate.bytes(it) }
        if (apdu == null) return resolve(id, null, NOT_A_COMMAND)
        host.post {
            val current = link
            if (current == null) {
                resolve(id, null, CardLinkError.LOST.words)
                return@post
            }
            if (!CardGate.allows(apdu, selected)) {
                resolve(id, null, NOT_FOXYS)
                return@post
            }
            val choosing = CardGate.read(apdu) == CardGate.Command.Select
            current.send(apdu) { answer, why ->
                if (answer == null) {
                    resolve(id, null, (why ?: CardLinkError.LOST).words)
                } else {
                    // chosen, or chosen again and refused: either way it is what the card last said
                    if (choosing && link === current) selected = CardGate.succeeded(answer)
                    resolve(id, CardGate.hex(answer), null)
                }
            }
        }
    }

    /**
     * {text}: the card left part way through, and the sheet stays up and looks
     * for it again, with this line on it. Answers "ok" when a card is there to
     * talk to (chosen afresh: the applet is chosen again before anything else),
     * and otherwise why not.
     */
    private fun again(id: String, body: Map<String, Any?>) {
        val text = CardGate.line(body["text"] as? String)
        host.post {
            val current = link
            if (current == null) {
                resolve(id, null, CardLinkError.CANCELLED.words)
                return@post
            }
            selected = false
            current.again(text.ifEmpty { AGAIN_LINE }) { why ->
                if (why != null) {
                    if (link === current) link = null
                    resolve(id, null, why.words)
                } else {
                    resolve(id, "ok", null)
                }
            }
        }
    }

    /** {text}: the line on the phone's sheet, changed while a session is open. */
    private fun say(id: String, body: Map<String, Any?>) {
        val text = CardGate.line(body["text"] as? String)
        host.post {
            link?.say(text)
            resolve(id, "ok", null)
        }
    }

    /**
     * {text} or {error}: close the sheet, with a tick and a word, or with the
     * reason in red. Also how the page takes down a sheet still waiting for a
     * card.
     */
    private fun end(id: String, body: Map<String, Any?>) {
        val error = (body["error"] as? String)?.let { CardGate.line(it) }
        val text = CardGate.line(body["text"] as? String)
        host.post {
            val current = link
            // the link tells the page it has ended while it is still the session in hand
            current?.end(error, text)
            link = null
            selected = false
            resolve(id, "ok", null)
        }
    }

    /** Foxy left the foreground: the session ends, as iOS's does (CardLink.swift `wentAway`). Main thread. */
    fun putAway() {
        link?.putAway()
    }

    /** The page went: its session goes with it, with nothing told to a page that is not there. Main thread. */
    fun pageGone() {
        val current = link ?: return
        link = null
        selected = false
        current.onProgress = null
        current.end(null, null)
    }
}
