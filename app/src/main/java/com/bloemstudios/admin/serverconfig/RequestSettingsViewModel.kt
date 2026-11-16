package com.bloemstudios.admin.serverconfig

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getRequestCapabilities
import com.bloemstudios.admin.requests.RequestCapabilities
import com.bloemstudios.admin.network.api.getRequestSettingsWithValidator
import com.bloemstudios.admin.network.api.updateRequestSettings
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** GET and PUT /api/v2/admin/request-settings. */
@Serializable
data class RequestSettings(
    val requests_enabled: Boolean,
    val global_max_requests: Int,
    val global_window_days: Int,
    val global_auto_approval_enabled: Boolean,
    val force_dual_quality: Boolean,
    /** Absent on a server that predates it: the switch is then hidden and nothing is sent. */
    val watchlist_requests: Boolean? = null,
)

/** The settings as the page edits them: numbers stay text until they are saved. */
data class RequestSettingsDraft(
    val enabled: Boolean,
    val autoApprove: Boolean,
    val maxRequests: String,
    val windowDays: String,
    val force4k: Boolean,
    val watchlist: Boolean?,
) {
    val maxValid get() = maxRequests.trim().toIntOrNull()?.let { it >= 0 } == true
    val windowValid get() = windowDays.trim().toIntOrNull()?.let { it >= 1 } == true
}

fun RequestSettings.toDraft() = RequestSettingsDraft(
    requests_enabled, global_auto_approval_enabled, global_max_requests.toString(), global_window_days.toString(),
    force_dual_quality, watchlist_requests,
)

data class LoadedRequestSettings(val settings: RequestSettings, val validator: String)

data class RequestSettingsState(
    /** The server serves guarded request settings: otherwise the page is not offered. */
    val available: Boolean = false,
    val loaded: LoadedRequestSettings? = null,
    val draft: RequestSettingsDraft = RequestSettingsDraft(true, false, "", "", false, null),
    val loadError: ErrorText? = null,
    val saving: Boolean = false,
    val saveError: ErrorText? = null,
    val conflict: Boolean = false,
) {
    private val base get() = loaded?.settings?.toDraft()
    val changeCount: Int
        get() {
            val b = base ?: return 0
            return listOf(b.enabled != draft.enabled, b.autoApprove != draft.autoApprove, b.maxRequests != draft.maxRequests,
                b.windowDays != draft.windowDays, b.force4k != draft.force4k, b.watchlist != draft.watchlist).count { it }
        }
    val canSave get() = loaded != null && changeCount > 0 && draft.maxValid && draft.windowValid && !saving
}

sealed interface RequestSettingsOutcome {
    data object Nothing : RequestSettingsOutcome
    data object Saved : RequestSettingsOutcome
    data object Conflict : RequestSettingsOutcome
    data class Rejected(val error: ErrorText) : RequestSettingsOutcome
}

class RequestSettingsViewModel : ViewModel() {
    private val _state = MutableStateFlow(RequestSettingsState())
    val state: StateFlow<RequestSettingsState> = _state

    suspend fun load(client: ApiClient, replaceEdits: Boolean = false) {
        try {
            val caps = try {
                client.getRequestCapabilities()
            } catch (e: ApiError) {
                // A server without the route cannot do this: the page is not offered, and nothing is an error.
                if (e.statusCode == 404 || e.statusCode == 501 || e.statusCode == 503) RequestCapabilities(available = false) else throw e
            }
            val available = caps.available && caps.guarded_configuration
            if (!available) {
                _state.update { it.copy(available = false, loaded = null, loadError = null) }
                return
            }
            val (settings, validator) = client.getRequestSettingsWithValidator()
            _state.update { current ->
                val fresh = LoadedRequestSettings(settings, validator.orEmpty())
                val keep = !replaceEdits && current.loaded != null && current.changeCount > 0
                if (keep) current.copy(available = true, loadError = null)
                else current.copy(
                    available = true, loaded = fresh, loadError = null,
                    // Edits survive a read made after a conflict: each stays where it still differs from the server.
                    draft = if (replaceEdits && current.loaded != null) mergeEdits(current, fresh.settings) else fresh.settings.toDraft(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(loadError = e.toErrorText(R.string.requests_settings_load_failed)) }
        } catch (_: Exception) {
            _state.update { it.copy(loadError = ErrorText.Res(R.string.requests_settings_load_failed)) }
        }
    }

    /** The user's edits over the server's new values: a field the user did not change takes the server's new value. */
    private fun mergeEdits(current: RequestSettingsState, fresh: RequestSettings): RequestSettingsDraft {
        val old = current.loaded!!.settings.toDraft()
        val now = fresh.toDraft()
        val d = current.draft
        return RequestSettingsDraft(
            enabled = if (d.enabled != old.enabled) d.enabled else now.enabled,
            autoApprove = if (d.autoApprove != old.autoApprove) d.autoApprove else now.autoApprove,
            maxRequests = if (d.maxRequests != old.maxRequests) d.maxRequests else now.maxRequests,
            windowDays = if (d.windowDays != old.windowDays) d.windowDays else now.windowDays,
            force4k = if (d.force4k != old.force4k) d.force4k else now.force4k,
            watchlist = if (d.watchlist != old.watchlist) d.watchlist else now.watchlist,
        )
    }

    fun edit(change: (RequestSettingsDraft) -> RequestSettingsDraft) = _state.update { it.copy(draft = change(it.draft), saveError = null) }

    fun discard() = _state.update { s -> s.loaded?.let { s.copy(draft = it.settings.toDraft(), conflict = false, saveError = null) } ?: s }

    suspend fun save(client: ApiClient): RequestSettingsOutcome {
        val state = _state.value
        val loaded = state.loaded ?: return RequestSettingsOutcome.Nothing
        if (!state.canSave) return RequestSettingsOutcome.Nothing
        val d = state.draft
        val body = RequestSettings(
            d.enabled, d.maxRequests.trim().toInt(), d.windowDays.trim().toInt(), d.autoApprove, d.force4k,
            d.watchlist,
        )
        _state.update { it.copy(saving = true, saveError = null) }
        try {
            client.updateRequestSettings(body, loaded.validator)
            _state.update { it.copy(conflict = false) }
            load(client, replaceEdits = true)
            return RequestSettingsOutcome.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.statusCode == 412 || e.statusCode == 428) {
                _state.update { it.copy(conflict = true) }
                load(client, replaceEdits = true)
                return RequestSettingsOutcome.Conflict
            }
            val error = e.toErrorText(R.string.requests_settings_save_failed)
            _state.update { it.copy(saveError = error) }
            return RequestSettingsOutcome.Rejected(error)
        } catch (_: Exception) {
            val error = ErrorText.Res(R.string.requests_settings_save_failed)
            _state.update { it.copy(saveError = error) }
            return RequestSettingsOutcome.Rejected(error)
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }
}
