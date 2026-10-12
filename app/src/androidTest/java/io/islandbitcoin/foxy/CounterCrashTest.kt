package io.islandbitcoin.foxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.bridge.FoxyBridge
import io.islandbitcoin.foxy.bridge.PageHost
import io.islandbitcoin.foxy.keychain.AtomicWrite
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.keychain.NUT13
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * S6 on the phone's own storage: a counterReserve that dies after its range is
 * written and before it is answered leaves the counter at or past the range when
 * the app comes back. The death is the seed thread stopping at the moment after
 * the rename (AtomicWrite's hook), so no reply goes out; "coming back" is a new
 * CounterStore reading the file, as a relaunch does. (Killing the process for
 * real would take the instrumentation with it.)
 */
@RunWith(AndroidJUnit4::class)
class CounterCrashTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "crash-test-${System.nanoTime()}").apply { mkdirs() }
    private val keyset = "009a1f293253e41e"

    @After
    fun clean() {
        AtomicWrite.afterRename = null
        dir.deleteRecursively()
    }

    private class Died : RuntimeException("the process died here")

    @Test
    fun aReservationCutOffBeforeItsReplyIsNotHandedOutAgain() {
        val seed = NUT13.seed("legal winner thank year wave sausage worth useful legal winner thank yellow")
        val counters = CounterStore(dir)
        val env = SeedEnvironment(
            savedSeed = { SeedVault.SecretsSeed.Found(seed) },
            seedPresent = { true },
            write = { null },
            counters = counters,
            candidates = SeedCandidates(),
            wordlist = { null },
            generate = { "" },
            migrationWindowOpen = { false },
            closeMigrationWindow = {},
            flags = Flags(dir),
        )
        val evaluated = CopyOnWriteArrayList<String>()
        val host = object : PageHost {
            override fun post(action: Runnable) = action.run()
            override fun evaluate(js: String) {
                evaluated.add(js)
            }
            override fun covered() {}
            override fun keepAwake(on: Boolean) {}
            override fun haptic(kind: String) {}
        }
        val executor = Executors.newSingleThreadExecutor()
        val bridge = FoxyBridge(host, { env }, executor, false)

        // the first reservation goes through: 0..<10
        bridge.onMessage("""{"action":"counterReserve","id":"r1","keysetId":"$keyset","count":10}""", true, FoxyBridge.ORIGIN)
        val end = System.currentTimeMillis() + 30_000
        while (evaluated.none { it.contains("\"r1\"") } && System.currentTimeMillis() < end) Thread.sleep(20)
        // the second dies after its file is renamed into place and before anything is answered
        AtomicWrite.afterRename = { file -> if (file.name == CounterStore.FILE_NAME) throw Died() }
        bridge.onMessage("""{"action":"counterReserve","id":"r2","keysetId":"$keyset","count":25}""", true, FoxyBridge.ORIGIN)
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        AtomicWrite.afterRename = null

        assertTrue("r1 was answered with its secrets: ${evaluated.map { it.take(160) }}", evaluated.any { it.contains("\"r1\"") && it.contains("secrets\\\":[\\\"") })
        assertTrue("r2's secrets never left", evaluated.none { it.contains("\"r2\"") && it.contains("secrets\\\":[\\\"") })
        // the app comes back: the counter is past what r2 had reserved, so nothing of it is handed out again
        val relaunched = CounterStore(dir)
        assertEquals(35L, relaunched.next(keyset))
        assertEquals(35L, relaunched.reserve(keyset, 0, 1))
    }
}
