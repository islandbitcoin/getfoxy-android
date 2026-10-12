package io.islandbitcoin.foxy.network

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * S10, S27: the SOCKS factory gives Tor a name, never an address, logs in with
 * the circuit label, dials port 9 while Tor's port is unknown, and fails before
 * any request byte when the handshake does; TorDns keeps the name and looks
 * nothing up.
 */
class Socks5SocketFactoryTest {
    private val socks = FakeSocks()
    private val target = ServerSocket(0)

    init {
        socks.forwardTo = target.localPort
    }

    @After
    fun close() {
        socks.close()
        target.close()
    }

    private fun connect(factory: Socks5SocketFactory, host: String, port: Int) =
        factory.createSocket().also { it.connect(InetSocketAddress(TorDns.lookup(host).single(), port), 5_000) }

    @Test
    fun torIsGivenTheNameWithAddressTypeThreeAndTheLabelAsTheLogin() {
        val label = "0123456789abcdef0123456789abcdef"
        val factory = Socks5SocketFactory(label, { socks.port })
        connect(factory, "mint.minibits.cash", 443).close()
        val h = socks.handshakes.single()
        assertEquals("ATYP 0x03: a name", 3, h.atyp)
        assertEquals("mint.minibits.cash", h.host)
        assertEquals(443, h.port)
        assertEquals(label, h.user)
        assertEquals(label, h.pass)
    }

    @Test
    fun anOnionAndAnUnresolvableNameGoAsTheyAre() {
        val factory = Socks5SocketFactory("aaaaaaaaaaaaaaaa", { socks.port })
        connect(factory, "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion", 80).close()
        connect(factory, "no-such-host.invalid", 443).close()
        assertEquals(
            listOf("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion", "no-such-host.invalid"),
            socks.handshakes.map { it.host },
        )
        assertTrue(socks.handshakes.all { it.atyp == 3 })
    }

    @Test
    fun twoLabelsAreTwoLogins() {
        connect(Socks5SocketFactory("1111111111111111", { socks.port }), "a.example.com", 443).close()
        connect(Socks5SocketFactory("2222222222222222", { socks.port }), "a.example.com", 443).close()
        assertEquals(listOf("1111111111111111", "2222222222222222"), socks.handshakes.map { it.user })
    }

    @Test
    fun torIsDialledOnIPv4LoopbackWhereItListens() {
        assertArrayEquals("127.0.0.1, never ::1 (Android's getLoopbackAddress)", byteArrayOf(127, 0, 0, 1), Socks5SocketFactory.TOR_HOST.address)
        val s = connect(Socks5SocketFactory("aaaaaaaaaaaaaaaa", { socks.port }), "mint.minibits.cash", 443)
        assertEquals("127.0.0.1", s.inetAddress.hostAddress)
        s.close()
    }

    @Test
    fun torDnsKeepsTheNameAndLooksNothingUp() {
        val began = System.nanoTime()
        val a = TorDns.lookup("this-name-does-not-exist.invalid").single()
        assertTrue("no resolver was asked: it answered at once", System.nanoTime() - began < 50_000_000)
        assertArrayEquals("the placeholder", byteArrayOf(0, 0, 0, 0), a.address)
        assertEquals("this-name-does-not-exist.invalid", InetSocketAddress(a, 443).hostString)
        try {
            TorDns.lookup("")
            fail("an empty name")
        } catch (e: java.net.UnknownHostException) {
        }
    }

    @Test
    fun whileTorsPortIsUnknownTheSocketDialsPortNineAndFails() {
        val factory = Socks5SocketFactory("aaaaaaaaaaaaaaaa", { 0 })
        assertEquals(Socks5SocketFactory.DEAD_PORT, factory.portNow())
        assertEquals(9, Socks5SocketFactory("aaaaaaaaaaaaaaaa", { 70_000 }).portNow())
        try {
            connect(factory, "mint.minibits.cash", 443)
            fail("port 9 serves nothing")
        } catch (e: Socks5Failure) {
            assertEquals(9, e.port)
        }
    }

    @Test
    fun aRefusedStreamFailsBeforeAnyRequestByte() {
        socks.refuseNext = 1
        socks.refuseWith = 4
        val factory = Socks5SocketFactory("aaaaaaaaaaaaaaaa", { socks.port })
        try {
            connect(factory, "mint.minibits.cash", 443)
            fail("Tor refused the stream")
        } catch (e: Socks5Failure) {
            assertEquals(4, e.code)
            assertEquals(socks.port, e.port)
            assertTrue(e.message!!.contains("host unreachable"))
        }
        // nothing reached the target: the request could not have left
        target.soTimeout = 300
        try {
            target.accept().close()
            fail("the target was reached")
        } catch (e: java.net.SocketTimeoutException) {
        }
    }

    @Test
    fun aListenerThatClosesMidHandshakeIsAHandshakeFailure() {
        socks.dropNext = 1
        try {
            connect(Socks5SocketFactory("aaaaaaaaaaaaaaaa", { socks.port }), "mint.minibits.cash", 443)
            fail("the listener closed")
        } catch (e: Socks5Failure) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun anAddressIsNeverHandedToTor() {
        val factory = Socks5SocketFactory("aaaaaaaaaaaaaaaa", { socks.port })
        for (literal in listOf("127.0.0.1", "10.0.0.1", "::1")) {
            try {
                factory.createSocket().connect(InetSocketAddress.createUnresolved(literal, 443), 2_000)
                fail("$literal was handed on")
            } catch (e: Socks5Failure) {
            }
        }
        assertEquals("no connection to Tor at all", 0, socks.connections.get())
    }

    @Test
    fun aLabelIsOneTo255Bytes() {
        for (bad in listOf("", "x".repeat(256))) {
            try {
                Socks5SocketFactory(bad, { 9 })
                fail("'${bad.take(8)}' was taken")
            } catch (e: IllegalArgumentException) {
            }
        }
        Socks5SocketFactory("x".repeat(255), { 9 })
    }

    @Test
    fun eachFactoryIsItsOwn() {
        val a = Socks5SocketFactory("aaaaaaaaaaaaaaaa", { 1 })
        val b = Socks5SocketFactory("aaaaaaaaaaaaaaaa", { 1 })
        assertTrue("two factories for one label are still two (identity)", a != b)
        assertEquals(a, a)
    }
}
