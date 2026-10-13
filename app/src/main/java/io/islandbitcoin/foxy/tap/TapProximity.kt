package io.islandbitcoin.foxy.tap

import java.util.Locale

/**
 * Which receiver the payer's phone is close enough to talk to
 * (TapProtocol.swift `TapProximity`). Signal strength only chooses the phone;
 * the code proves who owns it.
 *
 * The numbers are iOS's as they are (TapProtocol.swift at 8df0bb1), measured
 * iPhone to iPhone, and the Android payer ([TapPayer], ENG-671) decides on them
 * unchanged. **Pixel values: to be measured** — a Pixel paying an iPhone, an
 * iPhone paying a Pixel, and Pixel to Pixel read differently through different
 * antennas, and the method is docs/DEVICE-TESTS.md "Tap to pay: the Pixel pays"
 * (A12). Per-model thresholds are an upstream change that Android then follows.
 * Times are seconds, as `TimeInterval`; ids are whatever the radio names a
 * phone by.
 */
class TapProximity {
    /** The median reading, in dBm, a phone must reach: touching, on the iPhones it was set on. */
    var nearDbm = -33

    /** Near enough to be told about (TAP TO PAY on the payer's screen), not to connect. */
    var nearbyDbm = -54

    /** The link only ever confirms, never decides: loose, because its scale is rough. */
    var nearLinkedDbm = -60

    /** How long a phone must have been heard. */
    var hold = 0.35

    /** The hold when another receiver is within [tieDb]. */
    var rivalHold = 1.0
    var minReadings = 2

    /** A second phone this close to the best one makes it a tie. */
    var tieDb = 8

    /** Readings older than this are forgotten. */
    var memory = 2.0

    /** Close enough to be worth opening a quiet link to before the verdict. */
    var warmDbm = -64

    sealed class Verdict {
        data object Nothing : Verdict()

        /** A Foxy is there, not close enough; its median reading. */
        data class Far(val dbm: Int) : Verdict()
        data class Near(val id: String, val dbm: Int) : Verdict()
    }

    data class Rival(val id: String, val median: Int, val theirs: Int)

    private class Reading(val at: Double, val dbm: Int)

    // two scales, kept apart: advertisements, and an open link's own readings
    private var heard = LinkedHashMap<String, MutableList<Reading>>()
    private var linked = LinkedHashMap<String, MutableList<Reading>>()

    // the last advertisement from each phone, kept past the memory window
    private val lastAd = HashMap<String, Int>()
    private val lastAdAt = HashMap<String, Double>()

    /** What the last verdict fell short on, in words, for the log. */
    var why = ""
        private set

    /** Whether the last verdict came from the link's pool. */
    var decidedOnLink = false
        private set

    /** The threshold the last verdict was taken against. */
    val lastThreshold: Int get() = if (decidedOnLink) nearLinkedDbm else nearDbm

    /** An advertisement's reading. iOS reports 127 when it has none; Android's scale is the same. */
    fun add(id: String, dbm: Int, at: Double) {
        if (dbm >= 0 || dbm <= -120) return
        heard.getOrPut(id) { ArrayList() }.add(Reading(at, dbm))
        lastAd[id] = dbm
        lastAdAt[id] = at
    }

    /** An open link's own reading, on its own scale. */
    fun addLinked(id: String, dbm: Int, at: Double) {
        if (dbm >= 0 || dbm <= -120) return
        linked.getOrPut(id) { ArrayList() }.add(Reading(at, dbm))
    }

    fun forget() {
        heard = LinkedHashMap()
        linked = LinkedHashMap()
        lastAd.clear()
        lastAdAt.clear()
    }

    /** The most recent advertisement from a phone. */
    fun lastHeard(id: String): Int? = lastAd[id]

    private fun age(pool: LinkedHashMap<String, MutableList<Reading>>, now: Double): LinkedHashMap<String, MutableList<Reading>> {
        val kept = LinkedHashMap<String, MutableList<Reading>>()
        for ((id, r) in pool) {
            val left = r.filter { now - it.at <= memory }.toMutableList()
            if (left.isNotEmpty()) kept[id] = left
        }
        return kept
    }

    /** The strongest candidate worth opening a link to, before any verdict: two readings, no hold. */
    fun warmest(now: Double): String? {
        heard = age(heard, now)
        return heard.entries.map { Triple(it.key, median(it.value.map { r -> r.dbm }), it.value.size) }
            .filter { it.third >= 2 && it.second >= warmDbm }
            .sortedByDescending { it.second }
            .firstOrNull()?.first
    }

    /** Another receiver plainly nearer than the one the quiet link is open to, or null. */
    fun rival(target: String, now: Double): Rival? {
        heard = age(heard, now)
        val others = heard.entries.filter { it.key != target }
            .map { Triple(it.key, median(it.value.map { r -> r.dbm }), it.value) }
            .sortedByDescending { it.second }
        val best = others.firstOrNull() ?: return null
        if (best.third.size < minReadings || best.second < nearbyDbm) return null
        val span = best.third.last().at - best.third.first().at
        if (span < rivalHold - 0.05) return null
        val ads = heard[target]
        val lastAt = lastAdAt[target]
        val link = linked[target]?.filter { now - it.at <= memory }
        val theirs = when {
            ads != null -> median(ads.map { it.dbm })
            lastAd[target] != null && lastAt != null && now - lastAt <= 5 -> lastAd.getValue(target)
            link != null && link.isNotEmpty() -> median(link.map { it.dbm })
            else -> -127
        }
        return if (best.second >= theirs + tieDb) Rival(best.first, best.second, theirs) else null
    }

    fun verdict(now: Double): Verdict {
        heard = age(heard, now)
        linked = age(linked, now)
        // the link's own readings, when the advertisements have dried up: it confirms, never decides alone
        val one = linked.entries.firstOrNull { it.value.size >= minReadings }
        val advertised = one?.let { lastAd[it.key] }
        if (heard.isEmpty() && one != null && advertised != null && advertised >= nearDbm) {
            val med = median(one.value.map { it.dbm })
            val span = one.value.last().at - one.value.first().at
            if (med < nearLinkedDbm || span < hold - 0.05) {
                why = if (med < nearLinkedDbm) "too far, over the link"
                else String.format(Locale.ROOT, "heard over the link for %.2fs", span)
                return Verdict.Far(med)
            }
            why = ""
            decidedOnLink = true
            return Verdict.Near(one.key, med)
        }
        decidedOnLink = false
        val ranked = heard.entries.map { Triple(it.key, median(it.value.map { r -> r.dbm }), it.value) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return Verdict.Nothing
        val span = best.third.last().at - best.third.first().at
        // two receivers close together: the closer one wins, judged across a full second
        if (ranked.size > 1 && ranked[1].second >= best.second - tieDb && ranked[1].second >= nearDbm - tieDb) {
            if (best.second < nearDbm) return Verdict.Far(best.second)
            if (span < rivalHold - 0.05) {
                why = String.format(Locale.ROOT, "two phones close; choosing across %.1fs (%.2fs so far)", rivalHold, span)
                return Verdict.Far(best.second)
            }
            why = ""
            return Verdict.Near(best.first, best.second)
        }
        if (best.second < nearDbm || best.third.size < minReadings || span < hold - 0.05) {
            why = when {
                best.second < nearDbm -> "too far"
                best.third.size < minReadings -> "only ${best.third.size} reading(s)"
                else -> String.format(Locale.ROOT, "heard for %.2fs", span)
            }
            return Verdict.Far(best.second)
        }
        why = ""
        return Verdict.Near(best.first, best.second)
    }

    companion object {
        /** The middle reading, and for an even count the weaker of the two middle ones. */
        fun median(v: List<Int>): Int {
            val s = v.sorted()
            return if (s.isEmpty()) -127 else s[(s.size - 1) / 2]
        }
    }
}
