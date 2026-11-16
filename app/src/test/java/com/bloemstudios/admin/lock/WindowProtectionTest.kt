package com.bloemstudios.admin.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app switcher shows a cover while the lock is on; the unlocked app in use can still be screenshotted. */
class WindowProtectionTest {
    @Test
    fun withTheLockOffNothingIsSecure() {
        assertFalse(WindowProtection.secureFlag(lockOn = false, locked = true, resumed = false))
        assertFalse(WindowProtection.secureFlag(lockOn = false, locked = false, resumed = true))
    }

    @Test
    fun aLockedAppIsSecure() {
        assertTrue(WindowProtection.secureFlag(lockOn = true, locked = true, resumed = true))
    }

    @Test
    fun anUnlockedAppIsSecureOnlyWhileItIsNotInFront() {
        // Leaving the screen: the thumbnail is taken now, before the lock engages.
        assertTrue(WindowProtection.secureFlag(lockOn = true, locked = false, resumed = false))
        // In use and unlocked: screenshots are allowed.
        assertFalse(WindowProtection.secureFlag(lockOn = true, locked = false, resumed = true))
    }
}
