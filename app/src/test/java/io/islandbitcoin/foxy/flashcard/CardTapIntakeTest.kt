package io.islandbitcoin.foxy.flashcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a card's address comes in on Android (CardTapIntake.kt, ENG-668): the two
 * actions MainActivity's filters take, the exact address and nothing else, an intent
 * replayed from Recents is no tap, and a card being read (or just read, still resting
 * on the phone as reader mode goes off) never re-opens the Flashcard screen.
 */
class CardTapIntakeTest {
    private var clock = 5_000_000L
    private var session = false
    private val lines = mutableListOf<String>()
    private var asked = 0
    private val delivery = CardTapDelivery(now = { clock }, wait = { _, _ -> }, say = { lines.add(it) }).apply {
        tell = { done ->
            asked++
            done(true)
        }
        pageLoaded()
        coverChanged(up = false)
    }
    private val intake = CardTapIntake(delivery, now = { clock }, cardSession = { session }, say = { lines.add(it) })

    private val card = "https://getfoxy.io/card"
    private val ndef = CardTapIntake.NDEF_DISCOVERED
    private val view = CardTapIntake.VIEW

    @Test
    fun theActionsAreAndroidsOwn() {
        assertEquals(android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED, ndef)
        assertEquals(android.content.Intent.ACTION_VIEW, view)
    }

    /** From the tag dispatch or as a link, the card's address is a tap, told to the page with nothing of it. */
    @Test
    fun theCardsAddressFromTheTagOrALinkIsATap() {
        intake.intent(ndef, card, replayed = false)
        assertEquals(1, asked)
        intake.intent(view, "https://getfoxy.io/card/?from=browser", replayed = false)
        assertEquals(2, asked)
        assertFalse("the address is not logged", lines.any { "getfoxy" in it })
    }

    /** Any other action, the launcher's included, or no address, is no tap, and says nothing. */
    @Test
    fun anythingElseIsNoTap() {
        for (action in listOf(null, "android.intent.action.MAIN", "android.nfc.action.TECH_DISCOVERED", "android.nfc.action.TAG_DISCOVERED", "android.intent.action.SEND")) {
            intake.intent(action, card, replayed = false)
        }
        for (data in listOf(null, "", "https://www.getfoxy.io/card", "foxy://card", "https://getfoxy.io/cards", "http://getfoxy.io/card")) {
            intake.intent(ndef, data, replayed = false)
            intake.intent(view, data, replayed = false)
        }
        assertEquals(0, asked)
        assertNull(delivery.since)
        assertTrue("dropped without a word: $lines", lines.isEmpty())
    }

    /** Foxy opened again from Recents hands back the intent that first started it: that is no new tap. */
    @Test
    fun aReplayedIntentIsNoTap() {
        intake.intent(ndef, card, replayed = true)
        intake.intent(view, card, replayed = true)
        assertEquals(0, asked)
        assertNull(delivery.since)
    }

    /** While the page has a card session open, a card's address is the card being read. */
    @Test
    fun noTapWhileACardSessionIsOpen() {
        session = true
        assertTrue(intake.suppressed)
        intake.intent(ndef, card, replayed = false)
        assertEquals(0, asked)
        assertNull("not even kept for later", delivery.since)
        assertEquals(listOf(CardTapIntake.SUPPRESSED), lines)
        session = false
        assertFalse(intake.suppressed)
        intake.intent(ndef, card, replayed = false)
        assertEquals("a tap again once the session is over (and reader mode never went off here)", 1, asked)
    }

    /** Reader mode going off finds the card still on the phone: for 3 s its address is the card just read. */
    @Test
    fun noTapForTheCardJustReadAsReaderModeGoesOff() {
        intake.readerOff()
        intake.intent(ndef, card, replayed = false)
        clock += CardTapIntake.QUIET_AFTER_READ_MS - 1
        intake.intent(ndef, card, replayed = false)
        assertEquals(0, asked)
        assertEquals(listOf(CardTapIntake.SUPPRESSED, CardTapIntake.SUPPRESSED), lines)
        clock += 1
        assertFalse(intake.suppressed)
        intake.intent(ndef, card, replayed = false)
        assertEquals("lifted and held again later: a tap", 1, asked)
        assertEquals(3_000L, CardTapIntake.QUIET_AFTER_READ_MS)
    }

    /** A tap already waiting is not taken back by a session: the page rules on it (cardTapRule ignores it mid-payment). */
    @Test
    fun aTapAlreadyWaitingIsLeftToTheDelivery() {
        val waiting = CardTapDelivery(now = { clock }, wait = { _, _ -> }, say = {})
        val gate = CardTapIntake(waiting, now = { clock }, cardSession = { session }, say = {})
        gate.intent(ndef, card, replayed = false)
        assertNotNull("kept, the page is not up", waiting.since)
        session = true
        gate.intent(ndef, card, replayed = false)
        assertNotNull(waiting.since)
    }

    /** The suppression is the native side's only: a debug foxy://card goes straight to the delivery, as on iOS. */
    @Test
    fun theDebugCardLinkIsNotTheIntakes() {
        session = true
        intake.intent(view, "foxy://card", replayed = false)
        assertEquals(0, asked)
        delivery.cardTapped()
        assertEquals(1, asked)
    }
}
