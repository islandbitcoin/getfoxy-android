package io.islandbitcoin.foxy.scanner

/**
 * What a scanner does with each code the decoder reads (QRScannerViewController.swift
 * and LiveScanner.swift `metadataOutput`), apart from the camera so the JVM tests
 * hold it: a plain code is handed on at once; an animated one is gathered a
 * frame at a time ([AnimatedQRReader]) and handed on whole; the live pane, which
 * keeps reading the same code many times a second, hands the same text on again
 * only after [SAME_CODE_MS]. Used from one thread (the analysis executor).
 */
class ScanSession(private val clock: () -> Long, private val live: Boolean) {
    sealed class Next {
        /** Nothing to do (a repeat of the code just handed on). */
        object Nothing : Next() {
            override fun toString() = "Nothing"
        }

        /** Hand this text to the page. */
        data class Hand(val text: String) : Next()

        /** An animated code under way: this many percent of it is in. */
        data class Progress(val percent: Int) : Next()

        /** An animated code that could not be read. */
        object Rejected : Next() {
            override fun toString() = "Rejected"
        }
    }

    companion object {
        /** The live pane hands the same code on again only this long after. */
        const val SAME_CODE_MS = 3_000L
    }

    private val animated = AnimatedQRReader()
    private var lastCode = ""
    private var lastAt: Long? = null

    /** Starts over: a pane shown again, or the full-screen scanner opened. */
    fun reset() {
        animated.reset()
    }

    fun read(decoded: String): Next {
        if (decoded.isEmpty()) return Next.Nothing
        val text = when (val step = animated.receive(decoded)) {
            AnimatedQRReader.Step.NotAnimated -> decoded
            is AnimatedQRReader.Step.Done -> step.text
            is AnimatedQRReader.Step.Progress -> return Next.Progress(Math.floor(step.share * 100).toInt())
            AnimatedQRReader.Step.Rejected -> return Next.Rejected
        }
        if (!live) return Next.Hand(text)
        val now = clock()
        val at = lastAt
        if (text == lastCode && at != null && now - at < SAME_CODE_MS) return Next.Nothing
        lastCode = text
        lastAt = now
        return Next.Hand(text)
    }
}

/**
 * The zoom that puts a 25 mm code at a quarter of the frame from the closest
 * the lens focuses (QRScannerViewController.swift `focusClose`): codes are
 * usually on another phone's screen a hand's width away, nearer than some main
 * lenses focus. 1 (no zoom) when the lens already focuses close enough or the
 * camera does not say. `closestMm` from the lens's minimum focus distance,
 * `halfAngle` half the horizontal field of view in radians, `most` the
 * camera's largest zoom.
 */
fun closeFocus(closestMm: Double, halfAngle: Double, most: Double): Double {
    if (!(closestMm > 0) || !(halfAngle > 0) || !closestMm.isFinite() || !halfAngle.isFinite()) return 1.0
    val wanted = (25.0 / 0.25) / Math.tan(halfAngle)
    if (!(wanted > 0)) return 1.0
    val zoom = closestMm / wanted
    if (!(zoom > 1)) return 1.0
    return minOf(zoom, maxOf(1.0, most))
}
