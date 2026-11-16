package com.bloemstudios.admin.lock

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device-local preference; server credentials remain in their encrypted store. */
class AppLockPreferences(private val preferences: SharedPreferences) {
    private val _requireDeviceUnlock = MutableStateFlow(preferences.getBoolean("require_device_unlock", false))
    val requireDeviceUnlock: StateFlow<Boolean> = _requireDeviceUnlock

    fun setRequireDeviceUnlock(enabled: Boolean) {
        preferences.edit().putBoolean("require_device_unlock", enabled).apply()
        _requireDeviceUnlock.value = enabled
    }
}
