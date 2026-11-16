package com.bloemstudios.admin.requests

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.InFlightOperations
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.actOnRequest
import com.bloemstudios.admin.network.api.getRequestCapabilities
import com.bloemstudios.admin.network.api.getRequestCounts
import com.bloemstudios.admin.network.api.listRequestQueuePage
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** What one server's queue shows: the chosen view and filters, the counts, and the rows loaded so far. */
data class RequestQueueState(
    val view: RequestQueueView = RequestQueueView.NeedsApproval,
    val search: String = "",
    val media: RequestMediaFilter = RequestMediaFilter.All,
    /** The account whose requests are shown alone; null for every account. */
    val userFilter: String? = null,
    val counts: RequestCounts? = null,
    val requests: List<MediaRequest> = emptyList(),
    /** Where the next page starts; null on the last page. */
    val nextCursor: String? = null,
    val hasLoaded: Boolean = false,
    val loadingMore: Boolean = false,
    /** A failed next page: the rows already shown stay. */
    val moreFailed: Boolean = false,
    /** Account ID to name, for the "requested by" line. */
    val requesterNames: Map<String, String> = emptyMap(),
    /** Select mode on Needs approval: rows carry a check box and Approve N / Decline N act on [selected]. */
    val selecting: Boolean = false,
    /** IDs of the checked rows; a request that is no longer shown is dropped on the next read. */
    val selected: Set<String> = emptySet(),
    /** The latest bulk run: running while it is not finished, then its failures until dismissed. */
    val bulk: BulkProgress? = null,
    /** The server has no request administration; nothing is read until a person asks again. */
    val unavailable: Boolean = false,
    /** When the rows were last read well. */
    val lastUpdated: Instant? = null,
    /** The latest read failed while older rows stay on screen. */
    val refreshFailed: Boolean = false,
) {
    val bulkRunning: Boolean get() = bulk?.finished == false

    /** The rows a bulk action can take: shown, checked, and still waiting for an approval. */
    val selectedRows: List<MediaRequest> get() = requests.filter { it.id in selected && RequestQueue.canSelect(it) }

    /** True while any filter narrows the view, so an empty page says "No matching requests". */
    val isFiltered: Boolean get() = search.isNotBlank() || media != RequestMediaFilter.All || userFilter != null

    /** Rows past the first page were loaded, so a background refresh would drop them. */
    val hasLoadedMore: Boolean get() = requests.size > com.bloemstudios.admin.network.api.REQUEST_PAGE_LIMIT
}

/**
 * One server's request queue, as the web admin's Requests page shows it: four views with their counts, a
 * title or TMDB ID search, a media-type filter, an optional "requested by" filter, one page at a time, and
 * the approve / decline / retry / cancel actions.
 */
class RequestsViewModel : ViewModel() {
    private val latestLoad = LatestRequestGate()
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    private val _state = MutableStateFlow(RequestQueueState())
    val state: StateFlow<RequestQueueState> = _state
    /** True once a person picked a view; until then each load opens on [RequestQueue.defaultView]. */
    private var viewChosen = false
    private var namesLoaded = false
    private val _loadError = MutableStateFlow<ErrorText?>(null)
    val loadError: StateFlow<ErrorText?> = _loadError
    private var acceptedActionGeneration = 0L
    private val _actionError = MutableStateFlow<ErrorText?>(null)
    val actionError: StateFlow<ErrorText?> = _actionError
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    @Synchronized
    private fun publishLoadError(error: ErrorText?) {
        _loadError.value = error
        _errorMessage.value = _actionError.value ?: error
    }

    @Synchronized
    private fun beginAcceptedAction(): Long = ++acceptedActionGeneration

    @Synchronized
    private fun publishActionError(generation: Long, error: ErrorText?) {
        if (generation != acceptedActionGeneration) return
        _actionError.value = error
        _errorMessage.value = error ?: _loadError.value
    }

    @Synchronized
    fun dismissActionError() {
        _actionError.value = null
        _errorMessage.value = _loadError.value
    }

    /** Reads the time for "last updated"; replaced in tests. */
    internal var clock: () -> Instant = Instant::now

    fun select(view: RequestQueueView) {
        viewChosen = true
        _state.update {
            if (it.view == view) it else it.copy(view = view, selecting = false, selected = emptySet())
        }
    }

    /** Opens select mode on Needs approval, with [id] checked when it can be. A running bulk action is left alone. */
    fun startSelecting(id: String? = null) = _state.update {
        if (it.view != RequestQueueView.NeedsApproval || it.bulkRunning) it
        else it.copy(selecting = true, selected = id?.takeIf { row -> it.requests.any { r -> r.id == row && RequestQueue.canSelect(r) } }?.let(::setOf).orEmpty())
    }

    fun stopSelecting() = _state.update { if (it.bulkRunning) it else it.copy(selecting = false, selected = emptySet()) }

    fun toggleSelected(id: String) = _state.update {
        if (!it.selecting || it.bulkRunning || it.requests.none { r -> r.id == id && RequestQueue.canSelect(r) }) it
        else it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    /** Checks every row shown that can be approved. */
    fun selectAllShown() = _state.update {
        if (!it.selecting || it.bulkRunning) it else it.copy(selected = it.requests.filter(RequestQueue::canSelect).mapTo(HashSet()) { r -> r.id })
    }

    fun clearSelection() = _state.update { if (it.bulkRunning) it else it.copy(selected = emptySet()) }

    /** Forgets the last bulk run's result; what is still checked stays checked. */
    fun dismissBulkResult() = _state.update { if (it.bulkRunning) it else it.copy(bulk = null) }

    /**
     * Approves or declines every checked request, four at a time, through the per-request routes. A refusal does not
     * stop the rest. The queue is read once when the last answer is in; requests the server refused stay checked with
     * their reason in [RequestQueueState.bulk]. [reason] is the decline note, sent to each request when not blank.
     */
    suspend fun bulk(action: BulkRequestAction, reason: String?, client: ApiClient) {
        val rows = _state.value.takeIf { !it.bulkRunning }?.selectedRows.orEmpty()
        if (rows.isEmpty()) return
        val note = reason?.trim()?.takeIf { it.isNotEmpty() && action == BulkRequestAction.Decline }
        val result = runBulkRequests(
            action, rows.map { BulkTarget(it.id, it) },
            onProgress = { progress -> _state.update { it.copy(bulk = progress) } },
        ) { target ->
            operations.runResult<ErrorText?>(target.request.id, ErrorText.Res(R.string.requests_bulk_busy)) {
                try {
                    client.actOnRequest(target.request.id, action.queueAction.requestAction, note)
                    null
                } catch (e: ApiError) {
                    e.toErrorText(R.string.error_action_failed)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ErrorText.Res(R.string.error_action_failed)
                }
            }
        }
        val refused = result.failures.mapTo(HashSet()) { it.key }
        _state.update { it.copy(selected = refused, selecting = refused.isNotEmpty(), bulk = result) }
        load(client)
    }

    /**
     * Asks whether the server has request administration at all, once, before the first read. A server that says no, or
     * does not answer yes, is marked [RequestQueueState.unavailable]. Any other failure is left for the read to report.
     */
    suspend fun checkAvailability(client: ApiClient) {
        try {
            if (!client.getRequestCapabilities().available) _state.update { it.copy(unavailable = true, hasLoaded = true) }
        } catch (e: ApiError) {
            if (e.isRequestAdminUnavailable) _state.update { it.copy(unavailable = true, hasLoaded = true) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /** A person asked again (pull to refresh): look for request administration afresh. */
    fun allowRetry() = _state.update { it.copy(unavailable = false) }

    fun setSearch(text: String) = _state.update { if (it.search == text) it else it.copy(search = text) }
    fun setMedia(filter: RequestMediaFilter) = _state.update { it.copy(media = filter) }
    fun filterByUser(id: String) = _state.update { it.copy(userFilter = id) }
    fun clearUserFilter() = _state.update { it.copy(userFilter = null) }

    /**
     * Counts and the first page of the current view. Opens on what needs approval, else what is under way,
     * until a person picks a view. A failure keeps the rows already shown.
     */
    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val counts = client.getRequestCounts()
            if (!latestLoad.isCurrent(generation)) return
            val filter = _state.value
            val view = if (viewChosen) filter.view else RequestQueue.defaultView(counts)
            val page = client.listRequestQueuePage(view, filter.search, filter.media.queryValue, filter.userFilter)
            if (!latestLoad.isCurrent(generation)) return
            val now = clock()
            _state.update {
                val stillSelectable = page.items.filter(RequestQueue::canSelect).mapTo(HashSet()) { row -> row.id }
                it.copy(
                    view = view, counts = counts, requests = page.items, nextCursor = page.nextCursor,
                    hasLoaded = true, moreFailed = false, loadingMore = false,
                    selecting = it.selecting && view == RequestQueueView.NeedsApproval,
                    selected = if (view == RequestQueueView.NeedsApproval) it.selected.filterTo(HashSet()) { id -> id in stillSelectable } else emptySet(),
                    unavailable = false, lastUpdated = now, refreshFailed = false,
                )
            }
            publishLoadError(null)
        } catch (e: ApiError) {
            if (latestLoad.isCurrent(generation)) {
                _state.update { it.copy(hasLoaded = true, refreshFailed = true, unavailable = e.isRequestAdminUnavailable) }
                publishLoadError(e.toErrorText(R.string.error_could_not_load_requests))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (latestLoad.isCurrent(generation)) {
                _state.update { it.copy(hasLoaded = true, refreshFailed = true) }
                publishLoadError(ErrorText.Res(R.string.error_could_not_load_requests))
            }
        }
        loadNamesIfNeeded(client)
    }

    /**
     * A background refresh: the whole first page while nothing more was loaded, else only the counts, so a
     * person reading further down keeps their place.
     */
    suspend fun refresh(client: ApiClient) {
        if (!_state.value.hasLoadedMore) return load(client)
        try {
            val counts = client.getRequestCounts()
            _state.update { it.copy(counts = counts) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(refreshFailed = true, unavailable = e is ApiError && e.isRequestAdminUnavailable) }
        }
    }

    suspend fun loadMore(client: ApiClient) {
        val before = _state.value
        val cursor = before.nextCursor ?: return
        if (before.loadingMore) return
        val generation = latestLoad.current()
        _state.update { it.copy(loadingMore = true) }
        try {
            val page = client.listRequestQueuePage(before.view, before.search, before.media.queryValue, before.userFilter, cursor)
            if (!latestLoad.isCurrent(generation)) return
            _state.update {
                val known = it.requests.mapTo(HashSet()) { row -> row.id }
                it.copy(
                    requests = it.requests + page.items.filter { row -> row.id !in known },
                    nextCursor = page.nextCursor, moreFailed = false
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (latestLoad.isCurrent(generation)) _state.update { it.copy(moreFailed = true) }
        } finally {
            _state.update { it.copy(loadingMore = false) }
        }
    }

    suspend fun approve(request: MediaRequest, client: ApiClient): Boolean = perform(RequestQueueAction.Approve, request, client)
    suspend fun decline(request: MediaRequest, client: ApiClient, reason: String? = null): Boolean =
        perform(RequestQueueAction.Decline, request, client, reason)
    suspend fun retry(request: MediaRequest, client: ApiClient): Boolean = perform(RequestQueueAction.Retry, request, client)
    suspend fun cancel(request: MediaRequest, client: ApiClient, reason: String? = null): Boolean =
        perform(RequestQueueAction.Cancel, request, client, reason)

    /**
     * Runs one action, then reloads the counts and the view so the request moves to where it now belongs.
     * Returns false when the server refused it (the reason is in [actionError]) or another action on the
     * same request is still running. A blank note is not sent, nor is one for an action that takes none.
     */
    suspend fun perform(action: RequestQueueAction, request: MediaRequest, client: ApiClient, reason: String? = null): Boolean =
        operations.runResult(request.id, false) {
            val generation = beginAcceptedAction()
            val note = reason?.trim()?.takeIf { it.isNotEmpty() && action.asksForReason }
            try {
                client.actOnRequest(request.id, action.requestAction, note)
                publishActionError(generation, null)
            } catch (e: ApiError) {
                publishActionError(generation, e.toErrorText(R.string.error_action_failed))
                return@runResult false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publishActionError(generation, ErrorText.Res(R.string.error_action_failed))
                return@runResult false
            }
            // Reconciliation has its own error channel; success above remains success.
            load(client)
            true
        }

    private suspend fun loadNamesIfNeeded(client: ApiClient) {
        if (namesLoaded) return
        // Best effort: without the account list the line says "User 12".
        val users = try {
            client.listUsers()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return
        }
        namesLoaded = true
        _state.update { it.copy(requesterNames = users.associate { user -> user.id to user.username }) }
    }
}
