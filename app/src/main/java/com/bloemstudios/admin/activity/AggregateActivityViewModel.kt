package com.bloemstudios.admin.activity

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listPlaybackHistory
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.fanOutToServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import com.bloemstudios.admin.servers.ServerRequestLocks
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock

/** Independent server sections with pagination preserved across background polls. */
class AggregateActivityViewModel : ViewModel() {
    private val _sections = MutableStateFlow<List<ServerSectionResult<List<PlaybackHistoryEntry>>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<List<PlaybackHistoryEntry>>>> = _sections

    private val _hasMoreByServer = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hasMoreByServer: StateFlow<Map<String, Boolean>> = _hasMoreByServer

    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    private val loadedMore = ConcurrentHashMap.newKeySet<String>()
    private val cursorByServer = ConcurrentHashMap<String, String>()

    private val locks = ServerRequestLocks()
    private val latestLoad = com.bloemstudios.admin.LatestRequestGate()

    private var activeFilter: String? = null

    /** Loads (or reloads) page 1 for every server, resetting pagination for all of them. */
    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient, userId: String? = null) {
        val filter = normalize(userId)
        val generation = latestLoad.begin()
        if (activeFilter != filter) {
            activeFilter = filter
            _sections.value = emptyList()
        }
        _hasMoreByServer.value = emptyMap()
        _errorMessage.value = null
        cursorByServer.clear()
        loadedMore.clear()
        _sections.update { current -> current.filter { row -> servers.any { it.id == row.server.id } } }
        fanOutToServers(servers, clientFor, locks = locks, onResult = { section ->
            if (latestLoad.isCurrent(generation)) {
                val collection = section.result.getOrNull()
                loadedMore.remove(section.server.id)
                setCursor(section.server.id, collection?.page?.takeIf { it.has_more }?.next_cursor)
                val row = ServerSectionResult(section.server, section.result.map { it.items })
                _sections.update { current ->
                    val byId = current.associateBy { it.server.id } + (row.server.id to row)
                    servers.mapNotNull { byId[it.id] }
                }
            }
        }) { client -> client.listPlaybackHistory(filter, cursor = null) }
    }

    /** Appends the next page for just [server]'s section -- other sections are untouched. */
    suspend fun loadMore(server: AdminServer, clientFor: (AdminServer) -> ApiClient, userId: String? = null) {
        val generation = latestLoad.current()
        val filter = normalize(userId)
        locks.forServer(server).withLock {
            if (!latestLoad.isCurrent(generation)) return@withLock
            val cursor = cursorByServer[server.id]
            if (filter != activeFilter || cursor == null) return@withLock
            val result = runCatching { clientFor(server).listPlaybackHistory(filter, cursor) }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (!latestLoad.isCurrent(generation)) return@withLock
            result.onSuccess { page ->
                _sections.update { current ->
                    current.map { section ->
                        if (section.server.id == server.id) {
                            val existing = section.result.getOrNull().orEmpty()
                            val known = existing.mapTo(HashSet()) { it.session_id }
                            ServerSectionResult(server, Result.success(existing + page.items.filter { it.session_id !in known }))
                        } else section
                    }
                }
                loadedMore.add(server.id)
                setCursor(server.id, page.page?.takeIf { it.has_more }?.next_cursor)
                _errorMessage.value = null
            }
            result.onFailure { e ->
                _errorMessage.value = when (e) {
                    is ApiError -> e.toErrorText(server.displayName, R.string.error_could_not_load_more_activity)
                    else -> ErrorText.Res(R.string.error_could_not_load_more_activity)
                }
            }
        }
    }

    /** Retries just [server]'s section (e.g. after "sign in"/"couldn't reach"), resetting its pagination to page 1. */
    suspend fun retry(server: AdminServer, clientFor: (AdminServer) -> ApiClient, userId: String? = null) {
        val generation = latestLoad.current()
        locks.forServer(server).withLock {
            if (!latestLoad.isCurrent(generation)) return@withLock
            reloadOneLocked(server, clientFor(server), normalize(userId), generation, keepLoadedPages = false)
        }
    }

    /** Refreshes first pages; a section with later pages loaded keeps them and their cursor. */
    suspend fun pollRefresh(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient, userId: String? = null) = coroutineScope {
        val generation = latestLoad.current()
        val filter = normalize(userId)
        // A poll for another filter would write that filter's rows and cursors under the active one.
        if (filter != activeFilter) return@coroutineScope
        servers.map { server -> async {
            locks.forServer(server).withLock {
                if (!latestLoad.isCurrent(generation)) return@withLock
                val keep = server.id in loadedMore && filter == activeFilter
                reloadOneLocked(server, clientFor(server), filter, generation, keepLoadedPages = keep)
            }
        } }.awaitAll()
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // Caller holds this server's lock and captures the current load generation.
    private suspend fun reloadOneLocked(
        server: AdminServer,
        client: ApiClient,
        filter: String?,
        generation: Long,
        keepLoadedPages: Boolean
    ) {
        val result = runCatching { client.listPlaybackHistory(filter, cursor = null) }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!latestLoad.isCurrent(generation)) return
        val page = result.getOrNull()
        if (keepLoadedPages) {
            // A failed refresh leaves the loaded pages as they were.
            if (page == null) return
            val fresh = page.items.mapTo(HashSet()) { it.session_id }
            _sections.update { current ->
                current.map { section ->
                    if (section.server.id == server.id) {
                        val older = section.result.getOrNull().orEmpty().filter { it.session_id !in fresh }
                        ServerSectionResult(server, Result.success(page.items + older))
                    } else section
                }
            }
            return
        }
        val row = ServerSectionResult(server, result.map { it.items })
        _sections.update { current ->
            if (current.none { it.server.id == server.id }) current + row
            else current.map { section -> if (section.server.id == server.id) row else section }
        }
        loadedMore.remove(server.id)
        setCursor(server.id, page?.page?.takeIf { it.has_more }?.next_cursor)
    }

    private fun setCursor(serverId: String, cursor: String?) {
        if (cursor == null) cursorByServer.remove(serverId) else cursorByServer[serverId] = cursor
        _hasMoreByServer.update { it + (serverId to (cursor != null)) }
    }

    private fun normalize(userId: String?) = userId?.trim()?.takeIf { it.isNotEmpty() }
}
