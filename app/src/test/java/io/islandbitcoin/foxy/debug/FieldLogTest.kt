package io.islandbitcoin.foxy.debug

import io.islandbitcoin.foxy.TestSupport
import io.islandbitcoin.foxy.bridge.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * S18: the field log redacts tokens, invoices, payment requests, onion
 * addresses, nostr keys and long hex on the way in; marks trouble; keeps only
 * the connection diary across a relaunch, once; and answers the page's shape.
 * The redaction cases follow FieldLog.swift's patterns.
 */
class FieldLogTest {
    private val dir = TestSupport.tempDir("fieldlog")

    @Before
    fun reset() = FieldLog.resetForTests()

    @After
    fun clean() {
        FieldLog.resetForTests()
        dir.deleteRecursively()
    }

    @Test
    fun secretsAreRedactedOnTheWayIn() {
        val token = "cashuB" + "A1b2C3d4E5f6G7h8K9m0P1q2R3s4".repeat(2)
        val invoice = "lnbc10u1" + "pjq9zqxpp5".repeat(5)
        val request = "creqA" + "pGF0eCJiOWE4ZjQ".repeat(3)
        val onion = "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
        val nsec = "nsec1" + "vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        val hex = "a".repeat(64)
        val cases = listOf(
            "[foxy] got $token from the scanner" to "[foxy] got <cashu token, ${token.length} chars, not logged> from the scanner",
            "pay $invoice now" to "pay <lightning invoice, ${invoice.length} chars, not logged> now",
            "request $request" to "request <payment request, ${request.length} chars, not logged>",
            "inbox at $onion:80" to "inbox at <onion address, ${onion.length} chars, not logged>:80",
            "key $nsec" to "key <nostr key, ${nsec.length} chars, not logged>",
            "secret $hex end" to "secret <hex, 64 chars, not logged> end",
            "CASHUA" + "x".repeat(25) to "<cashu token, 31 chars, not logged>",
        )
        for ((raw, want) in cases) assertEquals(raw, want, FieldLog.redact(raw))
        // what makes a report answerable stays
        for (kept in listOf("[foxy] mint.minibits.cash answered 200", "balance 21000 sats", "keyset 009a1f293253e41e", "hex " + "b".repeat(63))) {
            assertEquals(kept, FieldLog.redact(kept))
        }
        FieldLog.write("[foxy-js] claimed $token")
        assertFalse(FieldLog.json().contains("A1b2"))
    }

    @Test
    fun troubleIsMarkedAndTheShapeIsThePages() {
        FieldLog.clock = { 1_234L }
        FieldLog.write("[foxy] all fine")
        FieldLog.write("[foxy] the mint request FAILED")
        FieldLog.write("[foxy] Foxy is not connected to Tor.")
        assertEquals(3, FieldLog.lines().size)
        assertEquals(listOf("[foxy] the mint request FAILED", "[foxy] Foxy is not connected to Tor."), FieldLog.lines(onlyBad = true).map { it.text })
        @Suppress("UNCHECKED_CAST")
        val json = Json.parse(FieldLog.json()) as List<Map<String, Any?>>
        assertEquals(mapOf("at" to 1234.0, "text" to "[foxy] all fine", "bad" to false), json[0])
        FieldLog.clear()
        assertEquals("[]", FieldLog.json())
    }

    @Test
    fun theRingHoldsTheNewest() {
        repeat(FieldLog.CAPACITY + 5) { FieldLog.write("line $it") }
        val lines = FieldLog.lines()
        assertEquals(FieldLog.CAPACITY, lines.size)
        assertEquals("line 5", lines.first().text)
        assertEquals("line ${FieldLog.CAPACITY + 4}", lines.last().text)
    }

    @Test
    fun onlyTheConnectionDiaryOutlivesARelaunchAndOnlyOnce() {
        FieldLog.begin(dir, "[foxy] Foxy 0.1.0 (1) on Android 16")
        FieldLog.write("[foxy] tor gate: CANNOT CONNECT TO TOR")
        FieldLog.write("[foxy-js] +12ms [log] [foxy] tor gate: offline")
        FieldLog.write("[foxy] counters: reserved 10 for a keyset")
        FieldLog.saveForNextRun()
        val file = File(dir, FieldLog.LAST_RUN_FILE)
        assertTrue(file.exists())
        val saved = file.readText()
        assertTrue(saved.contains("tor gate: CANNOT"))
        assertTrue("matched anywhere in the line, the page's lines too", saved.contains("tor gate: offline"))
        assertTrue("the banner", saved.contains("[foxy] Foxy 0.1.0"))
        assertFalse("nothing about money", saved.contains("counters"))

        // the next launch reads it, marks it, and deletes it
        FieldLog.resetForTests()
        FieldLog.begin(dir, "[foxy] Foxy 0.1.0 (1) on Android 16")
        assertFalse("read once and deleted", file.exists())
        val texts = FieldLog.lines().map { it.text }
        assertTrue(texts.first().startsWith("last run · "))
        assertTrue(texts.contains("last run · [foxy] tor gate: CANNOT CONNECT TO TOR"))
        assertTrue("trouble stays trouble", FieldLog.lines(onlyBad = true).any { it.text.contains("CANNOT CONNECT") })
        assertFalse(texts.any { it.contains("counters") })
        // and kept again, said once however many launches ago
        FieldLog.saveForNextRun()
        assertFalse(file.readText().contains("last run · last run"))
        FieldLog.clear()
        assertFalse("cleared from the LOGS screen, the diary goes too", file.exists())
    }
}
