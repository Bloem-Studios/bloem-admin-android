package com.bloemstudios.admin.servers

import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes a server's polls and mutations without blocking other servers. */
class ServerRequestLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun forServer(server: AdminServer): Mutex = locks.computeIfAbsent(server.id) { Mutex() }
}

/** Outcome of fanning one request out to one server: which server, and whether it succeeded. */
data class ServerSectionResult<T>(val server: AdminServer, val result: Result<T>)

/**
 * Runs [request] against every server in [servers] concurrently -- one
 * coroutine per server via async/awaitAll, not sequentially -- using
 * [clientFor] to obtain each server's ApiClient.
 *
 * A server with no stored token, an expired one (401), a non-admin one
 * (403), or an unreachable server all land here as a [Result.failure] for
 * that server's section -- never as an exception that aborts the whole
 * fan-out -- so the caller (a screen) can render a per-section error/"tap to
 * sign in" row instead of losing every other server's data because one
 * server is broken.
 *
 * Takes a `clientFor` lambda rather than [ServerStore] directly so this
 * stays trivially unit-testable (a fake per-server ApiClient/MockEngine)
 * without pulling ServerStore's real EncryptedSharedPreferences/DataStore
 * Android dependencies into fan-out/aggregate-view-model tests. The
 * [ServerStore]-based overload below is what production call sites use.
 */
suspend fun <T> fanOutToServers(
    servers: List<AdminServer>,
    clientFor: (AdminServer) -> ApiClient,
    locks: ServerRequestLocks? = null,
    onResult: (ServerSectionResult<T>) -> Unit = {},
    sectionTimeoutMillis: Long = 15_000L,
    request: suspend (ApiClient) -> T
): List<ServerSectionResult<T>> = coroutineScope {
    servers.map { server ->
        async {
            suspend fun fetch(): ServerSectionResult<T> {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(sectionTimeoutMillis) {
                            server.validatedOrigin()
                            Result.success(request(clientFor(server)))
                        } ?: Result.failure(java.util.concurrent.TimeoutException("Server request timed out"))
                    }
                }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val sectionResult = result.fold(onSuccess = { it }, onFailure = { Result.failure(it) })
                sectionResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                return ServerSectionResult(server, sectionResult).also(onResult)
            }
            locks?.forServer(server)?.withLock { fetch() } ?: fetch()
        }
    }.awaitAll()
}

/** Convenience overload for production call sites that already hold a [ServerStore]. */
suspend fun <T> fanOutToServers(
    servers: List<AdminServer>,
    store: ServerStore,
    request: suspend (ApiClient) -> T
): List<ServerSectionResult<T>> = fanOutToServers(servers, clientFor = { server -> store.client(server) }, request = request)
