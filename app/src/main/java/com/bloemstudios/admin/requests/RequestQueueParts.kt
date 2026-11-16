package com.bloemstudios.admin.requests

import com.bloemstudios.admin.ui.components.LucideIcon

import com.bloemstudios.admin.ui.components.ConfirmButton
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.LucideSize
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing

/** A row's test tag is this plus its request id (and server id and a colon before it, in All servers). */
const val REQUEST_ROW_TAG_PREFIX = "request-row:"
const val REQUEST_VIEW_TAG_PREFIX = "request-view:"
const val REQUEST_ACTION_TAG_PREFIX = "request-action:"
const val REQUEST_LIST_TAG = "request-list"
const val REQUEST_LOAD_MORE_TAG = "request-load-more"
const val REQUEST_VIEW_PICKER_TAG = "request-view-picker"
const val REQUEST_DETAIL_TAG = "request-detail"

/** The app's resources, read afresh when the configuration (language, font scale) changes. */
@Composable
internal fun rememberResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

@Composable
internal fun RequestTone.color(): Color = when (this) {
    RequestTone.Neutral, RequestTone.Progress, RequestTone.Success -> VeilColors.Muted
    RequestTone.Danger -> VeilColors.Danger
}

@DrawableRes private fun RequestQueueAction.icon(): Int = when (this) {
    RequestQueueAction.Approve -> R.drawable.lucide_check
    RequestQueueAction.Decline -> R.drawable.lucide_x
    RequestQueueAction.Retry -> R.drawable.lucide_rotate_cw
    RequestQueueAction.Cancel -> R.drawable.lucide_ban
}

/** A request's state as a status label: red only for a failure, plain text for every other state. */
@Composable
fun RequestBadge(text: String, tone: RequestTone, modifier: Modifier = Modifier) {
    StatusLabel(text, modifier, tone = if (tone == RequestTone.Danger) StatusTone.Danger else StatusTone.Quiet)
}

/** The state a requester sees, with the reason it closed or the error that stopped it. */
@Composable
fun RequestStateSummary(request: MediaRequest) {
    val state = RequestQueue.displayState(request)
    val problem = RequestQueue.problem(request)
    if (state == null && problem == null) return
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        state?.let { RequestBadge(stringResource(it.label), it.tone) }
        problem?.let {
            Text(
                it.text, style = MaterialTheme.typography.bodySmall,
                color = if (it.isError) VeilColors.Danger else VeilColors.Muted,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RequestDownloadLine(download: RequestDownload) {
    val res = rememberResources()
    val label = RequestQueue.downloadPhaseLabel(res, download.phase)
    val percent = RequestQueue.downloadPercent(download)
    if (percent == null) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        // The text beside the bar says what the bar shows, so the bar itself stays out of the reading order.
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.width(160.dp).clearAndSetSemantics { },
            color = VeilColors.Accent,
            trackColor = VeilColors.Card,
        )
        Text(
            stringResource(R.string.requests_download_progress, label, percent),
            style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
        )
    }
}

/** Where each quality went: the download server, its status, the rule that sent it there, the download's progress and the server's error. */
@Composable
fun RequestTargetList(request: MediaRequest) {
    val res = rememberResources()
    if (request.targets.isEmpty()) {
        Text(stringResource(R.string.requests_not_sent), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        request.targets.forEach { target ->
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny), modifier = Modifier.semantics(mergeDescendants = true) { }) {
                Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                    Text(target.quality, style = MaterialTheme.typography.labelMedium, color = VeilColors.Text)
                    Text(
                        RequestQueue.targetServerName(res, target), style = MaterialTheme.typography.bodySmall,
                        color = VeilColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    RequestBadge(
                        RequestQueue.targetStatusLabel(res, target.status),
                        if (target.status == "failed") RequestTone.Danger else RequestTone.Neutral,
                    )
                }
                target.route_name?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.requests_by_route, it), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
                }
                target.download?.let { RequestDownloadLine(it) }
                target.last_error?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = VeilColors.Danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "requester · 3 hours ago · via watchlist". */
@Composable
internal fun requestedLine(request: MediaRequest, requester: String): String {
    val res = rememberResources()
    val parts = mutableListOf(requester)
    RequestQueue.requestedAgo(res, RequestDates.parse(request.created_at))?.let { parts += it }
    if (request.source == "watchlist") parts += stringResource(R.string.requests_via_watchlist)
    return parts.joinToString(" · ")
}

/** The buttons of the actions a request's view allows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestActionButtons(
    request: MediaRequest,
    actions: List<RequestQueueAction>,
    busy: Boolean,
    onAction: (RequestQueueAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        actions.forEach { action ->
            val label = stringResource(action.label)
            val description = stringResource(R.string.requests_action_for, label, request.title)
            // A raised secondary button, whatever the action. The one accent fill on the screen is the bulk "Approve N";
            // three orange Approve buttons in a column would each say "this is the one that matters".
            OutlinedButton(
                onClick = { onAction(action) },
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = VeilSpacing.MinimumTouchTarget)
                    .testTag(REQUEST_ACTION_TAG_PREFIX + action.name.lowercase())
                    .semantics { contentDescription = description },
            ) {
                LucideIcon(action.icon(), contentDescription = null, size = LucideSize.Inline, modifier = Modifier.padding(end = VeilSpacing.Tiny))
                Text(label)
            }
        }
        if (busy) Text(
            stringResource(R.string.requests_busy), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

/**
 * One request in the queue: poster, title, what it is, who asked and when, its state, where it went, and
 * the actions its view allows. Tapping the request opens its details; a long press or the menu offers
 * [onFilterUser] ("Show only this account's requests"), where the queue has such a filter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RequestQueueRow(
    request: MediaRequest,
    requester: String,
    onAction: (RequestQueueAction) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onFilterUser: (() -> Unit)? = null,
    tag: String = REQUEST_ROW_TAG_PREFIX + request.id,
    /** Set in select mode: the row carries a check box, and a tap checks or unchecks it instead of opening the details. */
    selection: RequestRowSelection? = null,
    /** Starts select mode with this row checked; a long press does it where the row can be approved. */
    onSelectStart: (() -> Unit)? = null,
) {
    val res = rememberResources()
    val shape = RoundedCornerShape(VeilShapes.CompactCardCorner)
    var menuOpen by remember(request.id) { mutableStateOf(false) }
    val hint = stringResource(R.string.requests_row_details_hint)
    val filterLabel = stringResource(R.string.requests_filter_by_user)
    val selectLabel = stringResource(R.string.requests_select_row, request.title)
    val canFilter = onFilterUser != null && request.requested_by_user_id != null && selection == null
    val longPressSelects = selection == null && onSelectStart != null
    Column(
        modifier
            .fillMaxWidth()
            .testTag(tag)
            .clip(shape)
            .background(VeilColors.Card, shape)
            .border(BorderStroke(1.dp, VeilColors.Hairline), shape)
            .padding(VeilSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (selection != null) Modifier
                            .semantics { contentDescription = selectLabel }
                            .toggleable(value = selection.selected, enabled = !busy, role = Role.Checkbox, onValueChange = { selection.onToggle() })
                        else Modifier.combinedClickable(
                            onClickLabel = hint, role = Role.Button, onClick = onOpen,
                            onLongClickLabel = when { longPressSelects -> selectLabel; canFilter -> filterLabel; else -> null },
                            onLongClick = when {
                                longPressSelects -> onSelectStart
                                canFilter -> ({ menuOpen = true })
                                else -> null
                            },
                        )
                    )
                    .padding(end = if (canFilter) VeilSpacing.MinimumTouchTarget - VeilSpacing.Medium else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
            ) {
                if (selection != null) Checkbox(
                    checked = selection.selected, onCheckedChange = null, enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                RequestPoster(request.poster_path, Modifier.width(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                    Text(request.title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(RequestQueue.metaLine(res, request), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    Text(requestedLine(request, requester), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    RequestStateSummary(request)
                    RequestTargetList(request)
                }
            }
            if (canFilter) Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    LucideIcon(R.drawable.lucide_ellipsis, contentDescription = stringResource(R.string.requests_row_actions_description, request.title))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(filterLabel) },
                        onClick = { menuOpen = false; onFilterUser?.invoke() },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    )
                }
            }
        }
        if (selection == null) RequestActionButtons(request, RequestQueue.actions(request), busy, onAction)
    }
}

/** A selectable chip: at least 48 dp tall, whatever its text. */
@Composable
internal fun RequestChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(VeilShapes.ControlCorner)
    Row(
        modifier
            .heightIn(min = VeilSpacing.MinimumTouchTarget)
            .clip(shape)
            // The chosen chip is raised and has a text-colored edge; accent stays for the one primary action.
            .border(1.dp, if (selected) VeilColors.Text else VeilColors.Outline, shape)
            .background(if (selected) VeilColors.Raised else VeilColors.Card, shape)
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = VeilSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The queue's four views with their counts, as a row of chips that scrolls sideways. The row takes
 * sideways drags itself, so a swipe on it moves the row and a swipe anywhere else on the page still
 * changes pages.
 */
@Composable
fun RequestViewPicker(selection: RequestQueueView, counts: RequestCounts?, onSelect: (RequestQueueView) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.requests_views_label)
    Row(
        modifier
            .fillMaxWidth()
            .testTag(REQUEST_VIEW_PICKER_TAG)
            .semantics { contentDescription = label }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        RequestQueueView.entries.forEach { view ->
            val text = stringResource(view.label)
            val count = counts?.get(view)
            val description = if (count == null) text else stringResource(R.string.requests_view_count_description, text, count)
            RequestChip(
                selected = view == selection,
                onClick = { onSelect(view) },
                modifier = Modifier.testTag(REQUEST_VIEW_TAG_PREFIX + view.wire).semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
                if (count != null) Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
            }
        }
    }
}

/** What a decline, cancel or close asks before it runs: an optional note. */
@Composable
fun RequestReasonDialog(
    action: RequestQueueAction,
    request: MediaRequest,
    onConfirm: (note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val res = rememberResources()
    val prompt = RequestQueue.reasonPrompt(res, action, request) ?: return
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(prompt.message)
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(stringResource(R.string.requests_reason_label)) },
                    placeholder = { Text(stringResource(R.string.requests_reason_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // Declining or cancelling a request cannot be taken back; closing one only files it.
            ConfirmButton(
                onClick = { onConfirm(note) }, destructive = action != RequestQueueAction.Retry,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
            ) { Text(prompt.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.requests_reason_keep))
            }
        },
    )
}

/** The text of an instant for the details page, or null when the server sent none. */
internal fun detailDate(text: String?): String? = RequestDates.parse(text)?.let { RequestQueue.dateTime(it) }
