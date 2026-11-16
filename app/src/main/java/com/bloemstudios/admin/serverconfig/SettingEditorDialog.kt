package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingEditorDialog(client: ApiClient, settingKey: String, onDismiss: () -> Unit, onSaved: (AdminSetting) -> Unit, onSave: suspend (SettingEditSnapshot, String) -> SettingSaveResult = { original, value -> saveSetting(client, original, value) }) {
    var original by remember(client, settingKey) { mutableStateOf<SettingEditSnapshot?>(null) }
    var latest by remember { mutableStateOf<SettingEditSnapshot?>(null) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<ErrorText?>(null) }
    var busy by remember { mutableStateOf(true) }
    var conflict by remember { mutableStateOf(false) }
    var restartNeeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    ShellInteractionLockEffect(true)

    suspend fun readCurrent(initial: Boolean) {
        busy = true
        try {
            val snapshot = loadSettingForEdit(client, settingKey)
            if (initial) { original = snapshot; draft = snapshot.setting.value } else latest = snapshot
            error = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = ErrorText.Res(R.string.settings_read_failed) }
        finally { busy = false }
    }
    ForegroundOnceEffect(client, settingKey) { readCurrent(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingKey) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (original != null) {
                    if (restartNeeded) Text(stringResource(R.string.settings_saved_restart_required))
                    else if (original!!.setting.value == "true" || original!!.setting.value == "false") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.settings_enabled))
                            Switch(checked = draft == "true", enabled = !busy, onCheckedChange = { draft = it.toString() })
                        }
                    } else OutlinedTextField(value = draft, onValueChange = { draft = it }, enabled = !busy,
                        label = { Text(stringResource(R.string.settings_value)) }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                if (original == null && !busy) TextButton(onClick = { reads.launch { readCurrent(true) } }) { Text(stringResource(R.string.action_retry)) }
                if (conflict) {
                    Text(stringResource(R.string.settings_changed_on_server), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.settings_changed_on_server_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { reads.launch { readCurrent(false) } }) { Text(stringResource(R.string.settings_review_current)) }
                    latest?.let { snapshot ->
                        Text(stringResource(R.string.settings_current_value, snapshot.setting.value))
                        TextButton(enabled = !busy, onClick = { original = snapshot; latest = null; conflict = false }) {
                            Text(stringResource(R.string.settings_adopt_revision))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (restartNeeded) TextButton(onClick = { onSaved(original!!.setting) }) { Text(stringResource(R.string.action_done)) }
            else TextButton(enabled = original != null && !busy && !conflict && draft != original?.setting?.value, onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        when (val result = onSave(original!!, draft)) {
                            SettingSaveResult.Busy -> error = ErrorText.Res(R.string.action_in_progress)
                            is SettingSaveResult.Saved ->
                                if (result.setting.restart_required) { original = SettingEditSnapshot(result.setting, original!!.validator); restartNeeded = true }
                                else onSaved(result.setting)
                            SettingSaveResult.Conflict -> { conflict = true; latest = null }
                            is SettingSaveResult.Failed -> error = result.error
                        }
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { if (!restartNeeded) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
