package com.bloemstudios.admin.servers

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Admin-only session routing state, keyed by server identity. */
class AdminSessionViewModel : ViewModel() {
    private val _sessionExpiredReasonByServer = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionExpiredReasonByServer: StateFlow<Map<String, String>> = _sessionExpiredReasonByServer

    fun sessionExpiredReason(serverId: String): String? = _sessionExpiredReasonByServer.value[serverId]

    fun reportAdminUnauthorized(serverId: String) {
        _sessionExpiredReasonByServer.update {
            it + (serverId to "Your admin session expired. Sign in again to continue.")
        }
    }

    fun clearSessionExpired(serverId: String) {
        _sessionExpiredReasonByServer.update { it - serverId }
    }
}
