package io.islandbitcoin.foxy.tap

import java.util.UUID

/**
 * One receiving phone on a [PayerAir]: Android's own receiver ([TapReceiver],
 * its [TapGattServer] and a [FakePeripheral]) on the shared clock, what the
 * payer reads of its advertisement ([rssi]) and of an open link ([linkRssi]),
 * and the model its Device Information service serves ([model]; an iPhone serves
 * one to any connection, Android serves none).
 */
class AirReceiver(val address: String, val air: PayerAir, payload: String = TapAir.OFFER) {
    val bluetooth = FakeBluetooth(air.clock, air.pending)
    val events = ArrayList<Pair<TapStage, String?>>()
    val payments = ArrayList<String>()
    val quotes = ArrayList<String>()
    var paid: (String, (Int, String) -> Unit) -> Unit = { _, answer -> answer(200, "") }

    /** What the payer's scan reads of this phone's advertisements, in dBm. */
    var rssi = -30

    /** What an open link to it reads (`readRemoteRssi`); null: the link never says. */
    var linkRssi: Int? = -35

    /** Its model number (iOS's Device Information service), or null for a phone that serves none. */
    var model: String? = null

    val receiver = TapReceiver(
        payload, bluetooth,
        onEvent = { stage, detail -> events.add(stage to detail) },
        onPaid = { body, answer ->
            payments.add(body)
            paid(body, answer)
        },
        onQuote = { quotes.add(it) },
        diary = { air.diary.add("$address: $it") },
    )

    val peripheral: FakePeripheral get() = bluetooth.peripheral
    val stages: List<TapStage> get() = events.map { it.first }

    /** TAP pressed: on the air. */
    fun onAir() {
        receiver.start()
        receiver.arm()
        air.settle()
    }
}

/**
 * Android's GATT client as [TapPayer] meets it, joined at the ATT level to the
 * receivers' [FakePeripheral]s: a connect is the GATT server's `connected`
 * (and its MTU), discovery reads the service the receiver put up, a subscribe is
 * a CCCD write, a write longer than MTU − 3 is prepared writes and an execute
 * (as Android's stack sends it), notifications come back from the server. Every
 * answer is carried on [PayerAir.pending]; nothing is answered inside the call
 * that asked.
 */
class FakeTapCentral(private val air: PayerAir, val payer: TapPayer, val me: String = "payer-1") : TapCentral {
    init {
        air.centrals.add(this)
    }

    var scanning = false
    var scanStarts = 0
    var scanStops = 0
    var closed = false
    val connects = ArrayList<String>()
    val requests = ArrayList<String>()

    /** The open links, by receiver. */
    val links = LinkedHashMap<String, Conn>()

    /** A connect to these is never answered (a phone slow to open). */
    val neverAnswer = HashSet<String>()

    /** A connect to these fails at once. */
    val refuse = HashSet<String>()

    /** Notifications from these are not delivered (a receiver that never answers M1). */
    val deaf = HashSet<String>()

    /** Service discovery to these fails this many more times (Android's GATT status 129 or 133). */
    val discoveryFails = HashMap<String, Int>()

    /** Android could not start the scan (`onScanFailed`): it stops, and the payer is told. */
    fun failScan(code: Int) {
        scanning = false
        later { payer.scanFailed(code) }
    }

    /**
     * The receiver left its screen: its service goes from the GATT server while the link stays
     * up and goes on answering how strong it is (TapLink.swift 1547-1558), and the payer hears
     * Android's `onServiceChanged`.
     */
    fun servicesGone(device: String) {
        val c = links[device] ?: return
        c.r.peripheral.layout = null
        later { if (c.up) payer.servicesChanged(device) }
    }

    inner class Conn(val r: AirReceiver) : AttCentral {
        var up = false
        private var nextRequest = 1
        private val waiting = HashMap<Int, (Int, Int, ByteArray?) -> Unit>()

        fun request(then: (Int, Int, ByteArray?) -> Unit): Int {
            val n = nextRequest++
            waiting[n] = then
            return n
        }

        override fun answered(requestId: Int, status: Int, offset: Int, value: ByteArray?) {
            waiting.remove(requestId)?.invoke(status, offset, value)
        }

        override fun notified(door: UUID, value: ByteArray) {
            if (!up || r.address in deaf) return
            payer.valueChanged(r.address, door, value)
        }
    }

    private fun later(what: () -> Unit) = air.pending.addLast(what)

    /** The hardware filters the scan handed the controller, as AndroidTapCentral hands them ([TapScanRules.hardwareFilters]). */
    var filters: List<TapScanFilter> = emptyList()

    override fun startScan(): Boolean {
        if (closed) return false
        if (!scanning) scanStarts++
        scanning = true
        filters = TapScanRules.hardwareFilters
        return true
    }

    override fun stopScan() {
        if (scanning) scanStops++
        scanning = false
    }

    override fun connect(device: String) {
        connects.add(device)
        val r = air.receivers[device]
        if (r == null || device in refuse) {
            later { payer.connectFailed(device) }
            return
        }
        if (device in neverAnswer) return
        val c = Conn(r)
        links[device] = c
        later {
            if (links[device] !== c) return@later
            c.up = true
            r.peripheral.centrals[me] = c
            r.receiver.server.connected(me)
            payer.connected(device)
        }
    }

    override fun discover(device: String) {
        val c = links[device] ?: return later { payer.servicesFound(device, null) }
        later {
            if (!c.up) return@later
            val fails = discoveryFails[device] ?: 0
            if (fails > 0) {
                discoveryFails[device] = fails - 1
                return@later payer.servicesFound(device, null)
            }
            c.r.receiver.server.mtuChanged(me, air.mtu)
            val found = HashMap<UUID, Set<UUID>>()
            val layout = c.r.peripheral.layout
            if (layout != null && !c.r.peripheral.closed) found[layout.service] = layout.doors.map { it.uuid }.toSet()
            if (c.r.model != null) found[TapPayer.DEVICE_INFORMATION] = setOf(TapPayer.MODEL_NUMBER)
            payer.servicesFound(device, found)
        }
    }

    override fun setNotify(device: String, service: UUID, door: UUID, on: Boolean) {
        val c = links[device]
        if (c == null || !c.up) return later { payer.notifyState(device, door, "not connected") }
        val value = if (on) TapGatt.NOTIFY_ON else TapGatt.NOTIFY_OFF
        requests.add("cccd ${door.toString().take(8)} ${if (on) "on" else "off"}")
        val id = c.request { status, _, _ -> payer.notifyState(device, door, if (status == TapGatt.SUCCESS) null else "CCCD status $status") }
        later { c.r.receiver.server.descriptorWrite(me, id, door, TapGatt.CCCD, false, true, 0, value) }
    }

    override fun write(device: String, service: UUID, door: UUID, value: ByteArray) {
        val c = links[device]
        if (c == null || !c.up) return later { payer.wrote(device, door, "not connected") }
        val server = c.r.receiver.server
        if (value.size <= air.mtu - 3) {
            requests.add("write ${value.size}")
            val id = c.request { status, _, _ -> payer.wrote(device, door, if (status == TapGatt.SUCCESS) null else "ATT error $status") }
            later { server.characteristicWrite(me, id, door, false, true, 0, value) }
            return
        }
        // Android's stack: a long write, Prepare Write Requests of MTU - 5 bytes, then an Execute Write Request
        val step = air.mtu - 5
        val chunks = (value.indices step step).map { value.copyOfRange(it, minOf(it + step, value.size)) }
        fun prepare(i: Int) {
            if (i == chunks.size) {
                requests.add("execute")
                val id = c.request { status, _, _ -> payer.wrote(device, door, if (status == TapGatt.SUCCESS) null else "ATT error $status") }
                later { server.executeWrite(me, id, true) }
                return
            }
            val offset = i * step
            requests.add("prepare $offset ${chunks[i].size}")
            val id = c.request { status, gotOffset, echo ->
                if (status != TapGatt.SUCCESS) {
                    val cancel = c.request { _, _, _ -> }
                    later { server.executeWrite(me, cancel, false) }
                    payer.wrote(device, door, "ATT error $status")
                    return@request
                }
                check(gotOffset == offset && echo != null && echo.contentEquals(chunks[i])) { "the prepare write's echo did not match" }
                prepare(i + 1)
            }
            later { server.characteristicWrite(me, id, door, true, true, offset, chunks[i]) }
        }
        prepare(0)
    }

    override fun read(device: String, service: UUID, door: UUID) {
        val c = links[device]
        later { payer.valueRead(device, door, c?.r?.model?.toByteArray(Charsets.UTF_8)) }
    }

    override fun readRssi(device: String) {
        val c = links[device]
        val dbm = c?.r?.linkRssi
        later {
            if (c == null || !c.up) return@later payer.rssi(device, 0, false)
            // a link that will not say how strong it is: no answer at all (iOS: the delegate is never called)
            if (dbm != null) payer.rssi(device, dbm, true)
        }
    }

    override fun isConnected(device: String): Boolean = links[device]?.up == true

    override fun disconnect(device: String) {
        val c = links.remove(device) ?: return
        if (!c.up) return
        c.up = false
        c.r.peripheral.centrals.remove(me)
        later { c.r.receiver.server.disconnected(me) }
    }

    override fun close() {
        closed = true
        stopScan()
        for (d in links.keys.toList()) disconnect(d)
    }

    /** The receiver let the link go, or it was lost: both ends hear it. */
    fun drop(device: String) {
        val c = links.remove(device) ?: return
        if (!c.up) return
        c.up = false
        c.r.peripheral.centrals.remove(me)
        later {
            c.r.receiver.server.disconnected(me)
            payer.disconnected(device, "the link was lost")
        }
    }
}

/**
 * The Bluetooth controller's hardware filtering as the Pixel 8 Pro does it
 * (ENG-679): no filter at all lets every advertisement through; a service
 * filter passes an advertisement one of whose service UUIDs equals the
 * filter's under its mask, except that a filter whose mask is all zeros
 * matches nothing (`dumpsys bluetooth_manager`, filter `[ ServiceUuid=00000000-…
 * ServiceUuidMask=00000000-… ]`: "Number of results 0/0/0", not even unrelated
 * devices).
 */
object FakeController {
    private val NONE = UUID(0L, 0L)

    fun passes(filters: List<TapScanFilter>, ad: TapAdvert): Boolean =
        filters.isEmpty() || filters.any { matches(it, ad) }

    private fun matches(f: TapScanFilter, ad: TapAdvert): Boolean {
        if (f.mask == NONE) return false
        val m = f.mask
        return ad.services.any {
            (it.mostSignificantBits and m.mostSignificantBits) == (f.service.mostSignificantBits and m.mostSignificantBits) &&
                (it.leastSignificantBits and m.leastSignificantBits) == (f.service.leastSignificantBits and m.leastSignificantBits)
        }
    }
}

/**
 * A paying phone ([TapPayer] over a [FakeTapCentral]) and any number of
 * receivers ([AirReceiver]) on one clock: [hear] advances time and hands the
 * payer each receiver's advertisement ten times a second while it is on the air
 * and the payer is scanning, plus whatever else is on the air ([noise]); each
 * only if the scan's hardware filters let it through ([FakeController]).
 */
class PayerAir(val mtu: Int = 185, thisModel: String = "") {
    val clock = FakeClock()
    val pending = ArrayDeque<() -> Unit>()
    val diary = ArrayList<String>()
    val receivers = LinkedHashMap<String, AirReceiver>()
    val stages = ArrayList<TapStage>()
    val terms = ArrayList<String>()
    val changes = ArrayList<String>()
    var offer: Pair<String, String>? = null
    var offers = 0
    var keepChange: (String, (Boolean) -> Unit) -> Unit = { _, keep -> keep(true) }

    /** Other advertisements heard ten times a second while scanning: not Foxy's. */
    val noise = ArrayList<TapAdvert>()

    val bluetooth = FakeBluetooth(clock, pending)
    var central: FakeTapCentral? = null

    /** Every central made on this air, by any payer (the bridge's too). */
    val centrals = ArrayList<FakeTapCentral>()

    val payer: TapPayer = TapPayer(
        bluetooth,
        onStage = { stages.add(it) },
        done = { text, code ->
            offer = text to code
            offers++
        },
        onChange = { text, keep ->
            changes.add(text)
            keepChange(text, keep)
        },
        onTerms = { terms.add(it) },
        thisModel = thisModel,
        diary = { diary.add(it) },
    )

    init {
        TapPayer.forgetModels()
        bluetooth.centralFor = { p -> FakeTapCentral(this, p).also { central = it } }
    }

    fun receiver(address: String, payload: String = TapAir.OFFER): AirReceiver = AirReceiver(address, this, payload).also { receivers[address] = it }

    val c: FakeTapCentral get() = central ?: error("no central yet")

    fun settle() {
        while (true) {
            if (pending.isNotEmpty()) {
                pending.removeFirst()()
                continue
            }
            if (!clock.runDue()) return
        }
    }

    /** Time passes in tenths of a second, with every advertisement on the air heard at each. */
    fun hear(seconds: Double) {
        val steps = Math.round(seconds / 0.1).toInt()
        repeat(steps) {
            settle()
            for (c in centrals.toList()) {
                if (!c.scanning || c.closed) continue
                for (r in receivers.values) {
                    val p = r.bluetooth.opened.lastOrNull() ?: continue
                    val svc = p.advertisingWith ?: continue
                    if (!p.onAir) continue
                    val ad = TapAdvert(r.address, connectable = true, manufacturerData = false, services = listOf(svc), full = listOf(true), rssi = r.rssi)
                    if (FakeController.passes(c.filters, ad)) c.payer.discovered(ad)
                }
                for (n in noise) if (FakeController.passes(c.filters, n)) c.payer.discovered(n)
            }
            clock.advance(0.1) { settle() }
        }
        settle()
    }

    /** The payment over the held link; what became of it. */
    fun pay(body: String, seconds: Double = 5.0): TapPayerLink.Handover? {
        var how: TapPayerLink.Handover? = null
        payer.send(body) { how = it }
        hear(seconds)
        return how
    }
}
