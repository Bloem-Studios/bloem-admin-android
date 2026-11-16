package com.bloemstudios.admin.streams

import com.bloemstudios.admin.ui.components.LucideIcon

import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.links.DeepLinkItems
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class StreamActions(
    val onPause: (AdminSession) -> Unit,
    val onResume: (AdminSession) -> Unit,
    val onStop: (AdminSession) -> Unit,
    val onTerminate: (AdminSession) -> Unit,
    val onSendMessage: suspend (AdminSession, String?, String) -> SendMessageResult,
    /** Opens Logs on the stream's playback session (true: FFmpeg lines only). Null where the page cannot open Logs. */
    val onOpenLogs: ((AdminSession, Boolean) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(
    client: ApiClient,
    showClientIp: Boolean = false,
    onOpenLogs: ((playbackSessionId: String, ffmpegOnly: Boolean) -> Unit)? = null,
) {
    val viewModel = viewModel<StreamsViewModel>()
    val sessions by client.liveSessions.sessions.collectAsState()
    val sourceState by client.liveSessions.state.collectAsState()
    val sourceError by client.liveSessions.error.collectAsState()
    val error by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()
    // A widget row opens this page with that stream's details showing.
    val linkedSession = remember { DeepLinkItems.take("live.streams") }
    ForegroundEffect(client) { client.liveSessions.observe() }
    // The first page has real data: the moment the notification permission may be asked for (never straight after sign-in).
    LaunchedEffect(sessions != null) { if (sessions != null) com.bloemstudios.admin.push.FirstDataLoad.mark() }
    if (pullState.isRefreshing) {
        LaunchedEffect(pullState) {
            try { client.liveSessions.refresh() } finally { pullState.endRefresh() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection),
    ) {
        StreamsVeilContent(
            hasLoadedOnce = sessions != null || (sourceError != null && sourceError != SessionFailure.Unsupported),
            sessions = sessions.orEmpty(),
            sourceState = sourceState, sourceError = sourceError, sessionDataAvailable = sessions != null,
            errorMessage = error?.resolve(),
            pendingKeys = pendingKeys, onDismissError = viewModel::dismissActionError, showClientIp = showClientIp,
            linkedSessionId = linkedSession,
            onRetry = { pullState.startRefresh() },
            actions = StreamActions(
                onPause = { session -> scope.launch { viewModel.pause(session, client) } },
                onResume = { session -> scope.launch { viewModel.resume(session, client) } },
                onStop = { session -> scope.launch { viewModel.stop(session, client) } },
                onTerminate = { session ->
                    scope.launch {
                        val result = viewModel.terminate(session, client)
                        if (result == com.bloemstudios.admin.ActionResult.Success) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                onSendMessage = { session, title, message -> viewModel.sendMessage(session, title, message, client) },
                onOpenLogs = onOpenLogs?.let { open -> { session, ffmpegOnly -> open(session.session_id, ffmpegOnly) } },
            ),
        )
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
internal fun StreamsVeilContent(
    hasLoadedOnce: Boolean,
    sessions: List<AdminSession>,
    errorMessage: String?,
    onRetry: () -> Unit,
    actions: StreamActions,
    sourceState: RealtimeState = RealtimeState.Unavailable,
    sourceError: SessionFailure? = null,
    sessionDataAvailable: Boolean = hasLoadedOnce,
    pendingKeys: Set<String> = emptySet(),
    onDismissError: () -> Unit = {},
    showClientIp: Boolean = false,
    linkedSessionId: String? = null,
) {
    var actionSession by remember { mutableStateOf<AdminSession?>(null) }
    var pendingTermination by remember { mutableStateOf<AdminSession?>(null) }
    var sessionBeingMessaged by remember { mutableStateOf<AdminSession?>(null) }
    LaunchedEffect(actionSession?.session_id, sessions) {
        val openSession = actionSession ?: return@LaunchedEffect
        if (sessions.none { it.session_id == openSession.session_id }) actionSession = null
    }
    ShellInteractionLockEffect(
        active = actionSession != null || pendingTermination != null || sessionBeingMessaged != null,
    )

    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        // With nothing to show, the page is loading or failed, and says so once: no status line above it.
        val failed = sourceError != null && !sessionDataAvailable && sourceError != SessionFailure.Unsupported
        if (hasLoadedOnce && !failed) Box(Modifier.padding(top = VeilSpacing.Small)) { SessionSourceStatus(sourceState, sourceError, sessionDataAvailable) }
        if (errorMessage != null) VeilStateSurface(
            kind = VeilStateSurfaceKind.PartialFailure,
            title = stringResource(R.string.error_action_failed), message = errorMessage,
            stateDescription = stringResource(R.string.error_action_failed),
            action = VeilStateSurfaceAction(stringResource(R.string.action_dismiss), onDismissError),
        )
        when {
            !hasLoadedOnce -> VeilStateSurface(kind = VeilStateSurfaceKind.Loading, title = "", message = "",
                stateDescription = stringResource(R.string.streams_state_loading_description))
            failed -> SessionsFailureSurface(sourceError!!, onRetry)
            else -> {
                if (sessions.isEmpty()) VeilStateSurface(
                    kind = VeilStateSurfaceKind.Empty,
                    title = stringResource(R.string.streams_state_empty_title),
                    message = "",
                    stateDescription = stringResource(R.string.streams_state_empty_description),
                    action = VeilStateSurfaceAction(stringResource(R.string.action_refresh), onRetry),
                ) else LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    items(sessions, key = { it.session_id }) { session ->
                        StreamVeilRow(session, actionSession?.session_id == session.session_id,
                            { actionSession = session }, { actionSession = null }, actions,
                            { actionSession = null; pendingTermination = session },
                            { actionSession = null; sessionBeingMessaged = session }, session.session_id in pendingKeys, showClientIp,
                            startExpanded = session.session_id == linkedSessionId)
                    }
                }
            }
        }
    }

    sessionBeingMessaged?.takeUnless { sourceState == RealtimeState.SignInRequired }?.let { session ->
        MessageSessionDialog(
            session = session,
            onDismiss = { sessionBeingMessaged = null },
            onSend = { title, message -> actions.onSendMessage(session, title, message) },
        )
    }
    pendingTermination?.takeUnless { sourceState == RealtimeState.SignInRequired }?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingTermination = null },
            title = { Text(stringResource(R.string.streams_terminate_confirm_title)) },
            text = { Text(stringResource(R.string.streams_terminate_confirm_body, session.username, session.media_title)) },
            confirmButton = {
                DestructiveTextButton(
                    enabled = session.session_id !in pendingKeys,
                    onClick = {
                        actions.onTerminate(session)
                        pendingTermination = null
                    },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(R.string.streams_terminate)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingTermination = null },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun StreamVeilRow(
    session: AdminSession,
    menuExpanded: Boolean,
    onMenuRequested: () -> Unit,
    onMenuDismissed: () -> Unit,
    actions: StreamActions,
    onTerminateRequested: () -> Unit,
    onMessageRequested: () -> Unit,
    pending: Boolean = false,
    showClientIp: Boolean = false,
    startExpanded: Boolean = false,
) {
    StreamRow(session, showClientIp = showClientIp, pending = pending, startExpanded = startExpanded, actions = {
        Box {
            IconButton(enabled = !pending, onClick = onMenuRequested, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                LucideIcon(R.drawable.lucide_ellipsis, contentDescription = stringResource(R.string.streams_actions_description, session.username))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismissed) {
                if (session.is_paused) {
                    DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_resume)) }, onClick = {
                        onMenuDismissed(); actions.onResume(session)
                    })
                } else {
                    DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_pause)) }, onClick = {
                        onMenuDismissed(); actions.onPause(session)
                    })
                }
                DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_stop)) }, onClick = {
                    onMenuDismissed(); actions.onStop(session)
                })
                DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_terminate)) }, onClick = onTerminateRequested)
                DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_message)) }, onClick = onMessageRequested)
                actions.onOpenLogs?.let { openLogs ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_open_logs)) }, onClick = { onMenuDismissed(); openLogs(session, false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_open_ffmpeg_logs)) }, onClick = { onMenuDismissed(); openLogs(session, true) })
                }
            }
        }
    })
}

/** Whole-percent playback progress, or null if duration is unavailable or non-positive. */
fun playbackProgressPercent(session: AdminSession): Int? {
    val duration = session.file_duration?.takeIf { it > 0.0 } ?: return null
    return ((session.position_seconds / duration) * 100).toInt().coerceIn(0, 100)
}

@Composable
internal fun MessageSessionDialog(
    session: AdminSession,
    onDismiss: () -> Unit,
    onSend: suspend (title: String?, message: String) -> SendMessageResult,
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<ErrorText?>(null) }
    var sent by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(VeilShapes.PrimaryCardCorner)) {
            Column(modifier = Modifier.padding(VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.streams_message_dialog_title, session.username), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.streams_message_title_hint)) })
                OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text(stringResource(R.string.streams_message_body_hint)) })
                errorMessage?.let { Text(it.resolve(), color = VeilColors.Danger) }
                if (sent) Text(stringResource(R.string.streams_message_sent), color = VeilColors.Ok)
                Row {
                    Button(
                        enabled = !isSubmitting && message.isNotBlank(),
                        onClick = {
                            scope.launch {
                                isSubmitting = true
                                errorMessage = null
                                sent = false
                                when (val result = onSend(title.takeIf { it.isNotBlank() }, message)) {
                                    SendMessageResult.Busy -> errorMessage = ErrorText.Res(R.string.action_in_progress)
                                    is SendMessageResult.Success -> sent = true
                                    is SendMessageResult.ClientNotConnected -> errorMessage = ErrorText.Res(R.string.streams_message_client_not_connected)
                                    is SendMessageResult.Failure -> errorMessage = result.message
                                }
                                isSubmitting = false
                            }
                        },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    ) { Text(stringResource(R.string.streams_message_send)) }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}
