package io.islandbitcoin.foxy

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * A PNG read into ARGB pixels, for ArtTest: the JVM unit tests compile against
 * android.jar, which has no javax.imageio. Reads what the app's art is (8-bit
 * RGB or RGBA, not interlaced) and refuses anything else, so a file saved
 * some other way fails the test rather than being misread.
 */
class Png private constructor(val width: Int, val height: Int, private val argb: IntArray) {
    fun getRGB(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun read(file: File): Png {
            val bytes = file.readBytes()
            require(bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "$file is not a PNG" }
            fun int(at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
                ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            var at = 8
            var width = 0
            var height = 0
            var channels = 0
            val data = ByteArrayOutputStream()
            while (at < bytes.size) {
                val length = int(at)
                val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
                val body = at + 8
                when (type) {
                    "IHDR" -> {
                        width = int(body)
                        height = int(body + 4)
                        val depth = bytes[body + 8].toInt()
                        val colour = bytes[body + 9].toInt()
                        val interlace = bytes[body + 12].toInt()
                        require(depth == 8 && interlace == 0) { "$file: depth $depth, interlace $interlace" }
                        channels = when (colour) {
                            2 -> 3
                            6 -> 4
                            else -> throw IllegalArgumentException("$file: colour type $colour")
                        }
                    }
                    "IDAT" -> data.write(bytes, body, length)
                    "IEND" -> break
                }
                at = body + length + 4
            }
            val inflater = Inflater()
            inflater.setInput(data.toByteArray())
            val stride = width * channels
            val raw = ByteArray((stride + 1) * height)
            var read = 0
            while (read < raw.size) {
                val n = inflater.inflate(raw, read, raw.size - read)
                require(n > 0 || !inflater.needsInput()) { "$file: short image data" }
                if (n == 0) break
                read += n
            }
            inflater.end()
            require(read == raw.size) { "$file: $read of ${raw.size} bytes" }
            val out = IntArray(width * height)
            val prev = IntArray(stride)
            val line = IntArray(stride)
            for (y in 0 until height) {
                val row = y * (stride + 1)
                val filter = raw[row].toInt()
                for (i in 0 until stride) {
                    val x = raw[row + 1 + i].toInt() and 0xff
                    val a = if (i >= channels) line[i - channels] else 0
                    val b = prev[i]
                    val c = if (i >= channels) prev[i - channels] else 0
                    line[i] = (x + when (filter) {
                        0 -> 0
                        1 -> a
                        2 -> b
                        3 -> (a + b) / 2
                        4 -> {
                            val p = a + b - c
                            val pa = Math.abs(p - a)
                            val pb = Math.abs(p - b)
                            val pc = Math.abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        }
                        else -> throw IllegalArgumentException("$file: filter $filter")
                    }) and 0xff
                }
                for (px in 0 until width) {
                    val i = px * channels
                    val alpha = if (channels == 4) line[i + 3] else 0xff
                    out[y * width + px] = (alpha shl 24) or (line[i] shl 16) or (line[i + 1] shl 8) or line[i + 2]
                }
                line.copyInto(prev)
            }
            return Png(width, height, out)
        }
    }
}
