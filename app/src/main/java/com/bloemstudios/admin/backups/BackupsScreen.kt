package com.bloemstudios.admin.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.ConfirmActionDialog
import com.bloemstudios.admin.automation.SectionTitle
import com.bloemstudios.admin.jobs.rememberClock
import com.bloemstudios.admin.jobs.textAhead
import com.bloemstudios.admin.jobs.textBehind
import com.bloemstudios.admin.jobs.progressPercentText
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.VeilDialog
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.launch
import java.time.Instant

/** Backups: the status, Back up now, the nightly settings and the saved backups of this server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(client: ApiClient) {
    val viewModel = viewModel<BackupsViewModel>()
    val snapshot by viewModel.snapshot.collectAsState()
    val error by viewModel.error.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val pull = rememberPullToRefreshState()
    var loadedOnce by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    // Read on open, on pull, and after this page's own actions. No events channel carries backups, so a backup that is
    // running moves on the next of those.
    ForegroundEffect(client) {
        viewModel.load(client)
        loadedOnce = true
    }
    if (pull.isRefreshing) ForegroundEffect(pull) {
        if (pull.isRefreshing) try { viewModel.load(client) } finally { pull.endRefresh() }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            actionError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = viewModel::dismissActionError) }
            val current = snapshot
            ManagementState(!loadedOnce, loadedOnce && current == null, error?.resolve(), stringResource(R.string.backups_saved_none)) { pull.startRefresh() }
            if (current != null) {
                error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
                BackupsContent(
                    snapshot = current, busy = busy, busyPrefix = "",
                    onBackUpNow = { scope.launch { viewModel.backUpNow(client) } },
                    onEditSettings = { editing = true },
                    onDelete = { item -> scope.launch { viewModel.delete(client, item.id) } },
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = VeilSpacing.Medium),
                )
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
    if (editing) BackupSettingsDialog(client, onDismiss = { editing = false }, onSaved = { scope.launch { viewModel.load(client) } })
}

/**
 * One server's Backups page: [BackupStatusCard], then the saved backups. Writes go through the callbacks, so the single and
 * All servers pages share it. [busy] holds the keys of writes still running (`start`, `delete:<id>`), each prefixed with
 * [busyPrefix] in All servers.
 */
@Composable
fun BackupsContent(
    snapshot: BackupsSnapshot,
    busy: Set<String>,
    busyPrefix: String,
    onBackUpNow: () -> Unit,
    onEditSettings: () -> Unit,
    onDelete: (BackupItem) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = rememberClock(),
) {
    var confirmingStart by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BackupItem?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        BackupStatusCard(snapshot, working = "${busyPrefix}start" in busy, now, onBackUpNow = { confirmingStart = true }, onEditSettings = onEditSettings)
        SectionTitle(stringResource(R.string.backups_saved_title))
        if (snapshot.list.items.isEmpty()) Text(stringResource(R.string.backups_saved_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        else {
            if (snapshot.list.keep > 0) Text(stringResource(R.string.backups_keep_note, snapshot.list.keep), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            snapshot.list.items.forEach { item ->
                androidx.compose.runtime.key(item.id) {
                    BackupRow(item, canDelete = canDelete(item, snapshot.list) && "${busyPrefix}delete:${item.id}" !in busy, onDelete = { deleting = item })
                }
            }
        }
        Text(stringResource(R.string.backups_restore_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
    if (confirmingStart) ConfirmActionDialog(
        title = stringResource(R.string.backups_start_confirm_title), message = stringResource(R.string.backups_start_confirm_message),
        confirmLabel = stringResource(R.string.backups_back_up_now), onDismiss = { confirmingStart = false }, onConfirm = { confirmingStart = false; onBackUpNow() },
    )
    deleting?.let { item ->
        val whenText = formatDateTime(item.created_at) ?: item.created_at
        ConfirmActionDialog(
            title = stringResource(R.string.backups_delete_title),
            message = stringResource(R.string.backups_delete_message, kindLabel(item.kind).lowercase(), whenText),
            confirmLabel = stringResource(R.string.backups_delete), destructive = true,
            onDismiss = { deleting = null }, onConfirm = { deleting = null; onDelete(item) },
        )
    }
}

@Composable
private fun BackupStatusCard(snapshot: BackupsSnapshot, working: Boolean, now: Instant, onBackUpNow: () -> Unit, onEditSettings: () -> Unit) {
    val list = snapshot.list
    val block = backUpNowBlock(list, snapshot.configured)
    RowCard(Modifier.testTag("backup-status")) {
        val age = lastSuccessAge(list, now)
        val noRecent = hasWarning(list, "no_recent_backup")
        if (age != null) StatusLabel(stringResource(R.string.backups_last_success, age.textBehind()), tone = if (noRecent) StatusTone.Warning else StatusTone.Quiet)
        else StatusLabel(stringResource(R.string.backups_none_yet), tone = StatusTone.Warning)
        Text(nextBackupText(snapshot, now), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        snapshot.settings?.let {
            Text(stringResource(R.string.backups_settings_summary, it.time, it.keep), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        val run = list.last_run
        if (lastRunProblem(run)) {
            val text = when {
                run?.status == "interrupted" -> stringResource(R.string.backups_last_run_interrupted)
                !run?.error.isNullOrBlank() -> stringResource(R.string.backups_last_run_failed, run?.error.orEmpty())
                else -> stringResource(R.string.backups_last_run_failed_no_reason)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger)
        }
        list.warnings.filter { it.code != "no_recent_backup" && it.message.isNotBlank() }.forEach {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
        }
        RunningBackup(list)
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBackUpNow, enabled = block == null && !working, modifier = Modifier.testTag("backup-now")) { Text(stringResource(R.string.backups_back_up_now)) }
            OutlinedButton(onClick = onEditSettings, enabled = snapshot.settings != null, modifier = Modifier.testTag("backup-settings")) { Text(stringResource(R.string.backups_settings_title)) }
        }
        blockText(block)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
    }
}

/** A backup in progress: the tally dot, its percentage and the bar. Another operation holding the lock is a quiet note. */
@Composable
private fun RunningBackup(list: BackupList) {
    val active = list.active
    val fraction = runningBackupFraction(list)
    when {
        active != null && fraction != null -> {
            StatusLabel(
                if (active.progress > 0) stringResource(R.string.backups_running_percent, progressPercentText(active.progress)) else stringResource(R.string.backups_running),
                tone = StatusTone.Live,
            )
            val description = stringResource(R.string.backups_progress_description)
            LinearProgressIndicator(
                progress = { fraction.coerceAtLeast(0.02f) },
                modifier = Modifier.fillMaxWidth().height(VeilSpacing.Tiny).clip(RoundedCornerShape(VeilSpacing.Tiny / 2)).semantics { contentDescription = description },
                color = VeilColors.Text, trackColor = VeilColors.Raised,
            )
            active.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        }
        active != null -> StatusLabel(
            stringResource(
                when (active.operation) {
                    "restore" -> R.string.backups_busy_restore
                    "delete" -> R.string.backups_busy_delete
                    "upload" -> R.string.backups_busy_upload
                    else -> R.string.backups_busy_other
                },
            ),
        )
        list.busy -> StatusLabel(stringResource(R.string.backups_busy_other))
    }
}

@Composable
private fun nextBackupText(snapshot: BackupsSnapshot, now: Instant): String {
    if (!snapshot.list.enabled) return stringResource(R.string.backups_nightly_off)
    val at = snapshot.nextRunAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (at == null || !at.isAfter(now)) return stringResource(R.string.backups_next_unknown)
    val seconds = java.time.Duration.between(now, at).seconds
    return stringResource(R.string.backups_next_run, "${formatDateTime(snapshot.nextRunAt) ?: snapshot.nextRunAt} (${com.bloemstudios.admin.jobs.elapsedOf(seconds).textAhead()})")
}

@Composable
private fun blockText(block: BackUpBlock?): String? = when (block) {
    null -> null
    BackUpBlock.NoStorage -> stringResource(R.string.backups_block_no_storage)
    is BackUpBlock.Message -> block.text
    BackUpBlock.KeyNotConfirmed -> stringResource(R.string.backups_block_key)
    is BackUpBlock.Busy -> stringResource(R.string.backups_block_busy)
    BackUpBlock.DestinationUnavailable -> stringResource(R.string.backups_block_destination)
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "nightly" -> stringResource(R.string.backups_kind_nightly)
    "manual" -> stringResource(R.string.backups_kind_manual)
    "before-restore" -> stringResource(R.string.backups_kind_before_restore)
    "before-upgrade" -> stringResource(R.string.backups_kind_before_upgrade)
    else -> kind
}

@Composable
private fun BackupRow(item: BackupItem, canDelete: Boolean, onDelete: () -> Unit) {
    val whenText = formatDateTime(item.created_at) ?: item.created_at
    RowCard(Modifier.testTag("backup-${item.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(kindLabel(item.kind), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                Text(whenText, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted)
                StatusLabel(
                    when (item.status) {
                        "complete" -> stringResource(R.string.backups_status_complete)
                        "complete_with_warnings" -> stringResource(R.string.backups_status_warnings)
                        "in_progress" -> stringResource(R.string.backups_status_in_progress)
                        "incomplete" -> stringResource(R.string.backups_status_incomplete)
                        "unverified" -> stringResource(R.string.backups_status_unverified)
                        "unreadable" -> stringResource(R.string.backups_status_unreadable)
                        else -> item.status
                    },
                    tone = if (item.status == "complete" || item.status == "in_progress") StatusTone.Quiet else StatusTone.Warning,
                )
                Text(
                    listOfNotNull(sizeText(item.size_bytes).takeIf { item.size_bytes > 0 }, stringResource(R.string.backups_encrypted).takeIf { item.encrypted }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted,
                )
            }
            val deleteDescription = stringResource(R.string.backups_delete_description, whenText)
            TextButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.testTag("backup-${item.id}-delete").semantics { contentDescription = deleteDescription }) {
                Text(stringResource(R.string.backups_delete), color = if (canDelete) VeilColors.Danger else VeilColors.Muted)
            }
        }
    }
}

/**
 * The typed editor for backup.enabled, backup.time and backup.keep. It reads the settings and their validator when it opens;
 * saving writes only what changed. If the settings changed meanwhile it says so and offers to load the current values.
 */
@Composable
fun BackupSettingsDialog(client: ApiClient, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var edit by remember { mutableStateOf<BackupSettingsEdit?>(null) }
    var draft by remember { mutableStateOf<BackupSettingsDraft?>(null) }
    var failed by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showInvalid by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ErrorText?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    ShellInteractionLockEffect(true)
    ForegroundOnceEffect(client, reload) {
        failed = false
        try {
            val loaded = loadBackupSettingsForEdit(client)
            edit = loaded
            if (draft == null || conflict) draft = loaded.settings.toDraft()
            conflict = false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    VeilDialog(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(stringResource(R.string.backups_settings_title), style = MaterialTheme.typography.titleMedium)
            val current = draft
            val saved = edit
            when {
                failed -> Text(stringResource(R.string.backups_settings_unavailable), color = VeilColors.Danger, style = MaterialTheme.typography.bodyMedium)
                current == null || saved == null -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = VeilColors.Text, trackColor = VeilColors.Card)
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.backups_settings_enabled), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = current.enabled, enabled = !saving, onCheckedChange = { draft = current.copy(enabled = it) }, modifier = Modifier.testTag("backup-enabled"))
                    }
                    OutlinedTextField(
                        value = current.time, onValueChange = { draft = current.copy(time = it.take(5)) }, enabled = !saving, singleLine = true,
                        label = { Text(stringResource(R.string.backups_settings_time)) }, placeholder = { Text(stringResource(R.string.backups_settings_time_hint)) },
                        isError = showInvalid && !com.bloemstudios.admin.jobs.isValidTimeOfDay(current.time),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("backup-time"),
                    )
                    Text(stringResource(R.string.backups_settings_time_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    OutlinedTextField(
                        value = current.keep, onValueChange = { draft = current.copy(keep = it.filter(Char::isDigit).take(3)) }, enabled = !saving, singleLine = true,
                        label = { Text(stringResource(R.string.backups_settings_keep)) },
                        isError = showInvalid && current.keepCount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("backup-keep"),
                    )
                    if (showInvalid && !current.isValid) Text(stringResource(R.string.backups_settings_invalid), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                    if (conflict) {
                        Text(stringResource(R.string.backups_settings_conflict), color = VeilColors.Warning, style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !saving, onClick = { reload++ }) { Text(stringResource(R.string.backups_settings_load_current)) }
                    }
                    error?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Button(
                            enabled = !saving && !conflict, modifier = Modifier.testTag("backup-settings-save"),
                            onClick = {
                                if (!current.isValid) { showInvalid = true; return@Button }
                                scope.launch {
                                    saving = true; error = null
                                    when (val result = saveBackupSettings(client, saved, current)) {
                                        BackupSaveResult.Saved -> { onSaved(); onDismiss() }
                                        BackupSaveResult.Conflict -> conflict = true
                                        is BackupSaveResult.Failed -> error = result.error
                                    }
                                    saving = false
                                }
                            },
                        ) { Text(stringResource(R.string.backups_settings_save)) }
                    }
                }
            }
            if (failed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}
