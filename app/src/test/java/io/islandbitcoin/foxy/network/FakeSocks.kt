package io.islandbitcoin.foxy.network

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tor's SOCKS port, as far as the tests need it: a SOCKS5 server on loopback
 * that records every handshake (the address type, the name, the login) and
 * then either refuses it as told or joins the client to `forwardTo` (a
 * MockWebServer on loopback) and pipes bytes both ways. Nothing it does leaves
 * the machine.
 */
class FakeSocks(var forwardTo: Int = 0) : AutoCloseable {
    data class Handshake(val atyp: Int, val host: String, val port: Int, val user: String, val pass: String)

    // IPv4 loopback only, as Tor listens
    private val server = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = server.localPort
    val handshakes = CopyOnWriteArrayList<Handshake>()
    val connections = AtomicInteger(0)

    /** The next this many CONNECT requests are answered with this reply code (0 = granted); then granted. */
    @Volatile var refuseNext = 0
    @Volatile var refuseWith = 4
    /** The next this many connections are closed before any reply, as a listener torn down mid-handshake. */
    @Volatile var dropNext = 0
    /**
     * CONNECT requests are read and never answered, as Tor holds a stream
     * "waiting for circuit" while it will not use the circuits it has (every
     * primary guard marked unreachable, ENG-652): the client's handshake times out.
     */
    @Volatile var holdConnects = false

    init {
        thread(isDaemon = true, name = "fake-socks") {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                connections.incrementAndGet()
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            if (dropNext > 0) {
                dropNext--
                s.close()
                return
            }
            if (input.readUnsignedByte() != 5) return s.close()
            val n = input.readUnsignedByte()
            val methods = ByteArray(n).also { input.readFully(it) }
            if (methods.none { it.toInt() == 2 }) {
                out.write(byteArrayOf(5, 0xFF.toByte()))
                return s.close()
            }
            out.write(byteArrayOf(5, 2))
            input.readUnsignedByte() // auth version 1
            val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            out.write(byteArrayOf(1, 0))
            input.readUnsignedByte() // version
            input.readUnsignedByte() // command
            input.readUnsignedByte() // reserved
            val atyp = input.readUnsignedByte()
            val host = when (atyp) {
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                4 -> ByteArray(16).also { input.readFully(it) }.joinToString(":") { "%02x".format(it) }
                else -> ""
            }
            val port = input.readUnsignedShort()
            handshakes.add(Handshake(atyp, host, port, user, pass))
            if (holdConnects) {
                // until the client gives up and closes
                while (input.read() >= 0) {
                }
                return s.close()
            }
            if (refuseNext > 0) {
                refuseNext--
                out.write(byteArrayOf(5, refuseWith.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                return s.close()
            }
            val target = Socket()
            target.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), forwardTo), 5_000)
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            out.flush()
            pipe(input, target.getOutputStream()) { target.shutdownOutput() }
            pipe(target.getInputStream(), out) { s.shutdownOutput() }
        } catch (e: IOException) {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, end: () -> Unit) {
        thread(isDaemon = true) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val r = from.read(buf)
                    if (r < 0) break
                    to.write(buf, 0, r)
                    to.flush()
                }
            } catch (_: IOException) {
            }
            try {
                end()
            } catch (_: IOException) {
            }
        }
    }

    override fun close() = server.close()
}
