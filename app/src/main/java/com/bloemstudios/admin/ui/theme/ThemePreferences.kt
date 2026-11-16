package com.bloemstudios.admin.ui.theme

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device-local choice of System, Light or Dark (App settings). The default follows the system. */
class ThemePreferences(private val preferences: SharedPreferences) {
    private val _themeMode = MutableStateFlow(ThemeMode.fromStorage(preferences.getString(KEY, null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY, mode.storageValue).apply()
        _themeMode.value = mode
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}
