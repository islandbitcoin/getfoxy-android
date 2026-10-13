package io.islandbitcoin.foxy.tap

/**
 * One GATT operation at a time on one connection, as Android requires: a
 * second `writeCharacteristic`, `writeDescriptor`, `readCharacteristic`,
 * `discoverServices`, `requestMtu` or `readRemoteRssi` while one is outstanding
 * is refused (returns false) rather than queued. CoreBluetooth queues them
 * itself, so iOS's `TapPayer` never had to.
 *
 * [add] queues an operation: [Op.start] begins it and says whether Android took
 * it; the callback that answers it calls [done], and the next one starts. One
 * Android would not take is answered at once with [Op.failed]. One whose answer
 * never comes in [stall] seconds is answered [Op.failed] too, and the queue goes
 * on: a link the stack has lost track of must not hold every later word.
 * Everything runs on the [clock]'s thread.
 */
class TapGattQueue(
    private val clock: TapClock,
    private val stall: Double = STALL,
    private val say: (String) -> Unit = {},
) {
    companion object {
        /** Longer than any one ATT round trip should take, shorter than Android's own 30 s ATT timeout. */
        const val STALL = 10.0
    }

    class Op(val name: String, val start: () -> Boolean, val failed: (String) -> Unit, val stall: Double)

    private val waiting = ArrayDeque<Op>()
    private var current: Op? = null
    private var timer: TapClock.Timer? = null
    private var closed = false

    /** What is in flight now, by name (the tests and the log). */
    val inFlight: String? get() = current?.name
    val queued: Int get() = waiting.size

    fun add(name: String, start: () -> Boolean, failed: (String) -> Unit, stall: Double = this.stall) {
        if (closed) {
            clock.after(0.0) { failed("the link is closed") }
            return
        }
        waiting.addLast(Op(name, start, failed, stall))
        next()
    }

    /** The operation in flight was answered (with whatever its callback said). */
    fun done() {
        timer?.cancel()
        timer = null
        current = null
        next()
    }

    /** The link is gone: everything still queued is answered as failed, and nothing more starts. */
    fun close(why: String) {
        if (closed) return
        closed = true
        timer?.cancel()
        timer = null
        val left = listOfNotNull(current) + waiting
        current = null
        waiting.clear()
        for (op in left) clock.after(0.0) { op.failed(why) }
    }

    private fun next() {
        while (current == null && waiting.isNotEmpty() && !closed) {
            val op = waiting.removeFirst()
            current = op
            val took = try {
                op.start()
            } catch (e: SecurityException) {
                false
            }
            if (!took) {
                current = null
                say("Android would not take ${op.name}")
                clock.after(0.0) { op.failed("Android would not take ${op.name}") }
                continue
            }
            timer = clock.after(op.stall) {
                if (current !== op) return@after
                say("${op.name} was never answered in ${op.stall} s; going on")
                current = null
                timer = null
                op.failed("${op.name} was never answered")
                next()
            }
        }
    }
}

/**
 * Android lets an app start a BLE scan at most five times in any thirty
 * seconds; the sixth is dropped without an error (`BluetoothLeScanner`,
 * "App is scanning too frequently"). This says whether a start now would be
 * one too many, and how long until it would not be.
 */
class TapScanBudget(private val most: Int = MOST, private val window: Double = WINDOW) {
    companion object {
        const val MOST = 5
        const val WINDOW = 30.0
    }

    private val starts = ArrayDeque<Double>()

    /** Seconds to wait before a start at [now] would be allowed: 0 when it may start now. */
    fun wait(now: Double): Double {
        while (starts.isNotEmpty() && now - starts.first() >= window) starts.removeFirst()
        return if (starts.size < most) 0.0 else starts.first() + window - now
    }

    /** A start was made at [now]. */
    fun started(now: Double) {
        starts.addLast(now)
    }
}
