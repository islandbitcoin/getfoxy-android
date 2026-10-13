package io.islandbitcoin.foxy.tap

/**
 * Whether the person refused the system's Bluetooth question, as iOS reads
 * `.unauthorized` (TapLink.swift `cbState`): refused, the stage is `denied`
 * with no dialog. What is kept is the refusal, not the asking:
 *
 * - a question nobody answered (the launcher's result came back empty, the
 *   activity went away) writes nothing, so it is asked again;
 * - a grant writes no refusal, and whenever the permissions are seen granted
 *   any refusal kept from before is dropped. Android 11+ resets the runtime
 *   permissions of an app left unused for months; iOS never takes a
 *   CBManager authorization back that way, so a grant Android took away is
 *   asked afresh, as on a new install, and not read as a refusal.
 */
class BluetoothRefusal(private val store: Store, private val permitted: () -> Boolean) {
    /** Where the refusal is kept (AndroidTapBluetooth: its preferences, no backup). */
    interface Store {
        fun get(): Boolean
        fun put(refused: Boolean)
        fun clear()
    }

    /** The system's question came back: true granted, false refused, null not answered. */
    fun answered(answer: Boolean?) {
        if (answer == null) return
        // the permissions as they are now, not the launcher's map: the two are asked together
        if (permitted()) store.clear() else store.put(true)
    }

    /** Refused before and still not granted. */
    fun refused(): Boolean {
        if (permitted()) {
            seenGranted()
            return false
        }
        return store.get()
    }

    /** The permissions are granted now: a refusal kept from before no longer holds. */
    fun seenGranted() {
        if (store.get()) store.clear()
    }
}
