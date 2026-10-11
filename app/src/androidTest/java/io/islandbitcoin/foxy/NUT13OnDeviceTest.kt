package io.islandbitcoin.foxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.islandbitcoin.foxy.keychain.Bytes
import io.islandbitcoin.foxy.keychain.NUT13
import io.islandbitcoin.foxy.keychain.P2PK
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The derivation on the phone, through the APK's own libsecp256k1 (secp256k1-kmp's
 * JNI library for Android): the NUT-13 spec's first vectors for both keyset
 * versions and BIP-32's normal-child vector, as the JVM tests check them on the desk.
 */
@RunWith(AndroidJUnit4::class)
class NUT13OnDeviceTest {
    private val spec = "half depart obvious quality work element tank gorilla view sugar picture humble"

    @Test
    fun theSpecVectorsDeriveOnThePhone() {
        val seed = NUT13.seed(spec)
        val v00 = NUT13.derive(seed, "009a1f293253e41e", 0, 2)
        assertEquals("485875df74771877439ac06339e284c3acfcd9be7abf3bc20b516faeadfe77ae", Bytes.hex(v00[0].secret))
        assertEquals("ad00d431add9c673e843d4c2bf9a778a5f402b985b8da2d5550bf39cda41d679", Bytes.hex(v00[0].blindingFactor))
        assertEquals("8f2b39e8e594a4056eb1e6dbb4b0c38ef13b1b2c751f64f810ec04ee35b77270", Bytes.hex(v00[1].secret))
        val p2pk = P2PK.key(NUT13.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"), 0)
        assertEquals("03d5e349add0f7c450d20e0968ecdb9e5e4b64ceecfd1a5c8c90664b482ab37649", Bytes.hex(p2pk.publicKey))
    }
}
