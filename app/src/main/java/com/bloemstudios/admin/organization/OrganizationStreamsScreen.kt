package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.LucideIcon

import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.OrganizationLiveChannels
import com.bloemstudios.admin.streams.StreamRow
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

/** The test tag of a stream's overflow menu button, followed by its session id. */
const val ORGANIZATION_STREAM_MENU_TAG_PREFIX = "organization-stream-menu:"

/**
 * The organization's Now Playing: the platform page's row without the client address, node or routing, and a stop or message
 * where the server allows them. The list is read when the page opens, on pull, and each time the organization live socket's
 * `org.streams` channel says a stream changed. A server without that socket keeps the open-and-pull behaviour.
 */
@Composable
fun OrganizationStreamsScreen(session: OrganizationSession) {
    val viewModel = viewModel<OrganizationStreamsViewModel>()
    val state by viewModel.state.collectAsState()
    val capabilities by session.supportCapabilities.collectAsState()
    val scope = rememberCoroutineScope()
    OrganizationLiveEffect(session, OrganizationLiveChannels.STREAMS) { viewModel.load(session) }
    OrganizationStreamsContent(
        state = state, capabilities = capabilities,
        onRetry = { scope.launch { viewModel.load(session) } },
        onRefresh = { viewModel.load(session) },
        onDismiss = viewModel::dismissMessages,
        onStop = { stream -> scope.launch { viewModel.stop(session, stream) } },
        onMessage = { stream, title, message -> viewModel.sendMessage(session, stream, title, message) },
    )
}

@Composable
internal fun OrganizationStreamsContent(
    state: OrganizationStreamsState,
    capabilities: SupportCapabilities,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onStop: (SupportStream) -> Unit,
    onMessage: suspend (SupportStream, title: String?, message: String) -> StreamMessageResult,
    onRefresh: (suspend () -> Unit)? = null,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var stopping by remember { mutableStateOf<SupportStream?>(null) }
    var messaging by remember { mutableStateOf<SupportStream?>(null) }
    OrganizationPage(
        title = stringResource(R.string.organization_streams_title),
        subtitle = stringResource(R.string.organization_streams_subtitle),
        keepSubtitle = true,
        loading = state.loading, empty = state.streams.isEmpty(),
        emptyMessage = stringResource(if (state.unavailable) R.string.organization_streams_unavailable else R.string.organization_streams_none),
        error = state.error, notice = state.notice, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
    ) {
        if (state.truncated) item {
            Text(
                stringResource(R.string.organization_streams_more, state.streams.size),
                style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
            )
        }
        items(state.streams, key = { it.session_id }) { stream ->
            val pending = stream.session_id in state.pendingIds
            val canStop = capabilities.streams.stop && stream.can_stop
            val canMessage = capabilities.streams.message && stream.can_message
            StreamRow(
                session = stream.toAdminSession(), showClientIp = false, pending = pending, showRouting = false,
                actions = if (canStop || canMessage) ({
                    Box {
                        IconButton(
                            enabled = !pending, onClick = { menuFor = stream.session_id },
                            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(ORGANIZATION_STREAM_MENU_TAG_PREFIX + stream.session_id),
                        ) {
                            LucideIcon(R.drawable.lucide_ellipsis, contentDescription = stringResource(R.string.organization_streams_actions, stream.person_name))
                        }
                        DropdownMenu(expanded = menuFor == stream.session_id, onDismissRequest = { menuFor = null }) {
                            if (canMessage) DropdownMenuItem(
                                text = { Text(stringResource(R.string.organization_streams_message)) },
                                onClick = { menuFor = null; messaging = stream },
                                modifier = Modifier.testTag("organization-stream-message"),
                            )
                            if (canStop) DropdownMenuItem(
                                text = { Text(stringResource(R.string.organization_streams_stop)) },
                                onClick = { menuFor = null; stopping = stream },
                                modifier = Modifier.testTag("organization-stream-stop"),
                            )
                        }
                    }
                }) else null,
            )
        }
    }
    stopping?.let { stream ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(stringResource(R.string.organization_streams_stop_title, stream.person_name)) },
            text = { Text(stringResource(R.string.organization_streams_stop_body, stream.person_name, stream.title)) },
            confirmButton = {
                DestructiveTextButton(
                    onClick = { stopping = null; onStop(stream) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-stream-stop-confirm"),
                ) { Text(stringResource(R.string.organization_streams_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { stopping = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
    messaging?.let { stream -> StreamMessageDialog(stream, onMessage) { messaging = null } }
}

/** Title (optional) and message for one stream. Stays open after a send so the result can be read; Close leaves. */
@Composable
internal fun StreamMessageDialog(
    stream: SupportStream,
    onSend: suspend (SupportStream, title: String?, message: String) -> StreamMessageResult,
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<StreamMessageResult?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.organization_streams_message_title, stream.person_name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(TITLE_LIMIT); result = null }, singleLine = true,
                    label = { Text(stringResource(R.string.organization_streams_message_title_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("organization-stream-message-title"),
                )
                OutlinedTextField(
                    value = message, onValueChange = { message = it.take(MESSAGE_LIMIT); result = null },
                    label = { Text(stringResource(R.string.organization_streams_message_body_hint)) },
                    supportingText = { Text(stringResource(R.string.organization_streams_message_count, message.length)) },
                    minLines = 2, modifier = Modifier.fillMaxWidth().testTag("organization-stream-message-body"),
                )
                when (val shown = result) {
                    null -> Unit
                    is StreamMessageResult.Sent -> Text(
                        stringResource(if (shown.queued) R.string.organization_streams_message_queued else R.string.organization_streams_message_sent),
                        color = VeilColors.Ok, modifier = Modifier.testTag("organization-stream-message-result"),
                    )
                    StreamMessageResult.Busy -> Text(stringResource(R.string.action_in_progress), color = VeilColors.Warning)
                    StreamMessageResult.ClientNotConnected -> Text(
                        stringResource(R.string.organization_streams_message_not_connected), color = VeilColors.Danger,
                        modifier = Modifier.testTag("organization-stream-message-result"),
                    )
                    is StreamMessageResult.Invalid -> Text(shown.message.resolve(), color = VeilColors.Danger, modifier = Modifier.testTag("organization-stream-message-result"))
                    is StreamMessageResult.Failed -> Text(shown.message.resolve(), color = VeilColors.Danger, modifier = Modifier.testTag("organization-stream-message-result"))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && message.isNotBlank(),
                onClick = {
                    scope.launch {
                        sending = true
                        result = null
                        result = onSend(stream, title.takeIf { it.isNotBlank() }, message)
                        if (result is StreamMessageResult.Sent) message = ""
                        sending = false
                    }
                },
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-stream-message-send"),
            ) { Text(stringResource(R.string.organization_streams_message_send)) }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-stream-message-close")) {
                Text(stringResource(R.string.organization_streams_message_close))
            }
        },
    )
}

private const val TITLE_LIMIT = OrganizationStreamsViewModel.TITLE_LIMIT
private const val MESSAGE_LIMIT = OrganizationStreamsViewModel.MESSAGE_LIMIT
