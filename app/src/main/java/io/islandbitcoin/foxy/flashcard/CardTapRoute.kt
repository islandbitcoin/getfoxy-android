package io.islandbitcoin.foxy.flashcard

/**
 * Where a card's address goes once CardTapActivity has it (ENG-668, review of PR #15).
 *
 * The address never starts MainActivity by itself. A singleTop MainActivity is reused
 * only while it is the top of its task; with the scanner or a seed screen over it (both
 * started in the same task), Android would make a second MainActivity: a second page on
 * the same storage, a second PutAway, Tor installed again, and its splash over the seed
 * words. iOS ignores the tap whenever anything is already up
 * (sandbox/plans/foxy-card-android.md:252-256). So the decision is made here, before any
 * activity is started:
 *
 *  - [Way.LAUNCHED]: no page is alive. MainActivity is started with [EXTRA], and the
 *    page is told when it can take it (CardTapDelivery).
 *  - [Way.DELIVERED]: a page is alive and its activity is the top of its task (in front,
 *    or put away with nothing over it). It is brought back and handed [EXTRA]
 *    (onNewIntent): the same page, never a second one.
 *  - [Way.DROPPED]: a page is alive and something is over it in its task (the scanner,
 *    a seed screen, the screen-lock confirmation, the share sheet), or what is on top
 *    cannot be known. The tap is dropped and nothing is started, as iOS ignores it.
 *
 * Pure: the Android side (CardTapActivity) reads the live page and its task's top
 * activity and acts on the answer.
 */
object CardTapRoute {
    /** MainActivity's one-shot extra: a card was tapped. Nothing of the address rides with it. */
    const val EXTRA = "io.islandbitcoin.foxy.CARD_TAPPED"

    enum class Way { LAUNCHED, DELIVERED, DROPPED }

    /**
     * [pageAlive]: a MainActivity exists in this process and is not finishing.
     * [topOfPageTask]: the class name of the activity on top of that MainActivity's task,
     * or null when it cannot be read. [page]: MainActivity's class name.
     */
    fun of(pageAlive: Boolean, topOfPageTask: String?, page: String): Way = when {
        !pageAlive -> Way.LAUNCHED
        topOfPageTask == page -> Way.DELIVERED
        else -> Way.DROPPED
    }
}
