package com.bloemstudios.admin.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.*
import org.junit.Test

// Covers BiometricSupport.shouldBypass -- the actual security-load-bearing
// decision in the whole app-lock feature (whether AppLockScreen lets the
// admin straight in without ever putting up a BiometricPrompt). Every
// BiometricManager.canAuthenticate() status code that matters is exercised
// explicitly rather than relying on "anything not SUCCESS" logic, which is
// exactly how the original implementation shipped fail-open on statuses
// like HW_UNAVAILABLE that should keep the lock engaged.
class BiometricSupportTest {
    @Test
    fun `BIOMETRIC_SUCCESS does not bypass`() {
        assertFalse(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `BIOMETRIC_ERROR_NONE_ENROLLED bypasses`() {
        assertTrue(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `BIOMETRIC_ERROR_NO_HARDWARE bypasses`() {
        assertTrue(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun `BIOMETRIC_ERROR_HW_UNAVAILABLE does NOT bypass -- keeps the lock engaged`() {
        assertFalse(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED does NOT bypass -- keeps the lock engaged`() {
        assertFalse(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    }

    @Test
    fun `BIOMETRIC_ERROR_UNSUPPORTED does NOT bypass -- keeps the lock engaged`() {
        assertFalse(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    }

    @Test
    fun `BIOMETRIC_STATUS_UNKNOWN does NOT bypass -- keeps the lock engaged`() {
        assertFalse(BiometricSupport.shouldBypass(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
    }

    @Test
    fun `a cancel, a dismissal and a timeout say nothing`() {
        listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_TIMEOUT,
        ).forEach { assertNull(BiometricSupport.messageFor(it, "Canceled")) }
    }

    @Test
    fun `a lockout shows the system's own text`() {
        assertEquals(
            "Too many attempts.",
            BiometricSupport.messageFor(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts."),
        )
        assertEquals(
            "Locked out.",
            BiometricSupport.messageFor(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, "Locked out."),
        )
    }

    @Test
    fun `an error with no text says nothing`() {
        assertNull(BiometricSupport.messageFor(BiometricPrompt.ERROR_LOCKOUT, " "))
        assertNull(BiometricSupport.messageFor(BiometricPrompt.ERROR_LOCKOUT, null))
    }

    @Test
    fun `only a system cancel counts as the system closing the prompt`() {
        assertTrue(BiometricSupport.isSystemCancel(BiometricPrompt.ERROR_CANCELED))
        assertFalse(BiometricSupport.isSystemCancel(BiometricPrompt.ERROR_USER_CANCELED))
        assertFalse(BiometricSupport.isSystemCancel(BiometricPrompt.ERROR_LOCKOUT))
    }
}
