package io.islandbitcoin.foxy.keychain

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * A small text sealed with a Keystore AES-256-GCM key ([SeedKeys]; on a phone
 * [KeystoreSeedKeys] under its own alias) and written with [AtomicWrite] in
 * `noBackupFilesDir`: the host approvals, which would list the mints and payment
 * domains this person uses (S13, Route.swift:640-643). The iOS keychain's
 * three-way read, kept: a file that is not there is [TextStore.Read.Absent]; one that
 * cannot be opened (a locked phone, a key gone) is [TextStore.Read.Failed], never absent.
 */
class SealedFile(private val file: File, private val keys: SeedKeys, aad: String) : TextStore {

    private val aad = aad.toByteArray(Charsets.US_ASCII)

    companion object {
        private const val FORMAT: Byte = 1
        private const val IV_BYTES = 12
    }

    override fun read(): TextStore.Read {
        if (!file.exists()) return TextStore.Read.Absent
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 1 + IV_BYTES + 16 || bytes[0] != FORMAT) return TextStore.Read.Failed("not a sealed file")
            val key = keys.get() ?: return TextStore.Read.Failed("its key is gone")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 1, IV_BYTES))
            cipher.updateAAD(aad)
            TextStore.Read.Found(cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES).toString(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            TextStore.Read.Failed(e.javaClass.simpleName)
        } catch (e: IOException) {
            TextStore.Read.Failed(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            TextStore.Read.Failed(e.javaClass.simpleName)
        }
    }

    /** True once the text is on the disk, sealed; false, with nothing changed, otherwise. */
    override fun write(text: String): Boolean = try {
        val key = keys.get() ?: keys.create()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // a random IV of the cipher's own (the Keystore insists: setRandomizedEncryptionRequired)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw GeneralSecurityException("no IV")
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        if (iv.size != IV_BYTES) throw GeneralSecurityException("an unexpected IV length")
        AtomicWrite.write(file, byteArrayOf(FORMAT) + iv + sealed)
        true
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }
}

/** A small text kept somewhere: read with the keychain's three answers, written whole or not at all. */
interface TextStore {
    sealed class Read {
        data class Found(val text: String) : Read()
        object Absent : Read()
        data class Failed(val why: String) : Read()
    }

    fun read(): Read
    fun write(text: String): Boolean

    /** In memory only: for a host with nowhere to keep anything, and for the tests. */
    class Memory(var text: String? = null, var failRead: Boolean = false, var failWrite: Boolean = false) : TextStore {
        var writes = 0
        override fun read(): Read = when {
            failRead -> Read.Failed("told to fail")
            text == null -> Read.Absent
            else -> Read.Found(text!!)
        }
        override fun write(text: String): Boolean {
            if (failWrite) return false
            this.text = text
            writes++
            return true
        }
    }
}
