package io.islandbitcoin.foxy.network

import okhttp3.Dns
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * SOCKS5 to Tor, by hand, one factory per circuit label (docs/ROUTE.md §5;
 * ANDROID-MAPPING decision 1, decided here).
 *
 * Java's own SOCKS client takes credentials only from the process-wide
 * `java.net.Authenticator`, which cannot carry a per-request label, and it may
 * resolve the name itself. So OkHttp is given no proxy, [TorDns] (which never
 * looks anything up), and this factory, whose sockets:
 *
 * - ignore the address OkHttp resolved: they dial `127.0.0.1:<Tor's SOCKS
 *   port>`, port 9 while the port is unknown (nothing listens there, so the
 *   request is refused rather than sent elsewhere, TorService.swift:1814-1816);
 * - authenticate with username = password = the label (RFC 1929), so Tor keeps
 *   each label on its own circuit (IsolateSOCKSAuth);
 * - CONNECT with ATYP 0x03 and the host's name as OkHttp was given it
 *   (`InetSocketAddress.hostString`, never a reverse lookup), so Tor resolves
 *   it, and refuse to send an IP literal at all (Tor's SafeSocks would too).
 *
 * Every failure before the CONNECT is granted is a [Socks5Failure]: thrown
 * from `connect`, before OkHttp has written one byte of the request, which is
 * what makes it the one failure a money request may be retried after.
 *
 * OkHttp's `Address` does not compare socket factories, so a pooled connection
 * could otherwise be handed to another label: RouteHttp gives each label its
 * own factory and its own connection pool.
 */
class Socks5SocketFactory(
    val label: String,
    private val port: () -> Int,
    private val handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS,
) : SocketFactory() {
    companion object {
        /** Where a request goes while Tor's port is unknown: the discard port, which nothing on a phone serves. */
        const val DEAD_PORT = 9

        /**
         * 127.0.0.1, where Tor listens (`SocksPort auto` binds IPv4 loopback).
         * Not `getLoopbackAddress()`: on Android that is ::1, which Tor does not
         * listen on, and every request was refused (found on the Pixel).
         */
        val TOR_HOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        const val HANDSHAKE_TIMEOUT_MS = 30_000
        private const val VERSION: Byte = 5
        private const val USER_PASS: Byte = 2
        private const val CONNECT: Byte = 1
        private const val DOMAIN: Byte = 3

        /** What the reply codes mean (RFC 1928 §6, and Tor's ExtendedErrors 0xF0-0xF7). */
        fun replyWords(code: Int): String = when (code) {
            1 -> "general failure"
            2 -> "not allowed"
            3 -> "network unreachable"
            4 -> "host unreachable"
            5 -> "connection refused"
            6 -> "TTL expired"
            7 -> "command not supported"
            8 -> "address type not supported"
            0xF0 -> "onion service descriptor not found"
            0xF1 -> "onion service descriptor invalid"
            0xF2 -> "onion service introduction failed"
            0xF3 -> "onion service rendezvous failed"
            0xF4 -> "onion service missing client authorization"
            0xF5 -> "onion service wrong client authorization"
            0xF6 -> "onion service invalid address"
            0xF7 -> "onion service introduction timed out"
            else -> "reply $code"
        }
    }

    init {
        val n = label.toByteArray(Charsets.UTF_8).size
        require(n in 1..255) { "a circuit label is 1 to 255 bytes" }
    }

    /** The port a socket of this factory dials now: Tor's, or [DEAD_PORT] while it is unknown. */
    fun portNow(): Int = port().let { if (it in 1..65535) it else DEAD_PORT }

    override fun createSocket(): Socket = Socks5Socket()

    override fun createSocket(host: String, port: Int): Socket =
        Socks5Socket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socks5Socket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    /** A socket whose connect is a SOCKS5 handshake with Tor for the name it was asked to reach. */
    inner class Socks5Socket : Socket() {
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("not an InetSocketAddress")
            // the name as it was given; never getHostName, which may look the address up
            val host = target.hostString ?: ""
            // the port dialled travels in every Socks5Failure, for telling a rebuilt Tor from Tor being down
            val p = portNow()
            if (host.isEmpty() || host.toByteArray(Charsets.UTF_8).size > 255) throw Socks5Failure("no name to give Tor", p)
            if (Route.isIpLiteral(host)) throw Socks5Failure("an address, not a name, was to be given to Tor", p)
            if (target.port !in 1..65535) throw Socks5Failure("no port", p)
            try {
                super.connect(InetSocketAddress(TOR_HOST, p), timeout)
            } catch (e: IOException) {
                throw Socks5Failure("Tor's SOCKS port $p refused the connection (${e.javaClass.simpleName})", p, e, kind = Socks5Failure.Kind.DIAL)
            }
            val before = soTimeout
            soTimeout = if (timeout > 0) minOf(timeout, handshakeTimeoutMs) else handshakeTimeoutMs
            try {
                handshake(getInputStream(), getOutputStream(), host, target.port, p)
            } catch (e: Socks5Failure) {
                closeQuietly()
                throw e
            } catch (e: IOException) {
                closeQuietly()
                throw Socks5Failure("the SOCKS handshake broke (${e.javaClass.simpleName})", p, e, kind = Socks5Failure.Kind.BROKEN)
            }
            soTimeout = before
        }

        private fun closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handshake(input: InputStream, output: OutputStream, host: String, targetPort: Int, p: Int) {
        // the greeting: version 5, one method, username/password
        output.write(byteArrayOf(VERSION, 1, USER_PASS))
        output.flush()
        val method = readExactly(input, 2, p)
        if (method[0] != VERSION || method[1] != USER_PASS) throw Socks5Failure("Tor did not take a login", p)
        // RFC 1929: the label as username and password
        val user = label.toByteArray(Charsets.UTF_8)
        val auth = ByteArray(3 + 2 * user.size)
        auth[0] = 1
        auth[1] = user.size.toByte()
        user.copyInto(auth, 2)
        auth[2 + user.size] = user.size.toByte()
        user.copyInto(auth, 3 + user.size)
        output.write(auth)
        output.flush()
        val authReply = readExactly(input, 2, p)
        if (authReply[1].toInt() != 0) throw Socks5Failure("Tor refused the login", p)
        // CONNECT, by name
        val name = host.toByteArray(Charsets.UTF_8)
        val request = ByteArray(7 + name.size)
        request[0] = VERSION
        request[1] = CONNECT
        request[2] = 0
        request[3] = DOMAIN
        request[4] = name.size.toByte()
        name.copyInto(request, 5)
        request[5 + name.size] = (targetPort shr 8).toByte()
        request[6 + name.size] = targetPort.toByte()
        output.write(request)
        output.flush()
        val head = readExactly(input, 4, p)
        if (head[0] != VERSION) throw Socks5Failure("not a SOCKS5 reply", p)
        val code = head[1].toInt() and 0xff
        if (code != 0) throw Socks5Failure("Tor could not open the stream: ${replyWords(code)}", p, code = code, kind = Socks5Failure.Kind.UNREACHABLE)
        val rest = when (head[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> readExactly(input, 1, p)[0].toInt() and 0xff
            else -> throw Socks5Failure("a reply with an unknown address type", p)
        }
        readExactly(input, rest + 2, p)
    }

    private fun readExactly(input: InputStream, n: Int, p: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw Socks5Failure("Tor closed the SOCKS connection", p, kind = Socks5Failure.Kind.BROKEN)
            got += r
        }
        return out
    }
}

/**
 * Anything that went wrong before Tor granted the stream: no request byte has
 * been written. `port`: the SOCKS port that was dialled. `code`: Tor's reply
 * code, when it answered one. `kind`: which step failed, in OnionPost.swift's
 * terms (its `Failure`), so a payment posted to an onion is told what iOS
 * tells it.
 */
class Socks5Failure(message: String, val port: Int, cause: Throwable? = null, val code: Int = 0, val kind: Kind = Kind.REFUSED) : IOException(message, cause) {
    enum class Kind {
        /** Nothing answered on Tor's SOCKS port: iOS's `noTor`. */
        DIAL,

        /** Tor answered, but not with what SOCKS5 asks (or refused the login): `refusedBySocks`. */
        REFUSED,

        /** Tor refused the stream with a reply code ([code]): `unreachable`. */
        UNREACHABLE,

        /** The connection to Tor closed or failed mid-handshake: `broken`. */
        BROKEN,
    }
}

/**
 * The name, kept, and nothing looked up: OkHttp needs an address to hand the
 * socket factory, and [Socks5SocketFactory] ignores it and gives Tor the name
 * (S10). The placeholder is 0.0.0.0; `getByAddress` with a name does no lookup.
 */
object TorDns : Dns {
    private val PLACEHOLDER = byteArrayOf(0, 0, 0, 0)

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isEmpty()) throw UnknownHostException("no name")
        return listOf(InetAddress.getByAddress(hostname, PLACEHOLDER))
    }
}
