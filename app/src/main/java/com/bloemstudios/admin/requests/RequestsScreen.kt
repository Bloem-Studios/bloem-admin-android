package com.bloemstudios.admin.requests

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listRequestEvents
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.links.DeepLinkItems
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.delay

/** How long typing must pause before the search runs, as on the web. */
private const val SEARCH_DEBOUNCE_MILLIS = 300L

/** A server's Requests page: the web admin's request queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(client: ApiClient) {
    val viewModel = viewModel<RequestsViewModel>()
    val state by viewModel.state.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    val pullState = rememberPullToRefreshState()

    val reads = rememberForegroundReadLauncher()
    val res = rememberResources()

    // The menu's count follows this page's: read it again whenever this page's own count changes.
    val nudgeCount = LocalRequestCountsNudge.current
    LaunchedEffect(state.counts?.needs_approval) { if (state.counts != null) nudgeCount() }

    // The server may have no request administration; then there is nothing to read, and nothing to poll.
    // The first read opens the queue on the right view. After it the queue is read on pull and after this page's own
    // actions: the events socket has no channel for requests, so no timer stands in for one.
    val unavailable = state.unavailable
    ForegroundEffect(client, unavailable) {
        viewModel.checkAvailability(client)
        if (unavailable || viewModel.state.value.unavailable) return@ForegroundEffect
        viewModel.load(client)
    }
    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try {
                    // A person asking again looks for request administration afresh.
                    if (viewModel.state.value.unavailable) viewModel.allowRetry() else viewModel.load(client)
                    // Pulling asks the menu's badge again too.
                    nudgeCount()
                } finally { pullState.endRefresh() }
            }
        }
    }
    val reload = { reads.launch { viewModel.load(client) }; Unit }
    val bulkCallbacks = RequestBulkCallbacks(
        onStart = { key -> viewModel.startSelecting(key) },
        onToggle = viewModel::toggleSelected,
        onSelectAll = viewModel::selectAllShown,
        onClear = viewModel::clearSelection,
        onDone = { viewModel.stopSelecting(); viewModel.dismissBulkResult() },
        onApprove = { reads.launch { viewModel.bulk(BulkRequestAction.Approve, null, client) } },
        onDecline = { note -> reads.launch { viewModel.bulk(BulkRequestAction.Decline, note, client) } },
        onDismissResult = viewModel::dismissBulkResult,
    )

    val flow = RequestFlow(
        perform = { action, _, request, reason -> viewModel.perform(action, request, client, reason) },
        loadEvents = { _, request -> client.listRequestEvents(request.id) },
        current = { _, id -> state.requests.firstOrNull { it.id == id } },
        requester = { _, request -> RequestQueue.requesterLabel(res, request, state.requesterNames) },
        actionError = { _ -> actionError },
        isBusy = { _, request -> request.id in pendingKeys },
    )
    val ui = rememberRequestFlowState(flow)

    // A widget row opens this page with that request's details showing, once, when the queue has it.
    var linkedRequestId by remember { mutableStateOf(DeepLinkItems.take("work.requests")) }
    LaunchedEffect(linkedRequestId, state.requests) {
        val id = linkedRequestId ?: return@LaunchedEffect
        state.requests.firstOrNull { it.id == id }?.let { ui.open(null, it); linkedRequestId = null }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        RequestsContent(
            state = state,
            loadError = loadError,
            actionError = actionError,
            pendingKeys = pendingKeys,
            onSelectView = { view -> viewModel.select(view); reload() },
            onSearch = { text -> if (text != viewModel.state.value.search) { viewModel.setSearch(text); reload() } },
            onMedia = { filter -> if (filter != viewModel.state.value.media) { viewModel.setMedia(filter); reload() } },
            onFilterUser = { id -> viewModel.filterByUser(id); reload() },
            onClearUserFilter = { viewModel.clearUserFilter(); reload() },
            onAction = ui::act,
            onOpen = ui::open,
            onLoadMore = { reads.launch { viewModel.loadMore(client) } },
            onRetry = { pullState.startRefresh() },
            onDismissError = viewModel::dismissActionError,
            bulk = bulkCallbacks,
        )
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
    RequestFlowDialogs(flow, ui)
}

/**
 * The queue: its views with their counts, a search and media-type filter, the account filter, and one row per
 * request with the actions its view allows. Stateless; [RequestsScreen] feeds it from the view model.
 */
@Composable
fun RequestsContent(
    state: RequestQueueState,
    loadError: ErrorText?,
    actionError: ErrorText?,
    pendingKeys: Set<String>,
    onSelectView: (RequestQueueView) -> Unit,
    onSearch: (String) -> Unit,
    onMedia: (RequestMediaFilter) -> Unit,
    onFilterUser: (String) -> Unit,
    onClearUserFilter: () -> Unit,
    onAction: (RequestQueueAction, String?, MediaRequest) -> Unit,
    onOpen: (String?, MediaRequest) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    /** Select mode and bulk actions on Needs approval; null where the page offers none. */
    bulk: RequestBulkCallbacks? = null,
) {
    val res = rememberResources()
    LazyColumn(
        Modifier.fillMaxSize().testTag(REQUEST_LIST_TAG),
        contentPadding = PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        // Without request administration there is no queue to show, and nothing to retry.
        if (state.unavailable) {
            item(key = "unavailable") { RequestUnavailableNote() }
            return@LazyColumn
        }
        item(key = "views") { RequestViewPicker(state.view, state.counts, onSelectView) }
        item(key = "filters") {
            RequestFilters(state, onSearch, onMedia, onClearUserFilter)
        }
        if (state.refreshFailed && state.requests.isNotEmpty()) item(key = "stale") { RequestStaleLine(state.lastUpdated) }
        val selectable = state.requests.count(RequestQueue::canSelect)
        if (bulk != null && state.view == RequestQueueView.NeedsApproval && (selectable > 0 || state.selecting || state.bulk != null)) {
            item(key = "bulk") {
                RequestBulkSection(state.selecting, selectable, state.selectedRows.size, state.bulk, bulk)
            }
        }
        if (actionError != null) item(key = "action-error") {
            VeilStateSurface(
                kind = VeilStateSurfaceKind.PartialFailure,
                title = stringResource(R.string.error_action_failed), message = actionError.resolve(),
                stateDescription = stringResource(R.string.error_action_failed),
                action = VeilStateSurfaceAction(stringResource(R.string.action_dismiss), onDismissError),
            )
        }
        when {
            !state.hasLoaded -> item(key = "loading") {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Loading, title = "", message = "",
                    stateDescription = stringResource(R.string.management_loading),
                )
            }
            loadError != null && state.requests.isEmpty() -> item(key = "failed") {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Failure,
                    title = stringResource(R.string.requests_load_failed_title),
                    message = loadError.resolve(),
                    stateDescription = stringResource(R.string.requests_load_failed_detail),
                    action = VeilStateSurfaceAction(stringResource(R.string.action_retry), onRetry),
                )
            }
            state.requests.isEmpty() -> item(key = "empty") {
                if (state.isFiltered) VeilStateSurface(
                    kind = VeilStateSurfaceKind.Empty,
                    title = stringResource(R.string.requests_no_matches_title),
                    message = stringResource(R.string.requests_no_matches_detail),
                    stateDescription = stringResource(R.string.requests_no_matches_title),
                ) else VeilStateSurface(
                    kind = VeilStateSurfaceKind.Empty,
                    title = stringResource(R.string.requests_all_clear),
                    message = stringResource(state.view.empty),
                    stateDescription = stringResource(state.view.empty),
                )
            }
            else -> {
                items(state.requests, key = { it.id }) { request ->
                    RequestQueueRow(
                        request = request,
                        requester = RequestQueue.requesterLabel(res, request, state.requesterNames),
                        busy = request.id in pendingKeys,
                        onAction = { action -> onAction(action, null, request) },
                        onOpen = { onOpen(null, request) },
                        onFilterUser = request.requested_by_user_id?.let { id -> { onFilterUser(id) } },
                        selection = if (bulk != null && state.selecting && RequestQueue.canSelect(request))
                            RequestRowSelection(request.id in state.selected) { bulk.onToggle(request.id) } else null,
                        onSelectStart = if (bulk != null && state.view == RequestQueueView.NeedsApproval && RequestQueue.canSelect(request))
                            ({ bulk.onStart(request.id) }) else null,
                    )
                }
                if (state.moreFailed) item(key = "more-failed") {
                    Text(stringResource(R.string.requests_more_failed), style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger)
                }
                if (state.nextCursor != null) item(key = "more") {
                    TextButton(
                        onClick = onLoadMore, enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(REQUEST_LOAD_MORE_TAG),
                    ) {
                        Text(stringResource(if (state.loadingMore) R.string.management_loading else R.string.requests_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestFilters(
    state: RequestQueueState,
    onSearch: (String) -> Unit,
    onMedia: (RequestMediaFilter) -> Unit,
    onClearUserFilter: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(state.search) }
    // The typed text is the field's own until typing pauses; then the queue reads with it.
    LaunchedEffect(text) {
        if (text.trim() != state.search.trim()) {
            delay(SEARCH_DEBOUNCE_MILLIS)
            onSearch(text.trim())
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            singleLine = true,
            label = { Text(stringResource(R.string.requests_search_label)) },
            placeholder = { Text(stringResource(R.string.requests_search_placeholder)) },
            leadingIcon = { LucideIcon(R.drawable.lucide_search, contentDescription = null) },
            trailingIcon = if (text.isEmpty()) null else ({
                IconButton(onClick = { text = "" }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    LucideIcon(R.drawable.lucide_x, contentDescription = stringResource(R.string.requests_search_clear))
                }
            }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.testTag("request-media-filter")) {
            RequestMediaFilter.entries.forEach { filter ->
                RequestChip(selected = filter == state.media, onClick = { onMedia(filter) }, role = Role.RadioButton,
                    modifier = Modifier.testTag("request-media:" + (filter.queryValue ?: "all"))) {
                    Text(stringResource(filter.label), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
                }
            }
        }
        state.userFilter?.let { id ->
            val name = state.requesterNames[id] ?: stringResource(R.string.requests_user_number, id)
            val clear = stringResource(R.string.requests_clear_user_filter)
            RequestChip(selected = true, onClick = onClearUserFilter, role = Role.Button,
                modifier = Modifier.testTag("request-user-filter")) {
                Text(stringResource(R.string.requests_requested_by_filter, name), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
                LucideIcon(R.drawable.lucide_x, contentDescription = clear, size = LucideSize.Inline, modifier = Modifier.padding(start = VeilSpacing.Tiny))
            }
        }
    }
}
