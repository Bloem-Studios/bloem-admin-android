package com.bloemstudios.admin.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.ConfirmButton
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val REQUEST_SELECT_START_TAG = "request-select-start"
const val REQUEST_SELECT_BAR_TAG = "request-select-bar"
const val REQUEST_SELECT_ALL_TAG = "request-select-all"
const val REQUEST_BULK_APPROVE_TAG = "request-bulk-approve"
const val REQUEST_BULK_DECLINE_TAG = "request-bulk-decline"
const val REQUEST_SELECT_DONE_TAG = "request-select-done"
const val REQUEST_BULK_PROGRESS_TAG = "request-bulk-progress"
const val REQUEST_BULK_RESULT_TAG = "request-bulk-result"
const val REQUEST_BULK_NOTE_TAG = "request-bulk-note"
const val REQUEST_UNAVAILABLE_TAG = "request-unavailable"
const val REQUEST_STALE_TAG = "request-stale"

/** What the queue pages do for select mode and bulk actions. Keys are request IDs, or `serverId:requestId` in All servers. */
class RequestBulkCallbacks(
    val onStart: (key: String?) -> Unit,
    val onToggle: (key: String) -> Unit,
    val onSelectAll: () -> Unit,
    val onClear: () -> Unit,
    val onDone: () -> Unit,
    val onApprove: () -> Unit,
    val onDecline: (note: String) -> Unit,
    val onDismissResult: () -> Unit,
)

/** A row in select mode: its check box state and how a tap changes it. */
class RequestRowSelection(val selected: Boolean, val onToggle: () -> Unit)

/**
 * The strip above the rows on Needs approval. Outside select mode it offers Select; inside, "Select all shown", how many
 * are checked, Approve N, Decline N and Done. The buttons wait while a run is in progress.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestSelectBar(
    selecting: Boolean,
    shown: Int,
    selected: Int,
    running: Boolean,
    onStart: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!selecting) {
        if (shown == 0) return
        TextButton(
            onClick = onStart,
            modifier = modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(REQUEST_SELECT_START_TAG),
        ) { Text(stringResource(R.string.requests_select)) }
        return
    }
    val allSelected = shown > 0 && selected == shown
    RowCard(modifier.testTag(REQUEST_SELECT_BAR_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = VeilSpacing.MinimumTouchTarget)
                    .testTag(REQUEST_SELECT_ALL_TAG)
                    .toggleable(value = allSelected, enabled = !running && shown > 0, role = Role.Checkbox) { if (it) onSelectAll() else onClear() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
            ) {
                Checkbox(checked = allSelected, onCheckedChange = null, enabled = !running && shown > 0)
                Column {
                    Text(stringResource(R.string.requests_select_all_shown), color = VeilColors.Text, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.requests_selected_count, selected), color = VeilColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(
                onClick = onDone, enabled = !running,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(REQUEST_SELECT_DONE_TAG),
            ) { Text(stringResource(R.string.requests_select_done)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Button(
                onClick = onApprove, enabled = selected > 0 && !running,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(REQUEST_BULK_APPROVE_TAG),
            ) { Text(stringResource(R.string.requests_bulk_approve, selected)) }
            OutlinedButton(
                onClick = onDecline, enabled = selected > 0 && !running,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(REQUEST_BULK_DECLINE_TAG),
            ) { Text(stringResource(R.string.requests_bulk_decline, selected)) }
        }
    }
}

/**
 * A bulk run: "Approving 3 of 12" with a bar while it runs; afterwards what was done, and each refused request with its
 * reason. A refused request stays checked, so Approve N or Decline N tries it again.
 */
@Composable
fun RequestBulkStatus(progress: BulkProgress, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val approving = progress.action == BulkRequestAction.Approve
    if (!progress.finished) {
        RowCard(modifier.testTag(REQUEST_BULK_PROGRESS_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(
                stringResource(if (approving) R.string.requests_bulk_approving else R.string.requests_bulk_declining, progress.done, progress.total),
                color = VeilColors.Text, style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                color = VeilColors.Accent, trackColor = VeilColors.Hairline,
            )
        }
        return
    }
    val succeeded = progress.total - progress.failures.size
    RowCard(modifier.testTag(REQUEST_BULK_RESULT_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
        if (progress.failures.isEmpty()) {
            Text(
                pluralStringResource(if (approving) R.plurals.requests_bulk_approved else R.plurals.requests_bulk_declined, succeeded, succeeded),
                color = VeilColors.Text, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            StatusLabel(
                stringResource(if (approving) R.string.requests_bulk_approve_partial else R.string.requests_bulk_decline_partial, succeeded, progress.total),
                tone = StatusTone.Danger,
            )
            progress.failures.forEach { failure ->
                Text(
                    stringResource(R.string.requests_bulk_failure, failure.title, failure.message.resolve()),
                    color = VeilColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

/**
 * The select bar, the run's progress or result, and the note prompt, as one block above the rows of Needs approval.
 * [shown] counts the rows that can be checked; [selected] how many are.
 */
@Composable
fun RequestBulkSection(
    selecting: Boolean,
    shown: Int,
    selected: Int,
    progress: BulkProgress?,
    callbacks: RequestBulkCallbacks,
    modifier: Modifier = Modifier,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val running = progress?.finished == false
    ShellInteractionLockEffect(active = running)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        RequestSelectBar(
            selecting = selecting, shown = shown, selected = selected, running = running,
            onStart = { callbacks.onStart(null) }, onSelectAll = callbacks.onSelectAll, onClear = callbacks.onClear,
            onApprove = callbacks.onApprove, onDecline = { asking = true }, onDone = callbacks.onDone,
        )
        progress?.let { RequestBulkStatus(it, callbacks.onDismissResult) }
    }
    if (asking) BulkDeclineDialog(
        count = selected,
        onConfirm = { note -> asking = false; callbacks.onDecline(note) },
        onDismiss = { asking = false },
    )
}

/** Asks for the one optional note that goes with every decline in a bulk run. */
@Composable
fun BulkDeclineDialog(count: Int, onConfirm: (note: String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    ShellInteractionLockEffect(true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.requests_bulk_decline_title, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.requests_bulk_decline_message))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(stringResource(R.string.requests_reason_label)) },
                    placeholder = { Text(stringResource(R.string.requests_reason_placeholder)) },
                    modifier = Modifier.fillMaxWidth().testTag(REQUEST_BULK_NOTE_TAG),
                )
            }
        },
        confirmButton = {
            ConfirmButton(onClick = { onConfirm(note) }, destructive = true, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.requests_bulk_decline, count))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.requests_reason_keep))
            }
        },
    )
}

/** Shown in place of the queue where the server has no request administration. There is nothing to retry. */
@Composable
fun RequestUnavailableNote(modifier: Modifier = Modifier) {
    RowCard(modifier.testTag(REQUEST_UNAVAILABLE_TAG)) {
        Text(stringResource(R.string.requests_unavailable_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
        Text(stringResource(R.string.requests_unavailable_detail), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
}

/** "Couldn't refresh · last updated 14:32", for rows that stay on screen after a read failed. */
@Composable
fun RequestStaleLine(lastUpdated: Instant?, modifier: Modifier = Modifier) {
    if (lastUpdated == null) return
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(lastUpdated)
    StatusLabel(stringResource(R.string.requests_stale, time), modifier.testTag(REQUEST_STALE_TAG), tone = StatusTone.Warning)
}
