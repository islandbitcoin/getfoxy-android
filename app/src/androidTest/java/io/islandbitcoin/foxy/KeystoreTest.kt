package io.islandbitcoin.foxy

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.islandbitcoin.foxy.keychain.KeystoreSeedKeys
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

/**
 * S3 on the phone: the seed's key is AES-256 in the Keystore's secure hardware
 * (StrongBox where the phone has it), needs no user authentication (protection
 * `none`), and the vault round-trips through it, from a second vault object as
 * after a relaunch. S4: a file without its key reads as failed, never absent.
 * Uses its own alias and folder, and removes both.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "foxy.seed.test.${System.nanoTime()}"
    private lateinit var dir: File
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    @Before
    fun folder() {
        dir = File(context.noBackupFilesDir, "keystore-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun clean() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        dir.deleteRecursively()
    }

    @Test
    fun theKeyIsInSecureHardwareAndNeedsNoAuthentication() {
        val keys = KeystoreSeedKeys(context, alias)
        assertFalse(keys.exists())
        val key = keys.create()
        assertTrue(keys.exists())
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertFalse("protection none: no authentication per use", info.isUserAuthenticationRequired)
        assertTrue(info.purposes and KeyProperties.PURPOSE_ENCRYPT != 0)
        if (Build.VERSION.SDK_INT >= 31) {
            val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val want = if (strongBox) KeyProperties.SECURITY_LEVEL_STRONGBOX else KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            assertEquals("StrongBox where the phone has it, else the TEE", want, info.securityLevel)
        } else {
            @Suppress("DEPRECATION")
            assertTrue(info.isInsideSecureHardware)
        }
    }

    @Test
    fun onALockedPhoneTheSeedIsNeitherWrittenNorTakenForAbsent() {
        org.junit.Assume.assumeTrue("the phone is unlocked; theVaultRoundTrips… covers it", Phone.locked)
        val vault = SeedVault(dir, KeystoreSeedKeys(context, alias))
        val refused = vault.write(words)
        assertEquals("the keystore refused the write", refused)
        assertEquals("nothing was left behind: no file, no key", SeedVault.Answer.Absent, vault.read())
    }

    @Test
    fun theVaultRoundTripsAndAFileWithoutItsKeyIsAFailure() {
        Phone.assumeUnlocked()
        val first = SeedVault(dir, KeystoreSeedKeys(context, alias))
        assertEquals(SeedVault.Answer.Absent, first.read())
        assertEquals(null, first.write(words))
        // a second vault, as after a relaunch, reads the same words through the Keystore
        val second = SeedVault(dir, KeystoreSeedKeys(context, alias))
        assertEquals(SeedVault.Answer.Found(words), second.read())
        assertNotNull((second.seedForSecrets() as? SeedVault.SecretsSeed.Found)?.seed)
        // what is on disk is not the words
        assertFalse(String(File(dir, SeedVault.FILE_NAME).readBytes(), Charsets.ISO_8859_1).contains("legal"))
        // the key gone, the file left: failed, never absent (S4)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        val third = SeedVault(dir, KeystoreSeedKeys(context, alias))
        assertTrue(third.read() is SeedVault.Answer.Failed)
        assertTrue(third.write("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")!!
            .contains("nothing was written"))
    }
}
