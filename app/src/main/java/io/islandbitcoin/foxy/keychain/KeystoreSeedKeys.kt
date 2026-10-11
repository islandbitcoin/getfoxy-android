package io.islandbitcoin.foxy.keychain

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.islandbitcoin.foxy.debug.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The seed's key (SECURITY-INVARIANTS.md S3): AES-256-GCM in the Android
 * Keystore, never exportable, usable only while the phone is unlocked
 * (`setUnlockedDeviceRequired`), in StrongBox where the phone has one and in
 * the TEE where it does not. Protection `none`: no user authentication per use
 * (that is `device`, P3, with a second key). Gone with the app on an uninstall.
 */
class KeystoreSeedKeys(private val context: Context, private val alias: String = ALIAS) : SeedKeys {
    companion object {
        const val ALIAS = "foxy.seed.none.v1"
        private const val PROVIDER = "AndroidKeyStore"
    }

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun exists(): Boolean = store().containsAlias(alias)

    override fun get(): SecretKey? = store().getKey(alias, null) as SecretKey?

    override fun create(): SecretKey {
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                return generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.line("[foxy] seed: StrongBox would not make the key; it is made in the TEE")
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    override fun delete() = store().deleteEntry(alias)
}
