package io.islandbitcoin.foxy.web

import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.flashcard.CardTapDelivery
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.FakeScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENG-668: PutAway tells the card-tap delivery when Foxy is put away, and when the page
 * has been told of the return, after `_resumed` and never before it
 * (FoxyWebView.swift:1001-1002, 1173-1178): a card held to the phone on the way back
 * reaches a page that has already raised its lock.
 */
class PutAwayCardTapTest {
    private val sched = FakeScheduler()
    private val dir = TestSupport.tempDir("putaway-cardtap")
    private val vault = SeedVault(dir, FakeKeys()) { sched.now }

    /** Everything in the order it happened: the page's scripts, and what the delivery heard. */
    private val events = mutableListOf<String>()

    private inner class FakePage : PutAway.Page {
        override var moneyInFlight = 0
        override fun keepAwake(on: Boolean) {}
        override fun evaluate(js: String) {
            if ("_resumed(" in js) events.add("_resumed")
            if ("_putAway(" in js) events.add("_putAway")
        }
        override fun read(js: String, answer: (String?) -> Unit) = answer("false")
        override fun pushUptime() {}
    }

    private val page = FakePage()
    private var confirming = false
    private val away = PutAway(
        { page }, vault, parkTor = {}, torBack = {}, sched = sched, ownPrompt = { confirming },
        wentAway = { events.add("wentAway") }, resumedTold = { events.add("resumedTold") },
    )

    @After
    fun clean() {
        Route.openDoor()
        dir.deleteRecursively()
    }

    @Test
    fun awayIsToldBeforeAnythingAndTheReturnAfterResumed() {
        away.back() // the first start: no return, nothing told
        assertEquals(emptyList<String>(), events)
        away.away()
        assertEquals(listOf("wentAway", "_putAway"), events)
        sched.advance(5_000)
        away.back()
        assertEquals(listOf("wentAway", "_putAway", "_resumed", "resumedTold"), events)
    }

    @Test
    fun aReturnBeforeTheLeaveIsStillAReturn() {
        away.back()
        away.away()
        sched.advance(100)
        away.back()
        assertEquals(listOf("wentAway", "_putAway", "_resumed", "resumedTold"), events)
    }

    /** Foxy's own screen-lock confirmation is no put-away: the delivery hears nothing, and a yes brings nothing back. */
    @Test
    fun foxysOwnConfirmationIsNoTripAway() {
        away.back()
        confirming = true
        away.away()
        confirming = false
        away.back()
        away.promptAnswered(true)
        sched.advance(PutAway.ANSWER_AT_MOST_MS + 1_000)
        assertEquals(emptyList<String>(), events)
    }

    /** A no to it is a return like any other: the page hears `_resumed`, and then the delivery does. */
    @Test
    fun aNoToTheConfirmationIsAReturnToldInOrder() {
        away.back()
        confirming = true
        away.away()
        sched.advance(3_000)
        confirming = false
        away.back()
        away.promptAnswered(false)
        assertEquals(listOf("_resumed", "resumedTold"), events)
    }

    /** End to end with the real delivery: a card held to the phone on the way back waits for `_resumed`, then goes. */
    @Test
    fun aCardOnTheWayBackWaitsForResumed() {
        var asked = 0
        val delivery = CardTapDelivery(now = { sched.now }, wait = { _, _ -> }, say = {})
        delivery.tell = { done ->
            events.add("asked")
            asked++
            done(true)
        }
        delivery.pageLoaded()
        delivery.coverChanged(up = false)
        val withDelivery = PutAway(
            { page }, vault, parkTor = {}, torBack = {}, sched = sched,
            wentAway = delivery::wentToBackground, resumedTold = delivery::cameBack,
        )
        withDelivery.back()
        withDelivery.away()
        delivery.received("https://getfoxy.io/card")
        assertEquals("away: nobody asked", 0, asked)
        sched.advance(2_000)
        withDelivery.back()
        assertEquals(1, asked)
        assertNull(delivery.since)
        assertTrue(events.indexOf("_resumed") < events.indexOf("asked"))
    }
}
