package com.bloemstudios.admin.backups

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.ActionRunner
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.deleteBackup
import com.bloemstudios.admin.network.api.getBackupCapabilities
import com.bloemstudios.admin.network.api.getEffectiveSettingsWithValidator
import com.bloemstudios.admin.network.api.getTask
import com.bloemstudios.admin.network.api.listBackups
import com.bloemstudios.admin.network.api.startBackup
import com.bloemstudios.admin.network.api.updateSetting
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Backups page shows for one server. [settings] and [nextRunAt] are read apart from the list, so a server
 * that will not show them still shows its backups. The next run is the backup task's own, which the server keeps pointed at
 * backup.time, because that time is on the server's clock.
 */
data class BackupsSnapshot(
    val list: BackupList,
    val configured: Boolean,
    val settings: BackupSettings?,
    val nextRunAt: String?,
)

suspend fun ApiClient.readBackups(): BackupsSnapshot {
    val capabilities = getBackupCapabilities()
    val configured = capabilities.state == "available"
    val list = listBackups()
    val settings = try { backupSettingsOf(getEffectiveSettingsWithValidator().first) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    val next = try { getTask("backup").next_run_at } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    return BackupsSnapshot(list, configured, settings, next)
}

class BackupsViewModel : ViewModel() {
    private val latestLoad = LatestRequestGate()
    private val _snapshot = MutableStateFlow<BackupsSnapshot?>(null)
    val snapshot: StateFlow<BackupsSnapshot?> = _snapshot
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val actions = ActionRunner()
    val busy = actions.busy
    val actionError = actions.error
    fun dismissActionError() = actions.dismiss()

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val snapshot = client.readBackups()
            if (!latestLoad.isCurrent(generation)) return
            _snapshot.value = snapshot
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (latestLoad.isCurrent(generation)) _error.value = e.toErrorText(R.string.backups_error_load)
        } catch (_: Exception) {
            if (latestLoad.isCurrent(generation)) _error.value = ErrorText.Res(R.string.backups_error_load)
        }
    }

    suspend fun backUpNow(client: ApiClient) {
        actions.run("start", R.string.backups_error_start) { client.startBackup() }
        load(client)
    }

    suspend fun delete(client: ApiClient, id: String) {
        actions.run("delete:$id", R.string.backups_error_delete) { client.deleteBackup(id) }
        load(client)
    }
}

// ---- Editing backup.enabled, backup.time and backup.keep ----------------------------------------------------------

/** The settings as they were when editing began, with the validator of that snapshot (the ETag of every setting). */
data class BackupSettingsEdit(val settings: BackupSettings, val validator: String)

sealed interface BackupSaveResult {
    data object Saved : BackupSaveResult
    data object Conflict : BackupSaveResult
    data class Failed(val error: ErrorText) : BackupSaveResult
}

suspend fun loadBackupSettingsForEdit(client: ApiClient): BackupSettingsEdit {
    val (values, validator) = client.getEffectiveSettingsWithValidator()
    require(!validator.isNullOrBlank()) { "The server did not provide an edit validator" }
    return BackupSettingsEdit(backupSettingsOf(values), validator)
}

/**
 * Writes the changed keys one at a time (PUT /api/v2/admin/settings/{key}, If-Match). The first uses the validator from
 * when editing began, so a change made meanwhile (412 or 428) is a conflict and nothing is written. Each write moves the
 * validator, so before the next key the page reads it again and checks the settings still read as they should after our
 * own earlier writes; if another admin changed one in between, the save stops there instead of overwriting it.
 */
suspend fun saveBackupSettings(client: ApiClient, edit: BackupSettingsEdit, draft: BackupSettingsDraft): BackupSaveResult {
    val changes = changedSettings(edit.settings, draft)
    var validator = edit.validator
    var expected = edit.settings
    var first = true
    try {
        for ((key, value) in changes) {
            if (!first) {
                val (values, fresh) = client.getEffectiveSettingsWithValidator()
                if (backupSettingsOf(values) != expected || fresh.isNullOrBlank()) return BackupSaveResult.Conflict
                validator = fresh
            }
            client.updateSetting(key, value, validator)
            expected = when (key) {
                BACKUP_ENABLED_KEY -> expected.copy(enabled = value.toBoolean())
                BACKUP_TIME_KEY -> expected.copy(time = value)
                else -> expected.copy(keep = value.toInt())
            }
            first = false
        }
        return BackupSaveResult.Saved
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        return if (e.statusCode == 412 || e.statusCode == 428) BackupSaveResult.Conflict else BackupSaveResult.Failed(e.toErrorText(R.string.backups_error_save))
    } catch (_: Exception) {
        return BackupSaveResult.Failed(ErrorText.Res(R.string.backups_error_save))
    }
}
