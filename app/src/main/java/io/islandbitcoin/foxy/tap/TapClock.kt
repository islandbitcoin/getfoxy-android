package io.islandbitcoin.foxy.tap

/**
 * The clock a tap's link runs on: what the time is, and a task for later.
 *
 * iOS's TapLink uses `Timer.scheduledTimer` and `DispatchQueue.main.asyncAfter`
 * on the main queue. The links here take this instead, so the radio layer can
 * hand them the main looper (a `Handler`) and the JVM tests a clock they move
 * by hand. Everything a link does, it does on the one thread its clock runs
 * tasks on; the radio layer posts its callbacks there too.
 */
interface TapClock {
    /** Seconds since a fixed point, as `ProcessInfo.systemUptime`. */
    fun now(): Double

    /** Run [task] after [seconds], on the link's thread, unless cancelled first. */
    fun after(seconds: Double, task: () -> Unit): Timer

    fun interface Timer {
        fun cancel()
    }
}
