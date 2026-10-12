package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The keys a payment request locks ecash to: NUT-13's path m/129373'/10'/0'/0'/i,
 * a NORMAL last level checked against BIP-32's own vector, and the window a
 * private key may be asked in (S7). Ported from P2PKTests.swift with its vectors.
 */
class P2PKTest {
    private val saved = TestSupport.SAVED
    private val phone = TestSupport.ABANDON_ABOUT
    private fun seed(words: String) = NUT13.seed(words)
    private fun hex(b: ByteArray) = Bytes.hex(b)

    @Test
    fun aNormalChildMatchesBip32sOwnVector() {
        val master = NUT13.master(NUT13.Seed(Bytes.fromHex("000102030405060708090a0b0c0d0e0f")!!))
        assertEquals("e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35", hex(master.key))
        assertEquals("873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508", hex(master.chain))
        val hardened = NUT13.child(master, 0x8000_0000L)
        assertEquals("edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea", hex(hardened.key))
        assertEquals("47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141", hex(hardened.chain))
        val normal = NUT13.child(hardened, 1)
        assertEquals("m/0'/1 is a NORMAL child", "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368", hex(normal.key))
        assertEquals("2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19", hex(normal.chain))
    }

    private val savedKeys = listOf(
        Triple(0L, "120921e8a32e61ac8ef0d3680e698e72bb6ec9fc36f8a8a68111e8aa61542f32", "03b11b8eb689103aebc20cd57fe4c11b9f629dbd79b40e83b13c9ebd857b543391"),
        Triple(1L, "ed677d91ed3081bd0e122ee177b5a555c4ab7f49f8b501b339d76c979c4e40bf", "03aa4d871b3193ba1a3f69c525a5c1ee9f214f77ea8391ba3dadcb8eb6b53c672d"),
        Triple(2L, "b828a729e2352748505c8540a2293a4278d5c4bec3b3e3cd3d30460d89363b7a", "03a242e9753fabd1680800cc462ca94d17d49e8654986b3d9f0749625e2733fd74"),
        Triple(300L, "47ff48bfbc28a53b2deb8c1442bb46aa98ea5986de1a0ac509d6c54c3625a0ab", "0289876dc9ff4a6cad10444c9937a2c4a1dcebeeab0ce5453558d4bbf03b92290c"),
        Triple(20_000L, "6105c2567fc907fdb35efca75142088be9a76c786e2a9c26a7470560c29b5aea", "03b4f3c9f4ef6373ee2e15f618145c2a4cf78317fb388d84dce3de9d936da37c61"),
        Triple(0x7FFF_FFFFL, "c92da9f5eb99533f8a75b4a62dde1c8c89cec8d96f3f3466701a8b1364792bd3", "032dcf3ee2df4188fb5347539d154fead88b64675dba77f1b5851319c2f9912f25"),
    )

    private val phoneKeys = listOf(
        Triple(0L, "83686ff4170082b77dc0d20b9b726b24c07e10aaed01e33205a714c6e9621821", "03d5e349add0f7c450d20e0968ecdb9e5e4b64ceecfd1a5c8c90664b482ab37649"),
        Triple(1L, "f33b913c92a8544d089a9e5c989dab7d32522829354c81eeea807b285b3711fc", "037db85d2a01d197b59a906526300d675f9b46700106282ee55b2a414f25a7e618"),
        Triple(2L, "ba8fc1ee8ecc9c05b5ce1988d45a4c6dc9a4b8e0484777c53aa25c833ad9f49a", "038d7afee85b98e2d5a05c7bdea42be33d4717053d8eaf82d986bbd25a6c10424c"),
        Triple(300L, "27f7ba5e158dea44d2ef087ed5a775551d96a85294b70fa6d76d6c7cfef87d05", "0373bbee8538e14758c359c8f6f224f3b0b6e6cefadff0538c2eb9772b1f221448"),
        Triple(20_000L, "64003e5a7b96ee27e18a9aa5e7210b1b305a15b2de54df32108fa6d4a7b3f295", "030c8d8e1c56759fc071574aea3f8447178de1479ea0247651ede71f6e9d7f64ba"),
        Triple(0x7FFF_FFFFL, "427007b5869f404e204fab719831e31a89adbb663d6dd92c1ac40f0091d119d3", "02ea562cbdc3b291d49f36112a729820f9b2c6e12f5c610e6c49083a62da7a6dd1"),
    )

    @Test
    fun thePathIsTheOneNut13Writes() {
        assertEquals(129_373L, P2PK.PURPOSE)
        assertEquals(10L, P2PK.ACCOUNT)
        for ((words, vectors) in listOf(saved to savedKeys, phone to phoneKeys)) {
            val s = seed(words)
            for ((index, priv, pub) in vectors) {
                val pair = P2PK.key(s, index)
                assertEquals(index, pair.index)
                assertEquals("m/129373'/10'/0'/0'/$index", priv, hex(pair.privateKey))
                assertEquals("m/129373'/10'/0'/0'/$index", pub, hex(pair.publicKey))
                assertEquals(33, pair.publicKey.size)
            }
        }
    }

    @Test
    fun aHardenedLastLevelWouldGiveDifferentKeys() {
        val s = seed(phone)
        var parent = NUT13.master(s)
        for (level in longArrayOf(P2PK.PURPOSE, P2PK.ACCOUNT, 0, 0)) parent = NUT13.child(parent, level or 0x8000_0000L)
        val hardened = (0L until 3L).map { hex(NUT13.publicKey(NUT13.child(parent, it or 0x8000_0000L).key)) }
        val normal = P2PK.publicKeys(s, 0, 3).map { hex(it) }
        assertEquals(phoneKeys.take(3).map { it.third }, normal)
        for (key in hardened) assertFalse(normal.contains(key))
    }

    @Test
    fun aRangeIsTheSameAsOneAtATime() {
        val s = seed(phone)
        val range = P2PK.derive(s, 7, 5)
        assertEquals(listOf(7L, 8L, 9L, 10L, 11L), range.map { it.index })
        for (pair in range) {
            val alone = P2PK.key(s, pair.index)
            assertEquals(hex(alone.privateKey), hex(pair.privateKey))
            assertEquals(hex(alone.publicKey), hex(pair.publicKey))
        }
    }

    @Test
    fun eachPublicKeyIsItsOwnPrivateKeys() {
        for (pair in P2PK.derive(seed(saved), 0, 4)) assertEquals(hex(NUT13.publicKey(pair.privateKey)), hex(pair.publicKey))
    }

    @Test
    fun theSameIndexUnderAnotherSeedIsAnotherKey() {
        val one = P2PK.key(seed(saved), 4)
        val other = P2PK.key(seed(phone), 4)
        assertNotEquals(hex(one.publicKey), hex(other.publicKey))
        assertNotEquals(hex(one.privateKey), hex(other.privateKey))
    }

    @Test
    fun onlyNormalChildIndicesExist() {
        assertEquals(0x7FFF_FFFFL, P2PK.LAST_INDEX)
        assertEquals(1L shl 31, P2PK.LARGEST_NEXT)
        assertTrue(P2PK.indicesFit(0, 1))
        assertTrue(P2PK.indicesFit(0x7FFF_FFFFL, 1))
        assertFalse("2^31 is 0', a different key", P2PK.indicesFit(0x8000_0000L, 1))
        assertFalse(P2PK.indicesFit(0x7FFF_FFFFL, 2))
        assertFalse(P2PK.indicesFit(0, 0))
        assertFalse(P2PK.indicesFit(Long.MAX_VALUE, 1))
        try {
            P2PK.derive(seed(phone), 0x8000_0000L, 1)
            fail("2^31 derived")
        } catch (e: P2PK.Failure.BadIndex) {
            assertEquals("outside the lock-key window", SeedActions.problem(e))
        }
    }

    @Test
    fun aScanStopsAtTwentyThousand() {
        assertEquals(20_000L, P2PK.LAST_SCANNED)
        assertEquals(300, P2PK.MOST_SCANNED)
        assertEquals(64, P2PK.MOST_RESERVED)
        assertTrue(P2PK.scanFits(0, 300))
        assertTrue(P2PK.scanFits(19_700, 300))
        assertFalse(P2PK.scanFits(19_701, 300))
        assertFalse(P2PK.scanFits(20_000, 1))
        assertFalse(P2PK.scanFits(0, 0))
    }

    @Test
    fun aKeyIsAnsweredNearWhatIsReservedOrInsideWhatAScanServed() {
        assertEquals(RestoreWindow.BEYOND, P2PK.BEYOND)
        assertTrue(P2PK.keyAllowed(0, 0, 0))
        assertTrue(P2PK.keyAllowed(999, 0, 0))
        assertFalse(P2PK.keyAllowed(1000, 0, 0))
        assertTrue(P2PK.keyAllowed(500, 400, 0))
        assertTrue(P2PK.keyAllowed(1399, 400, 0))
        assertFalse(P2PK.keyAllowed(1400, 400, 0))
        assertFalse(P2PK.keyAllowed(1480, 0, 0))
        assertTrue("the walk that found it", P2PK.keyAllowed(1480, 0, 1600))
        assertFalse("the end is one past", P2PK.keyAllowed(1600, 0, 1600))
        assertFalse(P2PK.keyAllowed(0x8000_0000L, 0, Long.MAX_VALUE))
        assertTrue("next + 1000 overflowing is not a hole", P2PK.keyAllowed(0x7FFF_FFFFL, Long.MAX_VALUE, 0))
    }

    @Test
    fun theRepliesCarryKeysAndNothingElse() {
        val keys = P2PK.publicKeys(seed(phone), 0, 2)
        assertEquals("{\"start\":0,\"next\":2,\"pubkeys\":[\"${phoneKeys[0].third}\",\"${phoneKeys[1].third}\"]}", P2PK.pubkeysReply(0, 2, keys))
        assertEquals("{\"start\":9,\"next\":9,\"pubkeys\":[]}", P2PK.pubkeysReply(9, 9, emptyList()))
        val pair = P2PK.key(seed(phone), 1)
        assertEquals("{\"index\":1,\"privkey\":\"${phoneKeys[1].second}\",\"pubkey\":\"${phoneKeys[1].third}\"}", P2PK.keyReply(pair))
    }

    @Test
    fun wipingAPairClearsThePrivateHalf() {
        val pair = P2PK.key(seed(phone), 3)
        val pub = pair.publicKey.copyOf()
        pair.wipe()
        assertTrue(Bytes.isZero(pair.privateKey))
        assertTrue("the public half is not a secret and stays", pub.contentEquals(pair.publicKey))
    }
}
