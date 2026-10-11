package io.islandbitcoin.foxy.debug

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.keychain.AtomicWrite
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What Foxy has been doing, kept on the phone so it can be handed over: a port
 * of the iOS FieldLog (Foxy/Debug/FieldLog.swift).
 *
 * In every build. Memory only, redacted on the way in, readable by the page
 * for the ERRORS and LOGS screens (`fieldLog`), and out of the phone only when
 * the person taps COPY. Nothing here reaches logcat: in a release build Foxy's
 * only log is this ([Log]).
 *
 * One small part is kept across one relaunch: the connection diary (Tor, the
 * gate, Orbot, tap, the screens; never a mint, an amount or a token), written to
 * `noBackupFilesDir` and read back and deleted at the next launch, so a person
 * who kills Foxy to get off a stuck screen can still say what it was doing.
 */
object FieldLog {
    class Line(val at: Long, val text: String, val bad: Boolean)

    const val CAPACITY = 10_000
    const val KEEP_CAPACITY = 3_000
    const val LAST_RUN_FILE = "foxy-lastrun.log"

    private val lock = Any()
    private val ring = ArrayDeque<Line>()
    private val keep = ArrayDeque<Line>()
    private var saveDue = false
    @Volatile private var dir: File? = null
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    private val saver = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "foxy-fieldlog").apply { isDaemon = true }
    }

    private val troubles = listOf(
        "error", "failed", "refused", "could not", "cannot",
        "no answer", "timed out", "gave up", "stopped asking",
        "not connected", "unavailable", "invalid", "rejected",
    )

    private val keepPrefixes = listOf(
        "[foxy] tor:", "[foxy] tor gate:", "[foxy] orbot:",
        "[foxy] tunnel:", "[foxy] tor guards",
        "[tap] ", "[foxy] tap:",
        "[foxy] pay steps:", "[foxy] a request was paid",
        "[foxy] a payment for a request",
        "[foxy] screen ", "[foxy] paste control",
        "[foxy] Foxy ",
    )

    /** The first line of every report, and where the last-run diary lives. */
    fun begin(directory: File, banner: String) {
        dir = directory
        takeLastRun()
        write(banner)
    }

    private fun isBad(text: String): Boolean {
        val low = text.lowercase()
        return troubles.any { low.contains(it) }
    }

    fun write(raw: String) {
        val text = redact(raw)
        val line = Line(clock(), text, isBad(text))
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > CAPACITY) ring.removeFirst()
            if (keepPrefixes.any { text.contains(it) }) {
                keep.addLast(line)
                while (keep.size > KEEP_CAPACITY) keep.removeFirst()
                saveSoon()
            }
        }
    }

    /** Lock held. */
    private fun saveSoon() {
        if (saveDue || dir == null) return
        saveDue = true
        saver.schedule({
            synchronized(lock) { saveDue = false }
            saveForNextRun()
        }, 5, TimeUnit.SECONDS)
    }

    /** The diary, written out: on the way to the background, and a few seconds after a kept line. */
    fun saveForNextRun() {
        val d = dir ?: return
        val held = synchronized(lock) { keep.toList() }
        if (held.isEmpty()) return
        val text = held.joinToString("\n") { "${it.at}\t${it.text}" }
        try {
            AtomicWrite.write(File(d, LAST_RUN_FILE), text.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // a diary that could not be saved is a lost diary, not a failure of anything else
        }
    }

    /** Last run's diary into this run's buffer, then deleted. */
    fun takeLastRun() {
        val d = dir ?: return
        val f = File(d, LAST_RUN_FILE)
        val text = try {
            if (!f.exists()) return
            f.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return
        } finally {
            f.delete()
        }
        val lines = text.split("\n").mapNotNull { row ->
            val tab = row.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val ms = row.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val was = row.substring(tab + 1)
            val body = if (was.startsWith("last run · ")) was else "last run · $was"
            Line(ms, body, isBad(body))
        }
        if (lines.isEmpty()) return
        synchronized(lock) {
            lines.asReversed().forEach { ring.addFirst(it) }
            while (ring.size > CAPACITY) ring.removeFirst()
            lines.asReversed().forEach { keep.addFirst(it) }
            while (keep.size > KEEP_CAPACITY) keep.removeFirst()
        }
    }

    fun lines(onlyBad: Boolean = false): List<Line> {
        val all = synchronized(lock) { ring.toList() }
        return if (onlyBad) all.filter { it.bad } else all
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            keep.clear()
        }
        dir?.let { File(it, LAST_RUN_FILE).delete() }
    }

    /** The page's shape: [{at, text, bad}], newest last. */
    fun json(onlyBad: Boolean = false): String =
        Json.write(lines(onlyBad).map { mapOf("at" to it.at, "text" to it.text, "bad" to it.bad) })

    // Redaction: the shapes that carry money or a payee, an onion address, a
    // nostr key and long hex. Mint hosts and sat amounts stay.
    private val secrets: List<Pair<Regex, String>> = listOf(
        Regex("cashu[AB][A-Za-z0-9_=-]{20,}", RegexOption.IGNORE_CASE) to "cashu token",
        Regex("ln(bc|tb|bcrt)[0-9][a-z0-9]{30,}", RegexOption.IGNORE_CASE) to "lightning invoice",
        Regex("creq[A-Za-z0-9_=-]{20,}", RegexOption.IGNORE_CASE) to "payment request",
        Regex("\\b[a-z2-7]{55,56}\\.onion\\b", RegexOption.IGNORE_CASE) to "onion address",
        Regex("\\bnsec1[02-9ac-hj-np-z]{20,}", RegexOption.IGNORE_CASE) to "nostr key",
        Regex("\\b[0-9a-f]{64,}\\b", RegexOption.IGNORE_CASE) to "hex",
    )

    fun redact(line: String): String {
        var out = line
        for ((pattern, what) in secrets) {
            out = pattern.replace(out) { m -> "<$what, ${m.value.length} chars, not logged>" }
        }
        return out
    }

    /** For tests: everything forgotten, and no directory. */
    internal fun resetForTests() {
        synchronized(lock) {
            ring.clear()
            keep.clear()
            saveDue = false
        }
        dir = null
        clock = System::currentTimeMillis
    }
}
