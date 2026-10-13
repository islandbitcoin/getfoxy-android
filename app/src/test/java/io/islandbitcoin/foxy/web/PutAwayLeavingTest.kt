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
        var letGo = 0
        override fun letGo() {
            letGo++
        }
        /** Each return told to the page's side (ENG-672: the onion inbox may make an address again). */
        var cameBack = 0
        override fun cameBack() {
            cameBack++
        }
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
    private var confirming = false
    private val away = PutAway({ page }, vault, parkTor = { parks++ }, torBack = { backs.add(it) }, sched = sched, ownPrompt = { confirming })

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
        sched.advance(Route.WAIT_FOR_MONEY_MS + 1_000)
        assertEquals("parked once: nothing leaves twice", 1, parks)
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
        sched.advance(1_000)
        assertEquals("by 21 s Tor is parked although the page never answered", 1, parks)
        assertTrue(Route.shut())
    }

    /**
     * What holds the ceiling when the page never answers: the 1 s read timeout,
     * a main-looper post like the looks, so a look is decided every 1.25 s
     * (0.4 s + 1 s, then every 1 s + 0.25 s) and the first past 20 s, at
     * 20.15 s, leaves at once. Not a watchdog: there is none.
     */
    @Test
    fun theReadTimeoutAloneHoldsTheCeilingAt20SecondsAndAQuarter() {
        page.answers = false
        page.moneyInFlight = 1
        away.away()
        val lastLook = PutAway.FIRST_LOOK_MS + PutAway.READ_AT_MOST_MS +
            ((Route.WAIT_FOR_MONEY_MS - PutAway.FIRST_LOOK_MS - PutAway.READ_AT_MOST_MS) / (PutAway.READ_AT_MOST_MS + PutAway.LOOK_EVERY_MS) + 1) *
            (PutAway.READ_AT_MOST_MS + PutAway.LOOK_EVERY_MS)
        assertEquals(20_150L, lastLook)
        sched.advance(lastLook - 1)
        assertEquals("the look before 20 s still waits for the melt", 0, parks)
        val reads = page.reads
        sched.advance(1)
        assertEquals("the first look past 20 s leaves and parks at once", 1, parks)
        assertTrue(Route.shut())
        sched.advance(60_000)
        assertEquals("no look after the leave", reads, page.reads)
        assertEquals(1, parks)
    }

    @Test
    fun theTidyingPageIsWaitedForAndThenLeftAt20Seconds() {
        page.tidying = true
        away.away()
        sched.advance(Route.WAIT_FOR_MONEY_MS)
        assertEquals(0, parks)
        sched.advance(1_000)
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
        assertEquals("no park after the return, from a look or a read timeout", 0, parks)
    }

    /**
     * API 28-29: Foxy's own screen-lock confirmation is Settings' activity over
     * Foxy, which stops it (DeviceAuth.confirming). That stop is not a put-away:
     * the vault's epoch is not moved (a seed read under the prompt is kept), the
     * page's screens are not let go (SeedBridge.closeScreens would bump the
     * screen generation, and a seedShow would answer verified:false without
     * opening), the door stays open and nothing comes back on the return.
     */
    @Test
    fun aStopUnderFoxysOwnConfirmationPutsNothingAway() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        assertEquals("the seed being read under the prompt is still kept after", epoch, vault.epochNow)
        assertEquals("no screen is let go: the screen generation does not move", 0, page.letGo)
        assertTrue("no _putAway pushed", page.evaluated.none { it.contains("_putAway") })
        sched.advance(30_000)
        assertTrue("the door stays open", !Route.shut())
        assertEquals(0, parks)
        assertEquals("no _tidying look", 0, page.reads)
        confirming = false
        away.back()
        // the result comes after ProcessLifecycleOwner's start: the screen lock was given
        away.promptAnswered(true)
        sched.advance(PutAway.ANSWER_AT_MOST_MS + 1_000)
        assertEquals("nothing was put away, so nothing comes back", emptyList<Long>(), backs)
        assertTrue(page.evaluated.none { it.contains("_resumed") })
        assertEquals("a yes keeps the seed read under it", epoch, vault.epochNow)
        // a real put-away afterwards is one
        away.away()
        assertEquals(epoch + 1, vault.epochNow)
        assertEquals(1, page.letGo)
    }

    @Test
    fun aConfirmationLeftUpForTwoMinutesIsAPutAwayAfterAll() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        sched.advance(PutAway.PROMPT_AT_MOST_MS - 1)
        assertEquals(epoch, vault.epochNow)
        sched.advance(1)
        assertEquals("the person left from the prompt: put away", epoch + 1, vault.epochNow)
        assertEquals(1, page.letGo)
        sched.advance(PutAway.FIRST_LOOK_MS + 1_000)
        assertEquals(1, parks)
        confirming = false
        away.back()
        assertEquals("a return after it is a return", 1, backs.size)
    }

    /**
     * The hole a skipped put-away opened: the owner leaves from Foxy's own
     * confirmation (Home), someone opens Foxy from Recents within the 120 s and
     * presses Back on the confirmation. That cancel proves nothing, so it is a
     * return like any other: what the stop skipped is put away (the vault's epoch
     * moves, the screens are let go) and `_resumed(secondsAway)` is pushed, which
     * PIN-locks the page for an absence of a second or more (iOS index.html
     * watchWake; docs/BRIDGE.md "Coming back").
     */
    @Test
    fun aConfirmationAnsweredNoAfterTimeAwayIsAReturnAndThePageLocks() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        sched.advance(30_000)
        confirming = false
        away.back()
        assertTrue("nothing is decided before the answer", page.evaluated.none { it.contains("_resumed") })
        assertEquals(epoch, vault.epochNow)
        away.promptAnswered(false)
        assertEquals("the put-away the stop skipped is run: the seed kept for the visit goes", epoch + 1, vault.epochNow)
        assertEquals("the screens are let go", 1, page.letGo)
        val resumed = page.evaluated.filter { it.contains("_resumed(") }
        assertEquals(listOf("window.FoxyWallet && window.FoxyWallet._resumed(30)"), resumed)
        assertTrue("the door is open", !Route.shut())
        assertEquals("Tor was never parked for it, so it is not set up again", emptyList<Long>(), backs)
        sched.advance(PutAway.PROMPT_AT_MOST_MS)
        assertEquals("settled once", 1, page.evaluated.count { it.contains("_resumed(") })
        assertEquals(0, parks)
    }

    @Test
    fun aNoThatComesWhileStillStoppedIsKeptForTheReturn() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        sched.advance(5_000)
        confirming = false
        away.promptAnswered(false)
        assertEquals("still away: nothing yet", epoch, vault.epochNow)
        away.back()
        assertEquals(epoch + 1, vault.epochNow)
        assertTrue(page.evaluated.any { it.contains("_resumed(5)") })
    }

    @Test
    fun aYesThatComesWhileStillStoppedPutsNothingAway() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        sched.advance(5_000)
        confirming = false
        away.promptAnswered(true)
        away.back()
        sched.advance(PutAway.ANSWER_AT_MOST_MS + 1_000)
        assertEquals(epoch, vault.epochNow)
        assertTrue(page.evaluated.none { it.contains("_resumed") })
    }

    @Test
    fun noAnswerAfterTheReturnIsTakenAsNo() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        sched.advance(3_000)
        away.back()
        sched.advance(PutAway.ANSWER_AT_MOST_MS - 1)
        assertEquals(epoch, vault.epochNow)
        sched.advance(1)
        assertEquals("no answer in time: a no", epoch + 1, vault.epochNow)
        assertTrue(page.evaluated.any { it.contains("_resumed(5)") })
        // an answer arriving late changes nothing
        away.promptAnswered(true)
        assertEquals(1, page.evaluated.count { it.contains("_resumed(") })
    }

    @Test
    fun leavingAgainBeforeTheAnswerIsAPutAway() {
        away.back()
        val epoch = vault.epochNow
        confirming = true
        away.away()
        sched.advance(4_000)
        away.back()
        confirming = false
        away.away()
        assertEquals(epoch + 1, vault.epochNow)
        assertTrue(page.evaluated.any { it.contains("_putAway(true)") })
        sched.advance(1_000)
        away.back()
        assertEquals("the seconds away count from the first stop", listOf(5L), backs)
        assertTrue(page.evaluated.any { it.contains("_resumed(5)") })
    }

    /**
     * ENG-672: the onion inbox's addresses go with [PutAway.Page.letGo] as Foxy is
     * put away, before Tor parks, and a return is told (`cameBack`) before
     * `_resumed`, so an address is made again only once Foxy is back; a stop
     * under Foxy's own confirmation that is given tells neither.
     */
    @Test
    fun aPutAwayLetsGoBeforeTorParksAndAReturnSaysItCameBackBeforeResumed() {
        away.back()
        assertEquals("the first start is no return", 0, page.cameBack)
        away.away()
        assertEquals("let go at once, as the leave begins", 1, page.letGo)
        assertEquals("Tor not parked yet", 0, parks)
        sched.advance(PutAway.FIRST_LOOK_MS + 1_000)
        assertEquals(1, parks)
        assertEquals("nothing comes back while away", 0, page.cameBack)
        away.back()
        assertEquals(1, page.cameBack)
        assertTrue(page.evaluated.last().contains("_resumed("))
        // a stop under the confirmation, given: nothing let go, nothing come back
        confirming = true
        away.away()
        confirming = false
        away.back()
        away.promptAnswered(true)
        assertEquals(1, page.letGo)
        assertEquals(1, page.cameBack)
        // and one not given is a return: let go, then come back
        confirming = true
        away.away()
        confirming = false
        away.back()
        away.promptAnswered(false)
        assertEquals(2, page.letGo)
        assertEquals(2, page.cameBack)
    }
}
