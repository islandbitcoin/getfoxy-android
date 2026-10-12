package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.network.Route.Leaving
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route (S9, S12, S21): with no Tor and no yes nothing is available and every
 * start is refused with the fixed text; the door shuts for 5 s at a time and
 * opens by itself or on a return (injected clock); the putting-away rule; and
 * the URL, host, redirect and label rules. Ported from RouteTests.swift.
 */
class RouteTest {
    private val before = Route.tor

    @After
    fun open() {
        Route.openDoor()
        Route.clock = { System.nanoTime() / 1_000_000 }
        Route.unprotected = false
        Route.tor = before
    }

    private fun url(s: String) = Route.parse(s)!!

    private fun torRunning(running: Boolean) {
        Route.tor = object : TorRoute {
            override val isRunning = running
            override val socksPort = 0
        }
    }

    @Test
    fun nothingIsAvailableAndEveryRequestIsRefused() {
        assertFalse(Route.available)
        assertEquals("Foxy is not connected to Tor.", Route.REFUSAL)
        var called = false
        assertNull(Route.start(RouteRequest(url("https://mint.minibits.cash/v1/keys")), "aaaaaaaaaaaaaaaa") { called = true })
        assertEquals(0, Route.out)
        Route.shutDoor()
        assertNull(Route.start(RouteRequest(url("https://mint.minibits.cash/v1/keys")), "aaaaaaaaaaaaaaaa") { called = true })
        assertEquals("a request that was refused is not out", 0, Route.out)
        assertFalse("a refused request's answer is never asked for", called)
    }

    @Test
    fun availableIsTorOrThePersonsYesAndNothingElse() {
        torRunning(false)
        assertFalse(Route.available)
        Route.unprotected = true
        assertTrue(Route.available)
        Route.unprotected = false
        torRunning(true)
        assertTrue(Route.available)
    }

    @Test
    fun theDoorRefusesEvenWithARoute() {
        Route.unprotected = true
        Route.shutDoor()
        assertNull(Route.start(RouteRequest(url("https://mint.minibits.cash/v1/keys")), "aaaaaaaaaaaaaaaa") {})
        assertEquals(0, Route.out)
    }

    @Test
    fun mintAddressesAreHttpsOrHttpToAnOnion() {
        torRunning(false)
        assertNull(Route.urlProblem("https://mint.minibits.cash/Bitcoin"))
        assertNull(Route.urlProblem("HTTPS://Mint.Example.com:3338/v1/info"))
        assertNull(Route.urlProblem("https://mint.example.com./v1/keys"))
        for (refused in listOf("http://mint.example.com/", "ftp://abcdef.onion/", "file://abcdef.onion/x",
            "wss://mint.example.com/", "javascript:alert(1)", "mint.example.com", "", "https:///v1")) {
            assertEquals(refused, Route.NOT_A_MINT, Route.urlProblem(refused))
        }
        assertEquals(Route.NOT_A_MINT, Route.urlProblem(null))
    }

    @Test
    fun hostsFoxyWillNotAskFor() {
        for (refused in listOf("https://localhost/", "https://127.0.0.1/", "https://0x7f.1/", "https://127.1/",
            "https://2130706433/", "https://017700000001/", "https://0x7f000001/", "https://127.0.1/",
            "https://192.168.1.1/", "https://10.1/", "https://[::1]/", "https://[fe80::1]/", "https://router/",
            "https://printer.local/", "https://nas.home.arpa/", "https://x.internal/", "https://box.lan/",
            "https://pc.localdomain/", "https://app.localhost/", "https://my.home/", "https://x.corp/",
            "https://127.0.0.1./")) {
            assertNotNull(refused, Route.urlProblem(refused))
        }
        for (fine in listOf("https://1password.com/", "https://mint.0x7f.io/", "https://256.1.1.1.example.com/",
            "https://testnut.cashu.space/", "https://8333.space/", "https://a-b.example.org/")) {
            assertNull(fine, Route.urlProblem(fine))
        }
    }

    @Test
    fun inetAtonsGrammar() {
        for (yes in listOf("127.0.0.1", "127.1", "0x7f.1", "0X7F.0.0.1", "2130706433", "017700000001", "0", "0x", "1.2.3",
            "255.255.255.255", "4294967295", "0xffffffff", "1.16777215", "1.2.65535", "00", "0.0.0.0")) {
            assertTrue(yes, Route.isIPv4Literal(yes))
        }
        for (no in listOf("", "1.", ".1", "1..2", "256.1.1.1", "1.2.3.4.5", "4294967296", "1.16777216", "1.2.65536",
            "08", "09.1", "0xg", "a.b.c.d", "1.2.3.a", "1password", "127.0.0.1x", "-1")) {
            assertFalse(no, Route.isIPv4Literal(no))
        }
    }

    @Test
    fun anOnionNeedsTor() {
        torRunning(false)
        assertEquals("An .onion address needs Tor, and Foxy is not connected to Tor.", Route.urlProblem("http://abcdefghijklmnop.onion/v1/info"))
        Route.unprotected = true
        assertEquals("not even unprotected", Route.ONION_NEEDS_TOR, Route.urlProblem("https://abcdefghijklmnop.onion/v1/info"))
        torRunning(true)
        assertNull(Route.urlProblem("http://abcdefghijklmnop.onion/v1/info"))
    }

    @Test
    fun aRedirectStaysOnItsHostAndMeetsTheSameRules() {
        torRunning(false)
        val from = url("https://mint.example.com/v1/keys")
        assertNull(Route.redirectProblem(from, url("https://mint.example.com/v1/keysets")))
        assertNull(Route.redirectProblem(from, url("https://MINT.example.com./other")))
        assertEquals("A redirect to another host is not followed.", Route.redirectProblem(from, url("https://pay.example.com/")))
        assertNotNull(Route.redirectProblem(from, url("http://mint.example.com/")))
        assertNotNull(Route.redirectProblem(from, url("https://192.168.1.1/")))
        assertNotNull(Route.redirectProblem(null, url("https://mint.example.com/")))
    }

    @Test
    fun thePageLabelIsKeptAndAnythingElseGetsACircuitOfItsOwn() {
        val label = "0123456789abcdef0123456789abcdef"
        assertEquals(label, MintCircuit.label(label))
        val a = MintCircuit.label(null)
        val b = MintCircuit.label("")
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue("two unlabelled requests must not share a circuit", a != b)
        for (bad in listOf("<torS0X>0" + "a".repeat(20), "short", "a".repeat(65), "0123456789ABCDEF0123456789ABCDEF",
            "0123456789abcdef 123456789abcdef")) {
            assertTrue(bad, MintCircuit.label(bad) != bad)
        }
        assertEquals(32, MintCircuit.label(42.0).length)
        assertEquals("16 and 64 are the bounds", "a".repeat(16), MintCircuit.label("a".repeat(16)))
        assertEquals("a".repeat(64), MintCircuit.label("a".repeat(64)))
    }

    @Test
    fun theGenericHeadersAreTorBrowsers() {
        assertEquals("Mozilla/5.0 (Windows NT 10.0; rv:140.0) Gecko/20100101 Firefox/140.0", Route.GENERIC_HEADERS["User-Agent"])
        assertEquals("en-US,en;q=0.5", Route.GENERIC_HEADERS["Accept-Language"])
        assertEquals(4 * 1024 * 1024, Route.LARGEST_ANSWER)
    }

    @Test
    fun theDoorShutsForFiveSecondsAndOpensByItself() {
        assertEquals(5_000L, Route.SHUT_FOR_MS)
        assertFalse(Route.shut(100_000))
        Route.shutDoor(100_000)
        assertTrue(Route.shut(100_000))
        assertTrue(Route.shut(100_000 + Route.SHUT_FOR_MS - 100))
        assertFalse("nobody opened it: a missed return cannot leave it shut", Route.shut(100_000 + Route.SHUT_FOR_MS))
        Route.shutDoor(200_000)
        Route.openDoor()
        assertFalse(Route.shut(200_100))
        // with the injected clock
        var now = 1_000L
        Route.clock = { now }
        Route.shutDoor()
        assertTrue(Route.shut())
        now += 4_999
        assertTrue(Route.shut())
        now += 1
        assertFalse(Route.shut())
    }

    @Test
    fun aSwapAndItsTopUpAreWaitedFor() {
        assertEquals(Leaving.Wait, Route.leaving(1, false, 1, 400, 0))
        assertEquals(Leaving.Wait, Route.leaving(0, true, 0, 12_000, 0))
        assertEquals(Leaving.Wait, Route.leaving(1, false, 3, 19_000, 9_000))
    }

    @Test
    fun afterTwentySecondsWhateverIsOutIsLeft() {
        assertEquals(Leaving.Leave(0), Route.leaving(1, false, 1, 20_100, 0))
        assertEquals(Leaving.Leave(0), Route.leaving(0, true, 0, 20_100, 0))
        assertEquals(Leaving.Leave(0), Route.leaving(0, false, 2, 20_100, 1_000))
        assertEquals(Leaving.Leave(500), Route.leaving(0, false, 0, 20_100, 0))
    }

    @Test
    fun aRequestThatMovesNoMoneyIsWaitedForThreeSeconds() {
        assertEquals(Leaving.Wait, Route.leaving(0, false, 1, 400, 0))
        assertEquals(Leaving.Wait, Route.leaving(0, false, 2, 3_200, 2_900))
        assertEquals(Leaving.Leave(0), Route.leaving(0, false, 2, 3_600, 3_100))
        assertEquals(Leaving.Wait, Route.leaving(0, false, 1, 15_000, 500))
    }

    @Test
    fun withNothingOutItLeavesAfterHalfASecond() {
        assertEquals(Leaving.Leave(500), Route.leaving(0, false, 0, 400, 0))
        assertEquals(Leaving.Leave(500), Route.leaving(0, false, 0, 9_000, 3_500))
    }
}
