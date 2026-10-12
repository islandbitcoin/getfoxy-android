package io.islandbitcoin.foxy

import android.app.KeyguardManager
import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.keychain.KeystoreSeedKeys
import io.islandbitcoin.foxy.keychain.SeedGate
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

/**
 * The seed's `device` protection on the phone (S3): its key needs the person
 * for every use (API 30+) and survives a new fingerprint; and a move to it the
 * person does not approve leaves the `none` seed where it was, readable, with
 * the protection unchanged. The full none → device → none round trip needs a
 * finger or the screen lock, which only the person can give: it is a by-hand
 * check (docs/DEVICE-TESTS.md). Own aliases and folder, removed after.
 */
@RunWith(AndroidJUnit4::class)
class SeedProtectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val plainAlias = "foxy.seed.test.none.${System.nanoTime()}"
    private val deviceAlias = "foxy.seed.test.device.${System.nanoTime()}"
    private val dir = File(context.noBackupFilesDir, "protect-test-${System.nanoTime()}").apply { mkdirs() }
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    @After
    fun clean() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { store.deleteEntry(plainAlias) }
        runCatching { store.deleteEntry(deviceAlias) }
        dir.deleteRecursively()
    }

    private fun secure() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    @Test
    fun theDeviceKeyNeedsThePersonForEveryUseAndSurvivesANewFinger() {
        Phone.assumeUnlocked()
        assumeTrue("a phone with a screen lock (a device key cannot be made without one)", secure())
        val key = KeystoreSeedKeys(context, deviceAlias, authenticated = true).create()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertTrue(info.isUserAuthenticationRequired)
        assertFalse("a new fingerprint does not lose the seed", info.isInvalidatedByBiometricEnrollment)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            assertEquals("every use", 0, info.userAuthenticationValidityDurationSeconds)
            assertEquals(android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG or android.security.keystore.KeyProperties.AUTH_DEVICE_CREDENTIAL,
                info.userAuthenticationType)
        } else {
            assertEquals(KeystoreSeedKeys.TIME_BOUND_SECONDS, info.userAuthenticationValidityDurationSeconds)
        }
        // the key is no use without the person: an encryption straight through it is refused, at init on
        // API 28-29 (a time-bound key throws UserNotAuthenticatedException there), at use on API 30+
        val refused = runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
            cipher.doFinal(ByteArray(16))
        }.isFailure
        assertTrue("used with no one asked, it refuses", refused)
    }

    @Test
    fun aMoveThePersonDoesNotApproveLeavesTheSeedWhereItWas() {
        Phone.assumeUnlocked()
        assumeTrue(secure())
        val vault = SeedVault(
            dir, KeystoreSeedKeys(context, plainAlias),
            deviceKeys = KeystoreSeedKeys(context, deviceAlias, authenticated = true),
            gate = SeedGate { _, _, _, _ -> SeedGate.Answer.No },
            passcodeSet = { true },
        )
        assertNull(vault.write(words))
        assertEquals(SeedVault.DECLINED, vault.setProtection(SeedVault.DEVICE))
        assertEquals(SeedVault.NONE, vault.protection())
        assertFalse(File(dir, SeedVault.DEVICE_FILE_NAME).exists())
        assertFalse("no device key is left", KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(deviceAlias))
        assertEquals(SeedVault.Answer.Found(words), vault.read())
    }
}
