package io.islandbitcoin.foxy.tap

/**
 * What tap to pay needs of the phone's Bluetooth, and nothing more: whether
 * there is an adapter, whether it can be a peripheral (the receiver) and a
 * scanner (the payer), whether Foxy may use it for each, whether it is on, and a
 * GATT server and advertiser, or a scanner and GATT client, when it is. On a
 * phone it is `AndroidTapBluetooth`; in the JVM tests a fake. Main thread only,
 * and so is every callback.
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
     * The person refused the system's question on this install, and the permissions are still not
     * granted. iOS asks only while the authorization is `.notDetermined`; once refused it reads
     * `.unauthorized` and the stage is `denied` with no dialog (TapLink.swift `cbState`). Android would
     * put its dialog up again on every TAP until its own lockout, so Foxy remembers a refusal and does
     * not ask again. Only a refusal: a question left unanswered, or a grant Android later took back
     * (Android 11+ resets an unused app's permissions; iOS never does), is asked again ([BluetoothRefusal]).
     */
    fun refused(): Boolean

    fun power(): Power

    /** Told each change of [power] until the function returned is called. */
    fun watch(onChange: (Power) -> Unit): () -> Unit

    /** The clock a link runs on: the main looper. */
    val clock: TapClock

    /** A GATT server and an advertiser whose callbacks go to [server], or null when Android would not open one. */
    fun open(server: TapGattServer): TapPeripheral?

    /**
     * The adapter can scan for receivers (`bluetoothLeScanner`). Android answers
     * false while the adapter is off, so this is asked only when [power] is ON.
     */
    val scans: Boolean

    /**
     * The payer can run on this phone at all ([BluetoothAnswer.pays]): API 31+. Below it a scan needs
     * a location permission and location services on, which the payer does not ask for, so tap to
     * pay's paying side answers `unsupported` there and nothing is asked for it.
     */
    val pays: Boolean

    /** The payer's permissions are granted ([BluetoothAnswer.scanPermissions]). */
    fun scanPermitted(): Boolean

    /** Asks for them, the system's own question; `done(granted)`. Answered at once when nothing needs asking. */
    fun askScan(done: (Boolean) -> Unit)

    /** The person refused the payer's question on this install and it is still not granted ([refused], for the scan). */
    fun scanRefused(): Boolean

    /** A scanner and GATT client whose callbacks go to [payer], or null when Android would not give one. */
    fun central(payer: TapPayer): TapCentral?

    /** A phone without Bluetooth Foxy can use: every question is answered `unsupported` (ENG-656). */
    class None(override val clock: TapClock) : TapBluetooth {
        override val adapter = false
        override val advertises = false
        override fun permitted() = false
        override fun ask(done: (Boolean) -> Unit) = done(false)
        override fun refused() = false
        override fun power() = Power.OFF
        override fun watch(onChange: (Power) -> Unit): () -> Unit = {}
        override fun open(server: TapGattServer): TapPeripheral? = null
        override val scans = false
        override val pays = false
        override fun scanPermitted() = false
        override fun askScan(done: (Boolean) -> Unit) = done(false)
        override fun scanRefused() = false
        override fun central(payer: TapPayer): TapCentral? = null
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

    /** The first API level the payer runs on (ENG-671): [pays]. */
    const val PAYER_SDK = 31

    /**
     * Whether the payer runs at this API level. From 31 the scan has a permission of its own,
     * BLUETOOTH_SCAN, declared `neverForLocation`. Before 31 Android returns no scan results
     * without a location permission (Android 9) or ACCESS_FINE_LOCATION (Android 10 and 11), and
     * none at all while location services are off (developer.android.com, "Bluetooth
     * permissions"): a location dialog the page and iOS's flow never explain, and a listen that
     * hears nothing, and says nothing, whenever location is off (ENG-658's failure). So the payer
     * is `unsupported` on API 28-30, as foxy-parity-gap.md §2.1 suggested, and Foxy never asks
     * for or declares a location permission. The receiver still runs there.
     */
    fun pays(sdk: Int): Boolean = sdk >= PAYER_SDK

    /**
     * What the payer's scan needs at this API level (ENG-671): from 31, BLUETOOTH_SCAN (declared
     * `neverForLocation`: Foxy never derives a place from what it hears) and BLUETOOTH_CONNECT,
     * the same "Nearby devices" group as the receiver's two, so one dialog answers all three.
     * Nothing before 31, where the payer does not run ([pays]).
     */
    fun scanPermissions(sdk: Int): List<String> =
        if (pays(sdk)) listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT") else emptyList()

    /**
     * What the first-install screen's APPROVE asks: the receiver's and the
     * payer's together, in the system's one question, as iOS's one Bluetooth
     * authorization covers both roles (FoxyBridge+Tap.swift `BluetoothAsk`).
     */
    fun askedAtApprove(sdk: Int): List<String> = (permissions(sdk) + scanPermissions(sdk)).distinct()
}
