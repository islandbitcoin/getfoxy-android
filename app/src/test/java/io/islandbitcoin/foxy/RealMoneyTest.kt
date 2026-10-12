package io.islandbitcoin.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real-money rules DeviceExitTest applies before it launches anything
 * (RealMoney, docs/DEVICE-TESTS.md "Real money"): no money moves without
 * `realMoney yes`, nothing above `maxSats`, the cap of 100 sats a leg unless the
 * person raises it, and the melt's invoice read and checked, never trusted.
 *
 * The invoices are one fake-invoice.js invoice (fixed timestamp) with its
 * human-readable part swapped and the bech32 checksum computed again for each
 * (by tools/fake-invoice.js's own polymod, not RealMoney's); none pays anyone.
 */
class RealMoneyTest {
    private val body = "p4tzwuqpp58q55ukf4eqqvmvgkn8v8mm46a6zjrzys2cn9nnemw6wvhz5d8y5ssp5xc50fqf9tzys007ymgq0zmvum7u3p2zfqywet7e9whask4ry6f4sdqhvehhs7fqw3jhxapqd4jkcaqxqrrsscqpj9qrsgqp7s3yl8umdm2z6q7ud5tqz5f9lhp5aq3s65ryllmp2cyagp7lpz9j6u78qg52afgm02sxfv6acnxpzlc9hz78smasm2y5pvnde4jksqq"
    private val checksums = mapOf(
        "lnbc80n" to "hl7tff", // 8 sats
        "lnbc100n" to "mlwuz9", // 10 sats
        "lnbc1u" to "ve8n0w", // 100 sats
        "lnbc1010n" to "8ff3rh", // 101 sats
        "lnbc1m" to "9x5r4m", // 100,000 sats
        "lnbc" to "fxax96", // no amount
        "lntb80n" to "u3jfkf", // testnet
        "lnbcrt80n" to "0xexnm", // regtest
        "lnbc80p" to "kehlpn", // 8 msat
        "lnbc85p" to "xshwv5", // a pico amount BOLT11 does not allow
        "lnbc1001n" to "um7cdn", // 100.1 sats
        "lnbc620n" to "ejuyh6", // 62 sats
        "lnbc630n" to "06tg20", // 63 sats
        "lnbc980n" to "86r82q", // 98 sats
        "lnbc990n" to "3j5th4", // 99 sats
        "lnbc4950n" to "sf8vq5", // 495 sats
        "lnbc4960n" to "r3hcwz", // 496 sats
    )
    private fun inv(hrp: String) = hrp + "1" + body + checksums.getValue(hrp)

    private fun args(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private fun refused(vararg pairs: Pair<String, String>): String =
        assertThrows(RealMoney.Refused::class.java) { RealMoney.leg(args(*pairs)) }.message!!

    private val good = arrayOf("realMoney" to "yes", "maxSats" to "64", "invoice" to inv("lnbc80n"))

    @Test
    fun noMoneyMovesWithoutRealMoneyYes() {
        val withoutGo = good.filter { it.first != "realMoney" }.toTypedArray()
        assertTrue(refused(*withoutGo).contains("-e realMoney yes"))
        for (word in listOf("true", "YES", "y", "1", "", "yes ")) {
            assertTrue(word, refused(*withoutGo, "realMoney" to word).contains("explicit go"))
        }
    }

    @Test
    fun aLegWithEveryRuleMetIsAllowed() {
        val leg = RealMoney.leg(args(*good))
        assertEquals(RealMoney.Leg(RealMoney.DEFAULT_MINT, 64, 64, inv("lnbc80n"), 8, RealMoney.DEFAULT_PAY_TIMEOUT_SECONDS), leg)
        assertEquals("https://mint.minibits.cash/Bitcoin", RealMoney.DEFAULT_MINT)
        val other = RealMoney.leg(args(*good, "mint" to "https://mint.example.com", "mintSats" to "32", "payTimeoutSeconds" to "120"))
        assertEquals(RealMoney.Leg("https://mint.example.com", 64, 32, inv("lnbc80n"), 8, 120), other)
        // a QR code's uppercase invoice is the same invoice
        assertEquals(inv("lnbc80n"), RealMoney.leg(args(*good.filter { it.first != "invoice" }.toTypedArray(), "invoice" to inv("lnbc80n").uppercase())).invoice)
    }

    @Test
    fun maxSatsIsRequiredAndCappedAt100UnlessThePersonRaisesIt() {
        assertTrue(refused("realMoney" to "yes", "invoice" to inv("lnbc80n")).contains("-e maxSats"))
        for (bad in listOf("0", "-5", "ten", "1.5", "", "0100")) {
            assertTrue(bad, refused("realMoney" to "yes", "maxSats" to bad, "invoice" to inv("lnbc80n")).contains("maxSats: a whole number"))
        }
        assertEquals(100L, RealMoney.leg(args("realMoney" to "yes", "maxSats" to "100", "invoice" to inv("lnbc80n"))).maxSats)
        assertTrue(refused("realMoney" to "yes", "maxSats" to "101", "invoice" to inv("lnbc80n")).contains("-e overDefaultCap yes"))
        assertTrue(refused("realMoney" to "yes", "maxSats" to "500", "overDefaultCap" to "true", "invoice" to inv("lnbc80n")).contains("default cap"))
        assertEquals(500L, RealMoney.leg(args("realMoney" to "yes", "maxSats" to "500", "overDefaultCap" to "yes", "invoice" to inv("lnbc80n"))).mintSats)
    }

    @Test
    fun anInvoiceAboveMaxSatsIsRefused() {
        assertTrue(refused("realMoney" to "yes", "maxSats" to "64", "invoice" to inv("lnbc1u")).contains("100 sats, above maxSats 64"))
        assertTrue(refused("realMoney" to "yes", "maxSats" to "100", "invoice" to inv("lnbc1010n")).contains("101 sats, above maxSats 100"))
        assertTrue(refused("realMoney" to "yes", "maxSats" to "100", "invoice" to inv("lnbc1m")).contains("100000 sats, above maxSats 100"))
        // and one that leaves no room for the mint's fee reserve
        assertTrue(refused("realMoney" to "yes", "maxSats" to "8", "invoice" to inv("lnbc80n")).contains("mint more than the invoice"))
        assertTrue(refused("realMoney" to "yes", "maxSats" to "64", "mintSats" to "100", "invoice" to inv("lnbc80n")).contains("mintSats 100 is above maxSats 64"))
    }

    @Test
    fun theMeltLeavesRoomForTheMintsFeeReserve() {
        assertEquals(2L, RealMoney.feeMarginSats(64))
        assertEquals(2L, RealMoney.feeMarginSats(100))
        assertEquals(3L, RealMoney.feeMarginSats(201))
        assertEquals(5L, RealMoney.feeMarginSats(500))
        val goodButInvoice = good.filter { it.first != "invoice" }.toTypedArray()
        // the documented example: 64 minted, an invoice of 62 at most (63 cannot pay a 2-sat reserve)
        assertEquals(62L, RealMoney.leg(args(*goodButInvoice, "invoice" to inv("lnbc620n"))).invoiceSats)
        assertTrue(refused(*goodButInvoice, "invoice" to inv("lnbc630n")).contains("at least 2 sats over it: an invoice of at most 62 sats"))
        assertTrue(refused(*good, "mintSats" to "9").contains("at most 7 sats"))
        assertEquals(8L, RealMoney.leg(args(*good, "mintSats" to "10")).invoiceSats)
        assertEquals(98L, RealMoney.leg(args("realMoney" to "yes", "maxSats" to "100", "invoice" to inv("lnbc980n"))).invoiceSats)
        assertTrue(refused("realMoney" to "yes", "maxSats" to "100", "invoice" to inv("lnbc990n")).contains("at most 98 sats"))
        // above 200 minted the 1% is the larger
        val over = arrayOf("realMoney" to "yes", "maxSats" to "500", "overDefaultCap" to "yes")
        assertEquals(495L, RealMoney.leg(args(*over, "invoice" to inv("lnbc4950n"))).invoiceSats)
        assertTrue(refused(*over, "invoice" to inv("lnbc4960n")).contains("at least 5 sats over it"))
    }

    @Test
    fun theMeltPaysOnlyAnInvoiceThePersonGave() {
        assertTrue(refused("realMoney" to "yes", "maxSats" to "64").contains("-e invoice"))
        assertTrue(refused(*good.filter { it.first != "invoice" }.toTypedArray(), "invoice" to inv("lnbc")).contains("no amount"))
        assertTrue(refused(*good.filter { it.first != "invoice" }.toTypedArray(), "invoice" to inv("lntb80n")).contains("not a mainnet invoice"))
        assertTrue(refused(*good.filter { it.first != "invoice" }.toTypedArray(), "invoice" to inv("lnbcrt80n")).contains("not a mainnet invoice"))
    }

    @Test
    fun theInvoiceIsReadNotTrusted() {
        assertEquals(8L, RealMoney.invoiceSats(inv("lnbc80n")))
        assertEquals(10L, RealMoney.invoiceSats(inv("lnbc100n")))
        assertEquals(100L, RealMoney.invoiceSats(inv("lnbc1u")))
        assertEquals(101L, RealMoney.invoiceSats(inv("lnbc1010n")))
        assertEquals(100_000L, RealMoney.invoiceSats(inv("lnbc1m")))
        fun why(s: String) = assertThrows(RealMoney.Refused::class.java) { RealMoney.invoiceSats(s) }.message!!
        assertTrue(why(inv("lnbc80p")).contains("whole number of sats"))
        assertTrue(why(inv("lnbc1001n")).contains("whole number of sats"))
        assertTrue(why(inv("lnbc85p")).contains("not one BOLT11 allows"))
        // a changed amount breaks the checksum: the amount cannot be edited down
        assertTrue(why("lnbc10n1" + body + checksums.getValue("lnbc80n")).contains("checksum"))
        assertTrue(why(inv("lnbc80n").dropLast(1) + "q").contains("checksum"))
        assertTrue(why("Lnbc80n1" + body + checksums.getValue("lnbc80n")).contains("mixes upper and lower case"))
        for (junk in listOf("", "lnbc", "lnbc80n1", "hello", "lightning:" + inv("lnbc80n"), inv("lnbc80n") + "'")) {
            assertThrows(junk, RealMoney.Refused::class.java) { RealMoney.invoiceSats(junk) }
        }
    }

    @Test
    fun theMintIsAnHttpsUrlAndDefaultsToMinibits() {
        for (bad in listOf("http://mint.minibits.cash/Bitcoin", "https://", "mint.minibits.cash", "https://mint.minibits.cash/Bitcoin'); alert(1); ('", "https://a b.com", "javascript:alert(1)")) {
            assertTrue(bad, refused(*good, "mint" to bad).contains("an https URL of a mint"))
        }
        for (bad in listOf("0", "29", "3601", "x")) assertThrows(bad, RealMoney.Refused::class.java) { RealMoney.leg(args(*good, "payTimeoutSeconds" to bad)) }
    }
}
