package io.islandbitcoin.foxy.flashcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card's address on its way to the page: kept until the page can take it, told once
 * (CardTapDelivery.kt): getfoxy/iOS `FoxyTests/CardTapDeliveryTests.swift` at 8df0bb1,
 * case for case, on a clock and a timer the test runs by hand.
 */
class CardTapDeliveryTest {
    /** A delivery with a clock, a timer and a page of its own, all run by hand. */
    private class Rig {
        var clock = 1_000_000_000L

        /** What the page says to each question, in order; the last answer is repeated. */
        var answers = listOf(true)

        /** Questions held back, for a page that is slow to answer ([holdAnswers]). */
        val held = mutableListOf<(Boolean) -> Unit>()
        var holdAnswers = false
        var asked = 0
        var timers = mutableListOf<() -> Unit>()
        val waits = mutableListOf<Long>()
        val lines = mutableListOf<String>()

        val delivery = CardTapDelivery(
            now = { clock },
            wait = { ms, run ->
                waits.add(ms)
                timers.add(run)
            },
            say = { lines.add(it) },
        )

        init {
            delivery.tell = { done ->
                val answer = answers[minOf(asked, answers.size - 1)]
                asked++
                if (holdAnswers) held.add(done) else done(answer)
            }
        }

        /** The page is loaded, nothing is over it, and Foxy never left. */
        fun open() {
            delivery.pageLoaded()
            delivery.coverChanged(up = false)
        }

        /** Every timer that is waiting, once. */
        fun fireTimers() {
            val due = timers
            timers = mutableListOf()
            due.forEach { it() }
        }

        fun advance(seconds: Double) {
            clock += (seconds * 1000).toLong()
        }
    }

    private val card = "https://getfoxy.io/card"

    /** The page is up and the card is tapped: it is told, at once, and once. */
    @Test
    fun toldAtOnceWhenThePageIsReady() {
        val rig = Rig()
        rig.open()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
        assertNull("a yes ends it", rig.delivery.since)
        assertEquals(listOf("a card's address arrived", "told the page"), rig.lines)
        assertTrue("nothing is left to ask again", rig.timers.isEmpty())
    }

    /** A launch from the card: the address comes before the page is loaded or uncovered, and waits. */
    @Test
    fun keptUntilThePageIsReadyThenToldOnce() {
        val rig = Rig()
        assertFalse(rig.delivery.ready)
        rig.delivery.received(card)
        assertEquals("not loaded, and the splash is up", 0, rig.asked)
        assertNotNull("kept", rig.delivery.since)
        rig.delivery.pageLoaded()
        assertEquals("loaded, but the splash is still up", 0, rig.asked)
        rig.delivery.coverChanged(up = false)
        assertEquals("told when the last thing in the way went", 1, rig.asked)
        assertNull(rig.delivery.since)
        // and never again for the same tap, whatever else happens
        rig.delivery.pageLoaded()
        rig.delivery.coverChanged(up = false)
        rig.delivery.cameBack()
        rig.fireTimers()
        assertEquals(1, rig.asked)
    }

    /** The cover may come down before the page has loaded: the tap waits for both, in either order. */
    @Test
    fun keptUntilLoadedWhenTheCoverGoesFirst() {
        val rig = Rig()
        rig.delivery.received(card)
        rig.delivery.coverChanged(up = false)
        assertEquals("uncovered, not loaded", 0, rig.asked)
        rig.delivery.pageLoaded()
        assertEquals(1, rig.asked)
        assertNull(rig.delivery.since)
    }

    /** A card held twice, or one address handed over twice: one tap, one message. */
    @Test
    fun addressesWhileOneWaitsAreTheSameTap() {
        val rig = Rig()
        rig.delivery.received(card)
        rig.delivery.received(card)
        rig.delivery.received("https://getfoxy.io/card/?again=1")
        assertEquals("said once", listOf("a card's address arrived"), rig.lines)
        rig.open()
        assertEquals(1, rig.asked)
        assertNull(rig.delivery.since)
    }

    /** Once told, the next address is a tap of its own, and the page rules on that one too. */
    @Test
    fun anotherTapAfterBeingToldIsAnotherTap() {
        val rig = Rig()
        rig.open()
        rig.delivery.received(card)
        rig.delivery.received(card)
        assertEquals(2, rig.asked)
    }

    /** The page has loaded and not booted: its answer is the only sign, so it is asked until it says yes, every 0.3 s. */
    @Test
    fun askedAgainUntilThePageSaysYes() {
        val rig = Rig()
        rig.answers = listOf(false, false, true)
        rig.open()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
        assertEquals("one question to come", 1, rig.timers.size)
        assertNotNull(rig.delivery.since)
        rig.fireTimers()
        assertEquals(2, rig.asked)
        assertEquals(1, rig.timers.size)
        rig.fireTimers()
        assertEquals(3, rig.asked)
        assertNull(rig.delivery.since)
        assertTrue("a yes leaves no timer", rig.timers.isEmpty())
        rig.fireTimers()
        assertEquals("and nothing asks again", 3, rig.asked)
        assertEquals("each question 0.3 s after the last answer", listOf(300L, 300L), rig.waits)
    }

    /** A page that never says yes is asked for a minute, and then given up on. */
    @Test
    fun givenUpAMinuteAfterTheAddressArrived() {
        val rig = Rig()
        rig.answers = listOf(false)
        rig.open()
        rig.delivery.received(card)
        rig.advance(59.0)
        rig.fireTimers()
        assertEquals("still asked at 59 seconds", 2, rig.asked)
        assertNotNull(rig.delivery.since)
        rig.advance(2.0)
        rig.fireTimers()
        assertEquals("not at 61", 2, rig.asked)
        assertNull(rig.delivery.since)
        assertTrue("nothing keeps asking", rig.timers.isEmpty())
        assertEquals("given up, the page did not take it within a minute", rig.lines.last())
        rig.fireTimers()
        rig.delivery.pageLoaded()
        assertEquals(2, rig.asked)
    }

    /** The minute is exact: 59.999 s is still asked, 60 s is given up. */
    @Test
    fun theMinuteIsSixtySecondsToTheMillisecond() {
        val rig = Rig()
        rig.answers = listOf(false)
        rig.open()
        rig.delivery.received(card)
        rig.clock += CardTapDelivery.LAPSE_MS - 1
        rig.fireTimers()
        assertEquals(2, rig.asked)
        rig.clock += 1
        rig.fireTimers()
        assertEquals(2, rig.asked)
        assertNull(rig.delivery.since)
    }

    /** An address that waited under a cover for more than a minute is not acted on when the cover comes down. */
    @Test
    fun anOldAddressIsNeverToldWhenTheCoverGoesLater() {
        val rig = Rig()
        rig.delivery.pageLoaded()
        rig.delivery.received(card)
        rig.advance(300.0)
        rig.delivery.coverChanged(up = false)
        assertEquals(0, rig.asked)
        assertNull(rig.delivery.since)
        assertEquals("given up, the page did not take it within a minute", rig.lines.last())
    }

    /** A tap renews the wait: the minute is counted from the latest address. */
    @Test
    fun aLaterAddressRenewsTheWait() {
        val rig = Rig()
        rig.answers = listOf(false)
        rig.open()
        rig.delivery.received(card)
        rig.advance(50.0)
        rig.delivery.received(card)
        rig.advance(50.0)
        rig.fireTimers()
        assertNotNull("100 seconds after the first, 50 after the second", rig.delivery.since)
        rig.advance(11.0)
        rig.fireTimers()
        assertNull(rig.delivery.since)
    }

    /** The page raises its lock when it is told Foxy is back, so a card is never told before that. */
    @Test
    fun afterABackgroundNothingIsToldUntilTheReturnIs() {
        val rig = Rig()
        rig.open()
        rig.delivery.wentToBackground()
        assertFalse(rig.delivery.ready)
        rig.delivery.received(card)
        rig.delivery.coverChanged(up = true)
        rig.delivery.coverChanged(up = false)
        rig.delivery.pageLoaded()
        assertEquals("back and uncovered, but the page has not been told it is back", 0, rig.asked)
        rig.delivery.cameBack()
        assertEquals(1, rig.asked)
        assertNull(rig.delivery.since)
    }

    /** The address that comes with a return can arrive before the return is told, or after it. */
    @Test
    fun anAddressAfterTheReturnIsToldAtOnce() {
        val rig = Rig()
        rig.open()
        rig.delivery.wentToBackground()
        rig.delivery.cameBack()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
    }

    /** The splash is a cover, and up again holds the tap until it is down. */
    @Test
    fun nothingIsToldUnderACover() {
        val rig = Rig()
        rig.open()
        rig.delivery.coverChanged(up = true)
        rig.delivery.received(card)
        assertEquals(0, rig.asked)
        rig.delivery.coverChanged(up = true)
        assertEquals("up again changes nothing", 0, rig.asked)
        rig.delivery.coverChanged(up = false)
        assertEquals(1, rig.asked)
    }

    /** A page whose process ended is being loaded again; it is not asked until it has loaded. */
    @Test
    fun aPageThatWentIsNotAskedUntilItIsBack() {
        val rig = Rig()
        rig.open()
        rig.delivery.pageGone()
        assertFalse(rig.delivery.ready)
        rig.delivery.received(card)
        assertEquals(0, rig.asked)
        rig.delivery.pageLoaded()
        assertEquals(1, rig.asked)
    }

    /** A page that went while a no was being retried: the retry asks nothing until the reload has finished. */
    @Test
    fun aRetryWhileThePageIsGoneWaitsForTheReload() {
        val rig = Rig()
        rig.answers = listOf(false, true)
        rig.open()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
        rig.delivery.pageGone()
        rig.fireTimers()
        assertEquals("the page is gone: nothing asked", 1, rig.asked)
        assertTrue("and no timer left running", rig.timers.isEmpty())
        assertNotNull("still kept", rig.delivery.since)
        rig.advance(5.0)
        rig.delivery.pageLoaded()
        assertEquals(2, rig.asked)
        assertNull(rig.delivery.since)
    }

    /** A page that is slow to answer is not asked a second time while it thinks. */
    @Test
    fun aQuestionIsNotAskedTwiceWhileItIsOut() {
        val rig = Rig()
        rig.holdAnswers = true
        rig.open()
        rig.delivery.received(card)
        rig.delivery.pageLoaded()
        rig.delivery.coverChanged(up = false)
        rig.delivery.cameBack()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
        rig.held.removeAt(0)(true)
        assertNull("the second address was the first, said again", rig.delivery.since)
        assertEquals(1, rig.asked)
        assertTrue(rig.timers.isEmpty())
    }

    /**
     * A question out to a page whose process then ended (review of PR #15): that page never
     * answers, so the question is forgotten, and the reloaded page is asked exactly once more.
     * The dead page's answer, if it ever comes, is not heard.
     */
    @Test
    fun aQuestionOutAcrossAPageGoneIsAskedOnceMoreOfTheNewPage() {
        val rig = Rig()
        rig.holdAnswers = true
        rig.open()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
        rig.delivery.pageGone()
        rig.delivery.cameBack()
        rig.delivery.coverChanged(up = false)
        assertEquals("not asked while the page is reloading", 1, rig.asked)
        rig.delivery.pageLoaded()
        assertEquals("the new page is asked, once", 2, rig.asked)
        rig.delivery.cameBack()
        assertEquals("and not again while that question is out", 2, rig.asked)
        // the dead page's answer comes after all: not heard, whatever it says
        rig.held.removeAt(0)(false)
        assertTrue("no retry from a dead page's no", rig.timers.isEmpty())
        assertNotNull(rig.delivery.since)
        rig.held.removeAt(0)(true)
        assertNull("the new page's yes ends it", rig.delivery.since)
        assertEquals(2, rig.asked)
        assertEquals(1, rig.lines.count { it == CardTapDelivery.TOLD })
    }

    /** A dead page's yes is not the new page's: the new page is still asked, and its own yes ends it. */
    @Test
    fun aDeadPagesLateYesIsNotHeard() {
        val rig = Rig()
        rig.holdAnswers = true
        rig.open()
        rig.delivery.received(card)
        rig.delivery.pageGone()
        rig.held.removeAt(0)(true)
        assertNotNull("the page that answered is gone", rig.delivery.since)
        rig.delivery.pageLoaded()
        assertEquals(2, rig.asked)
        rig.held.removeAt(0)(true)
        assertNull(rig.delivery.since)
    }

    /**
     * A page slow to answer (its JS blocked at boot for seconds) is never taken to have said
     * no: there is no timer on the answer, as on iOS, so `_cardTapped()` is never queued a
     * second time behind the first, and a slow yes ends it with one question asked.
     */
    @Test
    fun aSlowYesNeverLeadsToASecondCall() {
        val rig = Rig()
        rig.holdAnswers = true
        rig.open()
        rig.delivery.received(card)
        assertEquals(1, rig.asked)
        assertTrue("no timer runs on the answer", rig.timers.isEmpty())
        rig.advance(5.0)
        rig.fireTimers()
        rig.delivery.cameBack()
        rig.delivery.coverChanged(up = false)
        rig.delivery.pageLoaded()
        assertEquals("still one question, however long it takes", 1, rig.asked)
        rig.held.removeAt(0)(true)
        assertNull(rig.delivery.since)
        assertEquals(1, rig.asked)
        assertTrue(rig.timers.isEmpty())
    }

    /** An answer that arrives after the tap was given up on starts nothing. */
    @Test
    fun aLateNoAfterTheGiveUpStartsNothing() {
        val rig = Rig()
        rig.holdAnswers = true
        rig.open()
        rig.delivery.received(card)
        rig.advance(120.0)
        rig.delivery.cameBack()
        assertNull("given up", rig.delivery.since)
        rig.held.removeAt(0)(false)
        assertTrue("no timer for a tap that is over", rig.timers.isEmpty())
        assertEquals(1, rig.asked)
    }

    /** No page to ask yet (the activity has not set `tell`): kept, and asked once it is set and something changes. */
    @Test
    fun withNoOneToAskTheTapIsKept() {
        val rig = Rig()
        val tell = rig.delivery.tell
        rig.delivery.tell = null
        rig.open()
        rig.delivery.received(card)
        assertEquals(0, rig.asked)
        assertNotNull(rig.delivery.since)
        rig.delivery.tell = tell
        rig.delivery.pageLoaded()
        assertEquals(1, rig.asked)
    }

    /** iOS's own numbers: a minute, and a third of a second. */
    @Test
    fun theTimesAreIOSs() {
        assertEquals(60_000L, CardTapDelivery.LAPSE_MS)
        assertEquals(300L, CardTapDelivery.RETRY_AFTER_MS)
        assertTrue(CardTapDelivery.RETRY_AFTER_MS in 1 until 1_000)
    }
}
