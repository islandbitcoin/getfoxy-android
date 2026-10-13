package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.debug.Log
import java.util.UUID

/**
 * One receiver, as iOS's `TapReceiver` is one (Foxy/Bluetooth/TapLink.swift at
 * 8df0bb1): the link ([TapReceiverLink]), its GATT radio ([TapGattServer]), and
 * the phone's Bluetooth ([TapBluetooth]) underneath, which says whether there is
 * a radio to use. One payment's worth: a new service UUID each time, never armed
 * again once it has handed an offer to somebody.
 *
 * [start] is `CBPeripheralManager(delegate:…)`: the radio's state is told to the
 * link, and from the link to the page, as `peripheralManagerDidUpdateState` does
 * (`off`, `denied`, `unsupported`, or nothing and on the air once armed). Where
 * Foxy has not been given the Bluetooth permissions yet the system asks, as
 * iOS's does when the manager is made. Bluetooth turned off and on again is
 * followed: off says `off` and lets every connection go, on goes back on the air.
 */
class TapReceiver(
    payload: String,
    private val bluetooth: TapBluetooth,
    private val onEvent: (TapStage, String?) -> Unit,
    onPaid: (String, (Int, String) -> Unit) -> Unit = { _, _ -> },
    onQuote: (String) -> Unit = {},
    service: UUID = TapCrypto.newService(),
    private val diary: (String) -> Unit = Log::line,
) {
    private var unwatch: (() -> Unit)? = null
    private var started = false
    private var gone = false
    private var radioUp = false

    val server = TapGattServer(TapGatt.Layout(service), bluetooth.clock, onEvent, diary, onClosed = { letGoOfRadio() })

    val link = TapReceiverLink(payload, server, bluetooth.clock, onEvent, onPaid, onQuote, service, diary = diary)

    init {
        server.attach(link)
    }

    /** On the air from the amount screen: no payer may join yet (the link), and payers nearby are told so (the door byte). */
    var early: Boolean
        get() = link.early
        set(value) {
            link.early = value
            server.early = value
        }

    /** This phone is top-down, for the payers nearby. */
    var turned: Boolean
        get() = server.turned
        set(value) {
            server.turned = value
        }

    /** The radio's state, now and as it changes. Nothing is advertised before [arm]. */
    fun start() {
        if (started || gone) return
        started = true
        val b = bluetooth
        if (!BluetoothAnswer.worthAsking(b.adapter, b.power(), b.advertises) || b.permitted()) return follow()
        diary("[tap] receive: asking for the Bluetooth permissions")
        b.ask { granted ->
            if (gone) return@ask
            if (!granted) {
                diary("[tap] receive: the Bluetooth permissions were refused")
                return@ask link.radioState(TapStage.DENIED)
            }
            follow()
        }
    }

    private fun follow() {
        if (gone) return
        val b = bluetooth
        unwatch = b.watch { power -> state(power) }
        state(b.power())
    }

    private fun state(power: TapBluetooth.Power) {
        if (gone) return
        val b = bluetooth
        val bad = BluetoothAnswer.stage(b.adapter, b.permitted(), power, power == TapBluetooth.Power.ON && b.advertises)
        if (bad != null) {
            if (radioUp) {
                radioUp = false
                server.radioDown()
            }
            return link.radioState(bad)
        }
        if (!radioUp) {
            val p = b.open(server)
            if (p == null) {
                diary("[tap] receive: Android would not open a GATT server")
                return link.radioState(TapStage.UNSUPPORTED)
            }
            radioUp = true
            server.radioUp(p)
        }
        link.radioState(null)
    }

    private fun letGoOfRadio() {
        gone = true
        radioUp = false
        unwatch?.invoke()
        unwatch = null
    }

    /** Foxy is in front (true) or put away: off the air while away, whatever the link wants. */
    fun foreground(on: Boolean) {
        server.foreground = on
    }

    fun arm() = link.arm()

    fun update(payload: String) = link.update(payload)

    val busy: Boolean get() = link.busy
    val takenOne: Boolean get() = link.takenOne
    val withPayer: Boolean get() = link.withPayer

    var onChangeAnswered: ((Boolean) -> Unit)?
        get() = link.onChangeAnswered
        set(value) {
            link.onChangeAnswered = value
        }

    fun expectChange() = link.expectChange()
    fun sendTerms(text: String) = link.sendTerms(text)
    fun sendAsking() = link.sendAsking()
    fun sendChange(text: String) = link.sendChange(text)
    fun handOverChange(token: String, tell: (ChangeHandover.Word) -> Unit) = link.handOverChange(token, tell)

    /** Ends it now, whatever is in flight: the page is gone. */
    fun stopNow() {
        link.stopNow()
        letGoOfRadio()
    }

    /** Ends it, or answers false and waits for a payment in flight ([TapReceiverLink.stop]). */
    fun stop(whenIdle: (() -> Unit)? = null): Boolean {
        val done = link.stop(whenIdle)
        // a radio that was never asked for (or not yet answered) has nothing for the link to close
        if (done) letGoOfRadio()
        return done
    }
}
