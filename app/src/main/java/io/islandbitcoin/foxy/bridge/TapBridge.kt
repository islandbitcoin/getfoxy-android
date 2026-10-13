package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.debug.Log
import io.islandbitcoin.foxy.network.MintCircuit
import io.islandbitcoin.foxy.tap.BluetoothAnswer
import io.islandbitcoin.foxy.tap.ChangeHandover
import io.islandbitcoin.foxy.tap.TapBluetooth
import io.islandbitcoin.foxy.tap.TapClock
import io.islandbitcoin.foxy.tap.TapReceiver
import io.islandbitcoin.foxy.tap.TapReceiverLink
import io.islandbitcoin.foxy.tap.TapStage
import java.text.BreakIterator

/**
 * Tap to pay's receiving side and the doors around it (Foxy/Bridge/FoxyBridge+Tap.swift
 * at 8df0bb1), ENG-670:
 *
 * - `bluetoothAsk`: the first-install Bluetooth screen's APPROVE. The system's
 *   question for BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (API 31+), and the
 *   answer `allowed`, `denied`, `off`, `unsupported` or, after a minute with no
 *   state, `unknown`, pushed to `__foxyBluetoothAnswer` first and then replied.
 *   No scan, no advertisement.
 * - `tapReceiveStart` / `tapReceiveStop`: the receive screen's TAP. A receiver
 *   ([TapReceiver]) goes on the air with what the screen offers; its stages come
 *   back as `_tap({side:"receive",…})`, a payment as `_requestPaid(body, key,
 *   "tap")` answered through `inboxAnswer`, and the payer's price as `_tapQuote`.
 *   A phone without Bluetooth Foxy can use says `unsupported` (ENG-656).
 * - `tapChange`, `tapAsking`, `tapChangeDue`, `tapTerms`: M7, M9, holding the
 *   link for change, M11; `_tapChangeKeptLate`.
 * - `inboxAnswer`: the page's word on a payment it was handed. The onion inbox
 *   (ENG-672) answers through the same registry.
 * - `shakeStart` / `shakeStop`: a shake presses TAP while armed (`_tap` `shake`);
 *   and `_turned`, the phone the other way up, from [turned].
 *
 * Nothing advertises while Foxy is put away ([away], [back]): the receiver is
 * let go and, if the page still wants it, a new one goes on the air when Foxy is
 * in front again. Main thread for everything but [handle]'s first checks.
 */
class TapBridge(
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
) {
    companion object {
        val ACTIONS = setOf(
            "bluetoothAsk", "tapReceiveStart", "tapReceiveStop",
            "tapChange", "tapAsking", "tapChangeDue", "tapTerms",
            "shakeStart", "shakeStop", "inboxAnswer",
        )

        const val STILL_GOING = "That payment is still going through. A moment."
        const val NO_LINK = "That phone is no longer connected."
        const val NO_CHANGE = "There is no change to send."
        const val CHANGE_NOT_KEPT = "The payer's phone did not say it kept the change."
        const val NO_PAYMENT_WAITING = "No payment is waiting on that answer."

        /** `inboxAnswer`'s text is cut to this many characters (FoxyBridge+Delivery.swift `handleInboxAnswer`). */
        const val MOST_ANSWER_TEXT = 300

        /** The tap's answer key is dropped this long after the receiver gave up on it (FoxyBridge+Tap.swift:111-117). */
        const val ANSWER_KEPT = TapReceiverLink.RESULT_WAIT + 5

        /**
         * `window.FoxyWallet._tap({…})`, as iOS's `pushTap` builds it: `code` only
         * for four digits, `edge` only for left or right, `pct` only on sending or
         * receiving, 0 to 100.
         */
        fun tapPush(side: String, stage: TapStage, code: String?): String {
            val js = StringBuilder("{\"side\":\"").append(side).append("\",\"stage\":\"").append(stage.word).append('"')
            if (code != null && code.length == 4 && code.all { it in '0'..'9' }) js.append(",\"code\":\"").append(code).append('"')
            if (code == "left" || code == "right") js.append(",\"edge\":\"").append(code).append('"')
            if ((stage == TapStage.SENDING || stage == TapStage.RECEIVING) && code != null) {
                code.toIntOrNull()?.takeIf { it in 0..100 }?.let { js.append(",\"pct\":").append(it) }
            }
            js.append('}')
            return "window.FoxyWallet && window.FoxyWallet._tap && window.FoxyWallet._tap($js)"
        }

        fun requestPaid(body: String, key: String): String =
            "window.FoxyWallet && window.FoxyWallet._requestPaid && window.FoxyWallet._requestPaid(" +
                FoxyBridge.literal(body) + ", " + FoxyBridge.literal(key) + ", \"tap\")"

        fun turnedPush(upsideDown: Boolean): String =
            "window.FoxyWallet && window.FoxyWallet._turned && window.FoxyWallet._turned(${if (upsideDown) "true" else "false"})"

        /** The first [n] characters as a person reads them (Swift's `prefix`). */
        fun prefix(s: String, n: Int): String {
            val chars = BreakIterator.getCharacterInstance()
            chars.setText(s)
            var end = 0
            repeat(n) {
                val next = chars.next()
                if (next == BreakIterator.DONE) return s
                end = next
            }
            return s.substring(0, end)
        }
    }

    /** The answers the page owes for payments it was handed, by key (FoxyBridge.swift `inboxAnswers`). */
    private val answers = HashMap<String, (Int, String) -> Unit>()

    private var receiver: TapReceiver? = null
    private var shakeArmed = false

    /** The phone is top-down: kept so a receiver made later starts out knowing. iOS never sets it at 8df0bb1. */
    private var tapTurned = false

    /** Foxy is in front. */
    private var front = true

    /** What the page has on the air, for a receiver made again after Foxy comes back; null once it said stop. */
    private var wanted: Pair<String, Boolean>? = null

    private val bluetooth: TapBluetooth by lazy { host.bluetooth() ?: TapBluetooth.None(HostClock(host)) }

    private fun evaluate(js: String) = host.post { host.evaluate(js) }

    private fun pushTap(side: String, stage: TapStage, code: String?) = host.evaluate(tapPush(side, stage, code))

    fun handle(action: String, id: String, body: Map<String, Any?>) {
        when (action) {
            "bluetoothAsk" -> host.post { bluetoothAsk(id) }
            "tapReceiveStart" -> {
                val payload = body["payload"] as? String ?: ""
                val early = body["early"] as? Boolean ?: false
                if (payload.isNotEmpty() && !TapRules.isPayload(payload)) return resolve(id, null, TapRules.NOT_PAYABLE)
                host.post { tapReceiveStart(id, payload, early) }
            }
            "tapReceiveStop" -> host.post { tapReceiveStop(id) }
            "tapChange" -> {
                val token = body["body"] as? String ?: ""
                host.post { tapChange(id, token) }
            }
            "tapAsking" -> host.post {
                if (receiver?.sendAsking() == true) {
                    resolve(id, "ok", null)
                } else {
                    Log.line("[tap] receive: nobody to tell that a person is deciding")
                    resolve(id, "no link", null)
                }
            }
            "tapChangeDue" -> host.post {
                receiver?.expectChange()
                resolve(id, "ok", null)
            }
            "tapTerms" -> {
                val text = body["body"] as? String ?: ""
                host.post { if (receiver?.sendTerms(text) == true) resolve(id, "ok", null) else resolve(id, null, NO_LINK) }
            }
            "shakeStart" -> host.post {
                if (!shakeArmed) {
                    Log.line("[foxy] tap: shake to tap on")
                    host.shakeSense(true) { shaken() }
                }
                shakeArmed = true
                resolve(id, "ok", null)
            }
            "shakeStop" -> host.post {
                if (shakeArmed) {
                    Log.line("[foxy] tap: shake to tap off")
                    host.shakeSense(false) {}
                }
                shakeArmed = false
                resolve(id, "ok", null)
            }
            "inboxAnswer" -> inboxAnswer(id, body)
            else -> resolve(id, null, "Unknown action: $action")
        }
    }

    // MARK: the first-install question

    /**
     * Answered twice, as iOS answers it: the gate's `__foxyBluetoothAnswer`
     * first, because the gate is up before the wallet page that would otherwise
     * hear the reply, and then the action's own id.
     */
    private fun bluetoothAsk(id: String) {
        val b = bluetooth
        var done = false
        var unwatch: () -> Unit = {}
        var timer: TapClock.Timer? = null
        fun finish(state: String) {
            if (done) return
            done = true
            timer?.cancel()
            unwatch()
            Log.line("[foxy] bluetooth: asked; the answer is $state")
            host.evaluate(PlatformBridge.bluetoothAnswer(state))
            resolve(id, state, null)
        }
        // read once the question is answered, and again as the radio changes while it is turning on
        fun read() {
            val power = b.power()
            BluetoothAnswer.answer(b.adapter, b.permitted(), power, power == TapBluetooth.Power.ON && b.advertises)?.let { finish(it) }
        }
        fun follow() {
            if (done) return
            unwatch = b.watch { read() }
            read()
        }
        timer = b.clock.after(BluetoothAnswer.WAIT) { finish(BluetoothAnswer.UNKNOWN) }
        // no question on a phone that could not use the answer, nor when it is already given, nor again once
        // answered (a refusal then reads `denied` with no dialog, as iOS's `.unauthorized`)
        if (!BluetoothAnswer.worthAsking(b.adapter, b.power(), b.advertises) || b.permitted() || b.asked()) return follow()
        b.ask { follow() }
    }

    // MARK: the receive screen's TAP

    private fun tapReceiveStart(id: String, payload: String, early: Boolean) {
        val held = receiver
        if (held != null && held.takenOne) {
            if (held.busy) return resolve(id, null, STILL_GOING)
            // a payer is deciding on the offer they hold: the new one waits for the next customer
            if (held.withPayer) {
                held.early = early
                held.update(payload)
                wanted = payload to early
                return resolve(id, "ok", null)
            }
            held.stop()
            receiver = null
        }
        wanted = payload to early
        val r = receiver
        if (r != null) {
            r.early = early
            r.update(payload)
            // pressing TAP on a screen that is already on the air is a no-op
            r.arm()
            return resolve(id, "ok", null)
        }
        resolve(id, "ok", null)
        // after the reply, as iOS's peripheral manager says its state after the handler has answered. What is
        // made is what the page wants by then, not what this call carried: a second TAP queued behind this
        // post (the empty amount screen, then the invoice) would otherwise lose to the first, and iOS, which
        // makes its receiver in the same block (FoxyBridge+Tap.swift 75-144), has no such window
        host.post {
            val w = wanted
            if (receiver == null && w != null) make(w.first, w.second)
        }
    }

    private fun make(payload: String, early: Boolean) {
        val r = TapReceiver(
            payload, bluetooth,
            onEvent = { stage, code -> pushTap("receive", stage, code) },
            onPaid = { body, tell -> paid(body, tell) },
            onQuote = { text -> host.evaluate(PlatformBridge.push("_tapQuote", text)) },
        )
        r.turned = tapTurned
        r.early = early
        r.foreground(front)
        receiver = r
        r.start()
        // pressing TAP is the arming: nothing advertises on its own
        r.arm()
    }

    /**
     * The same door a payment over Tor comes through (FoxyBridge+Tap.swift
     * 77-122): an answer key, `_requestPaid(body, key, "tap")`, and the page's
     * `inboxAnswer` read back into M6's status and reason. The key is dropped a
     * little after the receiver gave up on it, so a very late answer finds
     * nothing rather than a second M6.
     */
    private fun paid(body: String, tell: (Int, String) -> Unit) {
        val key = MintCircuit.label(null)
        answers[key] = { status, why -> tell(status, why) }
        bluetooth.clock.after(ANSWER_KEPT) { answers.remove(key) }
        host.evaluate(requestPaid(body, key))
    }

    private fun tapReceiveStop(id: String) {
        wanted = null
        // held, not dropped, while a payment is crossing: the link is the only thing carrying it
        val r = receiver
        if (r != null) {
            val done = r.stop { if (receiver === r) receiver = null }
            if (done) receiver = null
        }
        resolve(id, "ok", null)
    }

    // MARK: change, M9, M11

    private fun tapChange(id: String, token: String) {
        if (token.isEmpty()) return resolve(id, null, NO_CHANGE)
        val r = receiver ?: return resolve(id, null, NO_LINK)
        val sent = r.handOverChange(token) { word ->
            when (word) {
                ChangeHandover.Word.KEPT -> resolve(id, "ok", null)
                ChangeHandover.Word.NOT_HANDED -> resolve(id, null, CHANGE_NOT_KEPT)
                ChangeHandover.Word.LATE -> {
                    Log.line("[tap] receive: the payer kept the change after this phone had stopped waiting")
                    host.evaluate(PlatformBridge.pushNoArgument("_tapChangeKeptLate"))
                }
            }
        }
        if (!sent) resolve(id, null, NO_LINK)
    }

    // MARK: inboxAnswer

    /** The page's word on a payment it was handed: {answer, status, text}, statuses 200, 409 and 422 only. */
    private fun inboxAnswer(id: String, body: Map<String, Any?>) {
        val key = body["answer"] as? String
        val asked = (body["status"] as? Number)?.toInt() ?: 422
        val text = prefix(body["text"] as? String ?: "", MOST_ANSWER_TEXT)
        host.post {
            val answer = key?.let { answers.remove(it) } ?: return@post resolve(id, null, NO_PAYMENT_WAITING)
            val status = if (asked == 200 || asked == 409 || asked == 422) asked else 422
            // the onion's JSON, {"ok":true} or {"error":text}, read back for the tap's M6: the reason alone
            answer(status, if (status == 200) "" else text)
            resolve(id, "ok", null)
        }
    }

    // MARK: shake and turned

    /** The phone was shaken. Told to the page only while armed. */
    private fun shaken() {
        if (!shakeArmed) {
            Log.line("[foxy] tap: shaken, but shake to tap is off")
            return
        }
        Log.line("[foxy] tap: shaken; pressing TAP")
        pushTap("receive", TapStage.SHAKE, null)
    }

    /** The phone is the other way up, or back. The page turns its invoice screen round on it. Main thread. */
    fun turned(upsideDown: Boolean) {
        // the flip is off at 8df0bb1, so the payer is not told to swap its mark either: tapTurned stays as it is
        host.evaluate(turnedPush(upsideDown))
    }

    // MARK: Foxy away and back, and the page gone

    /**
     * Foxy is put away: nothing on the air, and a receiver with no payer let go. Main thread.
     *
     * A receiver whose payer is linked keeps its link, off the air: a payment crossing until it is
     * answered, and a payer holding the offer (the code on both screens) until they pay or leave. iOS
     * keeps `tapReceiver` across the background: it is let go only by TAP again, by stop and by the page
     * going (FoxyBridge+Tap.swift 73, 159-161, 320-328; FoxyBridge.swift 1096). Letting it go here would
     * close the GATT server under a payer about to send, and with no other road yet (ENG-672) the sale
     * would be lost, the case [tapReceiveStart] already guards for a payer deciding.
     */
    fun away() {
        if (!front) return
        front = false
        val r = receiver ?: return
        r.foreground(false)
        if (r.busy || r.withPayer) return
        Log.line("[tap] receive: Foxy was put away; the receiver is let go")
        r.stop()
        if (receiver === r) receiver = null
    }

    /** Foxy is in front again: what the page had on the air goes back on it, as a new receiver. Main thread. */
    fun back() {
        if (front) return
        front = true
        val r = receiver
        if (r != null) {
            r.foreground(true)
            return
        }
        val w = wanted ?: return
        Log.line("[tap] receive: Foxy is back; on the air again with a new service")
        make(w.first, w.second)
    }

    /** The page went: nothing is advertised or listened for on behalf of a page that is gone (FoxyBridge+Tap.swift `stopTap`). */
    fun pageGone() {
        if (shakeArmed) host.shakeSense(false) {}
        shakeArmed = false
        wanted = null
        // not waited for: with the page gone nothing is left to write a payment down
        receiver?.stopNow()
        receiver = null
        answers.clear()
    }

    /** A [TapClock] over the page host's own looper, for a phone with no Bluetooth (the tests' FakeHost). */
    private class HostClock(private val host: PageHost) : TapClock {
        override fun now(): Double = System.nanoTime() / 1e9

        override fun after(seconds: Double, task: () -> Unit): TapClock.Timer {
            var on = true
            host.later((seconds * 1000).toLong()) { if (on) task() }
            return TapClock.Timer { on = false }
        }
    }
}
