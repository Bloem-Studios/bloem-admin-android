package com.bloemstudios.admin.requests

import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listRequestEvents
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * Aggregate ("All Servers") counterpart to RequestsScreen: the same four views and rows, one section per server.
 * Each server's requests are read whole, so the views and their counts are worked out here with the web's rule;
 * actions, history and names run against the row's own server.
 */
@Composable
fun AggregateRequestsScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    onSignIn: (AdminServer) -> Unit
) {
    val viewModel = viewModel<AggregateRequestsViewModel>()
    val sections by viewModel.sections.collectAsState()
    val names by viewModel.names.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }
    val res = rememberResources()

    // The menu's count follows this page's: read it again whenever the waiting total changes.
    val nudgeCount = LocalRequestCountsNudge.current
    val waitingTotal = sections.sumOf { RequestQueue.countsOf(it.result.getOrNull().orEmpty()).needs_approval }
    LaunchedEffect(waitingTotal) { if (sections.isNotEmpty()) nudgeCount() }

    // On open and on pull: the events socket has no channel for the request queue.
    ForegroundEffect(servers) {
        viewModel.load(servers, clientFor)
        viewModel.loadNames(servers, clientFor)
    }

    fun serverFor(id: String?) = servers.first { it.id == id }
    val flow = RequestFlow(
        perform = { action, serverId, request, reason -> viewModel.perform(action, serverFor(serverId), request, reason, clientFor) },
        loadEvents = { serverId, request -> clientFor(serverFor(serverId)).listRequestEvents(request.id) },
        current = { serverId, id -> sections.firstOrNull { it.server.id == serverId }?.result?.getOrNull()?.firstOrNull { it.id == id } },
        requester = { serverId, request -> RequestQueue.requesterLabel(res, request, names[serverId].orEmpty()) },
        serverName = { serverId -> servers.firstOrNull { it.id == serverId }?.displayName },
        actionError = { _ -> actionError },
        isBusy = { serverId, request -> viewModel.pendingKey(serverFor(serverId), request) in pendingKeys },
    )
    val ui = rememberRequestFlowState(flow)

    val selecting by viewModel.selecting.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val bulkProgress by viewModel.bulk.collectAsState()
    val bulkCallbacks = RequestBulkCallbacks(
        onStart = { key -> viewModel.startSelecting(key) },
        onToggle = viewModel::toggleSelected,
        onSelectAll = viewModel::selectAllShown,
        onClear = viewModel::clearSelection,
        onDone = { viewModel.stopSelecting(); viewModel.dismissBulkResult() },
        onApprove = { reads.launch { viewModel.bulk(BulkRequestAction.Approve, null, servers, clientFor) } },
        onDecline = { note -> reads.launch { viewModel.bulk(BulkRequestAction.Decline, note, servers, clientFor) } },
        onDismissResult = viewModel::dismissBulkResult,
    )

    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.lookAgain(servers, clientFor) }
    ) {
        AggregateRequestsContent(
            sections = sections,
            names = names,
            actionError = actionError,
            pendingKeys = pendingKeys,
            pendingKey = viewModel::pendingKey,
            onAction = ui::act,
            onOpen = ui::open,
            onSignIn = onSignIn,
            onRetrySection = { server -> reads.launch { viewModel.retrySection(server, clientFor) } },
            onDismissError = viewModel::dismissActionError,
            selecting = selecting,
            selected = selected,
            bulkProgress = bulkProgress,
            bulk = bulkCallbacks,
        )
    }
    RequestFlowDialogs(flow, ui)
}

/** The All servers queue: the views with their summed counts, then one section of rows per server. Stateless. */
@Composable
fun AggregateRequestsContent(
    sections: List<ServerSectionResult<List<MediaRequest>>>,
    names: Map<String, Map<String, String>>,
    actionError: ErrorText?,
    pendingKeys: Set<String>,
    pendingKey: (AdminServer, MediaRequest) -> String,
    onAction: (RequestQueueAction, String?, MediaRequest) -> Unit,
    onOpen: (String?, MediaRequest) -> Unit,
    onSignIn: (AdminServer) -> Unit,
    onRetrySection: (AdminServer) -> Unit,
    onDismissError: () -> Unit,
    /** Select mode and bulk actions on Needs approval; keys are `serverId:requestId`. */
    selecting: Boolean = false,
    selected: Set<String> = emptySet(),
    bulkProgress: BulkProgress? = null,
    bulk: RequestBulkCallbacks? = null,
) {
    val res = rememberResources()
    val counts = sections.fold(RequestCounts()) { total, section ->
        total + RequestQueue.countsOf(section.result.getOrNull().orEmpty())
    }
    // Opens on what needs approval anywhere, else what is under way, until a view is picked.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val view = RequestQueueView.entries.firstOrNull { it.wire == picked } ?: RequestQueue.defaultView(counts)
    val selectable = sections.sumOf { it.result.getOrNull().orEmpty().count(RequestQueue::canSelect) }

    LazyColumn(
        Modifier.fillMaxSize().testTag(REQUEST_LIST_TAG),
        contentPadding = PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        item(key = "views") {
            RequestViewPicker(view, counts, onSelect = { next ->
                if (next != view) bulk?.onDone?.invoke()
                picked = next.wire
            })
        }
        if (bulk != null && view == RequestQueueView.NeedsApproval && (selectable > 0 || selecting || bulkProgress != null)) {
            item(key = "bulk") { RequestBulkSection(selecting, selectable, selected.size, bulkProgress, bulk) }
        }
        if (actionError != null) item(key = "action-error") {
            VeilStateSurface(
                kind = VeilStateSurfaceKind.PartialFailure,
                title = stringResource(R.string.error_action_failed), message = actionError.resolve(),
                stateDescription = stringResource(R.string.error_action_failed),
                action = VeilStateSurfaceAction(stringResource(R.string.action_dismiss), onDismissError),
            )
        }
        sections.forEach { section ->
            val server = section.server
            item(key = "server:${server.id}") {
                ServerGroupHeader(server.displayName)
            }
            val requests = section.result.getOrNull()
            val failure = section.result.exceptionOrNull()
            if (failure != null) {
                item(key = "failure:${server.id}") {
                    val isAuthFailure = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
                    if (failure is ApiError && failure.isRequestAdminUnavailable) {
                        // Nothing to retry: the server has no request service.
                        Text(
                            stringResource(R.string.requests_unavailable_title), style = MaterialTheme.typography.bodyMedium,
                            color = VeilColors.Muted, modifier = Modifier.testTag(REQUEST_UNAVAILABLE_TAG),
                        )
                    }
                    else if (isAuthFailure) ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(server) })
                    else ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, server.displayName), { onRetrySection(server) })
                }
            } else {
                val rows = requests.orEmpty().filter { RequestQueue.view(it) == view }
                if (rows.isEmpty()) item(key = "empty:${server.id}") {
                    Text(stringResource(view.empty), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                } else items(rows, key = { "${server.id}:${it.id}" }) { request ->
                    RequestQueueRow(
                        request = request,
                        requester = RequestQueue.requesterLabel(res, request, names[server.id].orEmpty()),
                        busy = pendingKey(server, request) in pendingKeys,
                        onAction = { action -> onAction(action, server.id, request) },
                        onOpen = { onOpen(server.id, request) },
                        tag = "$REQUEST_ROW_TAG_PREFIX${server.id}:${request.id}",
                        selection = if (bulk != null && selecting && RequestQueue.canSelect(request)) {
                            val key = pendingKey(server, request)
                            RequestRowSelection(key in selected) { bulk.onToggle(key) }
                        } else null,
                        onSelectStart = if (bulk != null && view == RequestQueueView.NeedsApproval && RequestQueue.canSelect(request))
                            ({ bulk.onStart(pendingKey(server, request)) }) else null,
                    )
                }
            }
        }
    }
}
