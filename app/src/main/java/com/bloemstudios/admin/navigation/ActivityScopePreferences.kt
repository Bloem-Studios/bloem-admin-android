package com.bloemstudios.admin.navigation

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Device-local launch scope; credentials stay in the encrypted ServerStore. */
class ActivityScopePreferences(private val preferences: SharedPreferences) {
    private val _allServers = MutableStateFlow(preferences.getBoolean("allServersActivity", true))
    val allServers: StateFlow<Boolean> = _allServers
    private val _selectedServerId = MutableStateFlow(preferences.getString("selectedServerId", null))
    val selectedServerId: StateFlow<String?> = _selectedServerId

    fun selectAllServers() {
        preferences.edit().putBoolean("allServersActivity", true).apply()
        _allServers.value = true
    }

    fun selectServer(id: String) {
        preferences.edit().putString("selectedServerId", id).putBoolean("allServersActivity", false).apply()
        _selectedServerId.value = id
        _allServers.value = false
    }
}

fun activityStartRoute(allServers: Boolean, selectedServerId: String?, savedIds: Set<String>): String =
    if (!allServers && selectedServerId != null && selectedServerId in savedIds) {
        "adminGate/$selectedServerId"
    } else {
        "allServers"
    }
