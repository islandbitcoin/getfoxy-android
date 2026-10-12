package io.islandbitcoin.foxy.tor

import io.islandbitcoin.foxy.bridge.Json

/**
 * What the page is told about the route (Route.swift:479-512), the reply to
 * `privacy`, `torRetry` and `unprotected` and the `_privacy` push: Tor's state,
 * progress, everUp, the person's choice, the transport, Orbot (always "none" on
 * Android, ANDROID-MAPPING decision 3), firstSetup, whether a VPN is up, and the
 * network. The page whitelists these fields.
 */
object Snapshot {
    fun json(
        tor: String,
        progress: Int,
        everUp: Boolean,
        unprotected: Boolean,
        transport: TorTransport,
        firstSetup: Boolean,
        vpn: Boolean,
        network: String,
    ): String = Json.write(
        mapOf(
            "tor" to tor, "progress" to progress, "everUp" to everUp, "unprotected" to unprotected,
            "transport" to transport.wire, "orbot" to "none", "firstSetup" to firstSetup,
            "vpn" to vpn, "orbotKey" to false, "network" to network,
        ),
    )

    /** A host with no Tor at all: the snapshot P1 answered (the contract's `privacy` stub), network as it is. */
    fun noTor(unprotected: Boolean = false, vpn: Boolean = false, network: String = "unknown") =
        json("none", 0, false, unprotected, TorTransport.DIRECT, false, vpn, network)
}
