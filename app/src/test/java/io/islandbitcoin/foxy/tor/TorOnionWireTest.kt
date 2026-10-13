package io.islandbitcoin.foxy.tor

import net.freehaven.tor.control.TorControlConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * What tor/Tor.kt's control link writes for an onion address, through jtorctl
 * itself (the library the app ships), against a control port made of two
 * byte streams: the exact `ADD_ONION` line iOS sends (TorService.swift
 * `addOnion`), the service id read from Tor's answer, an error answer thrown
 * (which Tor.kt's `call` answers as no address), and `DEL_ONION`.
 */
class TorOnionWireTest {
    // a hang here is a bug in the fake port, not a pass: each test is bounded
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(20)

    private val id = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd"

    /**
     * A control port that answers `reply` once a whole command line has been
     * written, as Tor does: never before, or jtorctl's reader would take the
     * reply with nobody waiting for it.
     */
    private fun link(reply: String, sent: ByteArrayOutputStream): TorControlConnection = link(listOf(reply), sent)

    /** The same, answering each command line with the next of `replies` (the last one again once they run out). */
    private fun link(replies: List<String>, sent: ByteArrayOutputStream): TorControlConnection {
        val answers = PipedOutputStream()
        val input = PipedInputStream(answers, 4096)
        var lines = 0
        val out = object : OutputStream() {
            override fun write(b: Int) {
                sent.write(b)
                if (b == '\n'.code) {
                    answers.write(replies[minOf(lines++, replies.size - 1)].toByteArray())
                    answers.flush()
                }
            }
        }
        return TorControlConnection(input, out)
    }

    /** The control thread, run when the test says: what it would be doing while the main thread's deadline passes. */
    private class HeldWorker : java.util.concurrent.Executor {
        val held = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            held.addLast(command)
        }
        fun runAll() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    @Test
    fun addOnionWritesIosLineAndReadsTheServiceId() {
        val sent = ByteArrayOutputStream()
        val answer = link("250-ServiceID=$id\r\n250 OK\r\n", sent)
            .addOnion(TorOnion.KEY, mapOf(80 to TorOnion.target(40123)), TorOnion.FLAGS)
        assertEquals("ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach Port=80,127.0.0.1:40123\r\n", sent.toString())
        assertEquals(TorOnion.command(40123) + "\r\n", sent.toString())
        assertEquals(id, answer["onionAddress"])
        // DiscardPK: Tor hands back no key, and none is read
        assertTrue(answer["onionPrivKey"] == null)
    }

    @Test
    fun anErrorAnswerIsThrownNotTakenForAnAddress() {
        val sent = ByteArrayOutputStream()
        try {
            link("512 Bad arguments to ADD_ONION\r\n", sent).addOnion(TorOnion.KEY, mapOf(80 to TorOnion.target(1)), TorOnion.FLAGS)
            fail("an error answer made an address")
        } catch (e: IOException) {
            // Tor.kt's JtorctlControl.call answers null for it
        }
    }

    /**
     * Tor.kt's JtorctlControl: an ADD_ONION Tor answers inside the deadline is
     * handed over, and nothing is removed.
     */
    @Test
    fun anAddOnionAnsweredInTimeIsHandedOver() {
        val sent = ByteArrayOutputStream()
        val sched = FakeScheduler()
        val worker = HeldWorker()
        val control = JtorctlControl(link("250-ServiceID=$id\r\n250 OK\r\n", sent), sched, worker)
        val got = mutableListOf<String?>()
        control.addOnion(40123, 20_000) { got.add(it) }
        worker.runAll()
        sched.drain()
        assertEquals(listOf<String?>(id), got)
        sched.advance(30_000)
        worker.runAll()
        assertEquals("answered once", listOf<String?>(id), got)
        assertEquals("no DEL_ONION", TorOnion.command(40123) + "\r\n", sent.toString())
    }

    /**
     * The control link was stuck past the deadline (behind a slow SETCONF):
     * the inbox was told no address, so nobody holds the id. With Detach, the
     * service Tor made late would stay published for the daemon's life; it is
     * ended with DEL_ONION instead, and `done` is not called a second time.
     */
    @Test
    fun anAddOnionAnsweredAfterTheDeadlineIsRemoved() {
        val sent = ByteArrayOutputStream()
        val sched = FakeScheduler()
        val worker = HeldWorker()
        val control = JtorctlControl(link(listOf("250-ServiceID=$id\r\n250 OK\r\n", "250 OK\r\n"), sent), sched, worker)
        val got = mutableListOf<String?>()
        control.addOnion(40123, 20_000) { got.add(it) }
        sched.advance(20_000)
        assertEquals("the deadline answered no address", listOf<String?>(null), got)
        // Tor makes the service only now
        worker.runAll()
        sched.drain()
        worker.runAll()
        assertEquals("done is not called again", listOf<String?>(null), got)
        assertEquals(TorOnion.command(40123) + "\r\n" + "DEL_ONION $id\r\n", sent.toString())
    }

    @Test
    fun aLateErrorAnswerRemovesNothing() {
        val sent = ByteArrayOutputStream()
        val sched = FakeScheduler()
        val worker = HeldWorker()
        val control = JtorctlControl(link("512 Bad arguments to ADD_ONION\r\n", sent), sched, worker)
        val got = mutableListOf<String?>()
        control.addOnion(40123, 20_000) { got.add(it) }
        sched.advance(20_000)
        worker.runAll()
        sched.drain()
        worker.runAll()
        assertEquals(listOf<String?>(null), got)
        assertEquals(TorOnion.command(40123) + "\r\n", sent.toString())
    }

    @Test
    fun delOnionNamesTheServiceId() {
        val sent = ByteArrayOutputStream()
        link("250 OK\r\n", sent).delOnion(id)
        assertEquals("DEL_ONION $id\r\n", sent.toString())
    }
}
