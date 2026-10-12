package io.islandbitcoin.foxy.flashcard

/**
 * One tap of a card: the phone's NFC session, and the card found in it (a port
 * of Foxy/Flashcard/CardLink.swift, getfoxy/iOS 8df0bb1).
 *
 * The page asks for a session (`cardBegin`), talks to the card through it one
 * command at a time (`cardSend`), may change the one line of text on the
 * phone's sheet (`cardSay`), looks for the card again when it left part way
 * (`cardAgain`), and closes it (`cardEnd`). [io.islandbitcoin.foxy.bridge.CardBridge]
 * holds one of these for as long as that lasts and reads every command before
 * it is carried ([CardGate]).
 *
 * What is carried is not kept and not logged. One of the commands is the
 * card's PIN.
 *
 * Two links: [NfcCardLink], the phone's radio in reader mode with Foxy's own
 * card sheet ([CardSheet]: Android has no system NFC sheet); and in debug
 * builds only, `SimCardLink`, which reaches the applet running in jCardSim on
 * the developer's machine over loopback, as iOS's Simulator build does. The
 * page cannot tell which it has.
 *
 * Every method and every callback is on the main thread.
 */
interface CardLink {
    /** Told once, when the session has gone for any reason. */
    var onGone: (() -> Unit)?

    /** Told as the session goes on, so the page can show what the sheet shows: (stage, text), the stage one of [CardGate.STAGES]. */
    var onProgress: ((stage: String, text: String) -> Unit)?

    /** Open the sheet with `text` on it. `found(null)` when a card is there to talk to; `found(why)` when the session ended first. */
    fun begin(text: String, found: (CardLinkError?) -> Unit)

    /** The card left part way through: the sheet stays up with `text` and looks for the card again; `found` as [begin]'s. */
    fun again(text: String, found: (CardLinkError?) -> Unit)

    /** One command, and the card's answer with its two status bytes on the end; or why there is none. */
    fun send(apdu: ByteArray, done: (answer: ByteArray?, why: CardLinkError?) -> Unit)

    /** The sheet's line, changed. */
    fun say(text: String)

    /** Close the sheet: with a tick and `text`, or with `error` said in red. */
    fun end(error: String?, text: String?)

    /**
     * Foxy left the foreground. iOS ends a session then (the system takes the
     * NFC sheet down, and `wentAway` ends a sheet still waiting for a card with
     * "Foxy was put away"); reader mode on Android is the resumed activity's
     * only, so the same is done here.
     */
    fun putAway()
}

/** Why a session ended without a card, in words the page reads (`cardSession` in build/wallet/21a-flashcard.js tells them apart). */
enum class CardLinkError(val words: String) {
    /** This phone cannot read a card at all, or this build may not. */
    UNAVAILABLE("NFC is not available on this phone"),

    /** The sheet was dismissed. */
    CANCELLED("the session was cancelled"),

    /** Nobody held a card up in time. */
    TIMED_OUT("the session timed out"),

    /** The card left, or the session ended under it. */
    LOST("the tag was lost"),
}
