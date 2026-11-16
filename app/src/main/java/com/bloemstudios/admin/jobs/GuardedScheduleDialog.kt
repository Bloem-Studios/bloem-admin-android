package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.bloemstudios.admin.ui.theme.VeilColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.getTaskSchedule
import com.bloemstudios.admin.network.api.updateTaskSchedule
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable data class TaskSchedule(val task_key: String, val triggers: List<TaskTrigger>)
@Serializable data class ScheduleUpdate(val triggers: List<TaskTrigger>)
data class ScheduleSnapshot(val schedule: TaskSchedule, val validator: String)

suspend fun readSchedule(client: ApiClient, key: String): ScheduleSnapshot {
    val (schedule, validator) = client.getTaskSchedule(key)
    require(!validator.isNullOrBlank()) { "Missing schedule validator" }
    return ScheduleSnapshot(schedule, validator)
}
suspend fun saveSchedule(client: ApiClient, original: ScheduleSnapshot, triggers: List<TaskTrigger>): UpdateTriggersResult = try {
    client.updateTaskSchedule(original.schedule.task_key, triggers, original.validator)
    UpdateTriggersResult.Success
} catch (e: CancellationException) { throw e }
catch (e: ApiError) {
    if (e.statusCode == 412 || e.statusCode == 428) UpdateTriggersResult.Conflict
    else UpdateTriggersResult.Failure(e.toErrorText(R.string.error_could_not_update_job_schedule))
} catch (_: Exception) { UpdateTriggersResult.Failure(ErrorText.Res(R.string.error_could_not_update_job_schedule)) }

@Composable
internal fun GuardedScheduleDialog(client: ApiClient, task: AdminTask, onDismiss: () -> Unit) {
    var original by remember { mutableStateOf<ScheduleSnapshot?>(null) }
    var latest by remember { mutableStateOf<ScheduleSnapshot?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    ShellInteractionLockEffect(true)
    ForegroundOnceEffect(client, task.key, retry) {
        failed = false
        try { original = readSchedule(client, task.key) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val captured = original
    if (captured == null) AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jobs_schedule_dialog_title, task.name)) },
        text = { Column { if (failed) Text(stringResource(R.string.settings_edit_unavailable)) else LinearProgressIndicator(Modifier.fillMaxWidth(), color = VeilColors.Text, trackColor = VeilColors.Card) } },
        confirmButton = { if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) else EditTriggersDialog(task.name, captured.schedule.triggers, onDismiss,
        onSave = { saveSchedule(client, original!!, it) },
        onReview = { readSchedule(client, task.key).also { latest = it }.schedule.triggers },
        onAdopt = { original = latest; latest = null }
    )
}
