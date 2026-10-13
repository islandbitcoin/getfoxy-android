package io.islandbitcoin.foxy.tap

import java.util.UUID

/**
 * Bluetooth as [TapReceiver] sees it, with no radio: whether there is an
 * adapter, whether it advertises, the permissions and the system's question,
 * the power and its changes, and a [FakePeripheral] each time a GATT server is
 * opened. Everything happens on [clock]'s thread, as on the main looper.
 */
class FakeBluetooth(override val clock: FakeClock = FakeClock()) : TapBluetooth {
    override var adapter = true
    override var advertises = true
    var granted = true

    /** What the system's question answers: true, false, or null for one still on screen. */
    var grantOnAsk: Boolean? = true
    var asks = 0
    private val waiting = ArrayList<(Boolean) -> Unit>()
    var powerNow = TapBluetooth.Power.ON
    private val watchers = ArrayList<(TapBluetooth.Power) -> Unit>()
    val opened = ArrayList<FakePeripheral>()
    var openFails = false

    /** Every peripheral's callbacks, carried across by [GattAir.settle]. */
    val pending = ArrayDeque<() -> Unit>()

    /** Where a refusal is kept (AndroidTapBluetooth: its preferences); the logic is the app's own [BluetoothRefusal]. */
    val kept = MemoryRefusal()
    private val refusal = BluetoothRefusal(kept) { granted }

    override fun permitted() = granted.also { if (it) refusal.seenGranted() }

    override fun ask(done: (Boolean) -> Unit) {
        asks++
        val answer = grantOnAsk
        if (answer == null) {
            waiting.add(done)
            return
        }
        granted = answer
        refusal.answered(answer)
        done(answer)
    }

    override fun refused() = refusal.refused()

    /** A question taken off screen with no answer (the launcher's empty map): nothing is kept. */
    fun unanswered() {
        refusal.answered(null)
        val w = waiting.toList()
        waiting.clear()
        w.forEach { it(granted) }
    }

    /** Android 11+ took back the permissions of an app left unused (no dialog, no refusal). */
    fun autoRevoke() {
        granted = false
    }

    /** The question on screen is answered now. */
    fun answer(grant: Boolean) {
        granted = grant
        refusal.answered(grant)
        val w = waiting.toList()
        waiting.clear()
        w.forEach { it(grant) }
    }

    override fun power() = powerNow

    override fun watch(onChange: (TapBluetooth.Power) -> Unit): () -> Unit {
        watchers.add(onChange)
        return { watchers.remove(onChange) }
    }

    val watching: Int get() = watchers.size

    fun setPower(p: TapBluetooth.Power) {
        powerNow = p
        watchers.toList().forEach { it(p) }
    }

    override fun open(server: TapGattServer): TapPeripheral? {
        if (openFails) return null
        return FakePeripheral(server, pending).also { opened.add(it) }
    }

    val peripheral: FakePeripheral get() = opened.last()
}

/** [BluetoothRefusal.Store] in memory. */
class MemoryRefusal : BluetoothRefusal.Store {
    var value = false
    override fun get() = value
    override fun put(refused: Boolean) {
        value = refused
    }
    override fun clear() {
        value = false
    }
}

/**
 * Android's GATT server and advertiser as [TapGattServer] meets them: every
 * answer comes back later (on [pending]), it takes one notification at a time
 * and refuses the next until it says the last was sent, and the centrals
 * ([FakeCentral]) connected to it hear what it notifies.
 */
class FakePeripheral(val server: TapGattServer, private val pending: ArrayDeque<() -> Unit>) : TapPeripheral {
    var layout: TapGatt.Layout? = null
    var addOk = true
    var addCalls = 0
    var advertisingWith: UUID? = null
    var startCalls = 0
    var stopCalls = 0
    var advertiseFails: Int? = null
    var closed = false
    val centrals = LinkedHashMap<String, FakeCentral>()

    /** Notifications the stack has not said it sent: it takes no other meanwhile. */
    var inFlight = 0
        private set
    var mostInFlight = 0
        private set

    /** While true, onNotificationSent never comes (a stack that has lost it). */
    var holdSent = false

    /** The stack refuses this many notifications, whatever the flow control (a busy radio). */
    var refuseNotifications = 0

    val notifications = ArrayList<Triple<String, UUID, ByteArray>>()
    val responses = ArrayList<Triple<String, Int, Int>>()

    override fun addService(layout: TapGatt.Layout): Boolean {
        addCalls++
        this.layout = layout
        pending.addLast { server.serviceAdded(addOk) }
        return true
    }

    override fun startAdvertising(service: UUID) {
        startCalls++
        val fail = advertiseFails
        if (fail != null) {
            pending.addLast { server.advertiseFailed(fail) }
            return
        }
        advertisingWith = service
        pending.addLast { server.advertiseStarted() }
    }

    override fun stopAdvertising() {
        stopCalls++
        advertisingWith = null
    }

    override fun notify(device: String, door: UUID, value: ByteArray): Boolean {
        if (closed) return false
        if (refuseNotifications > 0) {
            refuseNotifications--
            return false
        }
        if (inFlight > 0) return false
        inFlight++
        mostInFlight = maxOf(mostInFlight, inFlight)
        notifications.add(Triple(device, door, value))
        val c = centrals[device]
        pending.addLast { c?.notified(door, value) }
        if (!holdSent) {
            pending.addLast {
                inFlight = 0
                server.notificationSent(device, TapGatt.SUCCESS)
            }
        }
        return true
    }

    /** The stack sends the notification it was holding after all. */
    fun releaseSent(device: String) {
        inFlight = 0
        server.notificationSent(device, TapGatt.SUCCESS)
    }

    override fun respond(device: String, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        responses.add(Triple(device, requestId, status))
        val c = centrals[device] ?: return
        pending.addLast { c.answered(requestId, status, offset, value) }
    }

    override fun close() {
        closed = true
        advertisingWith = null
    }

    val onAir: Boolean get() = advertisingWith != null && !closed
}

/**
 * A paying phone at the ATT level: a [TapPayerLink.Radio] that connects, writes
 * the out door's CCCD to subscribe, and writes with response, as a long write
 * (prepared writes of MTU − 5 bytes, each checked against its echo, then an
 * execute) whenever a write is longer than MTU − 3, as iOS does with
 * `maximumWriteValueLength(.withResponse)` of 512.
 */
class FakeCentral(
    val id: String,
    private val bluetooth: FakeBluetooth,
    val mtu: Int,
    private val writeLength: Int = TapGatt.MOST_VALUE,
    /** The payer's app is away: it hears nothing the receiver notifies. */
    private val away: () -> Boolean = { false },
    /** Writes go to this door instead of the in door (a payer at the wrong door). */
    private val writeDoor: () -> UUID? = { null },
) : TapPayerLink.Radio {
    lateinit var payer: TapPayerLink
    private val p: FakePeripheral get() = bluetooth.peripheral
    private val server: TapGattServer get() = p.server
    private var nextRequest = 1
    private val waiting = HashMap<Int, (Int, Int, ByteArray?) -> Unit>()

    /** Every request this central made: (kind, door, prepare, offset, size). */
    val requests = ArrayList<String>()
    val out = ArrayList<ByteArray>()
    val nearBytes = ArrayList<Pair<String, Byte>>()
    var isConnected = false
    var gaveUp: String? = null
    var held = false
    var closedLink = false

    /** While true the subscribe skips the CCCD (a central that never turned notifications on). */
    var skipCccd = false

    /** How many Prepare Write Responses were checked against what was sent (offset and bytes). */
    var echoChecks = 0

    fun connect() {
        isConnected = true
        p.centrals[id] = this
        bluetooth.pending.addLast {
            server.connected(id)
            if (mtu != TapGatt.DEFAULT_MTU) server.mtuChanged(id, mtu)
        }
    }

    /** The link drops; [tellPayer] false when the payer itself let it go (the payer hears nothing of its own close). */
    fun disconnect(tellPayer: Boolean = true) {
        if (!isConnected) return
        isConnected = false
        p.centrals.remove(id)
        bluetooth.pending.addLast { server.disconnected(id) }
        if (tellPayer) bluetooth.pending.addLast { payer.disconnected() }
    }

    private fun request(then: (Int, Int, ByteArray?) -> Unit): Int {
        val r = nextRequest++
        waiting[r] = then
        return r
    }

    fun answered(requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        waiting.remove(requestId)?.invoke(status, offset, value)
    }

    fun notified(door: UUID, value: ByteArray) {
        if (!isConnected) return
        val layout = p.layout ?: return
        when (door) {
            layout.out.uuid -> if (!away()) {
                out.add(value)
                payer.notified(value)
            }
            else -> nearBytes.add((layout.edge(door) ?: "?") to value[0])
        }
    }

    /** Subscribes (or not) on a door, as a descriptor write with response. */
    fun cccd(door: UUID, value: ByteArray, then: (Int) -> Unit = {}) {
        val r = request { status, _, _ -> then(status) }
        requests.add("cccd ${door.toString().take(8)} ${value.joinToString("") { "%02x".format(it) }}")
        bluetooth.pending.addLast { server.descriptorWrite(id, r, door, TapGatt.CCCD, false, true, 0, value) }
    }

    override fun subscribe() {
        val layout = p.layout ?: return
        if (skipCccd) {
            // CoreBluetooth's setNotifyValue would have written it; this central says it subscribed without
            bluetooth.pending.addLast { payer.subscribed(null) }
            return
        }
        cccd(layout.out.uuid, TapGatt.NOTIFY_ON) { status ->
            payer.subscribed(if (status == TapGatt.SUCCESS) null else "the CCCD write was refused ($status)")
        }
    }

    override fun write(piece: ByteArray) {
        val layout = p.layout ?: return
        val door = writeDoor() ?: layout.into.uuid
        if (piece.size <= mtu - 3) {
            requests.add("write ${piece.size}")
            val r = request { status, _, _ -> payer.wrote(if (status == TapGatt.SUCCESS) null else "ATT error $status") }
            bluetooth.pending.addLast { server.characteristicWrite(id, r, door, false, true, 0, piece) }
            return
        }
        // a long write: Prepare Write Requests of MTU - 5 bytes, then an Execute Write Request
        val step = mtu - 5
        val chunks = (piece.indices step step).map { piece.copyOfRange(it, minOf(it + step, piece.size)) }
        fun prepare(i: Int) {
            if (i == chunks.size) {
                requests.add("execute")
                val r = request { status, _, _ -> payer.wrote(if (status == TapGatt.SUCCESS) null else "ATT error $status") }
                bluetooth.pending.addLast { server.executeWrite(id, r, true) }
                return
            }
            val offset = i * step
            val chunk = chunks[i]
            requests.add("prepare $offset ${chunk.size}")
            val r = request { status, gotOffset, echo ->
                if (status != TapGatt.SUCCESS) {
                    // the queue is thrown away and the write has failed
                    val c = request { _, _, _ -> }
                    bluetooth.pending.addLast { server.executeWrite(id, c, false) }
                    payer.wrote("ATT error $status")
                    return@request
                }
                // CoreBluetooth checks each echo against what it sent
                check(gotOffset == offset && echo != null && echo.contentEquals(chunk)) { "the prepare write's echo did not match" }
                echoChecks++
                prepare(i + 1)
            }
            bluetooth.pending.addLast { server.characteristicWrite(id, r, door, true, true, offset, chunk) }
        }
        prepare(0)
    }

    override fun writeLength() = writeLength
    override val connected: Boolean get() = isConnected

    override fun giveUp(why: String) {
        gaveUp = why
        disconnect(tellPayer = false)
    }

    override fun holdLink() {
        held = true
    }

    override fun answered() {}

    override fun close() {
        closedLink = true
        disconnect(tellPayer = false)
    }
}

/**
 * A receiver ([TapReceiver]: the link, its GATT server, and Bluetooth) and a
 * payer ([TapPayerLink]) joined at the ATT level by [FakePeripheral] and
 * [FakeCentral]: subscribes are CCCD writes, writes longer than the MTU are
 * prepared writes and an execute, notifications go one at a time at most
 * MTU − 3 bytes. Nothing is delivered inside the call that sent it; [settle]
 * carries everything across.
 */
class GattAir(
    val mtu: Int = 185,
    payload: String = TapAir.OFFER,
    val bluetooth: FakeBluetooth = FakeBluetooth(),
    writeLength: Int = TapGatt.MOST_VALUE,
) {
    val clock: FakeClock get() = bluetooth.clock
    val receiverEvents = ArrayList<Pair<TapStage, String?>>()
    val payerStages = ArrayList<TapStage>()
    val diary = ArrayList<String>()
    val payments = ArrayList<String>()
    val quotes = ArrayList<String>()
    val changes = ArrayList<String>()
    var paid: (String, (Int, String) -> Unit) -> Unit = { _, answer -> answer(200, "") }
    var keepChange: (String, (Boolean) -> Unit) -> Unit = { _, keep -> keep(true) }
    var offer: Pair<String, String>? = null
    var payerAway = false

    /** Writes go to this door instead of the in door (a payer at the wrong door). */
    var writeDoor: UUID? = null

    val receiver = TapReceiver(
        payload, bluetooth,
        onEvent = { stage, detail -> receiverEvents.add(stage to detail) },
        onPaid = { body, answer ->
            payments.add(body)
            paid(body, answer)
        },
        onQuote = { quotes.add(it) },
        diary = { diary.add(it) },
    )

    val central = FakeCentral("payer-1", bluetooth, mtu, writeLength, away = { payerAway }, writeDoor = { writeDoor })

    val payer = TapPayerLink(
        receiver.link.service, central, clock,
        onStage = { payerStages.add(it) },
        done = { text, code -> offer = text to code },
        onChange = { text, keep ->
            changes.add(text)
            keepChange(text, keep)
        },
        diary = { diary.add(it) },
    ).also { central.payer = it }

    fun settle() {
        while (true) {
            if (bluetooth.pending.isNotEmpty()) {
                bluetooth.pending.removeFirst()()
                continue
            }
            if (!clock.runDue()) return
        }
    }

    fun advance(seconds: Double) = clock.advance(seconds) { settle() }

    /** TAP pressed: the radio's state, the service up, on the air. */
    fun onAir() {
        receiver.start()
        receiver.arm()
        settle()
    }

    /** On the air, then the payer connects, subscribes, and speaks: M1 to M3, and the offer. */
    fun link() {
        onAir()
        central.connect()
        settle()
        payer.connecting()
        payer.speak()
        settle()
    }

    fun pay(body: String): TapPayerLink.Handover? {
        var how: TapPayerLink.Handover? = null
        payer.send(body) { how = it }
        settle()
        return how
    }

    val stages: List<TapStage> get() = receiverEvents.map { it.first }
}
