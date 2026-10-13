package io.islandbitcoin.foxy.tap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import io.islandbitcoin.foxy.debug.Log
import java.util.UUID

/**
 * [TapCentral] on a phone: `BluetoothLeScanner` and `BluetoothGatt`, every
 * callback posted to the main looper and handed to [TapPayer] (TapLink.swift
 * `TapPayer`'s `CBCentralManager` and `CBPeripheral` at 8df0bb1).
 *
 * - **The scan.** No hardware filter ([TapScanRules.hardwareFilters] is empty):
 *   the receiver's service is new for every invoice, so it cannot be named
 *   before it is heard, and the "any service UUID" filter used until ENG-679 (a
 *   service filter with an all-zero mask) matched nothing on the Pixel 8 Pro's
 *   controller. An unfiltered scan is allowed in the foreground, and Foxy scans
 *   only in front ([TapPayer] stops it when Foxy is put away); Android gives an
 *   unfiltered scan nothing with the screen off, which never happens while Foxy
 *   is in front. Low latency, every advertisement reported (iOS's
 *   `AllowDuplicates`). What counts as a Foxy is decided from the
 *   advertisement's own bytes ([TapAdBytes], [TapScanRules]). Android's
 *   five-starts-in-thirty-seconds limit is kept ([TapScanBudget]): a start that
 *   would be one too many is made when it no longer would be.
 * - **The link.** `connectGatt(…, autoConnect = false, TRANSPORT_LE)`, then an MTU
 *   of [MTU] asked for (CoreBluetooth negotiates its own), then the services.
 *   Every operation goes through one [TapGattQueue] per link.
 * - **Subscribing** turns notifications on locally and writes the door's CCCD
 *   (0x2902), as `setNotifyValue` does; the answer is the descriptor write's.
 * - **Writes** are with response. One longer than MTU − 3 (up to 512 bytes,
 *   [TapPayer.WRITE_LENGTH]) is sent by the stack as a long write: prepared
 *   writes and an execute, as CoreBluetooth does with
 *   `maximumWriteValueLength(.withResponse)`.
 *
 * Made only once the payer's permissions are granted
 * ([AndroidTapBluetooth.central]); each call checks again, so a permission taken
 * away in Settings is a failure here and never a crash.
 */
@SuppressLint("MissingPermission") // AndroidTapBluetooth.central checks BLUETOOTH_SCAN and BLUETOOTH_CONNECT; every call is guarded by [allowed]
class AndroidTapCentral(
    private val context: Context,
    @Suppress("unused") private val manager: BluetoothManager,
    private val adapter: BluetoothAdapter,
    private val scanner: BluetoothLeScanner,
    private val payer: TapPayer,
    private val main: Handler,
) : TapCentral {
    companion object {
        /** The ATT MTU asked for: Android's most (517 = 512 bytes of value + 5). */
        const val MTU = 517

        /** A phone that never answers the MTU question is not waited on longer than this before the services are found. */
        const val MTU_WAIT = 2.0

        /** Which callback answers each queued operation ([TapGattQueue.done]): a late one is not taken for another's. */
        private const val MTU_ANSWER = "MTU"
        private const val DISCOVERY_ANSWER = "service discovery"
        private const val DESCRIPTOR_ANSWER = "CCCD write"
        private const val WRITE_ANSWER = "write"
        private const val READ_ANSWER = "read"
        private const val RSSI_ANSWER = "signal reading"

        /**
         * The scan's hardware filters, as Android's objects: [TapScanRules.hardwareFilters], which is
         * empty (an unfiltered foreground scan; iOS's rules then decide in software, ENG-679).
         */
        fun scanFilters(): List<ScanFilter> = TapScanRules.hardwareFilters.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it.service), ParcelUuid(it.mask)).build()
        }

        /** Low latency, every advertisement, as soon as it is heard. */
        fun scanSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()

        /**
         * Android counts scan starts per app, not per scanner: one budget for the process, so a
         * listen stopped and started again by the page (a new [TapPayer], a new central) is
         * counted with the one before. Main thread only.
         */
        private val budget = TapScanBudget()
    }

    private val clock = AndroidTapBluetooth.HandlerClock(main)
    private var closed = false
    private var scanning = false
    private var scanWanted = false
    private var scanLater: TapClock.Timer? = null
    private val devices = HashMap<String, BluetoothDevice>()
    private val links = HashMap<String, Link>()

    private fun allowed(): Boolean = !closed && BluetoothAnswer.scanPermissions(Build.VERSION.SDK_INT).all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun say(what: String) = Log.line("[tap] pay: $what")

    // MARK: the scan

    private val scanned = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val bytes = result.scanRecord?.bytes
            val read = TapAdBytes.read(bytes)
            val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else true
            val ad = TapAdvert(device.address, connectable, read.manufacturerData, read.services, read.full, result.rssi)
            main.post {
                if (closed || !scanning) return@post
                devices[device.address] = device
                payer.discovered(ad)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                if (closed) return@post
                // the same callback is already scanning: nothing failed
                if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return@post say("the scan was already running")
                scanning = false
                say("the scan failed (error $errorCode)")
                // a scan stopped since is not the payer's news; one it still wants is started again by it
                if (scanWanted) payer.scanFailed(errorCode)
            }
        }
    }

    override fun startScan(): Boolean {
        if (!allowed()) return false
        scanWanted = true
        if (scanning || scanLater != null) return true
        val now = SystemClock.uptimeMillis() / 1000.0
        val wait = budget.wait(now)
        if (wait > 0) {
            say("Android allows five scans in thirty seconds; listening again in ${"%.1f".format(wait)} s")
            scanLater = clock.after(wait) {
                scanLater = null
                if (scanWanted && !closed) startNow()
            }
            return true
        }
        return startNow()
    }

    private fun startNow(): Boolean {
        if (!allowed()) return false
        return try {
            scanner.startScan(scanFilters(), scanSettings(), scanned)
            budget.started(SystemClock.uptimeMillis() / 1000.0)
            scanning = true
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            // the adapter went off between the state and this
            false
        }
    }

    override fun stopScan() {
        scanWanted = false
        scanLater?.cancel()
        scanLater = null
        if (!scanning) return
        scanning = false
        try {
            scanner.stopScan(scanned)
        } catch (e: SecurityException) {
            say("could not stop the scan (${e.javaClass.simpleName})")
        } catch (e: IllegalStateException) {
            // the adapter is off: nothing is listening
        }
    }

    // MARK: the links

    /** One connection: its GATT, its queue, and whether it ever came up. */
    private inner class Link(val address: String) {
        var gatt: BluetoothGatt? = null
        var up = false
        var open = true
        val queue = TapGattQueue(clock, say = ::say)

        fun live(): Boolean = open && !closed && links[address] === this

        fun characteristic(service: UUID, door: UUID): BluetoothGattCharacteristic? =
            gatt?.getService(service)?.getCharacteristic(door)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                main.post {
                    if (!live()) return@post
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        up = true
                        payer.connected(address)
                        return@post
                    }
                    if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        val was = up
                        drop()
                        if (was) {
                            payer.disconnected(address, if (status == BluetoothGatt.GATT_SUCCESS) null else "status $status")
                        } else {
                            payer.connectFailed(address)
                        }
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                main.post { if (live()) queue.done(MTU_ANSWER) }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                // read here, on the binder thread, the moment Android has them
                // null: discovery failed (often 129 or 133, and passing), not a phone without the service
                val found = if (status == BluetoothGatt.GATT_SUCCESS) {
                    g.services.associate { s -> s.uuid to s.characteristics.map { it.uuid }.toSet() }
                } else {
                    null
                }
                main.post {
                    if (!live()) return@post
                    if (!queue.done(DISCOVERY_ANSWER)) return@post
                    payer.servicesFound(address, found)
                }
            }

            // API 31+, and so is the payer (BluetoothAnswer.pays): no API on which this never comes
            override fun onServiceChanged(g: BluetoothGatt) {
                main.post { if (live()) payer.servicesChanged(address) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                val door = d.characteristic.uuid
                main.post {
                    if (!live()) return@post
                    if (!queue.done(DESCRIPTOR_ANSWER)) return@post
                    payer.notifyState(address, door, if (status == BluetoothGatt.GATT_SUCCESS) null else "the CCCD write failed (status $status)")
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                val door = ch.uuid
                main.post {
                    if (!live()) return@post
                    if (!queue.done(WRITE_ANSWER)) return@post
                    payer.wrote(address, door, if (status == BluetoothGatt.GATT_SUCCESS) null else "ATT error $status")
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                read(ch.uuid, value.copyOf(), status)
            }

            @Deprecated("Android 13 hands the value with the callback")
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                read(ch.uuid, ch.value?.copyOf(), status)
            }

            private fun read(door: UUID, value: ByteArray?, status: Int) {
                main.post {
                    if (!live()) return@post
                    if (!queue.done(READ_ANSWER)) return@post
                    payer.valueRead(address, door, if (status == BluetoothGatt.GATT_SUCCESS) value else null)
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                changed(ch.uuid, value.copyOf())
            }

            @Deprecated("Android 13 hands the value with the callback")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                changed(ch.uuid, ch.value?.copyOf() ?: ByteArray(0))
            }

            private fun changed(door: UUID, value: ByteArray) {
                main.post { if (live()) payer.valueChanged(address, door, value) }
            }

            override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                main.post {
                    if (!live()) return@post
                    if (!queue.done(RSSI_ANSWER)) return@post
                    payer.rssi(address, rssi, status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        /** The link is gone or let go: its queue answers nothing more, and its GATT is closed. */
        fun drop() {
            open = false
            up = false
            if (links[address] === this) links.remove(address)
            queue.close("the link is closed")
            val g = gatt
            gatt = null
            try {
                g?.disconnect()
                g?.close()
            } catch (e: SecurityException) {
                say("the link closed without its permission (${e.javaClass.simpleName})")
            }
        }
    }

    override fun connect(device: String) {
        links[device]?.drop()
        val d = devices[device] ?: try {
            adapter.getRemoteDevice(device)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (d == null || !allowed()) {
            clock.after(0.0) { payer.connectFailed(device) }
            return
        }
        val link = Link(device)
        links[device] = link
        val g = try {
            d.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            null
        }
        if (g == null) {
            link.drop()
            clock.after(0.0) { payer.connectFailed(device) }
            return
        }
        link.gatt = g
    }

    private fun queued(
        device: String,
        name: String,
        answer: String,
        start: (Link, BluetoothGatt) -> Boolean,
        failed: (String) -> Unit,
        stall: Double = TapGattQueue.STALL,
    ) {
        val link = links[device]
        if (link == null) {
            clock.after(0.0) { failed("no link to that phone") }
            return
        }
        link.queue.add(
            name,
            start = {
                val g = link.gatt
                g != null && allowed() && start(link, g)
            },
            // a link let go says nothing more ([TapCentral.disconnect])
            failed = { why -> if (link.live()) failed(why) },
            stall = stall,
            answer = answer,
        )
    }

    override fun discover(device: String) {
        // the MTU first; a phone that will not take the question is not waited for
        queued(device, "an MTU of $MTU", MTU_ANSWER, start = { _, g -> g.requestMtu(MTU) }, failed = {}, stall = MTU_WAIT)
        queued(device, "service discovery", DISCOVERY_ANSWER, start = { _, g -> g.discoverServices() }, failed = { payer.servicesFound(device, null) })
    }

    override fun setNotify(device: String, service: UUID, door: UUID, on: Boolean) {
        queued(device, "notifications ${if (on) "on" else "off"}", DESCRIPTOR_ANSWER, start = { link, g ->
            val ch = link.characteristic(service, door) ?: return@queued false
            val cccd = ch.getDescriptor(TapGatt.CCCD) ?: return@queued false
            if (!g.setCharacteristicNotification(ch, on)) return@queued false
            val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }, failed = { why -> payer.notifyState(device, door, why) })
    }

    override fun write(device: String, service: UUID, door: UUID, value: ByteArray) {
        val bytes = value.copyOf()
        queued(device, "a write of ${bytes.size} bytes", WRITE_ANSWER, start = { link, g ->
            val ch = link.characteristic(service, door) ?: return@queued false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        }, failed = { why -> payer.wrote(device, door, why) })
    }

    override fun read(device: String, service: UUID, door: UUID) {
        queued(device, "a read", READ_ANSWER, start = { link, g ->
            val ch = link.characteristic(service, door) ?: return@queued false
            g.readCharacteristic(ch)
        }, failed = { payer.valueRead(device, door, null) })
    }

    override fun readRssi(device: String) {
        queued(device, "a signal reading", RSSI_ANSWER, start = { _, g -> g.readRemoteRssi() }, failed = { payer.rssi(device, 0, false) })
    }

    override fun isConnected(device: String): Boolean = links[device]?.let { it.up && it.live() } == true

    override fun disconnect(device: String) {
        links[device]?.drop()
    }

    override fun close() {
        if (closed) return
        stopScan()
        for (l in links.values.toList()) l.drop()
        links.clear()
        devices.clear()
        closed = true
    }
}
