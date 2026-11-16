package com.bloemstudios.admin.streams

import com.bloemstudios.admin.ui.components.LucideIcon

import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.realtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class AggregateStreamActions(
    val onPause: (AdminServer, AdminSession) -> Unit,
    val onResume: (AdminServer, AdminSession) -> Unit,
    val onStop: (AdminServer, AdminSession) -> Unit,
    val onTerminate: (AdminServer, AdminSession) -> Unit,
    val onSendMessage: suspend (AdminServer, AdminSession, String?, String) -> SendMessageResult,
    val onSignIn: (AdminServer) -> Unit,
    val onRetry: (AdminServer) -> Unit,
)

@Composable
fun AggregateStreamsScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    onSignIn: (AdminServer) -> Unit,
    expiredServerIds: Set<String> = emptySet(),
    showClientIp: Boolean = false,
) {
    val viewModel = viewModel<AggregateStreamsViewModel>()
    val errorMessage by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { store.client(it) } }
    ForegroundEffect(servers) {
        servers.forEach { server -> runCatching { clientFor(server) }.getOrNull()?.let { client -> launch { client.liveSessions.observe() } } }
    }
    val actions = AggregateStreamActions(
        { server, session -> scope.launch { viewModel.pause(server, session, clientFor) } },
        { server, session -> scope.launch { viewModel.resume(server, session, clientFor) } },
        { server, session -> scope.launch { viewModel.stop(server, session, clientFor) } },
        { server, session -> scope.launch { viewModel.terminate(server, session, clientFor) } },
        { server, session, title, message -> viewModel.sendMessage(server, session, title, message, clientFor) },
        onSignIn, { server -> scope.launch { viewModel.retry(server, clientFor) } },
    )
    AggregateRefreshSurface(emptyList(), onRefresh = { viewModel.load(servers, clientFor) }) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
            errorMessage?.let { item { Text(it.resolve(), color = VeilColors.Warning); TextButton(onClick = viewModel::dismissActionError) { Text(stringResource(R.string.action_dismiss)) } } }
            if (servers.isEmpty()) item { Text(stringResource(R.string.activity_scope_no_servers)) }
            items(servers, key = { it.id }) { server ->
                val client = remember(server, store) { runCatching { clientFor(server) }.getOrNull() }
                if (client == null) {
                    LiveServerStreamsSection(server, null, RealtimeState.Unavailable, SessionFailure.Connection, false, actions)
                    return@items
                }
                val sessions by client.liveSessions.sessions.collectAsState()
                LaunchedEffect(sessions != null) { if (sessions != null) com.bloemstudios.admin.push.FirstDataLoad.mark() }
                val state by client.liveSessions.state.collectAsState()
                val error by client.liveSessions.error.collectAsState()
                LiveServerStreamsSection(server, sessions, state, error, server.id in expiredServerIds, actions, pendingKeys, showClientIp)
            }
        }
    }
}

@Composable
internal fun LiveServerStreamsSection(server: AdminServer, sessions: List<AdminSession>?,
    state: RealtimeState, error: SessionFailure?, expired: Boolean, actions: AggregateStreamActions, pendingKeys: Set<String> = emptySet(), showClientIp: Boolean = false) {
    var actionTarget by remember { mutableStateOf<AdminSession?>(null) }
    var termination by remember { mutableStateOf<AdminSession?>(null) }
    var messaging by remember { mutableStateOf<AdminSession?>(null) }
    val signIn = expired || state == RealtimeState.SignInRequired
    LaunchedEffect(sessions, signIn) {
        if (signIn || sessions?.none { it.session_id == actionTarget?.session_id } != false) actionTarget = null
        if (signIn || sessions?.none { it.session_id == termination?.session_id } != false) termination = null
        if (signIn || sessions?.none { it.session_id == messaging?.session_id } != false) messaging = null
    }
    ShellInteractionLockEffect(actionTarget != null || termination != null || messaging != null)
    // One state per server: rows, loading, or one error. The server's name carries its live status, so no line
    // below it has to say "reconnecting" over a page that says it is loading or failed.
    val failed = !signIn && hasNoSessionsBecauseOfFailure(sessions, error)
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
            Text(server.displayName, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (!signIn && sessions != null) SessionSourceBadge(state)
        }
        if (!signIn && sessions != null) SessionSourceNote(state, error, hasSessions = true)
        when {
            signIn -> VeilStateSurface(
                kind = VeilStateSurfaceKind.PartialFailure,
                title = stringResource(R.string.streams_server_sign_in_title),
                message = stringResource(R.string.session_source_sign_in_message),
                stateDescription = stringResource(R.string.streams_server_failure_description, server.displayName),
                action = VeilStateSurfaceAction(stringResource(R.string.relogin_sign_in)) { actions.onSignIn(server) },
            )
            failed -> SessionsFailureSurface(error!!, onRetry = { actions.onRetry(server) })
            sessions == null -> SessionsLoading()
            sessions.isEmpty() -> Text(stringResource(R.string.streams_aggregate_no_sessions))
            else -> sessions.forEach { session ->
                AggregateStreamRow(session, actionTarget?.session_id == session.session_id,
                    { actionTarget = session }, { actionTarget = null },
                    { actionTarget = null; actions.onPause(server, session) },
                    { actionTarget = null; actions.onResume(server, session) },
                    { actionTarget = null; actions.onStop(server, session) },
                    { actionTarget = null; termination = session }, { actionTarget = null; messaging = session },
                    "${server.id}:${session.session_id}" in pendingKeys, showClientIp)
            }
        }
    }
    messaging?.takeUnless { signIn }?.let { session ->
        MessageSessionDialog(session, onDismiss = { messaging = null },
            onSend = { title, message -> actions.onSendMessage(server, session, title, message) })
    }
    termination?.takeUnless { signIn }?.let { session -> AlertDialog(onDismissRequest = { termination = null },
        title = { Text(stringResource(R.string.streams_terminate_confirm_title)) },
        text = { Text(stringResource(R.string.streams_terminate_confirm_body, session.username, session.media_title)) },
        confirmButton = { DestructiveTextButton(enabled = "${server.id}:${session.session_id}" !in pendingKeys, onClick = { termination = null; actions.onTerminate(server, session) }) { Text(stringResource(R.string.streams_terminate)) } },
        dismissButton = { TextButton(onClick = { termination = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

/** The first read of a server's sessions: a quiet bar and one line. Nothing to retry yet. */
@Composable
internal fun SessionsLoading(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.status_sessions_loading)
    Column(modifier.fillMaxWidth().semantics { stateDescription = description }, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(description, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = VeilColors.Muted, trackColor = VeilColors.Raised)
    }
}

@Composable
internal fun AggregateStreamsVeilContent(
    hasLoadedOnce: Boolean,
    sections: List<ServerSectionResult<List<AdminSession>>>,
    errorMessage: String?,
    actions: AggregateStreamActions,
    showClientIp: Boolean = false,
) {
    var actionTarget by remember { mutableStateOf<Pair<AdminServer, AdminSession>?>(null) }
    var pendingTermination by remember { mutableStateOf<Pair<AdminServer, AdminSession>?>(null) }
    var messaging by remember { mutableStateOf<Pair<AdminServer, AdminSession>?>(null) }
    LaunchedEffect(actionTarget?.first?.id, actionTarget?.second?.session_id, sections) {
        val (server, session) = actionTarget ?: return@LaunchedEffect
        val stillPresent = sections.firstOrNull { it.server.id == server.id }
            ?.result?.getOrNull()
            ?.any { it.session_id == session.session_id } == true
        if (!stillPresent) actionTarget = null
    }
    ShellInteractionLockEffect(active = actionTarget != null || pendingTermination != null || messaging != null)

    if (!hasLoadedOnce) {
        VeilStateSurface(
            kind = VeilStateSurfaceKind.Loading,
            title = "",
            message = "",
            stateDescription = stringResource(R.string.streams_aggregate_state_loading_description),
            modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        if (errorMessage != null) {
            VeilStateSurface(
                kind = VeilStateSurfaceKind.PartialFailure,
                title = stringResource(R.string.streams_state_partial_title),
                message = errorMessage,
                stateDescription = stringResource(R.string.streams_aggregate_state_partial_description),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            items(sections, key = { it.server.id }) { section ->
                Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    Text(
                        section.server.displayName,
                        color = VeilColors.Text,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = VeilSpacing.Small).semantics { heading() },
                    )
                    val sessions = section.result.getOrNull()
                    val failure = section.result.exceptionOrNull()
                    when {
                        failure != null -> ServerStreamFailure(section.server, failure, actions)
                        sessions.isNullOrEmpty() -> VeilStateSurface(
                            kind = VeilStateSurfaceKind.Empty,
                            title = stringResource(R.string.streams_aggregate_no_sessions),
                            message = "",
                            stateDescription = stringResource(R.string.streams_server_empty_description, section.server.displayName),
                        )
                        else -> sessions.forEach { session ->
                            AggregateStreamRow(
                                session = session,
                                menuExpanded = actionTarget?.let { it.first.id == section.server.id && it.second.session_id == session.session_id } == true,
                                onMenuRequested = { actionTarget = section.server to session },
                                onMenuDismissed = { actionTarget = null },
                                onPause = { actionTarget = null; actions.onPause(section.server, session) },
                                onResume = { actionTarget = null; actions.onResume(section.server, session) },
                                onStop = { actionTarget = null; actions.onStop(section.server, session) },
                                onTerminate = {
                                    actionTarget = null
                                    pendingTermination = section.server to session
                                },
                                onMessage = { actionTarget = null; messaging = section.server to session },
                                showClientIp = showClientIp,
                            )
                        }
                    }
                }
            }
        }
    }

    messaging?.let { (server, session) ->
        MessageSessionDialog(session, onDismiss = { messaging = null },
            onSend = { title, message -> actions.onSendMessage(server, session, title, message) })
    }

    pendingTermination?.let { (server, session) ->
        AlertDialog(
            onDismissRequest = { pendingTermination = null },
            title = { Text(stringResource(R.string.streams_terminate_confirm_title)) },
            text = { Text(stringResource(R.string.streams_terminate_confirm_body, session.username, session.media_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onTerminate(server, session)
                        pendingTermination = null
                    },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(R.string.streams_terminate)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTermination = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ServerStreamFailure(
    server: AdminServer,
    failure: Throwable,
    actions: AggregateStreamActions,
) {
    val isAuthFailure = failure is ApiError && failure.statusCode in setOf(401, 403)
    VeilStateSurface(
        kind = VeilStateSurfaceKind.PartialFailure,
        title = if (isAuthFailure) stringResource(R.string.streams_server_sign_in_title) else stringResource(R.string.streams_server_unavailable_title),
        message = if (isAuthFailure) {
            stringResource(R.string.streams_server_sign_in_message, server.displayName)
        } else {
            stringResource(R.string.streams_aggregate_unreachable, server.displayName)
        },
        stateDescription = stringResource(R.string.streams_server_failure_description, server.displayName),
        action = VeilStateSurfaceAction(
            label = if (isAuthFailure) stringResource(R.string.relogin_sign_in) else stringResource(R.string.action_retry),
            onClick = { if (isAuthFailure) actions.onSignIn(server) else actions.onRetry(server) },
        ),
    )
}

@Composable
private fun AggregateStreamRow(
    session: AdminSession,
    menuExpanded: Boolean,
    onMenuRequested: () -> Unit,
    onMenuDismissed: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onTerminate: () -> Unit,
    onMessage: () -> Unit,
    pending: Boolean = false,
    showClientIp: Boolean = false,
) {
    StreamRow(session, showClientIp = showClientIp, pending = pending, actions = {
        Box {
            IconButton(enabled = !pending, onClick = onMenuRequested, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                LucideIcon(R.drawable.lucide_ellipsis, contentDescription = stringResource(R.string.streams_actions_description, session.username))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismissed) {
                DropdownMenuItem(
                    enabled = !pending,
                    text = { Text(stringResource(if (session.is_paused) R.string.streams_resume else R.string.streams_pause)) },
                    onClick = if (session.is_paused) onResume else onPause,
                )
                DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_stop)) }, onClick = onStop)
                DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_terminate)) }, onClick = onTerminate)
                DropdownMenuItem(enabled = !pending, text = { Text(stringResource(R.string.streams_message)) }, onClick = onMessage)
            }
        }
    })
}
