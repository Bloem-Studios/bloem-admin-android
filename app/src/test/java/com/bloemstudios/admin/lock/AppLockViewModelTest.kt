package com.bloemstudios.admin.lock

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Test

// Covers only the pure lock-state transition logic. The real BiometricPrompt
// UI flow (BiometricManager availability, the actual prompt, callback
// wiring in AppLockScreen) needs real hardware/an instrumented test and is
// NOT covered here.
class AppLockViewModelTest {
    @Test
    fun `starts locked`() {
        val viewModel = AppLockViewModel()

        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `onUnlocked clears the lock`() {
        val viewModel = AppLockViewModel()

        viewModel.onUnlocked()

        assertFalse(viewModel.isLocked.value)
    }

    @Test
    fun `ON_STOP re-locks even after a successful unlock`() {
        val viewModel = AppLockViewModel()
        viewModel.onUnlocked()

        viewModel.onProcessStopped()

        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `configuration activity stop does not relock`() {
        val viewModel = AppLockViewModel()
        viewModel.onUnlocked()

        viewModel.onActivityStopped(isChangingConfigurations = true)

        assertFalse(viewModel.isLocked.value)
    }

    @Test
    fun `ordinary activity stop relocks`() {
        val viewModel = AppLockViewModel()
        viewModel.onUnlocked()

        viewModel.onActivityStopped(isChangingConfigurations = false)

        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `other lifecycle events do not change lock state`() {
        val viewModel = AppLockViewModel()
        viewModel.onUnlocked()

        viewModel.onLifecycleEvent(Lifecycle.Event.ON_START)
        viewModel.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        viewModel.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        viewModel.onLifecycleEvent(Lifecycle.Event.ON_CREATE)

        assertFalse(viewModel.isLocked.value)
    }

    @Test
    fun `still locked before any unlock even across non-STOP events`() {
        val viewModel = AppLockViewModel()

        viewModel.onLifecycleEvent(Lifecycle.Event.ON_START)

        assertTrue(viewModel.isLocked.value)
    }

    // The prompt opens by itself, once per lock

    @Test
    fun `a fresh lock takes the automatic prompt once`() {
        val viewModel = AppLockViewModel()

        assertTrue(viewModel.takeAutoPrompt())
        assertFalse(viewModel.takeAutoPrompt())
    }

    @Test
    fun `no automatic prompt after a cancel`() {
        val viewModel = AppLockViewModel()
        assertTrue(viewModel.takeAutoPrompt())

        viewModel.onPromptError(BiometricPrompt.ERROR_USER_CANCELED, "Canceled")

        assertFalse(viewModel.takeAutoPrompt())
        assertTrue(viewModel.isLocked.value)
        assertNull(viewModel.message.value)
    }

    @Test
    fun `the Unlock button retries after a cancel`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()
        viewModel.onPromptError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Cancel")

        assertTrue(viewModel.takeManualPrompt())
    }

    @Test
    fun `the automatic prompt opens again after returning from the background`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()
        viewModel.onPromptError(BiometricPrompt.ERROR_USER_CANCELED, "Canceled")

        viewModel.onActivityStopped(isChangingConfigurations = false)

        assertTrue(viewModel.takeAutoPrompt())
    }

    @Test
    fun `the automatic prompt opens again after an unlock and a later background`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()
        viewModel.onUnlocked()
        assertFalse(viewModel.takeAutoPrompt())

        viewModel.onProcessStopped()

        assertTrue(viewModel.takeAutoPrompt())
    }

    @Test
    fun `the process and activity stops of one trip away give one prompt`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()
        viewModel.onUnlocked()

        viewModel.onActivityStopped(isChangingConfigurations = false)
        viewModel.onProcessStopped()

        assertTrue(viewModel.takeAutoPrompt())
        assertFalse(viewModel.takeAutoPrompt())
    }

    @Test
    fun `no second prompt when the activity is recreated while the lock screen is up`() {
        val viewModel = AppLockViewModel()
        assertTrue(viewModel.takeAutoPrompt())

        // Rotation or a fold: the stop is a configuration change, and the ViewModel is the same one.
        viewModel.onActivityStopped(isChangingConfigurations = true)

        assertFalse(viewModel.takeAutoPrompt())
        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `no second prompt when recreated after a cancel`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()
        viewModel.onPromptError(BiometricPrompt.ERROR_USER_CANCELED, "Canceled")

        viewModel.onActivityStopped(isChangingConfigurations = true)

        assertFalse(viewModel.takeAutoPrompt())
    }

    @Test
    fun `two lock screens on one ViewModel take one prompt between them`() {
        val viewModel = AppLockViewModel()

        val first = viewModel.takeAutoPrompt()
        val second = viewModel.takeAutoPrompt()

        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `an unlocked app takes no prompt`() {
        val viewModel = AppLockViewModel()
        viewModel.onUnlocked()

        assertFalse(viewModel.takeAutoPrompt())
        assertFalse(viewModel.takeManualPrompt())
    }

    @Test
    fun `a lockout shows its message and does not reopen the prompt`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()

        viewModel.onPromptError(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts. Try again later.")

        assertEquals("Too many attempts. Try again later.", viewModel.message.value)
        assertFalse(viewModel.takeAutoPrompt())
        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `the message goes when the prompt is tried again`() {
        val viewModel = AppLockViewModel()
        viewModel.takeAutoPrompt()
        viewModel.onPromptError(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts. Try again later.")

        assertTrue(viewModel.takeManualPrompt())

        assertNull(viewModel.message.value)
    }

    @Test
    fun `unlocking clears the message`() {
        val viewModel = AppLockViewModel()
        viewModel.setMessage("Something")

        viewModel.onUnlocked()

        assertNull(viewModel.message.value)
    }
}
