package io.islandbitcoin.foxy.keychain

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 * the TEE where it does not. Gone with the app on an uninstall.
 *
 * Protection `none` ([ALIAS]): no user authentication per use. Protection
 * `device` ([DEVICE_ALIAS], `authenticated`): the person's strong biometric or
 * screen lock for each use on API 30+ (`setUserAuthenticationParameters(0, …)`,
 * asked through BiometricPrompt with the cipher as its CryptoObject), and within
 * 10 s of confirming the screen lock on API 28-29, where a per-use key cannot
 * take the screen lock (ENG-649 decision 5). Enrolling a new fingerprint does
 * not invalidate it (the seed must not be lost to a new finger); removing the
 * screen lock does, which Android does to every such key, and the seed then
 * reads as failed, never absent (SECURITY-INVARIANTS.md S3, S4).
 */
class KeystoreSeedKeys(
    private val context: Context,
    private val alias: String = ALIAS,
    private val authenticated: Boolean = false,
) : SeedKeys {
    companion object {
        const val ALIAS = "foxy.seed.none.v1"
        const val DEVICE_ALIAS = "foxy.seed.device.v1"

        /** How long a screen-lock confirmation opens the `device` key on API 28-29. */
        const val TIME_BOUND_SECONDS = 10
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
            .apply { if (authenticated) requireThePerson(this) }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    override fun delete() = store().deleteEntry(alias)

    private fun requireThePerson(builder: KeyGenParameterSpec.Builder) {
        builder.setUserAuthenticationRequired(true)
        builder.setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(TIME_BOUND_SECONDS)
        }
    }
}
