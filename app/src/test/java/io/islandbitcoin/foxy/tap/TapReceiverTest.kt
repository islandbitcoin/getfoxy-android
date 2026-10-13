package io.islandbitcoin.foxy.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENG-670: what a receiver says of its radio as it is made (TapLink.swift
 * `cbState`, `peripheralManagerDidUpdateState`), and the Bluetooth answers'
 * mapping ([BluetoothAnswer]) from Android's state: the adapter, whether it can
 * advertise, the two permissions and the power.
 */
class TapReceiverTest {
    private fun made(b: FakeBluetooth): Pair<TapReceiver, ArrayList<TapStage>> {
        val stages = ArrayList<TapStage>()
        val r = TapReceiver(TapAir.OFFER, b, onEvent = { s, _ -> stages.add(s) }, diary = {})
        return r to stages
    }

    private fun settle(b: FakeBluetooth) {
        while (true) {
            if (b.pending.isNotEmpty()) {
                b.pending.removeFirst()()
                continue
            }
            if (!b.clock.runDue()) return
        }
    }

    @Test
    fun noAdapterOrOneThatCannotAdvertiseIsUnsupportedAndNothingIsAsked() {
        for (setup in listOf<(FakeBluetooth) -> Unit>({ it.adapter = false }, { it.advertises = false })) {
            val b = FakeBluetooth().apply { granted = false }
            setup(b)
            val (r, stages) = made(b)
            r.start()
            r.arm()
            settle(b)
            assertEquals(listOf(TapStage.UNSUPPORTED), stages)
            assertEquals("no question for a radio the phone cannot use", 0, b.asks)
            assertTrue(b.opened.isEmpty())
        }
    }

    @Test
    fun theSystemsQuestionIsAskedWhenNeverAnsweredAndARefusalIsDenied() {
        val b = FakeBluetooth().apply {
            granted = false
            grantOnAsk = null
        }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        assertEquals(1, b.asks)
        assertTrue("nothing until it is answered", stages.isEmpty())
        b.answer(false)
        settle(b)
        assertEquals(listOf(TapStage.DENIED), stages)
        assertTrue(b.opened.isEmpty())

        val yes = FakeBluetooth().apply {
            granted = false
            grantOnAsk = true
        }
        val (r2, stages2) = made(yes)
        r2.start()
        r2.arm()
        settle(yes)
        assertEquals(listOf(TapStage.SEARCHING), stages2)
        assertTrue(yes.peripheral.onAir)
    }

    @Test
    fun aRefusalIsRememberedAndTheNextReceiverIsDeniedWithNoSecondQuestion() {
        // iOS asks only while `.notDetermined`; refused, it reads `.unauthorized` and says denied with no dialog
        val b = FakeBluetooth().apply {
            granted = false
            grantOnAsk = false
        }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        settle(b)
        assertEquals(1, b.asks)
        assertEquals(listOf(TapStage.DENIED), stages)
        r.stop()

        val (r2, stages2) = made(b)
        r2.start()
        r2.arm()
        settle(b)
        assertEquals("no second system question once it has been answered", 1, b.asks)
        assertEquals(listOf(TapStage.DENIED), stages2)
        assertTrue(b.opened.isEmpty())

        // granted later in Settings: on the air, still with no question
        b.granted = true
        val (r3, stages3) = made(b)
        r3.start()
        r3.arm()
        settle(b)
        assertEquals(1, b.asks)
        assertEquals(listOf(TapStage.SEARCHING), stages3)
    }

    @Test
    fun aGrantAndroidTookBackIsAskedAgainNotReadAsARefusal() {
        // Android 11+ resets the runtime permissions of an app left unused for months; iOS never takes a
        // CBManager authorization back, so iOS would still be on the air. No refusal was given: ask afresh.
        val b = FakeBluetooth().apply {
            granted = false
            grantOnAsk = true
        }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        settle(b)
        assertEquals(1, b.asks)
        assertEquals(listOf(TapStage.SEARCHING), stages)
        r.stop()
        settle(b)

        b.autoRevoke()
        assertFalse("a grant taken back is not a refusal", b.refused())
        val (r2, stages2) = made(b)
        r2.start()
        r2.arm()
        settle(b)
        assertEquals("asked again, as a never-asked install is", 2, b.asks)
        assertEquals(listOf(TapStage.SEARCHING), stages2)
    }

    @Test
    fun aRefusalGrantedInSettingsThenTakenBackIsAskedAgain() {
        val b = FakeBluetooth().apply {
            granted = false
            grantOnAsk = false
        }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        settle(b)
        assertEquals(listOf(TapStage.DENIED), stages)
        assertTrue(b.refused())
        r.stop()

        // granted in Settings and seen granted: the old refusal no longer holds
        b.granted = true
        assertTrue(b.permitted())
        assertFalse(b.kept.value)

        b.autoRevoke()
        b.grantOnAsk = true
        val (r2, stages2) = made(b)
        r2.start()
        r2.arm()
        settle(b)
        assertEquals(2, b.asks)
        assertEquals(listOf(TapStage.SEARCHING), stages2)
    }

    @Test
    fun aQuestionNobodyAnsweredIsNotARefusal() {
        // the launcher's empty map: the request was cancelled or interrupted, not answered
        val b = FakeBluetooth().apply {
            granted = false
            grantOnAsk = null
        }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        b.unanswered()
        settle(b)
        assertEquals(listOf(TapStage.DENIED), stages)
        assertFalse("nothing kept for an unanswered question", b.refused())
        r.stop()

        b.grantOnAsk = true
        val (r2, stages2) = made(b)
        r2.start()
        r2.arm()
        settle(b)
        assertEquals("asked again", 2, b.asks)
        assertEquals(listOf(TapStage.SEARCHING), stages2)
    }

    @Test
    fun aQuestionAnsweredAfterTheReceiverStoppedMakesNoRadio() {
        val b = FakeBluetooth().apply {
            granted = false
            grantOnAsk = null
        }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        assertTrue(r.stop())
        b.answer(true)
        settle(b)
        assertTrue(stages.isEmpty())
        assertTrue(b.opened.isEmpty())
        assertEquals(0, b.watching)
    }

    @Test
    fun offIsOffAndTurningOnIsWaitedForThenOnTheAir() {
        val b = FakeBluetooth().apply { powerNow = TapBluetooth.Power.OFF }
        val (r, stages) = made(b)
        r.start()
        r.arm()
        settle(b)
        assertEquals(listOf(TapStage.OFF), stages)
        b.setPower(TapBluetooth.Power.TURNING)
        b.setPower(TapBluetooth.Power.ON)
        settle(b)
        assertEquals(listOf(TapStage.OFF, TapStage.OFF, TapStage.SEARCHING), stages)
        assertTrue(b.peripheral.onAir)
    }

    @Test
    fun aGattServerAndroidWouldNotOpenIsUnsupported() {
        val b = FakeBluetooth().apply { openFails = true }
        val (r, stages) = made(b)
        r.start()
        settle(b)
        assertEquals(listOf(TapStage.UNSUPPORTED), stages)
    }

    @Test
    fun eachReceiverIsANewServiceUuid() {
        val b = FakeBluetooth()
        val seen = (1..20).map { made(b).first.link.service }.toSet()
        assertEquals(20, seen.size)
    }

    @Test
    fun theFirstInstallAnswerFromAndroidsState() {
        val on = TapBluetooth.Power.ON
        val off = TapBluetooth.Power.OFF
        val turning = TapBluetooth.Power.TURNING
        // (adapter, permitted, power, advertises) -> answer
        val table = listOf(
            listOf(false, true, on, true) to "unsupported",
            listOf(false, false, off, false) to "unsupported",
            listOf(true, true, on, false) to "unsupported",
            listOf(true, false, on, false) to "unsupported",
            listOf(true, false, on, true) to "denied",
            listOf(true, false, off, false) to "denied",
            listOf(true, true, off, false) to "off",
            listOf(true, true, on, true) to "allowed",
            listOf(true, true, turning, false) to null,
            listOf(true, false, turning, false) to "denied",
        )
        for ((k, want) in table) {
            val got = BluetoothAnswer.answer(k[0] as Boolean, k[1] as Boolean, k[2] as TapBluetooth.Power, k[3] as Boolean)
            assertEquals(k.toString(), want, got)
        }
        for (a in listOf("allowed", "denied", "off", "unsupported", "unknown")) {
            assertTrue(a in listOf(BluetoothAnswer.ALLOWED, BluetoothAnswer.DENIED, BluetoothAnswer.OFF, BluetoothAnswer.UNSUPPORTED, BluetoothAnswer.UNKNOWN))
        }
        assertEquals(60.0, BluetoothAnswer.WAIT, 0.0)
    }

    @Test
    fun theReceiversStageFromAndroidsState() {
        val on = TapBluetooth.Power.ON
        assertEquals(TapStage.UNSUPPORTED, BluetoothAnswer.stage(false, true, on, true))
        assertEquals(TapStage.UNSUPPORTED, BluetoothAnswer.stage(true, true, on, false))
        assertEquals(TapStage.DENIED, BluetoothAnswer.stage(true, false, on, true))
        // turning off is iOS's .resetting, which is off; turning on is off until it is on
        assertEquals(TapStage.OFF, BluetoothAnswer.stage(true, true, TapBluetooth.Power.OFF, false))
        assertEquals(TapStage.OFF, BluetoothAnswer.stage(true, true, TapBluetooth.Power.TURNING, false))
        assertEquals(null, BluetoothAnswer.stage(true, true, on, true))
    }

    @Test
    fun thePermissionsAskedAreTheTwoFromApi31AndNoneBefore() {
        assertEquals(listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT"), BluetoothAnswer.permissions(31))
        assertEquals(BluetoothAnswer.permissions(31), BluetoothAnswer.permissions(36))
        for (sdk in 28..30) assertTrue("API $sdk holds BLUETOOTH and BLUETOOTH_ADMIN from the install", BluetoothAnswer.permissions(sdk).isEmpty())
        for (sdk in 28..37) assertFalse("never a scan or a location", BluetoothAnswer.permissions(sdk).any { it.contains("SCAN") || it.contains("LOCATION") })
    }

    @Test
    fun askingIsWorthItOnlyWhereTheAnswerCouldBeUsed() {
        assertFalse(BluetoothAnswer.worthAsking(false, TapBluetooth.Power.ON, true))
        assertFalse(BluetoothAnswer.worthAsking(true, TapBluetooth.Power.ON, false))
        assertTrue(BluetoothAnswer.worthAsking(true, TapBluetooth.Power.ON, true))
        // Android cannot tell whether an adapter that is off advertises: asked, so it is ready when it comes on
        assertTrue(BluetoothAnswer.worthAsking(true, TapBluetooth.Power.OFF, false))
    }
}
