package io.islandbitcoin.foxy.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * S12 on the real client: OkHttp behind the fake SOCKS server, a MockWebServer
 * as the mint. Answers are capped while they arrive and refused up front by
 * their Content-Length; a redirect to another host or onto the local network
 * is not followed; only the allowed headers and the generic ones leave; no
 * cookies; two circuit labels never share a connection; a request that reached
 * the server is never sent again by the client.
 */
class RouteClientTest {
    private val mint = MockWebServer()
    private lateinit var socks: FakeSocks
    private val before = Route.tor

    /** A name, never an address: Tor is handed names (the fake joins every one to the mock mint). */
    private fun url(path: String) = Route.parse("http://mintmintmintmint.onion:${mint.port}$path")!!

    @Before
    fun up() {
        mint.start()
        socks = FakeSocks(mint.port)
        Route.tor = object : TorRoute {
            override val isRunning = true
            override val socksPort get() = socks.port
        }
        RouteHttp.forgetTorConnections()
    }

    @After
    fun down() {
        Route.tor = before
        RouteHttp.forgetTorConnections()
        socks.close()
        mint.shutdown()
    }

    private fun send(request: RouteRequest, label: String = "0123456789abcdef"): RouteAnswer {
        val got = AtomicReference<RouteAnswer>()
        val done = CountDownLatch(1)
        RouteHttp.torCall(request, label).start {
            got.set(it)
            done.countDown()
        }
        assertTrue("an answer within 20 s", done.await(20, TimeUnit.SECONDS))
        return got.get()
    }

    private fun bytes(n: Int) = Buffer().write(ByteArray(n) { 'a'.code.toByte() })

    /**
     * Tor dropped between the URL check and the send, with the person's
     * CONTINUE WITHOUT TOR set: the request falls to the open connection,
     * whose resolver refuses an onion (S11 "never for onion") before any
     * lookup or socket. Without that refusal the name would go to the phone's
     * resolver in the clear.
     */
    private fun sendOnionUnprotected(host: String) {
        Route.tor = object : TorRoute {
            override val isRunning = false
            override val socksPort = 0
        }
        Route.openDoor()
        Route.unprotected = true
        try {
            val url = Route.parse("http://$host:${mint.port}/v1/keys")!!
            val got = AtomicReference<RouteAnswer>()
            val done = CountDownLatch(1)
            val call = Route.start(RouteRequest(url), "0123456789abcdef") {
                got.set(it)
                done.countDown()
            }
            assertTrue("the open connection took the request", call != null)
            assertTrue("an answer within 20 s", done.await(20, TimeUnit.SECONDS))
            val a = got.get()
            assertTrue("failed, not answered: $a", a is RouteAnswer.Failed)
            a as RouteAnswer.Failed
            assertTrue("the resolver's refusal: ${a.error}", a.error is java.net.UnknownHostException)
            assertEquals("an onion needs Tor", a.error.message)
            assertFalse("not a SOCKS handshake failure", a.beforeRequest)
            assertEquals("no socket reached the server", 0, mint.requestCount)
            assertEquals("no SOCKS handshake either", 0, socks.connections.get())
        } finally {
            Route.unprotected = false
        }
    }

    @Test
    fun theOpenConnectionRefusesAnOnionWhenTorDroppedBeforeTheSend() {
        sendOnionUnprotected("abcdefghijklmnop.onion")
    }

    @Test
    fun theOpenConnectionRefusesAnOnionWithATrailingDot() {
        sendOnionUnprotected("abcdefghijklmnop.onion.")
    }

    @Test
    fun anAnswerOfFiveMegabytesIsCutOff() {
        mint.enqueue(MockResponse().setChunkedBody(bytes(5 * 1024 * 1024), 64 * 1024))
        assertEquals(RouteAnswer.TooLarge, send(RouteRequest(url("/v1/keys"))))
    }

    @Test
    fun aContentLengthOverTheCapIsRefusedBeforeTheBody() {
        // a body that would take minutes to arrive: refused at once on its length
        mint.enqueue(MockResponse().setBody(bytes(Route.LARGEST_ANSWER + 1)).throttleBody(1024, 1, TimeUnit.SECONDS))
        val began = System.nanoTime()
        assertEquals(RouteAnswer.TooLarge, send(RouteRequest(url("/v1/keys"))))
        assertTrue("refused up front, not after reading", System.nanoTime() - began < 5_000_000_000)
    }

    @Test
    fun anAnswerOfExactlyTheCapIsKept() {
        mint.enqueue(MockResponse().setBody(bytes(Route.LARGEST_ANSWER)))
        val a = send(RouteRequest(url("/v1/keys")))
        assertTrue(a is RouteAnswer.Done)
        assertEquals(Route.LARGEST_ANSWER, (a as RouteAnswer.Done).body.size)
    }

    @Test
    fun aRedirectToAnotherHostIsNotFollowed() {
        mint.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://paypaypaypaypay.onion:${mint.port}/x"))
        val a = send(RouteRequest(url("/v1/keys")))
        assertEquals("the redirect itself is the answer", 302, (a as RouteAnswer.Done).status)
        assertEquals("nothing was sent to the other host", 1, mint.requestCount)
    }

    @Test
    fun aRedirectOntoTheLocalNetworkIsNotFollowed() {
        for (where in listOf("http://192.168.1.1/", "https://192.168.1.1/", "https://router/", "https://printer.local/x", "file:///etc/hosts")) {
            mint.enqueue(MockResponse().setResponseCode(301).setHeader("Location", where))
            val a = send(RouteRequest(url("/v1/keys")))
            assertEquals(where, 301, (a as RouteAnswer.Done).status)
        }
        assertEquals(5, mint.requestCount)
    }

    @Test
    fun aRedirectOnTheSameHostIsFollowedAndAPostBecomesAGet() {
        mint.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/v1/keysets"))
        mint.enqueue(MockResponse().setBody("{\"keysets\":[]}"))
        val a = send(RouteRequest(url("/v1/keys"), "POST", mapOf("Content-Type" to "application/json"), "{}".toByteArray()))
        assertEquals(200, (a as RouteAnswer.Done).status)
        assertEquals("{\"keysets\":[]}", a.text)
        assertEquals("POST", mint.takeRequest().method)
        val second = mint.takeRequest()
        assertEquals("GET", second.method)
        assertEquals("/v1/keysets", second.path)
    }

    @Test
    fun aMethodOtherThanGetOrPostCannotBeMade() {
        for (m in listOf("PUT", "DELETE", "PATCH", "get", "")) {
            try {
                RouteRequest(url("/"), m)
                fail("$m was made")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun onlyTheGivenHeadersAndTheGenericOnesLeave() {
        mint.enqueue(MockResponse().setBody("ok"))
        send(RouteRequest(url("/v1/swap"), "POST", mapOf("Content-Type" to "application/json", "Accept" to "application/json"), "{}".toByteArray()))
        val r = mint.takeRequest()
        val names = r.headers.names().map { it.lowercase() }.toSet()
        // OkHttp's own transport headers are the only others
        val allowed = setOf("content-type", "accept", "user-agent", "accept-language", "host", "connection", "accept-encoding", "content-length")
        assertTrue("unexpected headers: ${names - allowed}", allowed.containsAll(names))
        assertEquals(Route.GENERIC_HEADERS["User-Agent"], r.getHeader("User-Agent"))
        assertEquals(Route.GENERIC_HEADERS["Accept-Language"], r.getHeader("Accept-Language"))
        assertFalse(r.getHeader("User-Agent")!!.contains("okhttp"))
        assertEquals("application/json", r.getHeader("Content-Type"))
    }

    @Test
    fun noCookieIsKeptOrSent() {
        mint.enqueue(MockResponse().setBody("a").addHeader("Set-Cookie", "who=you; Path=/"))
        mint.enqueue(MockResponse().setBody("b"))
        send(RouteRequest(url("/a")))
        send(RouteRequest(url("/b")))
        mint.takeRequest()
        assertNull(mint.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun twoLabelsOpenTwoConnectionsAndOneLabelKeepsItsOwn() {
        repeat(4) { mint.enqueue(MockResponse().setBody("x")) }
        send(RouteRequest(url("/1")), "1111111111111111")
        send(RouteRequest(url("/2")), "1111111111111111")
        assertEquals("one label: its connection is kept", 1, socks.connections.get())
        send(RouteRequest(url("/3")), "2222222222222222")
        assertEquals("another label: never the first one's connection", 2, socks.connections.get())
        assertEquals(listOf("1111111111111111", "2222222222222222"), socks.handshakes.map { it.user })
    }

    @Test
    fun aPostThatReachedTheServerIsNeverSentAgainByTheClient() {
        mint.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        mint.enqueue(MockResponse().setBody("must not be asked"))
        val a = send(RouteRequest(url("/v1/melt/bolt11"), "POST", mapOf("Content-Type" to "application/json"), "{\"quote\":\"q\"}".toByteArray()))
        assertTrue(a is RouteAnswer.Failed)
        assertFalse("its bytes left: not a handshake failure", (a as RouteAnswer.Failed).beforeRequest)
        Thread.sleep(300)
        assertEquals("the melt reached the mint once", 1, mint.requestCount)
    }

    @Test
    fun aRefusedHandshakeIsTheOneFailureBeforeTheRequest() {
        socks.refuseNext = 1
        val a = send(RouteRequest(url("/v1/swap"), "POST", emptyMap(), "{}".toByteArray()))
        assertTrue((a as RouteAnswer.Failed).beforeRequest)
        assertEquals(socks.port, a.dialled)
        assertEquals("nothing reached the mint", 0, mint.requestCount)
    }

    @Test
    fun aHandshakeFailureAfterARedirectIsNotBeforeTheRequest() {
        // the first hop reached the mint and was answered 307 on a closed connection; Tor then refuses the
        // second hop's stream. The POST has left once, so the caller must not be told "nothing was sent".
        mint.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                socks.refuseNext = 1
                return MockResponse().setResponseCode(307).setHeader("Location", "/v1/swap2").setHeader("Connection", "close")
            }
        }
        val a = send(RouteRequest(url("/v1/swap"), "POST", emptyMap(), "{}".toByteArray()))
        assertTrue(a is RouteAnswer.Failed)
        assertFalse("a later hop's failure is never 'before the request'", (a as RouteAnswer.Failed).beforeRequest)
        assertEquals("the swap reached the mint once", 1, mint.requestCount)
        assertEquals("and the second hop was refused by Tor", 2, socks.handshakes.size)
    }
}
