package io.islandbitcoin.foxy.tap

import java.util.UUID

/**
 * What the Android radio does for [TapPayer]: a BLE scanner and GATT client,
 * and nothing else (TapLink.swift `TapPayer`'s `CBCentralManager` and
 * `CBPeripheral` calls at 8df0bb1). Every call is made on the payer's thread;
 * every answer comes back to the payer's event methods on that same thread. A
 * receiver is named by its address.
 *
 * Each GATT operation is answered once, by the payer method named on it, even
 * when it could not be started (then with an error): the payer never waits on
 * a callback that will not come. Android takes one GATT operation at a time per
 * connection, so the radio queues them.
 */
interface TapCentral {
    /**
     * Listens for receivers; each advertisement goes to [TapPayer.discovered]. False when Android would not start a scan;
     * a scan Android fails to start after this answered true is [TapPayer.scanFailed].
     */
    fun startScan(): Boolean

    fun stopScan()

    /** [TapPayer.connected], or [TapPayer.connectFailed]; later [TapPayer.disconnected] if the receiver drops it. */
    fun connect(device: String)

    /**
     * Asks for a bigger MTU and then finds the services: [TapPayer.servicesFound]
     * with every service's characteristics, or null when discovery failed (an
     * empty map is a phone that serves nothing, which is not the same thing).
     */
    fun discover(device: String)

    /** Notifications on or off for [door] of [service]: the CCCD written, then [TapPayer.notifyState]. */
    fun setNotify(device: String, service: UUID, door: UUID, on: Boolean)

    /** One write with response; one longer than the MTU allows is a long write, which the stack makes. [TapPayer.wrote] follows. */
    fun write(device: String, service: UUID, door: UUID, value: ByteArray)

    /** One read; [TapPayer.valueRead] follows (null on a failure). */
    fun read(device: String, service: UUID, door: UUID)

    /** The open link's own signal strength; [TapPayer.rssi] follows. */
    fun readRssi(device: String)

    /** Whether the link to [device] is up now. */
    fun isConnected(device: String): Boolean

    /** Lets the link go. Nothing more is said of it, not even that it closed. */
    fun disconnect(device: String)

    /** Stops scanning and lets every link go. */
    fun close()
}

/**
 * One advertisement as the scan heard it: who sent it, whether it can be
 * connected to, whether it carries manufacturer data, its service UUIDs (each
 * with whether it was written as a full 128-bit UUID), and the signal strength.
 */
data class TapAdvert(
    val device: String,
    val connectable: Boolean,
    val manufacturerData: Boolean,
    val services: List<UUID>,
    /** For each of [services], whether the advertisement carried it as 128 bits (not a 16- or 32-bit short form). */
    val full: List<Boolean>,
    val rssi: Int,
)

/**
 * What counts as a Foxy receiver's advertisement, as the iPhone payer decides
 * (TapLink.swift `centralManager(_:didDiscover:…)`, 1154-1158 at 8df0bb1):
 * connectable, no manufacturer data, and exactly one service UUID, a full
 * 128-bit one. A Watch, AirPods and everything doing Apple's Continuity carry
 * manufacturer data; a short-form UUID is a standard service, not a receiver's
 * per-invoice one.
 *
 * The scan's own filter cannot name the service: it is new for every invoice
 * (TAP-SPEC §3). So the scan is filtered on "has a service UUID" (a filter on
 * the service field whose mask is all zeros), which keeps it a filtered scan for
 * Android, and these rules decide in software, exactly as iOS's do.
 */
object TapScanRules {
    /** The Bluetooth base UUID's low half: a 16- or 32-bit UUID written out as 128 bits ends in it. */
    private const val BASE_LSB = -0x7fffff7fa064cb05L // 0x800000805F9B34FB
    private const val BASE_MSB_LOW = 0x0000_1000L

    /** Whether a 128-bit UUID is really a short one written out on the Bluetooth base. */
    fun isShortForm(uuid: UUID): Boolean =
        uuid.leastSignificantBits == BASE_LSB && (uuid.mostSignificantBits and 0xFFFF_FFFFL) == BASE_MSB_LOW

    /** The receiver's service, or null when this is not a Foxy's advertisement. */
    fun service(ad: TapAdvert): UUID? {
        if (!ad.connectable || ad.manufacturerData) return null
        if (ad.services.size != 1) return null
        val full = ad.full.getOrNull(0) ?: !isShortForm(ad.services[0])
        return if (full) ad.services[0] else null
    }
}

/**
 * The advertisement's own bytes, read as CoreBluetooth reads them into
 * `CBAdvertisementDataServiceUUIDsKey` and `…ManufacturerDataKey`: the
 * length-type-value structures of the advertising data and the scan response
 * (Core 5.4 Vol 3 Part C 11; Supplement Part A 1.1 and 1.4). Android's
 * `ScanRecord.getServiceUuids` writes every short UUID out as 128 bits, which
 * would let a 16-bit standard service pass as a receiver's; this keeps the form.
 */
object TapAdBytes {
    data class Read(val services: List<UUID>, val full: List<Boolean>, val manufacturerData: Boolean)

    private const val INCOMPLETE_16 = 0x02
    private const val COMPLETE_16 = 0x03
    private const val INCOMPLETE_32 = 0x04
    private const val COMPLETE_32 = 0x05
    private const val INCOMPLETE_128 = 0x06
    private const val COMPLETE_128 = 0x07
    private const val MANUFACTURER = 0xFF

    private const val BASE_LSB = -0x7fffff7fa064cb05L

    private fun shortUuid(value: Long): UUID = UUID((value shl 32) or 0x0000_1000L, BASE_LSB)

    private fun le(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }

    /** The structures in [bytes]; a structure that runs past the end, or a length of zero, ends the read. */
    fun read(bytes: ByteArray?): Read {
        val services = ArrayList<UUID>()
        val full = ArrayList<Boolean>()
        var manufacturer = false
        val b = bytes ?: return Read(services, full, false)
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xff
            if (len == 0 || i + 1 + len > b.size) break
            val type = b[i + 1].toInt() and 0xff
            val start = i + 2
            val end = i + 1 + len
            when (type) {
                INCOMPLETE_16, COMPLETE_16 -> {
                    var j = start
                    while (j + 2 <= end) {
                        services.add(shortUuid(le(b, j, 2)))
                        full.add(false)
                        j += 2
                    }
                }
                INCOMPLETE_32, COMPLETE_32 -> {
                    var j = start
                    while (j + 4 <= end) {
                        services.add(shortUuid(le(b, j, 4)))
                        full.add(false)
                        j += 4
                    }
                }
                INCOMPLETE_128, COMPLETE_128 -> {
                    var j = start
                    while (j + 16 <= end) {
                        // little-endian on the air: the last byte first
                        val lsb = le(b, j, 8)
                        val msb = le(b, j + 8, 8)
                        services.add(UUID(msb, lsb))
                        full.add(true)
                        j += 16
                    }
                }
                MANUFACTURER -> manufacturer = true
            }
            i = end
        }
        return Read(services, full, manufacturer)
    }
}
