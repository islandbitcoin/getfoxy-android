// tor-android says when Tor stops only through LocalBroadcastManager, deprecated but its API
@file:Suppress("DEPRECATION")

package io.islandbitcoin.foxy.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.islandbitcoin.foxy.BuildConfig
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.network.NetworkKind
import io.islandbitcoin.foxy.network.OnionServices
import io.islandbitcoin.foxy.network.Route
import io.islandbitcoin.foxy.network.RouteHttp
import io.islandbitcoin.foxy.network.TorRoute
import net.freehaven.tor.control.RawEventListener
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tor in this process, as the rest of the app sees it: Route's [TorRoute], the
 * snapshot the page is told, and the calls the bridge and putting Foxy away
 * make. The logic is [TorMachine]'s; this file is Android's part of it:
 * tor-android's `TorService`, bound (never started, so never in the
 * foreground: audit finding 4), its jtorctl control link, and the main looper.
 *
 * Our wrapper is named Tor, not TorService, so it does not clash with
 * tor-android's.
 */
object Tor : TorRoute, OnionServices {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var machine: TorMachine? = null

    /** The page is told on every change (the bridge's _privacy push). Main thread. */
    @Volatile var onChange: (() -> Unit)? = null

    override val isRunning: Boolean get() = machine?.isRunning == true
    override val socksPort: Int get() = machine?.socksPort ?: 0

    val transport: TorTransport get() = machine?.transport ?: TorTransport.DIRECT

    /** [TorMachine.daemonGeneration]: an onion address made under an older one is gone. */
    val daemonGeneration: Int get() = machine?.daemonGeneration ?: 0

    /** Off the network because Foxy was put away or its activity finished ([park]); [resumed] brings it back. */
    val parked: Boolean get() = machine?.parked == true

    /** Once per process, before the first start. `forced`: a debug build's transport for the exit test. */
    @Synchronized
    fun install(context: Context, release: Boolean, forced: TorTransport?) {
        if (machine != null) return
        val app = context.applicationContext
        NetworkKind.start(app)
        val dir = app.noBackupFilesDir
        val m = TorMachine(
            sched = MainScheduler(main),
            daemon = ServiceDaemon(app, main) { machine },
            transports = IPtProxyTransports(File(dir, "tor-pt"), logging = !release),
            memory = TransportMemory(File(dir, "foxy-tor-transport")),
            release = release,
            hasNetwork = { NetworkKind.hasNetwork },
            onEstablished = { Route.unprotected = false },
            onPortForgotten = { RouteHttp.forgetTorConnections() },
        )
        m.forced = forced
        if (forced != null) Log.line("[foxy] tor: the transport is forced to ${forced.wire} (debug)")
        m.onChange = { onChange?.invoke() }
        machine = m
        Route.tor = this
        NetworkKind.onChange = { kind ->
            main.post {
                machine?.networkWent(kind != "none")
                onChange?.invoke()
            }
        }
    }

    fun start() = main.post { machine?.start() }

    fun restart(reason: String) = main.post { machine?.restart(reason) }

    /** Foxy is put away: Tor off the network. `done` once it is, or 4 s passed. Main thread. */
    fun park(done: () -> Unit = {}) {
        val m = machine ?: return done()
        m.backgrounded(done)
    }

    /** Foxy is back after `awayS` seconds. Main thread. */
    fun resumed(awayS: Long) {
        machine?.resumed(awayS)
    }

    /** An onion address for the inbox ([TorMachine.addOnion]); `done` on the main looper. Any thread. */
    override fun add(localPort: Int, done: (String?) -> Unit) {
        main.post {
            val m = machine ?: return@post done(null)
            m.addOnion(localPort, done)
        }
    }

    /** `DEL_ONION` ([TorMachine.removeOnion]). Any thread. */
    override fun remove(serviceId: String) {
        main.post { machine?.removeOnion(serviceId) }
    }

    /** What the page is told ([Snapshot]), as Tor and the network are now. */
    fun snapshot(): String {
        val m = machine
        return Snapshot.json(
            tor = m?.state ?: "none",
            progress = m?.progress ?: 0,
            everUp = m?.everUp ?: false,
            unprotected = Route.unprotected,
            transport = m?.transport ?: TorTransport.DIRECT,
            firstSetup = m?.firstSetup ?: false,
            vpn = NetworkKind.vpn,
            network = NetworkKind.network,
        )
    }

    private class MainScheduler(private val handler: Handler) : TorScheduler {
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun post(r: () -> Unit) {
            handler.post(r)
        }
        override fun after(ms: Long, r: () -> Unit): Cancel {
            val token = Any()
            handler.postAtTime({ r() }, token, SystemClock.uptimeMillis() + ms)
            return Cancel { handler.removeCallbacksAndMessages(token) }
        }
    }

    /**
     * tor-android's TorService, bound from the application context with
     * BIND_AUTO_CREATE and never started: it runs while Foxy's process does,
     * and never in the foreground. Its torrc is written before it is bound; the
     * directory seed is laid down then too.
     */
    private class ServiceDaemon(
        private val context: Context,
        private val main: Handler,
        private val machine: () -> TorMachine?,
    ) : TorDaemon {
        private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "foxy-tor-setup") }
        @Volatile private var service: TorService? = null
        @Volatile private var ended = false
        private var connection: ServiceConnection? = null
        private var generation = 0

        override val finished: Boolean get() = ended

        override fun hasRelayList(): Boolean {
            val dir = seedDir() ?: return false
            return listOf("cached-microdesc-consensus", "cached-consensus", "unverified-microdesc-consensus").any { File(dir, it).exists() }
        }

        /** tor-android's `--CacheDirectory`: `cacheDir/TorService`. */
        override fun seedDir(): File? = File(context.cacheDir, "TorService")

        init {
            val filter = IntentFilter().apply {
                addAction(TorService.ACTION_STATUS)
                addAction(TorService.ACTION_ERROR)
            }
            LocalBroadcastManager.getInstance(context).registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == TorService.ACTION_ERROR) {
                        Log.line("[foxy] tor: tor-android reported an error (${intent.getStringExtra(Intent.EXTRA_TEXT)?.take(120)})")
                        return
                    }
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS)
                    if (status == TorService.STATUS_STOPPING || status == TorService.STATUS_OFF) {
                        // only the service this launch bound: an earlier one, unbound by a relaunch, says OFF as it goes
                        if (!ended && service != null) {
                            ended = true
                            machine()?.daemonFinished("tor-android said $status")
                        }
                    }
                }
            }, filter)
        }

        override fun launch(torrc: String) {
            generation += 1
            val gen = generation
            connection?.let {
                try {
                    context.unbindService(it)
                } catch (_: IllegalArgumentException) {
                }
            }
            connection = null
            service = null
            ended = false
            io.execute {
                val ok = try {
                    val file = TorService.getTorrc(context)
                    file.parentFile?.mkdirs()
                    file.writeText(torrc)
                    true
                } catch (e: Exception) {
                    Log.line("[foxy] tor: the torrc could not be written (${e.javaClass.simpleName})")
                    false
                }
                seedDir()?.let { dir ->
                    TorSeed.plant(dir, ::seedStream, BuildConfig.TOR_SEED_PLAIN_SHA256)
                }
                main.post {
                    if (gen != generation) return@post
                    if (!ok) {
                        ended = true
                        machine()?.daemonFinished("no torrc")
                        return@post
                    }
                    bind(gen)
                }
            }
        }

        private fun seedStream(): java.io.InputStream? = try {
            context.assets.open(TorSeed.ASSET)
        } catch (e: java.io.IOException) {
            null
        }

        private fun bind(gen: Int) {
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (gen != generation) return
                    service = (binder as TorService.LocalBinder).service
                    waitForControl(gen, SystemClock.elapsedRealtime())
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (gen != generation) return
                    ended = true
                    machine()?.daemonFinished("the Tor service went away")
                }
            }
            connection = conn
            val bound = context.bindService(Intent(context, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                ended = true
                machine()?.daemonFinished("the Tor service would not bind")
            }
        }

        /** tor-android opens its control link once Tor has made the socket: asked every 250 ms, for 30 s. */
        private fun waitForControl(gen: Int, since: Long) {
            if (gen != generation || ended) return
            val link = service?.torControlConnection
            if (link != null) {
                link.addRawEventListener(RawEventListener { keyword, data ->
                    if (keyword == "STATUS_CLIENT" && data != null) main.post { if (gen == generation) machine()?.statusEvent(data) }
                })
                machine()?.controlReady(JtorctlControl(link, MainScheduler(main)))
                return
            }
            if (SystemClock.elapsedRealtime() - since > 30_000) {
                machine()?.noControl("no control link 30s after Tor started")
                return
            }
            main.postDelayed({ waitForControl(gen, since) }, 250)
        }
    }
}

/**
 * jtorctl's blocking calls, made on one thread of their own and answered on
 * the scheduler (the main looper), with a deadline after which the answer is
 * empty (TorService.swift `control`). A reply that comes after the deadline is
 * not handed to `done`; [addOnion]'s is not dropped either, because with
 * Flags=Detach the service it made would stay published for the rest of the
 * daemon's life with nobody holding its id: it is ended with DEL_ONION.
 */
internal class JtorctlControl(
    private val link: TorControlConnection,
    private val sched: TorScheduler,
    private val worker: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "foxy-tor-control") },
) : TorControl {
    private fun <T> call(timeoutMs: Long, empty: T, done: (T) -> Unit, onLate: (T) -> Unit = {}, work: () -> T) {
        val once = AtomicBoolean(false)
        val deadline = sched.after(timeoutMs) { if (once.compareAndSet(false, true)) done(empty) }
        worker.execute {
            val result = try {
                work()
            } catch (e: Exception) {
                empty
            }
            sched.post {
                if (once.compareAndSet(false, true)) {
                    deadline.cancel()
                    done(result)
                } else {
                    onLate(result)
                }
            }
        }
    }

    override fun setConf(settings: List<Pair<String, String>>, timeoutMs: Long, done: (Boolean, String) -> Unit) {
        call(timeoutMs, false to "no answer", { (ok, m) -> done(ok, m) }) {
            try {
                link.setConf(settings.map { (k, v) -> "$k $v" })
                true to "OK"
            } catch (e: java.io.IOException) {
                false to (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override fun getInfo(key: String, timeoutMs: Long, done: (String?) -> Unit) {
        call<String?>(timeoutMs, null, done) { link.getInfo(key) }
    }

    override fun extendCircuit(timeoutMs: Long, done: (Boolean) -> Unit) {
        call(timeoutMs, false, done) {
            link.extendCircuit("0", "")
            true
        }
    }

    /* jtorctl writes `ADD_ONION NEW:ED25519-V3 Flags=DiscardPK,Detach Port=80,127.0.0.1:<port>`
     * and reads ServiceID into "onionAddress"; an error reply throws, which answers null.
     * A service made after the deadline was already answered as none: it is removed at once. */
    override fun addOnion(localPort: Int, timeoutMs: Long, done: (String?) -> Unit) {
        call<String?>(timeoutMs, null, done, onLate = { id ->
            if (id != null) {
                Log.line("[foxy] tor: an onion address came after its deadline; removed")
                delOnion(id)
            }
        }) {
            link.addOnion(TorOnion.KEY, mapOf(80 to TorOnion.target(localPort)), TorOnion.FLAGS)["onionAddress"]
        }
    }

    override fun delOnion(serviceId: String) {
        call(10_000, Unit, {}) { link.delOnion(serviceId) }
    }
}
