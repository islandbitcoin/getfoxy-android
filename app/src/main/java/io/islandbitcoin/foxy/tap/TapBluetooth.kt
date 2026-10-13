package io.islandbitcoin.foxy.tap

/**
 * What tap to pay needs of the phone's Bluetooth, and nothing more: whether
 * there is an adapter, whether it can be a peripheral, whether Foxy may use it,
 * whether it is on, and a GATT server and advertiser when it is. On a phone it
 * is `AndroidTapBluetooth`; in the JVM tests a fake. Main thread only, and so
 * is every callback.
 */
interface TapBluetooth {
    /** The adapter's power, as `BluetoothAdapter.getState` says it. */
    enum class Power { ON, OFF, TURNING }

    /** There is a Bluetooth adapter at all (`BluetoothManager.adapter`). */
    val adapter: Boolean

    /**
     * The adapter can advertise and serve as a peripheral
     * (`isMultipleAdvertisementSupported` and a `bluetoothLeAdvertiser`).
     * Android answers false while the adapter is off, so this is asked only when [power] is ON.
     */
    val advertises: Boolean

    /** BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT are granted (API 31+; earlier, the install-time BLUETOOTH and BLUETOOTH_ADMIN). */
    fun permitted(): Boolean

    /** Asks for them, the system's own question; `done(granted)`. Answered at once when nothing needs asking. */
    fun ask(done: (Boolean) -> Unit)

    /**
     * The system's question has had an answer before, on this install. iOS asks only while the
     * authorization is `.notDetermined`; once refused it reads `.unauthorized` and the stage is `denied`
     * with no dialog (TapLink.swift `cbState`). Android would put its dialog up again on every TAP until
     * its own lockout, so Foxy remembers that it asked and does not ask again.
     */
    fun asked(): Boolean

    fun power(): Power

    /** Told each change of [power] until the function returned is called. */
    fun watch(onChange: (Power) -> Unit): () -> Unit

    /** The clock a link runs on: the main looper. */
    val clock: TapClock

    /** A GATT server and an advertiser whose callbacks go to [server], or null when Android would not open one. */
    fun open(server: TapGattServer): TapPeripheral?

    /** A phone without Bluetooth Foxy can use: every question is answered `unsupported` (ENG-656). */
    class None(override val clock: TapClock) : TapBluetooth {
        override val adapter = false
        override val advertises = false
        override fun permitted() = false
        override fun ask(done: (Boolean) -> Unit) = done(false)
        override fun asked() = false
        override fun power() = Power.OFF
        override fun watch(onChange: (Power) -> Unit): () -> Unit = {}
        override fun open(server: TapGattServer): TapPeripheral? = null
    }
}

/**
 * What Bluetooth's state means to the page (FoxyBridge+Tap.swift `BluetoothAsk`,
 * TapLink.swift `cbState`), from Android's: the first-install screen's answer
 * and the receiver's stage.
 *
 * iOS reads the authorization first and the radio after. Android cannot tell
 * whether an adapter can advertise while it is off, and a phone with no adapter
 * or one that cannot advertise is never asked for a permission it could not
 * use: so no adapter is `unsupported` before anything; a permission refused is
 * `denied`; an adapter off (or turning off) is `off`; one turning on is not an
 * answer yet; and an adapter on that cannot advertise is `unsupported`.
 */
object BluetoothAnswer {
    const val ALLOWED = "allowed"
    const val DENIED = "denied"
    const val OFF = "off"
    const val UNSUPPORTED = "unsupported"

    /** No word in a minute (BluetoothAsk's own timer). */
    const val UNKNOWN = "unknown"

    /** How long the first-install question waits for a state before it says [UNKNOWN] (FoxyBridge+Tap.swift `BluetoothAsk.start`). */
    const val WAIT = 60.0

    /** Whether to put the system's question up at all: not on a phone that could not use the answer. */
    fun worthAsking(adapter: Boolean, power: TapBluetooth.Power, advertises: Boolean): Boolean =
        adapter && !(power == TapBluetooth.Power.ON && !advertises)

    /** The first-install screen's answer, or null while the radio is turning on (iOS waits through `.resetting`). */
    fun answer(adapter: Boolean, permitted: Boolean, power: TapBluetooth.Power, advertises: Boolean): String? = when {
        !adapter -> UNSUPPORTED
        power == TapBluetooth.Power.ON && !advertises -> UNSUPPORTED
        !permitted -> DENIED
        power == TapBluetooth.Power.ON -> ALLOWED
        power == TapBluetooth.Power.OFF -> OFF
        else -> null
    }

    /** The receiver's word on its radio: null when it is on and usable, else the stage to push (TapLink.swift `cbState`). */
    fun stage(adapter: Boolean, permitted: Boolean, power: TapBluetooth.Power, advertises: Boolean): TapStage? = when {
        !adapter -> TapStage.UNSUPPORTED
        power == TapBluetooth.Power.ON && !advertises -> TapStage.UNSUPPORTED
        !permitted -> TapStage.DENIED
        power != TapBluetooth.Power.ON -> TapStage.OFF
        else -> null
    }

    /** What Android asks for at this API level: the two runtime permissions from 31, nothing before (install-time). */
    fun permissions(sdk: Int): List<String> =
        if (sdk >= 31) listOf("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT") else emptyList()
}
