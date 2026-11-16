package com.bloemstudios.admin.serverconfig

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getEffectiveSettingsWithValidator
import com.bloemstudios.admin.network.api.getRestartKeys
import com.bloemstudios.admin.network.api.getSensitiveStatus
import com.bloemstudios.admin.network.api.getServerStatus
import com.bloemstudios.admin.network.api.updateSettings
import com.bloemstudios.admin.network.serverSentence
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class SettingsPagesState(
    val snapshot: SettingsSnapshot? = null,
    val draft: SettingsDraft = SettingsDraft(emptyMap()),
    val loadError: ErrorText? = null,
    val saving: Boolean = false,
    /** Why the last save was refused, with the edits still in place. */
    val saveError: ErrorText? = null,
    /** The server's settings changed under the edits: they were read again and the edits are kept. */
    val conflict: Boolean = false,
    /** Keys the last saves wrote that only take effect after a restart. */
    val restartKeysSaved: List<String> = emptyList(),
)

sealed interface SettingsSaveOutcome {
    data object Nothing : SettingsSaveOutcome
    data object Busy : SettingsSaveOutcome
    data class Saved(val restartNeeded: Boolean) : SettingsSaveOutcome
    data object Conflict : SettingsSaveOutcome
    data class Rejected(val error: ErrorText) : SettingsSaveOutcome
}

/**
 * Holds one server's settings for the named pages: the snapshot the edits were made on, the edits, and the save. A save
 * sends only the changed keys, on the validator of the snapshot the edits began on; a 412 reads the settings again and
 * keeps the edits.
 */
class SettingsPagesViewModel : ViewModel() {
    private val _state = MutableStateFlow(SettingsPagesState())
    val state: StateFlow<SettingsPagesState> = _state
    private val latestLoad = LatestRequestGate()

    /**
     * Reads the server's settings. While there are edits, a later read refreshes only the status and the restart marker:
     * the values and the validator stay those the edits were made on, so a save is judged against what the admin saw.
     */
    suspend fun load(client: ApiClient, replaceEdits: Boolean = false) {
        val generation = latestLoad.begin()
        try {
            val fresh = readSnapshot(client)
            if (!latestLoad.isCurrent(generation)) return
            _state.update { current ->
                val held = current.snapshot
                if (!replaceEdits && held != null && current.draft.dirtyKeys.isNotEmpty()) {
                    current.copy(snapshot = held.copy(status = fresh.status, restart = fresh.restart, sensitive = fresh.sensitive), loadError = null)
                } else {
                    current.copy(snapshot = fresh, draft = current.draft.rebase(fresh.values), loadError = null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (latestLoad.isCurrent(generation)) _state.update { it.copy(loadError = e.toErrorText(R.string.error_could_not_load_server_config)) }
        } catch (_: Exception) {
            if (latestLoad.isCurrent(generation)) _state.update { it.copy(loadError = ErrorText.Res(R.string.error_could_not_load_server_config)) }
        }
    }

    private suspend fun readSnapshot(client: ApiClient): SettingsSnapshot = coroutineScope {
        val effective = async { client.getEffectiveSettingsWithValidator() }
        // Older servers lack these two; the pages still work, without restart markers and secret status.
        val restart = async { runCatching { client.getRestartKeys() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(RestartKeys()) }
        val sensitive = async { runCatching { client.getSensitiveStatus() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(SensitiveStatus()) }
        val status = async { client.getServerStatus() }
        val (values, validator) = effective.await()
        SettingsSnapshot(
            values = values,
            // Without a validator no write can be judged, so the pages stay read-only rather than guess.
            validator = validator.orEmpty(),
            restart = restart.await(),
            sensitive = sensitive.await(),
            status = status.await(),
        )
    }

    fun edit(key: String, value: String) = _state.update { it.copy(draft = it.draft.set(key, value), saveError = null) }

    fun discard() = _state.update { it.copy(draft = it.draft.discard(), saveError = null, conflict = false) }

    fun dismissSaveError() = _state.update { it.copy(saveError = null) }

    suspend fun save(client: ApiClient, pageKeys: Set<String>): SettingsSaveOutcome {
        val current = _state.value
        val snapshot = current.snapshot ?: return SettingsSaveOutcome.Nothing
        val changes = current.draft.changes(pageKeys)
        if (changes.isEmpty()) return SettingsSaveOutcome.Nothing
        if (current.saving) return SettingsSaveOutcome.Busy
        if (snapshot.validator.isBlank()) {
            val error = ErrorText.Res(R.string.settings_page_no_validator)
            _state.update { it.copy(saveError = error) }
            return SettingsSaveOutcome.Rejected(error)
        }
        _state.update { it.copy(saving = true, saveError = null) }
        try {
            val receipt = client.updateSettings(changes, snapshot.validator)
            _state.update {
                it.copy(
                    draft = it.draft.saved(changes.keys, receipt.values),
                    conflict = false,
                    restartKeysSaved = (it.restartKeysSaved + receipt.restart_required_keys).distinct(),
                )
            }
            // A new snapshot, the new validator, and the server's own restart marker.
            load(client, replaceEdits = true)
            return SettingsSaveOutcome.Saved(receipt.restart_required)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.statusCode == 412 || e.statusCode == 428) {
                _state.update { it.copy(conflict = true) }
                load(client, replaceEdits = true)
                return SettingsSaveOutcome.Conflict
            }
            val error = if (e.code == BREAK_GLASS_REQUIRED && e.serverSentence == null) ErrorText.Res(R.string.settings_break_glass_required)
            else e.toErrorText(R.string.settings_page_save_failed)
            _state.update { it.copy(saveError = error) }
            return SettingsSaveOutcome.Rejected(error)
        } catch (_: Exception) {
            val error = ErrorText.Res(R.string.settings_page_save_failed)
            _state.update { it.copy(saveError = error) }
            return SettingsSaveOutcome.Rejected(error)
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    private companion object {
        const val BREAK_GLASS_REQUIRED = "break_glass_required"
    }
}
