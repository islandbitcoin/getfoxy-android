package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.debug.Log

/** A scheduled run that may be called off. */
fun interface Cancel {
    fun cancel()
}

/** Where the machine runs: one thread (the main looper on a phone), a clock in ms, and timers. */
interface TorScheduler {
    fun now(): Long
    fun post(r: () -> Unit)
    fun after(ms: Long, r: () -> Unit): Cancel
}

/**
 * Tor's control link as the machine uses it. Every call answers once, on the
 * scheduler: with Tor's reply, or empty (`false`, `null`) after `timeoutMs`.
 */
interface TorControl {
    fun setConf(settings: List<Pair<String, String>>, timeoutMs: Long = 10_000, done: (ok: Boolean, message: String) -> Unit)
    fun getInfo(key: String, timeoutMs: Long = 10_000, done: (String?) -> Unit)
    fun extendCircuit(timeoutMs: Long = 5_000, done: (Boolean) -> Unit)
}

/** Tor itself: tor-android's TorService in this process, bound, never in the foreground. */
interface TorDaemon {
    /**
     * Write `torrc` and start Tor. Then, on the scheduler, [TorMachine.controlReady]
     * once the control link is up, or [TorMachine.daemonFinished] when Tor
     * would not start or its thread ended.
     */
    fun launch(torrc: String)

    /** Tor's thread has ended (it would not start, or it stopped). */
    val finished: Boolean

    /** A relay list saved by an earlier run: not the first setup. */
    fun hasRelayList(): Boolean

    /** Where Tor keeps its cache, which the directory seed's marker sits in. */
    fun seedDir(): java.io.File?
}

/**
 * Tor in this process: start, ready, the transport ladder, stuck detection,
 * park and resume, restart. A port of Foxy/Tor/TorService.swift, with Android
 * standing in for iOS where they differ:
 *
 * - tor-android owns the control link (a Unix control socket only this app can
 *   open, authenticated by it); Foxy uses that one link, so there is no
 *   reconnecting of links and no recovery of a listener iOS reclaimed. A link
 *   that does not answer a set-up in 3 s is a failure the page offers RETRY on.
 * - Tor runs in tor-android's bound service; when its thread ends, RETRY
 *   launches it again (tor-android can; iOS's TorThread cannot).
 * - no Orbot bypass (ANDROID-MAPPING decision 3): Orbot is "none".
 *
 * Kept from iOS: the SOCKS port read from `net/listeners/socks` (loopback only,
 * 20 tries a second apart, a generation guard, forgotten before anything that
 * can reopen the listener); ready only on a circuit with directory info and a
 * port; the ladder direct (three goes) → obfs4 → Snowflake with each
 * transport's patience and the extensions for a bootstrap still moving; the
 * stall watch on Tor's byte count; Tor off the network when Foxy is put away
 * and when the phone has no network, and a fresh set-up on every return.
 *
 * Every method runs on the scheduler's one thread.
 */
class TorMachine(
    private val sched: TorScheduler,
    private val daemon: TorDaemon,
    private val transports: PluggableTransports,
    private val memory: TransportMemory,
    private val release: Boolean,
    /** Whether the phone has any network now ('unknown' counts as yes). */
    private val hasNetwork: () -> Boolean,
    /** Tor connected: the open connection, if the person chose it, ends (S11). */
    private val onEstablished: () -> Unit = {},
    /** Tor's listener moved or Tor left the network: pooled connections to the old one go. */
    private val onPortForgotten: () -> Unit = {},
) {
    companion object {
        const val SOCKS_READS = 20
        const val STALL_AFTER_MS: Long = 5_000
        const val DIRECT_TRIES_MAX = 2
        const val CONTROL_WAIT_MS: Long = 3_000

        /**
         * A RETRY or RESTART TOR this soon after a set-up began joins it
         * instead of starting another (ENG-652). Every set-up switches Tor's
         * network off, which kills the circuits the one before it was
         * building, and Tor blames their guards: three stacked inside a second
         * (a return, the next activity's start, a restart) left every primary
         * guard "not reachable", and Tor held each new stream "waiting for
         * circuit" until the SOCKS handshake timed out, 30 s, with a circuit
         * established.
         */
        const val SET_UP_JOIN_MS: Long = 4_000
    }

    /** Told on every change the page should hear, on the scheduler. */
    var onChange: (() -> Unit)? = null

    /** A debug build's forced transport (`FoxyForceTransport`), for the exit test. */
    var forced: TorTransport? = null

    private var launched = false
    private var control: TorControl? = null
    @Volatile var ready = false
        private set
    @Volatile var socksPort = 0
        private set
    private var circuitUp = false
    private var dirInfo = true
    var everUp = false
        private set
    var firstSetup = false
        private set
    private var failed = false
    var progress = 0
        private set
    var transport = TorTransport.DIRECT
        private set
    private var tried = mutableSetOf<TorTransport>()
    private var armedAt = 0L
    private var progressAtArm = 0
    private var bootEventAt = Long.MIN_VALUE / 2
    private var bootSaid = 0
    private var extensions = 0
    private var deadline: Cancel? = null
    private var lifeAt = 0L
    private var stuckTold = false
    private var lifeTicking = false
    private var keepClock = false
    private var clockKeptFor = -1
    private var setUpGen = 0
    private var setUpPending = false
    private var setUpAt = Long.MIN_VALUE / 2
    /**
     * Whether a CIRCUIT_ESTABLISHED now is about Tor's network as it is: false
     * from a `DisableNetwork 1` until the `DisableNetwork 0` after it is
     * answered. Tor writes an event on the control link before its reply to a
     * later command, and the network being off builds nothing, so an event
     * heard in between is about circuits that `DisableNetwork 1` closed.
     */
    private var circuitsCount = true
    var parked = false
        private set
    private var socksGen = 0
    private var socksReadingGen = -1
    private var blackout = false
    private var directTries = 0
    private var offForBlackout = false
    private var networkBackAt = Long.MIN_VALUE / 2
    private var nudgedEventAt: Long? = null
    private var stallWatching = false
    private var stallAsking = false
    private var stallOn = false
    private var stallGen = 0
    private var stallBytes: Long? = null
    private var stallSince = 0L
    private var stallSawData = false

    val isRunning: Boolean get() = ready

    val stopped: Boolean get() = launched && daemon.finished

    /** "up", "connecting", "failed", "stuck" or "stopped" (TorService.swift `state`). */
    val state: String
        get() = when {
            ready -> "up"
            stopped -> "stopped"
            failed -> "failed"
            stuck -> "stuck"
            else -> "connecting"
        }

    val stuck: Boolean
        get() = TorStuck.offered(
            TorStuck.connecting(running = launched, ready = ready, failed = failed, stopped = stopped, parked = parked, offNetwork = offForBlackout),
            hasLink = control != null,
            quietMs = sched.now() - lifeAt,
            bytesCounted = transport == TorTransport.DIRECT,
            snowflake = transport == TorTransport.SNOWFLAKE,
        )

    private fun changed() {
        onChange?.invoke()
    }

    private fun noteLife() {
        lifeAt = sched.now()
    }

    // MARK: Starting

    /** Start Tor; or, if it is running without a circuit, try again now. */
    fun start() {
        if (ready) return
        // asked before `failed` is cleared: a failed set-up is never joined (ROUTE.md)
        val join = joinable()
        failed = false
        when {
            !launched -> {
                changed()
                launch()
            }
            stopped -> relaunch("Tor had stopped")
            control == null -> {
                armDeadline()
                changed()
            }
            join -> Log.line("[foxy] tor: retrying — a set-up began ${sched.now() - setUpAt}ms ago; joining it")
            else -> setUp("retrying")
        }
    }

    /**
     * A set-up began less than [SET_UP_JOIN_MS] ago and is still going: not
     * failed, not parked, not off for want of a network, its link up. Another
     * now would only switch the network off under the circuits it is building.
     */
    private fun joinable(): Boolean =
        launched && !ready && !failed && !parked && !offForBlackout && !setUpPending && control != null &&
            sched.now() - setUpAt < SET_UP_JOIN_MS

    /**
     * RESTART TOR and RETRY: a full set-up, keeping the fallback clock and the
     * transports already tried (TorService.swift `restart`). Not over a set-up
     * that began a moment ago because the network came back.
     */
    fun restart(reason: String) {
        if (ready) return
        if (!launched) return start()
        if (stopped) return relaunch(reason)
        if (parked) return setUp(reason)
        if (sched.now() - networkBackAt < 4_000 && control != null) {
            Log.line("[foxy] tor: $reason — already setting up, the network came back a moment ago")
            return
        }
        if (joinable()) {
            Log.line("[foxy] tor: $reason — a set-up began ${sched.now() - setUpAt}ms ago; joining it")
            return
        }
        Log.line("[foxy] tor: restarting ($reason): a new set-up")
        noteLife()
        keepClock = true
        failed = false
        if (deadline == null) armDeadline()
        changed()
        setUp(reason)
    }

    /**
     * A new daemon for one whose thread ended. `parked` is left as it is: a
     * RETRY while Foxy is away (the page's `torRetry` keeps running in the
     * background) launches Tor off the network (`DisableNetwork 1` in its
     * torrc, so it never connects; `controlReady` sends it again the moment
     * its link is up); the return sets it up.
     */
    private fun relaunch(reason: String) {
        Log.line(if (parked) "[foxy] tor: $reason; starting Tor again, off the network until Foxy comes back" else "[foxy] tor: $reason; starting Tor again")
        control = null
        launched = false
        ready = false
        circuitUp = false
        failed = false
        changed()
        launch()
        if (parked) {
            // nothing is set up while away, so there is no bootstrap to time
            deadline?.cancel()
            deadline = null
        }
    }

    private fun launch() {
        launched = true
        circuitsCount = true
        transport = forced ?: memory.remembered
        var settings = transportSettings(transport)
        if (settings == null) {
            transport = TorTransport.DIRECT
            settings = emptyList()
        }
        tried = mutableSetOf(transport)
        blackout = !hasNetwork()
        directTries = 0
        firstSetup = !daemon.hasRelayList()
        if (firstSetup) Log.line("[foxy] tor: no relay list saved yet: the first setup")
        if (transport != TorTransport.DIRECT) Log.line("[foxy] tor: starting through ${transport.wire}")
        armDeadline()
        noteLife()
        if (!lifeTicking) {
            lifeTicking = true
            lifeTick()
        }
        Log.line(if (parked) "[foxy] tor starting off the network; it chooses its SOCKS port" else "[foxy] tor starting; it chooses its SOCKS port")
        // away: Tor boots with its network off, so a control link that never comes leaves it offline
        daemon.launch(TorConfig.torrc(release, settings, offline = parked))
    }

    /** tor-android's control link is up. */
    fun controlReady(ctrl: TorControl) {
        control = ctrl
        noteLife()
        if (parked) {
            // put away before the link came up: Tor goes off the network now and
            // nothing is set up until Foxy comes back (Android keeps the process
            // running where iOS suspends it, so a late bootstrap would stay online)
            setUpPending = false
            val gen = setUpGen
            circuitsCount = false
            ctrl.setConf(listOf("DisableNetwork" to "1"), 4_000) { ok, message ->
                if (gen == setUpGen && parked) {
                    if (transport != TorTransport.DIRECT) transports.stopAll()
                    Log.line(if (ok) "[foxy] tor: off the network; Foxy was put away before its control link came up" else "[foxy] tor: could not switch its network off ($message)")
                }
            }
            // the watch runs from here on (it rests while parked); the set-up on the return turns it on
            watchForStall()
            changed()
            return
        }
        ctrl.getInfo("status/enough-dir-info") { v -> if (control === ctrl) dirInfoChanged(v != "0") }
        // where Tor has got to, asked rather than waited for: a link that came up late heard no steps
        ctrl.getInfo("status/bootstrap-phase", 5_000) { line ->
            if (control !== ctrl || line == null) return@getInfo
            val p = Regex("PROGRESS=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@getInfo
            if (p <= 0) return@getInfo
            Log.line("[foxy] tor: already at $p% when the control link came up")
            bootSaid = maxOf(bootSaid, p)
            bootEventAt = sched.now()
            if (p > progress && p < 100) {
                progress = p
                changed()
            }
        }
        readSocksPort()
        stallReset()
        watchForStall()
        if (setUpPending) {
            setUpPending = false
            setUp("control link connected")
        }
        changed()
    }

    /** tor-android started but no control link came within its wait: Foxy says it could not connect. */
    fun noControl(why: String) {
        Log.line("[foxy] tor: $why")
        fail()
    }

    /** Tor's thread ended, or it never started. */
    fun daemonFinished(why: String) {
        Log.line("[foxy] tor: stopped ($why)")
        control = null
        ready = false
        circuitUp = false
        forgetSocksPort()
        deadline?.cancel()
        deadline = null
        changed()
    }

    // MARK: Events

    /**
     * A STATUS_CLIENT event, as tor-android hands it on: `severity action
     * KEY=value ...` (control-spec "Status events").
     */
    fun statusEvent(data: String) {
        val parts = splitEvent(data)
        if (parts.size < 2) return
        val severity = parts[0]
        val action = parts[1]
        val args = parts.drop(2).mapNotNull { a ->
            val i = a.indexOf('=')
            if (i <= 0) null else a.substring(0, i) to a.substring(i + 1).trim('"')
        }.toMap()
        when {
            action == "CIRCUIT_ESTABLISHED" -> {
                noteLife()
                if (!circuitsCount) {
                    Log.line("[foxy] tor: a circuit from before the network went off; not counted")
                    return
                }
                established()
            }
            action == "CIRCUIT_NOT_ESTABLISHED" -> {
                noteLife()
                lost()
            }
            action == "ENOUGH_DIR_INFO" || action == "NOT_ENOUGH_DIR_INFO" -> {
                noteLife()
                dirInfoChanged(action == "ENOUGH_DIR_INFO")
            }
            action == "BOOTSTRAP" && severity == "WARN" -> {
                val reason = args["REASON"] ?: return
                if (lastTrouble != reason) {
                    lastTrouble = reason
                    Log.line("[foxy] tor: not getting through at ${args["PROGRESS"] ?: "?"}% — $reason (${args["COUNT"] ?: "?"} so far)")
                }
            }
            action == "BOOTSTRAP" -> {
                val p = args["PROGRESS"]?.toIntOrNull() ?: return
                noteLife()
                bootEventAt = sched.now()
                bootSaid = maxOf(bootSaid, p)
                if (p <= progress) return
                progress = p
                daemon.seedDir()?.let { TorSeed.reached(p, it) }
                changed()
            }
        }
    }

    private var lastTrouble: String? = null

    /** Words split on spaces, a quoted value kept whole. */
    internal fun splitEvent(data: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in data) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    cur.append(c)
                }
                c == ' ' && !quoted -> {
                    if (cur.isNotEmpty()) out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun established() {
        circuitUp = true
        if (parked) return
        if (setUpPending) return
        if (!dirInfo) {
            if (!ready) Log.line("[foxy] tor: circuit up, waiting for directory info")
            return
        }
        if (socksPort == 0) {
            if (!ready) Log.line("[foxy] tor: circuit up, reading its SOCKS port")
            readSocksPort()
            return
        }
        deadline?.cancel()
        deadline = null
        if (ready) return
        ready = true
        everUp = true
        directTries = 0
        daemon.seedDir()?.let { TorSeed.proven(it) }
        firstSetup = false
        bootSaid = 0
        failed = false
        progress = 100
        if (blackout && transport != TorTransport.DIRECT) {
            Log.line("[foxy] tor: ${transport.wire} got through, but the phone was off the network on the way here — not remembering it")
            memory.remembered = TorTransport.DIRECT
        } else if (forced == null) {
            memory.remembered = transport
        }
        blackout = false
        tried = mutableSetOf(transport)
        // continuing unprotected lasts until Tor can carry the traffic
        onEstablished()
        Log.line("[foxy] tor: circuit established")
        changed()
    }

    /** Silent to the person, but the route closes at once until a circuit is back. */
    private fun lost() {
        circuitUp = false
        if (!ready) return
        ready = false
        Log.line("[foxy] tor: circuit lost, reconnecting")
        armDeadline()
        changed()
    }

    private fun fail() {
        deadline?.cancel()
        deadline = null
        if (ready || failed) return
        failed = true
        Log.line("[foxy] tor: could not connect")
        changed()
    }

    private fun dirInfoChanged(enough: Boolean) {
        if (enough == dirInfo) return
        dirInfo = enough
        if (enough) {
            Log.line("[foxy] tor: directory info complete")
            if (circuitUp) established()
        } else if (ready) {
            ready = false
            Log.line("[foxy] tor: directory info incomplete; new circuits wait for it")
            armDeadline()
            changed()
        }
    }

    // MARK: The SOCKS port

    /** Asked until Tor answers, one read per listener at a time (TorService.swift:204-229). */
    private fun readSocksPort(attempt: Int = 0, asked: Int? = null) {
        val ctrl = control ?: run {
            socksReadingGen = -1
            return
        }
        val gen = asked ?: socksGen
        if (gen != socksGen) return
        if (asked == null) {
            if (socksReadingGen == gen) return
            socksReadingGen = gen
        }
        ctrl.getInfo("net/listeners/socks", 3_000) { value ->
            if (gen != socksGen) return@getInfo
            val port = value?.let { loopbackPort(it) } ?: 0
            if (port == 0) {
                if (attempt < SOCKS_READS) {
                    sched.after(1_000) { readSocksPort(attempt + 1, gen) }
                } else {
                    socksReadingGen = -1
                    Log.line("[foxy] tor: reports no SOCKS listener")
                }
                return@getInfo
            }
            socksReadingGen = -1
            if (port != socksPort) Log.line("[foxy] tor: SOCKS on 127.0.0.1:$port")
            socksPort = port
            if (circuitUp) established()
        }
    }

    /** "127.0.0.1:53211", quoted, the first of any listed. Loopback only. */
    internal fun loopbackPort(raw: String): Int? {
        val first = raw.replace("\"", "").trim().split(' ').firstOrNull() ?: return null
        val prefix = "127.0.0.1:"
        if (!first.startsWith(prefix)) return null
        val port = first.removePrefix(prefix).toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    /** Before anything that can make Tor close and reopen its listener: no request goes to a port until Tor says it is its own. */
    private fun forgetSocksPort() {
        socksPort = 0
        socksGen += 1
        onPortForgotten()
        if (ready) {
            ready = false
            changed()
        }
    }

    // MARK: Set-up, park, resume

    /**
     * A private connection set up on the daemon that is already running, the
     * way launch sets one up (TorService.swift:463-571): not ready, no port,
     * network off, the transport started fresh, its settings applied, network
     * on, the port read again, a circuit asked for, ready on Tor's own
     * CIRCUIT_ESTABLISHED.
     */
    fun setUp(reason: String) {
        if (parked) {
            Log.line("[foxy] tor: $reason — waiting for Foxy to come back")
            return
        }
        if (!launched) return start()
        setUpGen += 1
        val gen = setUpGen
        setUpAt = sched.now()
        val keep = keepClock
        keepClock = false
        clockKeptFor = if (keep) gen else -1
        noteLife()
        stallOff()
        if (!keep) {
            deadline?.cancel()
            deadline = null
        }
        ready = false
        circuitUp = false
        failed = false
        everUp = false
        socksPort = 0
        socksGen += 1
        onPortForgotten()
        progress = 0
        if (!keep) tried = mutableSetOf(transport)
        changed()
        val ctrl = control ?: run {
            setUpPending = true
            armDeadline()
            return
        }
        if (!hasNetwork()) {
            offForBlackout = true
            Log.line("[foxy] tor: $reason — the phone has no network; off it until there is one")
            circuitsCount = false
            ctrl.setConf(listOf("DisableNetwork" to "1"), 4_000) { _, _ -> }
            return
        }
        Log.line("[foxy] tor: setting up a private connection ($reason) through ${transport.wire}")
        var answered = false
        circuitsCount = false
        ctrl.setConf(listOf("DisableNetwork" to "1"), 4_000) { _, _ ->
            if (gen != setUpGen || answered) return@setConf
            answered = true
            networkOff(gen, ctrl)
        }
        sched.after(CONTROL_WAIT_MS) {
            if (gen != setUpGen || answered) return@after
            answered = true
            Log.line("[foxy] tor: the control link is not answering")
            fail()
        }
    }

    private fun networkOff(gen: Int, ctrl: TorControl) {
        setUpProgress(10)
        if (transport != TorTransport.DIRECT) transports.stopAll()
        val settings = transportSettings(transport)
        if (settings == null) {
            // this transport will not start now: on with the network, and the next one
            networkOn(gen, ctrl) { deadlineExpired() }
            return
        }
        setUpProgress(20)
        if (clockKeptFor != gen) armDeadline()
        // networkOn keeps the network off if a newer set-up, a put-away or the network going has taken over
        apply(settings, ctrl) { networkOn(gen, ctrl) }
    }

    private fun networkOn(gen: Int, ctrl: TorControl, then: (() -> Unit)? = null) {
        // never on for a set-up something newer superseded: that could be Foxy put away (S21)
        if (gen != setUpGen) return
        ctrl.setConf(listOf("DisableNetwork" to "0")) { ok, _ ->
            if (gen != setUpGen) return@setConf
            circuitsCount = true
            if (!ok) Log.line("[foxy] tor: could not switch the network back on")
            stallReset()
            setUpProgress(30)
            ctrl.getInfo("status/enough-dir-info") { v -> if (control === ctrl) dirInfoChanged(v != "0") }
            readSocksPort()
            if (then == null) {
                if (clockKeptFor != gen) armDeadline()
                askForCircuit(gen)
            } else {
                then()
            }
        }
    }

    /** One general circuit asked for, every 5 s until Tor announces one: after a long idle it may predict none. */
    private fun askForCircuit(gen: Int) {
        if (gen != setUpGen || ready || circuitUp) return
        val ctrl = control ?: return
        ctrl.extendCircuit(5_000) { }
        sched.after(5_000) { askForCircuit(gen) }
    }

    private fun setUpProgress(p: Int) {
        if (p <= progress || p >= 100) return
        progress = p
        changed()
    }

    /**
     * Foxy is going to the background: Tor comes off the network now, so it has
     * no connection to lose and nothing to hold against a guard
     * (TorService.swift `backgrounded`). `done` once Tor answered or 4 s passed.
     */
    fun backgrounded(done: () -> Unit) {
        val ctrl = control
        if (!launched || parked) return done()
        if (stopped) {
            // a Tor whose thread ended has nothing on the network to take off, so
            // no DisableNetwork; it is still marked away, so a RETRY before the
            // return launches the new Tor off the network (`relaunch`)
            parked = true
            setUpGen += 1
            setUpPending = false
            stallOff()
            deadline?.cancel()
            deadline = null
            Log.line("[foxy] tor: put away while stopped; a new Tor stays off the network until Foxy comes back")
            changed()
            return done()
        }
        if (ctrl == null) {
            // Tor is starting and its control link is not up yet: parked now, so
            // `controlReady` takes it off the network the moment the link arrives
            parked = true
            setUpGen += 1
            setUpPending = false
            stallOff()
            deadline?.cancel()
            deadline = null
            ready = false
            circuitUp = false
            Log.line("[foxy] tor: put away before its control link came up; off the network once it does")
            changed()
            return done()
        }
        parked = true
        setUpGen += 1
        val gen = setUpGen
        setUpPending = false
        circuitsCount = false
        stallOff()
        deadline?.cancel()
        deadline = null
        ready = false
        circuitUp = false
        socksPort = 0
        socksGen += 1
        onPortForgotten()
        changed()
        ctrl.setConf(listOf("DisableNetwork" to "1"), 4_000) { ok, message ->
            if (gen == setUpGen && parked) {
                if (transport != TorTransport.DIRECT) transports.stopAll()
                Log.line(if (ok) "[foxy] tor: off the network while Foxy is in the background" else "[foxy] tor: could not switch its network off ($message)")
            }
            done()
        }
    }

    /** The app came forward after `awayS` seconds: a fresh set-up, unless the circuit never went down. */
    fun resumed(awayS: Long) {
        if (!launched) return
        val wasParked = parked
        parked = false
        if (stopped) return relaunch("back after ${awayS}s; Tor had stopped")
        if (ready && !wasParked && awayS < 30) {
            Log.line("[foxy] tor: back after ${awayS}s and the circuit is up; nothing to set up")
            return
        }
        if (!wasParked && awayS < 1 && !failed && (deadline != null || setUpPending)) {
            Log.line("[foxy] tor: back after ${awayS}s with a set-up already running; left alone")
            return
        }
        setUp("back after ${awayS}s")
    }

    // MARK: The phone's network

    /** The network monitor's word (TorService.swift `networkWent`): it only decides what the ladder may conclude. */
    fun networkWent(have: Boolean) {
        if (!have) {
            blackout = true
            // after a moment: a handover from wifi to cellular is a brief gap
            sched.after(1_500) { if (!hasNetwork()) networkWentAway() }
        } else {
            sched.post { networkCameBack() }
        }
    }

    private fun networkWentAway() {
        val ctrl = control
        if (!launched || parked || offForBlackout || ctrl == null) return
        offForBlackout = true
        setUpGen += 1
        setUpPending = false
        circuitsCount = false
        stallOff()
        deadline?.cancel()
        deadline = null
        ready = false
        circuitUp = false
        socksPort = 0
        socksGen += 1
        onPortForgotten()
        changed()
        ctrl.setConf(listOf("DisableNetwork" to "1"), 4_000) { ok, message ->
            Log.line(if (ok) "[foxy] tor: off the network while the phone has none" else "[foxy] tor: could not switch its network off ($message)")
        }
    }

    private fun networkCameBack() {
        if (!offForBlackout) return
        offForBlackout = false
        if (!launched || parked || ready) return
        networkBackAt = sched.now()
        directTries = 0
        setUp("the network is back")
    }

    // MARK: The clock and the ladder

    private fun armDeadline(extending: Boolean = false) {
        deadline?.cancel()
        if (!extending) extensions = 0
        armedAt = sched.now()
        progressAtArm = progress
        val wait = if (transport == TorTransport.DIRECT && firstSetup) maxOf(20_000, transport.patienceMs) else transport.patienceMs
        deadline = sched.after(wait) {
            deadline = null
            if (!ready) deadlineExpired()
        }
    }

    /** A bootstrap out of time: the next transport not yet tried, or the failure the page shows (TorService.swift:363-488). */
    private fun deadlineExpired() {
        if (ready) return
        if (!hasNetwork()) {
            blackout = true
            if (control != null && !offForBlackout && !parked) {
                Log.line("[foxy] tor: no network at all, so ${transport.wire} is not what is wrong")
                networkWentAway()
                return
            }
            armDeadline(extending = true)
            return
        }
        if (stopped) {
            changed()
            return
        }
        if (circuitUp && !dirInfo) {
            Log.line("[foxy] tor: ${transport.wire} has a circuit and is fetching directory info, giving it longer")
            armDeadline(extending = true)
            return
        }
        val now = sched.now()
        if (now - bootEventAt < 3_000 && progress < 100 && extensions < 4) {
            extensions += 1
            Log.line("[foxy] tor: ${transport.wire} reported a step a moment ago, giving it longer")
            armDeadline(extending = true)
            return
        }
        if (progress > progressAtArm && progress < 100 && extensions < 2) {
            extensions += 1
            Log.line("[foxy] tor: ${transport.wire} still bootstrapping at $progress%, giving it longer")
            armDeadline(extending = true)
            return
        }
        if (control == null) {
            armDeadline(extending = true)
            return
        }
        if (transport == TorTransport.DIRECT && bootSaid >= 15 && progress < 100 && now - bootEventAt < 60_000) {
            if (now - bootEventAt >= 25_000 && nudgedEventAt != bootEventAt) {
                nudgedEventAt = bootEventAt
                Log.line("[foxy] tor: direct has reported nothing for ${(now - bootEventAt) / 1000}s at $bootSaid%; starting its connections again")
                nudge()
                armDeadline(extending = true)
                return
            }
            Log.line("[foxy] tor: direct is loading the relay list ($bootSaid%), giving it longer")
            armDeadline(extending = true)
            return
        }
        if (transport == TorTransport.DIRECT && directTries < DIRECT_TRIES_MAX) {
            directTries += 1
            Log.line("[foxy] tor: direct has not moved in ${transport.patienceMs / 1000}s; restarting it (try ${directTries + 1} of ${DIRECT_TRIES_MAX + 1})")
            setUp("direct did not move")
            return
        }
        val next = TorTransport.ORDER.firstOrNull { it !in tried }
        if (next == null) {
            fail()
            return
        }
        switchTransport(next)
    }

    private fun transportSettings(t: TorTransport): List<Pair<String, String>>? {
        if (t == TorTransport.DIRECT) return emptyList()
        return try {
            Bridges.settings(t, transports.start(t))
        } catch (e: Exception) {
            Log.line("[foxy] tor: ${t.wire} would not start (${e.message})")
            null
        }
    }

    private fun switchTransport(next: TorTransport) {
        tried.add(next)
        val ctrl = control
        val settings = if (ctrl == null) null else transportSettings(next)
        if (ctrl == null || settings == null) {
            deadlineExpired()
            return
        }
        Log.line("[foxy] tor: ${transport.wire} did not connect after ${(sched.now() - armedAt) / 1000}s at $progress%, trying ${next.wire}")
        transport = next
        directTries = 0
        progress = 0
        noteLife()
        armDeadline()
        changed()
        val gen = setUpGen
        apply(settings, ctrl) {
            if (gen != setUpGen) return@apply
            readSocksPort()
            askForCircuit(gen)
        }
    }

    /**
     * One SETCONF for the transport (TorService.swift `apply`): a single
     * configuration change, so Tor closes and reopens its listener once. For
     * direct, `UseBridges 0` is enough.
     */
    private fun apply(settings: List<Pair<String, String>>, ctrl: TorControl, then: () -> Unit) {
        val args = settings.ifEmpty { listOf("UseBridges" to "0") }
        forgetSocksPort()
        ctrl.setConf(args) { ok, message ->
            if (!ok) Log.line("[foxy] tor: settings refused: $message")
            then()
        }
    }

    /** Switching the network off and on: Tor tries its relays again now, keeping what it fetched. */
    private fun nudge() {
        val ctrl = control ?: return
        if (parked) return
        val gen = setUpGen
        Log.line("[foxy] tor: retrying")
        forgetSocksPort()
        stallReset()
        circuitsCount = false
        ctrl.setConf(listOf("DisableNetwork" to "1")) { _, _ ->
            if (gen != setUpGen || parked) return@setConf
            ctrl.setConf(listOf("DisableNetwork" to "0")) { ok, _ ->
                if (gen != setUpGen || parked) return@setConf
                if (!ok) Log.line("[foxy] tor: could not switch the network back on")
                circuitsCount = true
                stallReset()
                readSocksPort()
            }
        }
    }

    // MARK: The stall watch and stuck

    private fun stallReset() {
        stallOn = true
        stallGen += 1
        stallBytes = null
        stallSawData = false
        stallSince = sched.now()
    }

    private fun stallOff() {
        stallOn = false
        stallGen += 1
    }

    private fun watchForStall() {
        if (stallWatching) return
        stallWatching = true
        stallTick()
    }

    /** Once a second, Tor's byte count, while direct Tor is connecting for the first time since launch or a return. */
    private fun stallTick() {
        sched.after(1_000) { stallTick() }
        val ctrl = control ?: return
        if (!stallOn || stallAsking || ready || everUp || parked || transport != TorTransport.DIRECT) return
        val gen = stallGen
        stallAsking = true
        ctrl.getInfo("traffic/read", 2_000) { value ->
            stallAsking = false
            val bytes = value?.trim()?.toLongOrNull()
            if (gen != stallGen || ready || bytes == null) return@getInfo
            val now = sched.now()
            val before = stallBytes
            if (before == null) {
                stallBytes = bytes
                stallSince = now
                return@getInfo
            }
            if (bytes != before) {
                stallBytes = bytes
                stallSince = now
                stallSawData = true
                noteLife()
                return@getInfo
            }
            if (!stallSawData || now - stallSince < STALL_AFTER_MS) return@getInfo
            Log.line("[foxy] tor: no data for ${STALL_AFTER_MS / 1000}s at $progress%, starting its connections again")
            nudge()
        }
    }

    /** Once a second from launch: tell the page when stuck starts or ends. */
    private fun lifeTick() {
        sched.after(1_000) { lifeTick() }
        val now = stuck
        if (now == stuckTold) return
        stuckTold = now
        Log.line(if (now) "[foxy] tor: nothing has moved for ${(sched.now() - lifeAt) / 1000}s; RESTART TOR offered" else "[foxy] tor: moving again; RESTART TOR withdrawn")
        changed()
    }
}
