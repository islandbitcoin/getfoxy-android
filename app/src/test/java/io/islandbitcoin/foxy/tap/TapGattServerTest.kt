package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * ENG-670: the receiver's GATT radio ([TapGattServer] under [TapReceiver]) end
 * to end against a paying [TapPayerLink], joined at the ATT level ([GattAir]):
 * the service iOS puts up, the advertisement, a CCCD write as the subscribe, a
 * whole payment with its change handed back, writes longer than the MTU as
 * prepared writes and an execute, notifications one at a time and no longer
 * than MTU − 3, a payer with no CCCD, a payer that disconnects at each step,
 * the waits, the near doors and their byte, and nothing on the air while Foxy
 * is away.
 */
class TapGattServerTest {
    private fun TapPayerLink.Handover?.answer(): Map<*, *> {
        val a = this as? TapPayerLink.Handover.Answered ?: error("not answered: $this")
        return Json.parse(a.text) as Map<*, *>
    }

    // MARK: the service and the advertisement

    @Test
    fun theServiceIsIosFourDoorsInIosOrderWithACccdOnEachNotifyDoor() {
        // the first door vector iOS's own code made (fixtures/tap-vectors.json, TapVectorsTest)
        val service = UUID.fromString("10111213-1415-1617-1819-1A1B1C1D1E1F")
        val layout = TapGatt.Layout(service)
        assertEquals(
            listOf("1EDE50A1-F56C-4B4D-F7BF-626F085A992E", "5D209629-8B78-C790-2985-97FFE8777A6A",
                "1FC3A792-48DF-BF63-0E1B-67660DF32C6B", "C290CA6A-C2CD-7C60-F76D-8837002EE356"),
            layout.doors.map { it.uuid.toString().uppercase() },
        )
        // out: notify, readable; in: write (with response), writable; near: notify, readable (TapLink.swift:484-491)
        assertEquals(listOf(TapGatt.PROPERTY_NOTIFY, TapGatt.PROPERTY_WRITE, TapGatt.PROPERTY_NOTIFY, TapGatt.PROPERTY_NOTIFY), layout.doors.map { it.properties })
        assertEquals(listOf(TapGatt.PERMISSION_READ, TapGatt.PERMISSION_WRITE, TapGatt.PERMISSION_READ, TapGatt.PERMISSION_READ), layout.doors.map { it.permissions })
        assertEquals("a CCCD on every notify door, and only there", listOf(true, false, true, true), layout.doors.map { it.cccd })
        for (d in layout.doors) {
            assertEquals("no door asks for encryption, MITM or signing: nothing pairs", 0, d.permissions and TapGatt.ANY_SECURITY)
            assertEquals("no write without response, no indication, no read property", 0,
                d.properties and (TapGatt.PROPERTY_WRITE_NO_RESPONSE or TapGatt.PROPERTY_INDICATE or TapGatt.PROPERTY_READ))
        }
        assertEquals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), TapGatt.CCCD)
        assertEquals("left", layout.edge(layout.nearLeft.uuid))
        assertEquals("right", layout.edge(layout.nearRight.uuid))
        assertNull(layout.edge(layout.out.uuid))
    }

    @Test
    fun nothingIsOnTheAirBeforeTapIsPressedAndThenOnlyTheServiceAfterItIsUp() {
        val air = GattAir()
        air.receiver.start()
        air.settle()
        assertEquals("a GATT server is opened for the radio's state", 1, air.bluetooth.opened.size)
        assertEquals("but nothing is put up before TAP", 0, air.bluetooth.peripheral.addCalls)
        assertFalse(air.bluetooth.peripheral.onAir)
        assertTrue(air.stages.isEmpty())
        air.receiver.arm()
        assertEquals("the service first", 1, air.bluetooth.peripheral.addCalls)
        assertEquals("and no advertisement until it is up", 0, air.bluetooth.peripheral.startCalls)
        air.settle()
        assertEquals(air.receiver.link.service, air.bluetooth.peripheral.advertisingWith)
        assertEquals(air.receiver.link.service, air.bluetooth.peripheral.layout?.service)
        assertEquals(listOf(TapStage.SEARCHING), air.stages)
        assertTrue(air.receiver.server.onAir)
        // pressing TAP again on a screen on the air changes nothing
        air.receiver.arm()
        air.settle()
        assertEquals(1, air.bluetooth.peripheral.addCalls)
        assertEquals(1, air.bluetooth.peripheral.startCalls)
    }

    @Test
    fun aServiceThatWouldNotGoUpIsNotAdvertisedAndAPhoneThatCannotAdvertiseSaysUnsupported() {
        val air = GattAir()
        air.receiver.start()
        air.bluetooth.peripheral.addOk = false
        air.receiver.arm()
        air.settle()
        assertEquals(0, air.bluetooth.peripheral.startCalls)
        assertTrue(air.diary.any { it.contains("the service would not go up") })

        val other = GattAir()
        other.receiver.start()
        other.bluetooth.peripheral.advertiseFails = 5 // ADVERTISE_FAILED_FEATURE_UNSUPPORTED
        other.receiver.arm()
        other.settle()
        assertEquals(listOf(TapStage.UNSUPPORTED), other.stages)
        assertFalse(other.bluetooth.peripheral.onAir)
    }

    // MARK: a whole payment

    @Test
    fun aWholePaymentWithChangeHandedBackOverTheGattServer() {
        val air = GattAir(mtu = 185)
        air.link()
        val (offer, code) = air.offer ?: error("no offer")
        assertEquals(TapAir.OFFER, offer)
        assertEquals(code, air.receiverEvents.first { it.first == TapStage.CODE }.second)
        assertEquals(listOf(TapStage.SEARCHING, TapStage.CONNECTING, TapStage.CODE), air.stages)
        assertFalse("off the air at the payer's first word", air.bluetooth.peripheral.onAir)
        assertEquals("subscribed with a CCCD write first", "cccd ${air.receiver.link.let { TapGatt.Layout(it.service).out.uuid.toString().take(8) }} 0100",
            air.central.requests.first())

        // the page answers the payment once it owes change, and hands the change back over the same link
        var changeWord: ChangeHandover.Word? = null
        air.paid = { _, answer ->
            air.receiver.expectChange()
            answer(200, "")
        }
        val how = air.pay("{\"proofs\":[{\"amount\":64}]}")
        assertEquals(true, how.answer()["ok"])
        assertEquals(listOf("{\"proofs\":[{\"amount\":64}]}"), air.payments)
        assertTrue("change owed keeps the link", air.receiver.busy)
        assertTrue(air.receiver.handOverChange("cashuBchange") { changeWord = it })
        air.settle()
        assertEquals(listOf("cashuBchange"), air.changes)
        assertEquals("the payer said it kept it (M8)", ChangeHandover.Word.KEPT, changeWord)
        assertFalse(air.receiver.busy)

        // every notification at most MTU - 3, and never two in the air at once
        assertTrue(air.bluetooth.peripheral.notifications.all { it.third.size <= 182 })
        assertEquals(1, air.bluetooth.peripheral.mostInFlight)
        // the offer (2 KB padded) took many notifications, each one waited for
        assertTrue(air.bluetooth.peripheral.notifications.size > 12)
    }

    @Test
    fun writesLongerThanTheMtuArriveAsPreparedWritesAndAreTakenTogetherOnTheExecute() {
        for (mtu in listOf(23, 64, 185, 247)) {
            val air = GattAir(mtu = mtu)
            air.link()
            assertNotNull("mtu $mtu: linked", air.offer)
            val body = "{\"proofs\":[" + (1..60).joinToString(",") { "{\"amount\":$it,\"secret\":\"${"s".repeat(40)}\"}" } + "]}"
            val how = air.pay(body)
            assertEquals("mtu $mtu: the payment crossed whole", listOf(body), air.payments)
            assertEquals(true, how.answer()["ok"])
            val r = air.central.requests
            // iOS writes 512 at a time: over any MTU below 515 that is a long write
            assertTrue("mtu $mtu: long writes used", r.any { it.startsWith("prepare") } && r.contains("execute"))
            assertTrue("mtu $mtu: every prepared piece is MTU - 5", r.filter { it.startsWith("prepare") }.all { it.split(" ")[2].toInt() <= mtu - 5 })
            assertTrue("mtu $mtu: each echo was checked", air.central.echoChecks > 0)
            assertTrue("mtu $mtu: notifications fit", air.bluetooth.peripheral.notifications.all { it.third.size <= TapGatt.notifyLength(mtu) })
        }
    }

    @Test
    fun aWriteThatFitsGoesAsOneAndAnMtuOf517CarriesTheWhole512() {
        val air = GattAir(mtu = 517)
        air.link()
        air.pay("{\"proofs\":[{\"amount\":1}]}")
        assertFalse("no prepared write when a write fits", air.central.requests.any { it.startsWith("prepare") })
        assertTrue(air.bluetooth.peripheral.notifications.any { it.third.size == 512 })
        assertTrue(air.bluetooth.peripheral.notifications.all { it.third.size <= 512 })
    }

    @Test
    fun aLongWriteBeyondTheQueueIsRefusedAndACancelledOneDeliversNothing() {
        val air = GattAir()
        air.link()
        val server = air.receiver.server
        val p = air.bluetooth.peripheral
        val into = TapGatt.Layout(air.receiver.link.service).into.uuid
        val piece = ByteArray(180) { 7 }
        var offset = 0
        var req = 100
        while (offset + piece.size <= TapGatt.MOST_PREPARED) {
            server.characteristicWrite("payer-1", req++, into, true, true, offset, piece)
            offset += piece.size
        }
        assertTrue(p.responses.takeLast(3).all { it.third == TapGatt.SUCCESS })
        server.characteristicWrite("payer-1", req++, into, true, true, offset, piece)
        assertEquals("past the queue's bound", TapGatt.PREPARE_QUEUE_FULL, p.responses.last().third)
        // the queue went with the refusal: an execute now carries nothing
        server.executeWrite("payer-1", req++, true)
        assertEquals(TapGatt.SUCCESS, p.responses.last().third)
        // and a cancelled long write hands the link nothing
        val before = air.payments.size
        server.characteristicWrite("payer-1", req++, into, true, true, 0, ByteArray(40) { 1 })
        server.executeWrite("payer-1", req, false)
        assertEquals(TapGatt.SUCCESS, p.responses.last().third)
        assertEquals(before, air.payments.size)
        assertFalse(air.receiver.busy)
    }

    // MARK: a payer with no CCCD, writes at the wrong door, reads

    @Test
    fun aPayerThatNeverWroteTheCccdTakesNoPlaceAndIsToldNothing() {
        val air = GattAir()
        air.central.skipCccd = true
        air.link()
        assertNull(air.offer)
        assertTrue("its first word was refused", air.bluetooth.peripheral.responses.any { it.third == TapGatt.INSUFFICIENT_AUTHORIZATION })
        assertTrue("nothing was notified to it", air.bluetooth.peripheral.notifications.isEmpty())
        assertTrue("still on the air for a payer that does subscribe", air.bluetooth.peripheral.onAir)
        assertFalse(TapStage.CONNECTING in air.stages)
        // the payer gives up on its own clock, as an iOS payer whose subscribe failed
        air.advance(TapPayerLink.CONNECT_WAIT + 0.1)
        assertNotNull(air.central.gaveUp)
    }

    @Test
    fun theCccdTakesNotifyOnOrOffAndNothingElse() {
        val air = GattAir()
        air.onAir()
        air.central.connect()
        air.settle()
        val layout = TapGatt.Layout(air.receiver.link.service)
        val statuses = ArrayList<Int>()
        air.central.cccd(layout.out.uuid, byteArrayOf(0x02, 0x00)) { statuses.add(it) } // indications
        air.central.cccd(layout.out.uuid, byteArrayOf(0x01)) { statuses.add(it) }
        air.central.cccd(layout.out.uuid, byteArrayOf(0x05, 0x00)) { statuses.add(it) }
        air.central.cccd(layout.into.uuid, TapGatt.NOTIFY_ON) { statuses.add(it) } // the in door has no CCCD
        air.settle()
        assertEquals(listOf(TapGatt.CCCD_IMPROPERLY_CONFIGURED, TapGatt.INVALID_ATTRIBUTE_LENGTH, TapGatt.CCCD_IMPROPERLY_CONFIGURED, TapGatt.WRITE_NOT_PERMITTED), statuses)
        // a prepared write to a CCCD, and one at an offset
        val server = air.receiver.server
        server.descriptorWrite("payer-1", 50, layout.out.uuid, TapGatt.CCCD, true, true, 0, TapGatt.NOTIFY_ON)
        server.descriptorWrite("payer-1", 51, layout.out.uuid, TapGatt.CCCD, false, true, 1, TapGatt.NOTIFY_ON)
        assertEquals(listOf(TapGatt.REQUEST_NOT_SUPPORTED, TapGatt.INVALID_OFFSET), air.bluetooth.peripheral.responses.takeLast(2).map { it.third })
        // a CCCD read says what this central set; a characteristic read is refused (didReceiveRead)
        server.descriptorRead("payer-1", 52, layout.out.uuid, TapGatt.CCCD, 0)
        server.characteristicRead("payer-1", 53, 0)
        assertEquals(listOf(TapGatt.SUCCESS, TapGatt.READ_NOT_PERMITTED), air.bluetooth.peripheral.responses.takeLast(2).map { it.third })
        assertTrue("nobody subscribed", air.stages == listOf(TapStage.SEARCHING))
    }

    @Test
    fun aWriteOnAnotherDoorIsRefusedAsIosRefusesIt() {
        val air = GattAir()
        air.writeDoor = TapGatt.Layout(air.receiver.link.service).out.uuid
        air.link()
        assertNull(air.offer)
        assertTrue(air.bluetooth.peripheral.responses.any { it.third == TapGatt.INSUFFICIENT_AUTHORIZATION })
        assertTrue(air.diary.any { it.contains("refused") })
    }

    // MARK: disconnects and waits

    @Test
    fun aPayerThatDisconnectsBeforeTheHandshakeEndsPutsTheReceiverBackOnTheAir() {
        val air = GattAir()
        air.onAir()
        air.central.connect()
        air.settle()
        air.payer.connecting()
        air.payer.speak()
        // the subscribe and M1 land, then the link drops before M3
        repeat(4) { if (air.bluetooth.pending.isNotEmpty()) air.bluetooth.pending.removeFirst()() }
        air.central.disconnect()
        air.settle()
        assertTrue("back on the air for the next phone", air.bluetooth.peripheral.onAir)
        assertFalse(air.receiver.busy)
        assertTrue(air.diary.any { it.contains("went away before the handshake finished") })
    }

    @Test
    fun aPayerThatBeginsAndStopsHasThreeSeconds() {
        val air = GattAir()
        air.onAir()
        air.central.connect()
        air.settle()
        // subscribed, and its first word, and then nothing
        val layout = TapGatt.Layout(air.receiver.link.service)
        air.central.cccd(layout.out.uuid, TapGatt.NOTIFY_ON)
        air.settle()
        val stranger = TapSession(TapSession.Role.PAYER, air.receiver.link.service) {}
        val m1 = TapProtocol.framed((stranger.begin() as TapSession.Step.Send).bytes)!!
        air.receiver.server.characteristicWrite("payer-1", 9, layout.into.uuid, false, true, 0, m1)
        air.settle()
        assertFalse(air.bluetooth.peripheral.onAir)
        air.advance(TapReceiverLink.HANDSHAKE_WAIT - 0.1)
        assertFalse(air.bluetooth.peripheral.onAir)
        air.advance(0.2)
        assertTrue("back on the air after three seconds", air.bluetooth.peripheral.onAir)
        assertEquals(TapStage.SEARCHING, air.stages.last())
    }

    @Test
    fun aPayerThatDisconnectsMidPaymentIsGoneAndThePageHasTwentyFiveSeconds() {
        val air = GattAir(mtu = 23)
        air.paid = { _, _ -> }
        air.link()
        var how: TapPayerLink.Handover? = null
        val body = "{\"proofs\":[" + (1..40).joinToString(",") { "{\"amount\":$it,\"secret\":\"${"q".repeat(30)}\"}" } + "]}"
        air.payer.send(body) { how = it }
        // part of it across, and then the link drops
        repeat(30) { if (air.bluetooth.pending.isNotEmpty()) air.bluetooth.pending.removeFirst()() }
        air.central.disconnect()
        air.settle()
        assertEquals(TapStage.GONE, air.stages.last())
        assertTrue(how is TapPayerLink.Handover.Lost)
        assertFalse("half a payment on a link that is gone holds nothing", air.receiver.busy)

        // and a whole payment the page never answers: M6 says so at 25 s
        val again = GattAir()
        again.paid = { _, _ -> }
        again.link()
        var said: TapPayerLink.Handover? = null
        again.payer.send("{\"proofs\":[]}") { said = it }
        again.settle()
        assertTrue(again.receiver.busy)
        again.advance(TapReceiverLink.RESULT_WAIT + 0.1)
        assertEquals(504.0, said.answer()["code"])
    }

    @Test
    fun aNotificationAndroidNeverConfirmsIsTakenAsSentAfterASecondAndAStackThatRefusesIsAskedAgain() {
        val air = GattAir()
        air.onAir()
        air.central.connect()
        air.settle()
        air.payer.connecting()
        air.payer.speak()
        air.bluetooth.peripheral.holdSent = true
        air.settle()
        // M2 went out and Android said nothing more: nothing else goes until the stall
        val before = air.bluetooth.peripheral.notifications.size
        assertTrue(before >= 1)
        air.bluetooth.peripheral.holdSent = false
        air.bluetooth.peripheral.releaseSent("payer-1")
        air.settle()
        assertNotNull("the handshake finished once the radio was ready again", air.offer)

        val stalled = GattAir()
        stalled.link()
        stalled.bluetooth.peripheral.holdSent = true
        stalled.receiver.sendAsking()
        stalled.settle()
        stalled.advance(TapGattServer.NOTIFY_STALL + 0.1)
        assertTrue(stalled.diary.any { it.contains("said nothing of the last notification") })

        val busy = GattAir()
        busy.onAir()
        busy.bluetooth.peripheral.refuseNotifications = 3
        busy.central.connect()
        busy.settle()
        busy.payer.connecting()
        busy.payer.speak()
        busy.settle()
        assertNull("nothing goes while the stack refuses", busy.offer)
        busy.advance(3 * TapGattServer.NOTIFY_RETRY + 0.01)
        assertNotNull("a stack that refused three times was asked again", busy.offer)
    }

    // MARK: the near doors

    @Test
    fun aPayerOnANearDoorIsNearbyWithItsEdgeAndToldTheByte() {
        val air = GattAir()
        air.onAir()
        air.central.connect()
        air.settle()
        val layout = TapGatt.Layout(air.receiver.link.service)
        air.central.cccd(layout.nearLeft.uuid, TapGatt.NOTIFY_ON)
        air.settle()
        assertEquals(TapStage.NEARBY to "left", air.receiverEvents.last())
        assertEquals(listOf("left" to 0.toByte()), air.central.nearBytes)
        // the amount screen, then the invoice screen: bit 1, then nothing
        air.receiver.early = true
        air.settle()
        air.receiver.early = false
        air.settle()
        assertEquals(listOf("left" to 0.toByte(), "left" to 2.toByte(), "left" to 0.toByte()), air.central.nearBytes)
        // top-down: bit 0
        air.receiver.turned = true
        air.settle()
        assertEquals("left" to 1.toByte(), air.central.nearBytes.last())
        // changing doors is not leaving
        air.central.cccd(layout.nearRight.uuid, TapGatt.NOTIFY_ON)
        air.central.cccd(layout.nearLeft.uuid, TapGatt.NOTIFY_OFF)
        air.settle()
        assertEquals(TapStage.NEARBY to "right", air.receiverEvents.first { it.second == "right" })
        assertFalse(TapStage.FAR in air.stages)
        air.central.cccd(layout.nearRight.uuid, TapGatt.NOTIFY_OFF)
        air.settle()
        assertEquals(TapStage.FAR, air.stages.last())
    }

    @Test
    fun aPayerThatRefusesTheByteFortyTimesIsNotThere() {
        val air = GattAir()
        air.onAir()
        air.central.connect()
        air.settle()
        air.bluetooth.peripheral.refuseNotifications = 1_000
        air.central.cccd(TapGatt.Layout(air.receiver.link.service).nearRight.uuid, TapGatt.NOTIFY_ON)
        air.advance(10.0)
        assertTrue(air.diary.any { it.contains("took no door byte in 40 tries") })
        assertEquals(TapStage.FAR, air.stages.last())
    }

    @Test
    fun aPayerLinkedIsNotToldNearbyAndADisconnectLetsGoOfItsNearDoor() {
        val air = GattAir()
        air.link()
        air.central.cccd(TapGatt.Layout(air.receiver.link.service).nearLeft.uuid, TapGatt.NOTIFY_ON)
        air.settle()
        assertFalse("linked: no CONNECT TO PAY", TapStage.NEARBY in air.stages)
        val before = air.stages.size
        air.central.disconnect()
        air.settle()
        // CoreBluetooth says each door unsubscribed: near (far), and out (gone, no result was sent)
        assertEquals(setOf(TapStage.FAR, TapStage.GONE), air.stages.drop(before).toSet())
    }

    // MARK: Foxy away, the radio off, the end

    @Test
    fun nothingIsOnTheAirWhileFoxyIsAwayAndItGoesBackOnWhenFoxyIsInFront() {
        val air = GattAir()
        air.onAir()
        assertTrue(air.bluetooth.peripheral.onAir)
        air.receiver.foreground(false)
        assertFalse(air.bluetooth.peripheral.onAir)
        // whatever the link asks meanwhile (a payer gone before its handshake ended puts it back on the air)
        air.receiver.link.radioState(null)
        air.settle()
        assertFalse(air.bluetooth.peripheral.onAir)
        air.receiver.foreground(true)
        air.settle()
        assertTrue(air.bluetooth.peripheral.onAir)
        // an advertisement that comes up after Foxy went away is taken down again
        air.receiver.foreground(false)
        air.receiver.foreground(true)
        air.receiver.foreground(false)
        air.settle()
        assertFalse(air.bluetooth.peripheral.onAir)
    }

    @Test
    fun bluetoothTurnedOffSaysOffAndLetsEveryoneGoAndOnPutsItBackUp() {
        val air = GattAir()
        air.link()
        air.receiver.link.stop()
        val again = GattAir()
        again.onAir()
        again.central.connect()
        again.settle()
        again.bluetooth.setPower(TapBluetooth.Power.OFF)
        again.settle()
        assertEquals(TapStage.OFF, again.stages.last())
        assertTrue(again.bluetooth.opened.first().closed)
        again.bluetooth.setPower(TapBluetooth.Power.ON)
        again.settle()
        assertEquals("a new GATT server", 2, again.bluetooth.opened.size)
        assertEquals("the service up again", 1, again.bluetooth.peripheral.addCalls)
        assertTrue(again.bluetooth.peripheral.onAir)
        assertEquals(TapStage.SEARCHING, again.stages.last())
    }

    @Test
    fun stoppingClosesTheGattServerAndStopsListeningToThePower() {
        val air = GattAir()
        air.onAir()
        assertEquals(1, air.bluetooth.watching)
        assertTrue(air.receiver.stop())
        assertTrue(air.bluetooth.peripheral.closed)
        assertEquals(0, air.bluetooth.watching)
        // nothing more reaches the link
        air.receiver.server.serviceAdded(true)
        air.receiver.server.advertiseStarted()
        air.settle()
        assertEquals(listOf(TapStage.SEARCHING), air.stages)
    }

    @Test
    fun aStopWithAPaymentCrossingWaitsAndThenClosesEverything() {
        val air = GattAir()
        air.paid = { _, _ -> }
        air.link()
        air.payer.send("{\"proofs\":[]}") {}
        air.settle()
        var idle = false
        assertFalse(air.receiver.stop { idle = true })
        assertFalse(air.bluetooth.peripheral.closed)
        air.advance(TapReceiverLink.RESULT_WAIT + 2.1)
        assertTrue(idle)
        assertTrue(air.bluetooth.peripheral.closed)
        assertEquals(0, air.bluetooth.watching)
    }

    @Test
    fun theDoorBytesAreSaidBeforeAPaymentsPiecesWhenTheRadioIsReady() {
        val air = GattAir(mtu = 23)
        air.link()
        val layout = TapGatt.Layout(air.receiver.link.service)
        air.central.cccd(layout.nearLeft.uuid, TapGatt.NOTIFY_ON)
        air.settle()
        // change, many pieces, and the early bit while they go
        air.paid = { _, answer -> answer(200, "") }
        air.pay("{\"proofs\":[{\"amount\":1}]}")
        air.receiver.expectChange()
        air.receiver.handOverChange("cashuB" + "c".repeat(800)) {}
        air.receiver.early = true
        air.settle()
        val bytes = air.bluetooth.peripheral.notifications.filter { it.second == layout.nearLeft.uuid }.map { it.third[0] }
        assertEquals(listOf<Byte>(0, 2), bytes)
        assertArrayEquals(byteArrayOf(2), air.bluetooth.peripheral.notifications.last { it.second == layout.nearLeft.uuid }.third)
        assertEquals(1, air.bluetooth.peripheral.mostInFlight)
    }
}
