package com.bloemstudios.admin.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.dashboard.formatRelativeTime
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

/** How many runs the list shows. The server is asked for more, because the active runs share the list. */
internal const val RECENT_SCANS_SHOWN = 8

/** The newest scan runs of a server, with what each found. [libraryNames] maps a library id to its name. */
@Composable
internal fun RecentScans(runs: List<ScanRun>, libraryNames: Map<String, String>, modifier: Modifier = Modifier) {
    Column(modifier.testTag("recent-scans"), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(
            stringResource(R.string.libraries_recent_scans), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
            modifier = Modifier.padding(top = VeilSpacing.Small).semantics { heading() },
        )
        if (runs.isEmpty()) {
            Text(stringResource(R.string.libraries_recent_scans_none), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        runs.take(RECENT_SCANS_SHOWN).forEach { run -> ScanRunRow(run, libraryNames[run.library_id] ?: run.library_id) }
    }
}

@Composable
private fun ScanRunRow(run: ScanRun, libraryName: String) {
    val state = scanRunState(run)
    RowCard(Modifier.testTag("scan-run-${run.id}")) {
        Text(
            stringResource(R.string.libraries_scan_run_title, libraryName, scanTriggerText(run.trigger.ifBlank { run.mode })),
            style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
        )
        when (run.mode) {
            "subtree" -> run.path?.let { Text(stringResource(R.string.libraries_scan_run_folder, it), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
            "file" -> run.path?.let { Text(stringResource(R.string.libraries_scan_run_file, it), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        }
        if (state != null) StatusLabel(stringResource(state.labelRes()), tone = state.tone())
        else StatusLabel(run.status)
        run.result?.let { Text(scanCountsText(it), style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted) }
        if (state == ScanState.Failed) run.error_message?.takeIf { it.isNotBlank() }
            ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger) }
        val recordedAt = run.completed_at ?: run.started_at ?: run.requested_at
        val took = scanDuration(run)?.let { scanDurationText(it) }
        val whenText = recordedAt?.let { formatRelativeTime(it) }
        val line = when {
            took != null && whenText != null -> stringResource(R.string.libraries_scan_run_at, whenText, stringResource(R.string.libraries_scan_took, took))
            else -> whenText ?: took
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
    }
}
