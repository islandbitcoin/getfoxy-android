package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.network.TorRoute
import io.islandbitcoin.foxy.tor.FakeControl
import io.islandbitcoin.foxy.tor.FakeDaemon
import io.islandbitcoin.foxy.tor.FakeScheduler
import io.islandbitcoin.foxy.tor.FakeTransports
import io.islandbitcoin.foxy.tor.TorMachine
import io.islandbitcoin.foxy.tor.TransportMemory
import io.islandbitcoin.foxy.TestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * S11: continuing without Tor is the person's, for this process only. It is
 * cleared the moment Tor connects (the machine's onEstablished is what Tor.kt
 * wires to Route.unprotected = false), a yes while Tor runs is ignored, and it
 * lives in memory: Route holds it in an AtomicBoolean and nothing it holds is a
 * file or a store, so a new process starts without it.
 */
class UnprotectedLifetimeTest {
    private val before = Route.tor

    @After
    fun reset() {
        Route.unprotected = false
        Route.tor = before
    }

    @Test
    fun torConnectingClearsIt() {
        val sched = FakeScheduler()
        val control = FakeControl(sched)
        val m = TorMachine(sched, FakeDaemon(), FakeTransports(), TransportMemory(File(TestSupport.tempDir("u"), "t")), false, { true },
            onEstablished = { Route.unprotected = false })
        Route.unprotected = true
        assertTrue(Route.available)
        m.start()
        m.controlReady(control)
        sched.drain()
        assertTrue("still unprotected while Tor connects", Route.unprotected)
        m.statusEvent("NOTICE CIRCUIT_ESTABLISHED")
        sched.drain()
        assertFalse("Tor up: the open connection ends", Route.unprotected)
    }

    @Test
    fun whileTorRunsTheRouteIsTorsAndTheAskIsIgnored() {
        Route.tor = object : TorRoute {
            override val isRunning = true
            override val socksPort = 9150
        }
        var unprotected = false
        val shown = mutableListOf<String>()
        val env = NetEnvironment.noRoute(PromptQueue { p, _ -> shown.add(p.title); PromptQueue.Shown {} }).let {
            NetEnvironment(
                torRunning = { true }, bridged = { false }, snapshot = it.snapshot, restartTor = {}, unprotected = { unprotected },
                setUnprotected = { v -> unprotected = v }, available = { true }, approvals = it.approvals, prompts = it.prompts,
                send = it.send, after = it.after,
            )
        }
        NetBridge(env, io.islandbitcoin.foxy.FakeHost()) { _, _, _ -> }.handle("unprotected", "1", mapOf("on" to true))
        assertTrue("no dialog while Tor runs", shown.isEmpty())
        assertFalse(unprotected)
    }

    @Test
    fun itIsKeptInMemoryOnly() {
        val fields = Route::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) || true }
        val flag = fields.single { it.name.startsWith("unprotectedFlag") }
        assertEquals(java.util.concurrent.atomic.AtomicBoolean::class.java, flag.type)
        for (f in fields) {
            assertFalse("${f.name} could keep it on disk", f.type == File::class.java || io.islandbitcoin.foxy.keychain.TextStore::class.java.isAssignableFrom(f.type))
        }
        assertFalse("a new process starts without it", Route.unprotected)
    }
}
