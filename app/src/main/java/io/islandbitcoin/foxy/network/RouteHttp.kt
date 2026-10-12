package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.debug.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** One request as Route sends it: the URL (already checked), GET or POST, the only headers kept, the body. */
class RouteRequest(
    val url: HttpUrl,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** The whole exchange, every redirect included. */
    val timeoutMs: Long = 60_000,
) {
    init {
        require(method == "GET" || method == "POST") { "GET or POST only" }
    }
}

/** How a request ended. */
sealed class RouteAnswer {
    /** The last hop's status and its body, collected to at most [Route.LARGEST_ANSWER]. */
    class Done(val status: Int, val body: ByteArray, val url: HttpUrl) : RouteAnswer() {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    /** The answer was larger than the cap: refused up front by its Content-Length, or stopped while it arrived. */
    object TooLarge : RouteAnswer()

    /**
     * It failed. `beforeRequest`: the failure was Tor's SOCKS handshake, so no
     * byte of the request left (the one case that is retried). `dialled`: the
     * SOCKS port that handshake used (0 for the open connection, or a failure
     * after the handshake); the retry compares it with the first try's to tell
     * a rebuilt Tor from a Tor that is down ([RouteHttp.failureWords]).
     */
    class Failed(val error: IOException, val beforeRequest: Boolean, val dialled: Int) : RouteAnswer()
}

/**
 * One request on its way: OkHttp, with redirects followed here (each hop
 * checked with [Route.redirectProblem]) and the answer collected up to the cap.
 * OkHttp's own retry is off (`retryOnConnectionFailure(false)`): a request is
 * sent once, and only the caller decides to send it again.
 */
class RouteCall internal constructor(private val client: OkHttpClient, private val request: RouteRequest, private val cap: Int) {
    @Volatile private var current: Call? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        current?.cancel()
    }

    internal fun start(done: (RouteAnswer) -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs)
        hop(request.url, request.method, request.body, 0, deadline, done)
    }

    private fun build(url: HttpUrl, method: String, body: ByteArray?): Request {
        val b = Request.Builder().url(url)
        for ((k, v) in request.headers) b.header(k, v)
        if (method == "POST") b.post((body ?: ByteArray(0)).toRequestBody(null)) else b.get()
        return b.build()
    }

    private fun hop(url: HttpUrl, method: String, body: ByteArray?, hops: Int, deadline: Long, done: (RouteAnswer) -> Unit) {
        if (cancelled) return done(RouteAnswer.Failed(IOException("Canceled"), beforeRequest = false, dialled = 0))
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (left <= 0) return done(RouteAnswer.Failed(InterruptedIOException("timeout"), beforeRequest = false, dialled = 0))
        val call = client.newBuilder().callTimeout(left, TimeUnit.MILLISECONDS).build().newCall(build(url, method, body))
        current = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val socks = generateSequence(e as Throwable) { it.cause }.filterIsInstance<Socks5Failure>().firstOrNull()
                    ?: e.suppressed.filterIsInstance<Socks5Failure>().firstOrNull()
                // only the first hop: after a redirect, bytes of the request have already left once
                done(RouteAnswer.Failed(e, beforeRequest = socks != null && hops == 0, dialled = socks?.port ?: 0))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val location = r.header("Location")
                    if (r.code in REDIRECTS && location != null && hops < MOST_REDIRECTS) {
                        val target = url.resolve(location)
                        val problem = if (target == null) Route.NOT_A_MINT else Route.redirectProblem(request.url, target)
                        if (problem == null && target != null) {
                            // 307 and 308 keep the method and the body; the rest become a GET with none
                            val keep = r.code == 307 || r.code == 308
                            val nextMethod = if (keep) method else "GET"
                            val nextBody = if (keep) body else null
                            r.close()
                            return hop(target, nextMethod, nextBody, hops + 1, deadline, done)
                        }
                        // refused: the redirect response itself is the answer, a 3xx the page reports
                        Log.line("[foxy] redirect refused: ${url.host} -> ${location.take(120)} - $problem")
                    }
                    done(collect(r, url))
                }
            }
        })
    }

    private fun collect(r: Response, url: HttpUrl): RouteAnswer {
        val body = r.body ?: return RouteAnswer.Done(r.code, ByteArray(0), url)
        if (body.contentLength() > cap) {
            Log.line("[foxy] answer refused: more than $cap bytes from ${url.host}")
            return RouteAnswer.TooLarge
        }
        val buffer = Buffer()
        return try {
            val source = body.source()
            while (true) {
                val n = source.read(buffer, CHUNK)
                if (n == -1L) break
                if (buffer.size > cap) {
                    Log.line("[foxy] answer refused: more than $cap bytes from ${url.host}")
                    buffer.clear()
                    return RouteAnswer.TooLarge
                }
            }
            RouteAnswer.Done(r.code, buffer.readByteArray(), url)
        } catch (e: IOException) {
            RouteAnswer.Failed(e, beforeRequest = false, dialled = 0)
        }
    }

    companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MOST_REDIRECTS = 20
        private const val CHUNK = 8192L
    }
}

/**
 * The two clients and nothing else: Tor's (a SOCKS factory and a connection
 * pool per circuit label, [TorDns], no proxy of Java's) and the open
 * connection's (made only while the person has said yes, refusing `.onion`).
 * Both: no cookies, no cache, no redirects or retries of OkHttp's own, 60 s,
 * the generic headers.
 *
 * A pool per label because OkHttp's `Address` does not compare socket
 * factories: with one shared pool, a request on one label was found riding a
 * connection another label had opened (RouteClientTest). Each label's
 * connections live in its own pool and are never handed to another.
 */
object RouteHttp {
    /** One label's way out: its SOCKS login and its own connections. */
    private class Circuit(label: String) {
        val factory = Socks5SocketFactory(label, { torPort() })
        val pool = ConnectionPool(4, 2, TimeUnit.MINUTES)
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(GenericHeaders)
            .build()
    }

    private val circuits = ConcurrentHashMap<String, Circuit>()
    private const val MOST_LABELS = 256

    /** Where Tor's SOCKS port is read from; tests point it at a fake server. */
    @Volatile internal var torPort: () -> Int = { Route.tor.socksPort }

    @Volatile private var clear: OkHttpClient? = null

    private fun circuit(label: String): Circuit {
        if (circuits.size > MOST_LABELS) forgetTorConnections()
        return circuits.getOrPut(label) { Circuit(label) }
    }

    fun factoryFor(label: String): Socks5SocketFactory = circuit(label).factory

    fun torClient(label: String): OkHttpClient {
        val c = circuit(label)
        return base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(TorDns)
            .socketFactory(c.factory)
            .connectionPool(c.pool)
            .build()
    }

    /** The open connection: the system's resolver, never for an onion. Made on first use while unprotected. */
    fun clearClient(): OkHttpClient {
        clear?.let { return it }
        synchronized(this) {
            clear?.let { return it }
            val c = base.newBuilder().dns(ClearDns).build()
            clear = c
            return c
        }
    }

    fun torCall(request: RouteRequest, circuit: String): RouteCall = RouteCall(torClient(circuit), request, Route.LARGEST_ANSWER)

    fun clearCall(request: RouteRequest): RouteCall = RouteCall(clearClient(), request, Route.LARGEST_ANSWER)

    /**
     * Closing a pooled connection writes to its socket (a TLS close), which
     * Android refuses on the main thread (StrictMode's NetworkOnMainThread
     * killed the app the first time the page turned the open connection off).
     * The callers are on the main thread, so the closing happens here.
     */
    private val janitor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "foxy-route-close").apply { isDaemon = true }
    }

    /** The person turned the open connection off, or Tor connected: its client goes now, its connections a moment later. */
    fun dropClear() {
        val c = synchronized(this) { clear.also { clear = null } } ?: return
        janitor.execute { c.connectionPool.evictAll() }
    }

    /** Tor's listener moved or Tor went off the network: no pooled connection to the old one is used. */
    fun forgetTorConnections() {
        val all = circuits.values.toList()
        circuits.clear()
        if (all.isNotEmpty()) janitor.execute { all.forEach { it.pool.evictAll() } }
    }

    /** No app name on the wire: Tor Browser's User-Agent and Accept-Language on every request. */
    private object GenericHeaders : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val b = chain.request().newBuilder()
            for ((k, v) in Route.GENERIC_HEADERS) b.header(k, v)
            return chain.proceed(b.build())
        }
    }

    /** The open connection's resolver: the phone's, except for a name only Tor can resolve. */
    private object ClearDns : Dns {
        override fun lookup(hostname: String) =
            if (hostname.lowercase().trimEnd('.').endsWith(".onion")) throw UnknownHostException("an onion needs Tor") else Dns.SYSTEM.lookup(hostname)
    }

    /** Tor's own refusal of a stream (its SOCKS reply), in words; null when Tor gave none. */
    fun torRefusal(e: Throwable): String? {
        val socks = generateSequence(e) { it.cause }.filterIsInstance<Socks5Failure>().firstOrNull()
            ?: e.suppressed.filterIsInstance<Socks5Failure>().firstOrNull()
            ?: return null
        return if (socks.code == 0) null else Socks5SocketFactory.replyWords(socks.code)
    }

    /** A handshake that failed on one SOCKS port and again on another: Tor's listener moved under the request. */
    const val REBUILT = "Foxy's private connection was rebuilt mid-request. Try again."

    /** Whether a retry's handshake dialled another live SOCKS port than the first try's (never 0 or port 9). */
    fun portMoved(first: Int, retry: Int): Boolean =
        first != 0 && retry != 0 && retry != Socks5SocketFactory.DEAD_PORT && retry != first

    /**
     * What the person is told when a request failed, in the words iOS's
     * URLSession used. A SOCKS handshake that failed twice is Tor's listener
     * moving under the request (FoxyBridge.swift `mintFailureWords`) only when
     * `portMoved`: the retry dialled another port than the first try
     * (ENG-648 plan, "If the port changed mid-request"). Twice on the same port
     * (Tor down, port 9 while it is unknown, or a listener that refuses) is
     * Foxy not being connected to Tor.
     */
    fun failureWords(e: IOException, beforeRequest: Boolean, portMoved: Boolean = false): String = when {
        beforeRequest -> if (portMoved) REBUILT else Route.REFUSAL
        e is InterruptedIOException -> "The request timed out."
        e is UnknownHostException -> "A server with the specified hostname could not be found."
        e is SSLException -> "An SSL error has occurred and a secure connection to the server cannot be made."
        e.message == "Canceled" -> "cancelled"
        else -> "The network connection was lost."
    }
}
