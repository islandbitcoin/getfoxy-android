package io.islandbitcoin.foxy.nostr

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Foxy's Nostr cryptography against the specs' own vectors
 * (FoxyTests/NostrCryptoTests.swift at 8df0bb1, case for case, and more):
 * RFC 8439 for ChaCha20, and fixtures/nip44.vectors.json, the NIP-44 reference
 * vectors from paulmillr/nip44, sha256 269ed0f6…3ce25040, the file NIP-44 itself
 * names and iOS's tests/fixtures holds byte for byte: conversation keys, message
 * keys, padding, encrypting and decrypting, valid and invalid, and (which iOS's
 * tests leave out) the three long messages, pinned by the sha256 of their payload.
 *
 * Nothing here checks Foxy's code against Foxy's code, except the gift wrap at
 * the end, which is opened the way a receiving wallet opens it.
 */
@Suppress("UNCHECKED_CAST")
class NostrCryptoTest {
    private val v2: Map<String, Any?> by lazy { (TestSupport.fixture("nip44.vectors.json") as Map<String, Any?>)["v2"] as Map<String, Any?> }
    private val valid get() = v2["valid"] as Map<String, Any?>
    private val invalid get() = v2["invalid"] as Map<String, Any?>
    private fun cases(group: String, from: Map<String, Any?> = valid) = from[group] as List<Map<String, Any?>>
    private fun bytes(hex: String) = NostrEvent.bytes(hex).also { assertEquals("hex '$hex'", hex.length / 2, it.size) }
    private fun hex(b: ByteArray) = NostrEvent.hex(b)

    @Test
    fun theVectorsAreTheOnesTheNipNames() {
        val raw = javaClass.getResourceAsStream("/fixtures/nip44.vectors.json")!!.readBytes()
        assertEquals("269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040", hex(NostrCrypto.sha256(raw)))
        assertEquals(35, cases("get_conversation_key").size)
        assertEquals(10, cases("encrypt_decrypt").size)
        assertEquals(3, cases("encrypt_decrypt_long_msg").size)
        assertEquals(24, (valid["calc_padded_len"] as List<*>).size)
        assertEquals(8, cases("get_conversation_key", invalid).size)
        assertEquals(12, cases("decrypt", invalid).size)
        assertEquals(listOf(0.0, 65536.0, 100000.0, 10000000.0), invalid["encrypt_msg_lengths"])
    }

    // MARK: ChaCha20 (RFC 8439)

    @Test
    fun chaCha20BlockAtCounterZero() {
        // RFC 8439 A.1 #1
        val stream = ChaCha20.keystream(ByteArray(32), ByteArray(12), 0)
        assertEquals(
            "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
                "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586",
            hex(stream),
        )
    }

    @Test
    fun chaCha20BlockFunctionVector() {
        // RFC 8439 §2.3.2: key 00..1f, nonce 000000090000004a00000000, counter 1
        val key = ByteArray(32) { it.toByte() }
        val stream = ChaCha20.keystream(key, bytes("000000090000004a00000000"), 1)
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            hex(stream),
        )
    }

    @Test
    fun chaCha20SunscreenVector() {
        // RFC 8439 §2.4.2
        val key = ByteArray(32) { it.toByte() }
        val text = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .toByteArray()
        val out = ChaCha20.apply(text, key, bytes("000000000000004a00000000"), 1)
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            hex(out),
        )
        // and back
        assertArrayEquals(text, ChaCha20.apply(out, key, bytes("000000000000004a00000000"), 1))
    }

    @Test
    fun chaCha20AtACounterOtherThanOne() {
        // RFC 8439 A.2 #3: counter 42, the whole ciphertext
        val key = bytes("1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0")
        val text = "'Twas brillig, and the slithy toves\nDid gyre and gimble in the wabe:\nAll mimsy were the borogoves,\nAnd the mome raths outgrabe."
            .toByteArray()
        val out = ChaCha20.apply(text, key, bytes("000000000000000000000002"), 42)
        assertEquals(
            "62e6347f95ed87a45ffae7426f27a1df5fb69110044c0d73118effa95b01e5cf" +
                "166d3df2d721caf9b21e5fb14c616871fd84c54f9d65b283196c7fe4f60553eb" +
                "f39c6402c42234e32a356b3e764312a61a5532055716ead6962568f87d3f3f77" +
                "04c6a8d1bcd1bf4d50d6154b6da731b187b58dfd728afa36757a797ac188d1",
            hex(out),
        )
    }

    @Test
    fun chaCha20RefusesAKeyOrNonceOfTheWrongSize() {
        assertEquals(0, ChaCha20.apply(ByteArray(10), ByteArray(31), ByteArray(12)).size)
        assertEquals(0, ChaCha20.apply(ByteArray(10), ByteArray(32), ByteArray(8)).size)
    }

    // MARK: HKDF (RFC 5869), the two halves NIP-44 uses

    @Test
    fun hkdfRfc5869TestCase1() {
        val prk = Hkdf.extract(bytes("000102030405060708090a0b0c"), bytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"))
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(Hkdf.expand(prk, bytes("f0f1f2f3f4f5f6f7f8f9"), 42)),
        )
    }

    // MARK: NIP-44 v2

    @Test
    fun conversationKeys() {
        var checked = 0
        for (c in cases("get_conversation_key")) {
            val key = NostrCrypto.conversationKey(bytes(c["sec1"] as String), bytes(c["pub2"] as String))
            assertEquals(c["note"] as String? ?: "", c["conversation_key"], hex(key))
            checked++
        }
        assertEquals(35, checked)
    }

    @Test
    fun conversationKeysThatMustBeRefused() {
        var checked = 0
        for (c in cases("get_conversation_key", invalid)) {
            try {
                NostrCrypto.conversationKey(bytes(c["sec1"] as String), bytes(c["pub2"] as String))
                fail("accepted: ${c["note"]}")
            } catch (e: NostrCrypto.Failure) {
                checked++
            }
        }
        assertEquals(8, checked)
    }

    @Test
    fun theConversationKeyIsTheSameFromEitherSide() {
        val a = NostrCrypto.newKey()
        val b = NostrCrypto.newKey()
        assertArrayEquals(
            NostrCrypto.conversationKey(a, NostrCrypto.publicKey(b)),
            NostrCrypto.conversationKey(b, NostrCrypto.publicKey(a)),
        )
    }

    @Test
    fun messageKeys() {
        val group = valid["get_message_keys"] as Map<String, Any?>
        val key = bytes(group["conversation_key"] as String)
        val list = group["keys"] as List<Map<String, Any?>>
        assertTrue(list.size > 20)
        for (c in list) {
            val keys = NostrCrypto.messageKeys(key, bytes(c["nonce"] as String))
            assertEquals(c["chacha_key"], hex(keys.chacha))
            assertEquals(c["chacha_nonce"], hex(keys.nonce))
            assertEquals(c["hmac_key"], hex(keys.hmac))
        }
    }

    @Test
    fun paddedLengths() {
        val pairs = valid["calc_padded_len"] as List<List<Double>>
        assertEquals(24, pairs.size)
        for (pair in pairs) assertEquals("length ${pair[0]}", pair[1].toInt(), NostrCrypto.paddedLength(pair[0].toInt()))
    }

    @Test
    fun encryptAndDecryptTheVectors() {
        var checked = 0
        for (c in cases("encrypt_decrypt")) {
            val pub2 = NostrCrypto.publicKey(bytes(c["sec2"] as String))
            val key = NostrCrypto.conversationKey(bytes(c["sec1"] as String), pub2)
            assertEquals(c["conversation_key"], hex(key))
            val plaintext = c["plaintext"] as String
            val payload = NostrCrypto.encrypt(plaintext.toByteArray(Charsets.UTF_8), key, bytes(c["nonce"] as String))
            assertEquals(c["payload"], payload)
            val back = NostrCrypto.decrypt(c["payload"] as String, key)
            assertEquals(plaintext, back?.toString(Charsets.UTF_8))
            // and from the other side
            val other = NostrCrypto.conversationKey(bytes(c["sec2"] as String), NostrCrypto.publicKey(bytes(c["sec1"] as String)))
            assertArrayEquals(key, other)
            checked++
        }
        assertEquals(10, checked)
    }

    @Test
    fun encryptAndDecryptTheLongMessages() {
        var checked = 0
        for (c in cases("encrypt_decrypt_long_msg")) {
            val plaintext = (c["pattern"] as String).repeat((c["repeat"] as Double).toInt()).toByteArray(Charsets.UTF_8)
            assertEquals(c["plaintext_sha256"], hex(NostrCrypto.sha256(plaintext)))
            val key = bytes(c["conversation_key"] as String)
            val payload = NostrCrypto.encrypt(plaintext, key, bytes(c["nonce"] as String))
            assertEquals(c["payload_sha256"], hex(NostrCrypto.sha256(payload.toByteArray(Charsets.UTF_8))))
            assertArrayEquals(plaintext, NostrCrypto.decrypt(payload, key))
            checked++
        }
        assertEquals(3, checked)
    }

    @Test
    fun payloadsThatMustNotDecrypt() {
        var checked = 0
        for (c in cases("decrypt", invalid)) {
            assertNull(c["note"] as String?, NostrCrypto.decrypt(c["payload"] as String, bytes(c["conversation_key"] as String)))
            checked++
        }
        assertEquals(12, checked)
    }

    @Test
    fun lengthsThatMustNotEncrypt() {
        val key = NostrCrypto.conversationKey(ByteArray(32) { 1 }, NostrCrypto.publicKey(ByteArray(32) { 2 }))
        for (length in invalid["encrypt_msg_lengths"] as List<Double>) {
            try {
                NostrCrypto.encrypt(ByteArray(length.toInt()) { 0x61 }, key)
                fail("length $length encrypted")
            } catch (e: NostrCrypto.Failure) {
                assertEquals(NostrCrypto.Failure.Kind.TOO_LONG, e.kind)
            }
        }
        // and the largest one that must work
        assertNotNull(NostrCrypto.decrypt(NostrCrypto.encrypt(ByteArray(65535) { 0x61 }, key), key))
    }

    @Test
    fun aPayloadWithoutItsPaddingIsRefusedThoughJavaWouldReadIt() {
        // Foundation's base64 needs the pad; java.util.Base64 does not, so the check is the code's own
        val key = NostrCrypto.conversationKey(ByteArray(32) { 1 }, NostrCrypto.publicKey(ByteArray(32) { 2 }))
        // 33 bytes pad to 64: 1 + 32 + 66 + 32 = 131 bytes, which base64 ends with one "="
        val payload = NostrCrypto.encrypt("x".repeat(33).toByteArray(), key, ByteArray(32) { 7 })
        assertTrue(payload, payload.endsWith("="))
        assertNotNull(NostrCrypto.decrypt(payload, key))
        assertNull(NostrCrypto.decrypt(payload.trimEnd('='), key))
    }

    // MARK: Keys and signatures (BIP-340, libsecp256k1)

    @Test
    fun bip340PublicKeyOfTheSpecsFirstVector() {
        // BIP-340 test vector 0: secret key 3 has the x-only key F9308A01…, and its signature verifies
        val secret = bytes("0000000000000000000000000000000000000000000000000000000000000003")
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", hex(NostrCrypto.publicKey(secret)))
        val sig = bytes("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0")
        assertTrue(NostrCrypto.verify(sig, ByteArray(32), NostrCrypto.publicKey(secret)))
        sig[0] = (sig[0].toInt() xor 1).toByte()
        assertFalse(NostrCrypto.verify(sig, ByteArray(32), NostrCrypto.publicKey(secret)))
    }

    @Test
    fun aKeyThatIsNotAScalarIsRefused() {
        for (bad in listOf(ByteArray(32), ByteArray(32) { -1 }, ByteArray(31) { 1 })) {
            try {
                NostrCrypto.publicKey(bad)
                fail("accepted ${hex(bad)}")
            } catch (e: NostrCrypto.Failure) {
            }
        }
    }

    // MARK: Events (NIP-01) and the gift wrap (NIP-59)

    @Test
    fun theEventIdIsTheHashOfTheCanonicalArray() {
        // NIP-01's rules, with the characters that used to be escaped wrongly
        val event = NostrEvent.Event("", "ab".repeat(32), 1_700_000_000, 14, listOf(listOf("p", "cd".repeat(32))),
            "{\"mint\":\"https://mint.example/Bitcoin\"}\n\t\"x\"", "")
        val canonical = NostrEvent.canonical(event)
        assertTrue("a mint URL must not be escaped: $canonical", canonical.contains("https://mint.example/Bitcoin"))
        assertTrue(canonical, canonical.contains("\\n\\t\\\""))
        assertFalse("slash escaping would change every id", canonical.contains("\\/"))
        val with = NostrEvent.withId(event)
        assertEquals(64, with.id.length)
        assertEquals(with.id, NostrEvent.withId(event).id)
        assertNotEquals(with.id, NostrEvent.withId(event.copy(content = event.content + " ")).id)
    }

    @Test
    fun theEventIdOfAKnownEvent() {
        // the id of a fixed event, worked out independently: sha256 of NIP-01's array as written by hand
        val event = NostrEvent.Event("", "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 1_700_000_000, 1,
            listOf(listOf("p", "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9")), "hello\u0001\u001f\u007f é", "")
        val array = "[0,\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",1700000000,1," +
            "[[\"p\",\"f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9\"]],\"hello\\u0001\\u001f\u007f é\"]"
        assertEquals(array, NostrEvent.canonical(event))
        assertEquals(hex(java.security.MessageDigest.getInstance("SHA-256").digest(array.toByteArray(Charsets.UTF_8))), NostrEvent.withId(event).id)
    }

    @Test
    fun aSignedEventVerifiesUnderItsOwnKey() {
        val secret = NostrCrypto.newKey()
        val pubkey = NostrCrypto.publicKey(secret)
        val event = NostrEvent.signed(NostrEvent.Event("", hex(pubkey), 1_700_000_000, 13, emptyList(), "hello", ""), secret)
        assertTrue(NostrCrypto.verify(NostrEvent.bytes(event.sig), NostrEvent.bytes(event.id), pubkey))
        assertTrue(NostrEvent.verified(event))
        val other = NostrCrypto.publicKey(NostrCrypto.newKey())
        assertFalse(NostrCrypto.verify(NostrEvent.bytes(event.sig), NostrEvent.bytes(event.id), other))
    }

    /**
     * The whole wrap, opened the way a receiving wallet opens it (nostr-tools'
     * unwrapEvent, rust-nostr's UnwrappedGift): the wrap decrypts to a seal, the
     * seal's signature holds, the rumor's author is the seal's, and the rumor
     * holds the payment. Read with the bridge's own JSON parser, not the event
     * code, so the shapes are checked from outside.
     */
    @Test
    fun aWrappedPaymentOpensTheWayAWalletOpensIt() {
        val receiverSecret = NostrCrypto.newKey()
        val receiver = NostrCrypto.publicKey(receiverSecret)
        val payload = "{\"id\":\"a1b2\",\"mint\":\"https://mint.minibits.cash/Bitcoin\",\"unit\":\"sat\",\"proofs\":[]}"
        val wrap = NostrEvent.giftWrapped(payload, receiver, now = 1_700_000_000)

        assertEquals(1059, wrap.kind)
        assertEquals(listOf(listOf("p", hex(receiver))), wrap.tags)
        assertTrue(NostrCrypto.verify(NostrEvent.bytes(wrap.sig), NostrEvent.bytes(wrap.id), NostrEvent.bytes(wrap.pubkey)))
        assertTrue(wrap.createdAt <= 1_700_000_000)
        assertTrue(wrap.createdAt >= 1_700_000_000 - 172_800)

        val toWrap = NostrCrypto.conversationKey(receiverSecret, NostrEvent.bytes(wrap.pubkey))
        val sealJson = NostrCrypto.decrypt(wrap.content, toWrap) ?: return fail("the wrap did not open")
        val seal = Json.parse(sealJson.toString(Charsets.UTF_8)) as Map<String, Any?>
        assertEquals(13.0, seal["kind"])
        assertEquals(emptyList<Any>(), seal["tags"])
        val sealPub = seal["pubkey"] as String
        assertNotEquals("the seal and the wrap must be signed by different keys", wrap.pubkey, sealPub)
        assertTrue("wallets check the seal's signature",
            NostrCrypto.verify(NostrEvent.bytes(seal["sig"] as String), NostrEvent.bytes(seal["id"] as String), NostrEvent.bytes(sealPub)))

        val toSeal = NostrCrypto.conversationKey(receiverSecret, NostrEvent.bytes(sealPub))
        val rumorJson = NostrCrypto.decrypt(seal["content"] as String, toSeal) ?: return fail("the seal did not open")
        val rumor = Json.parse(rumorJson.toString(Charsets.UTF_8)) as Map<String, Any?>
        assertEquals(14.0, rumor["kind"])
        assertEquals("wallets refuse a rumor from another key", sealPub, rumor["pubkey"])
        assertEquals(payload, rumor["content"])
        assertEquals("the rumor keeps the real time", 1_700_000_000.0, rumor["created_at"])
        assertFalse("a rumor is not signed", rumor.containsKey("sig"))
        assertEquals(listOf(listOf("p", hex(receiver))), rumor["tags"])
    }

    // MARK: nprofile (NIP-19)

    @Test
    fun theNip19ExampleProfileReads() {
        val read = Bech32.nprofile("nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p")
            ?: return fail("the example did not read")
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", hex(read.pubkey))
        assertEquals(listOf("wss://r.x.com", "wss://djbas.sadkb.com"), read.relays)
    }

    @Test
    fun aBentProfileIsRefused() {
        assertNull("a wrong checksum", Bech32.nprofile("nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9q"))
        assertNull("not an nprofile", Bech32.nprofile("npub1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4"))
        assertNull("nothing to read", Bech32.nprofile("nprofile1"))
        assertNull("nothing at all", Bech32.nprofile(""))
    }

    @Test
    fun theRelaysAPaymentGoesTo() {
        // a request's own relays, at most five, wss only
        val listed = listOf("wss://relay.one", "ws://relay.two", "https://relay.three", "wss://relay.one",
            "wss://a", "wss://b", "wss://c", "wss://d", "wss://e")
        assertEquals(listOf("wss://relay.one", "wss://a", "wss://b", "wss://c", "wss://d"), NostrDelivery.relays(listed))
        // none named: Foxy's fallbacks, which are where these wallets listen
        assertEquals(NostrDelivery.FALLBACK_RELAYS, NostrDelivery.relays(emptyList()))
        // and nothing that is not a URL Foundation would read
        assertEquals(listOf("wss://ok.example"), NostrDelivery.relays(listOf("wss://", "wss://a b.example", "wss://ünï.example", " wss://ok.example ")))
    }

    @Test
    fun whatARelaySaysAboutTheEvent() {
        val id = "ab".repeat(32)
        assertEquals("", NostrDelivery.reading("[\"OK\",\"$id\",true,\"\"]", id))
        assertEquals("", NostrDelivery.reading("[\"OK\",\"$id\",false,\"duplicate: have it\"]", id))
        assertEquals("blocked: no", NostrDelivery.reading("[\"OK\",\"$id\",false,\"blocked: no\"]", id))
        assertEquals("the relay refused it", NostrDelivery.reading("[\"OK\",\"$id\",false]", id))
        assertEquals("x".repeat(120), NostrDelivery.reading("[\"OK\",\"$id\",false,\"${"x".repeat(500)}\"]", id))
        assertNull(NostrDelivery.reading("[\"NOTICE\",\"hello\"]", id))
        assertNull(NostrDelivery.reading("[\"OK\",\"someone else\",true,\"\"]", id))
        assertNull(NostrDelivery.reading("not json", id))
    }
}
