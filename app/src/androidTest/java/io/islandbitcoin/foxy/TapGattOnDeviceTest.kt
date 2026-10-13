package io.islandbitcoin.foxy

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.tap.AndroidTapPeripheral
import io.islandbitcoin.foxy.tap.TapGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ENG-670 on a phone: the receiver's service as Android's own objects
 * (`AndroidTapPeripheral.service`), built from the layout the JVM tests hold to
 * iOS: one primary service, four characteristics in iOS's order with iOS's
 * properties, a CCCD (read and write, no encryption) on each notify door and
 * nothing on the in door, and Android's constants equal to the ones
 * [TapGatt] spells out. Needs no Bluetooth adapter and no permission, and
 * opens no GATT server: nothing goes on the air.
 */
@RunWith(AndroidJUnit4::class)
class TapGattOnDeviceTest {
    @Test
    fun theServiceIsTheLayoutAsAndroidsObjects() {
        val layout = TapGatt.Layout(UUID.fromString("10111213-1415-1617-1819-1A1B1C1D1E1F"))
        val service = AndroidTapPeripheral.service(layout)
        assertEquals(layout.service, service.uuid)
        assertEquals(BluetoothGattService.SERVICE_TYPE_PRIMARY, service.type)
        assertEquals(layout.doors.map { it.uuid }, service.characteristics.map { it.uuid })
        for ((door, ch) in layout.doors.zip(service.characteristics)) {
            assertEquals(door.properties, ch.properties)
            assertEquals(door.permissions, ch.permissions)
            if (door.cccd) {
                val cccd = ch.getDescriptor(TapGatt.CCCD)
                assertEquals(listOf(TapGatt.CCCD), ch.descriptors.map { it.uuid })
                assertEquals(BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE, cccd.permissions)
            } else {
                assertNull(ch.getDescriptor(TapGatt.CCCD))
                assertEquals(0, ch.descriptors.size)
            }
        }
    }

    @Test
    fun theAdvertisementIsTheServiceAloneAndConnectable() {
        // the iPhone payer drops an advert with manufacturer data or one not connectable (TapLink.swift 1154-1157: connectable, no manufacturer data, exactly one 128-bit service UUID)
        val service = UUID.fromString("10111213-1415-1617-1819-1A1B1C1D1E1F")
        val data = AndroidTapPeripheral.advertiseData(service)
        assertEquals(listOf(ParcelUuid(service)), data.serviceUuids)
        assertFalse("no device name", data.includeDeviceName)
        assertFalse("no TX power level", data.includeTxPowerLevel)
        assertEquals("no manufacturer data", 0, data.manufacturerSpecificData.size())
        assertTrue("no service data", data.serviceData.isEmpty())
        val settings = AndroidTapPeripheral.advertiseSettings()
        assertTrue("connectable", settings.isConnectable)
        assertEquals("on the air until taken off", 0, settings.timeout)
        assertEquals(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY, settings.mode)
        assertEquals(AndroidTapPeripheral.TX_POWER, settings.txPowerLevel)
    }

    @Test
    fun androidsConstantsAreTheOnesTheLayoutSpellsOut() {
        assertEquals(BluetoothGattCharacteristic.PROPERTY_READ, TapGatt.PROPERTY_READ)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, TapGatt.PROPERTY_WRITE_NO_RESPONSE)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_WRITE, TapGatt.PROPERTY_WRITE)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_NOTIFY, TapGatt.PROPERTY_NOTIFY)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_INDICATE, TapGatt.PROPERTY_INDICATE)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_READ, TapGatt.PERMISSION_READ)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, TapGatt.PERMISSION_READ_ENCRYPTED)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM, TapGatt.PERMISSION_READ_ENCRYPTED_MITM)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_WRITE, TapGatt.PERMISSION_WRITE)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED, TapGatt.PERMISSION_WRITE_ENCRYPTED)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM, TapGatt.PERMISSION_WRITE_ENCRYPTED_MITM)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED, TapGatt.PERMISSION_WRITE_SIGNED)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM, TapGatt.PERMISSION_WRITE_SIGNED_MITM)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_SUCCESS, TapGatt.SUCCESS)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_READ_NOT_PERMITTED, TapGatt.READ_NOT_PERMITTED)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_WRITE_NOT_PERMITTED, TapGatt.WRITE_NOT_PERMITTED)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, TapGatt.REQUEST_NOT_SUPPORTED)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_INVALID_OFFSET, TapGatt.INVALID_OFFSET)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION, TapGatt.INSUFFICIENT_AUTHORIZATION)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, TapGatt.INVALID_ATTRIBUTE_LENGTH)
        assertEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.toList(), TapGatt.NOTIFY_ON.toList())
        assertEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.toList(), TapGatt.NOTIFY_OFF.toList())
    }
}
