package com.bloemstudios.admin.requests

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.InFlightOperations
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.RequestAction
import com.bloemstudios.admin.network.api.actOnRequest
import com.bloemstudios.admin.network.api.listRequests
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.fanOutToServers
import com.bloemstudios.admin.servers.ServerRequestLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/** Per-server sections and locks let healthy servers continue independently. */
class AggregateRequestsViewModel : ViewModel() {
    private val _sections = MutableStateFlow<List<ServerSectionResult<List<MediaRequest>>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<List<MediaRequest>>>> = _sections
    private val _loadError = MutableStateFlow<ErrorText?>(null)
    val loadError: StateFlow<ErrorText?> = _loadError
    private var acceptedActionGeneration = 0L
    private val _actionError = MutableStateFlow<ErrorText?>(null)
    val actionError: StateFlow<ErrorText?> = _actionError
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage
    private val latestLoad = com.bloemstudios.admin.LatestRequestGate()
    private val locks = ServerRequestLocks()
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys

    fun pendingKey(server: AdminServer, request: MediaRequest): String = "${server.id}:${request.id}"

    @Synchronized
    private fun publishSections(sections: List<ServerSectionResult<List<MediaRequest>>>) {
        _sections.value = sections
        _loadError.value = sections.firstOrNull { it.result.isFailure }?.let { section ->
            val failure = section.result.exceptionOrNull()
            if (failure is ApiError) failure.toErrorText(section.server.displayName, R.string.error_could_not_load_requests)
            else ErrorText.Res(R.string.error_could_not_load_requests)
        }
        _errorMessage.value = _actionError.value ?: _loadError.value
    }

    @Synchronized
    private fun publishResult(result: ServerSectionResult<List<MediaRequest>>, servers: List<AdminServer>? = null) {
        val current = _sections.value
        if (servers == null) {
            publishSections(current.map { if (it.server.id == result.server.id) result else it })
        } else {
            val byId = current.associateBy { it.server.id } + (result.server.id to result)
            publishSections(servers.mapNotNull { byId[it.id] })
        }
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
    fun clearErrorMessage() = dismissActionError()

    /** Servers that said request administration is not available; the poll leaves them alone until a person asks again. */
    private val unavailableServers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A person asked again (pull to refresh): every server is read, including those that said they have no requests. */
    suspend fun lookAgain(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        unavailableServers.clear()
        load(servers, clientFor)
    }

    /**
     * Reads every server's requests. A server that answered `dependency_unavailable` is skipped (its section keeps the
     * failure) until [lookAgain].
     */
    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        val generation = latestLoad.begin()
        synchronized(this) {
            publishSections(_sections.value.filter { row -> servers.any { it.id == row.server.id } })
        }
        val live = servers.filter { it.id !in unavailableServers || _sections.value.none { s -> s.server.id == it.id } }
        fanOutToServers(live, clientFor, locks = locks, onResult = { result ->
            if (result.result.exceptionOrNull().let { it is ApiError && it.isRequestAdminUnavailable }) unavailableServers += result.server.id
            else unavailableServers -= result.server.id
            if (latestLoad.isCurrent(generation)) publishResult(result, servers)
        }) { client -> client.listRequests() }
    }

    // --- Select mode and bulk triage -------------------------------------------------------------------------------

    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting
    private val _selected = MutableStateFlow<Set<String>>(emptySet())

    /** Checked rows, as `serverId:requestId`. */
    val selected: StateFlow<Set<String>> = _selected
    private val _bulk = MutableStateFlow<BulkProgress?>(null)
    val bulk: StateFlow<BulkProgress?> = _bulk

    private val bulkRunning: Boolean get() = _bulk.value?.finished == false

    private fun selectableKeys(): Set<String> = _sections.value.flatMapTo(HashSet()) { section ->
        section.result.getOrNull().orEmpty().filter(RequestQueue::canSelect).map { pendingKey(section.server, it) }
    }

    fun startSelecting(key: String? = null) {
        if (bulkRunning) return
        _selecting.value = true
        _selected.value = key?.takeIf { it in selectableKeys() }?.let(::setOf).orEmpty()
    }

    fun stopSelecting() {
        if (bulkRunning) return
        _selecting.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(key: String) {
        if (!_selecting.value || bulkRunning || key !in selectableKeys()) return
        _selected.update { if (key in it) it - key else it + key }
    }

    fun selectAllShown() {
        if (!_selecting.value || bulkRunning) return
        _selected.value = selectableKeys()
    }

    fun clearSelection() {
        if (!bulkRunning) _selected.value = emptySet()
    }

    fun dismissBulkResult() {
        if (!bulkRunning) _bulk.value = null
    }

    /**
     * Approves or declines every checked request on the server it belongs to, four at a time. Refused requests stay
     * checked with their reason; each server's list is read once when the last answer is in.
     */
    suspend fun bulk(action: BulkRequestAction, reason: String?, servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        if (bulkRunning) return
        val byKey = HashMap<String, Pair<AdminServer, MediaRequest>>()
        _sections.value.forEach { section ->
            section.result.getOrNull().orEmpty().filter(RequestQueue::canSelect).forEach { byKey[pendingKey(section.server, it)] = section.server to it }
        }
        val targets = _selected.value.mapNotNull { key -> byKey[key]?.let { (_, request) -> BulkTarget(key, request) } }
        if (targets.isEmpty()) return
        val note = reason?.trim()?.takeIf { it.isNotEmpty() && action == BulkRequestAction.Decline }
        val result = runBulkRequests(action, targets, onProgress = { _bulk.value = it }) { target ->
            val server = byKey.getValue(target.key).first
            operations.runResult<ErrorText?>(target.key, ErrorText.Prefixed(server.displayName, ErrorText.Res(R.string.requests_bulk_busy))) {
                try {
                    clientFor(server).actOnRequest(target.request.id, action.queueAction.requestAction, note)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    e.toErrorText(server.displayName, R.string.error_action_failed)
                } catch (e: Exception) {
                    ErrorText.Prefixed(server.displayName, ErrorText.Res(R.string.error_action_failed))
                }
            }
        }
        val refused = result.failures.mapTo(HashSet()) { it.key }
        _selected.value = refused
        _selecting.value = refused.isNotEmpty()
        _bulk.value = result
        val touched = targets.mapNotNull { byKey[it.key]?.first }.distinctBy { it.id }
        touched.forEach { server -> runCatching { retrySection(server, clientFor) }.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
        // A read can drop requests that are gone; the checked rows follow.
        val still = selectableKeys()
        _selected.update { keys -> keys.filterTo(HashSet()) { it in still } }
    }

    suspend fun approve(server: AdminServer, request: MediaRequest, clientFor: (AdminServer) -> ApiClient) = act(server, request, clientFor, RequestAction.APPROVE)
    suspend fun decline(server: AdminServer, request: MediaRequest, reason: String? = null, clientFor: (AdminServer) -> ApiClient) = act(server, request, clientFor, RequestAction.DECLINE, reason)
    suspend fun retryRequest(server: AdminServer, request: MediaRequest, clientFor: (AdminServer) -> ApiClient) = act(server, request, clientFor, RequestAction.RETRY)
    suspend fun cancel(server: AdminServer, request: MediaRequest, reason: String? = null, clientFor: (AdminServer) -> ApiClient) = act(server, request, clientFor, RequestAction.CANCEL, reason)

    /** Runs [action] on the row's own server. A blank note is not sent, nor is one for an action that takes none. */
    suspend fun perform(action: RequestQueueAction, server: AdminServer, request: MediaRequest, reason: String? = null, clientFor: (AdminServer) -> ApiClient): Boolean =
        act(server, request, clientFor, action.requestAction, reason?.trim()?.takeIf { it.isNotEmpty() && action.asksForReason })

    /** Account ID to name per server, for the "requested by" line; a server without a list says "User 12". */
    private val _names = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val names: StateFlow<Map<String, Map<String, String>>> = _names

    /** Best effort and once per server: reads each server's account list that has none yet. */
    suspend fun loadNames(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        for (server in servers) {
            if (server.id in _names.value) continue
            val users = try {
                clientFor(server).listUsers()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue
            }
            _names.update { it + (server.id to users.associate { user -> user.id to user.username }) }
        }
    }

    private suspend fun act(server: AdminServer, request: MediaRequest, clientFor: (AdminServer) -> ApiClient, action: RequestAction, reason: String? = null): Boolean =
        operations.runResult(pendingKey(server, request), false) {
            val generation = beginAcceptedAction()
            var accepted = false
            // Acquire the entity guard before waiting for this server's poll lock.
            locks.forServer(server).withLock {
                val client = try {
                    clientFor(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    publishActionError(generation, e.toErrorText(server.displayName, R.string.error_action_failed))
                    return@withLock
                } catch (e: Exception) {
                    publishActionError(generation, ErrorText.Res(R.string.error_action_failed))
                    return@withLock
                }
                try {
                    client.actOnRequest(request.id, action, reason)
                    publishActionError(generation, null)
                    accepted = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    publishActionError(generation, e.toErrorText(server.displayName, R.string.error_action_failed))
                } catch (e: Exception) {
                    publishActionError(generation, ErrorText.Res(R.string.error_action_failed))
                }
                reloadOneLocked(server, client)
            }
            accepted
        }

    /** Retries the list, rather than the request's administrative mutation. */
    suspend fun retrySection(server: AdminServer, clientFor: (AdminServer) -> ApiClient) = locks.forServer(server).withLock {
        reloadOneLocked(server, clientFor(server))
    }

    private suspend fun reloadOneLocked(server: AdminServer, client: ApiClient) {
        val result = runCatching { client.listRequests() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        publishResult(ServerSectionResult(server, result))
    }
}
