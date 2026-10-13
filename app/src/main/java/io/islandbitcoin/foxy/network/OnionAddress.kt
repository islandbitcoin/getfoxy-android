package io.islandbitcoin.foxy.network

/**
 * A version 3 onion address: 56 characters of base32, the last a "d"
 * (Foxy/Network/OnionAddress.swift).
 *
 * Only the shape is checked. The checksum inside the address is Tor's to
 * check, and a wrong one fails there, before anything leaves the phone.
 */
object OnionAddress {
    fun isServiceId(id: String): Boolean =
        id.length == 56 && id.endsWith("d") && id.all { it in 'a'..'z' || it in '2'..'7' }

    /** "<56 characters>.onion", the host of a payment request delivered over Tor. */
    fun isHost(host: String): Boolean {
        val h = host.lowercase(java.util.Locale.ROOT)
        if (!h.endsWith(".onion")) return false
        return isServiceId(h.dropLast(".onion".length))
    }
}
