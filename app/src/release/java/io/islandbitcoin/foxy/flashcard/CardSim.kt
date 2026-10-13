package io.islandbitcoin.foxy.flashcard

/** Release builds have no card simulator: a card is reached through the radio or not at all. */
object CardSim {
    const val ENABLED = false

    @Suppress("UNUSED_PARAMETER")
    fun link(main: CardMain): CardLink? = null
}
