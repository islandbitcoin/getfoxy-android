package io.islandbitcoin.foxy.keychain

/**
 * Words typed on the native restore screen, held under an opaque id
 * (Foxy/Keychain/SeedCandidates.swift). The screen (seedEnter) and adopting
 * typed words (seedAdopt) are P3, so in P1 nothing ever adds a candidate:
 * restoreSecrets with a `candidate` answers "unknown candidate", as iOS
 * answers for an id it does not hold.
 */
class SeedCandidates {
    sealed class Failure(message: String) : Exception(message) {
        class Unknown : Failure("unknown candidate")
    }

    @Suppress("UNUSED_PARAMETER")
    fun serve(candidate: String, keysetId: String, start: Long, count: Int): NUT13.Seed = throw Failure.Unknown()

    fun forgetAll() {
        // nothing is held in P1
    }

    val count: Int get() = 0
}
