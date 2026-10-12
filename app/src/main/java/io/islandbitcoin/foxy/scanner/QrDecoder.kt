package io.islandbitcoin.foxy.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

/**
 * The one QR decoder (docs/ANDROID-MAPPING.md): zxing-cpp, which reads the
 * camera's luminance plane as CameraX hands it over, rotation included. QR
 * codes only, and the text only (iOS's `stringValue`): what it says is the
 * page's to decide, as for any scan. One per scanner, used from its analysis
 * thread.
 */
class QrDecoder {
    private val reader = BarcodeReader().apply {
        options.formats = setOf(BarcodeReader.Format.QR_CODE)
        options.tryHarder = true
        options.tryRotate = true
        options.tryInvert = true
    }

    /** The first QR code's text in a camera frame, or null. */
    fun decode(image: ImageProxy): String? = first(reader.read(image))

    /** The first QR code's text in a picture (the instrumented tests' fixtures), or null. */
    fun decode(bitmap: Bitmap, rotation: Int = 0): String? = first(reader.read(bitmap, Rect(0, 0, bitmap.width, bitmap.height), rotation))

    private fun first(results: List<BarcodeReader.Result>): String? =
        results.firstNotNullOfOrNull { r -> r.text?.takeIf { it.isNotEmpty() && r.error == null } }
}
