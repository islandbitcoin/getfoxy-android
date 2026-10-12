package io.islandbitcoin.foxy.web

import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.FakeScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Putting Foxy away, on a clock the test moves (S21; docs/ROUTE.md "The door
 * and backgrounding": Tor is parked when the work is done or after 20 s,
 * whichever first). The leave step parks Tor through the injected call; a page
 * whose JS never answers `_tidying` cannot hold Tor on the network past the
 * ceiling; a return before the leave parks nothing and sets Tor up again.
 */
class PutAwayLeavingTest {
    private val sched = FakeScheduler()
    private val dir = TestSupport.tempDir("putaway")
    private val vault = SeedVault(dir, FakeKeys()) { sched.now }
    private var parks = 0
    private val backs = mutableListOf<Long>()

    /** The page: answers `_tidying` as told, or never (a WebView whose JS is throttled). */
    private class FakePage : PutAway.Page {
        var answers = true
        var tidying = false
        override var moneyInFlight = 0
        val evaluated = mutableListOf<String>()
        var reads = 0
        override fun keepAwake(on: Boolean) {}
        override fun evaluate(js: String) {
            evaluated.add(js)
        }
        override fun read(js: String, answer: (String?) -> Unit) {
            reads++
            if (answers) answer(if (tidying) "true" else "false")
        }
        override fun pushUptime() {}
    }

    private val page = FakePage()
    private val away = PutAway({ page }, vault, parkTor = { parks++ }, torBack = { backs.add(it) }, sched = sched)

    @After
    fun clean() {
        Route.openDoor()
        dir.deleteRecursively()
    }

    @Test
    fun theLeaveStepShutsTheDoorAndParksTor() {
        away.back() // the first start: nothing to come back from
        away.away()
        assertTrue(page.evaluated.any { it.contains("_putAway(true)") })
        sched.advance(PutAway.FIRST_LOOK_MS)
        assertEquals("asked once, at 0.4 s", 1, page.reads)
        assertTrue("the door shut as the leave began", Route.shut())
        assertEquals("Tor is parked a moment later, not at once", 0, parks)
        sched.advance(500)
        assertEquals("parked through the injected call", 1, parks)
        sched.advance(PutAway.LEAVE_AT_MOST_MS)
        assertEquals("parked once: the watchdog does not leave twice", 1, parks)
    }

    @Test
    fun aPageThatNeverAnswersIsNotTidyingAndTorIsParked() {
        page.answers = false
        away.away()
        sched.advance(PutAway.FIRST_LOOK_MS + PutAway.READ_AT_MOST_MS - 1)
        assertEquals("not yet: the read has a second", 0, parks)
        sched.advance(1 + 500)
        assertEquals("the unanswered read counted as not tidying, and Tor parked", 1, parks)
    }

    @Test
    fun aPageThatNeverAnswersWithMoneyOutParksTorByTheCeiling() {
        page.answers = false
        page.moneyInFlight = 1
        away.away()
        sched.advance(Route.WAIT_FOR_MONEY_MS)
        assertEquals("a melt out: waited for, up to 20 s", 0, parks)
        sched.advance(PutAway.LEAVE_AT_MOST_MS - Route.WAIT_FOR_MONEY_MS)
        assertEquals("by 21 s Tor is parked although the page never answered", 1, parks)
        assertTrue(Route.shut())
    }

    @Test
    fun theTidyingPageIsWaitedForAndThenLeftAt20Seconds() {
        page.tidying = true
        away.away()
        sched.advance(Route.WAIT_FOR_MONEY_MS)
        assertEquals(0, parks)
        sched.advance(PutAway.LEAVE_AT_MOST_MS - Route.WAIT_FOR_MONEY_MS)
        assertEquals(1, parks)
    }

    @Test
    fun aReturnBeforeTheLeaveParksNothingAndSetsTorUp() {
        away.back()
        page.tidying = true
        away.away()
        sched.advance(5_000)
        away.back()
        assertEquals(listOf(5L), backs)
        assertTrue("the door is open again", !Route.shut())
        sched.advance(60_000)
        assertEquals("no park after the return, from a look or from the watchdog", 0, parks)
    }
}
