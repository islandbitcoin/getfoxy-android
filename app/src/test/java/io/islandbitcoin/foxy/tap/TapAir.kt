package io.islandbitcoin.foxy.tap

/**
 * A clock the tests move by hand: tasks run in the order they fall due, and
 * only when [advance] or [settle] reaches them.
 */
class FakeClock : TapClock {
    private class Task(val due: Double, val seq: Int, val run: () -> Unit) {
        var cancelled = false
    }

    private var t = 1000.0
    private var seq = 0
    private val tasks = ArrayList<Task>()

    override fun now(): Double = t

    override fun after(seconds: Double, task: () -> Unit): TapClock.Timer {
        val k = Task(t + seconds, seq++, task)
        tasks.add(k)
        return TapClock.Timer { k.cancelled = true }
    }

    /** Runs whatever is due now; true when anything ran. */
    fun runDue(): Boolean {
        val due = tasks.filter { !it.cancelled && it.due <= t + 1e-9 }.minWithOrNull(compareBy({ it.due }, { it.seq }))
            ?: return false
        tasks.remove(due)
        due.run()
        return true
    }

    /** Moves time on by [seconds], running each task as its moment comes, with [between] run after each. */
    fun advance(seconds: Double, between: () -> Unit = {}) {
        val end = t + seconds
        while (true) {
            between()
            val next = tasks.filter { !it.cancelled && it.due <= end + 1e-9 }.minWithOrNull(compareBy({ it.due }, { it.seq }))
                ?: break
            if (next.due > t) t = next.due
            tasks.remove(next)
            next.run()
        }
        t = end
        between()
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}

/**
 * Two phones and the air between them, with no Bluetooth: a receiver link and a
 * payer link joined by fake radios. Like CoreBluetooth's delegates, nothing is
 * delivered inside the call that sent it; [settle] carries everything across.
 */
class TapAir(
    val clock: FakeClock = FakeClock(),
    payload: String = OFFER,
    mtu: Int = 185,
    private val receiverSession: (java.util.UUID) -> TapSession = { TapSession(TapSession.Role.RECEIVER, it) {} },
    private val payerSession: (java.util.UUID) -> TapSession = { TapSession(TapSession.Role.PAYER, it) {} },
) {
    companion object {
        const val OFFER = "{\"v\":2,\"req\":\"creqAabcdefghijklmnopqrstuv\"}"
        const val PAYER = "payer-1"
    }

    val receiverEvents = ArrayList<Pair<TapStage, String?>>()
    val payerStages = ArrayList<TapStage>()
    val diary = ArrayList<String>()
    val pending = ArrayDeque<() -> Unit>()

    // what the page does
    var paid: ((String, (Int, String) -> Unit) -> Unit) = { _, answer -> answer(200, "") }
    val payments = ArrayList<String>()
    val quotes = ArrayList<String>()
    var offer: Pair<String, String>? = null
    var keepChange: ((String, (Boolean) -> Unit) -> Unit) = { _, keep -> keep(true) }
    val changes = ArrayList<String>()
    val terms = ArrayList<String>()

    // the radios' state
    var advertising = false
    var advertiseCalls = 0
    var receiverClosed = false
    var payerClosed = false
    var connected = true
    var gaveUp: String? = null
    var held = false
    var answeredCalls = 0

    /** While true, the payer hears nothing the receiver notifies (its app is away). */
    var payerAway = false

    /** Writes fail while this says so (the next one, or all). */
    var failWrites = 0

    /** While true, the payer's writes stay in the air, unanswered, until [releaseWrites]. */
    var holdWrites = false
    private val heldWrites = ArrayList<() -> Unit>()

    /** What the next subscribe answers: null when it lands. */
    var subscribeError: String? = null

    var stopAdvertisingCalls = 0

    val notified = ArrayList<ByteArray>()
    val written = ArrayList<ByteArray>()

    val receiverRadio: TapReceiverLink.Radio = object : TapReceiverLink.Radio {
        override fun advertise() {
            advertiseCalls++
            if (!advertising) {
                advertising = true
                pending.addLast { receiver.advertising() }
            }
        }

        override fun stopAdvertising() {
            stopAdvertisingCalls++
            advertising = false
        }

        override fun notify(central: String, piece: ByteArray): Boolean {
            notified.add(piece)
            if (central == PAYER && !payerAway) pending.addLast { payer.notified(piece) }
            return true
        }

        override fun notifyLength(central: String) = mtu - 3
        override fun close() {
            receiverClosed = true
            advertising = false
        }
    }

    val payerRadio: TapPayerLink.Radio = object : TapPayerLink.Radio {
        override fun subscribe() {
            pending.addLast {
                val error = subscribeError
                if (error == null) receiver.subscribed(PAYER)
                payer.subscribed(error)
            }
        }

        override fun write(piece: ByteArray) {
            written.add(piece)
            if (failWrites > 0) {
                failWrites--
                pending.addLast { payer.wrote("the write was refused") }
                return
            }
            val land = {
                val ok = receiver.write(PAYER, listOf(piece))
                payer.wrote(if (ok) null else "insufficient authorization")
            }
            if (holdWrites) heldWrites.add(land) else pending.addLast(land)
        }

        override fun writeLength() = mtu - 3
        override val connected: Boolean get() = this@TapAir.connected
        override fun giveUp(why: String) {
            gaveUp = why
        }

        override fun holdLink() {
            held = true
        }

        override fun answered() {
            answeredCalls++
        }

        override fun close() {
            payerClosed = true
        }
    }

    val receiver: TapReceiverLink = TapReceiverLink(
        payload, receiverRadio, clock,
        onEvent = { stage, detail -> receiverEvents.add(stage to detail) },
        onPaid = { text, answer ->
            payments.add(text)
            paid(text, answer)
        },
        onQuote = { quotes.add(it) },
        newSession = receiverSession,
        diary = { diary.add(it) },
    )

    val payer: TapPayerLink = TapPayerLink(
        receiver.service, payerRadio, clock,
        onStage = { payerStages.add(it) },
        done = { text, code -> offer = text to code },
        onChange = { text, keep ->
            changes.add(text)
            keepChange(text, keep)
        },
        onTerms = { terms.add(it) },
        newSession = payerSession,
        diary = { diary.add(it) },
    )

    /**
     * The writes held in the air land now, and are acknowledged: unless the
     * payer has closed its link meanwhile, which takes them down with it.
     */
    fun releaseWrites() {
        holdWrites = false
        val held = heldWrites.toList()
        heldWrites.clear()
        if (!payerClosed) held.forEach { pending.addLast(it) }
    }

    /** Carries everything in the air across, and runs what the clock has due now, until nothing is left. */
    fun settle() {
        while (true) {
            val next = pending.removeFirstOrNullCompat()
            if (next != null) {
                next()
                continue
            }
            if (!clock.runDue()) return
        }
    }

    private fun ArrayDeque<() -> Unit>.removeFirstOrNullCompat(): (() -> Unit)? = if (isEmpty()) null else removeFirst()

    /** Moves time on, carrying the air across as it goes. */
    fun advance(seconds: Double) = clock.advance(seconds) { settle() }

    /** The receiver armed and on the air, and the payer's verdict in: subscribe, M1 to M3, and the offer. */
    fun link() {
        receiver.radioState(null)
        receiver.arm()
        settle()
        payer.connecting()
        payer.speak()
        settle()
    }

    /** A payment handed over, and what became of it. */
    fun pay(body: String): TapPayerLink.Handover? {
        var how: TapPayerLink.Handover? = null
        payer.send(body) { how = it }
        settle()
        return how
    }
}
