package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.debug.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Tor circuit a request leaves on (Route.swift:16-26, `MintCircuit`).
 *
 * The page labels each job (a payment, a receive, one invoice's polls) with
 * 16-64 lowercase hex, and Tor keeps different labels on different circuits
 * (IsolateSOCKSAuth: the label is the SOCKS username and password). Anything
 * else, a missing label included, gets 16 fresh random bytes: a circuit of its
 * own, never a shared one.
 */
object MintCircuit {
    private val random = SecureRandom()

    fun label(value: Any?): String {
        val s = value as? String
        if (s != null && s.length in 16..64 && s.all { it in '0'..'9' || it in 'a'..'f' }) return s
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * A URL as Route checks and sends it (OkHttp's parsed URL). The name lets code
 * outside network/ carry one without touching the HTTP client
 * (tools/check-no-network.sh).
 */
typealias RouteUrl = HttpUrl

/** What Route needs to know of Tor: whether it carries requests now, and its SOCKS port (0 while unknown). */
interface TorRoute {
    val isRunning: Boolean
    val socksPort: Int
}

/**
 * The one route decision for every native request (docs/ROUTE.md §2;
 * Foxy/Network/Route.swift:38-513).
 *
 * Tor, if it is up. The open connection, if the person chose to continue
 * without Tor this session (a native dialog, [unprotected]). Otherwise nowhere:
 * [start] answers null with nothing sent, and every caller answers [REFUSAL].
 * Tor connecting clears the unprotected choice.
 *
 * The URL rules ([hostProblem], [urlProblem], [redirectProblem]), the answer cap
 * and the headers are iOS's, ported with RouteTests.swift. The HTTP client is
 * OkHttp, built only here and in [RouteHttp] (tools/check-no-network.sh).
 *
 * The door: while Foxy is being put away nothing new may leave, for
 * [SHUT_FOR_MS] at a time, and a return opens it (Route.swift:130-176).
 */
object Route {
    const val REFUSAL = "Foxy is not connected to Tor."
    const val NOT_A_MINT = "That is not a mint address Foxy will use."
    const val ONION_NEEDS_TOR = "An .onion address needs Tor, and Foxy is not connected to Tor."
    const val REDIRECT_ELSEWHERE = "A redirect to another host is not followed."

    /** The largest answer any request may collect (Route.swift:55, audit I6). */
    const val LARGEST_ANSWER = 4 * 1024 * 1024

    /**
     * Tor Browser's desktop values, the largest crowd on Tor (Route.swift:62-65):
     * no app name, Android or OkHttp version on the wire.
     */
    val GENERIC_HEADERS: Map<String, String> = linkedMapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:140.0) Gecko/20100101 Firefox/140.0",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    /** The suffixes of names on the phone's own network (Route.swift:298). */
    val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".localdomain", ".internal", ".lan", ".home", ".home.arpa", ".corp")

    /** How long the door stays shut at a time. */
    const val SHUT_FOR_MS: Long = 5_000

    /** How long money (a swap, a melt, the change top-up) is waited for when Foxy is put away. */
    const val WAIT_FOR_MONEY_MS: Long = 20_000

    /** How long anything else is waited for once no money is moving. */
    const val WAIT_FOR_THE_REST_MS: Long = 3_000

    private object NoTor : TorRoute {
        override val isRunning = false
        override val socksPort = 0
    }

    /** Tor as Route sees it: set once by the Tor service (tor/Tor.kt). */
    @Volatile var tor: TorRoute = NoTor

    /**
     * Session only: set by CONTINUE WITHOUT TOR after a native dialog's yes,
     * cleared when Tor connects or the page turns it off. In memory, never
     * written anywhere (Route.swift:42-46, S11). Clearing it drops the open
     * connection's client.
     */
    var unprotected: Boolean
        get() = unprotectedFlag.get()
        set(value) {
            unprotectedFlag.set(value)
            if (!value) RouteHttp.dropClear()
        }
    private val unprotectedFlag = AtomicBoolean(false)

    /** Whether a request would go anywhere right now: Tor, or the open connection the person chose. */
    val available: Boolean get() = tor.isRunning || unprotected

    // MARK: The door, and what is out

    /** Requests out now: from [start] until their answer is handed back. */
    val out: Int get() = traffic.get()
    private val traffic = AtomicInteger(0)

    @Volatile internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private val shutUntil = AtomicLong(Long.MIN_VALUE)

    fun shut(now: Long = clock()): Boolean = now < shutUntil.get()

    fun shutDoor(now: Long = clock()) = shutUntil.set(now + SHUT_FOR_MS)

    fun openDoor() = shutUntil.set(Long.MIN_VALUE)

    /**
     * One request, on the route as it is at this moment; null, with nothing
     * sent, when there is none (the door is shut, or neither Tor nor the
     * person's yes). Tor's client is made for the request's circuit label with
     * Tor's SOCKS port as it is when the socket connects; the open connection's
     * only while [unprotected] is set.
     */
    fun start(request: RouteRequest, circuit: String, done: (RouteAnswer) -> Unit): RouteCall? {
        if (shut()) {
            Log.line("[foxy] route: a request to ${request.url.host} not sent; Foxy is being put away")
            return null
        }
        val call = when {
            tor.isRunning -> RouteHttp.torCall(request, circuit)
            unprotected -> RouteHttp.clearCall(request)
            else -> return null
        }
        traffic.incrementAndGet()
        val once = AtomicBoolean(false)
        call.start { answer ->
            if (once.compareAndSet(false, true)) {
                traffic.updateAndGet { maxOf(0, it - 1) }
                done(answer)
            }
        }
        return call
    }

    /** What putting Foxy away does next: wait, or leave the network after a moment. */
    sealed class Leaving {
        object Wait : Leaving() {
            override fun toString() = "Wait"
        }

        data class Leave(val afterMs: Long) : Leaving()
    }

    /**
     * The rule for putting Foxy away (Route.swift `leaving`). `money`: swaps and
     * payments not answered; `tidying`: the page's change top-up; `out`: every
     * request not answered; `waited`: since Foxy was put away; `onTheRest`: how
     * long of that has been for requests that move no money.
     */
    fun leaving(money: Int, tidying: Boolean, out: Int, waitedMs: Long, onTheRestMs: Long): Leaving {
        if (waitedMs > WAIT_FOR_MONEY_MS) return Leaving.Leave(if (money > 0 || tidying || out > 0) 0 else 500)
        if (money > 0 || tidying) return Leaving.Wait
        if (out > 0) return if (onTheRestMs > WAIT_FOR_THE_REST_MS) Leaving.Leave(0) else Leaving.Wait
        return Leaving.Leave(500)
    }

    // MARK: Which addresses Foxy will ask for

    /** A URL as the page gave it, parsed; null when it is not an http(s) URL at all. */
    fun parse(raw: String): HttpUrl? = raw.toHttpUrlOrNull()

    /** The host as the rules see it: lowercased, brackets and edge dots off. */
    fun normalHost(host: String?): String = (host ?: "").lowercase().trim('[', ']', '.')

    /**
     * Hosts Foxy will not ask for, whoever supplied the address (Route.swift:287-309):
     * `localhost`, an IPv6 literal, a single label, an IPv4 literal in any form
     * `inet_aton` takes (`127.1`, `0x7f.1`, `2130706433`), the local-network
     * suffixes, and a `.onion` unless Tor runs.
     */
    fun hostProblem(host: String?, torRunning: Boolean = tor.isRunning): String? {
        val h = normalHost(host)
        if (h.isEmpty() || h == "localhost") return NOT_A_MINT
        if (h.contains(':')) return NOT_A_MINT
        if (!h.contains('.')) return NOT_A_MINT
        if (isIPv4Literal(h)) return NOT_A_MINT
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return NOT_A_MINT
        if (h.endsWith(".onion")) return if (torRunning) null else ONION_NEEDS_TOR
        return null
    }

    /** https, or http only to a `.onion`, and a host that passes [hostProblem] (Route.swift:318-324). */
    fun urlProblem(raw: String?, torRunning: Boolean = tor.isRunning): String? {
        val url = raw?.let { parse(it) } ?: return NOT_A_MINT
        return urlProblem(url, torRunning)
    }

    fun urlProblem(url: HttpUrl, torRunning: Boolean = tor.isRunning): String? {
        val host = normalHost(url.host)
        if (!(url.scheme == "https" || (url.scheme == "http" && host.endsWith(".onion")))) return NOT_A_MINT
        return hostProblem(url.host, torRunning)
    }

    /**
     * Every redirect meets the same rules as the first URL, and stays on the
     * host that was asked (Route.swift:346-353).
     */
    fun redirectProblem(original: HttpUrl?, target: HttpUrl, torRunning: Boolean = tor.isRunning): String? {
        urlProblem(target, torRunning)?.let { return it }
        val from = normalHost(original?.host)
        if (from.isEmpty() || from != normalHost(target.host)) return REDIRECT_ELSEWHERE
        return null
    }

    /**
     * Whether `inet_aton` would read this as an IPv4 address: one to four parts
     * split by dots, each decimal, octal (a leading 0) or hex (0x), the last
     * filling the bytes the others did not, every value in range. Kotlin has no
     * inet_aton, so this is its grammar, ported.
     */
    fun isIPv4Literal(host: String): Boolean {
        if (host.isEmpty()) return false
        val parts = host.split('.')
        if (parts.size > 4) return false
        val values = parts.map { inetPart(it) ?: return false }
        for (i in 0 until values.size - 1) if (values[i] > 0xff) return false
        val lastMost = when (values.size) {
            1 -> 0xffffffffL
            2 -> 0xffffffL
            3 -> 0xffffL
            else -> 0xffL
        }
        return values.last() <= lastMost
    }

    /** One part of an inet_aton address, or null when it is not one (or past 2^32). */
    private fun inetPart(part: String): Long? {
        if (part.isEmpty() || !part[0].isAsciiDigit()) return null
        var i = 0
        var base = 10
        if (part[0] == '0') {
            i = 1
            base = 8
            if (part.length > 1 && (part[1] == 'x' || part[1] == 'X')) {
                base = 16
                i = 2
            }
        }
        var value = 0L
        while (i < part.length) {
            val c = part[i]
            val digit = when {
                c.isAsciiDigit() -> {
                    if (base == 8 && c > '7') return null
                    c - '0'
                }
                base == 16 && c in 'a'..'f' -> c - 'a' + 10
                base == 16 && c in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            value = value * base + digit
            if (value > 0xffffffffL) return null
            i++
        }
        return value
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** An IPv4 literal (any inet_aton form) or an IPv6 one: never handed to Tor as a name. */
    fun isIpLiteral(host: String): Boolean {
        val h = host.trim('[', ']')
        return h.contains(':') || isIPv4Literal(h)
    }
}
