package io.islandbitcoin.foxy.flashcard

/**
 * DEBUG BUILDS ONLY: the card simulator's link ([SimCardLink]), which WebHost
 * uses in place of the radio on a device with no NFC (the emulator), or when
 * MainActivity was started with the `FoxyCardSim` extra. The release source
 * set's CardSim makes none, so a release build has no socket outside network/.
 */
object CardSim {
    const val ENABLED = true

    fun link(main: CardMain): CardLink = SimCardLink(main)
}
