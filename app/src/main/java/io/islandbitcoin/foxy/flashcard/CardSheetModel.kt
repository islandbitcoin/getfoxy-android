package io.islandbitcoin.foxy.flashcard

/**
 * What Foxy's card sheet shows, as iOS's CoreNFC sheet shows it, without a View
 * (CardSheetModelTest holds it): [CardSheetView] draws it.
 *
 * - Looking: "Ready to Scan", the page's line, the phone-and-card mark, Cancel.
 * - The line changes as the page and the link say (`cardSay`, [CardGate.SCANNING]
 *   once a card has connected, [NfcCardLink.ONE_CARD]); the title stays.
 * - Finished: a tick and the page's word (or none), no Cancel, gone after [FINISH_MS].
 * - Failed: the reason in red, no Cancel, gone after [FAIL_MS].
 * - Dismissed: gone at once (Cancel, the wait ran out, the card lost, Foxy put away).
 *
 * The line is always [CardGate.line]'s: one short printable line, whatever was asked.
 */
data class CardSheetModel(
    val title: String,
    val line: String,
    val mark: Mark,
    val cancellable: Boolean,
    /** How long it stays before it goes by itself, ms; null while it waits for the next word. */
    val holdMs: Long?,
) {
    enum class Mark { LOOKING, DONE, FAILED }

    companion object {
        /** iOS's sheet's title, as its system draws it. */
        const val TITLE = "Ready to Scan"
        const val CANCEL = "Cancel"

        /** About as long as iOS shows its tick before the sheet goes. */
        const val FINISH_MS = 1_200L

        /** About as long as iOS shows a reason in red. */
        const val FAIL_MS = 2_400L

        fun looking(text: String) = CardSheetModel(TITLE, CardGate.line(text), Mark.LOOKING, cancellable = true, holdMs = null)
    }

    fun saying(text: String): CardSheetModel = if (mark != Mark.LOOKING) this else copy(line = CardGate.line(text))

    /** A tick, and the page's word in place of the line when it gave one. */
    fun finished(text: String): CardSheetModel {
        val word = CardGate.line(text)
        return copy(line = word.ifEmpty { line }, mark = Mark.DONE, cancellable = false, holdMs = FINISH_MS)
    }

    fun failed(text: String): CardSheetModel =
        copy(line = CardGate.line(text), mark = Mark.FAILED, cancellable = false, holdMs = FAIL_MS)
}
