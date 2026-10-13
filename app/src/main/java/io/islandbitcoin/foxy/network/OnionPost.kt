package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.debug.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A payment posted to another phone's onion address (OnionInbox), through Tor
 * (Foxy/Network/OnionPost.swift).
 *
 * Not OkHttp: this is one exchange of iOS's own shape, byte for byte, so the
 * receiving phone (an iPhone or another Android) reads exactly what an iPhone
 * payer sends. SOCKS5 to Tor's port through [Socks5SocketFactory] (a login
 * that names the circuit, a CONNECT to the onion name, nothing looked up
 * here), then one HTTP/1.1 POST, and one answer read to the close.
 *
 * Only an onion host is accepted, and only through Tor: never the open
 * connection, where an onion name would be looked up by the phone's DNS. A
 * person who chose to continue without Tor still cannot post here, as on iOS.
 */
object OnionPost {
    /** An answer longer than this is cut off: the inbox says a few words. */
    const val LARGEST_ANSWER = 64 * 1024

    /** Left to itself an attempt waits this long, which is also Tor's SocksTimeout. */
    const val DEFAULT_TIMEOUT_MS = 120_000L

    /** What went wrong, and what the page is told (OnionPost.swift `Failure.text`). */
    sealed class Failure(val text: String) {
        object NotOnion : Failure("That is not an onion address.")
        object NoTor : Failure(Route.REFUSAL)

        /** Tor could not reach the address: not published yet, or gone. */
        data class Unreachable(val code: Int) : Failure("Their phone could not be reached over Tor.")
        object RefusedBySocks : Failure("Tor refused the connection.")
        object TimedOut : Failure("Their phone did not answer in time.")
        object Broken : Failure("The connection to their phone broke.")

        override fun toString(): String = javaClass.simpleName + (if (this is Unreachable) "($code)" else "")
    }

    sealed class Result {
        data class Done(val status: Int, val body: String) : Result()

        data class Failed(val failure: Failure) : Result()
    }

    /** Where a post may go: the host (lowercased), the port and the request target. */
    data class Target(val host: String, val port: Int, val path: String)

    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "foxy-onion-post").apply { isDaemon = true } }
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "foxy-onion-post-timer").apply { isDaemon = true }
    }

    /**
     * `http://<56>.onion[:port][/path]` and nothing else: no user or password,
     * no query, no fragment, the path as written (percent-encoded, never
     * decoded) and printable ASCII with no space, so no URL the page was handed
     * can add a header line or a second request (OnionPost.swift `send`,
     * `requestTarget`, `portOf`). Null for anything else.
     */
    fun target(url: String): Target? {
        // the scheme as Foundation keeps it: "HTTP://" is not "http"
        if (!url.startsWith("http://")) return null
        val rest = url.substring("http://".length)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) rest else rest.substring(0, cut)
        val after = if (cut < 0) "" else rest.substring(cut)
        // a user or a password, even an empty one
        if ('@' in authority) return null
        val colon = authority.lastIndexOf(':')
        val name = if (colon < 0) authority else authority.substring(0, colon)
        val port = if (colon < 0) {
            80
        } else {
            val digits = authority.substring(colon + 1)
            if (digits.isEmpty() || digits.length > 5 || !digits.all { it in '0'..'9' }) return null
            digits.toInt().takeIf { it in 0..65535 } ?: return null
        }
        if (!OnionAddress.isHost(name)) return null
        // no query and no fragment, not even an empty one
        if ('?' in after || '#' in after) return null
        val path = after.ifEmpty { "/" }
        if (!path.all { it.code in 0x21..0x7e }) return null
        return Target(name.lowercase(java.util.Locale.ROOT), port, path)
    }

    /** The bytes iOS writes: the head (OnionPost.swift `send`), then the body. */
    fun request(target: Target, body: ByteArray): ByteArray {
        val head = "POST ${target.path} HTTP/1.1\r\nHost: ${target.host}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + body
    }

    /**
     * POST `body` to `url` on the circuit `circuit`. `done` once, from any
     * thread, with the HTTP status and body or the failure. `tor`: Tor as Route
     * sees it (running, and its SOCKS port).
     */
    fun send(
        url: String,
        body: ByteArray,
        circuit: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        tor: TorRoute = Route.tor,
        doorShut: () -> Boolean = { Route.shut() },
        done: (Result) -> Unit,
    ) {
        val target = target(url) ?: return done(Result.Failed(Failure.NotOnion))
        /* Tor or nowhere: an onion name is never handed to the open connection.
         * And, as every request on Android, nothing new leaves while Foxy is
         * being put away (Route's door). */
        if (!tor.isRunning || tor.socksPort <= 0 || doorShut()) return done(Result.Failed(Failure.NoTor))
        // the login is the circuit label (IsolateSOCKSAuth); the bridge's labels are 16-64 hex (MintCircuit)
        if (circuit.toByteArray(Charsets.UTF_8).size !in 1..255) return done(Result.Failed(Failure.RefusedBySocks))
        val exchange = Exchange(done)
        val deadline = timers.schedule({ exchange.finish(Result.Failed(Failure.TimedOut)) }, timeoutMs, TimeUnit.MILLISECONDS)
        /* The socket's own timeouts sit a second past the attempt's ceiling, so the
         * ceiling is what ends a slow attempt (TimedOut, as iOS), never a read
         * timing out a moment earlier; and the handshake may take as long as the
         * attempt, an onion's descriptor and rendezvous taking their time. */
        val ms = (timeoutMs + 1_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        workers.execute {
            exchange.run(Socks5SocketFactory(circuit, { tor.socksPort }, handshakeTimeoutMs = ms), target, request(target, body), ms)
            deadline.cancel(false)
        }
    }

    /** One connection's steps; the first ending wins and closes the socket. */
    private class Exchange(private val done: (Result) -> Unit) {
        private val over = AtomicBoolean(false)
        @Volatile private var socket: Socket? = null

        fun finish(result: Result) {
            if (!over.compareAndSet(false, true)) return
            try {
                socket?.close()
            } catch (_: IOException) {
            }
            if (result is Result.Failed) Log.line("[foxy] onion post: ${result.failure}")
            done(result)
        }

        fun run(factory: Socks5SocketFactory, target: Target, request: ByteArray, timeoutMs: Int) {
            val s = factory.createSocket()
            socket = s
            if (over.get()) {
                s.close()
                return
            }
            try {
                s.connect(InetSocketAddress.createUnresolved(target.host, target.port), timeoutMs)
            } catch (e: Socks5Failure) {
                return finish(Result.Failed(when (e.kind) {
                    Socks5Failure.Kind.DIAL -> Failure.NoTor
                    Socks5Failure.Kind.UNREACHABLE -> Failure.Unreachable(e.code)
                    Socks5Failure.Kind.BROKEN -> Failure.Broken
                    Socks5Failure.Kind.REFUSED -> Failure.RefusedBySocks
                }))
            } catch (e: IOException) {
                return finish(Result.Failed(Failure.Broken))
            }
            try {
                // the deadline above bounds the rest; nothing here waits on a read timeout of its own
                s.soTimeout = 0
                val out = s.getOutputStream()
                out.write(request)
                out.flush()
            } catch (e: IOException) {
                return finish(Result.Failed(Failure.Broken))
            }
            /* Read to the close. Whether it closed or failed, what arrived is
             * parsed (OnionPost.swift `collect`: `complete || error`), so an
             * answer followed by a reset is still the answer. */
            val answer = ByteArrayOutputStream()
            try {
                val input = s.getInputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    answer.write(chunk, 0, n)
                    if (answer.size() > LARGEST_ANSWER) return finish(Result.Failed(Failure.Broken))
                }
            } catch (e: IOException) {
                if (over.get()) return
            }
            val parsed = HttpAnswer.parse(answer.toByteArray()) ?: return finish(Result.Failed(Failure.Broken))
            finish(Result.Done(parsed.status, parsed.body))
        }
    }
}
