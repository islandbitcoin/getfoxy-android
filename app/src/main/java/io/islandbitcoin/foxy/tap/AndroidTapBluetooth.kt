package io.islandbitcoin.foxy.tap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.islandbitcoin.foxy.debug.Log
import java.util.UUID

/**
 * [TapBluetooth] on a phone: the adapter, the two runtime permissions (asked
 * through [askPermissions], MainActivity's launcher), the adapter's power
 * through `ACTION_STATE_CHANGED`, and [AndroidTapPeripheral] for the GATT
 * server and the advertiser. Everything is posted to the main looper.
 */
class AndroidTapBluetooth(
    private val context: Context,
    /** The system's question; `done(true)` granted, `done(false)` refused, `done(null)` not answered. */
    private val askPermissions: (List<String>, (Boolean?) -> Unit) -> Unit,
) : TapBluetooth {
    private val main = Handler(Looper.getMainLooper())
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val bt: BluetoothAdapter? get() = manager?.adapter

    override val clock: TapClock = HandlerClock(main)

    override val adapter: Boolean get() = bt != null

    override val advertises: Boolean
        get() {
            val a = bt ?: return false
            return a.isMultipleAdvertisementSupported && a.bluetoothLeAdvertiser != null
        }

    private val needed = BluetoothAnswer.permissions(Build.VERSION.SDK_INT)

    private fun granted(): Boolean = needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun permitted(): Boolean = granted().also { if (it) refusal.seenGranted() }

    /** Where a refusal of the system's question is kept (no backup: a new install asks afresh). */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val refusal = BluetoothRefusal(
        object : BluetoothRefusal.Store {
            override fun get() = prefs.getBoolean(REFUSED, false)
            override fun put(refused: Boolean) = prefs.edit().putBoolean(REFUSED, refused).apply()
            override fun clear() = prefs.edit().remove(REFUSED).apply()
        },
        ::granted,
    )

    /** The receiver's and the payer's together: the first-install APPROVE and the receiver's TAP ask both in one question. */
    override fun ask(done: (Boolean) -> Unit) {
        if (permitted() && scanPermitted()) return done(true)
        askPermissions(BluetoothAnswer.askedAtApprove(Build.VERSION.SDK_INT)) { answer ->
            refusal.answered(answer)
            scanRefusal.answered(answer)
            done(permitted())
        }
    }

    override fun refused(): Boolean = refusal.refused()

    // MARK: the payer (ENG-671)

    override val pays: Boolean = BluetoothAnswer.pays(Build.VERSION.SDK_INT)

    override val scans: Boolean
        get() = pays && bt?.bluetoothLeScanner != null

    private val scanNeeded = BluetoothAnswer.scanPermissions(Build.VERSION.SDK_INT)

    private fun scanGranted(): Boolean = scanNeeded.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun scanPermitted(): Boolean = scanGranted().also { if (it) scanRefusal.seenGranted() }

    private val scanRefusal = BluetoothRefusal(
        object : BluetoothRefusal.Store {
            override fun get() = prefs.getBoolean(SCAN_REFUSED, false)
            override fun put(refused: Boolean) = prefs.edit().putBoolean(SCAN_REFUSED, refused).apply()
            override fun clear() = prefs.edit().remove(SCAN_REFUSED).apply()
        },
        ::scanGranted,
    )

    override fun askScan(done: (Boolean) -> Unit) {
        if (scanPermitted()) return done(true)
        askPermissions(scanNeeded) { answer ->
            scanRefusal.answered(answer)
            done(scanPermitted())
        }
    }

    override fun scanRefused(): Boolean = scanRefusal.refused()

    override fun central(payer: TapPayer): TapCentral? {
        // the payer runs from API 31 only (BluetoothAnswer.pays): onServiceChanged, which brings the card down, is 31+ too
        if (Build.VERSION.SDK_INT < BluetoothAnswer.PAYER_SDK) return null
        val m = manager ?: return null
        val a = bt ?: return null
        if (!scanPermitted()) return null
        val scanner = a.bluetoothLeScanner ?: return null
        return AndroidTapCentral(context, m, a, scanner, payer, main)
    }

    override fun power(): TapBluetooth.Power = when (bt?.state ?: BluetoothAdapter.STATE_OFF) {
        BluetoothAdapter.STATE_ON -> TapBluetooth.Power.ON
        BluetoothAdapter.STATE_TURNING_ON -> TapBluetooth.Power.TURNING
        // STATE_TURNING_OFF is iOS's `.resetting`, which the receiver calls off
        else -> TapBluetooth.Power.OFF
    }

    override fun watch(onChange: (TapBluetooth.Power) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                // the adapter is asked, not the broadcast's extra: the broadcast only says to look
                onChange(power())
            }
        }
        // ACTION_STATE_CHANGED is a protected broadcast: only the system can send it, so it is heard as the
        // system's. NOT_EXPORTED would not do here: before API 33 ContextCompat makes it a permission only this
        // app holds, and the Bluetooth service, which sends it, does not. Delivered on the main looper.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        var on = true
        return {
            if (on) {
                on = false
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // already gone with its context
                }
            }
        }
    }

    override fun open(server: TapGattServer): TapPeripheral? {
        val m = manager ?: return null
        val a = bt ?: return null
        if (!permitted()) return null
        val advertiser = a.bluetoothLeAdvertiser ?: return null
        return AndroidTapPeripheral.open(context, m, advertiser, server, main)
    }

    companion object {
        const val PREFS = "foxy.tap"
        const val REFUSED = "bluetoothRefused"
        const val SCAN_REFUSED = "bluetoothScanRefused"
    }

    /** The main looper as a [TapClock]. */
    class HandlerClock(private val handler: Handler) : TapClock {
        override fun now(): Double = SystemClock.uptimeMillis() / 1000.0

        override fun after(seconds: Double, task: () -> Unit): TapClock.Timer {
            val token = Any()
            handler.postAtTime({ task() }, token, SystemClock.uptimeMillis() + (seconds * 1000).toLong())
            return TapClock.Timer { handler.removeCallbacksAndMessages(token) }
        }
    }
}

/**
 * The GATT server and the advertiser (`BluetoothGattServer`,
 * `BluetoothLeAdvertiser`): [TapGatt.Layout] put up as iOS puts it up, a CCCD
 * on every notify door, the advertisement as iOS's (the service UUID and
 * nothing else, connectable), and every callback posted to the main looper and
 * handed to [TapGattServer]. Made only once the permissions are granted
 * ([AndroidTapBluetooth.open]); each call checks again, so a permission taken
 * away in Settings is a refusal here and never a crash.
 */
@SuppressLint("MissingPermission") // AndroidTapBluetooth.open checks BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT; every call is guarded by [allowed]
class AndroidTapPeripheral private constructor(
    private val context: Context,
    private val advertiser: BluetoothLeAdvertiser,
    private val server: TapGattServer,
    private val main: Handler,
) : TapPeripheral {
    companion object {
        /**
         * The advertiser's power. iOS chooses none (CoreBluetooth's own); the
         * payer's near cut-off, −33 dBm (TapProtocol.swift:278), was measured
         * between two iPhones, and what an iPhone payer reads from a Pixel at
         * this power is measured by hand (docs/DEVICE-TESTS.md, "Tap to pay: the
         * Pixel receives").
         */
        const val TX_POWER = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

        fun open(context: Context, manager: BluetoothManager, advertiser: BluetoothLeAdvertiser, server: TapGattServer, main: Handler): AndroidTapPeripheral? {
            val p = AndroidTapPeripheral(context, advertiser, server, main)
            val gatt = try {
                manager.openGattServer(context, p.callback)
            } catch (e: SecurityException) {
                null
            } ?: return null
            p.gatt = gatt
            return p
        }

        /**
         * The advertisement: the service UUID and nothing else, no name, no power level, no
         * manufacturer or service data. The iPhone payer drops an advert that carries manufacturer
         * data or is not connectable (TapLink.swift 1154-1157: connectable, no manufacturer data, exactly one 128-bit service UUID), so this is held by TapGattOnDeviceTest.
         */
        fun advertiseData(service: UUID): AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(service))
            .build()

        /** Connectable, low latency, [TX_POWER], no timeout: on the air until the link takes it off. */
        fun advertiseSettings(): AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(TX_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        /** The layout as Android's objects: each door, and a CCCD under each notify door. */
        fun service(layout: TapGatt.Layout): BluetoothGattService {
            val service = BluetoothGattService(layout.service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            for (door in layout.doors) {
                val ch = BluetoothGattCharacteristic(door.uuid, door.properties, door.permissions)
                if (door.cccd) {
                    ch.addDescriptor(BluetoothGattDescriptor(TapGatt.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                }
                service.addCharacteristic(ch)
            }
            return service
        }
    }

    private var gatt: BluetoothGattServer? = null
    private var service: BluetoothGattService? = null
    private val devices = HashMap<String, BluetoothDevice>()
    private var closed = false

    private fun allowed(): Boolean = !closed && BluetoothAnswer.permissions(Build.VERSION.SDK_INT).all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onMain(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    private val advertised = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = onMain { server.advertiseStarted() }
        override fun onStartFailure(errorCode: Int) = onMain { server.advertiseFailed(errorCode) }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) =
            onMain { server.serviceAdded(status == BluetoothGatt.GATT_SUCCESS) }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = onMain {
            val id = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                devices[id] = device
                server.connected(id)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                devices.remove(id)
                server.disconnected(id)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = onMain {
            devices[device.address] = device
            server.mtuChanged(device.address, mtu)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = onMain {
            devices[device.address] = device
            server.characteristicWrite(device.address, requestId, characteristic.uuid, preparedWrite, responseNeeded, offset, value?.copyOf())
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = onMain {
            devices[device.address] = device
            server.executeWrite(device.address, requestId, execute)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) = onMain {
            devices[device.address] = device
            server.characteristicRead(device.address, requestId, offset)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = onMain {
            devices[device.address] = device
            server.descriptorRead(device.address, requestId, descriptor.characteristic.uuid, descriptor.uuid, offset)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = onMain {
            devices[device.address] = device
            server.descriptorWrite(device.address, requestId, descriptor.characteristic.uuid, descriptor.uuid, preparedWrite, responseNeeded, offset, value?.copyOf())
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = onMain {
            server.notificationSent(device.address, status)
        }
    }

    override fun addService(layout: TapGatt.Layout): Boolean {
        val g = gatt ?: return false
        if (!allowed()) return false
        val s = service(layout)
        service = s
        return try {
            g.addService(s)
        } catch (e: SecurityException) {
            false
        }
    }

    override fun startAdvertising(service: UUID) {
        if (!allowed()) {
            main.post { server.advertiseFailed(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR) }
            return
        }
        try {
            advertiser.startAdvertising(advertiseSettings(), advertiseData(service), advertised)
        } catch (e: SecurityException) {
            main.post { server.advertiseFailed(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR) }
        } catch (e: IllegalStateException) {
            // the adapter went off between the state and this
            main.post { server.advertiseFailed(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR) }
        }
    }

    override fun stopAdvertising() {
        if (closed) return
        try {
            advertiser.stopAdvertising(advertised)
        } catch (e: SecurityException) {
            Log.line("[tap] receive: could not stop advertising (${e.javaClass.simpleName})")
        } catch (e: IllegalStateException) {
            // the adapter is off: nothing is on the air
        }
    }

    override fun notify(device: String, door: UUID, value: ByteArray): Boolean {
        val g = gatt ?: return false
        val d = devices[device] ?: return false
        val ch = service?.getCharacteristic(door) ?: return false
        if (!allowed()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.notifyCharacteristicChanged(d, ch, false, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = value
                @Suppress("DEPRECATION")
                g.notifyCharacteristicChanged(d, ch, false)
            }
        } catch (e: SecurityException) {
            false
        }
    }

    override fun respond(device: String, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val g = gatt ?: return
        val d = devices[device] ?: return
        if (!allowed()) return
        try {
            g.sendResponse(d, requestId, status, offset, value)
        } catch (e: SecurityException) {
            Log.line("[tap] receive: could not answer a request (${e.javaClass.simpleName})")
        }
    }

    override fun close() {
        if (closed) return
        stopAdvertising()
        closed = true
        val g = gatt
        gatt = null
        try {
            if (g != null) {
                for (d in devices.values) g.cancelConnection(d)
                g.clearServices()
                g.close()
            }
        } catch (e: SecurityException) {
            Log.line("[tap] receive: the GATT server closed without its permission (${e.javaClass.simpleName})")
        }
        devices.clear()
        service = null
    }
}
