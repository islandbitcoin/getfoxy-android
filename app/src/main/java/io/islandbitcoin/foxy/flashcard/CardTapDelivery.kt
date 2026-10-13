package io.islandbitcoin.foxy.flashcard

/**
 * A card held to the phone, on its way to the page: one message, kept until the page
 * can take it, and told once (getfoxy/iOS `Foxy/Flashcard/CardTapDelivery.swift`, ENG-668).
 *
 * Android hands Foxy the card's address ([CardAddress]) and nothing else, and Foxy
 * passes on that a card was tapped. The page decides what to do with it, by what is on
 * its screen (`cardTapRule`); this only gets the message there, and it must not do it
 * too early or twice:
 *
 *  - Kept. The address often arrives before the page can take it: a launch from the
 *    card brings it in onCreate, before the page has even loaded. It waits, and is
 *    told when the page is up.
 *  - Not under a cover. While the splash cover is over the page (SplashCover), nobody
 *    is looking at it. Android has no app-switcher cover (FLAG_SECURE blanks the
 *    Recents thumbnail instead), so the splash is the only one.
 *  - Not before the return is told. When Foxy comes back from the background the page
 *    raises its lock on hearing so (`_resumed`), and a card heard of before that would
 *    find no lock to wait behind. After a trip to the background nothing is told
 *    until the return has been ([cameBack], from PutAway after `_resumed`).
 *  - Asked again. A page that has loaded has not booted, and it has no way to say it
 *    is ready: its answer is the only sign, so the question is asked every
 *    [RETRY_AFTER_MS] until the answer is yes. It is given up [LAPSE_MS] after the
 *    latest address arrived, so a phone opened by hand much later is never sent to a card.
 *  - Once. Addresses that arrive while one waits are the same tap, and a yes ends it.
 *    A question is never given up on while its page lives: as on iOS there is no
 *    timer on the answer, so a page slow to answer (blocked at boot) is not asked a
 *    second time and cannot act twice. A page whose process ended never answers;
 *    [pageGone] forgets that question (its answer, if one ever comes, is ignored) and
 *    the reloaded page is asked afresh.
 *
 * The page is told a fixed message ([CardAddress.PAGE_CALL]). Nothing of the address is
 * here, and nothing of it is logged. One per MainActivity (its page); main thread only.
 */
class CardTapDelivery(
    /** The clock, ms; any monotonic one (SystemClock.elapsedRealtime in the app). */
    private val now: () -> Long,
    /** Runs `run` after `ms` on the main thread (a Handler post in the app). */
    private val wait: (ms: Long, run: () -> Unit) -> Unit,
    private val say: (String) -> Unit,
) {
    companion object {
        /** How long after the address arrived a tap is still told. */
        const val LAPSE_MS = 60_000L

        /** How long between one question to the page and the next. */
        const val RETRY_AFTER_MS = 300L

        const val ARRIVED = "a card's address arrived"
        const val TOLD = "told the page"
        const val GIVEN_UP = "given up, the page did not take it within a minute"
    }

    /** Asks the page, and calls back with whether it took the message. MainActivity sets it to the web view's. */
    var tell: ((done: (Boolean) -> Unit) -> Unit)? = null

    /** When the tap that has not been told yet arrived, or null. */
    var since: Long? = null
        private set
    private var asking = false

    /** Which page a question went to: bumped by [pageGone], so a dead page's answer is not heard. */
    private var generation = 0
    private var loaded = false
    private var covered = true
    private var returning = false

    /** An address arrived. Anything but the card's own is dropped without a word. */
    fun received(url: String?) {
        if (!CardAddress.isCard(url)) return
        cardTapped()
    }

    /** A card was tapped. Addresses while one waits are the same tap, and a later one renews how long it waits. */
    fun cardTapped() {
        if (since == null) say(ARRIVED)
        since = now()
        attempt()
    }

    /** The page has finished loading. It has not necessarily booted: the question is asked, and asked again, until it answers yes. */
    fun pageLoaded() {
        loaded = true
        attempt()
    }

    /**
     * The page is gone (its process ended) and is being loaded again. A question still
     * out to it will never be answered, or answered by a page that is no more: it is
     * forgotten, and the reloaded page is asked when it has loaded.
     */
    fun pageGone() {
        loaded = false
        asking = false
        generation++
    }

    /** The splash cover went up or came down. */
    fun coverChanged(up: Boolean) {
        covered = up
        if (!up) attempt()
    }

    /** Foxy went to the background: nothing is told until it is back and the page has been told so. */
    fun wentToBackground() {
        returning = true
    }

    /** Foxy is back and the page has been told it is (`_resumed`). */
    fun cameBack() {
        returning = false
        attempt()
    }

    /** Whether the page may be asked now. */
    val ready: Boolean get() = loaded && !covered && !returning

    private fun attempt() {
        val began = since ?: return
        if (now() - began >= LAPSE_MS) {
            since = null
            say(GIVEN_UP)
            return
        }
        val ask = tell
        if (!ready || asking || ask == null) return
        asking = true
        val asked = generation
        ask { taken ->
            // an answer from a page that has since gone is not this page's
            if (asked != generation) return@ask
            asking = false
            if (since == null) return@ask
            if (taken) {
                since = null
                say(TOLD)
                return@ask
            }
            wait(RETRY_AFTER_MS) { attempt() }
        }
    }
}
