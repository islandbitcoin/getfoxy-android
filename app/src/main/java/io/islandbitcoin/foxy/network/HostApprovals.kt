package io.islandbitcoin.foxy.network

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.bridge.Prompt
import io.islandbitcoin.foxy.bridge.PromptQueue
import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.keychain.TextStore

/**
 * Hosts Foxy may contact, and the question asked before a new one
 * (Route.swift:635-805, S13).
 *
 * Any script that ran in the page could send what the page holds to an address
 * of its own through `mintRequest`. So the first request to a host Foxy has not
 * used shows a native dialog that no script can answer: Allow or Don't Allow.
 *
 * Approved without asking: the five default mints and any host under one, the
 * two block explorers by onion and by name, a host the person allowed and any
 * host under it, and exactly (not their subdomains) the mints and contact
 * domains the wallet already had the first time this version ran
 * ([carryOver]). After a Don't Allow, no dialog for 30 s: a page cannot keep
 * asking until the person taps Allow to make it stop.
 *
 * Main thread only, as iOS's. Approvals are sealed with a Keystore key in
 * `noBackupFilesDir` (keychain/SealedFile.kt), never in a backup.
 */
class HostApprovals(
    private val allowedFile: TextStore,
    private val carriedFile: TextStore,
    private val prompts: PromptQueue,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        /** The mints the app offers by default (build/app DEFAULT_MINTS). */
        val BUILT_IN: Set<String> = setOf(
            "mint.minibits.cash", "mint.westernbtc.com",
            "mint.macadamia.cash", "nofee.testnut.cashu.space", "testnut.cashu.space",
        )

        /** The two explorers a card's clock comes from, by onion and by name (build/wallet/08b-block-headers.js). */
        val EXPLORERS: Set<String> = setOf(
            "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
            "explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion",
            "mempool.space",
            "blockstream.info",
        )

        const val QUIET_AFTER_NO_MS: Long = 30_000
        const val MOST_CARRIED = 200

        fun normal(host: String): String = host.lowercase().trim('.', ' ')

        /** The rule, apart from storage: a host in `approved` or under one, or exactly one in `carried`. */
        fun approves(host: String, approved: Set<String>, carried: Set<String>): Boolean {
            val h = normal(host)
            if (h.isEmpty()) return false
            return h in approved || h in carried || approved.any { h.endsWith(".$it") }
        }

        /** What of a page's list is carried: at most 200 names, each a plausible host name, normalised. */
        fun carriable(hosts: List<String>): List<String> = hosts.take(MOST_CARRIED).map(::normal).filter { h ->
            h.isNotEmpty() && h.length <= 253 && h.contains('.') && h.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        }

        fun title(host: String) = "Allow Foxy to contact $host?"

        fun message(host: String) = "Foxy has not contacted this address before. It happens when you add a mint, " +
            "receive a token from one, or pay a lightning address. If you did not just do one of those, tap Don't Allow. " +
            "Allowing it also allows addresses under it, such as pay.$host."

        private fun list(text: String): Set<String> =
            ((try { Json.parse(text) } catch (e: Json.Malformed) { null }) as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
    }

    private var allowedCache: Set<String>? = null
    private var carriedCache: Set<String>? = null
    private val waiting = LinkedHashMap<String, MutableList<(Boolean) -> Unit>>()
    private var asking: String? = null
    private var quietUntil = Long.MIN_VALUE

    /** The hosts the person allowed. A file that would not open approves nothing more, and is asked again. */
    private fun allowed(): Set<String> {
        allowedCache?.let { return it }
        return when (val r = allowedFile.read()) {
            is TextStore.Read.Found -> list(r.text).also { allowedCache = it }
            TextStore.Read.Absent -> emptySet<String>().also { allowedCache = it }
            is TextStore.Read.Failed -> {
                Log.line("[foxy] hosts: the allowed list could not be read (${r.why})")
                emptySet()
            }
        }
    }

    private fun carried(): Set<String> {
        carriedCache?.let { return it }
        return when (val r = carriedFile.read()) {
            is TextStore.Read.Found -> list(r.text).also { carriedCache = it }
            TextStore.Read.Absent -> emptySet()
            is TextStore.Read.Failed -> emptySet()
        }
    }

    fun isApproved(host: String): Boolean = approves(host, BUILT_IN + EXPLORERS + allowed(), carried())

    /**
     * The hosts a wallet already used, once: the first time this version runs.
     * Only when the carried file is absent, not merely unreadable (audit I4):
     * later calls change nothing, so a script injected afterwards cannot add its
     * own host this way.
     */
    fun carryOver(hosts: List<String>) {
        if (carriedFile.read() !is TextStore.Read.Absent) return
        val clean = carriable(hosts).toSortedSet()
        if (carriedFile.write(Json.write(clean.toList()))) {
            carriedCache = clean
            Log.line("[foxy] hosts: carried over ${clean.size} the wallet already used")
        }
    }

    /**
     * Asks once per host, one dialog at a time; everything waiting on that host
     * hears the answer. A Don't Allow refuses every other host waiting with it,
     * and keeps quiet for 30 s.
     */
    fun ask(host: String, done: (Boolean) -> Unit) {
        val h = normal(host)
        if (isApproved(h)) return done(true)
        if (clock() < quietUntil) return done(false)
        val list = waiting.getOrPut(h) { mutableListOf() }
        list.add(done)
        if (list.size == 1) next()
    }

    private fun next() {
        if (asking != null) return
        val h = waiting.keys.sorted().firstOrNull() ?: return
        asking = h
        prompts.ask(Prompt(title(h), message(h), no = "Don't Allow", yes = "Allow", destructive = false)) { allowed ->
            if (!allowed) {
                quietUntil = clock() + QUIET_AFTER_NO_MS
                val others = waiting.filterKeys { it != h }
                others.keys.forEach { waiting.remove(it) }
                others.values.forEach { l -> l.forEach { it(false) } }
                finish(h, false)
                return@ask
            }
            val now = allowed() + h
            // kept for this run whatever the disk said; written for the next
            allowedCache = now
            if (!allowedFile.write(Json.write(now.sorted()))) Log.line("[foxy] hosts: the allowed list could not be written")
            Log.line("[foxy] hosts: allowed $h")
            finish(h, true)
        }
    }

    private fun finish(h: String, allowed: Boolean) {
        val done = waiting.remove(h) ?: mutableListOf()
        if (asking == h) asking = null
        done.forEach { it(allowed) }
        next()
    }
}
