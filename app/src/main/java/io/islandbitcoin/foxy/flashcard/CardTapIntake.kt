package io.islandbitcoin.foxy.flashcard

/**
 * Where a card's address comes in on Android, before [CardTapDelivery] (ENG-668). iOS
 * has one way in (a link, `onOpenURL`); Android has two, both to MainActivity, in
 * onCreate or, with Foxy already up (singleTop), onNewIntent:
 *
 *  - [NDEF_DISCOVERED]: Android's own tag dispatch read the card's NDEF record with the
 *    screen on and unlocked and found MainActivity's filter for exactly
 *    `https://getfoxy.io/card`. The direct counterpart of iOS's background tag read.
 *  - [VIEW]: the same address as a link, from a browser or an NFC stack that offers
 *    the URI as a link (the App Link filter; not verified until getfoxy.io publishes
 *    an assetlinks.json with the release key, docs/DEVICE-TESTS.md).
 *
 * Each is held to [CardAddress.isCard], and nothing of the address goes further: the
 * delivery is told only that a card was tapped. An intent replayed rather than sent
 * (Foxy opened again from Recents, which hands back the intent that first started it,
 * or an activity restored from saved state) is not a tap, and is dropped.
 *
 * Android only, no iOS counterpart: a card being read is never a tap. While a card
 * session is open (CardBridge; reader mode, ReaderModeRadio) Android sends no tag to
 * any dispatch, but an address dispatched just before the session began can still
 * arrive, and when reader mode goes off at the session's end Android finds the card
 * still resting on the phone and dispatches its address again. Both are dropped: an
 * address while a session is open, and one within [QUIET_AFTER_READ_MS] of reader
 * mode going off ([readerOff]). So a card being read, or just read, never re-opens
 * the Flashcard screen. Main thread.
 */
class CardTapIntake(
    private val delivery: CardTapDelivery,
    private val now: () -> Long,
    /** Whether the page has a card session open (FoxyBridge.cardSessionOpen). */
    private val cardSession: () -> Boolean,
    private val say: (String) -> Unit,
) {
    companion object {
        const val NDEF_DISCOVERED = "android.nfc.action.NDEF_DISCOVERED"
        const val VIEW = "android.intent.action.VIEW"

        /** After reader mode goes off, a card still on the phone is re-dispatched at once; for this long it is the card just read. */
        const val QUIET_AFTER_READ_MS = 3_000L

        const val SUPPRESSED = "a card's address while a card is being read: not a tap"
    }

    private var readerOffAt: Long? = null

    /** Reader mode went off (a card session ended, ReaderModeRadio.stop). */
    fun readerOff() {
        readerOffAt = now()
    }

    /** Whether an address now is a card being read, or just read, and not a tap. */
    val suppressed: Boolean
        get() = cardSession() || readerOffAt?.let { now() - it < QUIET_AFTER_READ_MS } == true

    /**
     * An intent MainActivity was started with or handed: its action, its data as the
     * string it was handed over as (`Intent.getDataString`), and whether it is
     * replayed (launched from history, or the activity restored).
     */
    fun intent(action: String?, data: String?, replayed: Boolean) {
        if (action != NDEF_DISCOVERED && action != VIEW) return
        if (replayed || !CardAddress.isCard(data)) return
        if (suppressed) {
            say(SUPPRESSED)
            return
        }
        delivery.cardTapped()
    }
}
