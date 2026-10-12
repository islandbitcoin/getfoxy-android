package io.islandbitcoin.foxy

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DeviceExitTest's money rules, read from the instrumentation arguments the way
 * it reads them (a Bundle of `-e name value`): no money without `realMoney yes`,
 * no invoice above `maxSats`. Launches nothing and contacts nothing, so it runs
 * with the rest of the suite; RealMoneyTest has the rules in full on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class DeviceExitRefusalTest {
    // fake-invoice.js invoices with nothing behind them (RealMoneyTest): 8 sats and 100 sats
    private val body = "p4tzwuqpp58q55ukf4eqqvmvgkn8v8mm46a6zjrzys2cn9nnemw6wvhz5d8y5ssp5xc50fqf9tzys007ymgq0zmvum7u3p2zfqywet7e9whask4ry6f4sdqhvehhs7fqw3jhxapqd4jkcaqxqrrsscqpj9qrsgqp7s3yl8umdm2z6q7ud5tqz5f9lhp5aq3s65ryllmp2cyagp7lpz9j6u78qg52afgm02sxfv6acnxpzlc9hz78smasm2y5pvnde4jksqq"
    private val eight = "lnbc80n1${body}hl7tff"
    private val hundred = "lnbc1u1${body}ve8n0w"

    private fun bundle(vararg pairs: Pair<String, String>) = Bundle().apply { for ((k, v) in pairs) putString(k, v) }

    private fun refused(b: Bundle): String =
        assertThrows(RealMoney.Refused::class.java) { RealMoney.leg { b.getString(it) } }.message!!

    @Test
    fun noMoneyWithoutRealMoneyYes() {
        assertTrue(refused(bundle("foxyExit" to "true", "maxSats" to "64", "invoice" to eight)).contains("-e realMoney yes"))
        assertTrue(refused(bundle("foxyExit" to "true", "realMoney" to "true", "maxSats" to "64", "invoice" to eight)).contains("-e realMoney yes"))
    }

    @Test
    fun noInvoiceAboveMaxSats() {
        assertTrue(refused(bundle("foxyExit" to "true", "realMoney" to "yes", "maxSats" to "64", "invoice" to hundred)).contains("above maxSats 64"))
        assertTrue(refused(bundle("foxyExit" to "true", "realMoney" to "yes", "maxSats" to "64")).contains("-e invoice"))
    }
}
