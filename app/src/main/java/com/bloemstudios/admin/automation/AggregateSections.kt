package com.bloemstudios.admin.automation

import androidx.annotation.StringRes
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerRequestLocks
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.fanOutToServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * The All servers shape the automation pages share: one section per server holding what [read] returned for that
 * server, a failure kept in its own section, and writes sent to the row's own server only, after which that server alone
 * is read again. Polls and writes of one server never overlap; other servers are not held up.
 */
class AggregateSections<T>(private val read: suspend (ApiClient) -> T) {
    private val _sections = MutableStateFlow<List<ServerSectionResult<T>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<T>>> = _sections
    private val actions = ActionRunner()
    val busy = actions.busy
    val errorMessage: StateFlow<ErrorText?> = actions.error
    fun dismissError() = actions.dismiss()
    private val latestLoad = LatestRequestGate()
    private val locks = ServerRequestLocks()

    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        val generation = latestLoad.begin()
        _sections.update { current -> current.filter { row -> servers.any { it.id == row.server.id } } }
        fanOutToServers(servers, clientFor, locks = locks, onResult = { result ->
            if (latestLoad.isCurrent(generation)) _sections.update { current ->
                val byId = current.associateBy { it.server.id } + (result.server.id to result)
                servers.mapNotNull { byId[it.id] }
            }
        }) { client -> read(client) }
    }

    /** Reads just [server] again, for the Try again of a section that failed. */
    suspend fun retry(server: AdminServer, clientFor: (AdminServer) -> ApiClient) = locks.forServer(server).withLock {
        reloadLocked(server, clientFor(server))
    }

    /** Runs [write] on [server]'s own client, then reads that server again whether or not the write worked. */
    suspend fun act(
        server: AdminServer,
        key: String,
        @StringRes failure: Int,
        clientFor: (AdminServer) -> ApiClient,
        write: suspend (ApiClient) -> Unit,
    ): Boolean {
        var ok = false
        actions.run("${server.id}:$key", failure, serverName = server.displayName) {
            locks.forServer(server).withLock {
                val client = clientFor(server)
                try {
                    write(client)
                    ok = true
                } finally {
                    reloadLocked(server, client)
                }
            }
        }
        return ok
    }

    private suspend fun reloadLocked(server: AdminServer, client: ApiClient) {
        val result = runCatching { read(client) }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        _sections.update { current -> current.map { section -> if (section.server.id == server.id) ServerSectionResult(server, result) else section } }
    }
}
