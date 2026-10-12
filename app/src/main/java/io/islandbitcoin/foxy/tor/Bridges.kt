package io.islandbitcoin.foxy.tor

import IPtProxy.Controller as PtController
import IPtProxy.IPtProxy as Pt
import io.islandbitcoin.foxy.debug.Log
import java.io.File

/**
 * Pluggable transports, in this process, from the IPtProxy AAR
 * (Foxy/Tor/Bridges.swift:23-105).
 *
 * A transport listens on 127.0.0.1 and Tor reaches its bridges through it.
 * Snowflake finds a volunteer by asking a broker through a CDN, and public STUN
 * servers: those contacts go straight out, not through Tor, which is how
 * Snowflake works (docs/ROUTE.md §3). They learn this phone is starting
 * Snowflake; never a mint, an amount or a payment.
 *
 * The lines between the markers are iOS's, byte for byte (BridgesTest checks
 * them against the iOS file when the reference is there).
 */
object Bridges {
    // BEGIN BUILT-IN BRIDGES — tools/update-bridges.py, fetched 2026-09-13 from https://bridges.torproject.org/moat/circumvention/builtin
    val OBFS4_LINES = listOf(
        "obfs4 45.145.95.6:27015 C5B7CD6946FF10C5B3E89691A7D3F2C122D2117C cert=TD7PbUO0/0k6xYHMPW3vJxICfkMZNdkRrb63Zhl5j9dW3iRGiCx0A7mPhe5T2EDzQ35+Zw iat-mode=0",
        "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1",
        "obfs4 51.222.13.177:80 5EDAC3B810E12B01F6FD8050D2FD3E277B289A08 cert=2uplIpLQ0q9+0qMFrK5pkaYRDOe460LL9WHBvatgkuRr/SL31wBOEupaMMJ6koRE6Ld0ew iat-mode=0",
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
        "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1",
        "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNinLmxQDpVa+7kTOa9/m+tGWT1SmSYpQ9uTBGa6Hw iat-mode=0",
        "obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq/mtAjp1BIC9vHKJ2FAEpGssTPw iat-mode=0",
    )

    val SNOWFLAKE_LINES = listOf(
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478 utls-imitate=hellorandomizedalpn",
    )
    // END BUILT-IN BRIDGES

    /** The key=value arguments of a bridge line. */
    fun arguments(line: String): Map<String, String> = line.split(' ').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
    }.toMap()

    /** Tor's settings for a transport listening on `port`: none for direct. */
    fun settings(transport: TorTransport, port: Int): List<Pair<String, String>> {
        val lines = when (transport) {
            TorTransport.DIRECT -> return emptyList()
            TorTransport.OBFS4 -> OBFS4_LINES
            TorTransport.SNOWFLAKE -> SNOWFLAKE_LINES
        }
        return listOf("UseBridges" to "1", "ClientTransportPlugin" to "${transport.wire} socks5 127.0.0.1:$port") +
            lines.map { "Bridge" to it }
    }

    /** The IPs of the obfs4 bridges, the only places obfs4 talks to (the leak test's allowlist). */
    fun obfs4Addresses(): List<String> = OBFS4_LINES.map { it.split(' ')[1].substringBeforeLast(':') }
}

/** What starts a pluggable transport: the port it listens on, or a throw when it would not start. */
interface PluggableTransports {
    fun start(transport: TorTransport): Int
    fun stopAll()
}

/** IPtProxy's controller, wrapped as iOS wraps it: one per process, its state dir 0700. */
class IPtProxyTransports(private val stateDir: File, private val logging: Boolean) : PluggableTransports {
    private var controller: PtController? = null

    private fun controller(): PtController {
        controller?.let { return it }
        stateDir.mkdirs()
        TorSeed.ownerOnly(stateDir)
        val c = Pt.newController(stateDir.absolutePath, logging, false, "WARN", null)
        controller = c
        return c
    }

    override fun start(transport: TorTransport): Int {
        val name = when (transport) {
            TorTransport.DIRECT -> return 0
            TorTransport.OBFS4 -> Pt.Obfs4
            TorTransport.SNOWFLAKE -> Pt.Snowflake
        }
        val pt = controller()
        if (transport == TorTransport.SNOWFLAKE) {
            val args = Bridges.arguments(Bridges.SNOWFLAKE_LINES[0])
            pt.snowflakeBrokerUrl = args["url"] ?: ""
            pt.snowflakeFrontDomains = args["fronts"] ?: args["front"] ?: ""
            pt.snowflakeIceServers = args["ice"] ?: ""
            pt.snowflakeAmpCacheUrl = ""
        }
        pt.start(name, "")
        val port = pt.port(name).toInt()
        if (port <= 0) throw IllegalStateException("$name did not open a port")
        Log.line("[foxy] tor: ${transport.wire} listening on 127.0.0.1:$port")
        return port
    }

    override fun stopAll() {
        controller?.let {
            it.stop(Pt.Snowflake)
            it.stop(Pt.Obfs4)
        }
    }
}
