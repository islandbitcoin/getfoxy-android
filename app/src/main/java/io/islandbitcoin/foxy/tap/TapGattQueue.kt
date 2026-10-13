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
 *
 * Each operation says which callback answers it ([Op.answer], a [GattAnswer]),
 * and [done] names the callback that came: an answer for something no longer
 * in flight is dropped, never taken for the operation that is (ENG-679). On
 * the Pixel 8 Pro (Android 17) the stack runs its own service discovery first
 * on every connect and holds the app's MTU question behind it: the MTU was
 * given up on after 2 s and its `onMtuChanged` came 0.3 s later, in the middle
 * of the app's discovery, and ended it. A dropped answer does not stop the
 * operation in flight's [stall] timer: one whose own answer never comes still
 * fails, and the queue goes on.
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

    class Op(val name: String, val start: () -> Boolean, val failed: (String) -> Unit, val stall: Double, val answer: GattAnswer)

    private val waiting = ArrayDeque<Op>()
    private var current: Op? = null
    private var timer: TapClock.Timer? = null
    private var closed = false

    /** What is in flight now, by name (the tests and the log). */
    val inFlight: String? get() = current?.name
    val queued: Int get() = waiting.size

    fun add(name: String, answer: GattAnswer, start: () -> Boolean, failed: (String) -> Unit, stall: Double = this.stall) {
        if (closed) {
            clock.after(0.0) { failed("the link is closed") }
            return
        }
        waiting.addLast(Op(name, start, failed, stall, answer))
        next()
    }

    /**
     * The operation in flight was answered (with whatever its callback said). [answer] is the
     * callback that came: when the operation in flight is answered by another, or nothing is in
     * flight, this is a late answer to one already given up on (or one Android sent unasked), and
     * nothing moves; the operation in flight keeps its stall timer. True when it ended the
     * operation in flight.
     */
    fun done(answer: GattAnswer): Boolean {
        val op = current
        if (op == null || op.answer != answer) {
            say("a late ${answer.said} answer, for nothing in flight now${op?.let { " (${it.name} is)" } ?: ""}; ignored")
            return false
        }
        timer?.cancel()
        timer = null
        current = null
        next()
        return true
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
 * The `BluetoothGattCallback` that answers a queued [TapGattQueue] operation.
 * Every operation and every answering callback names one, so neither can be
 * left out: an operation with no answer would again be ended by any late one
 * (ENG-679), and a mismatched one would drop its own real answer.
 */
enum class GattAnswer(val said: String) {
    /** `onMtuChanged`, for `requestMtu`. */
    MTU("MTU"),

    /** `onServicesDiscovered`, for `discoverServices`. */
    DISCOVERY("service discovery"),

    /** `onDescriptorWrite`, for the CCCD write that turns notifications on or off. */
    CCCD_WRITE("CCCD write"),

    /** `onCharacteristicWrite`, for `writeCharacteristic` (a long write's too: one callback after the execute). */
    WRITE("write"),

    /** `onCharacteristicRead`, for `readCharacteristic`. */
    READ("read"),

    /** `onReadRemoteRssi`, for `readRemoteRssi`. */
    RSSI("signal reading"),
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

/**
 * When the payer's scan is started, kept going and stopped, apart from the
 * Android calls themselves ([start] and [stop]), so the JVM can drive it.
 *
 * - Android's five starts in thirty seconds ([budget]): a start that would be
 *   one too many is made when it no longer would be.
 * - Android's scan timeout. AOSP's `ScanManager` moves a scan that has run
 *   longer than `scan_timeout_millis` (30 minutes by default) to opportunistic
 *   mode, where it hears only what other apps' scans hear; a scan with a
 *   hardware filter is exempt (`isExemptFromScanDowngrade`: `!shouldUseAllPassFilter`),
 *   an unfiltered one is not, and `onScanFailed` is never called. The payer's
 *   scan has no filter (ENG-679, [TapScanRules.hardwareFilters]) and a funded
 *   home or send screen may be left in front for longer than that (the page
 *   itself waits 30 minutes, BRIDGE.md), so a scan that has run [CYCLE]
 *   seconds is stopped and started again, through the same budget. iOS has
 *   no such limit and the spec is silent.
 *
 * A start that fails after the payer asked for it (one deferred by the
 * budget, or a restart) is handed to [lost], so the payer starts it again
 * as it does after `onScanFailed`, and never listens to nothing in silence.
 * Everything runs on the [clock]'s thread.
 */
class TapScanCycle(
    private val clock: TapClock,
    private val budget: TapScanBudget,
    private val start: () -> Boolean,
    private val stop: () -> Unit,
    private val lost: () -> Unit,
    private val say: (String) -> Unit = {},
    private val cycle: Double = CYCLE,
) {
    companion object {
        /** Well inside AOSP's 30-minute `scan_timeout_millis`. */
        const val CYCLE = 25 * 60.0
    }

    /** Whether Android is scanning for us now. */
    var scanning = false
        private set

    /** Whether the payer wants a scan. */
    var wanted = false
        private set

    private var later: TapClock.Timer? = null
    private var renew: TapClock.Timer? = null

    /** The payer wants a scan: true when one is running or will be once the budget allows. */
    fun startScan(): Boolean {
        wanted = true
        if (scanning || later != null) return true
        return startOrDefer()
    }

    /** The payer is done listening. */
    fun stopScan() {
        wanted = false
        later?.cancel()
        later = null
        renew?.cancel()
        renew = null
        if (!scanning) return
        scanning = false
        stop()
    }

    /** Android said the scan failed (`onScanFailed`): it is not running, and the payer starts it again. */
    fun failed() {
        scanning = false
        renew?.cancel()
        renew = null
    }

    private fun startOrDefer(): Boolean {
        val wait = budget.wait(clock.now())
        if (wait > 0) {
            say("Android allows five scans in thirty seconds; listening again in ${"%.1f".format(wait)} s")
            later = clock.after(wait) {
                later = null
                if (wanted && !scanning && !startNow()) lost()
            }
            return true
        }
        return startNow()
    }

    private fun startNow(): Boolean {
        if (!start()) return false
        budget.started(clock.now())
        scanning = true
        renew?.cancel()
        renew = clock.after(cycle) {
            renew = null
            if (!wanted || !scanning) return@after
            say("the scan has run ${(cycle / 60).toInt()} minutes; starting it again before Android stops listening for it")
            scanning = false
            stop()
            if (!startOrDefer()) lost()
        }
        return true
    }
}
