package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.debug.Log
import java.io.File
import java.io.IOException

/**
 * The NUT-13 counters, kept natively (Foxy/Keychain/CounterStore.swift): per
 * keyset, the next counter a secret is derived from.
 *
 * A counter handed out twice makes outputs the mint has already signed. So a
 * counter only moves forward, and no range is handed out until the file that
 * records it has been written ([AtomicWrite]: whole, fsynced, renamed).
 *
 * `foxy-counters.json` in `noBackupFilesDir`, `{keysetId: next}`: one entry per
 * derivation path ([CounterRules]); at most 256 keysets; a file that is there
 * and cannot be read stops every reservation. A second file, `foxy-p2pk.json`,
 * `{"next": n}`, holds the next P2PK lock-key index. When the seed changes both
 * go aside under one stamp and the store starts empty.
 */
class CounterStore(
    val directory: File,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    sealed class Failure(val text: String) : Exception(text) {
        class Unreadable : Failure("the counters could not be read")
        class Unwritable : Failure("the counters could not be saved")
        class AlreadyIssued : Failure("range already issued")
        class OutOfRange : Failure("outside the keyset's counters")
        class TooFarAhead : Failure("too far ahead")
        class TooManyKeysets : Failure("too many keysets")
        class AlreadyImported : Failure("counters were already imported")

        override fun equals(other: Any?): Boolean = other != null && other::class == this::class
        override fun hashCode(): Int = this::class.hashCode()
    }

    companion object {
        const val FILE_NAME = "foxy-counters.json"
        const val P2PK_FILE_NAME = "foxy-p2pk.json"
        const val IMPORTED_FLAG = "foxy.counters.imported"
        const val LARGEST_NEXT: Long = 1L shl 53

        fun replacedFile(directory: File, stamp: Long) = File(directory, "foxy-counters.replaced.$stamp.json")
        fun replacedP2PKFile(directory: File, stamp: Long) = File(directory, "foxy-p2pk.replaced.$stamp.json")

        /** A counter file, or null if any entry is not a lowercase 00/01 id with a whole number 0..2^53. */
        fun parse(data: ByteArray): Map<String, Long>? {
            val obj = try {
                Json.parse(String(data, Charsets.UTF_8))
            } catch (e: Json.Malformed) {
                return null
            } as? Map<*, *> ?: return null
            val out = HashMap<String, Long>()
            for ((k, v) in obj) {
                val id = k as String
                if (NUT13.keysetVersion(id) == null || id != id.lowercase()) return null
                val n = wholeNumber(v, LARGEST_NEXT) ?: return null
                out[id] = n
            }
            return out
        }

        /** `{"next": n}`, or null when `next` is missing or not a whole number an index could be. */
        fun parseP2PK(data: ByteArray): Long? {
            val obj = try {
                Json.parse(String(data, Charsets.UTF_8))
            } catch (e: Json.Malformed) {
                return null
            } as? Map<*, *> ?: return null
            return wholeNumber(obj["next"], P2PK.LARGEST_NEXT)
        }

        private fun wholeNumber(v: Any?, most: Long): Long? {
            val d = v as? Double ?: return null
            if (!d.isFinite() || d < 0 || d != Math.floor(d) || d > most.toDouble()) return null
            return d.toLong()
        }
    }

    val file: File get() = File(directory, FILE_NAME)
    val p2pkFile: File get() = File(directory, P2PK_FILE_NAME)

    private val lock = Any()
    private var counters: Map<String, Long>? = null
    private val served = HashMap<String, Long>()
    private var p2pkStored: Long? = null
    private var p2pkServedEnd: Long = 0

    fun snapshot(): Map<String, Long> = synchronized(lock) { load() }

    fun next(keysetId: String): Long = synchronized(lock) {
        val c = load()
        c[CounterRules.storedKey(keysetId, c.keys)] ?: 0
    }

    fun importMax(incoming: Map<String, Long>): Map<String, Long> = synchronized(lock) {
        val c = HashMap(load())
        val before = HashMap(c)
        for ((id, value) in incoming.toSortedMap()) {
            val version = NUT13.keysetVersion(id) ?: continue
            val k = key(id, c)
            c[k] = maxOf(c[k] ?: 0, CounterRules.capped(value, version))
        }
        if (c != before) save(c)
        c
    }

    /** importMax, once per install. A failure leaves the import still to do. */
    fun importOnce(incoming: Map<String, Long>, flags: Flags): Map<String, Long> {
        if (flags.isSet(IMPORTED_FLAG)) throw Failure.AlreadyImported()
        val merged = importMax(incoming)
        try {
            flags.set(IMPORTED_FLAG)
        } catch (e: IOException) {
            throw Failure.Unwritable()
        }
        return merged
    }

    /** Reserves `next ..< next + count` and returns its start, written first. A count of 0 only reads. */
    fun reserve(keysetId: String, version: Int, count: Int): Long = synchronized(lock) {
        val c = HashMap(load())
        val start = c[CounterRules.storedKey(keysetId, c.keys)] ?: 0
        if (count <= 0) return@synchronized start
        if (!NUT13.countersFit(version, start, count)) throw Failure.OutOfRange()
        val stored = key(keysetId, c)
        c[stored] = start + count
        save(c)
        start
    }

    /** Reserves `start ..< start + count`, burning every counter below `start`. */
    fun reserveAt(keysetId: String, version: Int, start: Long, count: Int) = synchronized(lock) {
        val c = HashMap(load())
        val k = key(keysetId, c)
        val current = c[k] ?: 0
        if (start < current) throw Failure.AlreadyIssued()
        if (CounterRules.reserveAt(current, start) != CounterRules.Move.ALLOWED) throw Failure.TooFarAhead()
        if (count <= 0 || !NUT13.countersFit(version, start, count)) throw Failure.OutOfRange()
        c[k] = start + count
        save(c)
    }

    /** Moves `next` up to `value`, never down; at most 100 past it or to what a restore served. */
    fun advance(keysetId: String, value: Long): Long = synchronized(lock) {
        val c = HashMap(load())
        val version = NUT13.keysetVersion(keysetId) ?: throw Failure.OutOfRange()
        val slot = CounterRules.slot(keysetId) ?: throw Failure.OutOfRange()
        val found = CounterRules.storedKey(keysetId, c.keys)
        val current = c[found] ?: 0
        if (value <= current) return@synchronized current
        if (CounterRules.advance(current, value, served[slot] ?: 0, version) != CounterRules.Move.ALLOWED) {
            throw Failure.TooFarAhead()
        }
        val k = key(keysetId, c)
        c[k] = value
        save(c)
        value
    }

    /** Each entry raised to at least the end given, never lowered, cut to the ceiling. */
    fun raise(ends: Map<String, Long>): Map<String, Long> = synchronized(lock) {
        val c = HashMap(load())
        val before = HashMap(c)
        for ((id, end) in ends.toSortedMap()) {
            val version = NUT13.keysetVersion(id) ?: continue
            if (end <= 0) continue
            val k = key(id, c)
            c[k] = maxOf(c[k] ?: 0, CounterRules.capped(end, version))
        }
        if (c != before) save(c)
        c
    }

    fun noteServed(keysetId: String, end: Long) {
        val slot = CounterRules.slot(keysetId) ?: return
        synchronized(lock) { served[slot] = maxOf(served[slot] ?: 0, end) }
    }

    fun highestServed(keysetId: String): Long {
        val slot = CounterRules.slot(keysetId) ?: return 0
        return synchronized(lock) { served[slot] ?: 0 }
    }

    // MARK: The lock-key index

    fun p2pkNext(): Long = synchronized(lock) { loadP2PK() }

    fun p2pkReserve(count: Int): Long = synchronized(lock) {
        val start = loadP2PK()
        if (count <= 0) return@synchronized start
        if (!P2PK.indicesFit(start, count)) throw Failure.OutOfRange()
        saveP2PK(start + count)
        start
    }

    fun noteP2PKServed(end: Long) = synchronized(lock) { p2pkServedEnd = maxOf(p2pkServedEnd, end) }

    fun highestP2PKServed(): Long = synchronized(lock) { p2pkServedEnd }

    // MARK: Set aside

    /** The seed changed: both files go aside under one stamp, and the store starts empty. */
    fun moveAside(): File? = synchronized(lock) {
        val hasCounters = file.exists()
        val hasIndex = p2pkFile.exists()
        p2pkServedEnd = 0
        if (!hasCounters && !hasIndex) {
            counters = emptyMap()
            p2pkStored = 0
            return@synchronized null
        }
        var stamp = now()
        while ((hasCounters && replacedFile(directory, stamp).exists()) ||
            (hasIndex && replacedP2PKFile(directory, stamp).exists())
        ) {
            stamp += 1
        }
        var moved: File? = null
        if (hasCounters) {
            val target = replacedFile(directory, stamp)
            if (!file.renameTo(target)) {
                Log.line("[foxy] counters: could not be set aside")
                throw Failure.Unwritable()
            }
            moved = target
            Log.line("[foxy] counters: set aside as ${target.name}")
        }
        counters = emptyMap()
        if (hasIndex) {
            val target = replacedP2PKFile(directory, stamp)
            if (p2pkFile.renameTo(target)) {
                p2pkStored = 0
                Log.line("[foxy] lock keys: the index set aside as ${target.name}")
            } else {
                // left where it is: the same index under a new seed derives a different key
                Log.line("[foxy] lock keys: the index file could not be set aside; it is kept and counts on, which reuses no key")
                p2pkStored = null
            }
        } else {
            p2pkStored = 0
        }
        moved
    }

    // MARK: The file (lock held)

    private fun key(keysetId: String, c: Map<String, Long>): String {
        val k = CounterRules.storedKey(keysetId, c.keys)
        if (c[k] == null && c.size >= CounterRules.MOST_KEYSETS) throw Failure.TooManyKeysets()
        return k
    }

    private fun load(): Map<String, Long> {
        counters?.let { return it }
        if (!file.exists()) {
            counters = emptyMap()
            return emptyMap()
        }
        val parsed = try {
            parse(file.readBytes())
        } catch (e: IOException) {
            null
        }
        if (parsed == null) {
            Log.line("[foxy] counters: the file could not be read; no counter is handed out")
            throw Failure.Unreadable()
        }
        val merged = CounterRules.merged(parsed)
        if (merged.size != parsed.size) {
            Log.line("[foxy] counters: ${parsed.size - merged.size} entries of the same derivation path merged")
        }
        counters = merged
        return merged
    }

    /** Memory changes only once the file has. */
    private fun save(c: Map<String, Long>) {
        try {
            AtomicWrite.write(file, Json.write(c).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.line("[foxy] counters: not saved; no counter is handed out")
            throw Failure.Unwritable()
        }
        counters = HashMap(c)
    }

    private fun loadP2PK(): Long {
        p2pkStored?.let { return it }
        if (!p2pkFile.exists()) {
            p2pkStored = 0
            return 0
        }
        val parsed = try {
            parseP2PK(p2pkFile.readBytes())
        } catch (e: IOException) {
            null
        }
        if (parsed == null) {
            Log.line("[foxy] lock keys: the index file could not be read; no lock key is handed out")
            throw Failure.Unreadable()
        }
        p2pkStored = parsed
        return parsed
    }

    private fun saveP2PK(next: Long) {
        try {
            AtomicWrite.write(p2pkFile, Json.write(mapOf("next" to next)).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.line("[foxy] lock keys: the index was not saved; no lock key is handed out")
            throw Failure.Unwritable()
        }
        p2pkStored = next
    }
}
