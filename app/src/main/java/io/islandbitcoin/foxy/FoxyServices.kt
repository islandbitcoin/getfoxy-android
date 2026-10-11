package io.islandbitcoin.foxy

import android.app.KeyguardManager
import android.content.Context
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.BIP39
import io.islandbitcoin.foxy.keychain.BIP39.generate
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.KeystoreSeedKeys
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedMigrationWindow
import io.islandbitcoin.foxy.keychain.SeedVault
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What lives as long as the process: the seed vault, the counter file, the
 * migration window and the one seed executor. Everything that is the wallet's
 * is in `noBackupFilesDir` (S1).
 */
class FoxyServices(context: Context) {
    private val app = context.applicationContext
    val directory = app.noBackupFilesDir
    val flags = Flags(directory)
    val vault = SeedVault(directory, KeystoreSeedKeys(app))
    val counters = CounterStore(directory)
    val migrationWindow = SeedMigrationWindow(flags)
    val candidates = SeedCandidates()

    /** The seed actions' one thread: a reservation is atomic because nothing else runs beside it. */
    val seedExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "foxy-seed") }

    /** The bundled wordlist, only if it is the pinned one. */
    val wordlist: List<String>? by lazy {
        val list = try {
            app.assets.open(BIP39.ASSET).use { BIP39.wordlist(it.readBytes()) }
        } catch (e: Exception) {
            null
        }
        if (list == null) Log.line("[foxy] bip39: the bundled wordlist is missing or not the pinned list; nothing is made or checked with it")
        list
    }

    private val keyguard = app.getSystemService(KeyguardManager::class.java)

    fun environment(): SeedEnvironment = SeedEnvironment(
        savedSeed = { vault.seedForSecrets() },
        seedPresent = { vault.isThere() },
        write = { words -> vault.write(words) },
        counters = counters,
        candidates = candidates,
        wordlist = { wordlist },
        generate = { generate(it) },
        migrationWindowOpen = { migrationWindow.isOpen },
        closeMigrationWindow = { migrationWindow.close(it) },
        flags = flags,
        passcodeSet = { keyguard?.isDeviceSecure ?: true },
    )
}
