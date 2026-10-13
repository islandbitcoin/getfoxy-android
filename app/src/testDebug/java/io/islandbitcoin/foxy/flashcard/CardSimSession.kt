package io.islandbitcoin.foxy.flashcard

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor

/**
 * What the card simulator's end-to-end tests share: the bridge as the page posts
 * to it, CardGate, the debug build's SimCardLink, and a card applet running in
 * jCardSim behind its CardServer on a loopback port, the line protocol
 * getfoxy/card and lnflash/cashu-javacard both keep in tools/cardsim/run.sh.
 *
 * Run by tools/cardsim-test.sh, which builds the simulator at one pin, starts
 * it, and passes its port as `-Pfoxy.cardsimPort`; skipped when no port is
 * given (a plain `testDebugUnitTest`). [CardSimEndToEndTest] is Foxy 1.x's
 * applet (tools/cardsim.PIN), [CardSim2EndToEndTest] card protocol 2.0's
 * (tools/cardsim-2.PIN, ENG-682). The commands are the page's, as
 * 08a-flashcard.js builds them (`cardCommand`).
 */
abstract class CardSimSession {
    protected val port: Int? = System.getProperty("foxy.cardsimPort")?.toIntOrNull()
    protected val main = PumpedMain()
    protected val host = FakeHost()
    private val dir = TestSupport.tempDir("cardsim")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Found(NUT13.seed(TestSupport.ABANDON_ABOUT)) },
        seedPresent = { true },
        write = { null },
        counters = CounterStore(dir),
        candidates = SeedCandidates(),
        wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE },
        migrationWindowOpen = { false },
        closeMigrationWindow = {},
        flags = Flags(dir),
    )
    protected val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = true)
    private var ids = 0

    protected val mint = "https://mint.example.com/Bitcoin"
    protected val keyset = "0059534ce0bfa19a"
    /** secp256k1's generator, compressed: a point, for a piece's C, a refund key and a blinded output. */
    protected val g = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    @Before
    fun aCardSimulatorIsRunning() {
        assumeTrue("no card simulator: run tools/cardsim-test.sh", port != null)
        host.cardLinks = { SimCardLink(main, listOf(port!!)) }
        // a card out of its packet, on the reader
        assertEquals("ok", ctl("new"))
    }

    /** One line to the simulator's control (CardServer `ctl`), between sessions: it serves one connection at a time. */
    protected fun ctl(word: String): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port!!), 2_000)
            s.soTimeout = 20_000
            s.getOutputStream().write("ctl $word\n".toByteArray(StandardCharsets.US_ASCII))
            return BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine().trim()
        }
    }

    protected fun post(action: String, extra: Map<String, Any?> = emptyMap()): String {
        val id = "e" + ids++
        bridge.onMessage(Json.write(mapOf("action" to action, "id" to id) + extra), true, FoxyBridge.ORIGIN)
        return id
    }

    protected fun reply(id: String): Pair<String?, String?>? = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
        Triple(list[0], list[1], list[2])
    }.firstOrNull { it.first == id }?.let { it.second to it.third }

    /** Ask, and wait for the answer on the main thread. */
    protected fun ask(action: String, extra: Map<String, Any?> = emptyMap()): Pair<String?, String?> {
        val id = post(action, extra)
        main.pump("$action answered") { reply(id) != null }
        return reply(id)!!
    }

    protected fun pushes(): List<String> = host.evaluated.filter { it.contains("._card(") }.map { js ->
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(js.substringAfter("._card(").removeSuffix(")")) as Map<String, Any?>
        "${o["stage"]}: ${o["text"]}"
    }

    /** A command to the card; its answer's data, after the 9000 it must end with. */
    protected fun card(apdu: String, what: String): String {
        val (text, error) = ask("cardSend", mapOf("apdu" to apdu))
        assertEquals("$what: no error", null, error)
        assertTrue("$what answered $text", text!!.endsWith("9000"))
        return text.dropLast(4)
    }

    protected fun sw(apdu: String): String {
        val (text, error) = ask("cardSend", mapOf("apdu" to apdu))
        assertEquals(null, error)
        return text!!.takeLast(4)
    }

    /** As 08a-flashcard.js `cardCommand`: class B0, the instruction, P1, P2, the data with its length, the length expected back. */
    protected fun command(ins: String, p1: Int, data: String, le: Int?, p2: Int = 0): String =
        "b0" + ins + "%02x".format(p1) + "%02x".format(p2) + (if (data.isEmpty()) "" else "%02x".format(data.length / 2) + data) +
            (if (le == null) "" else "%02x".format(le))

    protected fun u32(n: Long) = "%08x".format(n)

    /** A piece as the card stores it: keyset (8), amount (4), nonce (32), C (33), date (4). */
    protected fun piece(amount: Long, seed: Int): String =
        keyset + u32(amount) + (0 until 32).joinToString("") { "%02x".format((seed + it) and 0xff) } + g + u32(0)

    /** A card that is on no reader: the sheet waits, and the page takes it down. */
    @Test
    fun aSheetWithNoCardIsTakenDownByThePage() {
        assertEquals("ok", ctl("off"))
        val begun = post("cardBegin", mapOf("text" to "Tap behind the phone."))
        main.settle(500)
        assertEquals(null, reply(begun))
        assertEquals("ok" to null, ask("cardEnd", mapOf("error" to "Cancelled")))
        main.pump("the begin answered") { reply(begun) != null }
        assertEquals(null to CardLinkError.CANCELLED.words, reply(begun))
        assertEquals("ok", ctl("on"))
    }

    protected companion object {
        val CardOwnerKeyShape = Regex("^0[23][0-9a-f]{64}$")
    }
}
