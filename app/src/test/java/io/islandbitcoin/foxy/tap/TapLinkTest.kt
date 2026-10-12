package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two links run against each other over [TapAir], with the clock moved by
 * hand: who holds the place, every wait iOS's TapLink sets (three seconds for
 * a handshake, twenty-five for the page, ninety once a person is asked, eight
 * and thirty and ninety-five on the payer, six after Foxy comes back, seven
 * for change), the change leg, a stop that waits for a payment in flight, and
 * what a write that failed means.
 */
class TapLinkTest {

    private fun TapPayerLink.Handover?.answer(): Map<*, *> {
        val a = this as? TapPayerLink.Handover.Answered ?: error("not answered: $this")
        return Json.parse(a.text) as Map<*, *>
    }

    @Test
    fun aWholeTapOverTheAir() {
        val air = TapAir()
        air.link()
        val (offer, code) = air.offer ?: error("no offer")
        assertEquals(TapAir.OFFER, offer)
        assertEquals("both screens show the same four digits", code, air.receiverEvents.first { it.first == TapStage.CODE }.second)
        assertTrue("off the air the moment a payer is really there", !air.advertising)
        assertTrue(air.held)
        assertEquals(listOf(TapStage.CONNECTING, TapStage.LINKED), air.payerStages)
        assertTrue(air.receiver.takenOne)
        assertTrue(air.receiver.withPayer)

        val how = air.pay("{\"proofs\":[{\"amount\":21}]}")
        assertEquals(listOf("{\"proofs\":[{\"amount\":21}]}"), air.payments)
        val answer = how.answer()
        assertEquals(true, answer["ok"])
        assertEquals(200.0, answer["code"])
        assertEquals(1, air.answeredCalls)
        assertFalse(air.receiver.busy)
        assertFalse(air.receiver.withPayer)
    }

    /** The offer may follow the handshake: the code is up before the page has an offer. */
    @Test
    fun theOfferFollowsTheHandshake() {
        val air = TapAir(payload = "")
        air.link()
        assertNotNull(air.receiverEvents.firstOrNull { it.first == TapStage.CODE })
        assertNull(air.offer)
        assertFalse(air.receiver.takenOne)
        air.receiver.update("not an offer")
        air.settle()
        assertNull("only a real offer goes out", air.offer)
        air.receiver.update(TapAir.OFFER)
        air.settle()
        assertEquals(TapAir.OFFER, air.offer?.first)
    }

    /** A subscribe takes nothing; a phone that says its first word and stops has three seconds. */
    @Test
    fun theHandshakeHasThreeSeconds() {
        val air = TapAir()
        air.receiver.radioState(null)
        air.receiver.arm()
        air.settle()
        assertTrue(air.advertising)
        air.receiver.subscribed("listener")
        air.settle()
        assertTrue("a subscribe alone takes no place and the receiver stays on the air", air.advertising)

        // a phone that promises a key and says nothing more
        val stranger = TapSession(TapSession.Role.PAYER, air.receiver.service) {}
        val m1 = TapProtocol.framed((stranger.begin() as TapSession.Step.Send).bytes)!!
        assertTrue(air.receiver.write("listener", listOf(m1)))
        air.settle()
        assertFalse("its first word took the place", air.advertising)
        assertFalse("and nobody else may write", air.receiver.write("someone else", listOf(m1)))
        assertFalse("nor on another door", air.receiver.write("listener", listOf(m1), inDoor = false))
        air.advance(2.9)
        assertFalse(air.advertising)
        air.advance(0.2)
        assertTrue("back on the air after three seconds", air.advertising)
        assertEquals(TapStage.SEARCHING, air.receiverEvents.last().first)
        assertFalse(air.receiver.busy)
    }

    /** Nobody takes the place while the amount is still being typed. */
    @Test
    fun nobodyJoinsOnTheAmountScreen() {
        val air = TapAir()
        air.receiver.early = true
        air.link()
        assertNull(air.offer)
        assertTrue(air.diary.any { it.contains("on the amount screen; not yet") })
    }

    @Test
    fun thePageHasTwentyFiveSecondsToAnswer() {
        val air = TapAir()
        air.paid = { _, _ -> }
        air.link()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.settle()
        assertTrue("a payment in flight keeps the receiver busy", air.receiver.busy)
        air.advance(24.9)
        assertNull(how)
        air.advance(0.2)
        val answer = how.answer()
        assertEquals(false, answer["ok"])
        assertEquals(504.0, answer["code"])
        assertEquals("Their wallet did not answer in time.", answer["why"])
        assertFalse(air.receiver.busy)
    }

    /** M6 is sealed once: the page's answer after the clock has spoken changes nothing. */
    @Test
    fun theResultIsSaidOnce() {
        val air = TapAir()
        var answerLater: ((Int, String) -> Unit)? = null
        air.paid = { _, answer -> answerLater = answer }
        air.link()
        val sent = ArrayList<TapPayerLink.Handover>()
        air.payer.send("{\"proofs\":[]}") { sent.add(it) }
        air.settle()
        answerLater!!(422, "That request is not open.")
        answerLater!!(200, "")
        air.advance(30.0)
        assertEquals(1, sent.size)
        val answer = (sent[0] as TapPayerLink.Handover.Answered).text
        assertEquals("{\"v\":2,\"ok\":false,\"code\":422,\"why\":\"That request is not open.\"}", answer)
    }

    /** A long reason is cut to 160 characters, so M6 still goes. */
    @Test
    fun aLongReasonIsCut() {
        val air = TapAir()
        air.paid = { _, answer -> answer(422, "n".repeat(400)) }
        air.link()
        assertEquals("n".repeat(TapReceiverLink.MOST_WHY), air.pay("{\"proofs\":[]}").answer()["why"])
    }

    /**
     * A reason that does not fit even cut (160 characters of four bytes each)
     * goes as a bare M6, never as silence: iOS falls back the same way.
     */
    @Test
    fun aReasonThatWillNotFitGoesBare() {
        val air = TapAir()
        air.paid = { _, answer -> answer(422, "\uD83E\uDD8A".repeat(200)) }
        air.link()
        val how = air.pay("{\"proofs\":[]}") as TapPayerLink.Handover.Answered
        assertEquals("{\"v\":2,\"ok\":false,\"code\":422}", how.text)
    }

    /** M9 stretches both clocks: ninety seconds on the receiver, ninety-five on the payer, said once. */
    @Test
    fun aPersonDecidingStretchesTheWait() {
        val air = TapAir()
        air.paid = { _, _ -> }
        air.link()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.settle()
        assertTrue(air.receiver.sendAsking())
        air.settle()
        assertEquals(TapStage.ASKING, air.payerStages.last())
        air.advance(30.0)
        assertNull("twenty-five seconds is the wrong clock once a person is asked", how)
        air.advance(59.9)
        assertNull(how)
        air.advance(0.2)
        assertEquals("They did not answer in time.", how.answer()["why"])
        assertFalse("no asking after the result", air.receiver.sendAsking())
    }

    @Test
    fun aSecondAskingIsIgnored() {
        val air = TapAir()
        air.paid = { _, _ -> }
        air.link()
        air.payer.send("{\"proofs\":[]}") {}
        air.settle()
        air.receiver.sendAsking()
        air.settle()
        air.receiver.sendAsking()
        air.settle()
        assertEquals(1, air.payerStages.count { it == TapStage.ASKING })
        assertTrue(air.diary.any { it.contains("a second asking; ignored") })
    }

    /** The price leg: M10 to the page, M11 back. */
    @Test
    fun thePriceLeg() {
        val air = TapAir()
        air.link()
        assertTrue(air.payer.sendQuote("{\"sats\":21}"))
        air.settle()
        assertEquals(listOf("{\"sats\":21}"), air.quotes)
        assertTrue(air.receiver.sendTerms("{\"v\":2,\"req\":\"creqAxyz\"}"))
        air.settle()
        assertEquals(listOf("{\"v\":2,\"req\":\"creqAxyz\"}"), air.terms)
    }

    /** Change: answered KEPT on the payer's word, and the link let go only after it. */
    @Test
    fun changeIsKeptOnThePayersWord() {
        val air = TapAir()
        air.link()
        air.receiver.expectChange()
        assertTrue("change owed keeps the link", air.receiver.busy)
        air.pay("{\"proofs\":[{\"amount\":32}]}")
        val words = ArrayList<ChangeHandover.Word>()
        assertTrue(air.receiver.handOverChange("cashuBchange") { words.add(it) })
        air.settle()
        assertEquals(listOf("cashuBchange"), air.changes)
        assertEquals(listOf(ChangeHandover.Word.KEPT), words)
        assertFalse(air.receiver.busy)
        air.advance(10.0)
        assertEquals("answered once", listOf(ChangeHandover.Word.KEPT), words)
    }

    /** Seven seconds, then NOT_HANDED; a "kept" after that is LATE. */
    @Test
    fun changeNotKeptInSevenSecondsAndThenLate() {
        val air = TapAir()
        var keepLater: ((Boolean) -> Unit)? = null
        air.keepChange = { _, keep -> keepLater = keep }
        air.link()
        air.pay("{\"proofs\":[{\"amount\":32}]}")
        val words = ArrayList<ChangeHandover.Word>()
        air.receiver.handOverChange("cashuBchange") { words.add(it) }
        air.advance(6.9)
        assertTrue(words.isEmpty())
        air.advance(0.2)
        assertEquals(listOf(ChangeHandover.Word.NOT_HANDED), words)
        keepLater!!(true)
        air.settle()
        assertEquals(listOf(ChangeHandover.Word.NOT_HANDED, ChangeHandover.Word.LATE), words)
    }

    /** A page that could not keep the change sends nothing: the receiver's own clock answers. */
    @Test
    fun aPageThatCouldNotKeepTheChangeSaysNothing() {
        val air = TapAir()
        air.keepChange = { _, keep -> keep(false) }
        air.link()
        air.pay("{\"proofs\":[{\"amount\":32}]}")
        val words = ArrayList<ChangeHandover.Word>()
        val before = air.written.size
        air.receiver.handOverChange("cashuBchange") { words.add(it) }
        air.settle()
        assertEquals("no M8", before, air.written.size)
        air.advance(7.1)
        assertEquals(listOf(ChangeHandover.Word.NOT_HANDED), words)
    }

    /** A payer that leaves with change owed is not waited for. */
    @Test
    fun aPayerThatLeavesWithChangeOwedIsNotWaitedFor() {
        val air = TapAir()
        air.keepChange = { _, _ -> }
        air.link()
        air.pay("{\"proofs\":[{\"amount\":32}]}")
        val words = ArrayList<ChangeHandover.Word>()
        air.receiver.handOverChange("cashuBchange") { words.add(it) }
        air.settle()
        assertTrue(air.receiver.busy)
        air.receiver.unsubscribed(TapAir.PAYER)
        assertEquals(listOf(ChangeHandover.Word.NOT_HANDED), words)
        assertFalse(air.receiver.busy)
        assertFalse("and nothing is sent to a payer that has gone", air.receiver.sendChange("cashuBmore"))
        assertFalse(air.receiver.handOverChange("") { words.add(it) })
    }

    /** A stop with a payment in flight waits for the answer, and goes two seconds after it. */
    @Test
    fun aStopWaitsForThePaymentInFlight() {
        val air = TapAir()
        var answerLater: ((Int, String) -> Unit)? = null
        air.paid = { _, answer -> answerLater = answer }
        air.link()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.settle()
        var idle = false
        assertFalse("not while a payment is crossing", air.receiver.stop { idle = true })
        assertFalse(air.receiverClosed)
        air.advance(5.0)
        answerLater!!(200, "")
        air.settle()
        assertEquals(true, how.answer()["ok"])
        air.advance(1.9)
        assertFalse(air.receiverClosed)
        air.advance(0.2)
        assertTrue("two seconds after the answer", air.receiverClosed)
        assertFalse(idle)
        air.advance(30.0)
        assertTrue("and the stop's own fuse still says idle", idle)
    }

    @Test
    fun aStopWithNothingInFlightIsAtOnce() {
        val air = TapAir()
        air.link()
        assertTrue(air.receiver.stop())
        assertTrue(air.receiverClosed)
        air.receiver.arm()
        assertFalse("a finished receiver is never on the air again", air.advertising)
    }

    /** The payer gone after the link was made, with nothing sent: said to the page. */
    @Test
    fun aPayerThatGoesAfterTheLinkIsSaid() {
        val air = TapAir()
        air.link()
        air.receiver.unsubscribed(TapAir.PAYER)
        assertEquals(TapStage.GONE, air.receiverEvents.last().first)
    }

    /** A payer that goes before the handshake finished puts the receiver back on the air. */
    @Test
    fun aPayerThatGoesMidHandshakeLeavesTheReceiverOnTheAir() {
        val air = TapAir()
        air.receiver.radioState(null)
        air.receiver.arm()
        air.settle()
        air.receiver.subscribed("p")
        val stranger = TapSession(TapSession.Role.PAYER, air.receiver.service) {}
        air.receiver.write("p", listOf(TapProtocol.framed((stranger.begin() as TapSession.Step.Send).bytes)!!))
        air.settle()
        assertFalse(air.advertising)
        air.receiver.unsubscribed("p")
        air.settle()
        assertTrue(air.advertising)
    }

    /** The radio off, or not allowed: said to the page, and nothing goes on the air. */
    @Test
    fun aRadioThatIsOffIsSaid() {
        val air = TapAir()
        air.receiver.arm()
        air.receiver.radioState(TapStage.OFF)
        air.settle()
        assertFalse(air.advertising)
        assertEquals(TapStage.OFF to null, air.receiverEvents.last())
        air.receiver.radioState(null)
        air.settle()
        assertTrue(air.advertising)
        assertEquals(TapStage.SEARCHING to null, air.receiverEvents.last())
    }

    /** A phone of another version is answered with this one's, said to the page, and the receiver goes back on the air. */
    @Test
    fun anotherVersionIsSaidAndTheReceiverStaysOnTheAir() {
        val air = TapAir()
        air.receiver.radioState(null)
        air.receiver.arm()
        air.settle()
        air.receiver.subscribed(TapAir.PAYER)
        air.receiver.write(TapAir.PAYER, listOf(TapProtocol.framed(byteArrayOf(0x01, 4) + ByteArray(32))!!))
        air.settle()
        assertEquals(TapStage.VERSION to "4", air.receiverEvents.last { it.first == TapStage.VERSION })
        assertTrue(air.advertising)
        assertEquals("the reply is this phone's version alone", listOf<Byte>(0, 2, 0x02, 3), air.notified.last().toList())
    }

    /** M12: back in front, the payer asks again and hears what it missed. */
    @Test
    fun aPayerThatWasAwayHearsTheResultAgain() {
        val air = TapAir()
        var answerLater: ((Int, String) -> Unit)? = null
        air.paid = { _, answer -> answerLater = answer }
        air.link()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.settle()
        air.payerAway = true
        answerLater!!(200, "")
        air.settle()
        assertNull(how)
        air.payerAway = false
        air.payer.askAgain()
        air.settle()
        assertEquals(true, how.answer()["ok"])
    }

    /** A receiver that says nothing in six seconds after Foxy came back has gone. */
    @Test
    fun aReceiverThatDoesNotAnswerAfterFoxyCameBackHasGone() {
        val air = TapAir()
        air.paid = { _, _ -> }
        air.link()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.settle()
        air.payerAway = true
        air.payer.askAgain()
        air.settle()
        // not asked again within five seconds
        val writes = air.written.size
        air.advance(4.9)
        air.payer.askAgain()
        air.settle()
        assertEquals(writes, air.written.size)
        air.advance(1.0)
        assertNull(how)
        air.advance(0.2)
        assertEquals(TapPayerLink.Handover.Lost("That phone did not answer after Foxy came back."), how)
    }

    /** Connected and nothing came of it: eight seconds, then the payer looks again. */
    @Test
    fun thePayerGivesUpOnASilentReceiverInEightSeconds() {
        val air = TapAir()
        air.payer.connecting()
        air.advance(7.9)
        assertNull(air.gaveUp)
        air.advance(0.2)
        assertEquals("timed out", air.gaveUp)
        assertEquals(listOf(TapStage.CONNECTING, TapStage.GONE, TapStage.SEARCHING), air.payerStages)
    }

    /** Once the receiver has spoken, thirty seconds for the offer. */
    @Test
    fun onceTheReceiverHasSpokenTheOfferHasThirtySeconds() {
        val air = TapAir(payload = "")
        air.link()
        air.advance(29.9)
        assertNull(air.gaveUp)
        air.advance(0.2)
        assertEquals("nothing came after the handshake", air.gaveUp)
        // and a link that has given up does nothing more
        air.receiver.update(TapAir.OFFER)
        air.settle()
        assertNull(air.offer)
    }

    @Test
    fun aSendWithNoLinkIsLostAtOnce() {
        val air = TapAir()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{}") { how = it }
        assertEquals(TapPayerLink.Handover.Lost("That phone is no longer connected."), how)
        assertFalse(air.payer.sendQuote("x"))
    }

    @Test
    fun oneSendAtATime() {
        val air = TapAir()
        air.paid = { _, _ -> }
        air.link()
        air.payer.send("{}") {}
        var second: TapPayerLink.Handover? = null
        air.payer.send("{}") { second = it }
        assertEquals(TapPayerLink.Handover.Lost("A payment is already going over."), second)
    }

    /** A receiver that goes mid-payment is said at once. */
    @Test
    fun aDisconnectMidPaymentIsLost() {
        val air = TapAir()
        air.paid = { _, _ -> }
        air.link()
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.settle()
        air.payer.disconnected("gone")
        assertEquals(TapPayerLink.Handover.Lost("That phone went away before it said what became of the payment."), how)
    }

    /** A step of the handshake that would not go lets the link go: its place in the count is spent. */
    @Test
    fun aHandshakeWordThatDidNotGoLetsTheLinkGo() {
        val air = TapAir()
        air.receiver.radioState(null)
        air.receiver.arm()
        air.settle()
        air.failWrites = 1
        air.payer.connecting()
        air.payer.speak()
        air.settle()
        assertEquals("a word did not get through", air.gaveUp)
    }

    @Test
    fun aPaymentThatWouldNotGoIsLost() {
        val air = TapAir()
        air.link()
        air.failWrites = 1
        assertEquals(TapPayerLink.Handover.Lost("That phone stopped taking the payment."), air.pay("{\"proofs\":[]}"))
        assertNull("not a reason to look for another receiver", air.gaveUp)
    }

    /** A large payment says its size first, goes in parts, and both screens say how far it has got. */
    @Test
    fun aLargePaymentSaysHowFarItHasGot() {
        val air = TapAir(mtu = 512)
        air.link()
        val big = "{\"proofs\":\"" + "abcdefghi₿".repeat(8_400) + "\"}"
        val answer = air.pay(big).answer()
        assertEquals(true, answer["ok"])
        assertEquals(listOf(big), air.payments)
        val receiving = air.receiverEvents.filter { it.first == TapStage.RECEIVING }.map { it.second!!.toInt() }
        assertTrue("the receiver said how far: $receiving", receiving.size > 5)
        assertEquals("never backwards", receiving.sorted(), receiving)
        assertTrue("never 100 before it is whole", receiving.all { it <= 99 })
        assertTrue(air.payerStages.count { it == TapStage.SENDING } > 5)
        assertEquals(100, air.payer.progress)
        assertTrue(air.diary.any { it.contains("a payment of") && it.contains("bytes is on its way") })
    }

    /** An ordinary payment says no size first: an older Foxy would not know the message. */
    @Test
    fun anOrdinaryPaymentSaysNoSize() {
        val air = TapAir()
        air.link()
        air.pay("{\"proofs\":[{\"amount\":21}]}")
        assertFalse(air.diary.any { it.contains("bytes is on its way") })
        assertTrue(air.receiverEvents.none { it.first == TapStage.RECEIVING })
    }

    /** A stop with a word still going out waits for it, two seconds at most. */
    @Test
    fun aPayerStopWaitsForAWordGoingOut() {
        val air = TapAir()
        air.link()
        air.payer.sendQuote("{\"sats\":21}")
        air.payer.stop()
        assertFalse(air.payerClosed)
        air.settle()
        assertTrue("closed once the word went", air.payerClosed)
        assertEquals(listOf("{\"sats\":21}"), air.quotes)
    }

    /**
     * As iOS (TapLink.swift:1064-1079 at 8df0bb1): every two seconds the stop
     * is asked again, and it waits again while the write is still out and the
     * link is up. The link closes when the link itself goes.
     */
    @Test
    fun aPayerStopWaitsWhileAWordIsOutAndClosesWhenTheLinkGoes() {
        val air = TapAir()
        air.link()
        air.payer.sendQuote("{\"sats\":21}")
        air.pending.clear() // the write never comes back
        air.payer.stop()
        air.advance(1.9)
        assertFalse(air.payerClosed)
        air.advance(0.2)
        assertFalse("still out and the link still up: it waits again", air.payerClosed)
        air.advance(20.0)
        assertFalse(air.payerClosed)
        air.connected = false
        air.advance(2.0)
        assertTrue("the next look finds the link gone and closes", air.payerClosed)
    }

    /**
     * The page keeps the change and lets the link go with M8 the one write in
     * flight, its acknowledgement five seconds away: the link waits for it,
     * so the receiver hears KEPT, not NOT_HANDED and a change code for change
     * the payer already holds.
     */
    @Test
    fun aPayerStopWaitsForTheWordThatTheChangeWasKept() {
        val air = TapAir()
        air.link()
        air.pay("{\"proofs\":[{\"amount\":32}]}")
        air.holdWrites = true
        val words = ArrayList<ChangeHandover.Word>()
        assertTrue(air.receiver.handOverChange("cashuBchange") { words.add(it) })
        air.settle()
        assertEquals(listOf("cashuBchange"), air.changes)
        air.payer.stop()
        air.advance(2.1)
        assertFalse("M8 is still going out: the link stays", air.payerClosed)
        air.advance(2.9)
        assertFalse(air.payerClosed)
        assertTrue(words.isEmpty())
        air.releaseWrites()
        air.settle()
        assertEquals(listOf(ChangeHandover.Word.KEPT), words)
        assertTrue("closed once M8 went", air.payerClosed)
        air.advance(10.0)
        assertEquals("answered once", listOf(ChangeHandover.Word.KEPT), words)
    }

    /**
     * A subscribe that failed is not retried at once: iOS never reaches its
     * error branch (TapLink.swift:1624-1625 at 8df0bb1, `guard ch.isNotifying`
     * first), so the payer waits out its eight seconds and gives up there.
     */
    @Test
    fun aSubscribeThatFailedWaitsOutTheConnectClock() {
        val air = TapAir()
        air.receiver.radioState(null)
        air.receiver.arm()
        air.settle()
        air.subscribeError = "the CCCD write failed"
        air.payer.connecting()
        air.payer.speak()
        air.settle()
        assertNull("not given up at once", air.gaveUp)
        assertTrue("and M1 never went", air.written.isEmpty())
        air.advance(7.9)
        assertNull(air.gaveUp)
        air.advance(0.2)
        assertEquals("timed out", air.gaveUp)
    }

    /**
     * Radio callbacks already on their way when the receiver stopped do
     * nothing (iOS gets this from `m.delegate = nil`, TapLink.swift:465): no
     * stage for the page, no place taken, no radio call.
     */
    @Test
    fun aStoppedReceiverIgnoresLateRadioCallbacks() {
        val air = TapAir()
        air.receiver.radioState(null)
        air.receiver.arm()
        air.settle()
        assertTrue(air.receiver.stop())
        val events = air.receiverEvents.size
        val advertises = air.advertiseCalls
        val stops = air.stopAdvertisingCalls
        val notifies = air.notified.size
        air.receiver.advertising()
        air.receiver.radioState(TapStage.OFF)
        air.receiver.radioState(TapStage.DENIED)
        air.receiver.radioState(null)
        air.receiver.subscribed(TapAir.PAYER)
        val stranger = TapSession(TapSession.Role.PAYER, air.receiver.service) {}
        val m1 = TapProtocol.framed((stranger.begin() as TapSession.Step.Send).bytes)!!
        assertFalse("a write after the stop is refused", air.receiver.write(TapAir.PAYER, listOf(m1)))
        air.receiver.unsubscribed(TapAir.PAYER)
        air.receiver.readyToUpdate()
        air.advance(10.0)
        assertEquals("no stage after the stop", events, air.receiverEvents.size)
        assertEquals(advertises, air.advertiseCalls)
        assertEquals(stops, air.stopAdvertisingCalls)
        assertEquals(notifies, air.notified.size)
        assertFalse(air.receiver.busy)
        assertFalse(air.receiver.takenOne)
    }

    /** A notification the radio could not take now goes when it says it is ready. */
    @Test
    fun notificationsWaitForTheRadio() {
        var full = true
        val sent = ArrayList<ByteArray>()
        val clock = FakeClock()
        val receiver = TapReceiverLink(
            TapAir.OFFER,
            object : TapReceiverLink.Radio {
                override fun advertise() {}
                override fun stopAdvertising() {}
                override fun notify(central: String, piece: ByteArray): Boolean {
                    if (full) return false
                    sent.add(piece)
                    return true
                }
                override fun notifyLength(central: String) = 20
                override fun close() {}
            },
            clock, { _, _ -> }, diary = {},
        )
        receiver.radioState(null)
        receiver.arm()
        receiver.subscribed("p")
        val payer = TapSession(TapSession.Role.PAYER, receiver.service) {}
        receiver.write("p", listOf(TapProtocol.framed((payer.begin() as TapSession.Step.Send).bytes)!!))
        assertTrue(sent.isEmpty())
        full = false
        receiver.readyToUpdate()
        val m2 = sent.fold(ByteArray(0)) { a, b -> a + b }
        assertEquals("M2 in pieces of twenty, framed", 2 + 1 + 1 + 32 + 16, m2.size)
        assertTrue(sent.all { it.size <= 20 })
    }
}
