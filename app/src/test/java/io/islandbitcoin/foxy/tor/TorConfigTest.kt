package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * S10, S27: Tor's settings, the transports' order and patience, the bridge
 * memory, the bridge lines byte for byte as iOS's, RESTART TOR's rule, and the
 * snapshot the page is told.
 */
class TorConfigTest {
    @Test
    fun theTorrcIsolatesRefusesAddressesAndOpensNoTunnel() {
        val lines = TorConfig.torrc(release = false, transport = emptyList()).lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "SocksPort auto IsolateDestAddr ExtendedErrors",
                "HTTPTunnelPort 0",
                "ConfluxEnabled 0",
                "SafeSocks 1",
                "ClientRejectInternalAddresses 1",
                "AvoidDiskWrites 1",
                "Log notice syslog",
            ),
            lines,
        )
        assertTrue(TorConfig.torrc(release = true, transport = emptyList()).contains("\nLog err stderr\n"))
    }

    @Test
    fun aTransportsSettingsGoInAsLines() {
        val t = TorConfig.torrc(false, Bridges.settings(TorTransport.OBFS4, 41001))
        assertTrue(t.contains("\nUseBridges 1\nClientTransportPlugin obfs4 socks5 127.0.0.1:41001\n"))
        assertEquals(7, t.lines().count { it.startsWith("Bridge obfs4 ") })
        try {
            TorConfig.torrc(false, listOf("Bridge" to "x\nControlPort 9051"))
            throw AssertionError("a second line was let in")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun theLadderIsDirectObfs4SnowflakeWithTheirPatience() {
        assertEquals(listOf(TorTransport.DIRECT, TorTransport.OBFS4, TorTransport.SNOWFLAKE), TorTransport.ORDER)
        assertEquals(5_000, TorTransport.DIRECT.patienceMs)
        assertEquals(60_000, TorTransport.OBFS4.patienceMs)
        assertEquals(90_000, TorTransport.SNOWFLAKE.patienceMs)
        assertEquals(2, TorMachine.DIRECT_TRIES_MAX)
        assertEquals(TorTransport.OBFS4, TorTransport.named("obfs4"))
        assertEquals(null, TorTransport.named("meek"))
    }

    @Test
    fun theBridgeLinesAreIOSsByteForByte() {
        val all = Bridges.OBFS4_LINES + Bridges.SNOWFLAKE_LINES
        val sha = MessageDigest.getInstance("SHA-256").digest((all.joinToString("\n") + "\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        // the lines between the markers of Foxy/Tor/Bridges.swift at 5581c96
        assertEquals("11813ffab5c8a696a7b4bed3e5e3e617b503648ec99200248b06354bdf0da507", sha)
        assertEquals(listOf("45.145.95.6", "212.83.43.95", "51.222.13.177", "37.218.245.14", "212.83.43.74", "209.148.46.65", "146.57.248.225"), Bridges.obfs4Addresses())
        val sf = Bridges.arguments(Bridges.SNOWFLAKE_LINES[0])
        assertEquals("https://1098762253.rsc.cdn77.org/", sf["url"])
        assertEquals("app.datapacket.com,www.datapacket.com", sf["fronts"])
        assertTrue(sf.getValue("ice").startsWith("stun:stun.epygi.com:3478,"))
        assertEquals(emptyList<Pair<String, String>>(), Bridges.settings(TorTransport.DIRECT, 1))
    }

    @Test
    fun theBridgeLinesMatchTheIOSFileWhenItIsHere() {
        val ref = System.getenv("FOXY_IOS_REF") ?: ""
        val swift = File(ref, "Foxy/Tor/Bridges.swift")
        assumeTrue("the iOS reference is not on this machine", swift.exists())
        val block = swift.readText().substringAfter("BEGIN BUILT-IN BRIDGES").substringBefore("END BUILT-IN BRIDGES")
        val lines = Regex("\"((?:obfs4|snowflake) [^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(Bridges.OBFS4_LINES + Bridges.SNOWFLAKE_LINES, lines)
    }

    @Test
    fun aBridgeIsRememberedForADayAndTheSameBridgeDoesNotStartANewOne() {
        var now = 1_790_000_000_000L
        val file = File(TestSupport.tempDir("memory"), "t")
        val m = TransportMemory(file) { now }
        assertEquals("nothing remembered is direct", TorTransport.DIRECT, m.remembered)
        m.remembered = TorTransport.OBFS4
        assertEquals(TorTransport.OBFS4, m.remembered)
        val since = m.since()
        now += 23 * 3_600_000L
        m.remembered = TorTransport.OBFS4
        assertEquals("the same bridge again keeps the day's start", since, m.since())
        now += 3_600_000L - 1
        assertEquals(TorTransport.OBFS4, m.remembered)
        now += 1
        assertEquals("a day on, direct gets its chance", TorTransport.DIRECT, m.remembered)
        m.remembered = TorTransport.SNOWFLAKE
        assertEquals("a bridge reached after the day starts a new one", now, m.since())
        m.remembered = TorTransport.DIRECT
        assertEquals(TorTransport.DIRECT, m.remembered)
        assertEquals(null, m.since())
        file.writeText("garbage")
        assertEquals(TorTransport.DIRECT, m.remembered)
    }

    @Test
    fun restartTorIsOfferedOnlyWhenARestartCanHelp() {
        fun offered(connecting: Boolean = true, link: Boolean = true, quiet: Long, counted: Boolean = true, snowflake: Boolean = false) =
            TorStuck.offered(connecting, link, quiet, counted, snowflake)
        assertFalse(offered(link = false, quiet = 9_900))
        assertTrue(offered(link = false, quiet = 10_000))
        assertFalse(offered(quiet = 29_900))
        assertTrue(offered(quiet = 30_000))
        assertFalse(offered(quiet = 59_900, counted = false))
        assertTrue(offered(quiet = 60_000, counted = false))
        assertFalse(offered(quiet = 119_900, counted = false, snowflake = true))
        assertTrue(offered(quiet = 120_000, counted = false, snowflake = true))
        assertFalse(offered(connecting = false, link = false, quiet = 600_000))
        fun connecting(ready: Boolean = false, failed: Boolean = false, stopped: Boolean = false, parked: Boolean = false, off: Boolean = false, running: Boolean = true) =
            TorStuck.connecting(running, ready, failed, stopped, parked, off)
        assertTrue(connecting())
        for (c in listOf(connecting(off = true), connecting(parked = true), connecting(ready = true), connecting(failed = true), connecting(stopped = true), connecting(running = false))) {
            assertFalse(c)
        }
    }

    @Test
    fun theSnapshotHasTheTenFieldsThePageReads() {
        @Suppress("UNCHECKED_CAST")
        val s = io.islandbitcoin.foxy.bridge.Json.parse(Snapshot.json("up", 100, true, false, TorTransport.OBFS4, false, true, "wifi")) as Map<String, Any?>
        assertEquals(setOf("tor", "progress", "everUp", "unprotected", "transport", "orbot", "firstSetup", "vpn", "orbotKey", "network"), s.keys)
        assertEquals("obfs4", s["transport"])
        assertEquals("none", s["orbot"])
        assertEquals(false, s["orbotKey"])
        // with no Tor it is the snapshot P1 answered (the contract's privacy stub), keys in any order
        val stub = TestSupport.contractEntries().single { it["name"] == "privacy" }["p1"] as Map<*, *>
        assertEquals(io.islandbitcoin.foxy.bridge.Json.parse(stub["text"] as String), io.islandbitcoin.foxy.bridge.Json.parse(Snapshot.noTor()))
    }
}
