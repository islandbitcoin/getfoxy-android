package io.islandbitcoin.foxy.keychain

import io.islandbitcoin.foxy.TestSupport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * A card's owner key ([CardOwner]): ported from FoxyTests/NUT13Tests.swift's
 * "A card's owner key" and "What the owner key may sign" (getfoxy/iOS 8df0bb1),
 * one assertion at a time, against the same fixture
 * (tests/fixtures/card-owner-vectors.json, which Node worked out with its own
 * crypto). An Android phone restored from an iPhone's words must make the same
 * key, byte for byte, or the card refuses it: a mismatch here is a blocking bug.
 * Then the card's own vectors (getfoxy/card spec/vectors/owner.json): what the
 * phone signs is the message the card verified.
 */
class CardOwnerTest {
    private val abandonAbout = TestSupport.ABANDON_ABOUT
    private val secp256k1G = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val nonceText = "000102030405060708090a0b0c0d0e0f"

    private fun bytes(hex: String): ByteArray = Bytes.fromHex(hex) ?: ByteArray(0)

    @Suppress("UNCHECKED_CAST")
    private val fixture: Map<String, Any?> by lazy { TestSupport.fixture("card-owner-vectors.json") as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun ownerCases(): List<Map<String, Any?>> = fixture["cases"] as List<Map<String, Any?>>

    /** The seed of one of the fixture's cases: the words where there are any (which also holds BIP-39 to Node's), the bytes where not. */
    private fun seedOf(c: Map<String, Any?>): NUT13.Seed {
        val want = c["seed"] as String
        val words = c["mnemonic"] as? String ?: return NUT13.Seed(bytes(want))
        val seed = NUT13.seed(words)
        assertEquals("the BIP-39 seed of ${words.take(20)}", want, seed.hex())
        return seed
    }

    /** d·G by plain double-and-add over BigInteger: a reference apart from the provider, for the tests only. */
    private fun referencePublicKey(d: ByteArray): String {
        val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        val gx = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
        val gy = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
        val a = p.subtract(BigInteger.valueOf(3))
        fun add(P: Pair<BigInteger, BigInteger>?, Q: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
            if (P == null) return Q
            if (Q == null) return P
            if (P.first == Q.first && P.second.add(Q.second).mod(p).signum() == 0) return null
            val l = if (P == Q) {
                P.first.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(P.second.shiftLeft(1).modInverse(p)).mod(p)
            } else {
                Q.second.subtract(P.second).multiply(Q.first.subtract(P.first).modInverse(p)).mod(p)
            }
            val x = l.pow(2).subtract(P.first).subtract(Q.first).mod(p)
            return x to l.multiply(P.first.subtract(x)).subtract(P.second).mod(p)
        }
        var r: Pair<BigInteger, BigInteger>? = null
        var g: Pair<BigInteger, BigInteger>? = gx to gy
        val k = BigInteger(1, d)
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, g)
            g = add(g, g)
        }
        fun h(n: BigInteger) = n.toString(16).padStart(64, '0')
        return "04" + h(r!!.first) + h(r.second)
    }

    // MARK: The key

    /** The scalar and the public key, which Node worked out from its own HMAC, BigInt and P-256. */
    @Test
    fun theOwnerKeyIsWhatNodeWorksOutForThreeSeedsAndCards() {
        val cases = ownerCases()
        assertEquals(3, cases.size)
        assertEquals("one seed is no phrase, only bytes", 2, cases.count { it["mnemonic"] is String })
        for (c in cases) {
            val seed = seedOf(c)
            val card = bytes(c["cardKey"] as String)
            assertTrue(CardOwner.isCardKey(card))
            val d = CardOwner.scalar(seed, card)!!
            try {
                assertEquals(c["scalar"], Bytes.hex(d))
                val pub = CardOwner.publicKey(seed, card)
                assertEquals(65, pub.size)
                assertEquals(0x04.toByte(), pub[0])
                assertEquals(c["pub"], Bytes.hex(pub))
                // the platform's own public key for that scalar is the same point, and so is plain arithmetic's
                assertEquals(c["pub"], Bytes.hex(P256.publicKey(d)))
                assertEquals(c["pub"], referencePublicKey(d))
            } finally {
                Bytes.wipe(d)
            }
        }
        // the first, as the HMAC alone gives it, apart from the derivation: abandon ... about, and the generator
        val first = seedOf(cases[0])
        assertEquals("5eb00bbddcf06908", first.hex().take(16))
        assertEquals(secp256k1G, cases[0]["cardKey"])
        assertEquals("7857475d975c2a11f98b0e05512092a107ce8c9ee3d48ec3518e7407a30fa6b9", cases[0]["scalar"])
    }

    @Test
    fun theOwnerKeyFollowsTheSeedAndTheCardsKeyAndNothingElse() {
        val seed = NUT13.seed(abandonAbout)
        val other = NUT13.seed(TestSupport.SAVED)
        fun d(seed: NUT13.Seed, key: String): String = Bytes.hex(CardOwner.scalar(seed, bytes(key)) ?: ByteArray(0))
        val first = d(seed, secp256k1G)
        assertEquals(64, first.length)
        assertEquals("the same seed and card, the same key: it is derived again on a new phone", first, d(seed, secp256k1G))
        assertNotEquals("another seed", first, d(other, secp256k1G))
        assertNotEquals("another card", first, d(seed, "02" + "11".repeat(32)))
        // one bit of the key: the first byte, the last byte
        assertNotEquals(first, d(seed, "03" + secp256k1G.drop(2)))
        assertNotEquals(first, d(seed, secp256k1G.dropLast(2) + "99"))
        // and it is not a keyset's secret
        val keyset = NUT13.derive(seed, "01" + "ab".repeat(32), 0, 1)
        assertNotEquals(Bytes.hex(keyset[0].secret), first)
    }

    /** 256-bit values are below 2n, so mod n is one subtraction, whatever the value. */
    @Test
    fun theReductionIsOneSubtractionOfP256sOrder() {
        assertEquals(fixture["n"], Bytes.hex(CardOwner.ORDER))
        @Suppress("UNCHECKED_CAST")
        val table = fixture["reductions"] as List<Map<String, String>>
        assertTrue(table.size >= 8)
        for (row in table) {
            val got = NUT13.reducedOnce(bytes(row.getValue("hash")), CardOwner.ORDER)
            assertEquals(row["hash"], row["reduced"], Bytes.hex(got))
        }
        // the edges, built here: n is 0, n + 1 is 1, n − 1 stays, the largest 256-bit value is 2^256 − 1 − n
        val n = CardOwner.ORDER
        val nMinusOne = n.copyOf().also { it[31] = (it[31] - 1).toByte() }
        val nPlusOne = n.copyOf().also { it[31] = (it[31] + 1).toByte() }
        val zero = ByteArray(32)
        assertArrayEquals(zero, NUT13.reducedOnce(n, n))
        assertArrayEquals(zero.copyOf().also { it[31] = 1 }, NUT13.reducedOnce(nPlusOne, n))
        assertArrayEquals(nMinusOne, NUT13.reducedOnce(nMinusOne, n))
        assertArrayEquals(zero, NUT13.reducedOnce(zero, n))
        assertEquals("00000000ffffffff00000000000000004319055258e8617b0c46353d039cdaae", Bytes.hex(NUT13.reducedOnce(ByteArray(32) { -1 }, n)))
        // secp256k1's order is still the one the other callers get
        assertArrayEquals(zero, NUT13.reducedOnce(NUT13.ORDER))
    }

    /** A value of 0 (or n, which is 0 too) is not a key: the next counter is tried, as many as it takes. */
    @Test
    fun aScalarThatIsZeroTriesTheNextCounter() {
        val zero = ByteArray(32)
        val one = ByteArray(32).also { it[31] = 1 }
        val five = ByteArray(32).also { it[31] = 5 }
        val asked = ArrayList<Int>()
        assertArrayEquals(one, CardOwner.scalar { asked.add(it); if (it == 0) zero.copyOf() else one.copyOf() })
        assertEquals("0 is no key, so counter 1", listOf(0, 1), asked)
        asked.clear()
        assertArrayEquals(five, CardOwner.scalar { asked.add(it); if (it < 3) CardOwner.ORDER.copyOf() else five.copyOf() })
        assertEquals("n is 0 mod n, as 0 is", listOf(0, 1, 2, 3), asked)
        asked.clear()
        assertArrayEquals(one, CardOwner.scalar { asked.add(it); one.copyOf() })
        assertEquals("a key at once asks once", listOf(0), asked)
        asked.clear()
        assertNull(CardOwner.scalar { asked.add(it); zero.copyOf() })
        assertEquals(256, asked.size)
        assertEquals(255, asked.last())
    }

    /** The message hashed: the tag, a zero byte, the card's key, and from counter 1 on that counter. */
    @Test
    fun theHashedMessageHasACounterOnlyAfterTheFirstTry() {
        val key = bytes(secp256k1G)
        val head = "466f7879436172642f6f776e6572" + "00" + secp256k1G // "FoxyCard/owner", 00, the key
        assertEquals(head, Bytes.hex(CardOwner.hashInput(key, 0)))
        assertEquals(head + "01", Bytes.hex(CardOwner.hashInput(key, 1)))
        assertEquals(head + "ff", Bytes.hex(CardOwner.hashInput(key, 255)))
        assertArrayEquals("FoxyCard/owner".toByteArray(Charsets.UTF_8), CardOwner.TAG)
    }

    @Test
    fun onlyACompressedKeyIsACardKey() {
        val body = ByteArray(32) { 0x11 }
        assertTrue(CardOwner.isCardKey(byteArrayOf(0x02) + body))
        assertTrue(CardOwner.isCardKey(byteArrayOf(0x03) + body))
        for (first in listOf(0x00, 0x01, 0x04, 0x05, 0xFF)) {
            assertFalse("starts $first", CardOwner.isCardKey(byteArrayOf(first.toByte()) + body))
        }
        assertFalse(CardOwner.isCardKey(ByteArray(0)))
        assertFalse("32 bytes", CardOwner.isCardKey(byteArrayOf(0x02) + body.copyOf(31)))
        assertFalse("34 bytes", CardOwner.isCardKey(byteArrayOf(0x02) + body + byteArrayOf(0x11)))
        assertFalse("an uncompressed key, 65 bytes", CardOwner.isCardKey(byteArrayOf(0x04) + body + body))
    }

    // MARK: What the owner key may sign

    /** The six names, and nothing the card also signs or verifies: LOCK_CARD and the time are not here. */
    @Test
    fun theLabelsAreSixAndNoNameStartsAnother() {
        val names = CardOwner.Label.entries.map { it.raw }
        assertEquals(listOf("change-pin", "set-limit", "set-owner", "set-card", "load", "reset"), names)
        assertEquals(6, names.size)
        for (a in names) {
            for (b in names) if (a != b) assertFalse("$a starts with $b, so a signature could name either", a.startsWith(b))
            // what is signed never begins as a lock, a time or the card's own proof does
            val signed = CardOwner.message(CardOwner.Label.of(a)!!, bytes(nonceText), ByteArray(0))
            for (other in listOf("FoxyCard/lock", "FoxyCard/time", "FoxyCard/auth", "FoxyCard/tag")) {
                val o = other.toByteArray(Charsets.UTF_8)
                assertFalse("$a signs as $other", signed.size >= o.size && signed.copyOf(o.size).contentEquals(o))
            }
        }
    }

    /** SET_CARD's data: unit (1), refund key (33), time key (65), mint length (1), mint. */
    private fun record(
        unit: Int = 1,
        timeKeyFirst: Int = 0x04,
        noTimeKey: Boolean = false,
        spoilTimeKey: Int? = null,
        length: Int,
        carried: Int? = null,
    ): ByteArray {
        val key = if (noTimeKey) ByteArray(65) else byteArrayOf(timeKeyFirst.toByte()) + ByteArray(64) { 0x33 }
        if (spoilTimeKey != null) key[spoilTimeKey] = 0x01
        return byteArrayOf(unit.toByte(), 0x02) + ByteArray(32) { 0x22 } + key + byteArrayOf(length.toByte()) + ByteArray(carried ?: length) { 0x6D }
    }

    @Test
    fun eachLabelTakesTheShapeOfValueItsCommandReads() {
        val digits = { n: Int -> ByteArray(n) { 0x35 } }
        val pin = CardOwner.Label.CHANGE_PIN
        for (n in 4..8) assertTrue("$n digits", pin.accepts(digits(n)))
        val badPins = listOf(ByteArray(0), digits(3), digits(9), byteArrayOf(0x31, 0x32, 0x33, 0x3A), byteArrayOf(0x31, 0x32, 0x33, 0x2F),
            byteArrayOf(0x31, 0x32, 0x33, 0x20), byteArrayOf(0x31, 0x32, 0x33, 0x00), byteArrayOf(0x61, 0x62, 0x63, 0x64),
            byteArrayOf(0x31, 0x32, 0x33, 0xB1.toByte()))
        for (bad in badPins) assertFalse(Bytes.hex(bad), pin.accepts(bad))
        // set-limit: exactly 4, 8 or 12 bytes
        val limit = CardOwner.Label.SET_LIMIT
        assertTrue(limit.accepts(ByteArray(4)))
        assertTrue(limit.accepts(ByteArray(4) { -1 }))
        assertTrue(limit.accepts(byteArrayOf(0, 0, 0, 0, 0, 0, 0x13, 0x88.toByte())))
        assertTrue(limit.accepts(byteArrayOf(0, 0, 0, 0, 0, 0, 0x13, 0x88.toByte(), 0, 0, 0x27, 0x10)))
        for (n in listOf(0, 1, 3, 5, 6, 7, 9, 10, 11, 13, 16)) assertFalse("$n bytes", limit.accepts(ByteArray(n) { 1 }))
        // set-owner: 65 bytes, 04 first
        val owner = CardOwner.Label.SET_OWNER
        val key = byteArrayOf(0x04) + ByteArray(64) { 0x55 }
        assertTrue(owner.accepts(key))
        for (first in listOf(0x00, 0x02, 0x03, 0x05, 0xFF)) {
            assertFalse("starts $first", owner.accepts(byteArrayOf(first.toByte()) + key.copyOfRange(1, 65)))
        }
        assertFalse(owner.accepts(key.copyOf(64)))
        assertFalse(owner.accepts(key + byteArrayOf(0x55)))
        assertFalse(owner.accepts(ByteArray(0)))
        // set-card: 100 + L bytes for a mint of L, 1 to 80, the time key starting 04 or all zeros
        val card = CardOwner.Label.SET_CARD
        for (length in listOf(1, 2, 24, 79, 80)) assertTrue("mint of $length", card.accepts(record(length = length)))
        for (length in listOf(1, 24, 80)) assertTrue("no time key, mint of $length", card.accepts(record(noTimeKey = true, length = length)))
        for (at in listOf(0, 1, 30, 63, 64)) {
            assertFalse("zeros but for byte $at of the time key", card.accepts(record(noTimeKey = true, spoilTimeKey = at, length = 5)))
        }
        assertEquals(CardOwner.MOST_VALUE_BYTES, record(length = 80).size)
        assertFalse("no mint", card.accepts(record(length = 0)))
        assertFalse("a mint past 80 bytes", card.accepts(record(length = 81)))
        assertFalse(card.accepts(record(length = 255)))
        assertFalse("says 5 and carries 4", card.accepts(record(length = 5, carried = 4)))
        assertFalse("says 5 and carries 6", card.accepts(record(length = 5, carried = 6)))
        assertFalse(card.accepts(record(length = 5, carried = 0)))
        assertFalse("a time key that is not uncompressed", card.accepts(record(timeKeyFirst = 0x02, length = 5)))
        assertFalse("a first byte of zero and a key after it", card.accepts(record(timeKeyFirst = 0x00, length = 5)))
        assertFalse("shorter than the fixed part", card.accepts(record(length = 5).copyOf(99)))
        assertFalse("the fixed part and a length with no mint", card.accepts(record(length = 5).copyOf(100)))
        assertFalse(card.accepts(ByteArray(0)))
        // load and reset: nothing at all
        for (label in listOf(CardOwner.Label.LOAD, CardOwner.Label.RESET)) {
            assertTrue(label.accepts(ByteArray(0)))
            for (n in 1..3) assertFalse("${label.raw}: $n bytes", label.accepts(ByteArray(n)))
        }
    }

    /** A body as the bridge hands it over: what was asked, and the action and id besides; a null leaves the field out. */
    private fun sign(label: Any?, value: Any?, key: Any? = null, nonce: Any? = null): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>("action" to "cardOwnerSign", "id" to "4")
        body["key"] = key ?: secp256k1G
        if (label != null) body["label"] = label
        body["nonce"] = nonce ?: nonceText
        if (value != null) body["value"] = value
        return body
    }

    /** {key, label, nonce, value} as the page sends them: whole and exact, or nothing. */
    @Test
    fun aSignRequestIsTheSixLabelsAndTheirValuesAndNothingElse() {
        val ok = listOf("change-pin" to "31323334", "change-pin" to "3132333435363738", "set-limit" to "000186a0",
            "set-owner" to "04" + "ab".repeat(64), "load" to "", "reset" to "", "set-card" to Bytes.hex(record(length = 24)))
        for ((label, value) in ok) {
            val request = CardOwner.signRequest(sign(label, value))
            assertNotNull("$label ${value.take(8)}", request)
            assertEquals(label, request!!.label.raw)
            assertArrayEquals(bytes(secp256k1G), request.key)
            assertArrayEquals(bytes(nonceText), request.nonce)
            assertEquals(value, Bytes.hex(request.value))
            assertTrue(request.isWellFormed)
        }
        // either case, as the key is
        val upper = CardOwner.signRequest(sign("set-limit", "000186A0", key = secp256k1G.uppercase(), nonce = nonceText.uppercase()))!!
        assertArrayEquals(byteArrayOf(0, 1, 0x86.toByte(), 0xA0.toByte()), upper.value)
        assertArrayEquals(bytes(secp256k1G), upper.key)
        // a label that is not one of the six, however near: LOCK_CARD, SET_TAG and the time in particular
        val labels: List<Any> = listOf("lock", "time", "auth", "tag", "FoxyCard/lock", "FoxyCard/time", "FoxyCard/load", "FoxyCard/change-pin", "", " ",
            "Load", "LOAD", "Change-Pin", "change-pin ", " load", "load\n", "load\u0000", "change_pin", "changePin", "setLimit",
            "set-allowance", "set-", "set", "change-pin,load", 5.0, true, listOf("load"), mapOf("label" to "load"))
        for (label in labels) {
            assertNull("label $label", CardOwner.signRequest(sign(label, "")))
            assertNull("label $label with a value", CardOwner.signRequest(sign(label, "31323334")))
        }
        assertNull("no label", CardOwner.signRequest(sign(null, "")))
        // a value that is not that label's, or not hex at all
        assertNull("load takes nothing", CardOwner.signRequest(sign("load", "00")))
        assertNull("reset takes nothing", CardOwner.signRequest(sign("reset", "00")))
        assertNull("three digits", CardOwner.signRequest(sign("change-pin", "313233")))
        assertNull(CardOwner.signRequest(sign("change-pin", "3132333g")))
        assertNull("odd", CardOwner.signRequest(sign("change-pin", "313233343")))
        assertNull(CardOwner.signRequest(sign("change-pin", "0x31323334")))
        assertNull(CardOwner.signRequest(sign("change-pin", " 31323334")))
        assertNull(CardOwner.signRequest(sign("set-limit", "00000000ff")))
        assertNull(CardOwner.signRequest(sign("set-owner", "02" + "ab".repeat(64))))
        assertNull("181 bytes: past the longest record", CardOwner.signRequest(sign("set-card", Bytes.hex(record(length = 81)))))
        assertNull(CardOwner.signRequest(sign("set-card", Bytes.hex(record(length = 5, carried = 6)))))
        assertNull("a long text is refused before it is read", CardOwner.signRequest(sign("set-card", "00".repeat(4_000))))
        for (value in listOf<Any>(5.0, true, listOf("31323334"), mapOf("value" to "31323334"))) {
            assertNull("value $value", CardOwner.signRequest(sign("change-pin", value)))
        }
        assertNull("no value, not even for load", CardOwner.signRequest(sign("load", null)))
        assertNull(CardOwner.signRequest(sign("load", null).plus("value" to null)))
        // a nonce of 16 bytes as 32 hex characters, and no other
        val shortNonce = nonceText.dropLast(2)
        val oddNonce = nonceText.dropLast(1)
        val badNonces = listOf("", "00", shortNonce, oddNonce, nonceText + "00", nonceText + "0", oddNonce + "g",
            "0x$shortNonce", " " + nonceText.drop(1), "é".repeat(16), "00".repeat(32))
        for (bad in badNonces) assertNull("nonce ${bad.take(12)}", CardOwner.signRequest(sign("load", "", nonce = bad)))
        for (bad in listOf<Any>(5.0, true, listOf(nonceText))) assertNull("nonce $bad", CardOwner.signRequest(sign("load", "", nonce = bad)))
        assertNull(CardOwner.signRequest(sign("load", "").plus("nonce" to null)))
        assertNull(CardOwner.signRequest(sign("load", "").minus("nonce")))
        // and a key that is not a card's
        val tail = secp256k1G.drop(2)
        val badKeys = listOf("", "02", secp256k1G.dropLast(2), secp256k1G + "00", "04$tail", "00$tail", secp256k1G.dropLast(1) + "g", " $secp256k1G")
        for (bad in badKeys) assertNull("key ${bad.take(12)}", CardOwner.signRequest(sign("load", "", key = bad)))
        assertNull(CardOwner.signRequest(sign("load", "").minus("key")))
    }

    @Test
    fun aKeyRequestIsACardsCompressedKeyAndNothingElse() {
        assertArrayEquals(bytes(secp256k1G), CardOwner.key(secp256k1G))
        assertArrayEquals(bytes(secp256k1G), CardOwner.key(secp256k1G.uppercase()))
        assertArrayEquals(byteArrayOf(0x03) + ByteArray(32) { 0xAB.toByte() }, CardOwner.key("03" + "Ab".repeat(32)))
        val tail = secp256k1G.drop(2)
        val notHex = listOf("", "02", secp256k1G.dropLast(2), secp256k1G + "00", secp256k1G.dropLast(1) + "g")
        val notCompressed = listOf("04$tail", "00$tail", "01$tail", "05$tail", "ff$tail", "0x$tail", secp256k1G + "\n")
        val wrongLength = listOf("02" + "é".repeat(32), "02".repeat(64), "0".repeat(66))
        val notStrings = listOf<Any?>(5.0, null, true, listOf(secp256k1G), mapOf("key" to secp256k1G))
        for (bad in notHex + notCompressed + wrongLength + notStrings) assertNull("$bad", CardOwner.key(bad))
    }

    /** What Node signed, checked here with the platform's verifier; and what this signs, checked against Node's public key. */
    @Test
    fun theSignaturesNodeMadeVerifyAndSoDoOurs() {
        var checked = 0
        for (c in ownerCases()) {
            val seed = seedOf(c)
            val card = c["cardKey"] as String
            val pub = bytes(c["pub"] as String)
            @Suppress("UNCHECKED_CAST")
            val list = c["signatures"] as List<Map<String, String>>
            assertEquals("every label, in this order", CardOwner.Label.entries.map { it.raw }, list.map { it["label"] })
            for (s in list) {
                val request = CardOwner.signRequest(sign(s["label"], s["value"], key = card, nonce = s["nonce"]))
                assertNotNull("the fixture's ${s["label"]} passes its own shape checks", request)
                // the message is the one Node signed
                val message = CardOwner.message(request!!.label, request.nonce, request.value)
                assertEquals(s["label"], s["message"], Bytes.hex(message))
                assertArrayEquals(("FoxyCard/" + request.label.raw).toByteArray(Charsets.UTF_8) + request.nonce + request.value, message)
                // Node's signature verifies here ...
                assertTrue("Node's ${s["label"]}", P256.verify(pub, message, bytes(s.getValue("sig"))))
                // ... and ours verifies against the public key Node gave, and against nothing near it
                val ours = CardOwner.signature(request, seed)
                assertTrue("ours, for ${s["label"]}", P256.verify(pub, message, ours))
                val wrong = message.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
                assertFalse("a changed message", P256.verify(pub, wrong, ours))
                checked++
            }
        }
        assertEquals("three seeds, each signing every label: six since the card could be reset", 18, checked)
    }

    /**
     * The card's own vectors (getfoxy/card spec/vectors/owner.json): nine proofs its
     * applet accepted. For every label the phone signs, the message it builds is the
     * one the card verified (the card's signature verifies over it); `lock` and `tag`
     * are labels the card knows and the phone refuses to sign.
     */
    @Test
    fun theMessageThePhoneSignsIsTheOneTheCardVerified() {
        @Suppress("UNCHECKED_CAST")
        val card = TestSupport.fixture("card-owner-proofs.json") as Map<String, Any?>
        assertEquals("P-256 (secp256r1)", card["curve"])
        assertEquals("ECDSA, SHA-256, DER", card["signature"])
        val owner = bytes(card["ownerKey"] as String)
        assertNotNull("the card's owner key is a point on P-256", P256.decode(owner))
        @Suppress("UNCHECKED_CAST")
        val proofs = card["proofs"] as List<Map<String, String>>
        assertEquals(9, proofs.size)
        val refused = ArrayList<String>()
        var verified = 0
        for (p in proofs) {
            val full = p.getValue("label")
            assertTrue(full, full.startsWith("FoxyCard/"))
            val name = full.removePrefix("FoxyCard/")
            val request = CardOwner.signRequest(sign(name, p["value"], nonce = p["nonce"]))
            if (request == null) {
                refused.add(name)
                continue
            }
            val message = CardOwner.message(request.label, request.nonce, request.value)
            assertArrayEquals(full.toByteArray(Charsets.UTF_8) + bytes(p.getValue("nonce")) + bytes(p.getValue("value")), message)
            assertTrue("the card's $full proof verifies over the phone's message", P256.verify(owner, message, bytes(p.getValue("signature"))))
            assertFalse("and not over another label's", P256.verify(owner, ("FoxyCard/x" + name).toByteArray() + request.nonce + request.value,
                bytes(p.getValue("signature"))))
            verified++
        }
        assertEquals("the labels the phone never signs", listOf("lock", "tag"), refused)
        assertEquals("change-pin, set-limit twice (4 and 12 bytes), load, set-card with no time key, set-owner, reset", 7, verified)
    }
}
