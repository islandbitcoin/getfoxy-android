package io.islandbitcoin.foxy.bridge

/**
 * What the receive screen's TAP may put on the air, and the `_tap` push, as
 * iOS writes them (Foxy/Bluetooth/TapProtocol.swift `isPayload`, `isInvoice`,
 * `isRequest`; FoxyBridge+Tap.swift `pushTap`). Android puts nothing on the air
 * until BLE tap to pay lands (ENG-650), but a `tapReceiveStart` is still
 * refused exactly where iOS refuses it, so the page meets one contract.
 */
object TapRules {
    /** TapProtocol.swift `maxPayload`. */
    const val MOST_PAYLOAD_BYTES = 4096

    /** TapProtocol.swift `maxInvoice`. */
    const val MOST_INVOICE_BYTES = 2048

    /** FoxyBridge+Tap.swift `handleTapReceiveStart`: a non-empty payload that is not an offer. */
    const val NOT_PAYABLE = "Not something a payer could pay."

    /** TapLink.swift `TapStage.unsupported`: this phone has no Bluetooth tap to pay. */
    const val UNSUPPORTED = "unsupported"

    private val invoiceShape = Regex("^ln[a-z0-9]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+$")
    private val requestShape = Regex("^creq[A-Za-z0-9_=+/-]{16,}$")

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** A Lightning invoice by shape: one case throughout, `ln…1…` in bech32's alphabet. */
    fun isInvoice(s: String): Boolean {
        if (bytes(s) < 20 || bytes(s) > MOST_INVOICE_BYTES) return false
        val t = s.lowercase()
        if (s != t && s != s.uppercase()) return false
        return invoiceShape.containsMatchIn(t)
    }

    /** A Cashu payment request by shape alone; what is in it is the page's to read. */
    fun isRequest(s: String): Boolean {
        if (bytes(s) < 20 || bytes(s) > MOST_PAYLOAD_BYTES) return false
        return requestShape.containsMatchIn(s)
    }

    /** `{"v":2,"inv"?,"req"?}` with at least one of the two, each of its shape, at most 4096 bytes. */
    fun isPayload(s: String): Boolean {
        if (bytes(s) > MOST_PAYLOAD_BYTES) return false
        val o = try {
            Json.parse(s)
        } catch (e: Json.Malformed) {
            return false
        } as? Map<*, *> ?: return false
        // JSONSerialization's `as? Int == 2`: the number two, however it was written, and not `true`
        if ((o["v"] as? Double) != 2.0) return false
        // `as? String`, as iOS reads them: a half that is not a string counts as missing
        val inv = o["inv"] as? String
        val req = o["req"] as? String
        if (inv != null && !isInvoice(inv)) return false
        if (req != null && !isRequest(req)) return false
        return inv != null || req != null
    }

    /** `window.FoxyWallet._tap({"side":…,"stage":…})`, guarded as every push is. */
    fun push(side: String, stage: String): String =
        "window.FoxyWallet && window.FoxyWallet._tap && window.FoxyWallet._tap(" +
            "{\"side\":" + Json.quote(side) + ",\"stage\":" + Json.quote(stage) + "})"
}
