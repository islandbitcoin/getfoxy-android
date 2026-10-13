package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.debug.Log
import java.util.UUID

/**
 * The receiver's radio on Android: [TapReceiverLink]'s [TapReceiverLink.Radio]
 * over a GATT server and an advertiser ([TapPeripheral]), and the GATT server's
 * callbacks turned into what CoreBluetooth hands iOS's `TapReceiver`
 * (Foxy/Bluetooth/TapLink.swift at 8df0bb1):
 *
 * - **Subscribing is a CCCD write.** CoreBluetooth keeps each central's
 *   Client Characteristic Configuration itself and says `didSubscribeTo` /
 *   `didUnsubscribeFrom`; here a write of 0x0001 to a notify door's CCCD is the
 *   subscribe, 0x0000 the unsubscribe, and a central that disconnects has
 *   unsubscribed from every door it held (as iOS says on a disconnect). Nothing
 *   is notified to a central that has not turned notifications on.
 * - **Long writes.** iOS writes up to 512 bytes at a time with response
 *   (`maximumWriteValueLength(.withResponse)`, TapLink.swift:1776-1794), which
 *   over an MTU smaller than that is a long write: prepared writes, each answered
 *   with its own bytes, and then an execute. The pieces are queued per central
 *   (at most [TapGatt.MOST_PREPARED] bytes) and handed to the link together on
 *   the execute, as CoreBluetooth hands `didReceiveWrite` its requests; the
 *   execute is answered with the link's verdict, success or insufficient
 *   authorization (TapLink.swift:669-685).
 * - **Notifications, one at a time.** Android takes one notification and
 *   refuses the next until `onNotificationSent`; that is
 *   `peripheralManagerIsReady(toUpdateSubscribers:)`. A notification is at most
 *   the central's MTU − 3 bytes ([TapGatt.notifyLength]).
 * - **The near doors.** A subscribe on `near left` or `near right` is the payer's
 *   word that it is close, and which edge of this screen the mark goes on
 *   (`nearby` with the edge, `far` when the last one leaves), and it is told one
 *   byte down that door: bit 0 this phone is top-down, bit 1 it is on the amount
 *   screen (`early`). A payer that refuses the byte 40 times running is dropped
 *   as not there (TapLink.swift:97-209, 527-545, 610-621).
 * - **Reads are refused** (`didReceiveRead` → `.readNotPermitted`); a CCCD read
 *   answers what that central set.
 * - **Nothing advertises in the background**: [foreground] false takes this
 *   phone off the air and keeps it off, whatever the link asks.
 *
 * Every method runs on the link's thread (the main looper on a phone). After
 * [close] the server calls nothing more on the link.
 */
class TapGattServer(
    private val layout: TapGatt.Layout,
    private val clock: TapClock,
    private val onEvent: (TapStage, String?) -> Unit,
    private val diary: (String) -> Unit = Log::line,
    /** Told once, when the link has let go of the radio for good. */
    private val onClosed: () -> Unit = {},
) : TapReceiverLink.Radio {
    companion object {
        /** A payer that refuses the door byte this many times running is not there (TapLink.swift:166). */
        const val MOST_DOOR_REFUSALS = 40

        /** A notification Android said nothing more about in this long is taken as gone, and the next one goes. */
        const val NOTIFY_STALL = 1.0

        /** After the stack refused a notification, it is offered again this soon. */
        const val NOTIFY_RETRY = 0.05
    }

    private lateinit var link: TapReceiverLink

    /** The link this radio serves. Set once, before anything else. */
    fun attach(link: TapReceiverLink) {
        this.link = link
    }

    private var peripheral: TapPeripheral? = null
    private var closed = false
    private var added = false
    private var adding = false
    private var startPending = false
    private var advertisingNow = false

    /** Whether the link has asked to be on the air and not since come off. */
    private var wantOnAir = false

    /** Foxy is in front. False keeps this phone off the air (S33, docs/SECURITY-INVARIANTS.md). */
    var foreground = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                if (advertisingNow || startPending) say("Foxy was put away; off the air")
                offAir()
            } else if (wantOnAir) {
                advertise()
            }
        }

    private val mtu = HashMap<String, Int>()
    private val subscriptions = HashMap<String, MutableSet<UUID>>()
    private val prepared = HashMap<String, MutableList<Pair<UUID, ByteArray>>>()
    private val preparedBytes = HashMap<String, Int>()

    // one notification in the air at a time, and to whom
    private var inFlightTo: String? = null
    private var stallTimer: TapClock.Timer? = null
    private var retryTimer: TapClock.Timer? = null

    // the near doors (TapLink.swift:97-209)
    private val nearCentrals = LinkedHashMap<String, String>()
    private val doorOwedTo = LinkedHashSet<String>()
    private val doorRefusals = HashMap<String, Int>()
    private var doorSaid = ""

    /** This phone is top-down: bit 0 of the door byte. iOS at 8df0bb1 never sets it (FoxyBridge+Tap.swift `turned`: the flip is off). */
    var turned = false
        set(value) {
            if (field == value) return
            field = value
            tellTurned()
        }

    /** On the air from the amount screen, nothing to pay yet: bit 1 of the door byte. */
    var early = false
        set(value) {
            if (field == value) return
            field = value
            tellTurned()
        }

    private val doorByte: ByteArray get() = byteArrayOf(((if (turned) 1 else 0) or (if (early) 2 else 0)).toByte())

    private fun say(what: String) = diary("[tap] receive: $what")

    // MARK: the radio coming and going

    /** The radio is on and this is its GATT server and advertiser. Nothing is put up until the link asks. */
    fun radioUp(p: TapPeripheral) {
        if (closed) {
            p.close()
            return
        }
        peripheral?.close()
        peripheral = p
        added = false
        adding = false
        startPending = false
        advertisingNow = false
    }

    /**
     * The radio went (Bluetooth turned off): every connection is gone with it, so
     * every central has unsubscribed, and the service has to go up again.
     */
    fun radioDown() {
        val p = peripheral ?: return
        peripheral = null
        p.close()
        added = false
        adding = false
        startPending = false
        advertisingNow = false
        for (device in subscriptions.keys.toList()) disconnected(device)
        mtu.clear()
        prepared.clear()
        preparedBytes.clear()
        clearInFlight()
    }

    // MARK: TapReceiverLink.Radio

    override fun advertise() {
        wantOnAir = true
        if (closed || !foreground) return
        val p = peripheral ?: return
        if (!added) {
            if (adding) return
            adding = true
            if (!p.addService(layout)) {
                adding = false
                say("the service would not go up")
            }
            return
        }
        if (advertisingNow || startPending) return
        startPending = true
        p.startAdvertising(layout.service)
    }

    override fun stopAdvertising() {
        wantOnAir = false
        offAir()
    }

    private fun offAir() {
        if (!advertisingNow && !startPending) return
        advertisingNow = false
        startPending = false
        peripheral?.stopAdvertising()
    }

    override fun notify(central: String, piece: ByteArray): Boolean {
        val p = peripheral ?: return false
        if (layout.out.uuid !in subscriptions[central].orEmpty()) {
            // what CoreBluetooth does with an update for a central that is not subscribed: it goes nowhere
            say("a notification for a phone not listening on the out door was dropped")
            return true
        }
        if (inFlightTo != null) return false
        if (!p.notify(central, layout.out.uuid, piece)) {
            retrySoon()
            return false
        }
        sent(central)
        return true
    }

    override fun notifyLength(central: String): Int = TapGatt.notifyLength(mtu[central] ?: TapGatt.DEFAULT_MTU)

    override fun close() {
        if (closed) return
        closed = true
        wantOnAir = false
        clearInFlight()
        retryTimer?.cancel()
        retryTimer = null
        peripheral?.close()
        peripheral = null
        added = false
        adding = false
        startPending = false
        advertisingNow = false
        mtu.clear()
        subscriptions.clear()
        prepared.clear()
        preparedBytes.clear()
        nearCentrals.clear()
        doorOwedTo.clear()
        doorRefusals.clear()
        doorSaid = ""
        onClosed()
    }

    // MARK: the GATT server's and the advertiser's callbacks, on the link's thread

    /** BluetoothGattServerCallback.onServiceAdded. */
    fun serviceAdded(ok: Boolean) {
        if (closed || peripheral == null) return
        adding = false
        if (!ok) {
            say("the service would not go up")
            return
        }
        added = true
        link.serviceAdded()
    }

    /** AdvertiseCallback.onStartSuccess. */
    fun advertiseStarted() {
        if (closed || !startPending) return
        startPending = false
        if (!foreground || !wantOnAir) {
            // came up after it was taken down again
            peripheral?.stopAdvertising()
            return
        }
        advertisingNow = true
        link.advertising()
    }

    /** AdvertiseCallback.onStartFailure, with its code (AdvertiseCallback.ADVERTISE_FAILED_*). */
    fun advertiseFailed(code: Int) {
        if (closed) return
        startPending = false
        say("not advertising — error $code")
        // ADVERTISE_FAILED_FEATURE_UNSUPPORTED: this phone cannot be a peripheral after all
        if (code == 5) link.radioState(TapStage.UNSUPPORTED)
    }

    /** onConnectionStateChange, connected. */
    fun connected(device: String) {
        if (closed) return
        mtu[device] = TapGatt.DEFAULT_MTU
    }

    /** onConnectionStateChange, disconnected: every door that central held is let go of, as iOS says. */
    fun disconnected(device: String) {
        if (closed) return
        prepared.remove(device)
        preparedBytes.remove(device)
        val held = subscriptions.remove(device).orEmpty()
        mtu.remove(device)
        if (inFlightTo == device) {
            // its onNotificationSent may never come
            clearInFlight()
            ready()
        }
        // the near doors first: a payer that leaves is not near any more
        for (door in held.filter { layout.edge(it) != null }) nearLetGo(device, door)
        if (layout.out.uuid in held) link.unsubscribed(device)
    }

    /** onMtuChanged. */
    fun mtuChanged(device: String, value: Int) {
        if (closed) return
        mtu[device] = value
    }

    /** onCharacteristicWriteRequest. */
    fun characteristicWrite(device: String, requestId: Int, door: UUID, prepare: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
        val bytes = value ?: ByteArray(0)
        if (closed) {
            if (responseNeeded) peripheral?.respond(device, requestId, TapGatt.INSUFFICIENT_AUTHORIZATION, offset, null)
            return
        }
        val p = peripheral ?: return
        if (prepare) {
            // a piece of a long write: queued, and answered with its own bytes (ATT Prepare Write Response)
            val total = (preparedBytes[device] ?: 0) + bytes.size
            if (total > TapGatt.MOST_PREPARED) {
                say("a long write longer than ${TapGatt.MOST_PREPARED} bytes; refused")
                prepared.remove(device)
                preparedBytes.remove(device)
                p.respond(device, requestId, TapGatt.PREPARE_QUEUE_FULL, offset, null)
                return
            }
            prepared.getOrPut(device) { ArrayList() }.add(door to bytes)
            preparedBytes[device] = total
            p.respond(device, requestId, TapGatt.SUCCESS, offset, bytes)
            return
        }
        if (offset != 0) {
            if (responseNeeded) p.respond(device, requestId, TapGatt.INVALID_OFFSET, offset, null)
            return
        }
        val ok = link.write(device, listOf(bytes), inDoor = door == layout.into.uuid)
        if (responseNeeded) peripheral?.respond(device, requestId, if (ok) TapGatt.SUCCESS else TapGatt.INSUFFICIENT_AUTHORIZATION, 0, null)
    }

    /** onExecuteWrite: the long write's pieces, together, or thrown away. */
    fun executeWrite(device: String, requestId: Int, execute: Boolean) {
        val queue = prepared.remove(device).orEmpty()
        preparedBytes.remove(device)
        if (closed) {
            peripheral?.respond(device, requestId, if (execute && queue.isNotEmpty()) TapGatt.INSUFFICIENT_AUTHORIZATION else TapGatt.SUCCESS, 0, null)
            return
        }
        if (!execute || queue.isEmpty()) {
            peripheral?.respond(device, requestId, TapGatt.SUCCESS, 0, null)
            return
        }
        // the first request's door decides, and every piece is read in the order it came (TapLink.swift:669-698)
        val ok = link.write(device, queue.map { it.second }, inDoor = queue.first().first == layout.into.uuid)
        peripheral?.respond(device, requestId, if (ok) TapGatt.SUCCESS else TapGatt.INSUFFICIENT_AUTHORIZATION, 0, null)
    }

    /** onCharacteristicReadRequest: nothing here is read (TapLink.swift:874-876). */
    fun characteristicRead(device: String, requestId: Int, offset: Int) {
        peripheral?.respond(device, requestId, TapGatt.READ_NOT_PERMITTED, offset, null)
    }

    /** onDescriptorReadRequest: a CCCD says what that central set. */
    fun descriptorRead(device: String, requestId: Int, door: UUID, descriptor: UUID, offset: Int) {
        val p = peripheral ?: return
        if (descriptor != TapGatt.CCCD || layout.door(door)?.cccd != true) {
            p.respond(device, requestId, TapGatt.READ_NOT_PERMITTED, offset, null)
            return
        }
        val value = if (door in subscriptions[device].orEmpty()) TapGatt.NOTIFY_ON else TapGatt.NOTIFY_OFF
        if (offset > value.size) {
            p.respond(device, requestId, TapGatt.INVALID_OFFSET, offset, null)
            return
        }
        p.respond(device, requestId, TapGatt.SUCCESS, offset, value.copyOfRange(offset, value.size))
    }

    /**
     * onDescriptorWriteRequest: a CCCD written is a subscribe or an unsubscribe,
     * answered before it is acted on, as the subscribe lands before
     * `didSubscribeTo` runs on iOS. Indications are not offered; a value of
     * another length, a long write or any other descriptor is refused.
     */
    fun descriptorWrite(device: String, requestId: Int, door: UUID, descriptor: UUID, prepare: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
        val p = peripheral ?: return
        fun answer(status: Int) {
            if (responseNeeded || prepare) p.respond(device, requestId, status, offset, if (status == TapGatt.SUCCESS) value else null)
        }
        if (closed || descriptor != TapGatt.CCCD || layout.door(door)?.cccd != true) return answer(TapGatt.WRITE_NOT_PERMITTED)
        if (prepare) return answer(TapGatt.REQUEST_NOT_SUPPORTED)
        if (offset != 0) return answer(TapGatt.INVALID_OFFSET)
        val v = value ?: ByteArray(0)
        if (v.size != 2) return answer(TapGatt.INVALID_ATTRIBUTE_LENGTH)
        val bits = (v[0].toInt() and 0xff) or ((v[1].toInt() and 0xff) shl 8)
        if (bits and 0x0002 != 0 || bits and 0x0001.inv() and 0xffff != 0) return answer(TapGatt.CCCD_IMPROPERLY_CONFIGURED)
        answer(TapGatt.SUCCESS)
        val held = subscriptions.getOrPut(device) { LinkedHashSet() }
        if (bits and 0x0001 != 0) {
            if (!held.add(door)) return
            if (layout.edge(door) != null) nearSubscribed(device, door) else link.subscribed(device)
        } else {
            if (!held.remove(door)) return
            if (layout.edge(door) != null) nearLetGo(device, door) else link.unsubscribed(device)
        }
    }

    /** onNotificationSent: the radio can take the next notification. */
    fun notificationSent(device: String, status: Int) {
        if (closed) return
        if (status != TapGatt.SUCCESS) say("a notification was not sent (status $status)")
        if (inFlightTo == null) return
        clearInFlight()
        ready()
    }

    // MARK: one notification at a time

    private fun sent(device: String) {
        inFlightTo = device
        stallTimer?.cancel()
        stallTimer = clock.after(NOTIFY_STALL) {
            stallTimer = null
            if (inFlightTo == null || closed) return@after
            say("Android said nothing of the last notification in ${NOTIFY_STALL}s; going on")
            inFlightTo = null
            ready()
        }
    }

    private fun clearInFlight() {
        inFlightTo = null
        stallTimer?.cancel()
        stallTimer = null
    }

    private fun retrySoon() {
        if (retryTimer != null) return
        retryTimer = clock.after(NOTIFY_RETRY) {
            retryTimer = null
            if (!closed && inFlightTo == null) ready()
        }
    }

    /**
     * `peripheralManagerIsReady(toUpdateSubscribers:)`. The door bytes owed go
     * first: each is one byte and says what a payer's screen should show now,
     * and with one notification in the air at a time a payment's pieces would
     * otherwise hold them back for as long as it takes to cross.
     */
    private fun ready() {
        if (closed) return
        if (doorOwedTo.isNotEmpty()) tellTurned(only = doorOwedTo.toSet())
        if (inFlightTo == null) link.readyToUpdate()
    }

    // MARK: the near doors

    private fun nearSubscribed(device: String, door: UUID) {
        val edge = layout.edge(door) ?: return
        // which door says which edge of THIS screen the payer wants the mark on
        nearCentrals[device] = edge
        tellTurned(only = setOf(device))
        if (link.idle) onEvent(TapStage.NEARBY, edge)
    }

    private fun nearLetGo(device: String, door: UUID) {
        val edge = layout.edge(door) ?: return
        // a payer changing doors (it has just learnt this phone's model) is not one leaving
        if (nearCentrals[device] == edge) {
            nearCentrals.remove(device)
            doorOwedTo.remove(device)
            doorRefusals.remove(device)
        }
        if (nearCentrals.isEmpty()) onEvent(TapStage.FAR, null)
    }

    /** The door byte to each payer nearby, on the door it chose; the ones the radio would not take are owed it. */
    private fun tellTurned(only: Set<String>? = null) {
        val p = peripheral ?: return
        for ((id, edge) in nearCentrals.entries.toList()) {
            if (only != null && id !in only) continue
            val door = layout.nearDoor(edge)
            if (door !in subscriptions[id].orEmpty()) continue
            if (inFlightTo != null) {
                // our own one-at-a-time, not a refusal: said when the radio is ready
                doorOwedTo.add(id)
                continue
            }
            if (p.notify(id, door, doorByte)) {
                sent(id)
                doorOwedTo.remove(id)
                doorRefusals.remove(id)
                continue
            }
            val n = (doorRefusals[id] ?: 0) + 1
            doorRefusals[id] = n
            if (n >= MOST_DOOR_REFUSALS) {
                say("a payer nearby took no door byte in $n tries; it is not told again")
                doorOwedTo.remove(id)
                doorRefusals.remove(id)
                nearCentrals.remove(id)
                if (nearCentrals.isEmpty()) onEvent(TapStage.FAR, null)
            } else {
                doorOwedTo.add(id)
                retrySoon()
            }
        }
        val line = "told ${nearCentrals.size} payer(s) nearby: " +
            "${if (early) "amount screen" else "invoice screen"}, ${if (turned) "top-down" else "upright"}" +
            (if (doorOwedTo.isEmpty()) "" else " — the radio was busy for ${doorOwedTo.size}, to be said again")
        if (line != doorSaid) {
            doorSaid = line
            say(line)
        }
    }

    /** What the tests read: whether the phone is on the air now. */
    val onAir: Boolean get() = advertisingNow
}
