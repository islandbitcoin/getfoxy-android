package io.islandbitcoin.foxy.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import io.islandbitcoin.foxy.debug.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * What kind of network the phone is on (Foxy/Network/NetworkKind.swift and
 * Route.swift's path monitor): wifi, cellular, wired, other, none, or unknown
 * before the first answer.
 *
 * "none" is the answer everything turns on: with it Tor is taken off the
 * network and the transport ladder learns nothing. So a network that is not one
 * must not be called one: a VPN whose tunnel is up with no network under it is
 * none. On Android a VPN's NetworkCapabilities carry TRANSPORT_VPN, and its
 * underlying transports when it has any (TRANSPORT_WIFI beside it); a VPN with
 * nothing else is the tunnel alone.
 */
object NetworkKind {
    /** The rule, apart from Android: the default network's transports. */
    fun of(hasNetwork: Boolean, validatedOrInternet: Boolean, wifi: Boolean, cellular: Boolean, wired: Boolean, vpn: Boolean, other: Boolean): String {
        if (!hasNetwork || !validatedOrInternet) return "none"
        if (wifi) return "wifi"
        if (cellular) return "cellular"
        if (wired) return "wired"
        // a tunnel and nothing under it: there is no network
        if (vpn && !other) return "none"
        return "other"
    }

    // TRANSPORT_USB is API 31; on an older phone the constant is a transport it never reports
    @SuppressLint("InlinedApi")
    fun of(caps: NetworkCapabilities?): String {
        if (caps == null) return "none"
        return of(
            hasNetwork = true,
            validatedOrInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            other = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN),
        )
    }

    private val kind = AtomicReference("unknown")
    @Volatile private var vpnUp = false

    /** Told on a change of kind, with whether there is a network now. */
    @Volatile var onChange: ((String) -> Unit)? = null

    /** wifi, cellular, wired, other, none, or unknown before the first answer. */
    val network: String get() = kind.get()

    /** 'unknown' counts as yes: only a definite 'none' is no network (Route.swift `hasNetwork`). */
    val hasNetwork: Boolean get() = network != "none"

    /** Whether a VPN is up now, asked every time (Route.snapshot `vpn`). */
    val vpn: Boolean get() = vpnUp

    private var started = false

    /** Starts watching the default network, once per process. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                vpnUp = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                became(of(caps))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {}

            override fun onLost(network: Network) {
                vpnUp = false
                became("none")
            }

            override fun onUnavailable() = became("none")
        })
        // the callback answers at once when there is a default network; none at all is said by nothing
        if (cm.activeNetwork == null) became("none")
    }

    /** Said, and acted on, only when it changed. */
    fun became(now: String) {
        val was = kind.getAndSet(now)
        if (was == now) return
        Log.line("[foxy] network: $now")
        onChange?.invoke(now)
    }
}
