package io.islandbitcoin.foxy

import android.bluetooth.le.ScanSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.tap.AndroidTapCentral
import io.islandbitcoin.foxy.tap.TapPayer
import io.islandbitcoin.foxy.tap.TapScanRules
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ENG-671 on a phone: the payer's scan as Android's own objects
 * (`AndroidTapCentral.scanFilters`, `scanSettings`). No hardware filter at all
 * (ENG-679): the receiver's service is new for every invoice and cannot be
 * named before it is heard, and the "any service UUID" filter used before (a
 * service filter with an all-zero mask) matched nothing on the Pixel 8 Pro's
 * controller. iOS's rules for what is a Foxy decide in software (TapScanRules,
 * held by the JVM's TapPayerTest, whose fake controller matches nothing with an
 * all-zero mask). Low latency, every advertisement reported, nothing batched.
 * Needs no Bluetooth adapter and no permission, and starts no scan.
 */
@RunWith(AndroidJUnit4::class)
class TapCentralOnDeviceTest {
    @Test
    fun theScanHandsTheControllerNoFilter() {
        assertEquals("an unfiltered foreground scan", emptyList<Any>(), AndroidTapCentral.scanFilters())
        assertEquals(emptyList<Any>(), TapScanRules.hardwareFilters)
    }

    @Test
    fun theScanIsLowLatencyEveryAdvertisementAtOnce() {
        val s = AndroidTapCentral.scanSettings()
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, s.scanMode)
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, s.callbackType)
        assertEquals(0L, s.reportDelayMillis)
    }

    @Test
    fun theDeviceInformationUuidsAreBluetoothsOwn() {
        assertEquals(UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB"), TapPayer.DEVICE_INFORMATION)
        assertEquals(UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB"), TapPayer.MODEL_NUMBER)
        assertEquals("Android's most MTU", 517, AndroidTapCentral.MTU)
    }
}
