package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * NUT-13 derived natively is cashu-ts's derivation byte for byte: the spec's
 * vectors, and the bundled cashu-ts's own answers (856 of them) for random
 * mnemonics, both keyset versions and the counters at the edges (S6, S7).
 * Ported from NUT13Tests.swift with the same fixtures.
 */
class NUT13Test {
    private fun hex(b: ByteArray) = Bytes.hex(b)

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?) = v as Map<String, Any?>

    @Test
    fun theSpecVectorsForBothKeysetVersions() {
        val vectors = obj(TestSupport.fixture("nut13-vectors.json"))
        val seed = NUT13.seed(vectors["mnemonic"] as String)
        val keysets = obj(vectors["keysets"])
        assertEquals(setOf("00", "01"), keysets.keys.map { it.take(2) }.toSet())
        var checked = 0
        for ((keysetId, w) in keysets) {
            @Suppress("UNCHECKED_CAST")
            val secrets = obj(w)["secrets"] as List<String>
            @Suppress("UNCHECKED_CAST")
            val factors = obj(w)["blindingFactors"] as List<String>
            assertEquals(secrets.size, factors.size)
            val batch = NUT13.derive(seed, keysetId, 0, secrets.size)
            assertEquals(keysetId, secrets, batch.map { hex(it.secret) })
            assertEquals(keysetId, factors, batch.map { hex(it.blindingFactor) })
            for (k in secrets.indices) {
                val one = NUT13.derive(seed, keysetId, k.toLong(), 1)
                assertEquals("$keysetId secret $k", secrets[k], hex(one[0].secret))
                assertEquals("$keysetId r $k", factors[k], hex(one[0].blindingFactor))
                checked++
            }
        }
        assertEquals("the spec lists five counters for each version", 10, checked)
    }

    @Test
    fun everyCrossCheckEntryMatchesTheBundledCashuTS() {
        val cross = obj(TestSupport.fixture("nut13-cross.json"))
        @Suppress("UNCHECKED_CAST")
        val cases = cross["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 3)
        var entries = 0
        val versions = HashSet<String>()
        val counters = HashSet<Long>()
        for (c in cases) {
            val mnemonic = c["mnemonic"] as String
            val seed = NUT13.seed(mnemonic)
            assertEquals("BIP-39 seed of a ${mnemonic.split(" ").size}-word mnemonic", c["seed"], seed.hex())
            @Suppress("UNCHECKED_CAST")
            for (keyset in c["keysets"] as List<Map<String, Any?>>) {
                val keysetId = keyset["keysetId"] as String
                versions.add(keysetId.take(2))
                @Suppress("UNCHECKED_CAST")
                val list = keyset["entries"] as List<Map<String, Any?>>
                for (entry in list) {
                    val counter = (entry["counter"] as Double).toLong()
                    counters.add(counter)
                    val got = NUT13.derive(seed, keysetId, counter, 1)[0]
                    assertEquals("$keysetId secret at $counter", entry["secret"], hex(got.secret))
                    assertEquals("$keysetId r at $counter", entry["blindingFactor"], hex(got.blindingFactor))
                    entries++
                }
                // counters 0 to 50 as the one batch the page would ask for
                val first = list.takeWhile { (it["counter"] as Double).toLong() <= 50 }
                val batch = NUT13.derive(seed, keysetId, 0, first.size)
                assertEquals(keysetId, first.map { it["secret"] }, batch.map { hex(it.secret) })
                assertEquals(keysetId, first.map { it["blindingFactor"] }, batch.map { hex(it.blindingFactor) })
            }
        }
        assertEquals(setOf("00", "01"), versions)
        assertTrue(counters.containsAll(listOf(0L, 50L, 1000L, 0x7FFF_FFFFL, 1L shl 40, (1L shl 53) - 1)))
        assertEquals("the 856 cashu-ts answers iOS checks", 856, entries)
    }

    @Test
    fun theSeedFromBytesIsTheSeedFromWords() {
        val fromWords = NUT13.seed(TestSupport.HALF)
        val fromBytes = NUT13.Seed(Bytes.fromHex(fromWords.hex())!!)
        for (id in listOf("009a1f293253e41e", "015ba18a8adcd02e715a58358eb618da4a4b3791151a4bee5e968bb88406ccf76a")) {
            assertEquals(NUT13.derive(fromWords, id, 3, 2), NUT13.derive(fromBytes, id, 3, 2))
        }
        assertTrue(fromWords.sameBytes(fromBytes))
        assertTrue(!fromWords.sameBytes(NUT13.seed(TestSupport.SAVED)))
    }

    @Test
    fun keysetIdsOfOneDerivationIndexDeriveTheSameSecrets() {
        val seed = NUT13.seed(TestSupport.HALF)
        val want = NUT13.derive(seed, TestSupport.ALIASES[0], 7, 3)
        for (id in TestSupport.ALIASES) {
            assertEquals(id, 864_559_728L, NUT13.derivationIndex(id))
            assertEquals(id, want, NUT13.derive(seed, id, 7, 3))
        }
        assertNotEquals(want, NUT13.derive(seed, "00b4cd27d8861a44", 7, 3))
        assertEquals("2^32 − 1 mod 2^31 − 1", 1L, NUT13.derivationIndex("00000000ffffffff"))
        assertNull(NUT13.derivationIndex("015ba18a8adcd02e715a58358eb618da4a4b3791151a4bee5e968bb88406ccf76a"))
    }

    @Test
    fun onlyAMnemonicsWordCountsMakeASeed() {
        NUT13.seed(TestSupport.ABANDON_ABOUT)
        for (words in listOf("", "abandon", TestSupport.ABANDON_ABOUT + " abandon", TestSupport.ABANDON_ABOUT.replace(" about", "  about"))) {
            try {
                NUT13.seed(words)
                fail("a seed from \"$words\"")
            } catch (e: NUT13.Failure.BadMnemonic) {
            }
        }
    }

    @Test
    fun theBip39SeedIsTheStandardsForAbandonAbout() {
        // BIP-39 vectors.json, no passphrase: abandon … about
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            NUT13.seed(TestSupport.ABANDON_ABOUT).hex(),
        )
    }

    @Test
    fun theBlindingFactorIsReducedModNOnce() {
        val n = NUT13.ORDER
        fun plus(x: ByteArray, k: Int): ByteArray {
            val out = x.copyOf()
            var carry = k
            for (i in 31 downTo 0) {
                val s = (out[i].toInt() and 0xff) + carry
                out[i] = s.toByte()
                carry = s ushr 8
            }
            return out
        }
        val zero = ByteArray(32)
        val nMinusOne = n.copyOf().also { it[31] = (it[31] - 1).toByte() }
        assertTrue(NUT13.reducedOnce(n).contentEquals(zero))
        assertTrue(NUT13.reducedOnce(plus(n, 1)).contentEquals(plus(zero, 1)))
        assertTrue(NUT13.reducedOnce(nMinusOne).contentEquals(nMinusOne))
        assertTrue(NUT13.reducedOnce(zero).contentEquals(zero))
        assertEquals("000000000000000000000000000000014551231950b75fc4402da1732fc9bebe", hex(NUT13.reducedOnce(ByteArray(32) { -1 })))
    }

    @Test
    fun keysetIdsAndCounterRangesTheDerivationRefuses() {
        val seed = NUT13.Seed(ByteArray(64) { 7 })
        for (id in listOf("", "00", "009a1f293253e41", "009a1f293253e41e0", "019a1f293253e41e", "029a1f293253e41e",
            "009a1f293253e41g", "I2yN+iRYfkzT", "00" + "a".repeat(64))) {
            try {
                NUT13.derive(seed, id, 0, 1)
                fail(id)
            } catch (e: NUT13.Failure.BadKeysetId) {
            }
        }
        val v00 = "009a1f293253e41e"
        val v01 = "01" + "ab".repeat(32)
        NUT13.derive(seed, v00, 0x7FFF_FFFFL, 1)
        NUT13.derive(seed, v01, (1L shl 53) - 1, 1)
        for ((id, start, count) in listOf(Triple(v00, 0x8000_0000L, 1), Triple(v00, 0x7FFF_FFFFL, 2), Triple(v01, 1L shl 53, 1),
            Triple(v01, (1L shl 53) - 1, 2), Triple(v01, Long.MAX_VALUE, 2), Triple(v00, 0L, 0), Triple(v00, -1L, 1))) {
            try {
                NUT13.derive(seed, id, start, count)
                fail("$id $start $count")
            } catch (e: NUT13.Failure.BadCounter) {
            }
        }
    }

    @Test
    fun theReplyIsTheAgreedJson() {
        val seed = NUT13.seed(TestSupport.HALF)
        val id = "009a1f293253e41e"
        assertEquals(
            "{\"keysetId\":\"009a1f293253e41e\",\"start\":1,\"secrets\":[" +
                "\"8f2b39e8e594a4056eb1e6dbb4b0c38ef13b1b2c751f64f810ec04ee35b77270\"," +
                "\"bc628c79accd2364fd31511216a0fab62afd4a18ff77a20deded7b858c9860c8\"],\"blindingFactors\":[" +
                "\"967d5232515e10b81ff226ecf5a9e2e2aff92d66ebc3edf0987eb56357fd6248\"," +
                "\"b20f47bb6ae083659f3aa986bfa0435c55c6d93f687d51a01f26862d9b9a4899\"]}",
            NUT13.reply(id, 1, NUT13.derive(seed, id, 1, 2)),
        )
    }

    @Test
    fun wipingZeroesKeysAndSeedsInPlace() {
        val key = NUT13.ExtendedKey(ByteArray(32) { 0xAB.toByte() }, ByteArray(32) { 0xCD.toByte() })
        key.wipe()
        assertTrue(key.key.all { it == 0.toByte() } && key.chain.all { it == 0.toByte() })
        val seed = NUT13.seed(TestSupport.HALF)
        seed.wipe()
        assertEquals("0".repeat(128), seed.hex())
    }

    @Test
    fun aThousandCountersTakeLittleTime() {
        val seed = NUT13.Seed(ByteArray(64) { 1 })
        val began = System.nanoTime()
        assertEquals(1000, NUT13.derive(seed, "00b4cd27d8861a44", 0, 1000).size)
        assertTrue("a thousand counters in under 20 s", System.nanoTime() - began < 20_000_000_000L)
    }
}
