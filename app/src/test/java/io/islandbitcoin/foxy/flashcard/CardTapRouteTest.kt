package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.flashcard.CardTapRoute.Way
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where CardTapActivity sends a card's address (review of PR #15): never a second page.
 * With a seed screen or the scanner over the page (in the page's own task), the tap is
 * dropped, as iOS ignores a tap whenever anything is already up
 * (sandbox/plans/foxy-card-android.md:252-256); with the page on top of its task it is
 * handed to that page; with no page a new one is started.
 */
class CardTapRouteTest {
    private val page = "io.islandbitcoin.foxy.MainActivity"

    @Test
    fun aSeedScreenOrTheScannerOverThePageDropsTheTap() {
        for (over in listOf(
            "io.islandbitcoin.foxy.seed.ShowWordsActivity",
            "io.islandbitcoin.foxy.seed.EnterWordsActivity",
            "io.islandbitcoin.foxy.scanner.ScannerActivity",
            // the screen-lock confirmation and the share sheet start in Foxy's task too
            "com.android.settings.password.ConfirmDeviceCredentialActivity",
            "com.android.internal.app.ChooserActivity",
        )) {
            assertEquals(over, Way.DROPPED, CardTapRoute.of(pageAlive = true, topOfPageTask = over, page = page))
        }
    }

    @Test
    fun thePageOnTopOfItsTaskIsHandedTheTap() {
        assertEquals(Way.DELIVERED, CardTapRoute.of(pageAlive = true, topOfPageTask = page, page = page))
    }

    @Test
    fun withNoPageANewOneIsStarted() {
        assertEquals(Way.LAUNCHED, CardTapRoute.of(pageAlive = false, topOfPageTask = null, page = page))
        assertEquals("what is left of an ended process's task is not a live page", Way.LAUNCHED,
            CardTapRoute.of(pageAlive = false, topOfPageTask = "io.islandbitcoin.foxy.seed.ShowWordsActivity", page = page))
    }

    /** A page alive whose task's top cannot be read: dropped, never a guess that could make a second page. */
    @Test
    fun anUnreadableTopIsDropped() {
        assertEquals(Way.DROPPED, CardTapRoute.of(pageAlive = true, topOfPageTask = null, page = page))
    }

    @Test
    fun theExtraCarriesNothingOfTheAddress() {
        assertEquals("io.islandbitcoin.foxy.CARD_TAPPED", CardTapRoute.EXTRA)
    }
}
