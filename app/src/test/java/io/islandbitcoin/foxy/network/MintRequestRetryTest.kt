package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.bridge.NetEnvironment
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.keychain.TextStore
import io.islandbitcoin.foxy.tor.Snapshot
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The money path, end to end through the bridge, Route, OkHttp and the SOCKS
 * factory (architect's decision: a double send is a blocking finding). A POST
 * is sent again only when Tor's SOCKS handshake failed, so no byte of it left;
 * once, after 0.4 s. A POST that reached the mint is never sent again, however
 * its answer was lost. And it is money in flight until it is answered.
 */
class MintRequestRetryTest {
    private val mint = MockWebServer()
    private lateinit var socks: FakeSocks
    private val before = Route.tor
    private val host = FakeHost()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private lateinit var bridge: FoxyBridge
    private val onion = "mintmintmintmint.onion"
    /** Tor's SOCKS port as Route reads it at each dial; a test moves it to model a rebuilt Tor. */
    @Volatile private var torPort: () -> Int = { socks.port }

    @Before
    fun up() {
        mint.start()
        socks = FakeSocks(mint.port)
        Route.tor = object : TorRoute {
            override val isRunning = true
            override val socksPort get() = torPort()
        }
        RouteHttp.forgetTorConnections()
        build()
    }

    /** The bridge, with `mintRequestMs` as a mint request's whole time. */
    private fun build(mintRequestMs: Long = 60_000) {
        val prompts = PromptQueue { _, _ -> null }
        val net = NetEnvironment(
            torRunning = { true }, bridged = { false }, snapshot = { Snapshot.noTor() }, restartTor = {},
            unprotected = { false }, setUnprotected = {}, available = { true },
            approvals = HostApprovals(TextStore.Memory(Json.write(listOf(onion))), TextStore.Memory(), prompts),
            prompts = prompts,
            send = { r, c, d -> Route.start(r, c, d)?.let { call -> { call.cancel() } } },
            after = { ms, r -> timers.schedule(r, ms, TimeUnit.MILLISECONDS) },
            mintRequestMs = mintRequestMs,
        )
        val dir = TestSupport.tempDir("retry")
        val env = SeedEnvironment(
            savedSeed = { SeedVault.SecretsSeed.Absent }, seedPresent = { false }, write = { null },
            counters = CounterStore(dir), candidates = SeedCandidates(), wordlist = { null }, generate = { "" },
            migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
        )
        bridge = FoxyBridge(host, { env }, Executor { it.run() }, false, net)
    }

    @After
    fun down() {
        Route.tor = before
        RouteHttp.forgetTorConnections()
        socks.close()
        mint.shutdown()
        timers.shutdownNow()
    }

    private fun melt(id: String) {
        val url = "http://$onion:${mint.port}/v1/melt/bolt11"
        bridge.onMessage(
            """{"action":"mintRequest","id":"$id","url":"$url","method":"POST","headers":{"content-type":"application/json"},"circuit":"0123456789abcdef0123456789abcdef","body":"{\"quote\":\"q1\"}"}""",
            true, FoxyBridge.ORIGIN,
        )
    }

    /** The (text, error) the page was answered for `id`. */
    private fun answer(id: String, withinMs: Long = 20_000): Pair<String?, String?> {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(withinMs)
        while (System.nanoTime() < end) {
            host.evaluated.firstOrNull { it.contains("_scanResult(\"$id\"") }?.let { js ->
                @Suppress("UNCHECKED_CAST")
                val args = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
                return args[1] to args[2]
            }
            Thread.sleep(20)
        }
        throw AssertionError("no answer for $id")
    }

    @Test
    fun aMeltWhoseHandshakeFailedIsSentOnceMoreAndReachesTheMintOnce() {
        socks.refuseNext = 1
        mint.enqueue(MockResponse().setBody("""{"paid":true}"""))
        melt("m1")
        assertEquals("200\n{\"paid\":true}" to null, answer("m1"))
        assertEquals("the melt reached the mint exactly once", 1, mint.requestCount)
        assertEquals("one refused handshake, one granted", 2, socks.handshakes.size)
        assertEquals("answered: no longer money in flight", 0, bridge.moneyInFlight)
    }

    @Test
    fun aMeltThatReachedTheMintIsNeverSentAgain() {
        mint.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        mint.enqueue(MockResponse().setBody("must never be asked"))
        melt("m2")
        val (text, error) = answer("m2")
        assertEquals(null, text)
        assertTrue(error!!.isNotEmpty())
        Thread.sleep(800)
        assertEquals("the melt reached the mint once, and was not sent again", 1, mint.requestCount)
        assertEquals(0, bridge.moneyInFlight)
    }

    @Test
    fun aListenerThatDropsTwiceOnTheSamePortIsNotConnectedToTorAndNothingReachedTheMint() {
        // the same port both times: Tor's listener did not move, so it is not "rebuilt" (ENG-648 plan)
        socks.dropNext = 2
        melt("m3")
        assertEquals(null to Route.REFUSAL, answer("m3"))
        assertEquals(0, mint.requestCount)
        assertEquals("tried once more, and only once", 2, socks.connections.get())
    }

    @Test
    fun aListenerThatMovedBetweenTheTriesSaysTheConnectionWasRebuiltAndNothingReachedTheMint() {
        FakeSocks(mint.port).use { moved ->
            // the first dial finds the old listener, which drops; by the retry Tor listens elsewhere, which drops too
            torPort = { if (socks.connections.get() == 0) socks.port else moved.port }
            socks.dropNext = 1
            moved.dropNext = 1
            melt("m6")
            assertEquals(null to RouteHttp.REBUILT, answer("m6"))
            assertEquals(0, mint.requestCount)
            assertEquals("the first try on the old port", 1, socks.connections.get())
            assertEquals("the retry on the new port, and only once", 1, moved.connections.get())
        }
    }

    @Test
    fun torRefusingTheStreamTwiceSaysSoAndNothingReachedTheMint() {
        socks.refuseNext = 2
        melt("m5")
        assertEquals(null to "$onion could not be asked over Tor: host unreachable", answer("m5"))
        assertEquals(0, mint.requestCount)
        assertEquals("tried once more, and only once", 2, socks.handshakes.size)
    }

    @Test
    fun aMeltIsMoneyInFlightUntilItIsAnswered() {
        mint.enqueue(MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS))
        melt("m4")
        Thread.sleep(300)
        assertEquals("waited for by putting Foxy away", 1, bridge.moneyInFlight)
        answer("m4")
        assertEquals(0, bridge.moneyInFlight)
    }

    /*
     * ENG-655: right after "circuit established" Tor took longer than 30 s to
     * answer the first CONNECT on a new label (a new circuit), and the
     * handshake gave up at 30 s, then its retry at 30 s more, for two minutes
     * on the Pixel. The handshake now waits as iOS's request does, within the
     * request's own time; the retry shares that time.
     */

    @Test
    fun aStreamTorTakesLongerThanThirtySecondsToOpenIsWaitedForAndReachesTheMintOnce() {
        // longer than the old 30 s handshake timeout, within the request's 60 s
        socks.grantAfterMs = 31_000
        mint.enqueue(MockResponse().setBody("""{"paid":true}"""))
        val started = System.nanoTime()
        melt("s1")
        assertEquals("200\n{\"paid\":true}" to null, answer("s1", withinMs = 50_000))
        assertTrue("answered after Tor granted the stream", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 31_000)
        assertEquals("one handshake, waited for: no timeout and no retry", 1, socks.handshakes.size)
        assertEquals("the melt reached the mint exactly once", 1, mint.requestCount)
        assertEquals(0, bridge.moneyInFlight)
    }

    @Test
    fun aHandshakeThatWaitedOutTheRequestsTimeIsAnsweredAndNotAskedAgain() {
        build(mintRequestMs = 8_000)
        socks.holdConnects = true
        val started = System.nanoTime()
        melt("s2")
        val (text, error) = answer("s2", withinMs = 15_000)
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(null, text)
        // Tor is connected and still opening the stream: a timeout, as iOS says, not "not connected to Tor"
        assertEquals(RouteHttp.TIMED_OUT, error)
        assertTrue("answered within the request's time, not past it with a retry: $took ms", took < 8_000 + 2_000)
        Thread.sleep(800)
        assertEquals("not asked again with no time left", 1, socks.handshakes.size)
        assertEquals(0, mint.requestCount)
        assertEquals(0, bridge.moneyInFlight)
    }

    @Test
    fun aHandshakeThatFailedEarlyIsStillAskedAgainWithTheTimeLeft() {
        build(mintRequestMs = 8_000)
        socks.dropNext = 1
        mint.enqueue(MockResponse().setBody("""{"paid":true}"""))
        melt("s3")
        assertEquals("200\n{\"paid\":true}" to null, answer("s3", withinMs = 10_000))
        assertEquals("dropped once, then granted", 2, socks.connections.get())
        assertEquals(1, mint.requestCount)
    }

    @Test
    fun aRetryHasOnlyTheTimeTheFirstTryLeftAndIsAnsweredWithinTheRequestsTime() {
        // the first handshake is dropped after 3 s, the retry is held: with its own 10 s again it would
        // still be in flight at about 13.4 s, past the request's 10 s, as a melt past the page's 65 s wait
        build(mintRequestMs = 10_000)
        socks.dropNext = 1
        socks.dropAfterMs = 3_000
        socks.holdConnects = true // reached only by the retry: the dropped connection closes before its CONNECT
        val started = System.nanoTime()
        melt("s4")
        val (text, error) = answer("s4", withinMs = 20_000)
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(null, text)
        assertEquals(RouteHttp.TIMED_OUT, error)
        assertTrue("the retry was sent after the drop: $took ms", took >= 3_000)
        assertTrue("answered within the request's 10 s, not the retry's own 10 s again: $took ms", took < 10_000 + 1_500)
        assertEquals("dropped once, then asked again and held", 2, socks.connections.get())
        assertEquals("only the retry reached its CONNECT", 1, socks.handshakes.size)
        assertEquals(0, mint.requestCount)
        assertEquals(0, bridge.moneyInFlight)
    }

    @Test
    fun aHandshakeThatRanOutOfTimeIsATimeoutAndOneTorClosedIsNot() {
        val readTimeout = Socks5Failure("the SOCKS handshake broke", 9050, java.net.SocketTimeoutException("Read timed out"))
        val callTimeout = java.io.InterruptedIOException("timeout").apply { initCause(Socks5Failure("Tor closed the SOCKS connection", 9050)) }
        assertEquals(RouteHttp.TIMED_OUT, RouteHttp.failureWords(readTimeout, beforeRequest = true))
        assertEquals(RouteHttp.TIMED_OUT, RouteHttp.failureWords(callTimeout, beforeRequest = true, portMoved = true))
        assertEquals(Route.REFUSAL, RouteHttp.failureWords(Socks5Failure("Tor closed the SOCKS connection", 9050), beforeRequest = true))
    }
}
