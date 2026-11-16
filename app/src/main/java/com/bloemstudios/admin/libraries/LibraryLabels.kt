package com.bloemstudios.admin.libraries

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.StatusTone

/** The words for a scan state. Queued, running, completed, failed and cancelled share the run-status words used by Scheduled tasks. */
@StringRes
internal fun ScanState.labelRes(): Int = when (this) {
    ScanState.Queued -> R.string.run_status_queued
    ScanState.Running -> R.string.run_status_running
    ScanState.Completed -> R.string.run_status_completed
    ScanState.Failed -> R.string.run_status_failed
    ScanState.Cancelled -> R.string.run_status_cancelled
    ScanState.Skipped -> R.string.libraries_scan_skipped
}

/** Running is the only live state; a failure is red, a skipped scan needs a look, the rest is quiet text. */
internal fun ScanState.tone(): StatusTone = when (this) {
    ScanState.Running -> StatusTone.Live
    ScanState.Failed -> StatusTone.Danger
    ScanState.Skipped -> StatusTone.Warning
    else -> StatusTone.Quiet
}

@Composable
internal fun scanDurationText(duration: ScanDuration): String = when {
    duration.hours > 0 -> stringResource(R.string.libraries_scan_duration_hours, duration.hours, duration.minutes)
    duration.minutes > 0 -> stringResource(R.string.libraries_scan_duration_minutes, duration.minutes, duration.seconds)
    else -> stringResource(R.string.libraries_scan_duration_seconds, duration.seconds)
}

/** What started a scan, in words; a trigger this app does not know is shown as the server wrote it, with spaces for underscores. */
@Composable
internal fun scanTriggerText(trigger: String): String = when (trigger) {
    "manual" -> stringResource(R.string.libraries_scan_trigger_manual)
    "auto" -> stringResource(R.string.libraries_scan_trigger_auto)
    "realtime_monitor" -> stringResource(R.string.libraries_scan_trigger_realtime)
    "autoscan" -> stringResource(R.string.libraries_scan_trigger_autoscan)
    "admin_library_refresh" -> stringResource(R.string.libraries_scan_trigger_library_refresh)
    "admin_item_refresh" -> stringResource(R.string.libraries_scan_trigger_item_refresh)
    else -> trigger.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
