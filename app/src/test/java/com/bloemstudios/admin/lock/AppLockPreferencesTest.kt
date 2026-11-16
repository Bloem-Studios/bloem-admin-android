package com.bloemstudios.admin.lock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLockPreferencesTest {
    @Test fun deviceUnlockDefaultsOffAndPersistsExplicitOptInAndOptOut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("optional_lock_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = AppLockPreferences(prefs)
        assertFalse(settings.requireDeviceUnlock.value)
        settings.setRequireDeviceUnlock(true)
        assertTrue(AppLockPreferences(prefs).requireDeviceUnlock.value)
        settings.setRequireDeviceUnlock(false)
        assertFalse(AppLockPreferences(prefs).requireDeviceUnlock.value)
    }
}
