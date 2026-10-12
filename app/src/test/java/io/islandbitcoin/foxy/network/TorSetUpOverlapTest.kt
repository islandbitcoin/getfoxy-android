package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.tor.FakeDaemon
import io.islandbitcoin.foxy.tor.FakeScheduler
import io.islandbitcoin.foxy.tor.FakeTransports
import io.islandbitcoin.foxy.tor.TorControl
import io.islandbitcoin.foxy.tor.TorMachine
import io.islandbitcoin.foxy.tor.TransportMemory
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ENG-652 end to end on the JVM: the machine, Route, OkHttp and the SOCKS
 * factory against a Tor that behaves as the Pixel's did (logcat at `Log info`,
 * 61bc4b7):
 *
 * - every `DisableNetwork 0` opens a new SOCKS listener on a new port, and
 *   `DisableNetwork 1` closes it;
 * - `DisableNetwork 1` while a circuit is being built kills it ("died before
 *   the first hop with no connection") and Tor records a failure against its
 *   guard ("Recorded failure for guard …"); with all three primary guards
 *   failed, Tor still reports CIRCUIT_ESTABLISHED, but holds every new stream
 *   "waiting for circuit" ("No primary guards available … Will try other
 *   guards before using this circuit"), so the SOCKS handshake never ends.
 *
 * The suite's stack: the previous test's activity finished mid-bootstrap (a
 * park), the @After's return, the next activity's start ("retrying") and the
 * test's restart, inside about 1.2 s. The request after it must leave on the
 * last listener's port and be answered.
 */
class TorSetUpOverlapTest {
    /** Tor as seen through its control link: listeners, the network switch and its primary guards. */
    private inner class GuardTor : TorControl {
        val setConfs = mutableListOf<List<Pair<String, String>>>()
        var networkOn = true
        var building = true
        var guardsFailed = 0
        var listener: FakeSocks? = FakeSocks(mint.port)
        val closed = mutableListOf<FakeSocks>()

        override fun setConf(settings: List<Pair<String, String>>, timeoutMs: Long, done: (Boolean, String) -> Unit) {
            setConfs.add(settings)
            when (settings.firstOrNull { it.first == "DisableNetwork" }?.second) {
                "1" -> {
                    if (networkOn && building) guardsFailed++
                    networkOn = false
                    building = false
                    listener?.let {
                        it.close()
                        closed.add(it)
                    }
                    listener = null
                }
                "0" -> if (!networkOn) {
                    networkOn = true
                    building = true
                    listener = FakeSocks(mint.port).also { it.holdConnects = guardsFailed >= PRIMARY_GUARDS }
                }
            }
            sched.post { done(true, "OK") }
        }

        override fun getInfo(key: String, timeoutMs: Long, done: (String?) -> Unit) {
            val v = when (key) {
                "net/listeners/socks" -> listener?.let { "\"127.0.0.1:${it.port}\"" } ?: ""
                "status/enough-dir-info" -> "1"
                "traffic/read" -> "0"
                else -> null
            }
            sched.post { done(v) }
        }

        override fun extendCircuit(timeoutMs: Long, done: (Boolean) -> Unit) {
            sched.post { done(true) }
        }

        /** A circuit completes: Tor says so, whatever state its guards are in. */
        fun circuitBuilt(m: TorMachine) {
            building = false
            m.statusEvent("NOTICE CIRCUIT_ESTABLISHED")
            sched.drain()
        }

        fun closeAll() {
            listener?.close()
        }
    }

    private companion object {
        /** The primary guards Tor tried on the Pixel ("Neuchatel", "Fribourg", "prsv"). */
        const val PRIMARY_GUARDS = 3
    }

    private val mint = MockWebServer()
    private val sched = FakeScheduler()
    private val dir = TestSupport.tempDir("tor-overlap")
    private val before = Route.tor
    private lateinit var tor: GuardTor
    private var call: RouteCall? = null

    @Before
    fun up() {
        mint.start()
        mint.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"name\":\"testnut\"}")
        }
        tor = GuardTor()
        RouteHttp.forgetTorConnections()
        Route.openDoor()
    }

    @After
    fun down() {
        call?.cancel()
        Route.tor = before
        RouteHttp.forgetTorConnections()
        tor.closeAll()
        mint.shutdown()
    }

    private fun machine(): TorMachine {
        val m = TorMachine(
            sched, FakeDaemon(dir), FakeTransports(), TransportMemory(File(dir, "foxy-tor-transport")), release = false,
            hasNetwork = { true }, onPortForgotten = { RouteHttp.forgetTorConnections() },
        )
        Route.tor = object : TorRoute {
            override val isRunning get() = m.isRunning
            override val socksPort get() = m.socksPort
        }
        return m
    }

    @Test
    fun aReturnARetryAndARestartStackedLeaveTheNextRequestOnTheLastPortAndItIsAnswered() {
        val m = machine()
        m.start()
        m.controlReady(tor)
        sched.drain()
        tor.circuitBuilt(m)
        assertTrue(m.isRunning)

        // the previous test: its activity finished while the @After's set-up was still building
        m.backgrounded { }
        sched.drain()
        m.resumed(0)
        sched.drain()
        m.backgrounded { }
        sched.drain()
        assertEquals("a put-away mid-build costs a guard: Tor's, not Foxy's to avoid (S21)", 1, tor.guardsFailed)

        // 20:25:24.592 the @After's return, .681 the next activity's start, 25.743 the test's restart
        m.resumed(0)
        sched.drain()
        sched.advance(89)
        m.start()
        sched.drain()
        sched.advance(1_062)
        m.restart("the test asked")
        sched.drain()
        tor.circuitBuilt(m)
        assertTrue("Tor up, as the Pixel's machine said it was", m.isRunning)

        val last = tor.listener
        assertNotNull(last)
        assertEquals("the machine holds the last listener's port", last!!.port, m.socksPort)

        // the test's first request: over Tor to the mint, answered
        val got = AtomicReference<RouteAnswer>()
        val done = CountDownLatch(1)
        val url = Route.parse("http://testnuttestnuttestnut.onion:${mint.port}/v1/info")!!
        call = Route.start(RouteRequest(url), "0123456789abcdef") {
            got.set(it)
            done.countDown()
        }
        assertNotNull("Route took the request", call)
        assertTrue("answered within 10 s, not held until the 30 s handshake timeout", done.await(10, TimeUnit.SECONDS))
        val answer = got.get()
        assertTrue("answered by the mint: $answer", answer is RouteAnswer.Done && answer.status == 200)
        assertEquals("on the last listener", 1, last.handshakes.size)
        assertTrue("and on no listener Tor had closed", tor.closed.all { it.handshakes.isEmpty() })
        assertEquals("only the put-away's kill: the retry and the restart joined the return's set-up", 1, tor.guardsFailed)
    }
}
