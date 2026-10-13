package io.islandbitcoin.foxy.network

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The relay WebSocket (RouteSocket, Route.swift `startSocket`): Tor or nothing,
 * wss only, a host Route would ask for, not while the door is shut; and on the
 * wire, OkHttp's WebSocket behind the fake SOCKS server, the circuit label as
 * the login and the relay's name handed to Tor (ATYP 3), a message each way.
 */
class RouteSocketTest {
    private val relay = MockWebServer()
    private lateinit var socks: FakeSocks
    private val before = Route.tor
    private val beforeUnprotected = Route.unprotected

    private fun tor(running: Boolean) {
        Route.tor = object : TorRoute {
            override val isRunning = running
            override val socksPort get() = if (running) socks.port else 0
        }
    }

    @Before
    fun up() {
        relay.start()
        socks = FakeSocks(relay.port)
        tor(true)
        Route.openDoor()
        RouteHttp.forgetTorConnections()
    }

    @After
    fun down() {
        Route.tor = before
        Route.unprotected = beforeUnprotected
        Route.openDoor()
        RouteHttp.forgetTorConnections()
        socks.close()
        relay.shutdown()
    }

    /** What a socket told its holder, in order. */
    private class Heard : RelayListener {
        val events = LinkedBlockingQueue<String>()
        override fun opened() {
            events.add("opened")
        }

        override fun text(message: String) {
            events.add("text:$message")
        }

        override fun ended(why: String) {
            events.add("ended:$why")
        }

        fun next(): String = events.poll(20, TimeUnit.SECONDS) ?: error("nothing within 20 s")
    }

    private val nobody = Heard()

    @Test
    fun onlyWssToAHostRouteWouldAskFor() {
        assertNull(RouteSocket.problem("wss://relay.damus.io"))
        assertNull(RouteSocket.problem("wss://relay.minibits.cash/"))
        assertNull(RouteSocket.problem("wss://nos.lol:443/path?x=1"))
        for (bad in listOf(
            "ws://relay.damus.io", "https://relay.damus.io", "WSS://relay.damus.io", "wss://", "wss://localhost",
            "wss://127.0.0.1", "wss://2130706433", "wss://[::1]", "wss://relay", "wss://printer.local", "wss://a b.example",
            "wss://user@relay.example\u0000", "relay.damus.io",
        )) {
            assertNotNull(bad, RouteSocket.problem(bad))
            assertNull(bad, RouteSocket.start(bad, "0123456789abcdef", nobody))
        }
        // an onion relay only while Tor runs, and then over Tor like the rest
        assertNull(RouteSocket.problem("wss://relayrelayrelay.onion", torRunning = true))
        assertEquals(Route.ONION_NEEDS_TOR, RouteSocket.problem("wss://relayrelayrelay.onion", torRunning = false))
        assertEquals("nothing reached Tor", 0, socks.connections.get())
    }

    @Test
    fun noSocketWithoutTorEvenWithContinueWithoutTorOn() {
        tor(false)
        Route.unprotected = true
        assertNull(RouteSocket.start("wss://relay.damus.io", "0123456789abcdef", nobody))
        assertEquals(0, socks.connections.get())
    }

    @Test
    fun noSocketWhileTheDoorIsShut() {
        Route.shutDoor()
        assertNull(RouteSocket.start("wss://relay.damus.io", "0123456789abcdef", nobody))
        assertEquals(0, socks.connections.get())
    }

    @Test
    fun aRelayOverTorByNameOnTheJobsCircuitAMessageEachWay() {
        val server = LinkedBlockingQueue<String>()
        relay.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                server.add(text)
                webSocket.send("[\"OK\",\"x\",true,\"\"]")
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                server.add("open")
            }
        }))
        val heard = Heard()
        val label = "00112233445566778899aabbccddeeff"
        // a name, never an address: ws:// for the mock relay, which speaks no TLS; RouteSocket.start takes wss only
        val socket = RouteSocket.open(RouteHttp.torClient(label), "ws://relayrelay.example:${relay.port}/", heard)
        assertTrue(socket.send("[\"REQ\",\"sub\",{}]"))
        assertEquals("opened", heard.next())
        assertEquals("open", server.poll(20, TimeUnit.SECONDS))
        assertEquals("[\"REQ\",\"sub\",{}]", server.poll(20, TimeUnit.SECONDS))
        assertEquals("text:[\"OK\",\"x\",true,\"\"]", heard.next())
        val hs = socks.handshakes.single()
        assertEquals("the relay's name goes to Tor (ATYP 3)", 3, hs.atyp)
        assertEquals("relayrelay.example", hs.host)
        assertEquals(relay.port, hs.port)
        assertEquals("the circuit label is the login", label, hs.user)
        assertEquals(label, hs.pass)
        // the upgrade carries the generic headers, nothing of the app's
        val upgrade = relay.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(Route.GENERIC_HEADERS["User-Agent"], upgrade.getHeader("User-Agent"))
        assertEquals("websocket", upgrade.getHeader("Upgrade"))
        socket.close()
        assertTrue("closed by its holder: nothing more is heard", heard.events.poll(500, TimeUnit.MILLISECONDS) == null)
        assertTrue(!socket.send("late"))
    }

    @Test
    fun aRelayTorRefusesIsEndedWithRoutesWords() {
        socks.refuseNext = 1
        socks.refuseWith = 4
        val heard = Heard()
        RouteSocket.open(RouteHttp.torClient("0123456789abcdef"), "ws://relayrelay.example:${relay.port}/", heard)
        assertEquals("ended:" + Route.REFUSAL, heard.next())
    }

    @Test
    fun aRelayThatWillNotUpgradeIsEndedWithItsStatus() {
        relay.enqueue(MockResponse().setResponseCode(503))
        val heard = Heard()
        RouteSocket.open(RouteHttp.torClient("0123456789abcdef"), "ws://relayrelay.example:${relay.port}/", heard)
        assertEquals("ended:the relay answered 503", heard.next())
    }
}
