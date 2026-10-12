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

    @Before
    fun up() {
        mint.start()
        socks = FakeSocks(mint.port)
        Route.tor = object : TorRoute {
            override val isRunning = true
            override val socksPort get() = socks.port
        }
        RouteHttp.forgetTorConnections()
        val prompts = PromptQueue { _, _ -> null }
        val net = NetEnvironment(
            torRunning = { true }, bridged = { false }, snapshot = { Snapshot.noTor() }, restartTor = {},
            unprotected = { false }, setUnprotected = {}, available = { true },
            approvals = HostApprovals(TextStore.Memory(Json.write(listOf(onion))), TextStore.Memory(), prompts),
            prompts = prompts,
            send = { r, c, d -> Route.start(r, c, d)?.let { call -> { call.cancel() } } },
            after = { ms, r -> timers.schedule(r, ms, TimeUnit.MILLISECONDS) },
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
    private fun answer(id: String): Pair<String?, String?> {
        val end = System.nanoTime() + 20_000_000_000
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
    fun aListenerThatDropsTwiceSaysTheConnectionWasRebuiltAndNothingReachedTheMint() {
        socks.dropNext = 2
        melt("m3")
        assertEquals(null to "Foxy's private connection was rebuilt mid-request. Try again.", answer("m3"))
        assertEquals(0, mint.requestCount)
        assertEquals("tried once more, and only once", 2, socks.connections.get())
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
}
