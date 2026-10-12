package io.islandbitcoin.foxy.keychain

import javax.crypto.Cipher

/**
 * What stands between the seed's `device` key and its use: the phone's own
 * biometric or screen lock, asked by Android (SECURITY-INVARIANTS.md S3). The
 * seed's `device` key needs the person for every use on API 30+ (a per-use key:
 * the cipher is handed to BiometricPrompt as its CryptoObject) and within 10 s
 * of a screen-lock confirmation on API 28-29 (a time-bound key: the cipher is
 * made again once the person has confirmed). [DeviceAuth] is the phone's; the
 * JVM tests use [DIRECT], whose keys need nobody.
 *
 * [open] blocks until the person answers and is never called on the main
 * thread, which draws the prompt: the vault calls it on the seed executor.
 */
fun interface SeedGate {
    sealed class Answer {
        /** The person said yes: the cipher may be used, once. */
        class Opened(val cipher: Cipher) : Answer()

        /** Cancelled, failed, or no answer in time. */
        object No : Answer()

        /** Nothing to ask with: no biometric (or, with `credential`, no screen lock) set up. */
        object Unavailable : Answer()
    }

    /**
     * Ask, with `reason` (one of native's fixed wordings), for the cipher
     * [cipher] makes. `credential`: the screen lock may stand in for a
     * biometric (the vault always allows it; the PIN lock's `biometric` with
     * `passcode: false` does not). `fresh`: the person is asked now, never
     * let through on an earlier yes (on API 28-29 a confirmation in the last
     * 10 s opens the time-bound key without asking; taking the guard off must
     * not ride on it, ENG-649 plan "Moving to `none` always prompts").
     */
    fun open(reason: String, credential: Boolean, fresh: Boolean, cipher: () -> Cipher): Answer

    companion object {
        /** No one is asked: the key needs no one (the JVM tests). */
        val DIRECT = SeedGate { _, _, _, cipher -> Answer.Opened(cipher()) }
    }
}
