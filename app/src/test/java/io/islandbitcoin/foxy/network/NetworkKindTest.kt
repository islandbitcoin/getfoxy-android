package io.islandbitcoin.foxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network the phone is on (NetworkKind.swift; RouteTests.swift
 * `testATunnelWithNothingUnderItIsNoNetwork`): a VPN with nothing under it is
 * no network, and 'unknown' is not taken for none.
 */
class NetworkKindTest {
    private fun kind(wifi: Boolean = false, cellular: Boolean = false, wired: Boolean = false, vpn: Boolean = false, other: Boolean = false, internet: Boolean = true, has: Boolean = true) =
        NetworkKind.of(has, internet, wifi, cellular, wired, vpn, other)

    @Test
    fun aTunnelWithNothingUnderItIsNoNetwork() {
        assertEquals("wifi", kind(wifi = true))
        assertEquals("Wi-Fi under a VPN", "wifi", kind(wifi = true, vpn = true))
        assertEquals("cellular under a VPN", "cellular", kind(cellular = true, vpn = true))
        assertEquals("wired", kind(wired = true))
        assertEquals("the VPN's tunnel and nothing else", "none", kind(vpn = true))
        assertEquals("tethered over Bluetooth is a network", "other", kind(other = true))
        assertEquals("other", kind(vpn = true, other = true))
        assertEquals("nothing named is not an accusation", "other", kind())
        assertEquals("none", kind(wifi = true, internet = false))
        assertEquals("none", kind(wifi = true, has = false))
    }

    @Test
    fun onlyADefiniteNoneIsNoNetworkAndAChangeIsToldOnce() {
        val told = mutableListOf<String>()
        NetworkKind.onChange = { told.add(it) }
        try {
            NetworkKind.became("unknown")
            assertTrue("unknown counts as a network", NetworkKind.hasNetwork)
            NetworkKind.became("wifi")
            NetworkKind.became("wifi")
            NetworkKind.became("none")
            assertFalse(NetworkKind.hasNetwork)
            NetworkKind.became("cellular")
            assertEquals(listOf("wifi", "none", "cellular"), told)
        } finally {
            NetworkKind.onChange = null
            NetworkKind.became("unknown")
        }
    }
}
