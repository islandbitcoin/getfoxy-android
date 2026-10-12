package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.tap.TapProximity.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * Tap to pay without a radio: the code, the offer check, the framing, the
 * proximity rule, change handed over, progress, the receiver's one place and a
 * write that failed. FoxyTests/TapProtocolTests.swift and its TapPlaceTests,
 * ported.
 */
class TapProtocolTest {

    private val invoice = "lnbc10n1pn9xyzpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq"

    @Test
    fun codeIsFourDigitsAndFixed() {
        val nonce = ByteArray(16) { 7 }
        val a = TapProtocol.code(invoice, nonce)
        assertEquals(4, a.length)
        assertTrue(a.all { it in '0'..'9' })
        assertEquals(a, TapProtocol.code(invoice, nonce))
    }

    @Test
    fun codeDependsOnPayloadAndNonce() {
        var differ = 0
        for (i in 0 until 50) {
            val n1 = ByteArray(16) { ((it + i) and 0xff).toByte() }
            val n2 = ByteArray(16) { ((it + i + 1) and 0xff).toByte() }
            if (TapProtocol.code(invoice, n1) != TapProtocol.code(invoice, n2)) differ++
            if (TapProtocol.code(invoice, n1) != TapProtocol.code(invoice + "q", n1)) differ++
        }
        assertTrue(differ > 95)
    }

    @Test
    fun codeMatchesTheFormula() {
        val nonce = ByteArray(16) { it.toByte() }
        val msg = "foxy tap v1".toByteArray() + byteArrayOf(0) + "lnbc1test".toByteArray() + nonce
        val d = MessageDigest.getInstance("SHA-256").digest(msg)
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        assertEquals("%04d".format(n % 10000), TapProtocol.code("lnbc1test", nonce))
    }

    @Test
    fun onlyAWellFormedOfferPasses() {
        val req = "creqApGF0gaNhdGVub3N0cmFheDQ3YWJjZGVmMTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTBhYmNk"
        assertTrue(TapProtocol.isPayload("{\"v\":2,\"inv\":\"$invoice\"}"))
        assertTrue(TapProtocol.isPayload("{\"v\":2,\"req\":\"$req\"}"))
        assertTrue(TapProtocol.isPayload("{\"v\":2,\"inv\":\"$invoice\",\"req\":\"$req\"}"))
        assertFalse(TapProtocol.isPayload("{\"v\":2}"))
        assertTrue(TapProtocol.isPayload("{\"v\":2,\"req\":\"$req=\"}"))
        assertTrue(TapProtocol.isPayload("{\"v\":2,\"req\":\"$req==\"}"))
        assertTrue(TapProtocol.isRequest("$req="))
        assertFalse(TapProtocol.isPayload("{\"v\":2,\"inv\":\"bc1qxy2kgdygjrsqtzq2n0\"}"))
        assertFalse(TapProtocol.isPayload("{\"v\":2,\"req\":\"cashuBo2F0gaJhaUgA\"}"))
        assertFalse(TapProtocol.isPayload("{\"v\":3,\"inv\":\"$invoice\"}"))
        assertFalse(TapProtocol.isPayload(invoice))
        assertFalse(TapProtocol.isPayload("{"))
        assertFalse(TapProtocol.isPayload("{\"v\":2,\"inv\":\"lnbc10n1" + "q".repeat(5000) + "\"}"))
    }

    @Test
    fun codeCoversTheRequestHalfAsWell() {
        val nonce = ByteArray(16) { 3 }
        val a = "{\"v\":2,\"inv\":\"$invoice\",\"req\":\"creqAaaaaaaaaaaaaaaaaaaa\"}"
        val b = "{\"v\":2,\"inv\":\"$invoice\",\"req\":\"creqAbbbbbbbbbbbbbbbbbbbb\"}"
        assertNotEquals(TapProtocol.code(a, nonce), TapProtocol.code(b, nonce))
    }

    @Test
    fun onlyInvoicesPass() {
        assertTrue(TapProtocol.isInvoice(invoice))
        assertTrue(TapProtocol.isInvoice(invoice.uppercase()))
        assertFalse(TapProtocol.isInvoice("cashuBo2F0gaJhaUgA"))
        assertFalse(TapProtocol.isInvoice("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        assertFalse(TapProtocol.isInvoice("lnbc10n1" + "q".repeat(3000)))
        assertFalse(TapProtocol.isInvoice("lnbc10n1pn9<script>"))
        assertFalse(TapProtocol.isInvoice("lnbc10n1pN9xyz" + "q".repeat(40)))
    }

    private fun feedAll(a: TapProtocol.Assembler, frame: ByteArray, size: Int): TapProtocol.Assembler.Result {
        var got: TapProtocol.Assembler.Result = TapProtocol.Assembler.Result.More
        for (piece in chunks(frame, size)) got = a.add(piece)
        return got
    }

    private fun chunks(b: ByteArray, size: Int): List<ByteArray> = (b.indices step size).map { b.copyOfRange(it, minOf(it + size, b.size)) }

    @Test
    fun framingRoundTripsInPieces() {
        val offer = "{\"v\":2,\"inv\":\"$invoice\"}"
        val frame = TapProtocol.frame(offer)!!
        for (size in listOf(1, 20, 182, 500)) {
            assertEquals("pieces of $size", TapProtocol.Assembler.Result.Done(offer), feedAll(TapProtocol.Assembler(), frame, size))
        }
    }

    @Test
    fun framingRefusesTooLongOrNotAnOffer() {
        val offer = "{\"v\":2,\"inv\":\"$invoice\"}"
        assertEquals(TapProtocol.Assembler.Result.Bad, TapProtocol.Assembler().add(byteArrayOf(0x20, 0x00)))
        assertEquals(TapProtocol.Assembler.Result.Bad, TapProtocol.Assembler().add(TapProtocol.frame("cashuBo2F0gaJhaUgAAAAAAAAAAAAA")!!))
        assertEquals(TapProtocol.Assembler.Result.Bad, TapProtocol.Assembler().add(TapProtocol.frame(offer)!! + byteArrayOf(1)))
    }

    @Test
    fun aPaymentRoundTripsInPieces() {
        val body = "{\"proofs\":[" + List(400) { "{\"amount\":1}" }.joinToString(",") + "]}"
        val frame = TapProtocol.frame(body)!!
        assertTrue("longer than an offer may be", frame.size > 4096)
        for (size in listOf(20, 182, 512)) {
            assertEquals("pieces of $size", TapProtocol.Assembler.Result.Done(body), feedAll(TapProtocol.Assembler.forPayment(), frame, size))
        }
    }

    @Test
    fun aPaymentLargerThanTheCapIsRefused() {
        assertEquals("65535 announced, past the cap", TapProtocol.Assembler.Result.Bad, TapProtocol.Assembler.forPayment().add(byteArrayOf(-1, -1)))
        assertTrue("a cap the two-byte length cannot reach is not a cap", TapProtocol.MAX_PAYMENT < 0xffff)
        assertEquals("nothing announced", TapProtocol.Assembler.Result.Bad, TapProtocol.Assembler.forPayment().add(byteArrayOf(0, 0)))
        assertEquals(TapProtocol.Bytes.Result.Bad, TapProtocol.Bytes().add(byteArrayOf(-1, -1)))
        assertEquals(TapProtocol.Bytes.Result.Bad, TapProtocol.Bytes().add(byteArrayOf(0, 0)))
    }

    @Test
    fun theOfferDoorStaysNarrow() {
        assertEquals("8192 announced at the offer door", TapProtocol.Assembler.Result.Bad, TapProtocol.Assembler().add(byteArrayOf(0x20, 0x00)))
    }

    @Test
    fun bytesComeBackUnchangedAndTheNextMessageStartsClean() {
        val b = TapProtocol.Bytes()
        val one = byteArrayOf(0x04) + ByteArray(300) { it.toByte() }
        val frame = TapProtocol.framed(one)!!
        var got: TapProtocol.Bytes.Result = TapProtocol.Bytes.Result.More
        for (p in TapProtocol.pieces(frame, 20)) got = b.add(p)
        assertEquals(TapProtocol.Bytes.Result.Done(one), got)
        assertEquals(0, b.buffer.size)
        assertEquals(TapProtocol.Bytes.Result.Bad, b.add(TapProtocol.framed(byteArrayOf(1))!! + byteArrayOf(9)))
        assertNull("a frame the two bytes cannot say", TapProtocol.framed(ByteArray(0x10000)))
        assertEquals(0x10001, TapProtocol.framed(ByteArray(0xffff))!!.size)
    }

    @Test
    fun aFrameIsCutIntoPiecesOfAtLeastTwentyBytes() {
        val frame = ByteArray(100) { it.toByte() }
        assertEquals(5, TapProtocol.pieces(frame, 3).size)
        assertEquals(listOf(100), TapProtocol.pieces(frame, 512).map { it.size })
        assertEquals(listOf(40, 40, 20), TapProtocol.pieces(frame, 40).map { it.size })
        assertTrue(TapProtocol.pieces(ByteArray(0), 20).isEmpty())
    }

    // MARK: proximity

    private val a = "a"
    private val b = "b"

    private fun feed(p: TapProximity, id: String, dbm: Int, from: Double, to: Double) {
        var t = from
        while (t <= to + 1e-9) {
            p.add(id, dbm, t)
            t += 0.05
        }
    }

    @Test
    fun nothingHeard() = assertEquals(Verdict.Nothing, TapProximity().verdict(10.0))

    @Test
    fun closeForLongEnoughIsNear() {
        val p = TapProximity()
        feed(p, a, -30, 0.0, 0.1)
        assertEquals("not held long enough yet", Verdict.Far(-30), p.verdict(0.1))
        feed(p, a, -30, 0.15, 0.4)
        assertEquals(Verdict.Near(a, -30), p.verdict(0.4))
    }

    @Test
    fun aReflectionAmongWeakReadingsIsNotATap() {
        val p = TapProximity()
        feed(p, a, -62, 0.0, 0.4)
        p.add(a, -30, 0.41)
        assertEquals(Verdict.Far(-62), p.verdict(0.41))
    }

    @Test
    fun twoFeetIsRefusedAndTouchingIsNot() {
        for (dbm in listOf(-52, -54)) {
            val p = TapProximity()
            feed(p, a, dbm, 0.0, 0.5)
            assertEquals("apart at $dbm should be refused", Verdict.Far(dbm), p.verdict(0.5))
        }
        for (dbm in listOf(-26, -30)) {
            val p = TapProximity()
            feed(p, a, dbm, 0.0, 0.5)
            assertEquals("touching at $dbm should connect", Verdict.Near(a, dbm), p.verdict(0.5))
        }
    }

    @Test
    fun aLinkReadingCannotOutvoteTheAdvertisement() {
        val p = TapProximity()
        p.add(a, -52, 0.0)
        p.addLinked(a, -37, 3.0)
        p.addLinked(a, -37, 3.6)
        if (p.verdict(3.7) is Verdict.Near) fail("a link reading decided on its own against a -52 advertisement")
        val q = TapProximity()
        q.add(a, -30, 0.0)
        q.addLinked(a, -29, 3.0)
        q.addLinked(a, -29, 3.6)
        assertEquals(Verdict.Near(a, -29), q.verdict(3.7))
        assertTrue(q.decidedOnLink)
        assertEquals(q.nearLinkedDbm, q.lastThreshold)
    }

    @Test
    fun aFreshAdvertisementLetsTheLinkConfirm() {
        val p = TapProximity()
        p.add(a, -58, 0.0)
        p.add(a, -30, 2.4)
        p.addLinked(a, -29, 2.5)
        p.addLinked(a, -29, 3.1)
        feed(p, a, -30, 2.4, 3.1)
        assertEquals(Verdict.Near(a, -30), p.verdict(3.1))
    }

    @Test
    fun acrossTheRoomIsFar() {
        val p = TapProximity()
        feed(p, a, -70, 0.0, 1.0)
        assertEquals(Verdict.Far(-70), p.verdict(1.0))
    }

    @Test
    fun oneSpikeIsNotEnough() {
        val p = TapProximity()
        feed(p, a, -65, 0.0, 0.8)
        p.add(a, -30, 0.81)
        assertEquals(Verdict.Far(-65), p.verdict(0.81))
    }

    @Test
    fun twoPhonesAsCloseTheCloserWinsAfterASecond() {
        val p = TapProximity()
        feed(p, a, -30, 0.0, 0.8)
        feed(p, b, -34, 0.0, 0.8)
        if (p.verdict(0.8) is Verdict.Near) fail("decided before a second had passed")
        feed(p, a, -30, 0.8, 1.1)
        feed(p, b, -34, 0.8, 1.1)
        assertEquals(Verdict.Near(a, -30), p.verdict(1.1))
    }

    @Test
    fun anotherReceiverPlainlyNearerIsARival() {
        val p = TapProximity()
        feed(p, a, -57, 0.0, 1.2)
        feed(p, b, -40, 0.0, 0.6)
        assertNull("not before it has been heard for a second", p.rival(a, 0.6))
        feed(p, b, -40, 0.6, 1.2)
        assertEquals(TapProximity.Rival(b, -40, -57), p.rival(a, 1.2))
        assertNull("and the nearer one has no rival in the other", p.rival(b, 1.2))
    }

    @Test
    fun twoReceiversThatReadAlikeKeepTheLinkWhereItIs() {
        val p = TapProximity()
        feed(p, a, -44, 0.0, 1.2)
        feed(p, b, -40, 0.0, 1.2)
        assertNull(p.rival(a, 1.2))
    }

    @Test
    fun aReceiverTooFarToBeToldAboutIsNoRival() {
        val p = TapProximity()
        feed(p, a, -75, 0.0, 1.2)
        feed(p, b, -60, 0.0, 1.2)
        assertNull(p.rival(a, 1.2))
    }

    @Test
    fun aLinkedReceiverNoLongerHeardIsMeasuredByWhatIsLeft() {
        val p = TapProximity()
        p.add(a, -42, 0.0)
        feed(p, b, -40, 2.5, 3.7)
        assertNull("its last advertisement still stands, and they read alike", p.rival(a, 3.7))
        p.addLinked(a, -62, 9.0)
        p.addLinked(a, -62, 9.6)
        feed(p, b, -40, 8.5, 9.7)
        assertEquals("once that is old, the link's own reading says it is the far one", b, p.rival(a, 9.7)?.id)
    }

    @Test
    fun aClearlyCloserPhoneWins() {
        val p = TapProximity()
        feed(p, a, -30, 0.0, 0.8)
        feed(p, b, -62, 0.0, 0.8)
        assertEquals(Verdict.Near(a, -30), p.verdict(0.8))
    }

    @Test
    fun wellBeyondTheThresholdIsFar() {
        val p = TapProximity()
        feed(p, a, -62, 0.0, 0.8)
        assertEquals(Verdict.Far(-62), p.verdict(0.8))
    }

    @Test
    fun edgesFollowTheMeasuredPairs() {
        val xs = "iPhone11,2"
        val p15 = "iPhone16,2"
        val p17 = "iPhone18,1"
        for ((payer, receiver) in listOf(p17 to xs, p17 to p15, xs to p15, p15 to xs)) {
            assertEquals("$payer paying $receiver", TapEdges.Edges("left", "right"), TapEdges.edges(payer, receiver))
        }
        for (payer in listOf(xs, p15)) {
            assertEquals("$payer paying a 17 Pro", TapEdges.Edges("right", "left"), TapEdges.edges(payer, p17))
        }
        for (receiver in listOf("iPhone18,2", "iPhone19,2", "iPhone19,3", "iPhone19,7")) {
            assertEquals(receiver, "left", TapEdges.edges(xs, receiver).receiver)
        }
        assertEquals("right", TapEdges.edges(xs, "iPhone14,5").receiver)
        assertEquals("left", TapEdges.edges(xs, "").payer)
        assertEquals("an Android receiver nobody has measured takes the default", "right", TapEdges.edges(xs, "Pixel 8 Pro").receiver)
    }

    @Test
    fun weakerThanTouchingIsNotATap() {
        for (dbm in listOf(-42, -46, -48)) {
            val p = TapProximity()
            feed(p, a, dbm, 0.0, 0.8)
            assertEquals("weaker than touching at $dbm", Verdict.Far(dbm), p.verdict(0.8))
        }
    }

    @Test
    fun clearlyApartOnAnyPairIsNot() {
        for (dbm in listOf(-52, -54, -60)) {
            val p = TapProximity()
            feed(p, a, dbm, 0.0, 0.8)
            assertEquals("apart at $dbm", Verdict.Far(dbm), p.verdict(0.8))
        }
    }

    @Test
    fun pressedTogetherIsATap() {
        val p = TapProximity()
        feed(p, a, -30, 0.0, 0.8)
        assertEquals(Verdict.Near(a, -30), p.verdict(0.8))
    }

    @Test
    fun warmingReachesFurtherThanATap() {
        val p = TapProximity()
        feed(p, a, -60, 0.0, 0.1)
        assertEquals("not a tap", Verdict.Far(-60), p.verdict(0.1))
        assertEquals("but worth opening a link to", a, p.warmest(0.1))
    }

    @Test
    fun warmingDoesNotReachAcrossARoom() {
        val p = TapProximity()
        feed(p, a, -80, 0.0, 0.5)
        assertNull(p.warmest(0.5))
    }

    @Test
    fun oneReadingDoesNotWarm() {
        val p = TapProximity()
        p.add(a, -30, 0.0)
        assertNull(p.warmest(0.0))
        p.add(a, -30, 0.05)
        assertEquals(a, p.warmest(0.05))
    }

    @Test
    fun warmingPicksTheStrongest() {
        val p = TapProximity()
        feed(p, a, -62, 0.0, 0.2)
        feed(p, b, -30, 0.0, 0.2)
        assertEquals(b, p.warmest(0.2))
    }

    @Test
    fun readingsFromAnySourceDecideTogether() {
        val p = TapProximity()
        feed(p, a, -60, 0.0, 0.2)
        assertEquals(Verdict.Far(-60), p.verdict(0.2))
        feed(p, a, -30, 0.25, 0.6)
        assertEquals(Verdict.Near(a, -30), p.verdict(0.6))
        assertEquals(-30, p.lastHeard(a))
    }

    @Test
    fun aPairOfCloseReadingsIsATap() {
        val p = TapProximity()
        p.add(a, -26, 0.0)
        p.add(a, -30, 1.0)
        assertEquals("the weaker of the two", Verdict.Near(a, -30), p.verdict(1.0))
        val q = TapProximity()
        q.add(a, -30, 0.0)
        q.add(a, -52, 1.0)
        assertEquals("the weaker of the two, and it is apart", Verdict.Far(-52), q.verdict(1.0))
    }

    @Test
    fun aPairWithOneReflectionIsNot() {
        val p = TapProximity()
        p.add(a, -70, 0.0)
        p.add(a, -30, 1.0)
        assertEquals("one lucky reading cannot decide", Verdict.Far(-70), p.verdict(1.0))
    }

    @Test
    fun theMedianIsUnchangedForOddCounts() {
        assertEquals(-50, TapProximity.median(listOf(-70, -50, -30)))
        assertEquals(-50, TapProximity.median(listOf(-90, -70, -50, -30, -10)))
        assertEquals("the weaker of an even pair", -70, TapProximity.median(listOf(-40, -70)))
        assertEquals(-127, TapProximity.median(emptyList()))
    }

    @Test
    fun oldReadingsAreForgotten() {
        val p = TapProximity()
        feed(p, a, -30, 0.0, 0.8)
        assertEquals(Verdict.Nothing, p.verdict(5.0))
        p.forget()
        assertNull(p.lastHeard(a))
    }

    @Test
    fun aSlowSourceStillReachesAVerdict() {
        val p = TapProximity()
        var t = 0.0
        while (t <= 1.8) {
            p.add(a, -30, t)
            t += 0.6
        }
        assertEquals(Verdict.Near(a, -30), p.verdict(1.8))
    }

    @Test
    fun theVerdictSaysWhatItWasShortOf() {
        val p = TapProximity()
        feed(p, a, -70, 0.0, 0.8)
        p.verdict(0.8)
        assertEquals("too far", p.why)
        val q = TapProximity()
        q.add(a, -30, 0.0)
        q.verdict(0.0)
        assertEquals("only 1 reading(s)", q.why)
        val r = TapProximity()
        r.add(a, -30, 0.0)
        r.add(a, -30, 0.1)
        r.verdict(0.1)
        assertEquals("heard for 0.10s", r.why)
    }

    @Test
    fun aSlowSourceIsStillForgotten() {
        val p = TapProximity()
        var t = 0.0
        while (t <= 1.8) {
            p.add(a, -30, t)
            t += 0.6
        }
        assertEquals(Verdict.Nothing, p.verdict(6.0))
    }

    @Test
    fun noReadingIsIgnored() {
        val p = TapProximity()
        feed(p, a, 127, 0.0, 1.0)
        assertEquals(Verdict.Nothing, p.verdict(1.0))
        p.add(a, -120, 1.0)
        assertEquals("-120 is no reading either", Verdict.Nothing, p.verdict(1.0))
    }

    // MARK: change handed over, and progress

    @Test
    fun changeIsGivenBackOnlyOnThePayersWord() {
        val prompt = ChangeHandover()
        assertEquals("the payer said so in time", ChangeHandover.Word.KEPT, prompt.heard(true))
        assertNull("a clock running out afterwards is nothing", prompt.heard(false))
        assertEquals("a second word is heard as late", ChangeHandover.Word.LATE, prompt.heard(true))

        val slow = ChangeHandover()
        assertFalse(slow.answered)
        assertEquals("the wait ran out: the page shows the code", ChangeHandover.Word.NOT_HANDED, slow.heard(false))
        assertTrue(slow.answered)
        assertNull("the payer going afterwards changes nothing", slow.heard(false))
        assertEquals("a payer that kept it after all takes the code down", ChangeHandover.Word.LATE, slow.heard(true))

        val gone = ChangeHandover()
        assertEquals(ChangeHandover.Word.NOT_HANDED, gone.heard(false))
        assertNull(gone.heard(false))
    }

    @Test
    fun progressIsSaidEveryFourPointsAndNoMoreOften() {
        val p = TapProgress()
        assertEquals("the first word is said at once", 0, p.step(0, 200))
        assertNull("two points is not enough", p.step(4, 200))
        assertEquals(4, p.step(8, 200))
        assertNull("it does not go back", p.step(6, 200))
        assertEquals(100, p.step(200, 200))
        assertNull("and is not said twice", p.step(200, 200))

        val r = TapProgress()
        assertEquals("the receiver never says 100 before the payment is whole", 99, r.step(500, 500, cap = 99))
        assertNull("nothing to measure against says nothing", r.step(1, 0))

        var said = 0
        val many = TapProgress()
        for (i in 0..1000) if (many.step(i, 1000) != null) said++
        assertEquals("a thousand writes are twenty-six words to the page", 26, said)
    }

    @Test
    fun aMessageInHandSaysHowFarItHasGot() {
        val b = TapProtocol.Bytes()
        assertNull(b.progress)
        assertEquals(TapProtocol.Bytes.Result.More, b.add(byteArrayOf(0x03)))
        assertNull("one byte is not yet a length", b.progress)
        assertEquals(TapProtocol.Bytes.Result.More, b.add(byteArrayOf(0xE8.toByte(), 0x05, 0x01)))
        assertEquals(TapProtocol.Bytes.Progress(4, 1002, 0x05), b.progress)
        assertTrue(TapSession.carriesPayment(b.progress?.kind))
        assertTrue("a part of a payment is payment too", TapSession.carriesPayment(0x0D))
        assertFalse("a result is not", TapSession.carriesPayment(0x06))
        assertFalse(TapSession.carriesPayment(null))
    }

    // MARK: the receiver's one place, and a word that would not go

    @Test
    fun theFirstWordTakesThePlaceAndASubscribeDoesNot() {
        assertTrue(TapPlace.taken(a, null, listening = true, linked = false, early = false))
        assertFalse("a word from a phone that never subscribed takes nothing", TapPlace.taken(a, null, false, false, false))
        assertFalse("one payer at a time: the place is held", TapPlace.taken(b, a, true, false, false))
        assertFalse("the holder's later words are not a second taking", TapPlace.taken(a, a, true, false, false))
        assertFalse(TapPlace.taken(a, null, true, true, false))
        assertFalse("nobody while the amount is still being typed", TapPlace.taken(a, null, true, false, true))
    }

    @Test
    fun theRestOfTheHandshakeHasThreeSeconds() {
        assertEquals("a real handshake is a few hundred milliseconds", 3.0, TapReceiverLink.HANDSHAKE_WAIT, 0.0)
    }

    @Test
    fun aWordThatDidNotGoLetsTheLinkGoUnlessItIsOneOfThree() {
        assertEquals(TapWriteFailure.LET_LINK_GO, TapWriteFailure.after(question = false, payment = false, changeKept = false))
        assertEquals(TapWriteFailure.WAIT_FOR_ANSWER, TapWriteFailure.after(question = true, payment = true, changeKept = false))
        assertEquals(TapWriteFailure.PAYMENT_LOST, TapWriteFailure.after(question = false, payment = true, changeKept = false))
        assertEquals(TapWriteFailure.NOTHING_MORE, TapWriteFailure.after(question = false, payment = false, changeKept = true))
    }

    /** The page's words for a stage, as TapLink.swift's raw values. */
    @Test
    fun theStagesAreThePagesWords() {
        assertEquals(
            listOf(
                "searching", "far", "connecting", "linked", "sending", "receiving", "code", "asking",
                "gone", "shake", "nearby", "version", "off", "denied", "unsupported",
            ),
            TapStage.entries.map { it.word },
        )
        assertEquals(io.islandbitcoin.foxy.bridge.TapRules.UNSUPPORTED, TapStage.UNSUPPORTED.word)
    }
}
