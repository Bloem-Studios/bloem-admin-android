package com.bloemstudios.admin.requests

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bloemstudios.admin.R
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface HistoryState {
    data object Loading : HistoryState
    data object Failed : HistoryState
    data class Loaded(val events: List<RequestEvent>) : HistoryState
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun DetailFact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, modifier = Modifier.width(104.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = VeilColors.Text, modifier = Modifier.weight(1f))
    }
}

/**
 * One request's details, as the web admin's request dialog: the title, its state and dates, who asked, where
 * each quality went, the overview, the history, and the actions its view allows. It covers the whole screen
 * and Back closes it. [perform] runs an action and says whether the server took it; the dialog closes then.
 */
@Composable
fun RequestDetailDialog(
    request: MediaRequest,
    requester: String,
    loadEvents: suspend () -> List<RequestEvent>,
    perform: suspend (RequestQueueAction, String?) -> Boolean,
    actionError: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    serverName: String? = null,
) {
    val res = rememberResources()
    val scope = rememberCoroutineScope()
    var history by remember(request.id) { mutableStateOf<HistoryState>(HistoryState.Loading) }
    var asking by remember { mutableStateOf<RequestQueueAction?>(null) }
    var running by remember { mutableStateOf(false) }
    ShellInteractionLockEffect(active = true)
    ForegroundOnceEffect(request.id) {
        history = try {
            HistoryState.Loaded(loadEvents())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HistoryState.Failed
        }
    }

    fun run(action: RequestQueueAction, reason: String?) {
        scope.launch {
            running = true
            try {
                if (perform(action, reason)) onDismiss()
            } finally {
                running = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag(REQUEST_DETAIL_TAG), color = VeilColors.Canvas, contentColor = VeilColors.Text) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Small, vertical = VeilSpacing.Tiny),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                        LucideIcon(R.drawable.lucide_chevron_left, size = LucideSize.Bar, contentDescription = stringResource(R.string.requests_detail_back))
                    }
                    Text(
                        request.title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = VeilSpacing.CompactHorizontal),
                    verticalArrangement = Arrangement.spacedBy(VeilSpacing.Large),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
                        RequestPoster(request.poster_path, Modifier.width(72.dp), size = "w185")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                            serverName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted) }
                            Text(RequestQueue.metaLine(res, request), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                            RequestStateSummary(request)
                        }
                    }
                    DetailSection(stringResource(R.string.requests_detail_request)) {
                        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                            RequestQueue.displayState(request)?.let {
                                DetailFact(stringResource(R.string.requests_detail_state), stringResource(it.label))
                            }
                            DetailFact(stringResource(R.string.requests_detail_requested_by), requester)
                            detailDate(request.created_at)?.let { DetailFact(stringResource(R.string.requests_detail_requested), it) }
                            detailDate(request.approved_at)?.let { DetailFact(stringResource(R.string.requests_detail_approved), it) }
                            detailDate(request.completed_at)?.let { DetailFact(stringResource(R.string.requests_detail_completed), it) }
                            if (request.media_type == "series") {
                                DetailFact(
                                    stringResource(R.string.requests_detail_seasons),
                                    RequestQueue.seasonList(res, request.seasons) ?: stringResource(R.string.requests_detail_whole_series),
                                )
                            }
                            request.tmdb_id?.let { DetailFact(stringResource(R.string.requests_detail_tmdb_id), it.toString()) }
                            if (request.source == "watchlist") {
                                Text(stringResource(R.string.requests_via_watchlist), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                            }
                        }
                    }
                    DetailSection(stringResource(R.string.requests_detail_servers)) { RequestTargetList(request) }
                    request.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
                    }
                    DetailSection(stringResource(R.string.requests_detail_history)) {
                        when (val state = history) {
                            HistoryState.Loading -> Text(stringResource(R.string.management_loading), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                            HistoryState.Failed -> Text(stringResource(R.string.requests_detail_history_failed), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                            is HistoryState.Loaded ->
                                if (state.events.isEmpty()) {
                                    Text(stringResource(R.string.requests_detail_history_empty), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                                } else Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                                    state.events.forEach { event -> HistoryEntry(event) }
                                }
                        }
                    }
                    val actions = RequestQueue.actions(request)
                    if (actions.isNotEmpty() || actionError != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                            RequestActionButtons(request, actions, busy = busy || running, onAction = { action ->
                                if (action.asksForReason) asking = action else run(action, null)
                            })
                            actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger) }
                        }
                    }
                }
            }
        }
    }

    asking?.let { action ->
        RequestReasonDialog(
            action = action, request = request,
            onConfirm = { note -> asking = null; run(action, note) },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun HistoryEntry(event: RequestEvent) {
    val res = rememberResources()
    val by = event.actor_username?.takeIf { it.isNotEmpty() }
    val whenText = detailDate(event.created_at)
    val line = listOfNotNull(whenText, by?.let { stringResource(R.string.requests_detail_actor, it) }).joinToString(" · ")
    Column(Modifier.semantics(mergeDescendants = true) { }) {
        Text(RequestQueue.eventLabel(res, event.type), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        event.message?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Text) }
    }
}

/** A request open in the details, and the server it is on (null on a single server). */
data class OpenRequest(val serverId: String?, val request: MediaRequest)

/** A decline, cancel or close waiting for its optional note. */
data class AskedAction(val action: RequestQueueAction, val server: String?, val request: MediaRequest)
