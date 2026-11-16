package com.bloemstudios.admin.lock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure lock-state logic for the app-wide biometric gate, deliberately kept
 * free of any BiometricPrompt/Android-framework calls so it's unit
 * testable on the plain JVM (see AppLockViewModelTest) -- the real
 * BiometricPrompt UI flow can only be exercised on-device/instrumented and
 * is wired separately in AppLockScreen.
 *
 * Starts locked: the app holds admin credentials for real production media
 * servers and exposes privileged administration, so nothing is shown until a
 * biometric/device-credential check (or an explicit "not available on this
 * device" bypass -- see BiometricSupport) has succeeded at least once this
 * process lifetime. Deliberately no grace-period timer: a real process or
 * activity background transition re-locks unconditionally; configuration
 * recreation does not.
 *
 * It also decides when the system prompt opens by itself (see
 * [LockPromptTracker]): once per lock, never again after a cancel, again
 * after the app was left and come back to, and never twice across a rotation.
 */
class AppLockViewModel : ViewModel() {
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked

    /** What the lock screen says under the title: a hardware problem or the prompt's own error text. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val prompts = LockPromptTracker()

    /** Called once BiometricPrompt reports success, or the bypass fires. */
    fun onUnlocked() {
        _isLocked.value = false
        _message.value = null
        prompts.onUnlocked()
    }

    fun onProcessStopped() {
        lock(changingConfigurations = false)
    }

    fun onActivityStopped(isChangingConfigurations: Boolean) {
        lock(isChangingConfigurations)
    }

    private fun lock(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        _isLocked.value = true
        prompts.onStopped(changingConfigurations = false)
    }

    /** The lock screen is resumed and has window focus. True once per lock: open the prompt now. */
    fun takeAutoPrompt(): Boolean {
        if (!_isLocked.value || !prompts.takeAutoPrompt()) return false
        _message.value = null
        return true
    }

    /** The Unlock button. True when the prompt should open now. */
    fun takeManualPrompt(): Boolean {
        if (!_isLocked.value) return false
        prompts.takeManualPrompt()
        _message.value = null
        return true
    }

    /** The prompt ended without unlocking. [errorCode] is BiometricPrompt's. */
    fun onPromptError(errorCode: Int, errorMessage: CharSequence?) {
        prompts.onPromptEnded(cancelledBySystem = BiometricSupport.isSystemCancel(errorCode))
        _message.value = BiometricSupport.messageFor(errorCode, errorMessage)
    }

    /** The device cannot show the prompt (or can again): the lock screen says why. */
    fun setMessage(message: String?) {
        _message.value = message
    }

    /** Non-stop lifecycle events never mutate lock state. */
    fun onLifecycleEvent(event: Lifecycle.Event) {
        // Kept as a pure-event seam for non-stop lifecycle tests. STOP is
        // handled by the explicit process/activity methods above, where the
        // activity observer can distinguish a configuration recreation.
    }
}
