package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.FakeHost
import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.keychain.CounterStore
import io.islandbitcoin.foxy.keychain.Flags
import io.islandbitcoin.foxy.keychain.SeedCandidates
import io.islandbitcoin.foxy.keychain.SeedEnvironment
import io.islandbitcoin.foxy.keychain.SeedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * S15: the bridge serves exactly the P3 contract. Every name in the table is in
 * conformance/contract.json; the 18 P1 actions, the 7 P2 actions, the 35 P3
 * actions (`bluetoothAsk`, `tapReceiveStart` and `tapReceiveStop` P3 since
 * ENG-656; `cardOwnerKey`, `cardOwnerSign` and `cardTime` since ENG-666; the card
 * session's `cardBegin`, `cardSend`, `cardSay`, `cardAgain` and `cardEnd` since
 * ENG-667; `inboxOpen`, `inboxClose`, `inboxAnswer` and `onionPost` since ENG-672) and the `p1` stubs of the LATER
 * actions are all there, and no P3 action is answered by its stub (four have
 * one: the real handler wins); each LATER stub answers the contract's text or
 * error byte for byte; every P3 answer is one the contract lists; with no Tor,
 * each P2 action answers as its stub said P1 would; a LATER action with no stub
 * is "Unknown action: <name>"; `{username}`, `{}`, a non-object and `{haptic}`
 * get no reply.
 */
class BridgeContractTest {
    private val host = FakeHost()
    private val dir = TestSupport.tempDir("contract")
    private val env = SeedEnvironment(
        savedSeed = { SeedVault.SecretsSeed.Absent },
        seedPresent = { false },
        write = { null },
        counters = CounterStore(dir),
        candidates = SeedCandidates(),
        wordlist = { TestSupport.wordlist },
        generate = { TestSupport.MADE },
        migrationWindowOpen = { false },
        closeMigrationWindow = {},
        flags = Flags(dir),
        // seedWipe's delete goes through, so its answer is the contract's {"wiped", "created"}
        delete = { true },
    )
    private val bridge = FoxyBridge(host, { env }, Executor { it.run() }, isDebug = false)

    private fun actions(phase: String) = entries.filter { it["kind"] == "action" && it["phase"] == phase }.map { it["name"] as String }.toSet()
    private val entries = TestSupport.contractEntries()

    private fun send(json: String) = bridge.onMessage(json, true, FoxyBridge.ORIGIN)

    /** What the page would be told: the (id, text, error) of every _scanResult evaluated. */
    private fun replies(): List<Triple<String?, String?, String?>> = host.evaluated.filter { it.contains("._scanResult(") }.map { js ->
        val args = js.substringAfter("_scanResult(").removeSuffix(")")
        @Suppress("UNCHECKED_CAST")
        val list = Json.parse("[$args]") as List<String?>
        Triple(list[0], list[1], list[2])
    }

    @Test
    fun everyNameInTheTableIsAnActionOfTheContract() {
        val actions = entries.filter { it["kind"] == "action" }.map { it["name"] as String }.toSet()
        for (name in bridge.handlers.keys) assertTrue("$name is not in contract.json", name in actions)
    }

    private val served = setOf("P1", "P2", "P3")

    @Test
    fun theP1P2AndP3ActionsAndTheLaterStubsAreAllThere() {
        val p1 = actions("P1")
        val p2 = actions("P2")
        val p3 = actions("P3")
        val stubs = entries.filter { it["kind"] == "action" && it["p1"] != null && it["phase"] !in setOf("P1", "P2") }.map { it["name"] as String }.toSet()
        val laterStubs = entries.filter { it["kind"] == "action" && it["p1"] != null && it["phase"] !in served }.map { it["name"] as String }.toSet()
        assertEquals(18, p1.size)
        assertEquals(setOf("privacy", "torRetry", "unprotected", "mintRequest", "price", "candles", "hostsKnown"), p2)
        assertEquals(35, p3.size)
        assertTrue("bluetoothAsk is P3 (ENG-656)", "bluetoothAsk" in p3)
        assertTrue("a card's owner key and its signed time are P3 (ENG-666)", p3.containsAll(setOf("cardOwnerKey", "cardOwnerSign", "cardTime")))
        assertTrue("the receive screen's TAP is P3 (ENG-656)", p3.containsAll(setOf("tapReceiveStart", "tapReceiveStop")))
        assertTrue("the card session is P3 (ENG-667)", p3.containsAll(setOf("cardBegin", "cardSend", "cardSay", "cardAgain", "cardEnd")))
        assertTrue("the onion inbox and post are P3 (ENG-672)", p3.containsAll(setOf("inboxOpen", "inboxClose", "inboxAnswer", "onionPost")))
        assertFalse("nostrSend is not (the ENG-672 follow-up)", "nostrSend" in p3)
        assertEquals(setOf("captured", "orbotRefresh", "orbotAccess", "seedProtection", "tapPayStart", "pasteStart", "pasteStop"), stubs)
        assertEquals(setOf("orbotRefresh", "orbotAccess", "tapPayStart"), laterStubs)
        assertEquals(p1, FoxyBridge.P1_ACTIONS)
        assertEquals(p2, FoxyBridge.P2_ACTIONS)
        assertEquals(p3, FoxyBridge.P3_ACTIONS)
        // the generated table still carries the P3 stubs; the bridge skips them for the real handlers
        assertEquals(stubs, ContractStubs.p1.keys)
        assertEquals(p1 + p2 + p3 + laterStubs, bridge.handlers.keys)
    }

    @Test
    fun noP3ActionIsAnsweredByItsStub() {
        // each answer below differs from the stub's: a stub left in the table (or put after the real handler) fails it
        host.isCaptured = true
        env.passcodeSet = { false }
        send("""{"action":"captured","id":"c"}""")
        send("""{"action":"seedProtection","id":"s"}""")
        send("""{"action":"pasteStart","id":"p","x":20,"y":600,"w":300,"h":60}""")
        send("""{"action":"pasteStop","id":"q"}""")
        val got = replies().associateBy { it.first }
        assertEquals(Triple("c", "yes", null), got["c"])
        assertEquals(mapOf("mode" to "none", "passcode" to false), Json.parse(got.getValue("s").second!!))
        assertEquals(Triple("p", "ok", null), got["p"])
        assertEquals(Triple("q", "", null), got["q"])
        assertEquals("pasteStop reached the real door", 1, host.pasteStops)
        for (name in setOf("captured", "seedProtection", "pasteStart", "pasteStop")) {
            assertTrue("$name has a stub in the generated table", name in ContractStubs.p1.keys)
        }
    }

    /** Whether `error` is one the contract lists, where "<…>" or "(...)" in a listed error stands for any text. */
    private fun listed(error: String, errors: List<String>): Boolean = errors.any { e ->
        val cut = listOf(e.indexOf('<'), e.indexOf("(...)")).filter { it >= 0 }.minOrNull()
        if (cut == null) e == error else error.startsWith(e.substring(0, cut))
    }

    @Test
    fun everyP3AnswerIsOneTheContractLists() {
        val bodies = mapOf(
            "captured" to "", "scan" to ""","prompt":"Scan an ecash token"""",
            "previewStart" to ""","x":20,"y":400,"w":300,"h":200""", "previewStop" to "",
            "pasteStart" to ""","x":20,"y":600,"w":300,"h":60""", "pasteStop" to "",
            "clipboard" to "", "copy" to ""","text":"cashuBx","sensitive":true""", "share" to ""","text":"cashuBx"""",
            "notify" to ""","kind":"received"""", "biometric" to ""","reason":"Unlock Foxy","passcode":true""",
            "openSettings" to "", "openCompany" to ""","which":"strike"""",
            "seedShow" to ""","verify":true""", "seedEnter" to "", "seedAdopt" to ""","candidate":"feed"""",
            "seedCandidateForget" to ""","candidate":"feed"""", "seedWipe" to "", "seedProtection" to "", "seedProtect" to ""","mode":"none"""",
            "bluetoothAsk" to "", "tapReceiveStart" to ""","payload":"","early":true""", "tapReceiveStop" to "",
            "cardOwnerKey" to ""","key":"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"""",
            "cardOwnerSign" to ""","key":"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","label":"load","nonce":"000102030405060708090a0b0c0d0e0f","value":""""",
            "cardTime" to "",
            // the card session (ENG-667) on a host that has no card link: a phone that cannot read a card
            "cardBegin" to ""","text":"Tap behind the phone."""", "cardSend" to ""","apdu":"00a404000af0464f5859434152440100"""",
            "cardSay" to ""","text":"Keep the card there"""", "cardAgain" to ""","text":"Hold the card here again"""",
            "cardEnd" to ""","text":"Done."""",
            "inboxOpen" to "", "inboxClose" to ""","hold":30""", "inboxAnswer" to ""","answer":"00112233445566778899aabbccddeeff","status":200,"text":""""",
            "onionPost" to ""","url":"http://abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd.onion/0123456789abcdef0123456789abcdef","body":"{}","timeout":30000""",
        )
        assertEquals(FoxyBridge.P3_ACTIONS, bodies.keys)
        // and each again with what the page should not send, which must still be a listed answer
        val wrong = mapOf(
            "previewStart" to ""","x":0,"y":0,"w":1,"h":1""", "pasteStart" to ""","x":0,"y":0,"w":1,"h":1""",
            "copy" to ""","text":""""", "share" to ""","text":""""", "notify" to ""","kind":"lure"""",
            "openCompany" to ""","which":"evil"""", "seedProtect" to ""","mode":"faceid"""", "seedAdopt" to "",
            "seedCandidateForget" to "", "tapReceiveStart" to ""","payload":"not an offer"""",
            "cardSend" to ""","apdu":"zz"""", "cardBegin" to ""","text":5""", "cardEnd" to ""","error":"x\u0000y"""",
            "cardOwnerKey" to ""","key":"04"""", "inboxClose" to ""","hold":"forever"""", "inboxAnswer" to ""","status":"200"""",
            "onionPost" to ""","url":"https://mint.example.com/v1/swap","body":""""", "cardOwnerSign" to ""","key":"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","label":"lock","nonce":"000102030405060708090a0b0c0d0e0f","value":""""",
        )
        for (round in listOf(bodies, wrong)) {
            for ((name, extra) in round) {
                host.evaluated.clear()
                send("""{"action":"$name","id":"r"$extra}""")
                val entry = entries.single { it["name"] == name }
                @Suppress("UNCHECKED_CAST")
                val reply = entry["reply"] as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val errors = reply["errors"] as List<String>
                assertEquals("$name ($extra) answered once", 1, replies().size)
                val r = replies().single()
                assertTrue("$name: exactly one of text and error", (r.second == null) != (r.third == null))
                if (r.third != null) {
                    assertTrue("$name: \"${r.third}\" is not in the contract's errors $errors", listed(r.third!!, errors))
                    continue
                }
                when (reply["format"]) {
                    "text" -> {
                        @Suppress("UNCHECKED_CAST")
                        val values = reply["values"] as List<String>?
                        if (values != null) assertTrue("$name: \"${r.second}\" is not in $values", r.second in values)
                    }
                    "json" -> {
                        @Suppress("UNCHECKED_CAST")
                        val fields = ((reply["json"] as Map<String, Any?>)["fields"] as Map<String, Any?>).keys
                        @Suppress("UNCHECKED_CAST")
                        val got = Json.parse(r.second!!) as Map<String, Any?>
                        assertTrue("$name: ${got.keys} not within $fields", fields.containsAll(got.keys))
                    }
                }
            }
        }
    }

    @Test
    fun withNoTorEachP2ActionAnswersAsP1Did() {
        val p2 = entries.filter { it["kind"] == "action" && it["phase"] == "P2" }
        for ((n, e) in p2.withIndex()) {
            val name = e["name"] as String
            @Suppress("UNCHECKED_CAST")
            val p1 = e["p1"] as Map<String, Any?>
            host.evaluated.clear()
            send("""{"action":"$name","id":"n$n","on":true,"url":"https://mint.example.com","method":"GET","hosts":["a.b"]}""")
            val r = replies().single()
            assertEquals(name, "n$n", r.first)
            assertEquals(name, p1["error"], r.third)
            val want = p1["text"] as String? ?: continue
            val got = r.second!!
            // the snapshot is the same object, its keys written in another order
            if (want.startsWith("{")) assertEquals(name, Json.parse(want), Json.parse(got)) else assertEquals(name, want, got)
        }
    }

    @Test
    fun eachLaterStubAnswersTheContractsTextOrErrorExactly() {
        val stubs = entries.filter { it["kind"] == "action" && it["p1"] != null && it["phase"] !in served }
        assertEquals(3, stubs.size)
        for ((n, e) in stubs.withIndex()) {
            val name = e["name"] as String
            @Suppress("UNCHECKED_CAST")
            val p1 = e["p1"] as Map<String, Any?>
            host.evaluated.clear()
            send("""{"action":"$name","id":"s$n","on":true,"url":"https://mint.example","method":"GET","hosts":["a.b"]}""")
            assertEquals(name, listOf(Triple("s$n", p1["text"] as String?, p1["error"] as String?)), replies())
        }
    }

    @Test
    fun aLaterPhasesActionIsUnknown() {
        // narrowed in P3: the later actions are now the LATER phase's only (seedShow, which this asked before, is served);
        // narrowed again in ENG-656: bluetoothAsk is P3, answered "unsupported" with its push (BluetoothAskTest),
        // and tapReceiveStart and tapReceiveStop are P3, answered as an iPhone without Bluetooth answers (TapReceiveTest);
        // and in ENG-666: cardOwnerKey, cardOwnerSign and cardTime are P3, answered as iOS answers them (CardBridgeTest);
        // and in ENG-667: the card session (cardBegin, cardSend, cardSay, cardAgain, cardEnd) is P3 (CardSessionTest);
        // and in ENG-672: inboxOpen, inboxClose, inboxAnswer and onionPost are P3 (DeliveryBridgeTest), nostrSend stays.
        // Named one by one, so a later action that stops being unknown has to be taken off here by hand.
        val later = entries.filter { it["kind"] == "action" && it["phase"] !in served && it["p1"] == null }.map { it["name"] as String }
        assertEquals(
            setOf(
                "nostrSend",
                "tapSend", "tapChange", "tapChangeKept", "tapAsking", "tapChangeDue",
                "tapQuote", "tapTerms", "tapPayStop", "shakeStart", "shakeStop",
            ),
            later.toSet(),
        )
        assertEquals(11, later.size)
        assertTrue(later.contains("nostrSend"))
        for (name in listOf("inboxOpen", "inboxClose", "inboxAnswer", "onionPost")) assertFalse("$name is no longer unknown", later.contains(name))
        assertFalse("bluetoothAsk is no longer unknown", later.contains("bluetoothAsk"))
        assertFalse("tapReceiveStart is no longer unknown", later.contains("tapReceiveStart"))
        assertFalse("tapReceiveStop is no longer unknown", later.contains("tapReceiveStop"))
        for (name in listOf("cardOwnerKey", "cardOwnerSign", "cardTime")) assertFalse("$name is no longer unknown", later.contains(name))
        for (name in CardBridge.ACTIONS) assertFalse("$name is no longer unknown (ENG-667)", later.contains(name))
        assertTrue(later.none { it in FoxyBridge.P3_ACTIONS })
        for (name in later) {
            host.evaluated.clear()
            send("""{"action":"$name","id":"u1"}""")
            assertEquals(name, listOf(Triple("u1", null, "Unknown action: $name")), replies())
        }
        host.evaluated.clear()
        // inboxOpen, asked here until ENG-672 served it: a later action with a field it does not take is still unknown
        send("""{"action":"nostrSend","id":"x","verify":true}""")
        assertEquals(listOf(Triple("x", null, "Unknown action: nostrSend")), replies())
    }

    @Test
    fun usernameEmptyNonObjectsAndHapticsGetNoReply() {
        for (message in listOf("""{"username":"satoshi"}""", "{}", "[]", "\"seedStatus\"", "5", "null", "not json",
            """{"action":"seedStatus"}""", """{"id":"1"}""", """{"action":5,"id":"1"}""", """{"action":"seedStatus","id":1}""",
            """[{"action":"seedStatus","id":"1"}]""")) {
            host.evaluated.clear()
            send(message)
            assertEquals(message, emptyList<String>(), host.evaluated)
        }
        host.evaluated.clear()
        send("""{"haptic":"success","action":"seedStatus","id":"h1"}""")
        assertEquals("a haptic message runs no action", emptyList<String>(), host.evaluated)
        assertEquals(listOf("success"), host.haptics)
    }

    @Test
    fun theP1ActionsOfTheContractAnswer() {
        for ((n, action) in FoxyBridge.P1_ACTIONS.withIndex()) {
            host.evaluated.clear()
            send("""{"action":"$action","id":"p$n"}""")
            val r = replies()
            assertEquals(action, 1, r.size)
            assertEquals("p$n", r[0].first)
            assertTrue("$action: exactly one of text and error", (r[0].second == null) != (r[0].third == null))
            assertTrue("$action is not unknown", r[0].third?.startsWith("Unknown action") != true)
        }
    }

    @Test
    fun theSmallP1ActionsAnswerAsTheContractSays() {
        send("""{"action":"covered","id":"c1"}""")
        send("""{"action":"awake","id":"a1","on":true}""")
        send("""{"action":"isDebug","id":"d1"}""")
        send("""{"action":"log","id":"l1","text":"[foxy] hello"}""")
        send("""{"action":"fieldLogClear","id":"f1"}""")
        assertEquals(listOf(Triple("c1", "ok", null), Triple("a1", "ok", null), Triple("d1", "no", null), Triple("l1", "ok", null),
            Triple("f1", "ok", null)), replies())
        assertEquals(1, host.coveredCount)
        assertEquals(true, host.awake)
        host.evaluated.clear()
        send("""{"action":"fieldLog","id":"g1","bad":false}""")
        val text = replies().single().second!!
        assertTrue(text.startsWith("[") && text.contains("the log was cleared"))
    }
}
