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
    private fun link(reply: String, sent: ByteArrayOutputStream): TorControlConnection {
        val answers = PipedOutputStream()
        val input = PipedInputStream(answers, 4096)
        val out = object : OutputStream() {
            override fun write(b: Int) {
                sent.write(b)
                if (b == '\n'.code) {
                    answers.write(reply.toByteArray())
                    answers.flush()
                }
            }
        }
        return TorControlConnection(input, out)
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

    @Test
    fun delOnionNamesTheServiceId() {
        val sent = ByteArrayOutputStream()
        link("250 OK\r\n", sent).delOnion(id)
        assertEquals("DEL_ONION $id\r\n", sent.toString())
    }
}
