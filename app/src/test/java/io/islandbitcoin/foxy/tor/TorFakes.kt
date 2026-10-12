package io.islandbitcoin.foxy.tor

import java.io.File

/** One thread and a clock the test moves: posts run on [drain], timers when [advance] passes them. */
class FakeScheduler : TorScheduler {
    var now = 1_000_000L
    private val posts = ArrayDeque<() -> Unit>()
    private class Timer(val at: Long, val seq: Long, val r: () -> Unit) {
        var cancelled = false
    }
    private val timers = mutableListOf<Timer>()
    private var seq = 0L

    override fun now() = now
    override fun post(r: () -> Unit) {
        posts.addLast(r)
    }

    override fun after(ms: Long, r: () -> Unit): Cancel {
        val t = Timer(now + ms, seq++, r)
        timers.add(t)
        return Cancel { t.cancelled = true }
    }

    fun drain() {
        var guard = 0
        while (posts.isNotEmpty()) {
            posts.removeFirst()()
            if (++guard > 100_000) error("posts never end")
        }
    }

    /** Moves the clock by `ms`, running every timer due on the way, in order. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            drain()
            val next = timers.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy<Timer>({ it.at }, { it.seq })) ?: break
            timers.remove(next)
            now = next.at
            next.r()
        }
        now = end
        drain()
    }
}

/** Tor's control link: records what it was told and answers as the test says, on the scheduler. */
class FakeControl(private val sched: FakeScheduler) : TorControl {
    val setConfs = mutableListOf<List<Pair<String, String>>>()
    val asked = mutableListOf<String>()
    var circuitsAsked = 0
    /** What GETINFO answers, by key; a missing key answers nothing (null). */
    val info = mutableMapOf(
        "net/listeners/socks" to "\"127.0.0.1:9150\"",
        "status/enough-dir-info" to "1",
        "status/bootstrap-phase" to "NOTICE BOOTSTRAP PROGRESS=0 TAG=starting SUMMARY=\"Starting\"",
        "traffic/read" to "0",
    )
    var answerSetConf = true
    var setConfOk = true

    override fun setConf(settings: List<Pair<String, String>>, timeoutMs: Long, done: (Boolean, String) -> Unit) {
        setConfs.add(settings)
        if (!answerSetConf) {
            sched.after(timeoutMs) { done(false, "no answer") }
            return
        }
        sched.post { done(setConfOk, if (setConfOk) "OK" else "refused") }
    }

    override fun getInfo(key: String, timeoutMs: Long, done: (String?) -> Unit) {
        asked.add(key)
        val v = info[key]
        sched.post { done(v) }
    }

    override fun extendCircuit(timeoutMs: Long, done: (Boolean) -> Unit) {
        circuitsAsked++
        sched.post { done(true) }
    }

    fun last(key: String): String? = setConfs.lastOrNull { s -> s.any { it.first == key } }?.first { it.first == key }?.second
}

class FakeDaemon(private val dir: File? = null) : TorDaemon {
    val torrcs = mutableListOf<String>()
    var ended = false
    var relayList = true
    override fun launch(torrc: String) {
        torrcs.add(torrc)
        ended = false
    }
    override val finished get() = ended
    override fun hasRelayList() = relayList
    override fun seedDir(): File? = dir
}

class FakeTransports : PluggableTransports {
    val started = mutableListOf<TorTransport>()
    var stops = 0
    var refuse: TorTransport? = null
    override fun start(transport: TorTransport): Int {
        if (transport == refuse) throw IllegalStateException("${transport.wire} would not start")
        started.add(transport)
        return if (transport == TorTransport.OBFS4) 41001 else 41002
    }
    override fun stopAll() {
        stops++
    }
}
