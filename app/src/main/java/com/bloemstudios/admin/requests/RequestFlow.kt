package com.bloemstudios.admin.requests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the request pages do with a request, whichever server it is on. [serverId] is null on a single server and
 * names the row's own server in All servers, where actions, history and names are that server's.
 */
class RequestFlow(
    /** Runs the action; false when the server refused it. [reason] is the note, if any. */
    val perform: suspend (action: RequestQueueAction, serverId: String?, request: MediaRequest, reason: String?) -> Boolean,
    val loadEvents: suspend (serverId: String?, request: MediaRequest) -> List<RequestEvent>,
    /** The request as the page now holds it, or null once it has left the page. */
    val current: (serverId: String?, id: String) -> MediaRequest?,
    val requester: (serverId: String?, request: MediaRequest) -> String,
    val serverName: (serverId: String?) -> String? = { null },
    /** The text of the latest refused action, for the details. */
    val actionError: (serverId: String?) -> ErrorText? = { null },
    val isBusy: (serverId: String?, request: MediaRequest) -> Boolean = { _, _ -> false },
)

/** The request open in the details and the action waiting for its note. */
@Stable
class RequestFlowState internal constructor(private val scope: CoroutineScope, private val flow: State<RequestFlow>) {
    var opened by mutableStateOf<OpenRequest?>(null)
        internal set
    var asked by mutableStateOf<AskedAction?>(null)
        internal set

    fun open(serverId: String?, request: MediaRequest) { opened = OpenRequest(serverId, request) }

    /** Decline, cancel and close ask for a note first; the others run at once. */
    fun act(action: RequestQueueAction, serverId: String?, request: MediaRequest) {
        if (action.asksForReason) asked = AskedAction(action, serverId, request)
        else scope.launch { flow.value.perform(action, serverId, request, null) }
    }

    internal fun confirm(asked: AskedAction, note: String) {
        this.asked = null
        scope.launch { flow.value.perform(asked.action, asked.server, asked.request, note) }
    }
}

@Composable
fun rememberRequestFlowState(flow: RequestFlow): RequestFlowState {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(flow)
    return remember(scope) { RequestFlowState(scope, latest) }
}

/** The details and the note prompt the rows open. Place once per page. */
@Composable
fun RequestFlowDialogs(flow: RequestFlow, ui: RequestFlowState) {
    ShellInteractionLockEffect(active = ui.asked != null)
    ui.opened?.let { open ->
        val live = flow.current(open.serverId, open.request.id) ?: open.request
        RequestDetailDialog(
            request = live,
            requester = flow.requester(open.serverId, live),
            loadEvents = { flow.loadEvents(open.serverId, live) },
            perform = { action, reason -> flow.perform(action, open.serverId, live, reason) },
            actionError = flow.actionError(open.serverId)?.resolve(),
            busy = flow.isBusy(open.serverId, live),
            onDismiss = { ui.opened = null },
            serverName = flow.serverName(open.serverId),
        )
    }
    ui.asked?.let { asked ->
        RequestReasonDialog(
            action = asked.action, request = asked.request,
            onConfirm = { note -> ui.confirm(asked, note) },
            onDismiss = { ui.asked = null },
        )
    }
}
