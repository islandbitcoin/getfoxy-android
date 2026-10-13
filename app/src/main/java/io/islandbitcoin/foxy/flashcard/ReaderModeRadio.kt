package io.islandbitcoin.foxy.flashcard

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import io.islandbitcoin.foxy.debug.Log

/**
 * The phone's NFC radio for [NfcCardLink]: `NfcAdapter.enableReaderMode` on the
 * activity in front, NFC-A and NFC-B (a Foxy card is ISO 14443-4, IsoDep), the
 * NDEF check skipped (the card's own tag applet is never read here, and the
 * card answers faster without it). While reader mode is on, Android sends no
 * other app, and none of its own dispatch, a card held to the phone: the
 * card's NDEF address cannot open a browser over a payment.
 *
 * Reader mode is the resumed activity's only: Android turns it off when the
 * activity pauses. [resumed] turns it back on for a sheet still looking, so a
 * system dialog over Foxy (the screen lock's prompt) does not end a tap that is
 * waiting for its card; Foxy put away ends the session itself
 * ([NfcCardLink.putAway]).
 *
 * [off] hears reader mode turned off by [stop] (ENG-668): a card still resting on
 * the phone is then found by Android's own dispatch, and its address is the card
 * just read, not a tap (CardTapIntake). It runs once per [stop] that ended a
 * look, and never for a stop with nothing looking (ReaderModeRadioTest).
 *
 * The adapter is reached through [ReaderMode], the activity's NfcAdapter on a
 * phone ([of]) and a fake in the JVM tests.
 */
class ReaderModeRadio(
    /** The phone's reader mode, or null on a phone with no NFC. */
    private val reader: ReaderMode?,
    /** Whether the activity can still take reader mode (not finishing or destroyed). */
    private val usable: () -> Boolean,
    private val off: () -> Unit = {},
) : CardRadio {
    /** Reader mode on one activity: NfcAdapter's enableReaderMode and disableReaderMode, or a fake. */
    interface ReaderMode {
        val isEnabled: Boolean

        /** Reader mode on, each tag to [found]. Throws IllegalStateException when the activity is not in front. */
        fun enable(found: (Tag) -> Unit)

        /** Reader mode off. May throw IllegalStateException when it was already off. */
        fun disable()
    }

    /** On a phone: the activity's own adapter. */
    constructor(activity: Activity, off: () -> Unit = {}) : this(
        of(activity),
        { !activity.isFinishing && !activity.isDestroyed },
        off,
    )

    companion object {
        const val FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        /**
         * How long one command may take on the card, ms. A payment's signature
         * on a JCOP4 card takes about 1.5 s (getfoxy/card's hardware report);
         * a card that waits longer than this is taken to have gone.
         */
        const val TRANSCEIVE_MS = 10_000

        /** The activity's NfcAdapter as a [ReaderMode], or null when the phone has none. */
        fun of(activity: Activity): ReaderMode? {
            val nfc = NfcAdapter.getDefaultAdapter(activity) ?: return null
            return object : ReaderMode {
                override val isEnabled: Boolean get() = nfc.isEnabled
                override fun enable(found: (Tag) -> Unit) = nfc.enableReaderMode(activity, { found(it) }, FLAGS, null)
                override fun disable() = nfc.disableReaderMode(activity)
            }
        }
    }

    private var looking: ((CardTag) -> Unit)? = null

    override fun available(): Boolean = reader?.isEnabled == true

    override fun start(found: (CardTag) -> Unit): Boolean {
        val nfc = reader ?: return false
        if (!nfc.isEnabled || !usable()) return false
        looking = found
        return try {
            nfc.enable { tag -> looking?.invoke(IsoDepTag.of(tag)) }
            true
        } catch (e: IllegalStateException) {
            // not in front: no reader mode for an activity that is not resumed
            looking = null
            false
        }
    }

    override fun stop() {
        if (looking == null) return
        looking = null
        try {
            reader?.disable()
        } catch (e: IllegalStateException) {
            Log.line("[card] reader mode was already off")
        }
        off()
    }

    /** The activity is in front again: a sheet still looking looks again (Android ended reader mode on the pause). */
    fun resumed() {
        val found = looking ?: return
        val nfc = reader ?: return
        try {
            nfc.enable { tag -> looking?.invoke(IsoDepTag.of(tag)) }
        } catch (e: IllegalStateException) {
            Log.line("[card] reader mode would not come back on")
        }
        looking = found
    }

    /** A card in the field, through IsoDep; one that has none speaks no commands. */
    private class IsoDepTag(private val iso: IsoDep?) : CardTag {
        companion object {
            fun of(tag: Tag): CardTag = IsoDepTag(IsoDep.get(tag))
        }

        override val speaksCommands: Boolean get() = iso != null

        override fun connect() {
            val dep = iso ?: throw java.io.IOException("not a card that speaks commands")
            dep.connect()
            dep.timeout = TRANSCEIVE_MS
        }

        override fun transceive(apdu: ByteArray): ByteArray {
            val dep = iso ?: throw java.io.IOException("not a card that speaks commands")
            return dep.transceive(apdu)
        }

        override fun close() {
            try {
                iso?.close()
            } catch (e: java.io.IOException) {
                // gone already
            }
        }
    }
}
