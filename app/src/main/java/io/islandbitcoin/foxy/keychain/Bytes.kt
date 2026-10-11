package io.islandbitcoin.foxy.keychain

/** Hex and wiping, as NUT13.swift has them. */
object Bytes {
    private val digits = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xff
            out[2 * i] = digits[b ushr 4]
            out[2 * i + 1] = digits[b and 0x0f]
        }
        return String(out)
    }

    /** Hex of either case to bytes; null for an odd length or a character that is not hex. */
    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0 || !isHexChar(hex[2 * i]) || !isHexChar(hex[2 * i + 1])) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isHexChar(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    /** True for all zeros, looking at every byte whatever it finds. */
    fun isZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) acc = acc or b.toInt()
        return acc == 0
    }

    /** a + b with no wrap: null where a Swift UInt64-style addition would overflow a non-negative Long. */
    fun add(a: Long, b: Long): Long? {
        val r = a + b
        return if (a >= 0 && b >= 0 && r < 0) null else r
    }
}
