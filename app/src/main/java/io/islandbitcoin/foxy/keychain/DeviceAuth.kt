package io.islandbitcoin.foxy.keychain

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.UserNotAuthenticatedException
import io.islandbitcoin.foxy.debug.Log
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher

/**
 * The person, asked by Android (iOS: LocalAuthentication): the framework's
 * BiometricPrompt (no androidx.biometric, whose prompt needs a
 * FragmentActivity), and on API 28-29, where a key cannot take the screen lock
 * per use, KeyguardManager's screen-lock confirmation (ENG-649 decision 5).
 *
 * Three uses: the seed's `device` key ([open], a [SeedGate]: on API 30+ the
 * cipher is BiometricPrompt's CryptoObject, so the yes is the key's own); the
 * device owner after a seed alert's yes ([ownerApproves], M8); and the PIN
 * lock's `biometric` ([biometric]). The words asked with are always native's.
 */
class DeviceAuth(private val app: Context, private val front: () -> Activity?) : SeedGate {
    enum class Result { YES, NO, UNAVAILABLE }

    companion object {
        /** No answer in two minutes is no (SeedStore.swift `ownerAnswerWait`). */
        const val ANSWER_WAIT_MS = 120_000L
        const val CANCEL = "Cancel"
    }

    private val main = Handler(Looper.getMainLooper())
    private val keyguard: KeyguardManager? = app.getSystemService(KeyguardManager::class.java)

    /**
     * Starts the screen-lock confirmation and answers whether it was given:
     * MainActivity's launcher, registered in onCreate (a launcher cannot be
     * registered later). Null with no activity to start it from. Main thread.
     */
    @Volatile var confirmCredential: ((Intent, (Boolean) -> Unit) -> Boolean)? = null

    val passcodeSet: Boolean get() = keyguard?.isDeviceSecure ?: false

    // MARK: SeedGate (the seed executor)

    override fun open(reason: String, credential: Boolean, cipher: () -> Cipher): SeedGate.Answer {
        check(Looper.myLooper() != Looper.getMainLooper()) { "the seed's key is never asked for on the main thread" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = try {
                cipher()
            } catch (e: GeneralSecurityException) {
                Log.line("[foxy] seed: the key would not start (${e.javaClass.simpleName})")
                return SeedGate.Answer.No
            }
            return when (waitFor { done -> prompt(reason, if (credential) STRONG_OR_CREDENTIAL else STRONG, c, done) }) {
                Result.YES -> SeedGate.Answer.Opened(c)
                Result.UNAVAILABLE -> SeedGate.Answer.Unavailable
                Result.NO -> SeedGate.Answer.No
            }
        }
        // API 28-29: a time-bound key; a confirmation in the last 10 s opens it
        try {
            return SeedGate.Answer.Opened(cipher())
        } catch (e: UserNotAuthenticatedException) {
            // asked below
        } catch (e: GeneralSecurityException) {
            Log.line("[foxy] seed: the key would not start (${e.javaClass.simpleName})")
            return SeedGate.Answer.No
        }
        return when (waitFor { done -> confirm(reason, done) }) {
            Result.YES -> try {
                SeedGate.Answer.Opened(cipher())
            } catch (e: GeneralSecurityException) {
                Log.line("[foxy] seed: the key would not start after the screen lock (${e.javaClass.simpleName})")
                SeedGate.Answer.No
            }
            Result.UNAVAILABLE -> SeedGate.Answer.Unavailable
            Result.NO -> SeedGate.Answer.No
        }
    }

    /**
     * After a seed alert's yes, the device owner (SeedStore.swift
     * `deviceOwnerApproves`): a pasted lure can lead someone to tap an alert, it
     * cannot give their screen lock. A phone with no screen lock has nothing to
     * ask, and the alert's yes stands. Blocks; never on the main thread.
     */
    fun ownerApproves(reason: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "never on the main thread" }
        if (!passcodeSet) {
            Log.line("[foxy] seed change: no screen lock on this phone to ask for")
            return true
        }
        val answer = waitFor { done ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) prompt(reason, WEAK_OR_CREDENTIAL, null, done) else confirm(reason, done)
        }
        Log.line("[foxy] seed change: the screen lock ${if (answer == Result.YES) "approved" else "declined"}")
        return answer == Result.YES
    }

    // MARK: The PIN lock (main thread)

    /**
     * The PIN lock's `biometric` (FoxyBridge.swift `handleBiometric`):
     * "unavailable" when there is no biometric set up (or, with `passcode`, no
     * screen lock either), which is not the person saying no.
     */
    fun biometric(reason: String, passcode: Boolean, done: (String) -> Unit) {
        val answer: (Result) -> Unit = { done(it.name.lowercase(java.util.Locale.ROOT)) }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> prompt(reason, if (passcode) WEAK_OR_CREDENTIAL else WEAK, null, answer)
            passcode && passcodeSet -> confirm(reason, answer)
            else -> prompt(reason, WEAK, null, answer)
        }
    }

    // MARK: Asking

    /** Asks on the main thread and waits; `ask` returns how to take its prompt down, if it can be. */
    private fun waitFor(ask: (done: (Result) -> Unit) -> CancellationSignal?): Result {
        val answered = CountDownLatch(1)
        val result = AtomicReference(Result.NO)
        val cancel = AtomicReference<CancellationSignal?>(null)
        main.post {
            cancel.set(ask { r ->
                result.set(r)
                answered.countDown()
            })
        }
        if (!answered.await(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS)) {
            main.post { cancel.get()?.cancel() }
            Log.line("[foxy] no biometric or screen lock in ${ANSWER_WAIT_MS / 1000}s, taken as no")
            return Result.NO
        }
        return result.get()
    }

    private val STRONG = 1
    private val WEAK = 2
    private val STRONG_OR_CREDENTIAL = 3
    private val WEAK_OR_CREDENTIAL = 4

    /** Only asked on API 30+, where the authenticators are. */
    @android.annotation.SuppressLint("InlinedApi")
    private fun authenticators(kind: Int): Int = when (kind) {
        STRONG -> BiometricManager.Authenticators.BIOMETRIC_STRONG
        WEAK -> BiometricManager.Authenticators.BIOMETRIC_WEAK
        STRONG_OR_CREDENTIAL -> BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else -> BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    /** BiometricPrompt, on the main thread; `done` once (the error codes are plain numbers before API 29). The signal that takes it down, when it went up. */
    @android.annotation.SuppressLint("InlinedApi")
    private fun prompt(reason: String, kind: Int, crypto: Cipher?, done: (Result) -> Unit): CancellationSignal? {
        val context: Context = front() ?: app
        val credential = kind == STRONG_OR_CREDENTIAL || kind == WEAK_OR_CREDENTIAL
        var said = false
        val once: (Result) -> Unit = { r -> if (!said) { said = true; done(r) } }
        val builder = BiometricPrompt.Builder(context).setTitle(reason)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val allowed = authenticators(kind)
            val manager = app.getSystemService(BiometricManager::class.java)
            if (manager == null || manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
                once(Result.UNAVAILABLE)
                return null
            }
            builder.setAllowedAuthenticators(allowed)
        } else if (credential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        if (!credential) builder.setNegativeButton(CANCEL, { it.run() }) { _, _ -> once(Result.NO) }
        val signal = CancellationSignal()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = once(Result.YES)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                val unavailable = errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL
                once(if (unavailable) Result.UNAVAILABLE else Result.NO)
            }
            // a finger not recognised is not an answer: the prompt stays up for another try
        }
        try {
            val prompt = builder.build()
            if (crypto != null) {
                prompt.authenticate(BiometricPrompt.CryptoObject(crypto), signal, { it.run() }, callback)
            } else {
                prompt.authenticate(signal, { it.run() }, callback)
            }
        } catch (e: RuntimeException) {
            Log.line("[foxy] the biometric prompt would not open (${e.javaClass.simpleName})")
            once(Result.UNAVAILABLE)
            return null
        }
        return signal
    }

    /** KeyguardManager's screen-lock confirmation, on the main thread; `done` once. It cannot be taken down from here. */
    private fun confirm(reason: String, done: (Result) -> Unit): CancellationSignal? {
        @Suppress("DEPRECATION")
        val intent = keyguard?.createConfirmDeviceCredentialIntent(reason, null)
        val start = confirmCredential
        when {
            intent == null -> done(Result.UNAVAILABLE)
            start == null -> done(Result.NO)
            !start(intent) { yes -> done(if (yes) Result.YES else Result.NO) } -> done(Result.NO)
        }
        return null
    }
}
