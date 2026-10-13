package io.islandbitcoin.foxy.flashcard

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The main looper for a link whose work runs on a thread of its own (SimCardLink):
 * posts queue here, and the test thread runs them ([pump]), so the link's rule
 * "every callback on the main thread" holds with the test thread as main.
 */
class PumpedMain : CardMain {
    private val queue = LinkedBlockingQueue<Runnable>()

    override fun post(action: Runnable) {
        queue.add(action)
    }

    override fun later(ms: Long, action: Runnable): () -> Unit {
        var cancelled = false
        val t = Thread {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (!cancelled) queue.add(action)
        }
        t.isDaemon = true
        t.start()
        return { cancelled = true }
    }

    /** Runs what is posted until `done` holds; fails after `ms`. */
    fun pump(what: String, ms: Long = 20_000, done: () -> Boolean) {
        val until = System.nanoTime() + ms * 1_000_000
        while (!done()) {
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) throw AssertionError("timed out waiting for $what")
            queue.poll(minOf(left, 100), TimeUnit.MILLISECONDS)?.run()
        }
    }

    /** Runs whatever is posted for `ms`. */
    fun settle(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (true) {
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) return
            queue.poll(left, TimeUnit.MILLISECONDS)?.run()
        }
    }
}

/**
 * A card server on loopback that speaks getfoxy/card's CardServer line protocol
 * ("tap", "apdu HEX", "end"), with a card the test puts on and takes off and
 * answers it scripts. One connection at a time, as the real one serves.
 */
class FakeCardServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    @Volatile var present = true

    /** The answer to a command (hex in, hex or "gone" out). */
    @Volatile var answer: (String) -> String = { "9000" }
    val heard = CopyOnWriteArrayList<String>()
    @Volatile var connections = 0

    private val thread = Thread {
        while (!server.isClosed) {
            try {
                server.accept().use { serve(it) }
            } catch (e: SocketException) {
                return@Thread
            } catch (e: java.io.IOException) {
                // the phone went
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun serve(phone: Socket) {
        connections++
        val r = BufferedReader(InputStreamReader(phone.getInputStream(), StandardCharsets.US_ASCII))
        val w = phone.getOutputStream()
        while (true) {
            val line = r.readLine()?.trim() ?: return
            heard.add(line)
            val out = when {
                line == "tap" -> if (present) "ok" else "none"
                line == "end" -> "ok"
                line.startsWith("apdu ") -> if (!present) "gone" else answer(line.substring(5))
                else -> "what"
            }
            w.write((out + "\n").toByteArray(StandardCharsets.US_ASCII))
            w.flush()
        }
    }

    override fun close() {
        server.close()
        thread.join(2_000)
    }
}

/** A loopback port that nothing listens on (taken and let go). */
fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
