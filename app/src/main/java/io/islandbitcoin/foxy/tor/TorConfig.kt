package io.islandbitcoin.foxy.tor

/**
 * Tor's settings (TorService.swift:798-822, docs/ROUTE.md §2, S10, S27), as the
 * torrc tor-android reads (`TorService.getTorrc`, before its thread starts).
 *
 * tor-android's own command line names the data, cache and control socket
 * directories, a control socket only Foxy can open, and a defaults torrc that
 * asks for SOCKS 9050 and an HTTP tunnel on 8118. Settings here override the
 * defaults file, so:
 *
 * - `SocksPort auto IsolateDestAddr ExtendedErrors`: a port Tor picks (a fixed
 *   one could be taken by another app while Tor's listener is closed), one
 *   circuit per destination, and onion failures told apart;
 * - `HTTPTunnelPort 0`: no HTTP tunnel at all;
 * - `SafeSocks 1`: a SOCKS request that carries an IP instead of a name is
 *   refused, so a client that resolved DNS itself fails rather than leaks;
 * - `ClientRejectInternalAddresses 1`: never a private address through an exit;
 * - `ConfluxEnabled 0`: no multipath circuits (Tor 0.4.9's conflux warned on
 *   every network off and on);
 * - `AvoidDiskWrites 1`, as iOS;
 * - release builds: `Log err stderr`, errors only, to a stream nothing reads;
 *   debug builds: `Log notice syslog`, which tor-android's Tor writes to
 *   logcat under its SyslogIdentityTag (TorService), as iOS's debug Tor
 *   writes to the Console.
 *
 * Not carried over: iOS's release `--hush` (TorService.swift:820-822), which
 * keeps Tor's startup notices, listener ports among them, from being printed
 * before the torrc is read. tor-android builds Tor's command line itself, a
 * fixed list with no room for another argument, so it cannot be passed; those
 * notices go to the native stdout, which an Android app process does not
 * capture. Said in SECURITY-INVARIANTS.md S27.
 *
 * The transport's settings (the bridge lines and the transport's local port)
 * come last, as iOS passes them on the command line at launch.
 *
 * `offline`: `DisableNetwork 1`, for a Tor launched while Foxy is away (a
 * RETRY or start in the background, docs/ROUTE.md §"Putting the app away",
 * S21). Tor boots off the network and makes no connection at all; the set-up
 * on the return turns the network on (`DisableNetwork 0`). iOS has no such
 * launch: its TorThread is never started again while the app is suspended.
 */
object TorConfig {
    fun torrc(release: Boolean, transport: List<Pair<String, String>>, offline: Boolean = false): String = buildString {
        appendLine("SocksPort auto IsolateDestAddr ExtendedErrors")
        appendLine("HTTPTunnelPort 0")
        appendLine("ConfluxEnabled 0")
        appendLine("SafeSocks 1")
        appendLine("ClientRejectInternalAddresses 1")
        appendLine("AvoidDiskWrites 1")
        appendLine(if (release) "Log err stderr" else "Log notice syslog")
        for ((k, v) in transport) {
            require(k.all { it.isLetterOrDigit() } && '\n' !in v && '\r' !in v) { "not a torrc line" }
            appendLine("$k $v")
        }
        if (offline) appendLine("DisableNetwork 1")
    }
}
