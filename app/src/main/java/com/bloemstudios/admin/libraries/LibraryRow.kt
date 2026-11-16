package com.bloemstudios.admin.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.dashboard.formatRelativeTime
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.libraryTypeLabelRes
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

/** What a person can do to one library. Each is already bound to that library and that server. */
internal class LibraryRowActions(
    val onScan: () -> Unit,
    val onStop: () -> Unit,
    val onCheckMount: () -> Unit,
    val onRefreshMetadata: () -> Unit,
    val onCancelRefresh: (LibraryJob) -> Unit,
)

/** One library: its facts, any scan warning, the scan and refresh under way, the last mount check, and the actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryRow(
    library: AdminLibrary,
    scanStatus: LibraryScanStatus?,
    scanHistoryAvailable: Boolean,
    mount: MountOutcome?,
    refreshJob: LibraryJob?,
    scanControls: Boolean,
    busy: Set<String>,
    actions: LibraryRowActions,
    modifier: Modifier = Modifier,
) {
    RowCard(modifier.testTag("library-${library.id}")) {
        Text(library.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
        Text(
            stringResource(
                R.string.libraries_row_summary,
                codeLabel(library.type, ::libraryTypeLabelRes),
                pluralStringResource(R.plurals.libraries_paths, library.paths.size, library.paths.size),
                stringResource(if (library.enabled) R.string.libraries_enabled else R.string.libraries_disabled),
            ),
            style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
        )
        Text(
            library.last_scanned_at?.let { stringResource(R.string.libraries_last_scanned, formatRelativeTime(it)) }
                ?: stringResource(R.string.libraries_never_scanned),
            style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
        )
        val warning = scanWarning(library)
        // A failed scan and the warning it raised carry the same server message; it is said once, under the warning,
        // where the hint for what to do about it is.
        if (scanHistoryAvailable) scanStatus?.let { ScanStatusLines(it, alreadyShown = warning?.message) }
        warning?.let { ScanWarningBlock(it) }
        mount?.let { MountBlock(it) }
        refreshJob?.let { RefreshBlock(library, it, busy, actions.onCancelRefresh) }

        val scanning = scanStatus?.state == ScanState.Queued || scanStatus?.state == ScanState.Running
        val refreshing = refreshJob != null && !refreshJob.terminal
        FlowRow(
            Modifier.fillMaxWidth().padding(top = VeilSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            if (scanControls) {
                val scanLabel = stringResource(R.string.libraries_action_scan_for, library.name)
                OutlinedButton(
                    onClick = actions.onScan, enabled = !scanning && "scan:${library.id}" !in busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = scanLabel },
                ) { Text(stringResource(R.string.libraries_scan)) }
            }
            if (scanControls && scanning) {
                val stopLabel = stringResource(R.string.libraries_action_stop_for, library.name)
                OutlinedButton(
                    onClick = actions.onStop, enabled = "stop:${library.id}" !in busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = stopLabel },
                ) { Text(stringResource(R.string.libraries_stop)) }
            }
            val mountLabel = stringResource(R.string.libraries_action_mount_for, library.name)
            OutlinedButton(
                onClick = actions.onCheckMount, enabled = "mount:${library.id}" !in busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = mountLabel },
            ) { Text(stringResource(R.string.libraries_check_mount)) }
            val refreshLabel = stringResource(R.string.libraries_action_refresh_for, library.name)
            OutlinedButton(
                onClick = actions.onRefreshMetadata, enabled = !refreshing && "refresh:${library.id}" !in busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = refreshLabel },
            ) { Text(stringResource(R.string.libraries_refresh_metadata)) }
        }
    }
}

@Composable
private fun ScanStatusLines(status: LibraryScanStatus, alreadyShown: String?) {
    Column(Modifier.testTag("scan-status"), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        // "Scan: Failed", not a bare "Failed": the card also carries a mount check and a refresh.
        StatusLabel(
            if (status.state == ScanState.Skipped) stringResource(R.string.libraries_scan_skipped)
            else stringResource(R.string.libraries_scan_state, stringResource(status.state.labelRes())),
            tone = status.state.tone(),
        )
        // No early `return` here: leaving a composable lambda halfway unbalances the composer and crashed the page
        // the moment a scan was started (a run that has not been recorded yet).
        status.run?.let { run ->
            when (status.state) {
                ScanState.Completed -> {
                    run.result?.let { Text(scanCountsText(it), style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted) }
                    scanDuration(run)?.let { Text(stringResource(R.string.libraries_scan_took, scanDurationText(it)), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
                }
                ScanState.Failed -> run.error_message?.takeIf { it.isNotBlank() && it.trim() != alreadyShown?.trim() }
                    ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger) }
                else -> Unit
            }
        }
    }
}

@Composable
internal fun scanCountsText(result: ScanResult): String =
    stringResource(R.string.libraries_scan_counts, result.new, result.updated, result.missing, result.errors)

@Composable
private fun ScanWarningBlock(warning: ScanWarning) {
    Column(Modifier.testTag("scan-warning"), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        StatusLabel(
            when (warning.kind) {
                ScanWarningKind.EmptyRoot -> stringResource(R.string.libraries_warning_empty_root)
                ScanWarningKind.DeadRoot -> stringResource(R.string.libraries_warning_dead_root)
                ScanWarningKind.PartialWalk -> stringResource(R.string.libraries_warning_partial_walk)
                ScanWarningKind.Other -> warning.code
            },
            tone = StatusTone.Warning, dot = true,
        )
        // The server's own message first; the page's words only when it sent none.
        val message = warning.message ?: when (warning.kind) {
            ScanWarningKind.EmptyRoot -> stringResource(R.string.libraries_warning_empty_root_message)
            ScanWarningKind.DeadRoot -> stringResource(R.string.libraries_warning_dead_root_message)
            ScanWarningKind.PartialWalk -> stringResource(R.string.libraries_warning_partial_walk_message)
            ScanWarningKind.Other -> null
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning) }
        if (warning.clearedByMountCheck) {
            Text(stringResource(R.string.libraries_warning_hint_mount), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        warning.raisedAt?.let {
            Text(stringResource(R.string.libraries_warning_raised, formatRelativeTime(it)), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
    }
}

@Composable
private fun MountBlock(outcome: MountOutcome) {
    Column(Modifier.testTag("mount-check"), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        when (outcome) {
            is MountOutcome.Failed ->
                Text(outcome.error.resolve(), style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger)
            is MountOutcome.Checked -> {
                val check = outcome.check
                val verdict = mountVerdict(check)
                StatusLabel(
                    stringResource(
                        when (verdict) {
                            MountVerdict.Reachable -> R.string.libraries_mount_reachable
                            MountVerdict.NotReachable -> R.string.libraries_mount_not_reachable
                            MountVerdict.LooksEmpty -> R.string.libraries_mount_looks_empty
                            MountVerdict.Problem -> R.string.libraries_mount_problem
                        },
                    ),
                    tone = when (verdict) {
                        MountVerdict.Reachable -> StatusTone.Quiet
                        MountVerdict.NotReachable -> StatusTone.Danger
                        else -> StatusTone.Warning
                    },
                    dot = verdict != MountVerdict.Reachable,
                )
                check.problemRoots().forEach { root ->
                    val line = when {
                        !root.reachable -> root.error_message?.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.libraries_mount_root_unreachable, root.path, it) }
                            ?: stringResource(R.string.libraries_mount_root_unreachable_no_reason, root.path)
                        else -> stringResource(R.string.libraries_mount_root_empty, root.path)
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
                if (verdict == MountVerdict.Reachable || verdict == MountVerdict.Problem) {
                    check.summary.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
                }
                check.checked_at.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.libraries_mount_checked, formatRelativeTime(it)), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun RefreshBlock(library: AdminLibrary, job: LibraryJob, busy: Set<String>, onCancel: (LibraryJob) -> Unit) {
    Column(Modifier.testTag("refresh-job"), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        val progress = job.progress?.takeIf { it.total > 0 }
        val text = when (job.state) {
            "queued" -> stringResource(R.string.libraries_refresh_queued)
            "running" -> progress?.let { stringResource(R.string.libraries_refresh_progress, it.current, it.total) }
                ?: stringResource(R.string.libraries_refresh_running)
            "canceling" -> stringResource(R.string.libraries_refresh_canceling)
            "succeeded" -> job.refresh_result?.let { stringResource(R.string.libraries_refresh_result, it.refreshed, it.failed) }
                ?: stringResource(R.string.libraries_refresh_succeeded)
            "failed" -> stringResource(R.string.libraries_refresh_failed)
            "canceled", "cancelled" -> stringResource(R.string.libraries_refresh_cancelled)
            else -> job.state
        }
        StatusLabel(
            text,
            tone = when (job.state) {
                "running" -> StatusTone.Live
                "failed" -> StatusTone.Danger
                else -> StatusTone.Quiet
            },
        )
        if (job.state == "running" && progress != null) {
            LinearProgressIndicator(
                progress = { (progress.current.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = VeilColors.Text, trackColor = VeilColors.Raised,
            )
        }
        if (job.state == "failed") job.failure?.detail?.takeIf { it.isNotBlank() }
            ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger) }
        if (job.cancelable && !job.terminal) {
            val cancelLabel = stringResource(R.string.libraries_action_cancel_refresh_for, library.name)
            OutlinedButton(
                onClick = { onCancel(job) }, enabled = "cancel:${job.id}" !in busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = cancelLabel },
            ) { Text(stringResource(R.string.libraries_cancel_refresh)) }
        }
    }
}
