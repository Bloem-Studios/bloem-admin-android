package com.bloemstudios.admin.alerts

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Device-local alert choices, all off until the admin turns them on: the master switch, which saved servers to
 * watch, and whether the lock screen may show more than the generic text. Holds no credentials.
 */
class AlertPreferences(private val preferences: SharedPreferences) {
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    private val _showDetailsOnLockScreen = MutableStateFlow(preferences.getBoolean(KEY_DETAILS, false))
    val showDetailsOnLockScreen: StateFlow<Boolean> = _showDetailsOnLockScreen

    private val _enabledServerIds = MutableStateFlow(preferences.getStringSet(KEY_SERVERS, emptySet()).orEmpty().toSet())
    /** Servers switched on in the list. They only count while [enabled] is on. */
    val enabledServerIds: StateFlow<Set<String>> = _enabledServerIds

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setShowDetailsOnLockScreen(value: Boolean) {
        preferences.edit().putBoolean(KEY_DETAILS, value).apply()
        _showDetailsOnLockScreen.value = value
    }

    fun isServerEnabled(serverId: String): Boolean = serverId in _enabledServerIds.value

    fun setServerEnabled(serverId: String, value: Boolean) {
        writeServers(if (value) _enabledServerIds.value + serverId else _enabledServerIds.value - serverId)
    }

    /** Forgets servers that are no longer saved, so a removed server stops being watched. */
    fun retainServers(savedIds: Set<String>) {
        val kept = _enabledServerIds.value.intersect(savedIds)
        if (kept != _enabledServerIds.value) writeServers(kept)
    }

    /** The saved servers that should raise alerts right now: none while the master switch is off. */
    fun alertedServerIds(savedIds: Set<String>): Set<String> =
        if (_enabled.value) _enabledServerIds.value.intersect(savedIds) else emptySet()

    private fun writeServers(ids: Set<String>) {
        preferences.edit().putStringSet(KEY_SERVERS, ids).apply()
        _enabledServerIds.value = ids
    }

    companion object {
        const val FILE = "alert_preferences"
        private const val KEY_ENABLED = "alerts_enabled"
        private const val KEY_DETAILS = "show_details_on_lock_screen"
        private const val KEY_SERVERS = "alert_server_ids"
    }
}
