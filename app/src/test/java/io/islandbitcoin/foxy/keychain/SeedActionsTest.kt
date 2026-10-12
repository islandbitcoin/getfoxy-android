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
 * Ported from SeedActionsTests.swift (the P1 actions, and a card's owner key since
 * ENG-666; seedAdopt and seedWipe are SeedActionsP3Test's).
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
        // a card's owner key and its signatures, and what they refuse (ENG-666)
        run(SeedActions.cardOwnerKey(cardKeyBytes, env))
        run(SeedActions.cardOwnerKey(byteArrayOf(0x04) + cardKeyBytes.copyOfRange(1, 33), env))
        run(SeedActions.cardOwnerKey(ByteArray(0), env))
        for (label in CardOwner.Label.entries) run(SeedActions.cardOwnerSign(sampleRequest(label), env))
        run(SeedActions.cardOwnerSign(CardOwner.SignRequest(cardKeyBytes, CardOwner.Label.SET_LIMIT, nonceBytes, byteArrayOf(1, 2, 3)), env))
        vault.words = null
        vault.windowOpen = false
        run(SeedActions.migrate(TestSupport.SAVED, env))
        run(SeedActions.status(env))
        run(SeedActions.counterReserve(range(v00, 0, 1), env))
        run(SeedActions.cardOwnerKey(cardKeyBytes, env))
        run(SeedActions.cardOwnerSign(sampleRequest(CardOwner.Label.LOAD), env))
        assertTrue(replies.size >= 30)
        assertTrue("an owner key was answered, and checked", replies.any { it.text?.contains("\"pub\":\"04") == true })
        assertTrue("signatures were answered, and checked", replies.any { it.text?.contains("\"sig\":\"30") == true })
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

    // MARK: A card's owner key (ENG-666; SeedActionsTests.swift, "A card's owner key")

    /** A card's compressed public key (secp256k1's generator), as the page gives it and as bytes. */
    private val cardKey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val cardKeyBytes: ByteArray get() = Bytes.fromHex(cardKey)!!
    private val nonce = "000102030405060708090a0b0c0d0e0f"
    private val nonceBytes: ByteArray get() = Bytes.fromHex(nonce)!!

    private fun bytes(hex: String): ByteArray = Bytes.fromHex(hex) ?: ByteArray(0)

    /** A request of each label with a value of the shape it takes. */
    private fun sampleValue(label: CardOwner.Label): String = when (label) {
        CardOwner.Label.CHANGE_PIN -> "31323334"
        CardOwner.Label.SET_LIMIT -> "000186a0"
        CardOwner.Label.SET_OWNER -> "04" + "ab".repeat(64)
        CardOwner.Label.SET_CARD -> "01" + "02" + "22".repeat(32) + "04" + "33".repeat(64) + "03" + "6d6d6d"
        CardOwner.Label.LOAD, CardOwner.Label.RESET -> ""
    }

    private fun sampleRequest(label: CardOwner.Label, key: String? = null) =
        CardOwner.SignRequest(bytes(key ?: cardKey), label, nonceBytes, bytes(sampleValue(label)))

    @Suppress("UNCHECKED_CAST")
    private fun fixtureCases(): List<Map<String, Any?>> = (TestSupport.fixture("card-owner-vectors.json") as Map<String, Any?>)["cases"] as List<Map<String, Any?>>

    /** What the page is given: one `pub`, 130 lowercase hex characters. */
    private fun ownerPub(key: String): String? {
        val reply = run(SeedActions.cardOwnerKey(bytes(key), env))
        val obj = parsed(reply)
        assertEquals("nothing but the public key", if (reply.text == null) emptySet() else setOf("pub"), obj.keys)
        return obj["pub"] as String?
    }

    /** The signature, or null: one `sig` and nothing else. */
    private fun ownerSig(request: CardOwner.SignRequest): String? {
        val reply = run(SeedActions.cardOwnerSign(request, env))
        val obj = parsed(reply)
        assertEquals("nothing but the signature", if (reply.text == null) emptySet() else setOf("sig"), obj.keys)
        return obj["sig"] as String?
    }

    /** Whether `sig` is the owner key's signature over "FoxyCard/" + label ‖ nonce ‖ value. */
    private fun verifies(sig: String?, pub: String?, label: String, nonce: String, value: String): Boolean {
        if (sig == null || pub == null) return false
        return P256.verify(bytes(pub), "FoxyCard/$label".toByteArray(Charsets.UTF_8) + bytes(nonce) + bytes(value), bytes(sig))
    }

    /** The public key is Node's, for the two phrases the stub vault can hold, and moves nothing. */
    @Test
    fun theOwnerPublicKeyIsTheSeedAndTheKeysAndMovesNothing() {
        val cases = fixtureCases()
        vault.words = TestSupport.ABANDON_ABOUT
        assertEquals(cases[0]["pub"], ownerPub(cardKey))
        assertEquals("asked again, answered the same", cases[0]["pub"], ownerPub(cardKey))
        // the same words on a new phone give the same key; other words, another
        vault.words = TestSupport.SAVED
        assertEquals(TestSupport.SAVED, cases[1]["mnemonic"])
        val elsewhere = ownerPub(cases[1]["cardKey"] as String)
        assertEquals(cases[1]["pub"], elsewhere)
        assertFalse(elsewhere == cases[0]["pub"])
        // another card under the same seed, and one bit of the key
        val sameSeed = ownerPub(cardKey)
        assertFalse(sameSeed == ownerPub("03" + cardKey.drop(2)))
        assertFalse(sameSeed == ownerPub(cardKey.dropLast(2) + "99"))
        val pub = elsewhere!!
        assertEquals(130, pub.length)
        assertTrue(pub.startsWith("04"))
        assertEquals(pub.lowercase(), pub)
        // no counter and no index moved
        assertEquals(emptyMap<String, Long>(), env.counters.snapshot())
        assertEquals(0L, env.counters.p2pkNext())
    }

    /** A signature made on the phone verifies against the key cardOwnerKey gave, for each label, and only for what was asked. */
    @Test
    fun aSignatureVerifiesAgainstTheOwnerKeyForWhatWasAskedAndNothingElse() {
        vault.words = TestSupport.ABANDON_ABOUT
        val pub = ownerPub(cardKey)
        val otherCard = "03" + cardKey.drop(2)
        for (label in CardOwner.Label.entries) {
            val value = sampleValue(label)
            val sig = ownerSig(sampleRequest(label))
            assertTrue(label.raw, verifies(sig, pub, label.raw, nonce, value))
            // over the label, the nonce and the value together: change any one and it is no signature
            for (other in CardOwner.Label.entries) {
                if (other != label) assertFalse("${label.raw} as ${other.raw}", verifies(sig, pub, other.raw, nonce, value))
            }
            assertFalse("not a lock", verifies(sig, pub, "lock", nonce, value))
            assertFalse("not a time", verifies(sig, pub, "time", nonce, value))
            assertFalse("not a tag", verifies(sig, pub, "tag", nonce, value))
            assertFalse("another nonce", verifies(sig, pub, label.raw, "ff" + nonce.drop(2), value))
            assertFalse("a longer value", verifies(sig, pub, label.raw, nonce, value + "00"))
            if (value.isNotEmpty()) {
                val changed = value.dropLast(2) + if (value.endsWith("00")) "01" else "00"
                assertFalse("another value", verifies(sig, pub, label.raw, nonce, changed))
            }
            // and not another card's key
            assertFalse(verifies(sig, ownerPub(otherCard), label.raw, nonce, value))
        }
        // the signature is for this card: asked for another card's, it is that key's
        val sig3 = ownerSig(sampleRequest(CardOwner.Label.LOAD, key = otherCard))
        assertTrue(verifies(sig3, ownerPub(otherCard), "load", nonce, ""))
        assertFalse(verifies(sig3, pub, "load", nonce, ""))
        // another seed signs as another owner
        vault.words = TestSupport.SAVED
        val alien = ownerSig(sampleRequest(CardOwner.Label.LOAD))
        assertFalse(verifies(alien, pub, "load", nonce, ""))
        assertTrue(verifies(alien, ownerPub(cardKey), "load", nonce, ""))
        assertEquals(emptyMap<String, Long>(), env.counters.snapshot())
        assertEquals(0L, env.counters.p2pkNext())
    }

    /** Ours verify against Node's public keys: the stub vault holds two of its phrases. */
    @Test
    fun ourSignaturesVerifyAgainstTheKeysNodeWorkedOut() {
        var checked = 0
        for ((index, c) in fixtureCases().withIndex()) {
            val words = c["mnemonic"] as? String ?: continue
            vault.words = words
            val card = c["cardKey"] as String
            @Suppress("UNCHECKED_CAST")
            for (s in c["signatures"] as List<Map<String, String>>) {
                val label = s.getValue("label")
                val value = s.getValue("value")
                val n = s.getValue("nonce")
                val request = CardOwner.signRequest(mapOf("key" to card, "label" to label, "nonce" to n, "value" to value))
                assertTrue("case $index $label", request != null)
                assertTrue("case $index $label", verifies(ownerSig(request!!), c["pub"] as String, label, n, value))
                checked++
            }
        }
        assertEquals(12, checked)
    }

    @Test
    fun theOwnerAnswersHoldNeitherTheWordsNorTheSeedNorTheKey() {
        vault.words = TestSupport.ABANDON_ABOUT
        val seed = NUT13.seed(TestSupport.ABANDON_ABOUT)
        val scalar = CardOwner.scalar(seed, cardKeyBytes)!!
        val scalarHex = Bytes.hex(scalar)
        Bytes.wipe(scalar)
        val seedHex = seed.hex()
        val texts = ArrayList<String>()
        val key = run(SeedActions.cardOwnerKey(cardKeyBytes, env))
        assertNull(key.error)
        val pubText = key.text!!
        val pub = parsed(key)["pub"] as String
        assertEquals("{\"pub\":\"$pub\"}", pubText)
        texts.add(pubText)
        for (label in CardOwner.Label.entries) {
            val reply = run(SeedActions.cardOwnerSign(sampleRequest(label), env))
            assertNull(reply.error)
            val text = reply.text!!
            val sig = parsed(reply)["sig"] as String
            assertEquals("{\"sig\":\"$sig\"}", text)
            assertEquals(sig.lowercase(), sig)
            assertTrue("DER", sig.startsWith("30"))
            texts.add(text)
        }
        // refusals say a fixed thing too
        vault.words = null
        texts.addAll(listOfNotNull(run(SeedActions.cardOwnerKey(cardKeyBytes, env)).error, run(SeedActions.cardOwnerSign(sampleRequest(CardOwner.Label.LOAD), env)).error))
        for (text in texts) {
            val lower = text.lowercase()
            for (part in listOf(seedHex, seedHex.take(32), seedHex.takeLast(32), scalarHex, scalarHex.take(32), scalarHex.takeLast(32), "abandon", "about")) {
                assertFalse("an answer holds ${part.take(12)}", lower.contains(part))
            }
        }
        assertFalse(pub.contains(scalarHex))
        assertEquals(130, pub.length)
    }

    /** With no seed there is nothing to derive from: a refusal, and no answer. A seed that cannot be read is another. */
    @Test
    fun withNoSeedTheOwnerKeyAndItsSignaturesAreRefused() {
        vault.words = null
        for (reply in listOf(run(SeedActions.cardOwnerKey(cardKeyBytes, env)), run(SeedActions.cardOwnerSign(sampleRequest(CardOwner.Label.CHANGE_PIN), env)))) {
            assertNull(reply.text)
            assertEquals("no seed", reply.error)
        }
        vault.words = TestSupport.SAVED
        vault.failRead = true
        for (reply in listOf(run(SeedActions.cardOwnerKey(cardKeyBytes, env)), run(SeedActions.cardOwnerSign(sampleRequest(CardOwner.Label.SET_CARD), env)))) {
            assertNull(reply.text)
            assertEquals("the seed could not be read", reply.error)
        }
    }

    /** A key that is not a compressed public key, and a request that is not whole, are refused before the seed is read. */
    @Test
    fun aRequestThatIsNotWholeIsRefusedBeforeTheSeedIsRead() {
        val body = ByteArray(32) { 0x11 }
        val before = vault.reads
        val notCardKeys = listOf(ByteArray(0), byteArrayOf(0x02) + body.copyOf(31), byteArrayOf(0x04) + body, byteArrayOf(0x00) + body,
            byteArrayOf(0x02) + body + byteArrayOf(0x11), byteArrayOf(0x04) + body + body)
        for (key in notCardKeys) {
            val reply = run(SeedActions.cardOwnerKey(key, env))
            assertNull("${key.size} bytes", reply.text)
            assertEquals("bad request", reply.error)
            val sign = run(SeedActions.cardOwnerSign(CardOwner.SignRequest(key, CardOwner.Label.LOAD, nonceBytes, ByteArray(0)), env))
            assertNull(sign.text)
            assertEquals("bad request", sign.error)
        }
        val k = cardKeyBytes
        val wrongShapes = listOf(
            CardOwner.SignRequest(k, CardOwner.Label.LOAD, nonceBytes, byteArrayOf(0)),
            CardOwner.SignRequest(k, CardOwner.Label.CHANGE_PIN, nonceBytes, byteArrayOf(0x31, 0x32, 0x33)),
            CardOwner.SignRequest(k, CardOwner.Label.CHANGE_PIN, nonceBytes, byteArrayOf(0x31, 0x32, 0x33, 0x3A)),
            CardOwner.SignRequest(k, CardOwner.Label.SET_LIMIT, nonceBytes, byteArrayOf(0, 0, 0)),
            CardOwner.SignRequest(k, CardOwner.Label.SET_OWNER, nonceBytes, byteArrayOf(0x02) + ByteArray(64)),
            CardOwner.SignRequest(k, CardOwner.Label.SET_CARD, nonceBytes, bytes(sampleValue(CardOwner.Label.SET_CARD)).let { it.copyOf(it.size - 1) }),
            CardOwner.SignRequest(k, CardOwner.Label.SET_CARD, nonceBytes, ByteArray(0)),
            CardOwner.SignRequest(k, CardOwner.Label.LOAD, nonceBytes.copyOf(15), ByteArray(0)),
            CardOwner.SignRequest(k, CardOwner.Label.LOAD, nonceBytes + byteArrayOf(0), ByteArray(0)),
            CardOwner.SignRequest(k, CardOwner.Label.LOAD, ByteArray(0), ByteArray(0)),
        )
        for (request in wrongShapes) {
            assertFalse(request.isWellFormed)
            val reply = run(SeedActions.cardOwnerSign(request, env))
            assertNull("$request", reply.text)
            assertEquals("bad request", reply.error)
        }
        assertEquals("the seed was not asked for", before, vault.reads)
        // and a whole one is the one thing that asks for it
        run(SeedActions.cardOwnerKey(cardKeyBytes, env))
        assertEquals(before + 1, vault.reads)
        run(SeedActions.cardOwnerSign(sampleRequest(CardOwner.Label.LOAD), env))
        assertEquals(before + 2, vault.reads)
    }
}
