package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.tap.TapSession.Role
import io.islandbitcoin.foxy.tap.TapSession.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The whole conversation, both sides run against each other in memory:
 * FoxyTests/TapSessionTests.swift, ported. No radio, so this is where the
 * protocol is proved.
 */
class TapSessionTest {
    private val quiet: (String) -> Unit = {}

    private fun payer(service: java.util.UUID) = TapSession(Role.PAYER, service, diary = quiet)
    private fun receiver(service: java.util.UUID) = TapSession(Role.RECEIVER, service, diary = quiet)

    /** Carries one side's output to the other, and hands back what it said. */
    private fun hand(step: Step, to: TapSession): Step = if (step is Step.Send) to.received(step.bytes) else Step.Waiting

    private fun linked(p: TapSession, r: TapSession): Step = hand(hand(hand(p.begin(), r), p), r)

    private inline fun <reified T : Step> expect(step: Step, what: String): T =
        step as? T ?: run { fail("$what: $step") } as T

    @Test
    fun aWholeTapFromHelloToPaid() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        val m2 = hand(payer.begin(), receiver)
        val m3 = hand(m2, payer)
        val receiverCode = expect<Step.Linked>(hand(m3, receiver), "the receiver did not link").code
        assertEquals(4, receiverCode.length)
        assertEquals(receiverCode, payer.code)
        assertEquals(receiverCode, receiver.code)

        val offerText = "{\"v\":2,\"inv\":\"lnbc10n1pn9\",\"req\":\"creqAabcdefghijklmnop\"}"
        val offer = expect<Step.Offer>(payer.received(receiver.sealOffer(offerText)!!), "no offer")
        assertEquals(offerText, offer.text)
        assertEquals(receiverCode, offer.code)

        val paymentText = "{\"mint\":\"https://mint.example\",\"proofs\":[{\"amount\":21}]}"
        assertEquals(paymentText, expect<Step.Payment>(receiver.received(payer.sealPayment(paymentText)!!), "no payment").text)

        assertEquals("{\"ok\":true}", expect<Step.Result>(payer.received(receiver.sealResult("{\"ok\":true}")!!), "no result").text)

        val changeText = "cashuBchangetokenforthepayer"
        assertEquals(changeText, expect<Step.Change>(payer.received(receiver.sealChange(changeText)!!), "no change").text)
        assertEquals(Step.ChangeTaken, receiver.received(payer.sealChangeTaken()!!))
    }

    /** The price leg (M10, M11) before any money. */
    @Test
    fun thePriceIsAgreedBeforeAnyMoney() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        expect<Step.Linked>(linked(payer, receiver), "not linked")
        expect<Step.Offer>(payer.received(receiver.sealOffer("{\"v\":2,\"req\":\"creqAabcdefghijklmnop\"}")!!), "no offer")
        assertEquals(Step.Quote("{\"sats\":21}"), receiver.received(payer.sealQuote("{\"sats\":21}")!!))
        assertEquals(Step.Terms("{\"v\":2}"), payer.received(receiver.sealTerms("{\"v\":2}")!!))
        assertNull("only a payer quotes", receiver.sealQuote("x"))
        assertNull("only a receiver sends terms", payer.sealTerms("x"))
        assertNull("a quote is one result's size", payer.sealQuote("x".repeat(253)))
    }

    @Test
    fun aPayerThatWasAwayHearsItAgain() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        expect<Step.Linked>(linked(payer, receiver), "not linked")
        expect<Step.Offer>(payer.received(receiver.sealOffer("{\"v\":2,\"req\":\"creqAabcdefghijklmnop\"}")!!), "no offer")
        expect<Step.Payment>(receiver.received(payer.sealPayment("{\"proofs\":[{\"amount\":21}]}")!!), "no payment")

        // said while the payer's app was away: neither reaches it
        val m6 = receiver.sealResult("{\"ok\":true}")!!
        val m7 = receiver.sealChange("cashuBchangetokenforthepayer")!!

        val from = expect<Step.Again>(receiver.received(payer.sealAgain()!!), "no repeat request").from
        val again = receiver.framesSince(from)
        assertEquals(2, again.size)
        assertArrayEquals("the same bytes, in the order they were said", m6, again[0])
        assertArrayEquals(m7, again[1])
        assertEquals("{\"ok\":true}", expect<Step.Result>(payer.received(again[0]), "no result the second time").text)
        assertEquals("cashuBchangetokenforthepayer", expect<Step.Change>(payer.received(again[1]), "no change the second time").text)

        assertEquals(Step.ChangeTaken, receiver.received(payer.sealChangeTaken()!!))
        val now = expect<Step.Again>(receiver.received(payer.sealAgain()!!), "no second repeat request").from
        assertTrue("a payer that has heard everything is told nothing twice", receiver.framesSince(now).isEmpty())
        assertNull("and a receiver cannot ask", receiver.sealAgain())
    }

    private fun paidPair(): Pair<TapSession, TapSession> {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        expect<Step.Linked>(linked(payer, receiver), "not linked")
        expect<Step.Offer>(payer.received(receiver.sealOffer("{\"v\":2,\"req\":\"creqAabcdefghijklmnop\"}")!!), "no offer")
        expect<Step.Payment>(receiver.received(payer.sealPayment("{\"proofs\":[{\"amount\":21}]}")!!), "no payment")
        return payer to receiver
    }

    @Test
    fun aPayerThatMissedOnlyTheChangeIsSentOnlyTheChange() {
        val (payer, receiver) = paidPair()
        expect<Step.Result>(payer.received(receiver.sealResult("{\"ok\":true}")!!), "no result")
        val m7 = receiver.sealChange("cashuBchange")!!
        val from = expect<Step.Again>(receiver.received(payer.sealAgain()!!), "no repeat request").from
        val again = receiver.framesSince(from)
        assertEquals(1, again.size)
        assertArrayEquals(m7, again[0])
        assertEquals("cashuBchange", expect<Step.Change>(payer.received(m7), "no change").text)
    }

    @Test
    fun aMissedWaitIsRepeatedBeforeTheResultItCameBefore() {
        val (payer, receiver) = paidPair()
        val m9 = receiver.sealAsking()!!
        val m6 = receiver.sealResult("{\"ok\":true}")!!
        val from = expect<Step.Again>(receiver.received(payer.sealAgain()!!), "no repeat request").from
        val again = receiver.framesSince(from)
        assertEquals("in the order they were sealed", 2, again.size)
        assertArrayEquals(m9, again[0])
        assertArrayEquals(m6, again[1])
        assertEquals(Step.Asking, payer.received(again[0]))
        expect<Step.Result>(payer.received(again[1]), "the result did not open")
        // out of order it would not have: this is what the counter is for
        val (p2, r2) = paidPair()
        r2.sealAsking()!!
        expect<Step.Stop>(p2.received(r2.sealResult("{\"ok\":true}")!!), "a result opened with a message missing before it")
    }

    @Test
    fun aMessageAlreadyOpenedIsNotOpenedAgain() {
        val (payer, receiver) = paidPair()
        val m6 = receiver.sealResult("{\"ok\":true}")!!
        expect<Step.Result>(payer.received(m6), "no result")
        expect<Step.Stop>(payer.received(m6), "the same result opened twice")
    }

    @Test
    fun askingBeforeAnythingWasSaidCostsNothing() {
        val (payer, receiver) = paidPair()
        val from = expect<Step.Again>(receiver.received(payer.sealAgain()!!), "no repeat request").from
        assertTrue(receiver.framesSince(from).isEmpty())
        expect<Step.Result>(payer.received(receiver.sealResult("{\"ok\":true}")!!), "the result did not open after an early ask")
    }

    /** Only the last eight are kept to be said again. */
    @Test
    fun aReceiverKeepsAHandfulToSayAgain() {
        val (_, receiver) = paidPair()
        repeat(10) { receiver.sealChange("cashuB$it")!! }
        assertEquals(8, receiver.framesSince(0u).size)
    }

    @Test
    fun aLargePaymentGoesInPartsAndArrivesWhole() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        expect<Step.Linked>(linked(payer, receiver), "not linked")
        expect<Step.Offer>(payer.received(receiver.sealOffer("{\"v\":2,\"req\":\"creqAabcdefghijklmnop\"}")!!), "no offer")

        // about 100 KB, and with a character that is more than one byte in it
        val big = "{\"proofs\":\"" + "abcdefghi₿".repeat(8_400) + "\"}"
        assertTrue(big.toByteArray().size > 2 * TapCrypto.PART_BODY)
        val estimate = payer.paymentWireEstimate(big)
        val size = payer.sealPaymentSize(estimate)!!
        val frames = payer.sealPaymentFrames(big)!!
        assertEquals("three parts for about a hundred kilobytes", 3, frames.size)
        assertTrue("each fits a frame's two-byte length", frames.all { it.size < 65_535 })
        val actual = frames.sumOf { it.size + 2 }
        assertTrue("the estimate is the wire size to within a few bytes a part", kotlin.math.abs(actual - estimate) < 200)

        assertEquals(Step.PaymentSize(estimate.toLong()), receiver.received(size))
        assertEquals(Step.Waiting, receiver.received(frames[0]))
        assertEquals(Step.Waiting, receiver.received(frames[1]))
        assertEquals("joined, it is what was sent", big, expect<Step.Payment>(receiver.received(frames[2]), "no payment at the last part").text)

        val other = payer(service)
        val recv2 = receiver(service)
        expect<Step.Linked>(linked(other, recv2), "not linked")
        assertEquals("an ordinary payment is still one message", 1, other.sealPaymentFrames("{\"proofs\":[]}")!!.size)
        // a size sealed after the payment is a message out of order, and is refused
        val p3 = payer(service)
        val r3 = receiver(service)
        expect<Step.Linked>(linked(p3, r3), "not linked")
        p3.sealPaymentFrames(big)!!
        expect<Step.Stop>(r3.received(p3.sealPaymentSize(1)!!), "a size sealed out of order opened")
        assertNull("past the cap nothing is sealed", other.sealPaymentFrames("x".repeat(TapCrypto.MOST_PAYMENT_TOTAL + 1)))
        assertNull("only a payer says how much is coming", recv2.sealPaymentSize(1000))
    }

    /** A size that is not a positive whole number stops the link. */
    @Test
    fun aSizeThatIsNotANumberStopsTheLink() {
        assertEquals(5L, TapSession.swiftInt("+5"))
        assertNull(TapSession.swiftInt(" 5"))
        assertNull("only ASCII digits, as Swift's Int(_:) reads", TapSession.swiftInt("٥"))
        assertNull(TapSession.swiftInt("9223372036854775808"))
        assertEquals(Long.MAX_VALUE, TapSession.swiftInt("9223372036854775807"))
        assertEquals(18446744073709551615uL, TapSession.swiftUInt64("18446744073709551615"))
        assertNull(TapSession.swiftUInt64("18446744073709551616"))
        assertNull(TapSession.swiftUInt64("-1"))
        assertNull(TapSession.swiftUInt64(""))
    }

    @Test
    fun eachSideCanOnlySealItsOwnHalfOfTheChangeLeg() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        linked(payer, receiver)
        assertNull("a payer cannot send change", payer.sealChange("cashuBnope"))
        assertNull("a receiver cannot say it kept the change", receiver.sealChangeTaken())
        assertNotNull(receiver.sealChange("cashuByes"))
        assertNotNull(payer.sealChangeTaken())
    }

    @Test
    fun changeInTheWrongPlaceStopsTheLink() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        expect<Step.Stop>(payer.received(byteArrayOf(0x07, 1, 2, 3)), "change before a key was taken")
        linked(payer, receiver)
        expect<Step.Stop>(payer.received(payer.sealChangeTaken()!!), "the payer heard its own receipt")
    }

    @Test
    fun changeIsAlwaysTheSameSizeOnTheWire() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        linked(payer, receiver)
        val small = receiver.sealChange("cashuBa")!!
        val large = receiver.sealChange("x".repeat(1200))!!
        assertEquals("a change message the size of its token would say how much it is", small.size, large.size)
    }

    @Test
    fun changeTooBigForOneSizeStillCrosses() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        linked(payer, receiver)
        val big = "cashuB" + "y".repeat(3000)
        assertEquals(big, expect<Step.Change>(payer.received(receiver.sealChange(big)!!), "no change").text)
    }

    @Test
    fun aKeyThatWasNotPromisedIsRefused() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        hand(payer.begin(), receiver)
        val other = payer(service)
        val forged = byteArrayOf(0x03) + other.publicKey + TapCrypto.randomNonce()
        val why = expect<Step.Stop>(receiver.received(forged), "a key that was never promised was accepted").why
        assertTrue(why, why.contains("promised"))
    }

    @Test
    fun anOfferFromAnotherHandshakeDoesNotOpen() {
        val a = TapCrypto.newService()
        val payerA = payer(a)
        val receiverA = receiver(a)
        linked(payerA, receiverA)
        val b = TapCrypto.newService()
        val payerB = payer(b)
        val receiverB = receiver(b)
        linked(payerB, receiverB)
        val why = expect<Step.Stop>(payerA.received(receiverB.sealOffer("{\"v\":2,\"inv\":\"lnbc1\"}")!!), "opened").why
        assertTrue(why, why.contains("would not open"))
    }

    @Test
    fun twoPayersNeverShareAHandshake() {
        val service = TapCrypto.newService()
        val first = payer(service)
        val second = payer(service)
        val toFirst = receiver(service)
        val toSecond = receiver(service)
        linked(first, toFirst)
        linked(second, toSecond)
        assertNotEquals("two payers, two handshakes", first.publicKey.toList(), second.publicKey.toList())
        val why = expect<Step.Stop>(second.received(toFirst.sealOffer("{\"v\":2,\"inv\":\"lnbc1\"}")!!), "a second payer opened it").why
        assertTrue(why, why.contains("would not open"))
    }

    @Test
    fun messagesOutOfPlaceStopTheLink() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        expect<Step.Stop>(payer.received(byteArrayOf(0x04, 1, 2, 3)), "an offer before any key")
        expect<Step.Stop>(receiver.received(byteArrayOf(0x05, 1, 2, 3)), "a payment before any key")
        expect<Step.Stop>(receiver.received(byteArrayOf(0x63, 1, 2)), "a kind nobody knows")
        expect<Step.Stop>(receiver.received(ByteArray(0)), "nothing at all")
        expect<Step.Stop>(receiver.received(byteArrayOf(0x01, TapCrypto.VERSION.toByte(), 1, 2)), "a promise of the wrong size")
        expect<Step.Stop>(receiver.received(byteArrayOf(0x01)), "a promise with nothing in it")
        assertEquals(Step.Stop("a commit where it does not belong"), payer.received(byteArrayOf(0x01, 3)))
        assertEquals(Step.Stop("a changeTaken where it does not belong"), payer.received(byteArrayOf(0x08, 3)))
    }

    @Test
    fun eachSideSaysItsVersionFirst() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        val m1 = expect<Step.Send>(payer.begin(), "no promise").bytes
        assertEquals(listOf<Byte>(0x01, TapCrypto.VERSION.toByte()), m1.take(2))
        assertEquals(2 + 32, m1.size)
        val m2 = expect<Step.Send>(receiver.received(m1), "no hello").bytes
        assertEquals(listOf<Byte>(0x02, TapCrypto.VERSION.toByte()), m2.take(2))
        assertEquals(2 + 32 + TapCrypto.NONCE_LENGTH, m2.size)
        assertEquals("a receiver does not speak first", Step.Waiting, receiver.begin())
    }

    @Test
    fun aPhoneFromBeforeTheVersionIsToldApart() {
        val service = TapCrypto.newService()
        val receiver = receiver(service)
        assertEquals(Step.OtherVersion(2, null), receiver.received(byteArrayOf(0x01) + ByteArray(32) { 7 }))
        assertNull("and no key was agreed with it", receiver.code)
        val payer = payer(service)
        payer.begin()
        assertEquals(Step.OtherVersion(2, null), payer.received(byteArrayOf(0x02) + ByteArray(32 + TapCrypto.NONCE_LENGTH) { 7 }))
        assertNull(payer.code)
    }

    @Test
    fun anotherVersionIsAnsweredWithThisOne() {
        val service = TapCrypto.newService()
        val receiver = receiver(service)
        val newer = TapCrypto.VERSION + 1
        val said = receiver.received(byteArrayOf(0x01, newer.toByte()) + ByteArray(40) { 7 })
        assertEquals(Step.OtherVersion(newer, byteArrayOf(0x02, TapCrypto.VERSION.toByte())), said)
        assertNull(receiver.code)
        val payer = payer(service)
        payer.begin()
        assertEquals(Step.OtherVersion(newer, null), payer.received(byteArrayOf(0x02, newer.toByte())))
        assertNull(payer.code)
    }

    @Test
    fun aSealedMessageRelabelledOnTheAirStopsTheLink() {
        val service = TapCrypto.newService()
        val payer = payer(service)
        val receiver = receiver(service)
        linked(payer, receiver)
        expect<Step.Offer>(payer.received(receiver.sealOffer("{\"v\":2,\"req\":\"creqA\"}")!!), "no offer")
        val asked = payer.sealAgain()!!
        assertEquals(0x0C.toByte(), asked[0])
        asked[0] = 0x08
        val why = expect<Step.Stop>(receiver.received(asked), "a question relabelled as a receipt for change was believed").why
        assertTrue(why, why.contains("would not open"))
    }

    @Test
    fun whatGoesOverTheWireIsAlwaysTheSameShape() {
        fun linkedPair(): Pair<TapSession, TapSession> {
            val service = TapCrypto.newService()
            val p = payer(service)
            val r = receiver(service)
            linked(p, r)
            return p to r
        }
        val small = linkedPair().second.sealOffer("{\"v\":2}")!!
        val longer = linkedPair().second.sealOffer("x".repeat(900))!!
        assertEquals("an offer is one size whatever is in it", small.size, longer.size)
        val few = linkedPair().first.sealPayment("a".repeat(300))!!
        val many = linkedPair().first.sealPayment("a".repeat(3000))!!
        assertEquals("payments inside one block are the same size", few.size, many.size)
        val lots = linkedPair().first.sealPayment("a".repeat(5000))!!
        assertTrue("a much larger payment is a larger bucket", lots.size > few.size)
    }

    /** Text that is not UTF-8 is refused, never read with a replacement character. */
    @Test
    fun onlyUtf8IsText() {
        assertEquals("₿", TapSession.utf8("₿".toByteArray()))
        assertNull(TapSession.utf8(byteArrayOf(0xc3.toByte())))
        assertNull(TapSession.utf8(byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())))
    }

    /** The diary says each thing once, and never the offer or the money. */
    @Test
    fun theDiaryHoldsNoSecrets() {
        val lines = ArrayList<String>()
        val service = TapCrypto.newService()
        val payer = TapSession(Role.PAYER, service) { lines.add(it) }
        val receiver = TapSession(Role.RECEIVER, service) { lines.add(it) }
        expect<Step.Linked>(linked(payer, receiver), "not linked")
        val offer = "{\"v\":2,\"req\":\"creqAabcdefghijklmnop\"}"
        payer.received(receiver.sealOffer(offer)!!)
        receiver.received(payer.sealPayment("cashuBsecretproofs")!!)
        receiver.received(payer.sealPayment("cashuBsecretproofs")!!)
        assertTrue(lines.isNotEmpty())
        assertEquals("each line once", lines.size, lines.toSet().size)
        for (l in lines) {
            assertTrue(l, l.startsWith("[tap] "))
            assertTrue(l, !l.contains("creqA") && !l.contains("cashuB"))
        }
    }
}
