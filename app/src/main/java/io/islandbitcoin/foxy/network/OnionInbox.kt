package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.debug.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The answer to one payment: call once; unanswered, the payer hears 504. Any thread. */
typealias InboxAnswer = (status: Int, body: String) -> Unit

/**
 * Tor's onion services, as the inbox needs them (TorService.swift `addOnion`,
 * `removeOnion`): tor/Tor.kt over the control link.
 */
interface OnionServices {
    /**
     * A new address forwarding the onion's port 80 to 127.0.0.1:`localPort`:
     * `ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach Port=80,127.0.0.1:<port>`.
     * `done`, once, from any thread, with the 56-character service id without
     * ".onion", or null when Tor is not up or would not make one.
     */
    fun add(localPort: Int, done: (String?) -> Unit)

    /** `DEL_ONION`: quietly nothing when Tor is gone, which takes the address with it. */
    fun remove(serviceId: String)
}

/** One address a payment request is paid to: what the bridge holds (an [OnionInbox], or a test's stand-in). */
interface Inbox {
    /** What this one is for, so the log tells the spare from the live one. */
    var note: String

    /** "http://<56>.onion/<32 hex>", or null when it is not open (yet, or any more). */
    val url: String?

    val isOpen: Boolean

    /** Listen, then ask Tor for the address; `done(url or null)` once, on the main thread. */
    fun open(done: (String?) -> Unit)

    /** End the address and the listener. Payments being redeemed finish; their payers do not hear back. */
    fun close()
}

/**
 * The address a Cashu payment request is paid to, over Tor, on this phone
 * (Foxy/Network/OnionInbox.swift).
 *
 * NUT-18 lets a request name where the payer's wallet sends the ecash. Foxy's
 * name an onion address that exists only while the request is on screen. The
 * payer's Foxy connects to it through Tor and posts the proofs; this phone
 * takes them, the page redeems them, and the payer is answered whether that
 * worked. Nobody sits in between, and neither phone learns the other's address.
 *
 * Tor forwards the onion's port 80 to a listener on 127.0.0.1 here, the one
 * listening socket in the app (tools/check-no-network.sh). Other apps on the
 * phone can reach that port too, so the request's path carries 128 random bits
 * and anything else is answered 404 without being read. What gets through is
 * only a claim of payment: the mint decides.
 *
 * The bounds are iOS's: four connections at a time, 8 kB of head, 256 kB of
 * body, 30 s for a payer to send its request once connected, 100 s for the page
 * to answer (then 504), 20 s for the address to open at all; the answer, then
 * the close (the write side shut, the socket let go 2 s later, so the bytes are
 * on the wire before the close: iOS found a cut-off answer otherwise).
 */
class OnionInbox(
    override var note: String = "for the request on screen",
    private val services: OnionServices,
    /** Posts to the main thread, where `open`'s answer and every payment are handed over. */
    private val main: (Runnable) -> Unit,
    private val readPatienceMs: Long = READ_PATIENCE_MS,
    private val answerPatienceMs: Long = ANSWER_PATIENCE_MS,
    private val openPatienceMs: Long = OPEN_PATIENCE_MS,
    private val lingerMs: Long = LINGER_MS,
    /** The body of a payment that reached the right path, on the main thread. Call the answer once. */
    private val onPayment: (body: String, answer: InboxAnswer) -> Unit,
) : Inbox {
    companion object {
        /** A request saying it is longer than this is refused unread: several hundred proofs. */
        const val LARGEST_PAYMENT = 256 * 1024
        const val LARGEST_HEAD = 8 * 1024
        const val MOST_CONNECTIONS = 4
        const val READ_PATIENCE_MS = 30_000L
        const val ANSWER_PATIENCE_MS = 100_000L
        const val OPEN_PATIENCE_MS = 20_000L
        const val LINGER_MS = 2_000L

        /** The words of each status the inbox sends (OnionInbox.swift `respond`). */
        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Content"
            504 -> "Gateway Timeout"
            else -> "Error"
        }

        /** The bytes of an answer, byte for byte iOS's. */
        fun response(status: Int, body: String): ByteArray {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            if (bytes.isNotEmpty()) head.append("Content-Type: application/json\r\n")
            head.append("\r\n")
            return head.toString().toByteArray(Charsets.UTF_8) + bytes
        }

        private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "foxy-onion-inbox-timer").apply { isDaemon = true }
        }
    }

    /** "/" and 128 random bits in hex: the secret a caller needs to be heard at all. */
    val path: String = "/" + MintCircuit.label(null)

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile private var serviceId: String? = null
    @Volatile private var server: ServerSocket? = null
    private val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val threads: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "foxy-onion-inbox").apply { isDaemon = true } }

    override val url: String?
        get() {
            val id = serviceId ?: return null
            if (closed.get()) return null
            return "http://$id.onion$path"
        }

    /** The loopback port Tor forwards to, 0 before the listener is up: a debug build's log, and the tests. */
    val localPort: Int get() = server?.localPort ?: 0

    /** The listener itself, null before it is up and once closed: the tests ask it whether it is closed. */
    internal val listener: ServerSocket? get() = server

    override val isOpen: Boolean
        get() = url != null && server?.let { it.isBound && !it.isClosed } == true

    override fun open(done: (String?) -> Unit) {
        val reported = AtomicBoolean(false)
        val report: (String?) -> Unit = { u -> if (reported.compareAndSet(false, true)) main(Runnable { done(u) }) }
        // a deadline, because nothing else here may leave the receive screen waiting for ever
        timers.schedule({
            if (!reported.get()) {
                Log.line("[foxy] onion inbox did not open in ${openPatienceMs / 1000}s — $note")
                report(null)
                close()
            }
        }, openPatienceMs, TimeUnit.MILLISECONDS)
        threads.execute {
            /* Bound to 127.0.0.1, which is what keeps it off the network: Tor
             * forwards the onion's traffic here, and nothing outside the phone
             * can reach it. */
            val listener = try {
                ServerSocket(0, 8, Socks5SocketFactory.TOR_HOST)
            } catch (e: IOException) {
                Log.line("[foxy] onion inbox listener would not start — $note (${e.javaClass.simpleName})")
                report(null)
                return@execute
            }
            synchronized(lock) {
                if (closed.get()) {
                    closeQuietly(listener)
                    report(null)
                    return@execute
                }
                server = listener
            }
            threads.execute { acceptAll(listener) }
            main(Runnable {
                if (closed.get()) return@Runnable report(null)
                services.add(listener.localPort) { id ->
                    val keep = synchronized(lock) {
                        if (id == null || closed.get()) {
                            false
                        } else {
                            serviceId = id
                            true
                        }
                    }
                    if (!keep) {
                        if (id != null) services.remove(id)
                        report(null)
                        return@add
                    }
                    Log.line("[foxy] onion inbox open — $note")
                    report(url)
                }
            })
        }
    }

    override fun close() {
        val id: String?
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            id = serviceId
        }
        if (id != null) services.remove(id)
        server?.let { closeQuietly(it) }
        server = null
        connections.forEach { closeQuietly(it) }
        connections.clear()
        Log.line("[foxy] onion inbox closed — $note")
    }

    // MARK: One connection: a request in, one answer out

    private fun acceptAll(listener: ServerSocket) {
        while (!closed.get()) {
            val conn = try {
                listener.accept()
            } catch (e: IOException) {
                return
            }
            if (closed.get() || connections.size >= MOST_CONNECTIONS) {
                closeQuietly(conn)
                continue
            }
            connections.add(conn)
            threads.execute { serve(conn) }
        }
    }

    private fun serve(conn: Socket) {
        val answering = AtomicBoolean(false)
        // still reading: a payer that stalls does not hold a slot
        val stall = timers.schedule({ if (!answering.get()) drop(conn) }, readPatienceMs, TimeUnit.MILLISECONDS)
        var buffer = ByteArray(16 * 1024)
        var count = 0
        try {
            val input = conn.getInputStream()
            val chunk = ByteArray(65_536)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) {
                    // closed before a whole request: nothing to answer
                    stall.cancel(false)
                    return drop(conn)
                }
                if (count + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, count + n))
                chunk.copyInto(buffer, count, 0, n)
                count += n
                when (val outcome = HttpRequestHead.parse(buffer, path, LARGEST_HEAD, LARGEST_PAYMENT, 0, count)) {
                    HttpRequestHead.Outcome.NeedMore -> continue
                    is HttpRequestHead.Outcome.Refuse -> {
                        stall.cancel(false)
                        return respond(conn, outcome.status, "")
                    }
                    is HttpRequestHead.Outcome.Payment -> {
                        answering.set(true)
                        stall.cancel(false)
                        return handOver(conn, outcome.body)
                    }
                }
            }
        } catch (e: IOException) {
            stall.cancel(false)
            drop(conn)
        }
    }

    private fun handOver(conn: Socket, body: String) {
        val answered = AtomicBoolean(false)
        val answer: InboxAnswer = { status, text ->
            if (answered.compareAndSet(false, true)) threads.execute { respond(conn, status, text) }
        }
        timers.schedule({ answer(504, "{\"error\":\"no answer in time\"}") }, answerPatienceMs, TimeUnit.MILLISECONDS)
        main(Runnable {
            if (closed.get()) answer(410, "") else onPayment(body, answer)
        })
    }

    /* The answer, then the close: the write side shut after the bytes, and the
     * socket let go a moment later, so the payer reads all of it. */
    private fun respond(conn: Socket, status: Int, body: String) {
        try {
            val out = conn.getOutputStream()
            out.write(response(status, body))
            out.flush()
            conn.shutdownOutput()
        } catch (e: IOException) {
            return drop(conn)
        }
        timers.schedule({ drop(conn) }, lingerMs, TimeUnit.MILLISECONDS)
    }

    private fun drop(conn: Socket) {
        closeQuietly(conn)
        connections.remove(conn)
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try {
            c.close()
        } catch (_: IOException) {
        }
    }
}
