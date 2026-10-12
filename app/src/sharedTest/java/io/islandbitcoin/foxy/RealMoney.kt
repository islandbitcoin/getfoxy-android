package io.islandbitcoin.foxy

import java.math.BigInteger

/**
 * The money rules of a device leg that moves real sats (docs/DEVICE-TESTS.md,
 * "Real money"). Android is tested on real mints with real ecash, so a leg that
 * mints or melts is refused unless every rule holds, before the app is even
 * launched:
 *
 * - the person said go: `realMoney` is exactly `yes`;
 * - `maxSats` is given, and is 100 or less unless `overDefaultCap` is `yes` too
 *   (the person raising the cap for this run, by name);
 * - the mint quote (`mintSats`, default `maxSats`) is at most `maxSats`, and the
 *   person pays it from their own wallet;
 * - the melt pays only `invoice`, an invoice the person made in their own
 *   wallet: a mainnet BOLT11 with an amount, a whole number of sats, at most
 *   `maxSats`, and leaving room under `mintSats` for the mint's Lightning fee
 *   reserve: `invoice + feeMarginSats(mintSats) <= mintSats` (2 sats, or 1% of
 *   `mintSats` rounded up, whichever is more; so 64 minted pays an invoice of
 *   at most 62). No fake invoice is ever paid on a real mint;
 * - the mint is `mint` (default [DEFAULT_MINT], the page's own default), over https.
 *
 * Pure Kotlin with no Android in it, in a source folder both the JVM tests and
 * the instrumented tests compile (`src/sharedTest`), so the refusals are tested
 * on the JVM (RealMoneyTest) and DeviceExitTest applies the same code.
 */
object RealMoney {
    const val DEFAULT_MINT = "https://mint.minibits.cash/Bitcoin"
    const val DEFAULT_CAP_SATS = 100L
    const val DEFAULT_PAY_TIMEOUT_SECONDS = 600L

    /**
     * The room a melt leaves under the amount minted for the mint's Lightning
     * fee reserve (NUT-05's `fee_reserve`; Foxy's pay owes the amount and the
     * reserve): 2 sats, or 1% of [mintSats] rounded up, whichever is more.
     */
    fun feeMarginSats(mintSats: Long): Long = maxOf(2L, (mintSats + 99L) / 100L)

    /** Why a leg does not run. Nothing has been launched or sent when this is thrown. */
    class Refused(message: String) : IllegalArgumentException(message)

    /** A leg every rule allowed. [invoice] is lowercase, as checked. */
    data class Leg(
        val mint: String,
        val maxSats: Long,
        val mintSats: Long,
        val invoice: String,
        val invoiceSats: Long,
        val payTimeoutSeconds: Long,
    )

    private val WHOLE = Regex("^[1-9][0-9]{0,8}$")
    private val MINT_URL = Regex("^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*/?$")

    /** The leg the arguments allow (`-e name value` on `am instrument`), or [Refused]. */
    fun leg(arg: (String) -> String?): Leg {
        if (arg("realMoney") != "yes") {
            throw Refused("this leg moves real money on a real mint: it runs only with the person's explicit go, -e realMoney yes")
        }
        val maxSats = number(arg("maxSats"), "maxSats")
            ?: throw Refused("say how much this leg may move: -e maxSats <sats> (at most $DEFAULT_CAP_SATS by default)")
        if (maxSats > DEFAULT_CAP_SATS && arg("overDefaultCap") != "yes") {
            throw Refused("maxSats $maxSats is above the default cap of $DEFAULT_CAP_SATS sats a leg; only the person raises it, with -e overDefaultCap yes")
        }
        val mintSats = number(arg("mintSats"), "mintSats") ?: maxSats
        if (mintSats > maxSats) throw Refused("mintSats $mintSats is above maxSats $maxSats")
        val mint = arg("mint") ?: DEFAULT_MINT
        if (!MINT_URL.matches(mint)) throw Refused("mint: an https URL of a mint, like $DEFAULT_MINT (got \"$mint\")")
        val given = arg("invoice")
            ?: throw Refused("the melt pays only an invoice the person made in their own wallet: -e invoice <bolt11>")
        val invoice = normalised(given)
        val invoiceSats = invoiceSats(invoice)
        if (invoiceSats > maxSats) throw Refused("the invoice is for $invoiceSats sats, above maxSats $maxSats: nothing was sent")
        val margin = feeMarginSats(mintSats)
        if (invoiceSats + margin > mintSats) {
            throw Refused(
                "the invoice is for $invoiceSats sats and the leg mints $mintSats: mint more than the invoice and the mint's fee reserve " +
                    "(at least $margin sats over it: an invoice of at most ${mintSats - margin} sats), or ask for a smaller invoice",
            )
        }
        val timeout = number(arg("payTimeoutSeconds"), "payTimeoutSeconds") ?: DEFAULT_PAY_TIMEOUT_SECONDS
        if (timeout !in 30L..3600L) throw Refused("payTimeoutSeconds: 30 to 3600 (got $timeout)")
        return Leg(mint, maxSats, mintSats, invoice, invoiceSats, timeout)
    }

    private fun number(value: String?, name: String): Long? {
        if (value == null) return null
        if (!WHOLE.matches(value)) throw Refused("$name: a whole number above zero (got \"$value\")")
        return value.toLong()
    }

    private fun normalised(invoice: String): String {
        val s = invoice.trim()
        if (s != s.lowercase() && s != s.uppercase()) throw Refused("the invoice mixes upper and lower case: not a BOLT11 invoice")
        return s.lowercase()
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private val AMOUNT = Regex("^([0-9]+)([munp]?)$")

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    /**
     * The amount of a mainnet BOLT11 invoice, in whole sats, or [Refused]: the
     * bech32 checksum must hold, the prefix must be `lnbc` (not testnet or
     * regtest), and the amount must be there and be a whole number of sats.
     * The signature is not checked: the mint checks it when it is asked to pay,
     * and the amount the mint pays is the one read here.
     */
    fun invoiceSats(invoice: String): Long {
        val s = normalised(invoice)
        val at = s.lastIndexOf('1')
        if (at < 4 || s.length - at - 1 < 7 + 104 + 6) throw Refused("not a BOLT11 invoice")
        val hrp = s.substring(0, at)
        val words = s.substring(at + 1).map { CHARSET.indexOf(it) }
        if (words.any { it < 0 } || hrp.any { it.code !in 33..126 }) throw Refused("not a BOLT11 invoice")
        val expanded = hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }
        if (polymod(expanded + words) != 1) throw Refused("the invoice's checksum does not hold: not a BOLT11 invoice")
        if (!hrp.startsWith("lnbc")) throw Refused("not a mainnet invoice (lnbc…): \"$hrp\"")
        val amount = hrp.removePrefix("lnbc")
        if (amount.isEmpty()) throw Refused("an invoice with no amount is refused: its amount is what is checked against maxSats")
        val m = AMOUNT.matchEntire(amount) ?: throw Refused("not a mainnet invoice with an amount: \"$hrp\"")
        val digits = m.groupValues[1]
        if (digits.length > 15 || digits.startsWith("0")) throw Refused("the invoice's amount is not one BOLT11 allows: \"$amount\"")
        val n = BigInteger(digits)
        val msat = when (m.groupValues[2]) {
            "" -> n * BigInteger.valueOf(100_000_000_000L)
            "m" -> n * BigInteger.valueOf(100_000_000L)
            "u" -> n * BigInteger.valueOf(100_000L)
            "n" -> n * BigInteger.valueOf(100L)
            else -> {
                if (n.mod(BigInteger.TEN).signum() != 0) throw Refused("the invoice's amount is not one BOLT11 allows: \"$amount\"")
                n / BigInteger.TEN
            }
        }
        val thousand = BigInteger.valueOf(1000L)
        if (msat.mod(thousand).signum() != 0) throw Refused("the invoice is not for a whole number of sats: \"$amount\"")
        val sats = msat / thousand
        if (sats.bitLength() > 62) throw Refused("the invoice's amount is far above any cap: \"$amount\"")
        return sats.toLong()
    }
}
