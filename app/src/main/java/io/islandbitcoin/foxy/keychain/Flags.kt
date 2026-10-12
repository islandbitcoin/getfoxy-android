package io.islandbitcoin.foxy.keychain

import java.io.File
import java.io.IOException

/**
 * One-way switches kept on this install, iOS's UserDefaults booleans
 * (`foxy.seedMigrate.closed`, `foxy.counters.imported`): a flag is an empty
 * file in `noBackupFilesDir`, written as the counter file is, and gone with the
 * app on an uninstall.
 */
class Flags(private val dir: File) {
    fun isSet(name: String): Boolean = File(dir, file(name)).exists()

    @Throws(IOException::class)
    fun set(name: String) {
        if (isSet(name)) return
        AtomicWrite.write(File(dir, file(name)), ByteArray(0))
    }

    private fun file(name: String): String {
        require(name.all { it.isLetterOrDigit() || it == '.' || it == '-' }) { "not a flag name" }
        return "flag.$name"
    }
}
