package io.islandbitcoin.foxy.tap

import java.util.UUID

/**
 * The receiving phone's GATT service, as iOS puts it up (Foxy/Bluetooth/TapLink.swift
 * `TapReceiver.advertise`, 481-504 at 8df0bb1), and what the ATT layer answers.
 *
 * One primary service on the per-invoice UUID, and under it, in iOS's order:
 *
 * | door | UUID | properties | permissions |
 * |---|---|---|---|
 * | out | `foxy tap v2 out` | notify | read |
 * | in | `foxy tap v2 in` | write (with response) | write |
 * | near left | `foxy tap v2 near left` | notify | read |
 * | near right | `foxy tap v2 near right` | notify | read |
 *
 * Every notify door carries a Client Characteristic Configuration descriptor
 * (0x2902), which CoreBluetooth adds by itself and Android's GATT server does
 * not: without it a central has nothing to write to subscribe. No door asks for
 * encryption or bonding (iOS's plain read and write permissions, not the `…EncryptionRequired`
 * ones), and every read is refused (`didReceiveRead` answers `.readNotPermitted`).
 *
 * The numbers are Android's own constants (`BluetoothGattCharacteristic`,
 * `BluetoothGattDescriptor`, `BluetoothGatt`), spelled out so the JVM tests can
 * hold the layout to iOS without the Android framework.
 */
object TapGatt {
    /** The Client Characteristic Configuration descriptor: notifications on (0x0001) or off. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // BluetoothGattCharacteristic.PROPERTY_* and PERMISSION_*
    const val PROPERTY_READ = 0x02
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20
    const val PERMISSION_READ = 0x01
    const val PERMISSION_READ_ENCRYPTED = 0x02
    const val PERMISSION_READ_ENCRYPTED_MITM = 0x04
    const val PERMISSION_WRITE = 0x10
    const val PERMISSION_WRITE_ENCRYPTED = 0x20
    const val PERMISSION_WRITE_ENCRYPTED_MITM = 0x40
    const val PERMISSION_WRITE_SIGNED = 0x80
    const val PERMISSION_WRITE_SIGNED_MITM = 0x100

    /** Every permission that would make a central pair or encrypt first: none of the doors has one. */
    const val ANY_SECURITY = PERMISSION_READ_ENCRYPTED or PERMISSION_READ_ENCRYPTED_MITM or PERMISSION_WRITE_ENCRYPTED or
        PERMISSION_WRITE_ENCRYPTED_MITM or PERMISSION_WRITE_SIGNED or PERMISSION_WRITE_SIGNED_MITM

    // ATT error codes (Core 5.4 Vol 3 Part F 3.4.1.1), as BluetoothGatt sends them
    const val SUCCESS = 0x00
    const val READ_NOT_PERMITTED = 0x02
    const val WRITE_NOT_PERMITTED = 0x03
    const val REQUEST_NOT_SUPPORTED = 0x06
    const val INVALID_OFFSET = 0x07
    const val INSUFFICIENT_AUTHORIZATION = 0x08
    const val PREPARE_QUEUE_FULL = 0x09
    const val INVALID_ATTRIBUTE_LENGTH = 0x0D
    const val CCCD_IMPROPERLY_CONFIGURED = 0xFD

    /** What a CCCD holds: notifications on, and off. */
    val NOTIFY_ON = byteArrayOf(0x01, 0x00)
    val NOTIFY_OFF = byteArrayOf(0x00, 0x00)

    /** The ATT MTU before a central asks for more, and the most any attribute value may be. */
    const val DEFAULT_MTU = 23
    const val MOST_VALUE = 512

    /**
     * The most one central may have queued in prepared writes before it executes
     * them. iOS writes at most `maximumWriteValueLength(.withResponse)` per write
     * (512 bytes, one attribute value: TapLink.swift:1776-1794), which a long write
     * carries in pieces of MTU − 5; this is several times that, and no more.
     */
    const val MOST_PREPARED = 4 * MOST_VALUE

    /**
     * The most one notification may carry to a central with this MTU:
     * `maximumUpdateValueLength` on iOS is the MTU less the three bytes of the
     * ATT header, and never less than 20 (TapLink.swift `push`, `max(20, …)`);
     * Android sends no attribute value over 512 bytes.
     */
    fun notifyLength(mtu: Int): Int = maxOf(20, minOf(mtu - 3, MOST_VALUE))

    /** One characteristic: its UUID, properties and permissions, and whether it carries a CCCD. */
    data class Door(val uuid: UUID, val properties: Int, val permissions: Int) {
        /** A door a central subscribes to carries a CCCD; the others carry no descriptor at all. */
        val cccd: Boolean get() = properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0
    }

    /** The service, its doors in iOS's order, and which is which. */
    class Layout(val service: UUID) {
        val out = Door(TapCrypto.outCharacteristic(service), PROPERTY_NOTIFY, PERMISSION_READ)
        val into = Door(TapCrypto.inCharacteristic(service), PROPERTY_WRITE, PERMISSION_WRITE)
        val nearLeft = Door(TapCrypto.nearCharacteristic(service, "left"), PROPERTY_NOTIFY, PERMISSION_READ)
        val nearRight = Door(TapCrypto.nearCharacteristic(service, "right"), PROPERTY_NOTIFY, PERMISSION_READ)

        /** `svc.characteristics = [out, into, nearL, nearR]` (TapLink.swift:496). */
        val doors: List<Door> = listOf(out, into, nearLeft, nearRight)

        /** Which edge of this screen a near door says, or null for one that is not a near door. */
        fun edge(door: UUID): String? = when (door) {
            nearLeft.uuid -> "left"
            nearRight.uuid -> "right"
            else -> null
        }

        fun nearDoor(edge: String): UUID = if (edge == "left") nearLeft.uuid else nearRight.uuid

        fun door(uuid: UUID): Door? = doors.firstOrNull { it.uuid == uuid }
    }
}

/**
 * What the Android radio does for [TapGattServer]: the GATT server and the
 * advertiser, and nothing else. Every call is made on the link's thread; every
 * answer comes back to the server's event methods on the same thread. A central
 * is named by its address.
 */
interface TapPeripheral {
    /** Puts the service up; [TapGattServer.serviceAdded] follows. False when it could not even be asked. */
    fun addService(layout: TapGatt.Layout): Boolean

    /**
     * On the air with [service] and nothing else: connectable, no device name, no
     * TX power level, no manufacturer or service data, no scan response.
     * [TapGattServer.advertiseStarted] or [TapGattServer.advertiseFailed] follows.
     */
    fun startAdvertising(service: UUID)

    fun stopAdvertising()

    /** One notification (never an indication) on [door] to [device]; false when the stack would not take it. */
    fun notify(device: String, door: UUID, value: ByteArray): Boolean

    /** The ATT response to a request that wanted one. */
    fun respond(device: String, requestId: Int, status: Int, offset: Int, value: ByteArray?)

    /** Off the air, the service removed, every connection let go, the GATT server closed. */
    fun close()
}
