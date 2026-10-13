package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.flashcard.CardGate
import io.islandbitcoin.foxy.flashcard.CardLink
import io.islandbitcoin.foxy.flashcard.CardLinkError
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * ENG-667: the card session through the bridge as the page posts it (JSON, main
 * frame, the asset origin), answered through `_scanResult` and pushed through
 * `_card` as FoxyBridge+Flashcard.swift answers and pushes (getfoxy/iOS 8df0bb1):
 * the gate before the link (nothing refused ever reaches a card), the applet
 * chosen before its instructions and chosen again after a card comes back,
 * one session at a time, progress only from the session in hand, and each
 * action's answer with no session, with a phone that cannot read a card, and
 * after the session went. The link is a script ([ScriptedLink]); the radio's
 * own rules are NfcCardLinkTest's, the simulator's SimCardLinkTest's.
 */
class CardSessionTest {
    /** A link that answers as the test says, and keeps what it was asked. */
    private class ScriptedLink : CardLink {
        override var onGone: (() -> Unit)? = null
        override var onProgress: ((String, String) -> Unit)? = null
        val carried = ArrayList<String>()
        val calls = ArrayList<String>()
        var found: ((CardLinkError?) -> Unit)? = null
        var answer: (ByteArray) -> ByteArray? = { byteArrayOf(0x90.toByte(), 0x00) }

        /** When set, a command is held, unanswered, until the test answers it through [held]. */
        var holding = false
        var held: ((ByteArray?, CardLinkError?) -> Unit)? = null

        override fun begin(text: String, found: (CardLinkError?) -> Unit) {
            calls.add("begin: $text")
            this.found = found
        }

        override fun again(text: String, found: (CardLinkError?) -> Unit) {
            calls.add("again: $text")
            this.found = found
        }

        override fun send(apdu: ByteArray, done: (ByteArray?, CardLinkError?) -> Unit) {
            carried.add(CardGate.hex(apdu))
            if (holding) {
                held = done
                return
            }
            val a = answer(apdu)
            if (a == null) done(null, CardLinkError.LOST) else done(a, null)
        }

        override fun say(text: String) {
            calls.add("say: $text")
            onProgress?.invoke("say", text)
        }

        override fun end(error: String?, text: String?) {
            calls.add("end: ${error ?: "-"} / ${text ?: "-"}")
            found?.let { found = null; it(CardLinkError.CANCELLED) }
            onProgress?.invoke("end", error ?: text ?: "")
            onGone?.let { onGone = null; it() }
        }

        override fun putAway() {
            calls.add("putAway")
            if (found != null) end("Foxy was put away", null) else {
                onProgress?.invoke("lost", CardLinkError.CANCELLED.words)
                onGone?.let { onGone = null; it() }
            }
        }

        fun connect() = found!!.also { found = null }(null)
    }

    private val host = FakeHost()
    private val dir = TestSupport.tempDir("card-session")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent },
        seedPresent = { false },
        write = { null },
        counters = CounterStore(dir),
        candidates = SeedCandidates(),
        wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE },
        migrationWindowOpen = { false },
        closeMigrationWindow = {},
        flags = Flags(dir),
    )
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)
    private val links = ArrayList<ScriptedLink>()

    init {
        host.cardLinks = { ScriptedLink().also { links.add(it) } }
    }

    private val select = "00a404000af0464f5859434152440100"
    private var ids = 0

    private fun ask(action: String, extra: Map<String, Any?> = emptyMap()): String {
        val id = "c" + ids++
        bridge.onMessage(Json.write(mapOf("action" to action, "id" to id) + extra), true, FoxyBridge.ORIGIN)
        return id
    }

    private fun replies(): Map<String, Pair<String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.associate { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
        list[0]!! to (list[1] to list[2])
    }

    private fun reply(id: String) = replies()[id]

    /** The `_card` pushes, as the page's `_card` reads them. */
    private fun pushes(): List<String> = host.evaluated.filter { it.contains("._card(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(js.substringAfter("._card(").removeSuffix(")")) as Map<String, Any?>
        "${o["stage"]}: ${o["text"]}"
    }

    private fun open(): ScriptedLink {
        val id = ask("cardBegin", mapOf("text" to "Tap behind the phone."))
        val link = links.last()
        link.onProgress!!("connected", CardGate.SCANNING)
        link.connect()
        assertEquals("ok" to null, reply(id))
        return link
    }

    @Test
    fun theFiveAreServedInP3() {
        for (action in CardBridge.ACTIONS) {
            assertTrue("$action is in the table", action in bridge.handlers)
            assertTrue("$action is P3", action in FoxyBridge.P3_ACTIONS)
        }
        assertEquals(setOf("cardBegin", "cardSend", "cardSay", "cardAgain", "cardEnd"), CardBridge.ACTIONS)
    }

    @Test
    fun aPhoneThatCannotReadACardSaysSo() {
        host.cardLinks = { null }
        val id = ask("cardBegin", mapOf("text" to "Tap behind the phone."))
        assertEquals(1, host.cardLinksMade)
        assertEquals(null to "NFC is not available on this phone", reply(id))
        assertFalse(bridge.cardSessionOpen)
    }

    @Test
    fun beginOpensTheSheetWithTheLineCutToOneLine() {
        host.cardLinks = { ScriptedLink().also { links.add(it) } }
        ask("cardBegin", mapOf("text" to "Tap\nbehind\u0007 the phone." + "x".repeat(300)))
        assertEquals("begin: Tapbehind the phone." + "x".repeat(120 - "Tapbehind the phone.".length), links.single().calls.single())
        ask("cardEnd", mapOf("text" to "Done."))
        ask("cardBegin", mapOf("text" to ""))
        assertEquals("a sheet with no line from the page says where the card goes", "begin: ${CardBridge.BEGIN_LINE}", links.last().calls.first())
        ask("cardBegin")
        assertEquals("begin: ${CardBridge.BEGIN_LINE}", links.last().calls.first())
    }

    @Test
    fun aCardFoundIsOkAndItsProgressReachesThePage() {
        open()
        assertEquals(listOf("connected: Reading the card"), pushes())
        assertTrue(bridge.cardSessionOpen)
    }

    @Test
    fun theSheetEndingFirstIsSaidInTheLinksWords() {
        for (why in CardLinkError.entries) {
            val id = ask("cardBegin", mapOf("text" to "Tap"))
            links.last().found!!(why)
            assertEquals(null to why.words, reply(id))
            assertFalse("a session that never found a card is not held", bridge.cardSessionOpen)
        }
    }

    @Test
    fun theAppletIsChosenBeforeAnythingElseIsCarried() {
        val link = open()
        // before the choosing: nothing of the applet's, PIN included, reaches the card
        val early = ask("cardSend", mapOf("apdu" to "b04000000431323334"))
        assertEquals(null to "That is not a command Foxy sends to a card.", reply(early))
        assertTrue(link.carried.isEmpty())
        // chosen
        link.answer = { byteArrayOf(0x01, 0x14, 0x90.toByte(), 0x00) }
        val chosen = ask("cardSend", mapOf("apdu" to select.uppercase()))
        assertEquals("01149000" to null, reply(chosen))
        link.answer = { byteArrayOf(0x00, 0x00, 0x00, 0x60, 0x90.toByte(), 0x00) }
        val balance = ask("cardSend", mapOf("apdu" to "b011000004"))
        assertEquals("000000609000" to null, reply(balance))
        assertEquals(listOf(select, "b011000004"), link.carried)
    }

    @Test
    fun aChoosingTheCardRefusesLeavesTheAppletUnchosen() {
        val link = open()
        link.answer = { byteArrayOf(0x6A, 0x82.toByte()) }
        assertEquals("6a82" to null, reply(ask("cardSend", mapOf("apdu" to select))))
        assertEquals(null to CardBridge.NOT_FOXYS, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
        // and chosen, then chosen again and refused: unchosen again
        link.answer = { byteArrayOf(0x90.toByte(), 0x00) }
        ask("cardSend", mapOf("apdu" to select))
        assertEquals("9000" to null, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
        link.answer = { byteArrayOf(0x69, 0x99.toByte()) }
        ask("cardSend", mapOf("apdu" to select))
        assertEquals(null to CardBridge.NOT_FOXYS, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
    }

    @Test
    fun whatIsNotACommandOrNotFoxysNeverReachesTheCard() {
        val link = open()
        ask("cardSend", mapOf("apdu" to select))
        val carriedBefore = link.carried.size
        for (bad in listOf(null, 42, "", "zz", "b0 11", "0")) {
            val id = ask("cardSend", if (bad == null) emptyMap() else mapOf("apdu" to bad))
            assertEquals("$bad", null to CardBridge.NOT_A_COMMAND, reply(id))
        }
        // locking, SET_TAG, the PIN's limit, another class, a payment card's record, another applet
        for (refused in listOf("b0500000de", "b05200000100", "b033000004000000ff", "8011000004", "00b2010c00", "00a4040007a0000000041010")) {
            assertEquals(refused, null to CardBridge.NOT_FOXYS, reply(ask("cardSend", mapOf("apdu" to refused))))
        }
        assertEquals("nothing refused was carried", carriedBefore, link.carried.size)
    }

    @Test
    fun aCommandWithNoSessionIsALostCard() {
        assertEquals(null to "the tag was lost", reply(ask("cardSend", mapOf("apdu" to select))))
        // but a command that is no command is said to be so first, as iOS checks it before the session
        assertEquals(null to CardBridge.NOT_A_COMMAND, reply(ask("cardSend", mapOf("apdu" to "xyz"))))
    }

    @Test
    fun aCardLostMidCommandIsLostAndTheAppletMustBeChosenAgain() {
        val link = open()
        ask("cardSend", mapOf("apdu" to select))
        link.answer = { null }
        assertEquals(null to "the tag was lost", reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
        // looked for again: the sheet's line, then ok, and the applet is unchosen
        val again = ask("cardAgain", mapOf("text" to "Hold it there again"))
        assertEquals("again: Hold it there again", link.calls.last())
        assertNull("not answered until a card is there", reply(again))
        link.connect()
        assertEquals("ok" to null, reply(again))
        link.answer = { byteArrayOf(0x90.toByte(), 0x00) }
        assertEquals(null to CardBridge.NOT_FOXYS, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
        assertEquals("9000" to null, reply(ask("cardSend", mapOf("apdu" to select))))
        assertEquals("9000" to null, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
    }

    @Test
    fun aChoosingAnsweredAfterTheCardWasLookedForAgainChoosesNothing() {
        val link = open()
        // the page chooses the applet, and looks for the card again before the answer comes back
        link.holding = true
        val chosen = ask("cardSend", mapOf("apdu" to select))
        assertNull(reply(chosen))
        val again = ask("cardAgain", mapOf("text" to "again"))
        link.connect()
        assertEquals("ok" to null, reply(again))
        // the card that left answers the choosing late, and yes: still the same link object
        link.held!!(byteArrayOf(0x90.toByte(), 0x00), null)
        assertEquals("9000" to null, reply(chosen))
        // the card now in hand (maybe a bank card) was never chosen: the applet's instructions are refused
        link.holding = false
        val carriedBefore = link.carried.size
        assertEquals(null to CardBridge.NOT_FOXYS, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
        assertEquals("nothing refused was carried", carriedBefore, link.carried.size)
        // chosen afresh, it is the applet's again
        assertEquals("9000" to null, reply(ask("cardSend", mapOf("apdu" to select))))
        assertEquals("9000" to null, reply(ask("cardSend", mapOf("apdu" to "b011000004"))))
    }

    @Test
    fun againWithNoLineSaysWhereTheCardGoes() {
        val link = open()
        ask("cardAgain")
        assertEquals("again: ${CardBridge.AGAIN_LINE}", link.calls.last())
    }

    @Test
    fun againThatFindsNoCardEndsTheSessionInHand() {
        val link = open()
        val again = ask("cardAgain", mapOf("text" to "again"))
        link.found!!(CardLinkError.TIMED_OUT)
        assertEquals(null to "the session timed out", reply(again))
        assertFalse(bridge.cardSessionOpen)
    }

    @Test
    fun againWithNoSessionIsCancelled() {
        assertEquals(null to "the session was cancelled", reply(ask("cardAgain", mapOf("text" to "again"))))
    }

    @Test
    fun sayChangesTheLineAndIsOkWithOrWithoutASession() {
        assertEquals("ok" to null, reply(ask("cardSay", mapOf("text" to "Keep the card there"))))
        val link = open()
        assertEquals("ok" to null, reply(ask("cardSay", mapOf("text" to "Keep\nthe card there"))))
        assertEquals("say: Keepthe card there", link.calls.last())
        assertEquals("say: Keepthe card there", pushes().last())
    }

    @Test
    fun endClosesWithAWordOrAReasonAndIsAlwaysOk() {
        assertEquals("with nothing open", "ok" to null, reply(ask("cardEnd", mapOf("text" to "Done."))))
        val link = open()
        assertEquals("ok" to null, reply(ask("cardEnd", mapOf("text" to "Done."))))
        assertEquals("end: - / Done.", link.calls.last())
        assertEquals("the end is told while it is still the session in hand", "end: Done.", pushes().last())
        assertFalse(bridge.cardSessionOpen)
        assertEquals(null to "the tag was lost", reply(ask("cardSend", mapOf("apdu" to select))))
        val second = open()
        ask("cardEnd", mapOf("error" to "Card taken away too soon.\n"))
        assertTrue("the reason, cut to a line", second.calls.last().startsWith("end: Card taken away too soon. / "))
        assertEquals("end: Card taken away too soon.", pushes().last())
    }

    @Test
    fun endTakesDownASheetStillWaitingAndItsBeginIsAnsweredCancelled() {
        val begun = ask("cardBegin", mapOf("text" to "Tap"))
        ask("cardEnd", mapOf("error" to "Cancelled"))
        assertEquals(null to "the session was cancelled", reply(begun))
    }

    @Test
    fun oneSessionAtATime() {
        val first = open()
        val second = ask("cardBegin", mapOf("text" to "Tap again"))
        assertEquals("the session left open was closed first", "end: - / -", first.calls.last())
        assertEquals(2, links.size)
        // the old session's progress no longer reaches the page; the new one's does
        val before = pushes().size
        first.onProgress?.invoke("lost", "the tag was lost")
        assertEquals(before, pushes().size)
        links.last().onProgress!!("connected", CardGate.SCANNING)
        links.last().connect()
        assertEquals("ok" to null, reply(second))
        assertEquals("connected: Reading the card", pushes().last())
        // the old one going says nothing about the new one
        first.onGone?.invoke()
        assertTrue(bridge.cardSessionOpen)
    }

    @Test
    fun foxyPutAwayEndsTheSession() {
        val waiting = ask("cardBegin", mapOf("text" to "Tap"))
        bridge.cardPutAway()
        assertEquals(null to "the session was cancelled", reply(waiting))
        // as on iOS: the wait is answered first, so the sheet's end is no longer the session in hand's, and is not pushed
        assertTrue(pushes().isEmpty())
        assertEquals("end: Foxy was put away / -", links.last().calls.last())
        assertFalse(bridge.cardSessionOpen)
        open()
        bridge.letGo()
        assertEquals("lost: the session was cancelled", pushes().last())
        assertFalse(bridge.cardSessionOpen)
    }

    @Test
    fun thePageGoingTakesItsSessionWithItAndTellsItNothing() {
        val link = open()
        val before = pushes().size
        bridge.pageGone()
        assertEquals("end: - / -", link.calls.last())
        assertEquals("no push for a page that is gone", before, pushes().size)
        assertFalse(bridge.cardSessionOpen)
    }

    @Test
    fun aProgressStageThatIsNotOneOfTheFourIsNotPushed() {
        val link = open()
        val before = pushes().size
        link.onProgress!!("done", "x")
        assertEquals(before, pushes().size)
    }
}
