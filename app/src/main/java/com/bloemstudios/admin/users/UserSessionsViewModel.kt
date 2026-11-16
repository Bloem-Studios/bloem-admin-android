package com.bloemstudios.admin.users

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listLoginSessions
import com.bloemstudios.admin.network.api.listUserDevices
import com.bloemstudios.admin.network.api.revokeAllLoginSessions
import com.bloemstudios.admin.network.api.revokeLoginSession
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Backs the per-user Sessions/Devices dialog opened from UsersScreen. Kept
// as its own ViewModel (not folded into UsersViewModel) because it's scoped
// to "whichever user's dialog is currently open" rather than to the whole
// Users tab -- the dialog composable constructs a fresh instance keyed on
// the target user's id (see UserSessionsDialog) rather than using the
// nav-backstack-scoped viewModel() factory UsersViewModel uses, so state
// from one user's dialog never leaks into another's.
class UserSessionsViewModel : ViewModel() {
    private val _sessions = MutableStateFlow<List<LoginSession>>(emptyList())
    val sessions: StateFlow<List<LoginSession>> = _sessions

    private val _devices = MutableStateFlow<List<UserDevice>>(emptyList())
    val devices: StateFlow<List<UserDevice>> = _devices

    private val sessionsGate = LatestRequestGate()
    private val devicesGate = LatestRequestGate()
    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading
    private val _devicesLoading = MutableStateFlow(false)
    val devicesLoading: StateFlow<Boolean> = _devicesLoading
    private val _sessionsLoaded = MutableStateFlow(false)
    val sessionsLoaded: StateFlow<Boolean> = _sessionsLoaded
    private val _devicesLoaded = MutableStateFlow(false)
    val devicesLoaded: StateFlow<Boolean> = _devicesLoaded
    private val _sessionsError = MutableStateFlow<ErrorText?>(null)
    val sessionsError: StateFlow<ErrorText?> = _sessionsError
    private val _devicesError = MutableStateFlow<ErrorText?>(null)
    val devicesError: StateFlow<ErrorText?> = _devicesError
    // Compatibility banner retains either unresolved section failure.
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    @Synchronized
    private fun publishSessionsError(error: ErrorText?) {
        _sessionsError.value = error
        _errorMessage.value = error ?: _devicesError.value
    }

    @Synchronized
    private fun publishDevicesError(error: ErrorText?) {
        _devicesError.value = error
        _errorMessage.value = _sessionsError.value ?: error
    }

    suspend fun loadSessions(userId: String, client: ApiClient) {
        val generation = sessionsGate.begin()
        _sessionsLoading.value = true
        try {
            val sessions = client.listLoginSessions(userId)
            if (!sessionsGate.isCurrent(generation)) return
            _sessions.value = sessions
            _sessionsLoaded.value = true
            publishSessionsError(null)
        } catch (e: ApiError) {
            if (sessionsGate.isCurrent(generation)) publishSessionsError(e.toErrorText(R.string.error_could_not_load_sessions))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (sessionsGate.isCurrent(generation)) publishSessionsError(ErrorText.Res(R.string.error_could_not_load_sessions))
        } finally {
            if (sessionsGate.isCurrent(generation)) _sessionsLoading.value = false
        }
    }

    suspend fun loadDevices(userId: String, client: ApiClient) {
        val generation = devicesGate.begin()
        _devicesLoading.value = true
        try {
            val devices = client.listUserDevices(userId)
            if (!devicesGate.isCurrent(generation)) return
            _devices.value = devices
            _devicesLoaded.value = true
            publishDevicesError(null)
        } catch (e: ApiError) {
            if (devicesGate.isCurrent(generation)) publishDevicesError(e.toErrorText(R.string.error_could_not_load_devices))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (devicesGate.isCurrent(generation)) publishDevicesError(ErrorText.Res(R.string.error_could_not_load_devices))
        } finally {
            if (devicesGate.isCurrent(generation)) _devicesLoading.value = false
        }
    }

    suspend fun revokeSession(userId: String, sessionId: String, client: ApiClient): RevokeResult {
        return try {
            client.revokeLoginSession(userId, sessionId)
            loadSessions(userId, client)
            RevokeResult.Success()
        } catch (e: ApiError) {
            RevokeResult.Failure(e.toErrorText(R.string.error_could_not_revoke_session))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            RevokeResult.Failure(ErrorText.Res(R.string.error_could_not_revoke_session))
        }
    }

    suspend fun revokeAllSessions(userId: String, client: ApiClient): RevokeResult {
        return try {
            val revoked = client.revokeAllLoginSessions(userId)
            loadSessions(userId, client)
            RevokeResult.Success(revoked)
        } catch (e: ApiError) {
            RevokeResult.Failure(e.toErrorText(R.string.error_could_not_revoke_session))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            RevokeResult.Failure(ErrorText.Res(R.string.error_could_not_revoke_session))
        }
    }
}
