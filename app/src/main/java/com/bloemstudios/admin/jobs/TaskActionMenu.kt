package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.ui.components.LucideIcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import java.time.Instant

@Composable
internal fun TaskActionMenu(
    task: AdminTask,
    busy: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onSchedule: () -> Unit,
    onHistory: () -> Unit,
) {
    var expanded by remember(task.key) { mutableStateOf(false) }
    ShellInteractionLockEffect(expanded)
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) {
            LucideIcon(R.drawable.lucide_ellipsis, contentDescription = stringResource(R.string.jobs_actions, task.name))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.jobs_open_task)) }, onClick = { expanded = false; onOpen() })
            // Run is offered for an idle task only, Stop for a running one only; a stopping task offers neither.
            if (canRun(task)) DropdownMenuItem(text = { Text(stringResource(R.string.jobs_run_now)) }, onClick = { expanded = false; onRun() })
            if (canCancel(task)) DropdownMenuItem(text = { Text(stringResource(R.string.jobs_stop)) }, onClick = { expanded = false; onStop() })
            DropdownMenuItem(text = { Text(stringResource(R.string.jobs_schedule)) }, enabled = !task.manual_only && !followsBackupSetting(task), onClick = { expanded = false; onSchedule() })
            DropdownMenuItem(text = { Text(stringResource(R.string.jobs_execution_history)) }, onClick = { expanded = false; onHistory() })
        }
    }
}

/** The backup task's time is owned by the backup.time setting; the server overwrites a trigger edited here. */
internal fun followsBackupSetting(task: AdminTask): Boolean = task.key == "backup"

/** The confirmation before a running task is stopped. Stopping keeps the work done so far, but it cannot be resumed. */
@Composable
internal fun StopTaskDialog(taskName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ShellInteractionLockEffect(true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jobs_stop_confirm_title, taskName)) },
        text = { Text(stringResource(R.string.jobs_stop_confirm_message)) },
        confirmButton = { DestructiveTextButton(onClick = onConfirm) { Text(stringResource(R.string.jobs_stop_confirm_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jobs_stop_confirm_keep)) } },
    )
}

/** The bar and the word under a running task: how far it is, or that it is stopping. */
@Composable
internal fun TaskProgress(task: AdminTask, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.jobs_progress_description, task.name)
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        // A run that has reported nothing yet still shows a sliver, as the web does, so the bar is never mistaken for absent.
        LinearProgressIndicator(
            progress = { progressFraction(task).coerceAtLeast(MIN_VISIBLE_FRACTION) },
            modifier = Modifier.fillMaxWidth().height(VeilSpacing.Tiny).clip(RoundedCornerShape(VeilSpacing.Tiny / 2)),
            color = if (isStopping(task)) VeilColors.Warning else VeilColors.Text,
            trackColor = VeilColors.Raised,
        )
    }
}

private const val MIN_VISIBLE_FRACTION = 0.02f

/** One scheduled task as a row: its name and schedule, how the last run ended, when the next one is, and its actions. */
@Composable
internal fun TaskRow(
    task: AdminTask,
    busy: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onSchedule: () -> Unit,
    onHistory: () -> Unit,
    onOpen: () -> Unit = {},
    now: Instant = rememberClock(),
) {
    var confirmingStop by remember(task.key) { mutableStateOf(false) }
    RowCard(modifier = Modifier.testTag("task-row-${task.key}"), onClick = onOpen) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(task.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TaskStatusLines(task, now)
            }
            TaskActionMenu(task, busy, onRun, onStop = { confirmingStop = true }, onOpen = onOpen, onSchedule = onSchedule, onHistory = onHistory)
        }
        if (inFlight(task)) TaskProgress(task)
    }
    if (confirmingStop) StopTaskDialog(task.name, onConfirm = { confirmingStop = false; onCancel() }, onDismiss = { confirmingStop = false })
}

/** What a task is doing or did: Running with its percentage (the tally dot), Stopping, or the last result with its duration, then the next run. */
@Composable
internal fun TaskStatusLines(task: AdminTask, now: Instant) {
    when {
        isStopping(task) -> StatusLabel(stringResource(R.string.jobs_stopping), tone = StatusTone.Warning, dot = true)
        task.state == "running" -> StatusLabel(
            if (task.progress > 0) stringResource(R.string.jobs_running_percent, progressPercentText(task.progress)) else stringResource(R.string.jobs_running),
            tone = StatusTone.Live,
        )
        else -> {
            val last = task.last_execution
            when {
                last != null -> StatusLabel(lastRunLine(last, now), tone = if (lastRunFailed(last)) StatusTone.Danger else StatusTone.Quiet, dot = true)
                task.manual_only -> StatusLabel(stringResource(R.string.jobs_manual_only))
                else -> StatusLabel(stringResource(R.string.jobs_never_run))
            }
            val words = scheduleWords(task.triggers)
            val next = nextRun(task, now)
            if (words != null || next != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (words != null) Text(words, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    when (next) {
                        NextRun.Overdue -> StatusLabel(stringResource(R.string.jobs_overdue), tone = StatusTone.Warning, dot = true)
                        is NextRun.In -> Text(stringResource(R.string.jobs_next_run, next.span.textAhead()), style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted, maxLines = 1)
                        null -> Unit
                    }
                }
            }
        }
    }
}
