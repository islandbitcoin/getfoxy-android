package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tor's state machine (a port of TorService.swift) against a fake control link,
 * a fake daemon and a clock the test moves: ready only on a circuit with
 * directory info and a port read from Tor; the port forgotten before anything
 * that can move the listener; the ladder direct (three goes) → obfs4 →
 * Snowflake with each one's patience, then the failure; the bridge remembered;
 * off the network when put away and when the phone has none; a fresh set-up on
 * every return; RETRY bringing a stopped Tor back; stuck; the seed's marker.
 */
class TorMachineTest {
    private val sched = FakeScheduler()
    private val dir = TestSupport.tempDir("tor")
    private val daemon = FakeDaemon(dir)
    private val transports = FakeTransports()
    private var wall = 1_790_000_000_000L
    private val memory = TransportMemory(File(dir, "foxy-tor-transport")) { wall }
    private var network = true
    private var established = 0
    private var forgotten = 0
    private var changes = 0
    private val control = FakeControl(sched)

    private fun machine(release: Boolean = false) = TorMachine(
        sched, daemon, transports, memory, release, { network },
        onEstablished = { established++ }, onPortForgotten = { forgotten++ },
    ).also { it.onChange = { changes++ } }

    /** Launched, its control link up, its port read. */
    private fun connected(m: TorMachine = machine()): TorMachine {
        m.start()
        m.controlReady(control)
        sched.drain()
        return m
    }

    private fun circuit(m: TorMachine) {
        m.statusEvent("NOTICE CIRCUIT_ESTABLISHED")
        sched.drain()
    }

    @Test
    fun launchWritesTheTorrcAndReadyNeedsACircuitDirectoryInfoAndAPort() {
        val m = machine()
        m.start()
        val torrc = daemon.torrcs.single()
        assertTrue(torrc.contains("SocksPort auto IsolateDestAddr ExtendedErrors\n"))
        assertTrue(torrc.contains("SafeSocks 1\n"))
        assertEquals("connecting", m.state)
        assertFalse(m.isRunning)
        // the circuit before the control link answered the port: not ready
        control.info.remove("net/listeners/socks")
        m.controlReady(control)
        sched.drain()
        assertEquals(0, m.socksPort)
        circuit(m)
        assertFalse("a circuit without a port is not ready", m.isRunning)
        // the port arrives on the next read, a second later
        control.info["net/listeners/socks"] = "\"127.0.0.1:9150\""
        sched.advance(1_000)
        assertEquals(9150, m.socksPort)
        assertTrue(m.isRunning)
        assertEquals("up", m.state)
        assertEquals(100, m.progress)
        assertTrue(m.everUp)
        assertEquals("Tor up clears the open connection", 1, established)
        assertEquals(TorTransport.DIRECT, memory.remembered)
    }

    @Test
    fun withoutDirectoryInfoACircuitIsNotReady() {
        control.info["status/enough-dir-info"] = "0"
        val m = connected()
        circuit(m)
        assertFalse(m.isRunning)
        m.statusEvent("NOTICE ENOUGH_DIR_INFO")
        assertTrue(m.isRunning)
        m.statusEvent("NOTICE NOT_ENOUGH_DIR_INFO")
        assertFalse("lost directory info: new circuits wait", m.isRunning)
    }

    @Test
    fun onlyALoopbackPortIsTaken() {
        val m = machine()
        assertEquals(53211, m.loopbackPort("\"127.0.0.1:53211\""))
        assertEquals(53211, m.loopbackPort("\"127.0.0.1:53211\" \"127.0.0.1:9050\""))
        for (no in listOf("\"0.0.0.0:9050\"", "\"[::1]:9050\"", "\"192.168.1.5:9050\"", "\"127.0.0.1:0\"", "\"127.0.0.1:70000\"", "", "\"unix:/x\"")) {
            assertEquals(no, null, m.loopbackPort(no))
        }
    }

    @Test
    fun aListenerThatNeverAnswersIsAskedTwentyTimesThenLeft() {
        control.info["net/listeners/socks"] = ""
        // Snowflake's patience, so no set-up starts a read of its own inside the half minute
        connected(machine().also { it.forced = TorTransport.SNOWFLAKE })
        sched.advance(30_000)
        assertEquals(1 + TorMachine.SOCKS_READS, control.asked.count { it == "net/listeners/socks" })
    }

    @Test
    fun thePortIsForgottenBeforeASetUpAndAnAnswerAboutTheOldListenerIsNotUsed() {
        val m = connected()
        circuit(m)
        assertTrue(m.isRunning)
        val before = forgotten
        m.restart("the person asked") // ready: nothing to do
        assertTrue(m.isRunning)
        m.statusEvent("NOTICE CIRCUIT_NOT_ESTABLISHED REASON=CLOCK_JUMPED")
        assertFalse("a lost circuit closes the route at once", m.isRunning)
        m.restart("the person asked")
        assertEquals("forgotten before the set-up touched the listener", 0, m.socksPort)
        assertTrue(forgotten > before)
        sched.drain()
        assertEquals(listOf("DisableNetwork" to "1"), control.setConfs[control.setConfs.size - 3])
        assertEquals(listOf("UseBridges" to "0"), control.setConfs[control.setConfs.size - 2])
        assertEquals(listOf("DisableNetwork" to "0"), control.setConfs.last())
        assertEquals(9150, m.socksPort)
    }

    @Test
    fun theLadderIsDirectThreeTimesThenObfs4ThenSnowflakeThenTheFailure() {
        val m = connected()
        assertEquals(TorTransport.DIRECT, m.transport)
        // three goes of five seconds on direct: launch, then two set-ups
        sched.advance(5_000)
        sched.advance(5_000)
        assertEquals(TorTransport.DIRECT, m.transport)
        assertEquals(2, control.setConfs.count { it == listOf("UseBridges" to "0") })
        sched.advance(5_000)
        assertEquals(TorTransport.OBFS4, m.transport)
        val obfs4 = control.setConfs.last()
        assertEquals("UseBridges" to "1", obfs4[0])
        assertEquals("ClientTransportPlugin" to "obfs4 socks5 127.0.0.1:41001", obfs4[1])
        assertEquals(Bridges.OBFS4_LINES, obfs4.drop(2).map { it.second })
        assertTrue(obfs4.drop(2).all { it.first == "Bridge" })
        // obfs4's patience is a minute
        sched.advance(59_000)
        assertEquals(TorTransport.OBFS4, m.transport)
        sched.advance(1_000)
        assertEquals(TorTransport.SNOWFLAKE, m.transport)
        assertEquals("ClientTransportPlugin" to "snowflake socks5 127.0.0.1:41002", control.setConfs.last()[1])
        assertEquals(Bridges.SNOWFLAKE_LINES, control.setConfs.last().drop(2).map { it.second })
        // Snowflake's, ninety seconds; then nothing is left to try
        sched.advance(89_000)
        assertEquals("connecting or stuck, not failed", false, m.state == "failed")
        sched.advance(1_000)
        assertEquals("failed", m.state)
        assertEquals(listOf(TorTransport.OBFS4, TorTransport.SNOWFLAKE), transports.started)
    }

    @Test
    fun aFirstSetupGivesDirectTwentySeconds() {
        daemon.relayList = false
        val m = connected()
        assertTrue(m.firstSetup)
        sched.advance(19_000)
        assertEquals("no restart yet", 0, control.setConfs.size)
        sched.advance(1_000)
        assertTrue(control.setConfs.isNotEmpty())
    }

    @Test
    fun aBootstrapStillMovingIsGivenLonger() {
        val m = connected()
        sched.advance(4_000)
        m.statusEvent("NOTICE BOOTSTRAP PROGRESS=15 TAG=handshake_dir SUMMARY=\"Finishing handshake with directory server\"")
        assertEquals(15, m.progress)
        sched.advance(1_000)
        assertEquals("a step a moment ago: not restarted", 0, control.setConfs.size)
        // and a direct download past 15% is not restarted inside a minute of its last step
        sched.advance(20_000)
        assertTrue(control.setConfs.none { it == listOf("UseBridges" to "0") })
        assertEquals(TorTransport.DIRECT, m.transport)
    }

    @Test
    fun aBridgeThatGotThroughIsUsedFirstNextLaunchForADay() {
        val m = connected()
        sched.advance(15_000)
        assertEquals(TorTransport.OBFS4, m.transport)
        circuit(m)
        assertTrue(m.isRunning)
        assertEquals(TorTransport.OBFS4, memory.remembered)
        // the next launch starts on obfs4, its settings in the torrc
        val next = machine()
        next.start()
        assertEquals(TorTransport.OBFS4, next.transport)
        assertTrue(daemon.torrcs.last().contains("ClientTransportPlugin obfs4 socks5 127.0.0.1:41001\n"))
        assertTrue(daemon.torrcs.last().contains("Bridge " + Bridges.OBFS4_LINES[0] + "\n"))
        wall += TransportMemory.BRIDGE_MEMORY_MS
        assertEquals("a day on, direct again", TorTransport.DIRECT, memory.remembered)
    }

    @Test
    fun aBridgeReachedAfterABlackoutIsNotRemembered() {
        network = false
        val m = connected()
        network = true
        sched.advance(15_000)
        assertEquals(TorTransport.OBFS4, m.transport)
        circuit(m)
        assertEquals(TorTransport.DIRECT, memory.remembered)
    }

    @Test
    fun aForcedTransportIsUsedAndNotRemembered() {
        val m = machine()
        m.forced = TorTransport.SNOWFLAKE
        m.start()
        assertTrue(daemon.torrcs.single().contains("ClientTransportPlugin snowflake socks5"))
        m.controlReady(control)
        sched.drain()
        circuit(m)
        assertTrue(m.isRunning)
        assertEquals(TorTransport.DIRECT, memory.remembered)
    }

    @Test
    fun aTransportThatWillNotStartFallsBackToDirect() {
        memory.remembered = TorTransport.OBFS4
        transports.refuse = TorTransport.OBFS4
        val m = machine()
        m.start()
        assertEquals(TorTransport.DIRECT, m.transport)
        assertFalse(daemon.torrcs.single().contains("UseBridges"))
    }

    @Test
    fun putAwayTorLeavesTheNetworkAndComesBackWithAFreshSetUp() {
        val m = connected()
        circuit(m)
        var done = false
        m.backgrounded { done = true }
        assertFalse(m.isRunning)
        assertTrue(m.parked)
        assertEquals(0, m.socksPort)
        sched.drain()
        assertTrue(done)
        assertEquals(listOf("DisableNetwork" to "1"), control.setConfs.last())
        // a circuit event while away does not make it ready
        circuit(m)
        assertFalse(m.isRunning)
        val n = control.setConfs.size
        m.resumed(12)
        sched.drain()
        assertFalse(m.parked)
        assertEquals(listOf("DisableNetwork" to "1"), control.setConfs[n])
        assertEquals(listOf("DisableNetwork" to "0"), control.setConfs.last())
        circuit(m)
        assertTrue(m.isRunning)
    }

    /**
     * Put away while Tor is still starting, before its control link is up
     * (Back within the first second on API 28-30). iOS suspends the process so a
     * late bootstrap never runs; Android keeps it, so the park must hold until
     * the link arrives and take Tor off the network then.
     */
    @Test
    fun putAwayBeforeTheControlLinkTakesTorOffTheNetworkWhenTheLinkArrives() {
        val m = machine()
        m.start()
        var done = false
        m.backgrounded { done = true }
        assertTrue("done at once: there is no link to ask", done)
        assertTrue(m.parked)
        assertTrue("nothing sent yet: no link", control.setConfs.isEmpty())
        m.controlReady(control)
        sched.drain()
        assertEquals("off the network the moment the link came up", listOf(listOf("DisableNetwork" to "1")), control.setConfs)
        assertFalse("no set-up turned the network back on", control.setConfs.any { s -> s.contains("DisableNetwork" to "0") })
        circuit(m)
        assertFalse("a late circuit while parked is not ready", m.isRunning)
        sched.advance(120_000)
        assertFalse(m.isRunning)
        assertEquals("still only the park: no ladder step ran while away", 1, control.setConfs.size)
        // back: a fresh set-up, network on, ready on the next circuit
        m.resumed(30)
        sched.drain()
        assertFalse(m.parked)
        assertEquals(listOf("DisableNetwork" to "0"), control.setConfs.last())
        circuit(m)
        assertTrue(m.isRunning)
    }

    @Test
    fun aQuickReturnToACircuitThatNeverWentDownChangesNothing() {
        val m = connected()
        circuit(m)
        val n = control.setConfs.size
        m.resumed(3)
        sched.drain()
        assertEquals(n, control.setConfs.size)
        assertTrue(m.isRunning)
    }

    @Test
    fun withNoNetworkTorIsTakenOffItAndComesBackWhenThereIsOne() {
        val m = connected()
        circuit(m)
        network = false
        m.networkWent(false)
        sched.advance(1_000)
        assertTrue("a brief gap is not acted on", m.isRunning)
        sched.advance(500)
        assertFalse(m.isRunning)
        assertEquals(listOf("DisableNetwork" to "1"), control.setConfs.last())
        assertFalse("not stuck: off the network on purpose", m.stuck)
        sched.advance(120_000)
        assertFalse(m.stuck)
        network = true
        m.networkWent(true)
        sched.drain()
        assertEquals(listOf("DisableNetwork" to "0"), control.setConfs.last())
        circuit(m)
        assertTrue(m.isRunning)
    }

    @Test
    fun theLadderLearnsNothingWithoutANetwork() {
        network = false
        val m = connected()
        sched.advance(5_000)
        assertEquals(listOf("DisableNetwork" to "1"), control.setConfs.last())
        sched.advance(300_000)
        assertEquals("no bridge was tried in the dark", TorTransport.DIRECT, m.transport)
        assertTrue(transports.started.isEmpty())
    }

    @Test
    fun aStoppedTorIsStartedAgainByRetry() {
        val m = connected()
        daemon.ended = true
        m.daemonFinished("tor-android said STOPPING")
        assertEquals("stopped", m.state)
        m.restart("the person asked")
        assertEquals("launched again", 2, daemon.torrcs.size)
        assertEquals("connecting", m.state)
    }

    /**
     * A Tor whose thread ended is marked away when Foxy goes to the background,
     * with no DisableNetwork (there is no daemon to send it to); coming back
     * launches it again.
     */
    private fun stoppedThenPutAway(): TorMachine {
        val m = connected()
        daemon.ended = true
        m.daemonFinished("tor-android said STOPPING")
        assertEquals("stopped", m.state)
        val sent = control.setConfs.size
        var done = 0
        m.backgrounded { done++ }
        assertEquals("backgrounded answers at once", 1, done)
        assertTrue("a stopped Tor is marked away", m.parked)
        assertEquals("nothing sent to a daemon that is gone", sent, control.setConfs.size)
        return m
    }

    /** The new Tor's control link comes up while Foxy is away: off the network, nothing set up, not ready. */
    private fun assertHeldOffTheNetwork(m: TorMachine, link: FakeControl) {
        m.controlReady(link)
        sched.drain()
        assertEquals("off the network the moment the link came up", listOf(listOf("DisableNetwork" to "1")), link.setConfs)
        circuit(m)
        assertFalse("a circuit while away is not ready", m.isRunning)
        sched.advance(120_000)
        assertFalse(m.isRunning)
        assertTrue("still away", m.parked)
        assertEquals("no set-up ran while away", 1, link.setConfs.size)
        assertFalse("nothing turned the network back on", link.setConfs.any { it.contains("DisableNetwork" to "0") })
    }

    @Test
    fun aStoppedTorPutAwayIsLaunchedAgainWhenFoxyComesBack() {
        val m = stoppedThenPutAway()
        m.resumed(0)
        assertEquals("launched again on resume", 2, daemon.torrcs.size)
        assertFalse("launched on the network", daemon.torrcs.last().contains("DisableNetwork 1"))
        assertFalse("the new Tor is not parked", m.parked)
        assertEquals("connecting", m.state)
        val link = FakeControl(sched)
        m.controlReady(link)
        sched.drain()
        assertFalse("the new Tor is not taken off the network", link.setConfs.any { it.contains("DisableNetwork" to "1") })
        circuit(m)
        assertTrue(m.isRunning)
    }

    /** The next activity (MainActivity: `if (Tor.parked) Tor.resumed(0) else Tor.start()`). */
    @Test
    fun aStoppedTorPutAwayIsLaunchedAgainByTheNextActivity() {
        val m = stoppedThenPutAway()
        if (m.parked) m.resumed(0) else m.start()
        assertEquals("launched again", 2, daemon.torrcs.size)
        assertFalse("launched on the network", daemon.torrcs.last().contains("DisableNetwork 1"))
        assertFalse("the new Tor is not parked", m.parked)
        assertEquals("connecting", m.state)
    }

    /** The page's automatic torRetry while away (a network change pushes `_privacy`): launched off the network. */
    @Test
    fun aRetryWhileAwayLaunchesAStoppedTorOffTheNetwork() {
        val m = stoppedThenPutAway()
        m.restart("network")
        assertEquals("launched again", 2, daemon.torrcs.size)
        assertTrue("launched off the network", daemon.torrcs.last().contains("\nDisableNetwork 1\n"))
        assertTrue("the new Tor is still away", m.parked)
        assertHeldOffTheNetwork(m, FakeControl(sched))
        // back: a fresh set-up turns the network on
        m.resumed(40)
        sched.drain()
        assertFalse(m.parked)
        circuit(m)
        assertTrue(m.isRunning)
    }

    @Test
    fun aStartWhileAwayLaunchesAStoppedTorOffTheNetwork() {
        val m = stoppedThenPutAway()
        m.start()
        assertEquals("launched again on start", 2, daemon.torrcs.size)
        assertTrue("launched off the network", daemon.torrcs.last().contains("\nDisableNetwork 1\n"))
        assertTrue("the new Tor is still away", m.parked)
        assertHeldOffTheNetwork(m, FakeControl(sched))
    }

    /** A Tor that stops after the park, then a RETRY while still away: the new Tor stays off the network. */
    @Test
    fun aRetryWhileAwayLaunchesATorThatStoppedWhileParkedOffTheNetwork() {
        val m = connected()
        circuit(m)
        m.backgrounded { }
        sched.drain()
        assertTrue(m.parked)
        daemon.ended = true
        m.daemonFinished("tor-android said STOPPING")
        m.restart("network")
        assertEquals("launched again", 2, daemon.torrcs.size)
        assertTrue("launched off the network", daemon.torrcs.last().contains("\nDisableNetwork 1\n"))
        assertTrue("the new Tor is still away", m.parked)
        assertHeldOffTheNetwork(m, FakeControl(sched))
    }

    @Test
    fun aTorThatStopsWhileParkedIsLaunchedAgainWhenFoxyComesBack() {
        val m = connected()
        circuit(m)
        m.backgrounded { }
        sched.drain()
        assertTrue(m.parked)
        daemon.ended = true
        m.daemonFinished("tor-android said STOPPING")
        m.resumed(0)
        assertEquals("launched again on resume", 2, daemon.torrcs.size)
        assertFalse("launched on the network", daemon.torrcs.last().contains("DisableNetwork 1"))
        assertFalse("the new Tor is not parked", m.parked)
        assertEquals("connecting", m.state)
    }

    @Test
    fun noControlLinkIsStuckAfterTenSecondsAndAFailureAfterThat() {
        val m = machine()
        m.start()
        sched.advance(9_000)
        assertEquals("connecting", m.state)
        sched.advance(1_000)
        assertEquals("stuck", m.state)
        m.noControl("no control link 30s after Tor started")
        assertEquals("failed", m.state)
    }

    @Test
    fun aControlLinkThatDoesNotAnswerASetUpIsAFailure() {
        val m = connected()
        control.answerSetConf = false
        m.restart("the person asked")
        sched.advance(TorMachine.CONTROL_WAIT_MS)
        assertEquals("failed", m.state)
    }

    @Test
    fun bootstrapEventsMoveTheProgressOnlyUpAndAWarningIsNotAStep() {
        val m = connected()
        m.statusEvent("NOTICE BOOTSTRAP PROGRESS=50 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\"")
        assertEquals(50, m.progress)
        m.statusEvent("NOTICE BOOTSTRAP PROGRESS=45 TAG=x SUMMARY=\"x\"")
        assertEquals(50, m.progress)
        m.statusEvent("WARN BOOTSTRAP PROGRESS=75 TAG=x SUMMARY=\"Problem\" WARNING=\"Connection refused\" REASON=CONNECTREFUSED COUNT=3 RECOMMENDATION=ignore")
        assertEquals(50, m.progress)
        assertEquals(listOf("NOTICE", "BOOTSTRAP", "SUMMARY=\"Loading relay descriptors\""), m.splitEvent("NOTICE BOOTSTRAP SUMMARY=\"Loading relay descriptors\""))
    }

    @Test
    fun theSeedsMarkerTurnsUsedAt40AndGoesWithTheFirstCircuit() {
        val marker = File(dir, TorSeed.MARKER_NAME)
        marker.writeBytes(ByteArray(0))
        val m = connected()
        m.statusEvent("NOTICE BOOTSTRAP PROGRESS=39 TAG=x SUMMARY=\"x\"")
        assertEquals(0L, marker.length())
        m.statusEvent("NOTICE BOOTSTRAP PROGRESS=40 TAG=x SUMMARY=\"x\"")
        assertEquals("used", marker.readText())
        circuit(m)
        assertFalse(marker.exists())
    }

    @Test
    fun releaseBuildsLogErrorsOnly() {
        machine(release = true).start()
        assertTrue(daemon.torrcs.single().contains("Log err stderr\n"))
    }

    // MARK: Set-ups that overlap (ENG-652)

    private fun networkOffs() = control.setConfs.count { s -> s.any { it == ("DisableNetwork" to "1") } }

    /**
     * The instrumented suite's stack: a return, the next activity's start
     * ("retrying") and a restart inside about a second. Each set-up switches the
     * network off under the circuits the one before was building, and Tor
     * blamed their guards for it; a retry or a restart this soon joins the
     * set-up that is running instead.
     */
    @Test
    fun aRetryAndARestartJoinASetUpThatJustBegan() {
        val m = connected()
        circuit(m)
        m.backgrounded { }
        sched.drain()
        m.resumed(0)
        sched.drain()
        val offs = networkOffs()
        val forgottenBefore = forgotten
        val port = m.socksPort
        sched.advance(90)
        m.start()
        sched.drain()
        sched.advance(1_060)
        m.restart("the test asked")
        sched.drain()
        assertEquals("neither switched the network off again", offs, networkOffs())
        assertEquals("nor forgot the port the return's set-up read", forgottenBefore, forgotten)
        assertEquals(port, m.socksPort)
        circuit(m)
        assertTrue("the set-up they joined comes up", m.isRunning)
    }

    @Test
    fun aRetryOrRestartAfterTheJoinWindowIsANewSetUp() {
        val m = connected()
        m.start() // a set-up, not ready
        sched.drain()
        val offs = networkOffs()
        sched.advance(TorMachine.SET_UP_JOIN_MS)
        m.start()
        sched.drain()
        assertEquals("a set-up 4 s old that has not come up: RETRY sets up again", offs + 1, networkOffs())
        sched.advance(TorMachine.SET_UP_JOIN_MS)
        m.restart("the person asked")
        sched.drain()
        assertEquals(offs + 2, networkOffs())
    }

    @Test
    fun aRestartAfterAFailedSetUpIsANewSetUpHoweverSoon() {
        val m = connected()
        control.answerSetConf = false
        m.start()
        sched.advance(TorMachine.CONTROL_WAIT_MS)
        assertEquals("failed", m.state)
        control.answerSetConf = true
        val offs = networkOffs()
        m.restart("the person asked")
        sched.drain()
        assertEquals(offs + 1, networkOffs())
    }

    @Test
    fun aReturnFromBeingPutAwayIsAlwaysASetUp() {
        val m = connected()
        m.start()
        sched.drain()
        m.backgrounded { }
        sched.drain()
        val offs = networkOffs()
        m.resumed(0)
        sched.drain()
        assertEquals("parked Tor is off the network: the return turns it on, however soon", "0", control.last("DisableNetwork"))
        assertEquals(offs + 1, networkOffs())
    }

    /**
     * Nothing a superseded set-up does reaches Tor: its settings answered after
     * Foxy was put away must not switch the network back on (S21).
     */
    @Test
    fun aSupersededSetUpNeverSwitchesTheNetworkBackOn() {
        val link = HeldControl(sched)
        val m = machine()
        m.start()
        m.controlReady(link)
        sched.drain()
        m.resumed(60) // a set-up: DisableNetwork 1 waits for Tor's reply
        link.release() // its network is off: the settings go out
        assertEquals(listOf("1"), link.network())
        m.backgrounded { } // put away while the settings are unanswered
        link.releaseAll()
        assertEquals("the network went off and stayed off", listOf("1", "1"), link.network())
        assertTrue(m.parked)
        assertFalse(m.isRunning)
    }

    @Test
    fun aSupersededTransportSwitchReadsNoPortAndAsksForNoCircuit() {
        val link = HeldControl(sched)
        val m = machine()
        m.start()
        m.controlReady(link)
        link.releaseAll()
        sched.drain()
        // direct out of time three times, then obfs4: its settings wait for Tor
        sched.advance(TorTransport.DIRECT.patienceMs)
        link.releaseAll()
        sched.advance(TorTransport.DIRECT.patienceMs)
        link.releaseAll()
        sched.advance(TorTransport.DIRECT.patienceMs)
        assertEquals(TorTransport.OBFS4, m.transport)
        assertTrue("obfs4's settings are out, unanswered", link.waiting > 0)
        m.backgrounded { }
        val asked = link.circuitsAsked
        link.releaseAll()
        sched.advance(10_000)
        assertEquals("no circuit asked for while away", asked, link.circuitsAsked)
        assertEquals(0, m.socksPort)
        assertEquals("1", link.network().last())
    }

    /**
     * Tor writes an event before its reply to a later command: a
     * CIRCUIT_ESTABLISHED heard after a set-up began, before its
     * `DisableNetwork 1` was answered, is about a circuit that switch closed.
     */
    @Test
    fun aCircuitFromBeforeASetUpSwitchedTheNetworkOffIsNotReady() {
        val m = connected()
        sched.advance(TorMachine.SET_UP_JOIN_MS)
        m.start() // RETRY: DisableNetwork 1 is out
        m.statusEvent("NOTICE CIRCUIT_ESTABLISHED") // the old network's circuit, heard before the reply
        sched.drain() // the reply, the settings, the network on, the port
        assertTrue(m.socksPort != 0)
        assertFalse("a circuit the set-up closed is not ready", m.isRunning)
        assertTrue("a circuit is asked for", control.circuitsAsked > 0)
        circuit(m)
        assertTrue("the set-up's own circuit is", m.isRunning)
    }

    @Test
    fun aCircuitHeardWhileParkedDoesNotMakeTheReturnReady() {
        val m = connected()
        circuit(m)
        m.backgrounded { }
        m.statusEvent("NOTICE CIRCUIT_ESTABLISHED")
        sched.drain()
        m.resumed(60)
        sched.drain()
        assertFalse("the return waits for a circuit of its own", m.isRunning)
        circuit(m)
        assertTrue(m.isRunning)
    }
}
