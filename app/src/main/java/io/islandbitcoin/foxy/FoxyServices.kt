package io.islandbitcoin.foxy

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import io.islandbitcoin.foxy.bridge.DeliveryEnvironment
import io.islandbitcoin.foxy.bridge.NetEnvironment
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.BIP39
import io.islandbitcoin.foxy.keychain.BIP39.generate
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.DeviceAuth
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.KeystoreSeedKeys
import io.islandbitcoin.foxy.keychain.SealedFile
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedMigrationWindow
import io.islandbitcoin.foxy.keychain.SeedVault
import io.islandbitcoin.foxy.network.HostApprovals
import io.islandbitcoin.foxy.network.OnionInbox
import io.islandbitcoin.foxy.network.OnionPost
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.tor.Tor
import io.islandbitcoin.foxy.tor.TorTransport
import io.islandbitcoin.foxy.web.Clipboard
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What lives as long as the process: the seed vault, the counter file, the
 * migration window, the one seed executor, the one queue of native questions
 * and the host approvals. Everything that is the wallet's is in
 * `noBackupFilesDir` (S1).
 */
class FoxyServices(context: Context) {
    private val app = context.applicationContext
    val directory = app.noBackupFilesDir
    val flags = Flags(directory)

    private val keyguard = app.getSystemService(KeyguardManager::class.java)

    /** The activity in front, which native dialogs are shown on; none, and every question is a no. */
    @Volatile private var front: WeakReference<Activity>? = null

    /** The person, asked by Android: the seed's `device` key, the device owner, the PIN lock's biometric. */
    val auth = DeviceAuth(app) { front?.get() }

    /** The vault's disk calls may delete a cut-off write's orphan key, so they run on [seedExecutor] only. */
    val vault = SeedVault(
        directory,
        KeystoreSeedKeys(app),
        onSeedThread = {
            if (BuildConfig.DEBUG) {
                check(Thread.currentThread().name == SEED_THREAD) { "the seed vault touched off the $SEED_THREAD executor" }
            }
        },
        deviceKeys = KeystoreSeedKeys(app, KeystoreSeedKeys.DEVICE_ALIAS, authenticated = true),
        gate = auth,
        passcodeSet = { keyguard?.isDeviceSecure ?: true },
    )
    val counters = CounterStore(directory)
    val migrationWindow = SeedMigrationWindow(flags)

    /** Foxy's copies, the page's reads, and the 120 s clear (S23). Main thread. */
    val clipboard = Clipboard(app)

    /** Typed words, in memory only, gone wherever the kept seed goes. */
    val candidates = SeedCandidates().also { c -> vault.onForget = { kept -> c.forgetAll(kept) } }

    /** The seed actions' one thread: a reservation is atomic because nothing else runs beside it. */
    val seedExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, SEED_THREAD) }

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

    /** The activity in front, if any (the seed screens and the scanner open from it). */
    val frontActivity: Activity? get() = front?.get()

    fun inFront(activity: Activity) {
        front = WeakReference(activity)
    }

    fun gone(activity: Activity) {
        if (front?.get() === activity) front = null
    }

    /** Every native yes-or-no, one at a time (NativePrompts.swift). Main thread. */
    val prompts = PromptQueue(PromptQueue.dialogs { front?.get() })

    /** Hosts Foxy may contact; approvals sealed with their own Keystore key (S13). Main thread. */
    val approvals: HostApprovals by lazy {
        val keys = KeystoreSeedKeys(app, HOSTS_KEY)
        HostApprovals(
            SealedFile(directory.resolve("foxy-hosts-allowed.v1.bin"), keys, "foxy.hosts.allowed.v1"),
            SealedFile(directory.resolve("foxy-hosts-carried.v1.bin"), keys, "foxy.hosts.carried.v1"),
            prompts,
        )
    }

    /** What the P2 actions use: Tor, Route, the approvals and the dialogs. */
    fun net(after: (Long, () -> Unit) -> Unit): NetEnvironment = NetEnvironment(
        torRunning = { Tor.isRunning },
        bridged = { Tor.isRunning && Tor.transport != TorTransport.DIRECT },
        snapshot = { Tor.snapshot() },
        restartTor = { Tor.restart(it) },
        unprotected = { Route.unprotected },
        setUnprotected = { Route.unprotected = it },
        available = { Route.available },
        approvals = approvals,
        prompts = prompts,
        send = { request, circuit, done -> Route.start(request, circuit, done)?.let { call -> { call.cancel() } } },
        after = after,
    )

    /** What the onion inbox and post use (ENG-672): Tor's onion services, a loopback listener per address, OnionPost. */
    fun delivery(main: (Runnable) -> Unit, after: (Long, () -> Unit) -> Unit): DeliveryEnvironment = DeliveryEnvironment(
        torRunning = { Tor.isRunning },
        daemonGeneration = { Tor.daemonGeneration },
        newInbox = { note, onPayment -> OnionInbox(note, Tor, main, onPayment = onPayment) },
        post = { url, body, circuit, timeoutMs, done -> OnionPost.send(url, body, circuit, timeoutMs, done = done) },
        after = after,
    )

    fun environment(): SeedEnvironment = SeedEnvironment(
        savedSeed = { vault.seedForSecrets() },
        seedPresent = { vault.isThere() },
        write = { words -> vault.write(words) },
        writeReplacing = { words, keep, confirm -> vault.write(words, replace = true, keepCandidate = keep, confirmReplace = confirm) },
        delete = { vault.delete() },
        protection = { vault.protection() },
        setProtection = { vault.setProtection(it) },
        wordsForScreen = { vault.wordsForScreen() },
        unlock = { reason, credential -> vault.unlock(reason, credential) },
        counters = counters,
        candidates = candidates,
        wordlist = { wordlist },
        generate = { generate(it) },
        migrationWindowOpen = { migrationWindow.isOpen },
        closeMigrationWindow = { migrationWindow.close(it) },
        flags = flags,
        passcodeSet = { keyguard?.isDeviceSecure ?: true },
    )

    companion object {
        /** The one seed executor's thread; the vault's disk calls run there and nowhere else. */
        const val SEED_THREAD = "foxy-seed"

        /** The host approvals' Keystore key, apart from the seed's. */
        const val HOSTS_KEY = "foxy.hosts.v1"
    }
}
