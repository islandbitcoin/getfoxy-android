package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.TestSupport.ALIASES
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The counter store, in a folder of its own for each test: counters never go
 * down, a range is written before it is handed out, one entry per derivation
 * path, moves are capped, and the files go aside when the seed changes (S6).
 * Ported from CounterStoreTests.swift.
 */
class CounterStoreTest {
    private val folder = TestSupport.tempDir("counters")
    private val v00 = "009a1f293253e41e"
    private val v01 = "01" + "ab".repeat(32)

    @After
    fun clean() {
        AtomicWrite.afterRename = null
        folder.setWritable(true)
        folder.deleteRecursively()
    }

    private fun store(now: Long = 1_800_000_000) = CounterStore(folder) { now }
    private fun onDisk() = CounterStore.parse(File(folder, CounterStore.FILE_NAME).readBytes())!!
    private fun indexOnDisk() = CounterStore.parseP2PK(File(folder, CounterStore.P2PK_FILE_NAME).readBytes())!!

    private inline fun <reified F : CounterStore.Failure> fails(block: () -> Unit): F {
        try {
            block()
        } catch (e: CounterStore.Failure) {
            if (e is F) return e
            fail("threw ${e::class.simpleName}, not ${F::class.simpleName}")
        }
        fail("did not throw ${F::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun reservesFollowOneAnotherAndAreWrittenFirst() {
        val s = store()
        assertEquals(emptyMap<String, Long>(), s.snapshot())
        AtomicWrite.afterRename = { assertEquals("on disk before the range is returned", mapOf(v00 to 3L), onDisk()) }
        assertEquals(0L, s.reserve(v00, 0, 3))
        AtomicWrite.afterRename = null
        assertEquals(3L, s.reserve(v00, 0, 10))
        assertEquals(0L, s.reserve(v01, 1, 1))
        assertEquals(mapOf(v00 to 13L, v01 to 1L), s.snapshot())
        assertEquals("a fresh store reads the same file", mapOf(v00 to 13L, v01 to 1L), store().snapshot())
    }

    @Test
    fun aCountOfZeroOnlyReads() {
        val s = store()
        assertEquals(0L, s.reserve(v00, 0, 0))
        assertFalse(File(folder, CounterStore.FILE_NAME).exists())
        s.reserve(v00, 0, 4)
        assertEquals(4L, s.reserve(v00, 0, 0))
        assertEquals(4L, s.next(v00))
    }

    @Test
    fun keysetIdsAreOneKeysetWhateverTheirCase() {
        val s = store()
        s.reserve(v00.uppercase(), 0, 5)
        assertEquals(5L, s.next(v00))
        assertEquals(5L, s.reserve(v00, 0, 1))
    }

    @Test
    fun reservingIsAtomicAcrossThreads() {
        val s = store()
        val starts = Collections.synchronizedList(ArrayList<Long>())
        val pool = Executors.newFixedThreadPool(16)
        repeat(64) { pool.execute { starts.add(s.reserve(v00, 0, 7)) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(64, starts.size)
        assertEquals("every range distinct, none skipped", (0 until 64).map { it * 7L }, starts.sorted())
        assertEquals(448L, s.next(v00))
        assertEquals(mapOf(v00 to 448L), onDisk())
    }

    @Test
    fun theKeysetsLastCounterIsTheLimit() {
        val s = store()
        assertEquals(mapOf(v00 to 0x7FFF_FFFEL), s.importMax(mapOf(v00 to 0x7FFF_FFFEL)))
        fails<CounterStore.Failure.OutOfRange> { s.reserve(v00, 0, 3) }
        assertEquals("a refused reserve moves nothing", 0x7FFF_FFFEL, s.next(v00))
        assertEquals(0x7FFF_FFFEL, s.reserve(v00, 0, 2))
        fails<CounterStore.Failure.OutOfRange> { s.reserve(v00, 0, 1) }
        fails<CounterStore.Failure.TooFarAhead> { s.advance(v00, (1L shl 31) + 1) }
        s.importMax(mapOf(v01 to (1L shl 53) - 1))
        assertEquals((1L shl 53) - 1, s.reserve(v01, 1, 1))
        assertEquals(1L shl 53, s.next(v01))
        assertEquals("one past the last is stored and read back", 1L shl 53, store().next(v01))
    }

    @Test
    fun reserveAtRefusesAnIssuedRangeAndBurnsUpToItsStart() {
        val s = store()
        s.reserve(v00, 0, 10)
        for (start in listOf(0L, 5L, 9L)) {
            val e = fails<CounterStore.Failure.AlreadyIssued> { s.reserveAt(v00, 0, start, 1) }
            assertEquals("range already issued", e.text)
        }
        assertEquals(10L, s.next(v00))
        s.reserveAt(v00, 0, 10, 2)
        assertEquals(12L, s.next(v00))
        s.reserveAt(v00, 0, 100, 5)
        assertEquals("the counters below 100 are burnt", 105L, s.next(v00))
        fails<CounterStore.Failure.AlreadyIssued> { s.reserveAt(v00, 0, 104, 1) }
        assertEquals(105L, s.next(v00))
    }

    @Test
    fun reserveAtMoreThanAHundredAheadIsRefused() {
        val s = store()
        s.reserve(v00, 0, 10)
        assertEquals("too far ahead", fails<CounterStore.Failure.TooFarAhead> { s.reserveAt(v00, 0, 111, 1) }.text)
        fails<CounterStore.Failure.TooFarAhead> { s.reserveAt(v00, 0, 0x7FFF_FFFFL, 2) }
        assertEquals(10L, s.next(v00))
        s.reserveAt(v00, 0, 110, 1)
        assertEquals(111L, s.next(v00))
        s.importMax(mapOf(v01 to (1L shl 53) - 10))
        fails<CounterStore.Failure.OutOfRange> { s.reserveAt(v01, 1, (1L shl 53) - 5, 10) }
    }

    @Test
    fun advanceNeverLowers() {
        val s = store()
        assertEquals(50L, s.advance(v00, 50))
        assertEquals(50L, s.advance(v00, 20))
        assertEquals(50L, s.advance(v00, 0))
        assertEquals(51L, s.advance(v00, 51))
        assertEquals(mapOf(v00 to 51L), onDisk())
        assertEquals("an unknown keyset at zero", 0L, s.advance(v01, 0))
        assertEquals("and nothing written for it", mapOf(v00 to 51L), s.snapshot())
    }

    @Test
    fun advanceGoesAHundredOrToWhatARestoreServed() {
        val s = store()
        assertEquals("too far ahead", fails<CounterStore.Failure.TooFarAhead> { s.advance(v00, 10_000) }.text)
        assertEquals("a refused move writes nothing", emptyMap<String, Long>(), s.snapshot())
        assertEquals(100L, s.advance(v00, 100))
        s.noteServed(ALIASES[2], 400)
        assertEquals("served through an alias counts for the entry", 400L, s.highestServed(v00))
        assertEquals(400L, s.advance(v00, 400))
        fails<CounterStore.Failure.TooFarAhead> { s.advance(v00, 501) }
        assertEquals(500L, s.advance(ALIASES[1], 500))
        assertEquals(mapOf(v00 to 500L), s.snapshot())
        s.noteServed(v00, 50)
        assertEquals("never lower", 400L, s.highestServed(v00))
    }

    @Test
    fun importTakesTheLargerOfEach() {
        val s = store()
        s.reserve(v00, 0, 30)
        assertEquals(mapOf(v00 to 30L, v01 to 44L), s.importMax(mapOf(v00 to 10L, v01 to 44L)))
        assertEquals(mapOf(v00 to 31L, v01 to 44L), s.importMax(mapOf(v00 to 31L, v01 to 2L)))
        assertEquals(mapOf(v00 to 31L, v01 to 44L), onDisk())
        assertEquals("the next reserve starts after the import", 44L, s.reserve(v01, 1, 1))
        assertEquals("cut to the 00 ceiling", mapOf(v00 to (1L shl 31), v01 to 45L), s.importMax(mapOf(v00 to 9_007_199_254_740_991)))
    }

    @Test
    fun importOnceAnswersOncePerInstall() {
        val flags = Flags(folder)
        val s = store()
        assertEquals(mapOf(v00 to 12L), s.importOnce(mapOf(v00 to 12L), flags))
        assertTrue(flags.isSet(CounterStore.IMPORTED_FLAG))
        assertEquals("counters were already imported", fails<CounterStore.Failure.AlreadyImported> { s.importOnce(mapOf(v00 to 40L), flags) }.text)
        assertEquals(12L, s.next(v00))
    }

    @Test
    fun raiseLiftsEachEntryAndLowersNone() {
        val s = store()
        s.reserve(v00, 0, 700)
        assertEquals("aliases raise the one entry; a zero adds nothing", mapOf(v00 to 900L, v01 to 250L),
            s.raise(mapOf(ALIASES[1] to 500L, ALIASES[2] to 900L, v01 to 250L, "00b4cd27d8861a44" to 0L)))
        assertEquals(mapOf(v00 to 900L, v01 to 250L), s.raise(mapOf(v01 to 100L)))
        assertEquals(mapOf(v00 to (1L shl 31), v01 to 250L), s.raise(mapOf(v00 to 9_000_000_000L)))
        assertEquals(mapOf(v00 to (1L shl 31), v01 to 250L), onDisk())
    }

    @Test
    fun everyAliasReachesTheFirstIdsEntry() {
        val s = store()
        assertEquals(0L, s.reserve(ALIASES[0], 0, 40))
        for (alias in ALIASES) {
            assertEquals(alias, 40L, s.next(alias))
            assertEquals("a peek", 40L, s.reserve(alias, 0, 0))
            fails<CounterStore.Failure.AlreadyIssued> { s.reserveAt(alias, 0, 39, 1) }
        }
        assertEquals(40L, s.reserve(ALIASES[3], 0, 5))
        s.reserveAt(ALIASES[2], 0, 50, 10)
        assertEquals(80L, s.advance(ALIASES[1], 80))
        assertEquals(mapOf(ALIASES[0] to 81L), s.importMax(mapOf(ALIASES[3] to 70L, ALIASES[2].uppercase() to 81L)))
        assertEquals("the snapshot keeps the first id", mapOf(ALIASES[0] to 81L), s.snapshot())
        assertEquals(mapOf(ALIASES[0] to 81L), store().snapshot())
    }

    @Test
    fun anImportOfAliasesIsOneEntry() {
        assertEquals("the smallest id of a new slot, at the largest value", mapOf(ALIASES[1] to 60L),
            store().importMax(mapOf(ALIASES[3] to 5L, ALIASES[1] to 60L, ALIASES[2] to 9L)))
    }

    @Test
    fun anOldFileWithAliasesIsMergedWhenRead() {
        File(folder, CounterStore.FILE_NAME).writeText("{\"${ALIASES[0]}\": 40, \"${ALIASES[2]}\": 90, \"$v01\": 3}")
        val s = store()
        assertEquals(mapOf(ALIASES[2] to 90L, v01 to 3L), s.snapshot())
        assertEquals(90L, s.next(ALIASES[0]))
        assertEquals(90L, s.reserve(ALIASES[1], 0, 1))
        assertEquals("written back merged with the next change", mapOf(ALIASES[2] to 91L, v01 to 3L), onDisk())
    }

    @Test
    fun theFileHoldsAtMost256Keysets() {
        val s = store()
        val many = (0 until 256).associate { "00%014x".format(it) to 1L }
        assertEquals(256, s.importMax(many).size)
        val extra = "00%014x".format(256)
        assertEquals("too many keysets", fails<CounterStore.Failure.TooManyKeysets> { s.reserve(extra, 0, 1) }.text)
        fails<CounterStore.Failure.TooManyKeysets> { s.advance(extra, 1) }
        fails<CounterStore.Failure.TooManyKeysets> { s.importMax(mapOf(extra to 1L)) }
        fails<CounterStore.Failure.TooManyKeysets> { s.raise(mapOf(v01 to 1L)) }
        assertEquals("a peek adds nothing, and is answered", 0L, s.reserve(extra, 0, 0))
        // an alias of a stored keyset is not a new one: index 5 + (2^31 − 1)
        val alias = "00%014x".format(5L + 0x7FFF_FFFFL)
        assertEquals(CounterRules.slot("00%014x".format(5)), CounterRules.slot(alias))
        assertEquals(1L, s.reserve(alias, 0, 1))
        assertEquals(256, s.snapshot().size)
    }

    @Test
    fun theLockIndexReservesForwardAndIsWrittenFirst() {
        val s = store()
        val file = File(folder, CounterStore.P2PK_FILE_NAME)
        assertEquals(0L, s.p2pkNext())
        assertFalse("reading writes nothing", file.exists())
        assertEquals("a peek", 0L, s.p2pkReserve(0))
        assertFalse(file.exists())
        AtomicWrite.afterRename = { assertEquals("on disk before the range is returned", 8L, indexOnDisk()) }
        assertEquals(0L, s.p2pkReserve(8))
        AtomicWrite.afterRename = null
        assertEquals(8L, s.p2pkReserve(1))
        assertEquals(9L, s.p2pkNext())
        assertEquals("a fresh store reads the same file", 9L, store().p2pkNext())
    }

    @Test
    fun theLockIndexIsNotInTheCounterFile() {
        val s = store()
        s.p2pkReserve(4)
        s.reserve(v00, 0, 2)
        assertEquals(mapOf(v00 to 2L), s.snapshot())
        val counters = File(folder, CounterStore.FILE_NAME).readText()
        assertFalse(counters.contains("p2pk"))
        assertNotNull(CounterStore.parse(counters.toByteArray()))
    }

    @Test
    fun theLockIndexStopsAtTheLastNormalChild() {
        File(folder, CounterStore.P2PK_FILE_NAME).writeText("{\"next\": 2147483646}")
        val fresh = store()
        assertEquals(0x7FFF_FFFEL, fresh.p2pkNext())
        fails<CounterStore.Failure.OutOfRange> { fresh.p2pkReserve(3) }
        assertEquals("a refused reserve moves nothing", 0x7FFF_FFFEL, fresh.p2pkNext())
        assertEquals(0x7FFF_FFFEL, fresh.p2pkReserve(2))
        assertEquals(1L shl 31, fresh.p2pkNext())
        fails<CounterStore.Failure.OutOfRange> { fresh.p2pkReserve(1) }
    }

    @Test
    fun anUnreadableLockIndexHandsOutNothing() {
        val file = File(folder, CounterStore.P2PK_FILE_NAME)
        for (bad in listOf("not json", "[1,2]", "{}", "{\"next\": -1}", "{\"next\": 1.5}", "{\"next\": true}",
            "{\"next\": \"4\"}", "{\"next\": 2147483649}", "{\"nxt\": 1}")) {
            file.writeText(bad)
            val s = store()
            fails<CounterStore.Failure.Unreadable> { s.p2pkNext() }
            fails<CounterStore.Failure.Unreadable> { s.p2pkReserve(1) }
            assertEquals("not overwritten", bad, file.readText())
        }
        file.writeText("{\"next\": 5, \"somethingNewer\": 1}")
        assertEquals("a key this build does not know is kept, not refused", 5L, store().p2pkNext())
    }

    @Test
    fun whatAScanServedIsRememberedAndNeverLowered() {
        val s = store()
        assertEquals(0L, s.highestP2PKServed())
        s.noteP2PKServed(300)
        assertEquals(300L, s.highestP2PKServed())
        s.noteP2PKServed(100)
        assertEquals("never lower", 300L, s.highestP2PKServed())
        s.noteP2PKServed(900)
        assertEquals(900L, s.highestP2PKServed())
        assertEquals("a new process has served nothing", 0L, store().highestP2PKServed())
    }

    @Test
    fun anUnreadableFileHandsOutNothingAndIsLeftAlone() {
        val file = File(folder, CounterStore.FILE_NAME)
        for (bad in listOf("not json", "[1,2]", "{\"$v00\": -1}", "{\"$v00\": 1.5}", "{\"$v00\": true}",
            "{\"$v00\": \"4\"}", "{\"00ZZ\": 4}", "{\"${v00.uppercase()}\": 4}", "{\"$v00\": 1e300}")) {
            file.writeText(bad)
            val s = store()
            assertEquals("the counters could not be read", fails<CounterStore.Failure.Unreadable> { s.reserve(v00, 0, 1) }.text)
            fails<CounterStore.Failure.Unreadable> { s.snapshot() }
            fails<CounterStore.Failure.Unreadable> { s.importMax(mapOf(v00 to 1L)) }
            assertEquals("a file that could not be read is not overwritten", bad, file.readText())
        }
    }

    @Test
    fun movedAsideOnAChangeOfSeedAndStartsEmpty() {
        val s = store(1_800_000_123)
        assertNull("no file, nothing to move", s.moveAside())
        s.reserve(v00, 0, 9)
        val aside = s.moveAside()!!
        assertEquals("foxy-counters.replaced.1800000123.json", aside.name)
        assertEquals("kept as it was", mapOf(v00 to 9L), CounterStore.parse(aside.readBytes()))
        assertFalse(File(folder, CounterStore.FILE_NAME).exists())
        assertEquals(emptyMap<String, Long>(), s.snapshot())
        assertEquals("the new seed's counters start at zero", 0L, s.reserve(v00, 0, 1))
        val again = s.moveAside()!!
        assertEquals("a second change in the same second does not overwrite the first", "foxy-counters.replaced.1800000124.json", again.name)
        assertEquals(mapOf(v00 to 9L), CounterStore.parse(aside.readBytes()))
        assertEquals(emptyMap<String, Long>(), store().snapshot())
    }

    @Test
    fun theLockIndexGoesAsideWithTheCountersUnderOneStamp() {
        val s = store(1_800_000_500)
        s.reserve(v00, 0, 3)
        s.p2pkReserve(6)
        assertEquals("foxy-counters.replaced.1800000500.json", s.moveAside()!!.name)
        assertEquals(6L, CounterStore.parseP2PK(File(folder, "foxy-p2pk.replaced.1800000500.json").readBytes()))
        assertFalse(File(folder, CounterStore.P2PK_FILE_NAME).exists())
        assertEquals(0L, s.p2pkNext())
        assertEquals(0L, s.highestP2PKServed())
        assertEquals(0L, store().p2pkNext())
    }

    @Test
    fun anIndexAloneIsStillMovedAside() {
        val s = store(1_800_000_600)
        s.p2pkReserve(2)
        assertNull("there was no counter file to return", s.moveAside())
        assertEquals(2L, CounterStore.parseP2PK(File(folder, "foxy-p2pk.replaced.1800000600.json").readBytes()))
        assertEquals(0L, s.p2pkNext())
    }

    @Test
    fun anUnreadableFileCanStillBeMovedAside() {
        File(folder, CounterStore.FILE_NAME).writeText("garbage")
        val s = store()
        fails<CounterStore.Failure.Unreadable> { s.snapshot() }
        assertNotNull(s.moveAside())
        assertEquals(emptyMap<String, Long>(), s.snapshot())
    }

    @Test
    fun aStoreWithNoFolderStartsEmptyAndMakesItOnItsFirstWrite() {
        val fresh = File(folder, "not yet")
        val counters = CounterStore(fresh)
        assertEquals(emptyMap<String, Long>(), counters.snapshot())
        assertEquals(0L, counters.next(v00))
        assertEquals(0L, counters.reserve(v00, 0, 0))
        assertEquals(emptyMap<String, Long>(), counters.importMax(emptyMap()))
        assertFalse("reading writes nothing", fresh.exists())
        assertEquals(mapOf(v00 to 4L), counters.importMax(mapOf(v00 to 4L)))
        assertEquals(mapOf(v00 to 4L), CounterStore.parse(File(fresh, CounterStore.FILE_NAME).readBytes()))
        assertEquals("a new store, as after a relaunch, reads what the first wrote", 4L, CounterStore(fresh).reserve(v00, 0, 2))
    }

    @Test
    fun theFileIsSortedJson() {
        val s = store()
        s.reserve(v01, 1, 2)
        s.reserve(v00, 0, 1)
        assertEquals("{\"$v00\":1,\"$v01\":2}", File(folder, CounterStore.FILE_NAME).readText())
    }

    @Test
    fun eachWriteIsARenameOfAFlushedTemporaryFileAndAFailedWriteChangesNothing() {
        val s = store()
        for (n in 1..20) s.reserve(v00, 0, n)
        assertEquals("no temporary file is left", listOf(CounterStore.FILE_NAME), folder.list()!!.toList())
        assertEquals(mapOf(v00 to 210L), onDisk())
        // a folder that cannot be written: the write fails and the file is as it was
        TestSupport.makeUnwritable(folder)
        try {
            assertEquals("the counters could not be saved", fails<CounterStore.Failure.Unwritable> { s.reserve(v00, 0, 1) }.text)
        } finally {
            folder.setWritable(true)
        }
        assertEquals(mapOf(v00 to 210L), onDisk())
        assertEquals("memory changes only once the file has", 210L, s.next(v00))
    }
}
