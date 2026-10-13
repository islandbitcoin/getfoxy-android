package io.islandbitcoin.foxy

import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.tap.AndroidTapCentral
import io.islandbitcoin.foxy.tap.TapPayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ENG-671 on a phone: the payer's scan as Android's own objects
 * (`AndroidTapCentral.scanFilters`, `scanSettings`). One filter, on the service
 * field with an all-zero mask: an advertisement that carries any service UUID,
 * since the receiver's is new for every invoice and cannot be named before it
 * is heard; iOS's rules for what is a Foxy are applied to what it lets through
 * (TapScanRules, held by the JVM's TapPayerTest). Low latency, every
 * advertisement reported, nothing batched. Needs no Bluetooth adapter and no
 * permission, and starts no scan.
 */
@RunWith(AndroidJUnit4::class)
class TapCentralOnDeviceTest {
    @Test
    fun theScanIsFilteredOnAnyServiceAndNothingElse() {
        val filters = AndroidTapCentral.scanFilters()
        assertEquals(1, filters.size)
        val f = filters.single()
        assertEquals(ParcelUuid(UUID(0L, 0L)), f.serviceUuid)
        assertEquals("an all-zero mask: any service UUID matches", ParcelUuid(UUID(0L, 0L)), f.serviceUuidMask)
        assertNull("no address", f.deviceAddress)
        assertNull("no name", f.deviceName)
        assertNull("no service data", f.serviceDataUuid)
        assertEquals("no manufacturer", -1, f.manufacturerId)
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
