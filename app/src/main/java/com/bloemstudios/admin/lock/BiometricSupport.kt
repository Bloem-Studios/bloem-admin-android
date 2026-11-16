package com.bloemstudios.admin.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

object BiometricSupport {
    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * The actual security-load-bearing decision: whether AppLockScreen
     * should let the admin straight in without ever putting up a prompt.
     * This is deliberately a pure function of the BiometricManager status
     * code (not a Context-touching one) so it's unit testable -- see
     * BiometricSupportTest, which is the only thing standing between "no
     * lock screen configured" and "the sensor glitched and we quietly
     * dropped the lock on a device that DOES have a PIN/biometric set up."
     *
     * Only BIOMETRIC_ERROR_NONE_ENROLLED and BIOMETRIC_ERROR_NO_HARDWARE
     * bypass -- both mean there is no possible way for a prompt to ever
     * succeed on this device/config, so refusing to let the admin in would
     * be a permanent lockout, not a security control. Every other
     * non-success status (HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED,
     * UNSUPPORTED, STATUS_UNKNOWN, and any future code) keeps the lock
     * engaged -- those are all either transient or ambiguous, and a device
     * that reports them may well have real credentials enrolled.
     */
    fun shouldBypass(canAuthenticateResult: Int): Boolean =
        canAuthenticateResult == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            canAuthenticateResult == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    /** The prompt was closed by the system (the app went to the background, the screen turned off). */
    fun isSystemCancel(errorCode: Int): Boolean = errorCode == BiometricPrompt.ERROR_CANCELED

    /**
     * What the lock screen says after the prompt ended with [errorCode]. Null for a cancel or a
     * dismissal (the person chose that, or the system did; the Unlock button is the retry). Anything
     * else, lockout above all ("Too many attempts. Try again later."), shows the system's own text.
     */
    fun messageFor(errorCode: Int, errorMessage: CharSequence?): String? = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_TIMEOUT,
        -> null
        else -> errorMessage?.toString()?.takeIf { it.isNotBlank() }
    }

    fun canAuthenticateResult(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)

    const val AUTHENTICATORS = ALLOWED_AUTHENTICATORS
}
