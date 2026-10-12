package io.islandbitcoin.foxy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.islandbitcoin.foxy.bridge.Json
import io.islandbitcoin.foxy.scanner.QrDecoder
import io.islandbitcoin.foxy.scanner.ScanSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner's decode path on the phone, without pointing the camera: QR
 * codes drawn here (zxing's Java encoder, test-only) as a phone screen shows
 * them, then read back through zxing-cpp, the decoder the camera frames go to,
 * through the APK's own native library. A cashu token, a lightning invoice,
 * and every frame of the iOS animated-QR fixture (made by the library cashu.me
 * draws its animated codes with), gathered by [ScanSession] into the token.
 * The physical scan with the camera is the person's (docs/DEVICE-TESTS.md).
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    /** `text` as a QR code on a light screen, `scale` pixels a module, with a margin, as a photo of a screen would hold it. */
    private fun draw(text: String, scale: Int = 6, rotate: Float = 0f): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val size = matrix.width * scale
        val code = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(code)
        canvas.drawColor(Color.rgb(235, 235, 230))
        val paint = Paint().apply { color = Color.rgb(20, 20, 24) }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) canvas.drawRect((x * scale).toFloat(), (y * scale).toFloat(), ((x + 1) * scale).toFloat(), ((y + 1) * scale).toFloat(), paint)
        }
        if (rotate == 0f) return code
        val turned = Bitmap.createBitmap(code, 0, 0, size, size, Matrix().apply { postRotate(rotate) }, true)
        // a larger frame around it, as the camera sees more than the code
        val frame = Bitmap.createBitmap(turned.width * 2, turned.height * 2, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(90, 90, 96))
            drawBitmap(turned, turned.width / 2f, turned.height / 2f, null)
        }
        return frame
    }

    @Test
    fun aTokenAndAnInvoiceReadBack() {
        val decoder = QrDecoder()
        val token = "cashuBo2FteBtodHRwczovL3Rlc3RudXQuY2FzaHUuc3BhY2VhdWNzYXRhdIGiYWlIAJofKTJT5B5hcIOkYWEQYXN4QDEzYjM"
        val invoice = "LNBC80N1PVJLUEZPP5QQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQYPQDQ5XYSXXATSYP3K7ENXV4JSXQZPU"
        assertEquals(token, decoder.decode(draw(token)))
        assertEquals(invoice, decoder.decode(draw(invoice, scale = 4)))
        assertEquals("held at an angle, in a larger frame", token, decoder.decode(draw(token, rotate = 17f)))
        assertEquals("upside down", invoice, decoder.decode(draw(invoice, rotate = 180f)))
        assertNull("a blank screen reads nothing", decoder.decode(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun theIosAnimatedFramesReadBackIntoTheirTokens() {
        val fixture = context.assets.open("animated-qr.json").use { it.readBytes().toString(Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        val cases = (Json.parse(fixture) as Map<String, Any?>)["cases"] as List<Map<String, Any?>>
        val decoder = QrDecoder()
        for (case in cases.filter { (it["seqLength"] as Double) <= 21 }) {
            val token = case["token"] as String
            @Suppress("UNCHECKED_CAST")
            val frames = case["frames"] as List<String>
            val session = ScanSession({ 0L }, live = false)
            var got: String? = null
            for (frame in frames) {
                val read = decoder.decode(draw(frame, scale = 4))
                assertEquals("${case["name"]}: the frame reads back as drawn", frame, read?.lowercase())
                val next = session.read(read!!)
                if (next is ScanSession.Next.Hand) {
                    got = next.text
                    break
                }
            }
            assertEquals(case["name"] as String, token, got)
        }
    }
}
