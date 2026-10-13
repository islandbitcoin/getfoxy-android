package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * ENG-671: the paying phone ([TapPayer] over a GATT client) end to end against
 * Android's own receiver ([TapReceiver], its GATT server), both sides at the
 * ATT level ([PayerAir]): the scan and what counts as a Foxy, the signal it
 * takes to connect, the quiet link and the near doors, the handshake, every
 * message after it (the offer, M10 quote, M11 terms, M9 asking, the payment in
 * one part and in many, M6, change and M8, M12 after Foxy was away), and the
 * ways a link ends: dropped, never opened, never answered, Bluetooth off, Foxy
 * put away.
 */
class TapPayerTest {
    private fun TapPayerLink.Handover?.answer(): Map<*, *> {
        val a = this as? TapPayerLink.Handover.Answered ?: error("not answered: $this")
        return Json.parse(a.text) as Map<*, *>
    }

    /** A payer and one receiver on the air at [rssi] dBm, listening. */
    private fun listening(rssi: Int = -30, payload: String = TapAir.OFFER): Pair<PayerAir, AirReceiver> {
        val air = PayerAir()
        val r = air.receiver("AA:01", payload)
        r.rssi = rssi
        r.onAir()
        air.payer.start()
        air.settle()
        return air to r
    }

    // MARK: a whole tap

    @Test
    fun aPhoneHeldToAReceiverFindsItLinksAndGetsTheOfferAndTheSameFourDigits() {
        val (air, r) = listening()
        assertTrue("listening from the start", air.c.scanning)
        air.hear(3.0)
        val (offer, code) = air.offer ?: error("no offer: ${air.diary.joinToString("\n")}")
        assertEquals(TapAir.OFFER, offer)
        assertTrue(Regex("^[0-9]{4}$").matches(code))
        assertEquals("both screens show the same four digits", code, r.events.first { it.first == TapStage.CODE }.second)
        assertEquals(listOf(TapStage.CONNECTING, TapStage.LINKED), air.stages.filter { it == TapStage.CONNECTING || it == TapStage.LINKED })
        assertTrue(air.payer.holding)
        assertFalse("the offer is in: no more scanning", air.c.scanning)
        assertEquals("one link, to the receiver it came for", listOf("AA:01"), air.c.connects)
        // the receiver heard the near door first (CONNECT TO PAY on its screen), then the payer's first word
        assertEquals(listOf(TapStage.SEARCHING, TapStage.NEARBY, TapStage.CONNECTING, TapStage.CODE), r.stages)
        assertEquals("right", r.events.first { it.first == TapStage.NEARBY }.second)
        // the out door was subscribed with a CCCD write, after the near door
        val cccds = air.c.requests.filter { it.startsWith("cccd") }
        assertEquals(TapGatt.Layout(r.receiver.link.service).nearRight.uuid.toString().take(8), cccds[0].split(" ")[1])
        assertEquals(TapGatt.Layout(r.receiver.link.service).out.uuid.toString().take(8), cccds[1].split(" ")[1])
    }

    @Test
    fun theWholeConversationQuoteTermsAskingPaymentAnswerAndChangeKept() {
        val (air, r) = listening()
        air.hear(3.0)
        assertNotNull(air.offer)

        // M10: this phone's price
        assertTrue(air.payer.sendQuote("{\"rate\":1}"))
        air.hear(0.5)
        assertEquals(listOf("{\"rate\":1}"), r.quotes)
        // M11: the receiver agrees and sends the request
        assertTrue(r.receiver.sendTerms("{\"v\":2,\"req\":\"creqAterms0123456789abcdef\"}"))
        air.hear(0.5)
        assertEquals(listOf("{\"v\":2,\"req\":\"creqAterms0123456789abcdef\"}"), air.terms)

        // the payment: the receiver's page owes change, asks a person (M9), then answers 200 (M6)
        var answer: ((Int, String) -> Unit)? = null
        r.paid = { _, a ->
            r.receiver.expectChange()
            answer = a
        }
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[{\"amount\":64}]}") { how = it }
        air.hear(1.0)
        assertEquals(listOf("{\"proofs\":[{\"amount\":64}]}"), r.payments)
        assertTrue(r.receiver.sendAsking())
        air.hear(1.0)
        assertTrue("M9: the payer waits on a person", TapStage.ASKING in air.stages)
        assertNull(how)
        // the person takes longer than the payer's thirty seconds, inside its ninety-five
        air.hear(40.0)
        assertNull("still waiting on the person", how)
        answer!!(200, "")
        air.hear(1.0)
        assertEquals(true, how.answer()["ok"])

        // change back over the same link: the payer's page keeps it, and M8 says so
        var word: ChangeHandover.Word? = null
        assertTrue(r.receiver.handOverChange("cashuBchange") { word = it })
        air.hear(1.0)
        assertEquals(listOf("cashuBchange"), air.changes)
        assertEquals(ChangeHandover.Word.KEPT, word)
    }

    @Test
    fun aPaymentOfManyPartsGoesAsLongWritesWithItsSizeFirstAndArrivesWhole() {
        val (air, r) = listening()
        air.hear(3.0)
        assertNotNull(air.offer)
        // larger than one part (TapCrypto.PART_BODY) and than SAY_SIZE_ABOVE on the wire
        val body = "{\"proofs\":[" + (1..1400).joinToString(",") { "{\"amount\":$it,\"secret\":\"${"s".repeat(50)}\"}" } + "]}"
        assertTrue(body.length > TapCrypto.PART_BODY * 2)
        val how = air.pay(body, seconds = 120.0)
        assertEquals(true, how.answer()["ok"])
        assertEquals(listOf(body), r.payments)
        assertTrue("written as prepared writes and an execute", air.c.requests.any { it.startsWith("prepare") } && "execute" in air.c.requests)
        assertTrue("the payer said how far it had got", air.stages.count { it == TapStage.SENDING } >= 3)
        assertTrue("and the receiver too", r.stages.count { it == TapStage.RECEIVING } >= 3)
    }

    @Test
    fun atAnMtuOf23EverythingStillCrosses() {
        val air = PayerAir(mtu = 23)
        val r = air.receiver("AA:01")
        r.onAir()
        air.payer.start()
        air.hear(20.0)
        assertNotNull(air.offer)
        val how = air.pay("{\"proofs\":[{\"amount\":21}]}", seconds = 60.0)
        assertEquals(true, how.answer()["ok"])
        assertTrue(r.peripheral.notifications.all { it.third.size <= 20 })
    }

    @Test
    fun aRefusalIsTheReceiversWordAndTheReceiverIsLeftAloneAfterwards() {
        val (air, r) = listening()
        r.paid = { _, answer -> answer(422, "This request is already paid.") }
        air.hear(3.0)
        val how = air.pay("{\"proofs\":[]}")
        val a = how.answer()
        assertEquals(422.0, a["code"])
        assertEquals("This request is already paid.", a["why"])
    }

    // MARK: what counts as a Foxy, and how near

    @Test
    fun onlyAConnectableAdvertWithOneFull128BitServiceAndNoManufacturerDataIsAFoxy() {
        val svc = UUID.fromString("6f1c2a3b-4d5e-4f60-8172-a3b4c5d6e7f8")
        fun ad(connectable: Boolean = true, maker: Boolean = false, services: List<UUID> = listOf(svc), full: List<Boolean> = listOf(true)) =
            TapAdvert("X", connectable, maker, services, full, -30)
        assertEquals(svc, TapScanRules.service(ad()))
        assertNull("not connectable", TapScanRules.service(ad(connectable = false)))
        assertNull("manufacturer data (a Watch, AirPods, Continuity)", TapScanRules.service(ad(maker = true)))
        assertNull("no service", TapScanRules.service(ad(services = emptyList(), full = emptyList())))
        assertNull("two services", TapScanRules.service(ad(services = listOf(svc, svc), full = listOf(true, true))))
        val heart = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        assertNull("a 16-bit standard service is not a receiver's", TapScanRules.service(ad(services = listOf(heart), full = listOf(false))))
        assertTrue(TapScanRules.isShortForm(heart))
        assertFalse(TapScanRules.isShortForm(svc))
    }

    @Test
    fun theAdvertisementsBytesAreReadAsCoreBluetoothReadsThem() {
        val svc = UUID.fromString("10111213-1415-1617-1819-1a1b1c1d1e1f")
        // flags, then a complete list of 128-bit UUIDs (little-endian on the air)
        val le = ByteArray(16) { (0x1f - it).toByte() }
        val foxy = byteArrayOf(2, 0x01, 0x06, 17, 0x07) + le
        val read = TapAdBytes.read(foxy)
        assertEquals(listOf(svc), read.services)
        assertEquals(listOf(true), read.full)
        assertFalse(read.manufacturerData)
        // a 16-bit list (heart rate, battery) and manufacturer data (Apple's 0x004C)
        val other = byteArrayOf(5, 0x03, 0x0d, 0x18, 0x0f, 0x18, 4, 0xff.toByte(), 0x4c, 0x00, 0x10)
        val o = TapAdBytes.read(other)
        assertEquals(listOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"), UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")), o.services)
        assertEquals(listOf(false, false), o.full)
        assertTrue(o.manufacturerData)
        // a 32-bit UUID is short too
        val thirtyTwo = TapAdBytes.read(byteArrayOf(5, 0x05, 0x78, 0x56, 0x34, 0x12))
        assertEquals(listOf(UUID.fromString("12345678-0000-1000-8000-00805f9b34fb")), thirtyTwo.services)
        assertEquals(listOf(false), thirtyTwo.full)
        // a structure that runs past the end ends the read; a zero length ends it; null is nothing
        assertTrue(TapAdBytes.read(byteArrayOf(17, 0x07, 1, 2, 3)).services.isEmpty())
        assertTrue(TapAdBytes.read(byteArrayOf(0, 0x07) + le).services.isEmpty())
        assertTrue(TapAdBytes.read(null).services.isEmpty())
        // and read into an advert, it passes the rules
        assertEquals(svc, TapScanRules.service(TapAdvert("A", true, read.manufacturerData, read.services, read.full, -30)))
        assertNull(TapScanRules.service(TapAdvert("B", true, o.manufacturerData, o.services, o.full, -30)))
    }

    @Test
    fun aWatchAnHeadsetAndAStandardServiceNearbyAreNeverConnectedTo() {
        val air = PayerAir()
        air.payer.start()
        air.settle()
        val svc = UUID.randomUUID()
        air.noise.add(TapAdvert("WATCH", true, true, listOf(svc), listOf(true), -20))
        air.noise.add(TapAdvert("BEACON", false, false, listOf(svc), listOf(true), -20))
        air.noise.add(TapAdvert("HR", true, false, listOf(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")), listOf(false), -20))
        air.noise.add(TapAdvert("TWO", true, false, listOf(svc, UUID.randomUUID()), listOf(true, true), -20))
        air.hear(5.0)
        assertTrue("nothing that is not a Foxy is connected to", air.c.connects.isEmpty())
        assertTrue(air.stages.isEmpty())
        air.payer.stop()
        assertTrue(air.diary.any { it.contains("passed by 200 advertisement(s)") })
    }

    @Test
    fun aReceiverOutOfReachIsNeitherWarmedNorToldAndOneInTheRoomIsWarmedButNotTaken() {
        val (air, r) = listening(rssi = -70)
        air.hear(5.0)
        assertTrue("below warmDbm: no link at all", air.c.connects.isEmpty())
        assertTrue(air.stages.isEmpty())

        // across a table: a quiet link opens, nothing is said down it, and nothing connects
        r.rssi = -60
        r.linkRssi = -60
        air.hear(5.0)
        assertEquals(listOf("AA:01"), air.c.connects)
        assertTrue("the quiet link says nothing: no near door, no first word", air.c.requests.isEmpty())
        assertTrue(TapStage.CONNECTING !in air.stages)
        assertTrue("the receiver saw nobody", r.stages == listOf(TapStage.SEARCHING))
        assertNull(air.offer)
    }

    @Test
    fun nearEnoughToBeToldAboutIsTapToPayOnBothScreensButNotAConnection() {
        val (air, r) = listening(rssi = -45)
        r.linkRssi = -45
        air.hear(4.0)
        assertTrue("TAP TO PAY on the payer's screen", TapStage.NEARBY in air.stages)
        assertEquals("CONNECT TO PAY on the receiver's", TapStage.NEARBY, r.stages.getOrNull(1))
        assertTrue(TapStage.CONNECTING !in air.stages)
        assertEquals("left", air.payer.edge)
        // the phones parted: far, and the receiver's card comes down
        r.rssi = -62
        air.hear(5.0)
        assertTrue(TapStage.FAR in air.stages)
        assertEquals(TapStage.FAR, r.stages.last())
        assertNull(air.offer)
    }

    @Test
    fun tapToPayPressedTakesTheReceiverTheToastIsForAndOnlyForFiveSeconds() {
        val (air, r) = listening(rssi = -50)
        r.linkRssi = -50
        air.hear(3.0)
        assertTrue(TapStage.NEARBY in air.stages)
        assertNull(air.offer)
        val before = air.payer.proximity.nearDbm
        air.payer.connectNearby()
        assertEquals(TapProximity().nearbyDbm - 8, air.payer.proximity.nearDbm)
        air.hear(2.0)
        assertNotNull("taken without touching", air.offer)
        air.hear(4.0)
        assertEquals("and the touching rule is back", before, air.payer.proximity.nearDbm)
    }

    @Test
    fun aReceiverStillOnItsAmountScreenIsNotTakenUntilItSaysNext() {
        val (air, r) = listening()
        r.receiver.early = true
        air.hear(4.0)
        assertNull("touching, but the receiver is still typing an amount", air.offer)
        assertTrue(air.diary.any { it.contains("is still on its amount screen") })
        r.receiver.early = false
        air.hear(3.0)
        assertNotNull(air.offer)
    }

    @Test
    fun anIphone17ProReceiverIsHeldOnItsLeftEdgeAndThePayersMarkGoesRight() {
        val (air, r) = listening(rssi = -45)
        r.model = "iPhone18,1"
        r.linkRssi = -45
        air.hear(4.0)
        assertEquals("right", air.payer.edge)
        assertEquals("left", r.events.last { it.first == TapStage.NEARBY }.second)
        assertTrue(air.diary.any { it.contains("that phone is a iPhone18,1") })
        assertEquals("iPhone18,1", TapPayer.cleanModel("iPhone18,1\u0000 ".toByteArray()))
    }

    @Test
    fun aNearerReceiverTakesTheQuietLink() {
        val air = PayerAir()
        val a = air.receiver("AA:01").apply { rssi = -58; linkRssi = -58 }
        a.onAir()
        air.payer.start()
        air.hear(3.0)
        assertEquals(listOf("AA:01"), air.c.connects)
        val b = air.receiver("BB:02").apply { rssi = -40; linkRssi = -40 }
        b.onAir()
        air.hear(4.0)
        assertEquals("the quiet link moved to the nearer one", listOf("AA:01", "BB:02"), air.c.connects)
        assertTrue(air.diary.any { it.contains("another receiver is nearer") })
        assertFalse(air.c.isConnected("AA:01"))
    }

    // MARK: links that come to nothing

    @Test
    fun aLinkThatNeverOpensIsAskedForAfreshAfterTenSeconds() {
        val (air, _) = listening()
        air.c.neverAnswer.add("AA:01")
        air.hear(12.0)
        assertTrue(air.diary.any { it.contains("has not opened in 2 s; waiting for it") })
        assertTrue(air.diary.any { it.contains("has not opened in 10 s; asking again") })
        assertTrue("asked again", air.c.connects.size >= 2)
        // and once it opens, the tap goes through
        air.c.neverAnswer.clear()
        air.hear(15.0)
        assertNotNull(air.offer)
    }

    @Test
    fun aLinkSilentAboutItsStrengthTurnsWarmingOffAndTheTapStillHappens() {
        val (air, r) = listening(rssi = -45)
        r.linkRssi = null
        air.hear(4.0)
        assertTrue(air.diary.any { it.contains("the open link will not say how strong it is") })
        assertNull(air.offer)
        val links = air.c.connects.size
        // warming is off for the rest of the listen: no quiet link is opened again
        air.hear(3.0)
        assertEquals(links, air.c.connects.size)
        // held together, it is decided on advertisements and connected the long way
        r.rssi = -30
        air.hear(4.0)
        assertNotNull("decided on advertisements, the long way", air.offer)
    }

    @Test
    fun aReceiverThatNeverAnswersTheFirstWordIsGivenUpOnAfterEightSecondsAndLookedForAgain() {
        val (air, r) = listening()
        air.c.deaf.add("AA:01")
        air.hear(12.0)
        assertTrue(TapStage.CONNECTING in air.stages)
        val gone = air.stages.indexOf(TapStage.GONE)
        assertTrue("gone, then searching", gone >= 0 && air.stages[gone + 1] == TapStage.SEARCHING)
        assertTrue(air.diary.any { it.contains("gave up on that one (timed out)") })
        assertNull(air.offer)
        // and heard again, it is taken
        air.c.deaf.clear()
        air.hear(15.0)
        assertNotNull(air.offer)
        assertEquals("a second receiver link was made, not the first one reused", 1, air.offers)
        assertTrue(r.stages.count { it == TapStage.CONNECTING } >= 1)
    }

    @Test
    fun aConnectionThatFailsIsRetriedQuietly() {
        val (air, _) = listening()
        air.c.refuse.add("AA:01")
        air.hear(3.0)
        assertTrue(air.c.connects.size >= 2)
        assertTrue("a quiet link failing changes no screen", TapStage.GONE !in air.stages)
        air.c.refuse.clear()
        air.hear(5.0)
        assertNotNull(air.offer)
    }

    @Test
    fun aPhoneWithoutTheServiceIsNotAFoxyAndIsLeftAloneOnceItWasTouched() {
        val air = PayerAir()
        val r = air.receiver("AA:01")
        r.onAir()
        air.payer.start()
        air.settle()
        // the receiver's GATT server lost its service: an advert for it, a server without it
        r.peripheral.layout = null
        air.hear(3.0)
        assertNull(air.offer)
        assertTrue(air.diary.none { it.contains("gave up on that one") })
    }

    @Test
    fun theReceiverDroppingTheLinkMidPaymentIsSaidAtOnce() {
        val (air, r) = listening()
        air.hear(3.0)
        r.paid = { _, _ -> } // the page never answers
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[{\"amount\":8}]}") { how = it }
        air.hear(1.0)
        assertNull(how)
        air.c.drop("AA:01")
        air.settle()
        assertEquals(TapPayerLink.Handover.Lost("That phone went away before it said what became of the payment."), how)
        // and a payment after that has no link
        var next: TapPayerLink.Handover? = null
        air.payer.send("{}") { next = it }
        air.settle()
        assertEquals(TapPayerLink.Handover.Lost("That phone is no longer connected."), next)
    }

    @Test
    fun aDroppedQuietLinkIsLookedForAgainWithNothingOnScreen() {
        val (air, _) = listening(rssi = -58)
        air.hear(2.0)
        assertEquals(listOf("AA:01"), air.c.connects)
        air.c.drop("AA:01")
        air.hear(2.0)
        assertTrue(air.c.connects.size >= 2)
        assertTrue(air.stages.none { it == TapStage.GONE || it == TapStage.CONNECTING })
    }

    // MARK: the radio and Foxy away

    @Test
    fun noAdapterNoScannerOrNoCentralIsUnsupportedAndNothingIsAsked() {
        val setups = listOf<(FakeBluetooth) -> Unit>({ it.adapter = false }, { it.scans = false }, { it.centralFor = { null } })
        for (setup in setups) {
            val air = PayerAir()
            setup(air.bluetooth)
            air.payer.start()
            air.settle()
            assertEquals(listOf(TapStage.UNSUPPORTED), air.stages)
            assertEquals(0, air.bluetooth.scanAsks)
        }
    }

    @Test
    fun thePayersQuestionIsAskedOnceAndARefusalIsDeniedWithNoSecondDialog() {
        val air = PayerAir()
        air.bluetooth.scanGranted = false
        air.bluetooth.grantScanOnAsk = false
        air.payer.start()
        air.settle()
        assertEquals(1, air.bluetooth.scanAsks)
        assertEquals(listOf(TapStage.DENIED), air.stages)
        assertNull(air.central)

        val again = PayerAir()
        again.bluetooth.scanGranted = false
        again.bluetooth.keptScan.value = true
        again.payer.start()
        again.settle()
        assertEquals("refused before: denied, no dialog", 0, again.bluetooth.scanAsks)
        assertEquals(listOf(TapStage.DENIED), again.stages)

        val yes = PayerAir()
        yes.bluetooth.scanGranted = false
        yes.bluetooth.grantScanOnAsk = null
        yes.payer.start()
        assertTrue("nothing until answered", yes.stages.isEmpty())
        yes.bluetooth.answerScan(true)
        yes.settle()
        assertTrue(yes.c.scanning)
    }

    @Test
    fun bluetoothOffSaysOffAndTheListenWaitsForItToComeBack() {
        val air = PayerAir()
        val r = air.receiver("AA:01")
        r.onAir()
        air.bluetooth.powerNow = TapBluetooth.Power.OFF
        air.payer.start()
        air.settle()
        assertEquals(listOf(TapStage.OFF), air.stages)
        assertNull(air.central)
        air.bluetooth.setPower(TapBluetooth.Power.ON)
        air.hear(3.0)
        assertNotNull("back on, the listen goes on and finds it", air.offer)
    }

    @Test
    fun bluetoothTurnedOffMidPaymentLosesThePaymentAtOnce() {
        val (air, r) = listening()
        air.hear(3.0)
        r.paid = { _, _ -> }
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[]}") { how = it }
        air.hear(1.0)
        air.bluetooth.setPower(TapBluetooth.Power.OFF)
        air.settle()
        assertTrue(how is TapPayerLink.Handover.Lost)
        assertEquals(TapStage.OFF, air.stages.last())
        assertTrue(air.c.closed)
    }

    @Test
    fun nothingScansWhileFoxyIsAwayAndAQuietLinkIsLetGo() {
        val (air, _) = listening(rssi = -58)
        air.hear(2.0)
        assertTrue(air.c.isConnected("AA:01"))
        air.payer.foreground(false)
        assertFalse("no scan while away", air.c.scanning)
        assertFalse("and no quiet link", air.c.isConnected("AA:01"))
        val heard = air.c.connects.size
        air.hear(5.0)
        assertEquals("nothing was connected to while away", heard, air.c.connects.size)
        air.payer.foreground(true)
        assertTrue(air.c.scanning)
        assertEquals("the scan was started again, once", 2, air.c.scanStarts)
    }

    @Test
    fun aLinkHoldingTheOfferIsKeptWhileAwayAndAskedAgainOnTheWayBack() {
        val (air, r) = listening()
        air.hear(3.0)
        assertNotNull(air.offer)
        r.paid = { _, answer -> answer(200, "") }
        var how: TapPayerLink.Handover? = null
        air.payer.send("{\"proofs\":[{\"amount\":2}]}") { how = it }
        air.payer.foreground(false)
        // the answer is missed (iOS: suspended), and Foxy comes back
        air.c.deaf.add("AA:01")
        air.hear(2.0)
        assertNull(how)
        assertTrue(air.c.isConnected("AA:01"))
        air.c.deaf.clear()
        air.payer.foreground(true)
        air.clock.advance(6.0) { air.settle() }
        air.payer.askAgain()
        air.hear(1.0)
        assertEquals(true, how.answer()["ok"])
        assertTrue(air.diary.any { it.contains("asking the receiver to say it again") })
    }

    @Test
    fun aStopWithTheWordThatChangeWasKeptStillGoingOutWaitsForItThenLetsEverythingGo() {
        val (air, r) = listening()
        air.hear(3.0)
        var word: ChangeHandover.Word? = null
        r.paid = { _, a ->
            r.receiver.expectChange()
            a(200, "")
        }
        air.pay("{\"proofs\":[{\"amount\":3}]}")
        // the page writes the change down and stops listening in the same moment, as it does
        air.keepChange = { _, keep ->
            keep(true)
            air.clock.after(0.0) { air.payer.stop() }
        }
        r.receiver.handOverChange("cashuBchange") { word = it }
        air.hear(3.0)
        assertEquals("M8 got there before the link was let go", ChangeHandover.Word.KEPT, word)
        assertTrue(air.c.closed)
        assertFalse(air.c.scanning)
        assertFalse(air.c.isConnected("AA:01"))
    }

    @Test
    fun aStopBeforeAnythingClosesTheCentral() {
        val (air, _) = listening(rssi = -58)
        air.hear(2.0)
        air.payer.stop()
        assertTrue(air.c.closed)
        assertFalse(air.c.isConnected("AA:01"))
        // and a stopped payer hears nothing more
        air.hear(2.0)
        assertTrue(air.stages.isEmpty())
    }
}
