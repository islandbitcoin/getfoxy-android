package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.TestSupport.ALIASES
import io.islandbitcoin.foxy.bridge.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Every P1 seed and counter action, run with a stub vault holding a known
 * phrase: their rules end to end (review H1, M1, M4, L5), and that no reply text
 * or error ever holds a word of the phrase or the seed (S2, review L12).
 * Ported from SeedActionsTests.swift (the P1 actions; seedAdopt, seedWipe and the
 * card's owner key are P3/LATER).
 */
class SeedActionsTest {
    /** The vault as SeedVault's rules treat it, in memory. */
    class StubVault {
        var words: String? = TestSupport.SAVED
        var failRead = false
        var writeProblem: String? = null
        val writes = ArrayList<String>()
        var windowOpen = true
        val windowClosedBy = ArrayList<String>()
        var reads = 0

        fun savedSeed(): SeedVault.SecretsSeed {
            reads++
            if (failRead) return SeedVault.SecretsSeed.Failed
            val w = words ?: return SeedVault.SecretsSeed.Absent
            return try {
                SeedVault.SecretsSeed.Found(NUT13.seed(w))
            } catch (e: NUT13.Failure) {
                SeedVault.SecretsSeed.Failed
            }
        }
    }

    private lateinit var folder: File
    private lateinit var vault: StubVault
    private lateinit var env: SeedEnvironment
    private val replies = ArrayList<SeedReply>()
    private val v00 = TestSupport.V00
    private val v01 = TestSupport.V01

    @Before
    fun setUp() {
        folder = TestSupport.tempDir("seed-actions")
        val v = StubVault()
        vault = v
        env = SeedEnvironment(
            savedSeed = { v.savedSeed() },
            seedPresent = { if (v.failRead) null else v.words != null },
            write = { words ->
                when {
                    v.failRead -> "The keychain did not answer (locked), so nothing was written."
                    v.words != null && v.words != words -> SeedVault.DIFFERENT_SEED
                    v.writeProblem != null -> v.writeProblem
                    else -> {
                        v.words = words
                        v.writes.add(words)
                        null
                    }
                }
            },
            counters = CounterStore(folder),
            candidates = SeedCandidates(),
            wordlist = { TestSupport.wordlist },
            generate = { TestSupport.MADE },
            migrationWindowOpen = { v.windowOpen },
            closeMigrationWindow = { why ->
                v.windowOpen = false
                v.windowClosedBy.add(why)
            },
            flags = Flags(folder),
        )
    }

    @After
    fun tearDown() {
        // L12: whatever the test did, no reply held a word or the seed
        SeedRepliesTest.assertNoWords(replies)
        folder.deleteRecursively()
    }

    private fun run(reply: SeedReply): SeedReply {
        replies.add(reply)
        assertTrue("an answer or an error, never both: $reply", (reply.text == null) != (reply.error == null))
        return reply
    }

    private fun range(id: String, start: Long, count: Int) =
        SeedActions.CounterRequest(id.lowercase(), NUT13.keysetVersion(id) ?: 9, start, count)

    @Suppress("UNCHECKED_CAST")
    private fun parsed(reply: SeedReply): Map<String, Any?> = reply.text?.let { Json.parse(it) as Map<String, Any?> } ?: emptyMap()
    private fun int(v: Any?) = (v as Double).toLong()

    @Test
    fun everyActionAnswersWithoutTheWordsOrTheSeed() {
        run(SeedActions.status(env))
        run(SeedActions.status(env, quiet = true))
        run(SeedActions.create(env))
        run(SeedActions.migrate(TestSupport.SAVED, env))
        run(SeedActions.migrate(TestSupport.TYPED_OTHER, env))
        vault.failRead = true
        run(SeedActions.status(env))
        run(SeedActions.status(env, quiet = true))
        run(SeedActions.create(env))
        run(SeedActions.migrate(TestSupport.SAVED, env))
        run(SeedActions.counterReserve(range(v00, 0, 3), env))
        vault.failRead = false
        run(SeedActions.countersImport(mapOf(v00 to 5L, v01 to 2L), env))
        run(SeedActions.countersImport(mapOf(v00 to 6L), env))
        run(SeedActions.counterSnapshot(env))
        run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 20), env))
        run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 90_000), env))
        run(SeedActions.counterReserve(range(v00, 0, 0), env))
        run(SeedActions.counterReserve(range(v00, 0, 4), env))
        run(SeedActions.counterReserve(range(v01, 0, 4), env))
        run(SeedActions.counterReserveAt(range(v00, 0, 2), env))
        run(SeedActions.counterReserveAt(range(v00, 30, 2), env))
        run(SeedActions.counterReserveAt(range(v00, 9_000, 2), env))
        run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(v00, 0, 50), null), env))
        run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(v00, 5_000, 1), null), env))
        run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(v00, 0, 1), "0123456789abcdef0123456789abcdef"), env))
        run(SeedActions.p2pkReserve(0, env))
        run(SeedActions.p2pkReserve(8, env))
        run(SeedActions.p2pkPubkeys(SeedActions.P2PKRange(0, 300), env))
        run(SeedActions.p2pkKey(0, env))
        run(SeedActions.p2pkKey(19_999, env))
        run(SeedActions.p2pkKey(1_000_000, env))
        vault.words = null
        vault.windowOpen = false
        run(SeedActions.migrate(TestSupport.SAVED, env))
        run(SeedActions.status(env))
        run(SeedActions.counterReserve(range(v00, 0, 1), env))
        assertTrue(replies.size >= 30)
        assertTrue("secrets were answered, and checked", replies.any { it.text?.contains("\"secrets\":[\"") == true })
        assertTrue("private keys were answered, and checked", replies.any { it.text?.contains("\"privkey\":\"") == true })
    }

    @Test
    fun anAliasReachesTheRealKeysetsCounterAndBurnsIt() {
        run(SeedActions.counterReserve(range(ALIASES[0], 0, 40), env))
        for (alias in ALIASES.drop(1)) {
            assertEquals(alias, "range already issued", run(SeedActions.counterReserveAt(range(alias, 0, 10), env)).error)
            assertEquals(alias, "range already issued", run(SeedActions.counterReserveAt(range(alias, 39, 1), env)).error)
        }
        assertEquals(40L, int(parsed(run(SeedActions.counterReserveAt(range(ALIASES[3], 40, 10), env)))["start"]))
        assertEquals("reserving through an alias burnt the real keyset's counters", 50L, env.counters.next(ALIASES[0]))
        assertEquals("never the alias's range again", 50L, int(parsed(run(SeedActions.counterReserve(range(ALIASES[0], 0, 1), env)))["start"]))
        assertEquals("one entry, under the first id", mapOf(ALIASES[0] to 51.0), parsed(run(SeedActions.counterSnapshot(env)))["counters"])
    }

    @Test
    fun anAliasGetsTheRealKeysetsRestoreWindowNotItsOwn() {
        run(SeedActions.counterReserve(range(ALIASES[0], 0, 10), env))
        assertNull(run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(ALIASES[1], 910, 100), null), env)).error)
        assertEquals("outside the restore window", run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(ALIASES[2], 911, 100), null), env)).error)
        assertEquals("too far ahead", run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(ALIASES[3], 5_000), env)).error)
        assertEquals(10L, env.counters.next(ALIASES[0]))
    }

    @Test
    fun counterAdvanceIsCappedAtAHundredOrWhatARestoreServed() {
        run(SeedActions.counterReserve(range(v00, 0, 40), env))
        assertEquals("too far ahead", run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 10_040), env)).error)
        assertEquals(140L, int(parsed(run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 140), env)))["next"]))
        assertEquals("never lower", 140L, int(parsed(run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 3), env)))["next"]))
        assertNull(run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(v00, 340, 100), null), env)).error)
        assertEquals(440L, int(parsed(run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 440), env)))["next"]))
        assertEquals("too far ahead", run(SeedActions.counterAdvance(SeedActions.AdvanceRequest(v00, 10_000), env)).error)
    }

    @Test
    fun counterReserveAtStartsAtMostAHundredAheadBeforeTheSeedIsRead() {
        run(SeedActions.counterReserve(range(v01, 0, 12), env))
        vault.failRead = true
        assertEquals("too far ahead", run(SeedActions.counterReserveAt(range(v01, 113, 5), env)).error)
        assertEquals("range already issued", run(SeedActions.counterReserveAt(range(v01, 11, 5), env)).error)
        vault.failRead = false
        assertNull(run(SeedActions.counterReserveAt(range(v01, 112, 5), env)).error)
        assertEquals(117L, env.counters.next(v01))
    }

    @Test
    fun countersImportAnswersOnce() {
        val first = run(SeedActions.countersImport(mapOf(v00 to 30L, v01 to 9_007_199_254_740_991L), env))
        assertEquals(mapOf(v00 to 30.0, v01 to 9_007_199_254_740_991.0), parsed(first)["counters"])
        assertEquals("counters were already imported", run(SeedActions.countersImport(mapOf(v00 to 31L), env)).error)
        assertEquals("counters were already imported", run(SeedActions.countersImport(emptyMap(), env)).error)
        assertEquals(30L, env.counters.next(v00))
        assertTrue(env.flags.isSet(CounterStore.IMPORTED_FLAG))
    }

    @Test
    fun anImportThatFailsCanBeSentAgain() {
        File(folder, CounterStore.FILE_NAME).writeText("garbage")
        assertEquals("the counters could not be read", run(SeedActions.countersImport(mapOf(v00 to 3L), env)).error)
        assertFalse(env.flags.isSet(CounterStore.IMPORTED_FLAG))
    }

    @Test
    fun importedValuesAreCappedPerVersion() {
        assertEquals(mapOf(v00 to (1L shl 31).toDouble()), parsed(run(SeedActions.countersImport(mapOf(v00 to 9_007_199_254_740_991L), env)))["counters"])
        assertEquals("outside the keyset's counters", run(SeedActions.counterReserve(range(v00, 0, 1), env)).error)
    }

    @Test
    fun migrateStillComparesWithTheWindowClosedAndNeverWrites() {
        vault.windowOpen = false
        assertEquals("{\"migrated\":false,\"same\":true}", run(SeedActions.migrate(TestSupport.SAVED, env)).text)
        assertEquals("{\"different\":true,\"migrated\":false}", run(SeedActions.migrate(TestSupport.TYPED_OTHER, env)).text)
        vault.words = null
        assertEquals("no migration here", run(SeedActions.migrate(TestSupport.SAVED, env)).error)
        assertTrue(vault.writes.isEmpty())
        assertNull(vault.words)
        vault.failRead = true
        assertEquals("the seed could not be read", run(SeedActions.migrate(TestSupport.SAVED, env)).error)
    }

    @Test
    fun migrateWritesOnlyWithTheWindowOpenAndClosesItOnAnAnswer() {
        vault.words = null
        vault.failRead = true
        assertEquals("the seed could not be read", run(SeedActions.migrate(TestSupport.SAVED, env)).error)
        assertTrue("an error leaves it open for the words to come again", vault.windowOpen)
        vault.failRead = false
        assertEquals("{\"migrated\":true}", run(SeedActions.migrate(TestSupport.SAVED, env)).text)
        assertEquals(listOf(TestSupport.SAVED), vault.writes)
        assertEquals(listOf("seedMigrate answered"), vault.windowClosedBy)
    }

    @Test
    fun migrateNeverWritesOverADifferentSeedEvenOneSavedMeanwhile() {
        val outcome = SeedActions.decideMigration(TestSupport.SAVED, true, SeedVault.SecretsSeed.Absent) { SeedVault.DIFFERENT_SEED }
        assertEquals(SeedMigrationWindow.Outcome.Different, outcome)
        assertEquals(SeedMigrationWindow.Outcome.Refused("the keystore refused the write"),
            SeedActions.decideMigration(TestSupport.SAVED, true, SeedVault.SecretsSeed.Absent) { "the keystore refused the write" })
        for (o in listOf(SeedMigrationWindow.Outcome.Migrated, SeedMigrationWindow.Outcome.Same, SeedMigrationWindow.Outcome.Different)) {
            assertFalse(SeedActions.migrateReply(o).text!!.contains("legal"))
        }
        SeedActions.decideMigration(TestSupport.SAVED, true, SeedVault.SecretsSeed.Found(NUT13.seed(TestSupport.ABANDON_ABOUT))) {
            fail("a different saved seed was written over")
            null
        }
    }

    @Test
    fun createNeverWritesOverASavedSeedAndSetsLeftoverCountersAside() {
        assertEquals("a seed already exists", run(SeedActions.create(env)).error)
        vault.words = null
        env.counters.reserve(v00, 0, 9)
        assertEquals("{\"created\":true}", run(SeedActions.create(env)).text)
        assertEquals(TestSupport.MADE, vault.words)
        assertTrue(vault.windowClosedBy.contains("a seed was made on the phone"))
        assertEquals("counters with no seed beside them went aside", emptyMap<String, Long>(), env.counters.snapshot())
        assertTrue(folder.list()!!.any { it.startsWith("foxy-counters.replaced.") })
    }

    @Test
    fun aCreateThatCannotWriteSaysWhyAndKeepsNothing() {
        vault.words = null
        vault.writeProblem = "the keystore refused the write"
        assertEquals("the keystore refused the write", run(SeedActions.create(env)).error)
        assertNull(vault.words)
        assertTrue("the window stays open", vault.windowOpen)
        vault.writeProblem = null
        val noList = SeedEnvironment(env.savedSeed, env.seedPresent, env.write, env.counters, env.candidates, { null }, env.generate,
            env.migrationWindowOpen, env.closeMigrationWindow, env.flags)
        assertEquals("the wordlist could not be checked", run(SeedActions.create(noList)).error)
    }

    @Test
    fun statusClosesTheWindowOnlyWhenASeedIsFound() {
        vault.words = null
        assertEquals("{\"exists\":false}", run(SeedActions.status(env)).text)
        assertEquals("{\"exists\":false}", run(SeedActions.status(env, quiet = true)).text)
        assertTrue(vault.windowOpen)
        vault.words = TestSupport.SAVED
        assertEquals("{\"exists\":true}", run(SeedActions.status(env, quiet = true)).text)
        assertFalse(vault.windowOpen)
        env.passcodeSet = { false }
        assertEquals("{\"exists\":true,\"passcode\":false}", run(SeedActions.status(env)).text)
        val readsBefore = vault.reads
        run(SeedActions.status(env, quiet = true))
        assertEquals("quiet reads nothing", readsBefore, vault.reads)
    }

    @Test
    fun reservingLockKeysMovesTheIndexAndAnswersPublicKeysOnly() {
        assertEquals("{\"start\":0,\"next\":0,\"pubkeys\":[]}", run(SeedActions.p2pkReserve(0, env)).text)
        assertEquals("a peek moves nothing", 0L, env.counters.p2pkNext())
        val first = run(SeedActions.p2pkReserve(8, env))
        assertEquals(0L, int(parsed(first)["start"]))
        assertEquals(8L, int(parsed(first)["next"]))
        @Suppress("UNCHECKED_CAST")
        val keys = parsed(first)["pubkeys"] as List<String>
        assertEquals(8, keys.size)
        assertEquals(8, keys.toSet().size)
        assertTrue(keys.all { it.length == 66 && (it.startsWith("02") || it.startsWith("03")) })
        assertFalse(first.text!!.contains("priv"))
        assertEquals(8L, env.counters.p2pkNext())
        val second = run(SeedActions.p2pkReserve(2, env))
        assertEquals(8L, int(parsed(second)["start"]))
        assertEquals(Bytes.hex(P2PK.key(NUT13.seed(TestSupport.SAVED), 3).publicKey), keys[3])
    }

    @Test
    fun aReservationWithNoSeedMovesNothing() {
        vault.words = null
        assertEquals("no seed", run(SeedActions.p2pkReserve(4, env)).error)
        assertEquals("no seed", run(SeedActions.counterReserve(range(v00, 0, 4), env)).error)
        assertEquals(0L, env.counters.p2pkNext())
        assertEquals(0L, env.counters.next(v00))
        vault.words = TestSupport.SAVED
        vault.failRead = true
        assertEquals("the seed could not be read", run(SeedActions.p2pkReserve(4, env)).error)
        assertEquals("the seed could not be read", run(SeedActions.counterReserve(range(v00, 0, 4), env)).error)
        assertEquals(0L, env.counters.p2pkNext())
        assertEquals(0L, env.counters.next(v00))
    }

    @Test
    fun aScanMovesNothingAndOpensWhatItWalked() {
        run(SeedActions.p2pkReserve(1, env))
        assertEquals("outside the lock-key window", run(SeedActions.p2pkKey(1480, env)).error)
        val walked = run(SeedActions.p2pkPubkeys(SeedActions.P2PKRange(1300, 300), env))
        assertEquals(1300L, int(parsed(walked)["start"]))
        assertEquals("the index is reported and not moved", 1L, int(parsed(walked)["next"]))
        @Suppress("UNCHECKED_CAST")
        val keys = parsed(walked)["pubkeys"] as List<String>
        assertEquals(300, keys.size)
        val key = parsed(run(SeedActions.p2pkKey(1480, env)))
        assertEquals(1480L, int(key["index"]))
        assertEquals(keys[180], key["pubkey"])
        assertEquals(64, (key["privkey"] as String).length)
        assertEquals(setOf("index", "privkey", "pubkey"), key.keys)
        assertEquals("outside the lock-key window", run(SeedActions.p2pkKey(1600, env)).error)
    }

    @Test
    fun aKeyNearWhatIsReservedNeedsNoWalk() {
        run(SeedActions.p2pkReserve(8, env))
        for (index in listOf(0L, 7L, 8L, 1007L)) assertTrue("$index", run(SeedActions.p2pkKey(index, env)).text != null)
        assertEquals("outside the lock-key window", run(SeedActions.p2pkKey(1008, env)).error)
    }

    @Test
    fun aDamagedIndexFileStillLetsAWalkFindAndOpenAKey() {
        File(folder, CounterStore.P2PK_FILE_NAME).writeText("not json at all")
        assertEquals("the counters could not be read", run(SeedActions.p2pkReserve(4, env)).error)
        val walked = run(SeedActions.p2pkPubkeys(SeedActions.P2PKRange(0, 300), env))
        assertEquals("told as 0", 0L, int(parsed(walked)["next"]))
        @Suppress("UNCHECKED_CAST")
        val keys = parsed(walked)["pubkeys"] as List<String>
        assertEquals(keys[250], parsed(run(SeedActions.p2pkKey(250, env)))["pubkey"])
        assertEquals("the counters could not be read", run(SeedActions.p2pkKey(5_000, env)).error)
    }

    @Test
    fun aCandidateIsUnknownInP1() {
        assertEquals("unknown candidate",
            run(SeedActions.restoreSecrets(SeedActions.RestoreRequest(range(v00, 0, 1), "0123456789abcdef0123456789abcdef"), env)).error)
    }

    @Test
    fun theErrorsThePageReads() {
        assertEquals("range already issued", SeedActions.problem(CounterStore.Failure.AlreadyIssued()))
        assertEquals("the counters could not be read", SeedActions.problem(CounterStore.Failure.Unreadable()))
        assertEquals("unknown candidate", SeedActions.problem(SeedCandidates.Failure.Unknown()))
        assertEquals("no seed", SeedActions.problem(SeedActions.Refusal("no seed")))
        assertEquals("the secrets could not be derived", SeedActions.problem(NUT13.Failure.InvalidKey()))
        assertEquals("too far ahead", SeedActions.problem(CounterStore.Failure.TooFarAhead()))
        assertEquals("counters were already imported", SeedActions.problem(CounterStore.Failure.AlreadyImported()))
        assertEquals("too many keysets", SeedActions.problem(CounterStore.Failure.TooManyKeysets()))
    }

    @Test
    fun theReplyShapes() {
        assertEquals("{\"exists\":true}", SeedActions.json(mapOf("exists" to true)))
        assertEquals("{\"created\":true}", SeedActions.json(mapOf("created" to true)))
        assertEquals("{\"counters\":{}}", SeedActions.countersReply(emptyMap()))
        assertEquals("{\"counters\":{\"$v00\":7,\"$v01\":0}}", SeedActions.countersReply(mapOf(v00 to 7L, v01 to 0L)))
        assertEquals("{\"keysetId\":\"$v00\",\"next\":9007199254740991}", SeedActions.json(mapOf("keysetId" to v00, "next" to 9_007_199_254_740_991L)))
        assertEquals("{\"keysetId\":\"$v00\",\"start\":12,\"secrets\":[],\"blindingFactors\":[]}", NUT13.reply(v00, 12, emptyList()))
    }
}
