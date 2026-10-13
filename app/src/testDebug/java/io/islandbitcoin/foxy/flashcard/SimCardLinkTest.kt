package io.islandbitcoin.foxy.flashcard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug build's card simulator link (debug/.../flashcard/SimCardLink.kt),
 * against a fake card server on loopback that speaks getfoxy/card's
 * CardServer line protocol: the ports tried in turn, "tap" until a card is on
 * the reader, each command as "apdu HEX" and its answer, "gone" as a card
 * lost, "end" as the sheet closes, and the session rules NfcCardLink keeps
 * (the wait, the page closing a wait, looking again, Foxy put away). The real
 * applet behind the real server is CardSimEndToEndTest's.
 */
class SimCardLinkTest {
    private val main = PumpedMain()
    private val servers = ArrayList<FakeCardServer>()
    private val progress = ArrayList<String>()
    private var gone = 0
    private val found = ArrayList<CardLinkError?>()

    @After
    fun closeServers() = servers.forEach { it.close() }

    private fun server() = FakeCardServer().also { servers.add(it) }

    private fun link(ports: List<Int>, waitMs: Long = 20_000) = SimCardLink(main, ports, waitMs).also {
        it.onProgress = { stage, text -> progress.add("$stage: $text") }
        it.onGone = { gone++ }
    }

    @Test
    fun theFourPortsAreGetfoxyCards() {
        assertEquals(listOf(47431, 47432, 47433, 47434), SimCardLink.PORTS)
        assertEquals(60_000L, SimCardLink.WAIT_MS)
    }

    @Test
    fun aCardOnTheReaderIsFoundAndTalkedTo() {
        val card = server()
        card.answer = { hex -> if (hex == "00a404000af0464f5859434152440100") "01149000" else "6d00" }
        val link = link(listOf(freePort(), card.port))
        link.begin("Tap behind the phone.") { found.add(it) }
        main.pump("a card") { found.isNotEmpty() }
        assertEquals(listOf<CardLinkError?>(null), found)
        assertEquals(listOf("connected: Reading the card"), progress)
        var answer: ByteArray? = null
        link.send(CardGate.bytes("00a404000af0464f5859434152440100")!!) { a, w -> assertNull(w); answer = a }
        main.pump("the answer") { answer != null }
        assertEquals("01149000", CardGate.hex(answer!!))
        link.say("Keep the card there")
        assertEquals("say: Keep the card there", progress.last())
        link.end(null, "Done.")
        main.pump("the end said to the server") { "end" in card.heard }
        assertEquals(listOf("tap", "apdu 00a404000af0464f5859434152440100", "end"), card.heard)
        assertEquals("end: Done.", progress.last())
        assertEquals(1, gone)
        // nothing more after the end
        var after: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, w -> after = w }
        assertEquals(CardLinkError.LOST, after)
    }

    @Test
    fun aReaderWithNoCardIsAskedAgainUntilOneIsPutOn() {
        val card = server()
        card.present = false
        val link = link(listOf(card.port))
        link.begin("Tap") { found.add(it) }
        main.pump("asked twice") { card.heard.count { it == "tap" } >= 2 }
        assertTrue(found.isEmpty())
        card.present = true
        main.pump("a card") { found.isNotEmpty() }
        assertEquals(listOf<CardLinkError?>(null), found)
        link.end(null, null)
    }

    @Test
    fun nobodyThereForTheWholeWaitTimesOut() {
        val link = link(listOf(freePort()), waitMs = 1_000)
        link.begin("Tap") { found.add(it) }
        main.pump("the wait to run out") { found.isNotEmpty() }
        assertEquals(listOf(CardLinkError.TIMED_OUT), found)
        assertEquals(listOf("lost: the session timed out"), progress)
        assertEquals(1, gone)
    }

    @Test
    fun aCardTakenAwayIsLostAndFoundAgain() {
        val card = server()
        val link = link(listOf(card.port))
        link.begin("Tap") { found.add(it) }
        main.pump("a card") { found.isNotEmpty() }
        card.present = false
        var why: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { a, w -> assertNull(a); why = w }
        main.pump("the card lost") { why != null }
        assertEquals(CardLinkError.LOST, why)
        assertEquals("lost: the tag was lost", progress.last())
        assertEquals("the session is still open", 0, gone)
        val again = ArrayList<CardLinkError?>()
        link.again("Hold the card here again") { again.add(it) }
        assertEquals("say: Hold the card here again", progress.last())
        main.pump("looked for again") { card.heard.count { it == "tap" } >= 2 }
        card.present = true
        main.pump("the card back") { again.isNotEmpty() }
        assertEquals(listOf<CardLinkError?>(null), again)
        var answer: ByteArray? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { a, _ -> answer = a }
        main.pump("an answer from the card that came back") { answer != null }
        assertEquals("9000", CardGate.hex(answer!!))
        link.end(null, "Done.")
    }

    @Test
    fun anAnswerThatIsNotHexIsALostCard() {
        val card = server()
        card.answer = { "what" }
        val link = link(listOf(card.port))
        link.begin("Tap") { found.add(it) }
        main.pump("a card") { found.isNotEmpty() }
        var why: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, w -> why = w }
        main.pump("lost") { why != null }
        assertEquals(CardLinkError.LOST, why)
    }

    @Test
    fun thePageTakesDownAWaitingSheet() {
        val card = server()
        card.present = false
        val link = link(listOf(card.port))
        link.begin("Tap") { found.add(it) }
        main.pump("asked") { "tap" in card.heard }
        link.end("Cancelled", null)
        assertEquals(listOf(CardLinkError.CANCELLED), found)
        assertEquals(listOf("end: Cancelled"), progress)
        assertEquals(1, gone)
        var again: CardLinkError? = null
        link.again("again") { again = it }
        assertEquals(CardLinkError.CANCELLED, again)
    }

    @Test
    fun foxyPutAwayEndsAWaitOrLosesTheCard() {
        val card = server()
        card.present = false
        val waiting = link(listOf(card.port))
        waiting.begin("Tap") { found.add(it) }
        main.pump("asked") { "tap" in card.heard }
        waiting.putAway()
        assertEquals(listOf(CardLinkError.CANCELLED), found)
        assertEquals("end: Foxy was put away", progress.last())
        card.present = true
        found.clear()
        val held = link(listOf(card.port))
        held.begin("Tap") { found.add(it) }
        main.pump("a card") { found.isNotEmpty() }
        held.putAway()
        assertEquals("lost: the session was cancelled", progress.last())
        assertEquals(2, gone)
    }
}
