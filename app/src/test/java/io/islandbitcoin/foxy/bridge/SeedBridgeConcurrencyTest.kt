package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.FakeKeys
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.BIP39
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one seed executor (S5, S6): fifty seedCreates at once make exactly one seed,
 * every other answered "a seed already exists" or "Another change to the seed is
 * still waiting"; reservations from many threads never overlap.
 */
class SeedBridgeConcurrencyTest {
    private val dir = TestSupport.tempDir("concurrency")
    private val keys = FakeKeys()
    private val vault = SeedVault(dir, keys)
    private val counters = CounterStore(File(dir, "counters"))
    private val host = FakeHost()
    private val seedThread = Executors.newSingleThreadExecutor()
    private val writes = java.util.concurrent.atomic.AtomicInteger(0)
    private val env = SeedEnvironment(
        savedSeed = { vault.seedForSecrets() }, seedPresent = { vault.isThere() },
        write = { writes.incrementAndGet(); vault.write(it) },
        counters = counters, candidates = SeedCandidates(), wordlist = { TestSupport.wordlist },
        generate = { BIP39.generate(it) }, migrationWindowOpen = { false }, closeMigrationWindow = {}, flags = Flags(dir),
    )
    private val bridge = FoxyBridge(host, { env }, seedThread, false)

    @After
    fun clean() {
        seedThread.shutdownNow()
        dir.deleteRecursively()
    }

    private fun parse(js: String): List<String?> {
        @Suppress("UNCHECKED_CAST")
        return Json.parse("[" + js.substringAfter("_scanResult(").removeSuffix(")") + "]") as List<String?>
    }

    private fun waitForReplies(n: Int) {
        val end = System.currentTimeMillis() + 60_000
        while (host.evaluated.size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(n, host.evaluated.size)
    }

    @Test
    fun fiftyParallelCreatesMakeExactlyOneSeed() {
        val pool = Executors.newFixedThreadPool(50)
        val go = CountDownLatch(1)
        repeat(50) { n ->
            pool.execute {
                go.await()
                bridge.onMessage("""{"action":"seedCreate","id":"c$n"}""", true, FoxyBridge.ORIGIN)
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        waitForReplies(50)
        val replies = host.evaluated.map { parse(it) }
        val created = replies.filter { it[1] == "{\"created\":true}" }
        assertEquals("exactly one seed was made", 1, created.size)
        for (r in replies - created.toSet()) {
            assertTrue("$r", r[2] == "a seed already exists" || r[2] == SeedBridge.BUSY)
        }
        assertEquals("one write reached the vault", 1, writes.get())
        assertTrue(vault.read() is SeedVault.Answer.Found)
    }

    @Test
    fun parallelReservationsNeverOverlap() {
        assertEquals(null, vault.write(TestSupport.SAVED))
        val pool = Executors.newFixedThreadPool(16)
        repeat(40) { n ->
            pool.execute {
                bridge.onMessage("""{"action":"counterReserve","id":"r$n","keysetId":"${TestSupport.V00}","count":${1 + n % 7}}""", true, FoxyBridge.ORIGIN)
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        waitForReplies(40)
        val ranges = host.evaluated.map { js ->
            val text = parse(js)[1]!!
            @Suppress("UNCHECKED_CAST")
            val o = Json.parse(text) as Map<String, Any?>
            val start = (o["start"] as Double).toLong()
            @Suppress("UNCHECKED_CAST")
            start until start + (o["secrets"] as List<String>).size
        }.sortedBy { it.first }
        for (i in 1 until ranges.size) assertEquals("ranges are contiguous and never overlap", ranges[i - 1].last + 1, ranges[i].first)
        assertEquals(0L, ranges.first().first)
        assertEquals(ranges.last().last + 1, counters.next(TestSupport.V00))
        val secrets = host.evaluated.flatMap { js ->
            @Suppress("UNCHECKED_CAST")
            ((Json.parse(parse(js)[1]!!) as Map<String, Any?>)["secrets"] as List<String>)
        }
        assertEquals("no secret was handed out twice", secrets.size, secrets.toSet().size)
    }
}
