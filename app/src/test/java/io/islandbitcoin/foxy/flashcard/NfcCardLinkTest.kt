package io.islandbitcoin.foxy.flashcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The card session over the radio (flashcard/NfcCardLink.kt), as a state
 * machine with the radio, the card, the sheet and the main looper faked: the
 * rules of CardLink.swift's `NFCCardLink` (getfoxy/iOS 8df0bb1) with Android's
 * parts. What the page hears (`found`, `_card` stages), what the sheet shows,
 * when reader mode is on, and what is let go, for each way a session goes: a
 * card, no card, Cancel, the wait running out, the card leaving, the page
 * closing it, looking again, and Foxy put away.
 */
class NfcCardLinkTest {
    private class FakeMain : CardMain {
        val posted = ArrayDeque<Runnable>()
        val timers = ArrayList<Triple<Long, Runnable, BooleanArray>>()

        override fun post(action: Runnable) {
            posted.add(action)
        }

        override fun later(ms: Long, action: Runnable): () -> Unit {
            val cancelled = booleanArrayOf(false)
            timers.add(Triple(ms, action, cancelled))
            return { cancelled[0] = true }
        }

        fun drain() {
            while (posted.isNotEmpty()) posted.removeFirst().run()
        }

        /** The timers armed and not cancelled. */
        fun armed() = timers.filter { !it.third[0] }

        fun fire() {
            val due = armed()
            due.forEach { it.third[0] = true }
            due.forEach { it.second.run() }
            drain()
        }
    }

    private class FakeRadio : CardRadio {
        var nfc = true
        var refuses = false
        var looking: ((CardTag) -> Unit)? = null
        var starts = 0
        var stops = 0

        override fun available() = nfc
        override fun start(found: (CardTag) -> Unit): Boolean {
            if (refuses) return false
            starts++
            looking = found
            return true
        }

        override fun stop() {
            if (looking != null) stops++
            looking = null
        }

        fun hold(tag: CardTag) = looking!!.invoke(tag)
    }

    private class FakeTag(override val speaksCommands: Boolean = true, val connects: Boolean = true) : CardTag {
        var connected = false
        var closed = false
        val heard = ArrayList<String>()
        var answer: (ByteArray) -> ByteArray = { byteArrayOf(0x90.toByte(), 0x00) }

        override fun connect() {
            if (!connects) throw IOException("would not connect")
            connected = true
        }

        override fun transceive(apdu: ByteArray): ByteArray {
            if (closed) throw IOException("closed")
            heard.add(CardGate.hex(apdu))
            return answer(apdu)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeSheet : CardSheet {
        val said = ArrayList<String>()
        var cancel: (() -> Unit)? = null
        var up = false

        override fun open(text: String, cancel: () -> Unit) {
            up = true
            this.cancel = cancel
            said.add("open: $text")
        }

        override fun show(text: String) {
            said.add("show: $text")
        }

        override fun finish(text: String) {
            up = false
            said.add("finish: $text")
        }

        override fun fail(text: String) {
            up = false
            said.add("fail: $text")
        }

        override fun dismiss() {
            up = false
            said.add("dismiss")
        }
    }

    private val main = FakeMain()
    private val radio = FakeRadio()
    private val sheet = FakeSheet()
    /** The blocking work runs at once; its result reaches the main looper through a post, as on a phone. */
    private val link = NfcCardLink(radio, sheet, main, Executor { it.run() })
    private val progress = ArrayList<String>()
    private var gone = 0
    private val found = ArrayList<CardLinkError?>()

    init {
        link.onProgress = { stage, text -> progress.add("$stage: $text") }
        link.onGone = { gone++ }
    }

    private fun begin(text: String = "Tap behind the phone.") {
        link.begin(text) { found.add(it) }
        main.drain()
    }

    private fun connected(): FakeTag {
        begin()
        val tag = FakeTag()
        radio.hold(tag)
        main.drain()
        return tag
    }

    @Test
    fun aPhoneWithNoNfcIsToldSoAndNoSheetGoesUp() {
        radio.nfc = false
        begin()
        assertEquals(listOf(CardLinkError.UNAVAILABLE), found)
        assertTrue("no sheet", sheet.said.isEmpty())
        assertEquals("no reader mode", 0, radio.starts)
        assertTrue("nothing pushed: the session never opened", progress.isEmpty())
    }

    @Test
    fun readerModeThatWillNotStartIsUnavailable() {
        radio.refuses = true
        begin()
        assertEquals(listOf(CardLinkError.UNAVAILABLE), found)
        assertEquals(listOf("open: Tap behind the phone.", "dismiss"), sheet.said)
        assertEquals(1, gone)
    }

    @Test
    fun aCardHeldUpIsConnectedAndThePageIsToldOnce() {
        begin("Hold your card")
        assertEquals(listOf("open: Hold your card"), sheet.said)
        assertEquals(1, radio.starts)
        assertEquals("the wait for a card is a minute", listOf(NfcCardLink.WAIT_MS), main.armed().map { it.first })
        assertTrue("nobody told yet", found.isEmpty())
        val tag = FakeTag()
        radio.hold(tag)
        main.drain()
        assertTrue(tag.connected)
        assertEquals(listOf<CardLinkError?>(null), found)
        assertEquals(listOf("connected: Reading the card"), progress)
        assertEquals("show: Reading the card", sheet.said.last())
        assertTrue("the wait is over", main.armed().isEmpty())
        assertTrue("the sheet stays up", sheet.up)
        // a second card while this one is in hand is let go, and nobody is told anything
        val other = FakeTag()
        radio.hold(other)
        main.drain()
        assertTrue(other.closed)
        assertFalse(other.connected)
        assertEquals(1, found.size)
    }

    @Test
    fun aCommandIsCarriedAndItsAnswerComesBack() {
        val tag = connected()
        tag.answer = { byteArrayOf(0x01, 0x14, 0x90.toByte(), 0x00) }
        var got: Pair<ByteArray?, CardLinkError?>? = null
        link.send(CardGate.bytes("00a404000af0464f5859434152440100")!!) { a, w -> got = a to w }
        main.drain()
        assertEquals(listOf("00a404000af0464f5859434152440100"), tag.heard)
        assertEquals("01149000", CardGate.hex(got!!.first!!))
        assertNull(got!!.second)
    }

    @Test
    fun aCardThatLeavesMidCommandIsLostAndSaidSo() {
        val tag = connected()
        tag.answer = { throw IOException("Tag was lost.") }
        var why: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { a, w -> assertNull(a); why = w }
        main.drain()
        assertEquals(CardLinkError.LOST, why)
        assertEquals("lost: the tag was lost", progress.last())
        assertTrue("the card is let go", tag.closed)
        assertTrue("the sheet stays up: the page decides (cardAgain or cardEnd)", sheet.up)
        assertEquals(0, gone)
        // nothing more goes to a card that has gone
        var after: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, w -> after = w }
        main.drain()
        assertEquals(CardLinkError.LOST, after)
        assertEquals("the second was never carried", 1, tag.heard.size)
    }

    @Test
    fun anAnswerTooShortToHoldAStatusIsALostCard() {
        val tag = connected()
        tag.answer = { byteArrayOf(0x90.toByte()) }
        var why: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, w -> why = w }
        main.drain()
        assertEquals(CardLinkError.LOST, why)
    }

    @Test
    fun somethingThatIsNotACardThatSpeaksCommandsIsPassedOver() {
        begin()
        val tag = FakeTag(speaksCommands = false)
        radio.hold(tag)
        main.drain()
        assertFalse(tag.connected)
        assertTrue("still waiting", found.isEmpty())
        assertEquals("show: ${NfcCardLink.ONE_CARD}", sheet.said.last())
        assertEquals("Hold one card to the back of the phone", NfcCardLink.ONE_CARD)
        // then a card
        radio.hold(FakeTag())
        main.drain()
        assertEquals(listOf<CardLinkError?>(null), found)
    }

    @Test
    fun aCardThatWillNotConnectIsLeftAndTheSheetGoesOnLooking() {
        begin()
        radio.hold(FakeTag(connects = false))
        main.drain()
        assertTrue(found.isEmpty())
        assertTrue(sheet.up)
        assertEquals(1, main.armed().size)
        radio.hold(FakeTag())
        main.drain()
        assertEquals(listOf<CardLinkError?>(null), found)
    }

    @Test
    fun nobodyHoldsACardUpForAMinute() {
        begin()
        main.fire()
        assertEquals(listOf(CardLinkError.TIMED_OUT), found)
        assertEquals(listOf("lost: the session timed out"), progress)
        assertEquals("dismiss", sheet.said.last())
        assertEquals(1, radio.stops)
        assertEquals(1, gone)
        // a card that turns up afterwards is nobody's
        val late = FakeTag()
        radio.looking?.invoke(late)
        main.drain()
        assertEquals(1, found.size)
    }

    @Test
    fun cancelOnTheSheetEndsTheSessionCancelled() {
        begin()
        sheet.cancel!!()
        main.drain()
        assertEquals(listOf(CardLinkError.CANCELLED), found)
        assertEquals(listOf("lost: the session was cancelled"), progress)
        assertEquals("dismiss", sheet.said.last())
        assertEquals(1, radio.stops)
        assertTrue("no timer left", main.armed().isEmpty())
        assertEquals(1, gone)
    }

    @Test
    fun cancelWithACardInHandLosesTheCard() {
        val tag = connected()
        sheet.cancel!!()
        main.drain()
        assertEquals("told once, when the card connected", listOf<CardLinkError?>(null), found)
        assertEquals("lost: the session was cancelled", progress.last())
        assertTrue(tag.closed)
        assertEquals(1, gone)
        var why: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, w -> why = w }
        assertEquals(CardLinkError.LOST, why)
    }

    @Test
    fun thePageClosesItWithATickAndAWord() {
        val tag = connected()
        link.end(null, "Done.")
        main.drain()
        assertEquals("end: Done.", progress.last())
        assertEquals("finish: Done.", sheet.said.last())
        assertTrue(tag.closed)
        assertEquals(1, radio.stops)
        assertEquals(1, gone)
        assertEquals("no lost said for a session the page closed", 0, progress.count { it.startsWith("lost") })
        var why: CardLinkError? = null
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, w -> why = w }
        assertEquals(CardLinkError.LOST, why)
        // closed twice: nothing more
        link.end(null, "again")
        assertEquals(1, gone)
        assertEquals("finish: Done.", sheet.said.last())
    }

    @Test
    fun thePageClosesItWithAReasonInRed() {
        connected()
        link.end("The card's PIN was wrong", "ignored")
        assertEquals("end: The card's PIN was wrong", progress.last())
        assertEquals("fail: The card's PIN was wrong", sheet.said.last())
    }

    @Test
    fun thePageTakesDownASheetStillWaiting() {
        begin()
        link.end("Cancelled", null)
        assertEquals("the wait is answered cancelled", listOf(CardLinkError.CANCELLED), found)
        assertEquals(listOf("end: Cancelled"), progress)
        assertEquals("fail: Cancelled", sheet.said.last())
        assertTrue(main.armed().isEmpty())
    }

    @Test
    fun theCardIsLookedForAgainWithTheSheetStillUp() {
        val first = connected()
        first.answer = { throw IOException("gone") }
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, _ -> }
        main.drain()
        val again = ArrayList<CardLinkError?>()
        link.again("Hold the card here again") { again.add(it) }
        main.drain()
        assertEquals("show: Hold the card here again", sheet.said.last())
        assertEquals("reader mode off and on, so a card still in the field is found afresh", 2, radio.starts)
        assertEquals(1, radio.stops)
        assertEquals("a new minute", listOf(NfcCardLink.WAIT_MS), main.armed().map { it.first })
        val second = FakeTag()
        radio.hold(second)
        main.drain()
        assertEquals(listOf<CardLinkError?>(null), again)
        assertTrue(second.connected)
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, _ -> }
        main.drain()
        assertEquals(1, second.heard.size)
    }

    @Test
    fun aCardFoundByAnEarlierLookIsLetGo() {
        begin()
        val earlier = radio.looking!!
        val again = ArrayList<CardLinkError?>()
        link.again("again") { again.add(it) }
        val stale = FakeTag()
        earlier(stale)
        main.drain()
        assertTrue(stale.closed)
        assertFalse(stale.connected)
        assertTrue(again.isEmpty())
    }

    @Test
    fun againAfterTheSessionEndedIsCancelled() {
        connected()
        link.end(null, "Done.")
        var why: CardLinkError? = null
        link.again("again") { why = it }
        assertEquals(CardLinkError.CANCELLED, why)
    }

    @Test
    fun aCardThatLeavesAndComesBackUnaskedIsNotTakenUp() {
        val tag = connected()
        tag.answer = { throw IOException("gone") }
        link.send(byteArrayOf(0xB0.toByte(), 0x11, 0, 0, 4)) { _, _ -> }
        main.drain()
        val back = FakeTag()
        radio.hold(back)
        main.drain()
        assertFalse("taken up only when the page asks again (cardAgain)", back.connected)
        assertTrue(back.closed)
    }

    @Test
    fun theLineChangesAsThePageSays() {
        connected()
        link.say("Keep the card there")
        link.say("")
        assertEquals("show: Keep the card there", sheet.said.last())
        assertEquals("say: Keep the card there", progress.last())
        assertEquals(1, progress.count { it.startsWith("say") })
    }

    @Test
    fun foxyPutAwayWhileTheSheetWaitsEndsTheWait() {
        begin()
        link.putAway()
        assertEquals(listOf(CardLinkError.CANCELLED), found)
        assertEquals(listOf("end: Foxy was put away"), progress)
        assertEquals("fail: Foxy was put away", sheet.said.last())
        assertEquals(1, radio.stops)
        assertEquals(1, gone)
    }

    @Test
    fun foxyPutAwayWithACardInHandLosesTheCard() {
        val tag = connected()
        link.putAway()
        assertEquals("lost: the session was cancelled", progress.last())
        assertEquals("dismiss", sheet.said.last())
        assertTrue(tag.closed)
        assertEquals(1, gone)
        link.putAway()
        assertEquals("once", 1, gone)
    }
}
