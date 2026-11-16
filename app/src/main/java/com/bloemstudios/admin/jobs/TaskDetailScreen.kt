package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.realtime.watch
import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.label
import com.bloemstudios.admin.ui.runStatusLabelRes
import com.bloemstudios.admin.ui.shell.LocalShellDrawerOpen
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.launch

/**
 * One task: what it does, its schedule in words with an editor, and its recent runs with status, start, duration, error
 * and failed steps. Opened from a row of Scheduled tasks; Back returns to the list. [serverName] heads the page in All
 * servers, where the client is that server's own.
 */
@Composable
internal fun TaskDetailScreen(
    client: ApiClient,
    key: String,
    onClose: () -> Unit,
    onOpenBackups: (() -> Unit)? = null,
    serverName: String? = null,
) {
    val viewModel = viewModel<TaskDetailViewModel>(key = "task:${serverName.orEmpty()}:$key")
    val task by viewModel.task.collectAsState()
    val notFound by viewModel.notFound.collectAsState()
    val error by viewModel.error.collectAsState()
    val runs by viewModel.runs.collectAsState()
    val runsError by viewModel.runsError.collectAsState()
    val hasMore by viewModel.hasMoreRuns.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    var scheduling by remember { mutableStateOf(false) }
    var confirmingStop by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val now = rememberClock()

    BackHandler(enabled = !LocalShellDrawerOpen.current, onBack = onClose)

    // Open once, then read the task again whenever the server says tasks or jobs changed, so progress moves without a timer.
    ForegroundEffect(client, key) {
        viewModel.open(client, key)
        client.live.watch(TASK_CHANNELS, readOnOpen = false) {
            val wasActive = task?.let(::inFlight) == true
            viewModel.refreshTask(client, key)
            // A run that just finished is the newest entry of the history.
            if (wasActive && task?.let(::inFlight) != true) viewModel.reloadRuns(client, key)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Small, vertical = VeilSpacing.Tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                LucideIcon(R.drawable.lucide_chevron_left, size = LucideSize.Bar, contentDescription = stringResource(R.string.jobs_page_back))
            }
            Text(
                task?.name ?: stringResource(R.string.tab_jobs),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        serverName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, modifier = Modifier.padding(horizontal = VeilSpacing.CompactHorizontal)) }

        val current = task
        when {
            notFound -> VeilStateSurface(
                kind = VeilStateSurfaceKind.Empty,
                title = stringResource(R.string.jobs_page_not_found), message = "",
                stateDescription = stringResource(R.string.jobs_page_not_found),
                modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
                action = VeilStateSurfaceAction(stringResource(R.string.jobs_page_back), onClose),
            )
            current == null -> if (error != null) VeilStateSurface(
                kind = VeilStateSurfaceKind.Failure,
                title = stringResource(R.string.management_failed_title), message = error?.resolve().orEmpty(),
                stateDescription = error?.resolve().orEmpty(),
                modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
                action = VeilStateSurfaceAction(stringResource(R.string.action_retry)) { scope.launch { viewModel.open(client, key) } },
            ) else VeilStateSurface(
                kind = VeilStateSurfaceKind.Loading, title = "", message = "",
                stateDescription = stringResource(R.string.management_loading),
                modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("task-page"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
            ) {
                item {
                    actionError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = viewModel::dismissActionError) }
                }
                item { TaskSummary(current, now, busy = false, onRun = { scope.launch { viewModel.run(client, key) } }, onStop = { confirmingStop = true }) }
                item { SchedulePanel(current, onEdit = { scheduling = true }, onOpenBackups = onOpenBackups) }
                item { Text(stringResource(R.string.jobs_page_runs), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }) }
                if (runs.isEmpty() && runsError == null) item { Text(stringResource(R.string.jobs_page_runs_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted) }
                runsError?.let { problem ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(problem.resolve(), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Danger, modifier = Modifier.weight(1f))
                            TextButton(onClick = { reads.launch { viewModel.reloadRuns(client, key) } }) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
                items(runs, key = { it.id }) { run -> RunRow(run) }
                if (hasMore) item {
                    OutlinedButton(enabled = !loadingMore, onClick = { scope.launch { loadingMore = true; try { viewModel.loadMoreRuns(client) } finally { loadingMore = false } } }) {
                        Text(stringResource(R.string.jobs_page_runs_load_older))
                    }
                }
            }
        }
    }

    if (confirmingStop) task?.let { StopTaskDialog(it.name, onConfirm = { confirmingStop = false; scope.launch { viewModel.cancel(client, key) } }, onDismiss = { confirmingStop = false }) }
    if (scheduling) task?.let { current ->
        GuardedScheduleDialog(client, current) { scheduling = false; scope.launch { viewModel.refreshTask(client, key) } }
    }
}

@Composable
private fun TaskSummary(task: AdminTask, now: java.time.Instant, busy: Boolean, onRun: () -> Unit, onStop: () -> Unit) {
    RowCard {
        if (task.description.isNotBlank()) Text(task.description, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
        Text(stringResource(R.string.jobs_page_category, categoryLabel(task.category)), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        TaskStatusLines(task, now)
        if (inFlight(task)) TaskProgress(task)
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            when {
                canRun(task) -> Button(enabled = !busy, onClick = onRun, modifier = Modifier.testTag("task-run")) { Text(stringResource(R.string.jobs_run_now)) }
                canCancel(task) -> OutlinedButton(enabled = !busy, onClick = onStop, modifier = Modifier.testTag("task-stop")) { Text(stringResource(R.string.jobs_stop)) }
            }
        }
    }
}

/** The schedule in words, and the editor for it unless the task has none or the backup setting owns it. */
@Composable
private fun SchedulePanel(task: AdminTask, onEdit: () -> Unit, onOpenBackups: (() -> Unit)?) {
    RowCard {
        Text(stringResource(R.string.jobs_page_schedule), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        when {
            task.manual_only -> Text(stringResource(R.string.jobs_page_schedule_manual), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            task.triggers.isEmpty() -> Text(stringResource(R.string.jobs_page_schedule_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            else -> {
                task.triggers.forEach { Text(it.label(), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text) }
                if (task.triggers.any { it.type == "daily" || it.type == "weekly" }) {
                    Text(stringResource(R.string.jobs_page_schedule_server_time), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
            }
        }
        if (followsBackupSetting(task)) {
            Text(stringResource(R.string.jobs_page_schedule_on_backups), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            if (onOpenBackups != null) TextButton(onClick = onOpenBackups) { Text(stringResource(R.string.jobs_page_open_backups)) }
        } else if (!task.manual_only) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("task-edit-schedule")) { Text(stringResource(R.string.jobs_page_schedule_edit)) }
        }
    }
}

@Composable
private fun RunRow(run: TaskExecution) {
    val failed = run.status == "failed"
    RowCard(Modifier.testTag("run-${run.id}")) {
        StatusLabel(codeLabel(run.status, ::runStatusLabelRes), tone = if (failed) StatusTone.Danger else StatusTone.Quiet, dot = true)
        Text(
            stringResource(R.string.jobs_page_run_started, formatDateTime(run.started_at) ?: run.started_at),
            style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted,
        )
        Text(durationText(run.duration_ms).text(), style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted)
        val steps = failedSteps(run.steps)
        if (steps.isNotEmpty()) Text(stringResource(R.string.jobs_page_run_failed_steps, steps.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger)
        run.error_message?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.jobs_page_run_error, it), style = MaterialTheme.typography.bodySmall, color = if (failed) VeilColors.Danger else VeilColors.Muted) }
    }
}
