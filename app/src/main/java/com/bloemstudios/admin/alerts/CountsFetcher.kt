package com.bloemstudios.admin.alerts

import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.getRequestCounts
import com.bloemstudios.admin.network.api.listSessions
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStoreHolder
import kotlinx.coroutines.CancellationException
import java.io.Closeable

/**
 * Turns "ask this server for its numbers" into a [CountsResult]. A server with no stored session is never contacted;
 * a rejected session or an expired sign-in says "sign in"; anything else says the numbers are unavailable.
 */
class CountsFetcher(
    private val hasSession: (AdminServer) -> Boolean,
    private val read: suspend (AdminServer) -> Pair<Int, Int>,
) : CountsSource {
    override suspend fun fetch(server: AdminServer): CountsResult {
        if (!hasSession(server)) return CountsResult.SignInNeeded
        return try {
            val (streams, requests) = read(server)
            CountsResult.Known(streams, requests)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ApiError) {
            if (error.statusCode == 401) CountsResult.SignInNeeded else CountsResult.Unavailable
        } catch (_: Exception) {
            CountsResult.Unavailable
        }
    }
}

/**
 * The real source: the app's one [com.bloemstudios.admin.servers.ServerStore], reached only when a widget or the tile
 * needs numbers, so a run that only watches reachability never opens the encrypted token store. The store's own client
 * refreshes an expired session, exactly as the screens do. Close it when the run ends.
 */
class StoreCountsSource(private val context: android.content.Context) : CountsSource, Closeable {
    private var lease: ServerStoreHolder.Lease? = null

    private fun store() = (lease ?: ServerStoreHolder.acquire(context).also { lease = it }).store

    private val fetcher = CountsFetcher(
        hasSession = { server -> store().let { it.accessToken(server.id) != null || it.refreshToken(server.id) != null } },
        read = { server ->
            val client = store().client(server)
            // The session list and the request counts are the same reads the Now playing and Requests pages make.
            client.listSessions().size to client.getRequestCounts().needs_approval
        },
    )

    override suspend fun fetch(server: AdminServer): CountsResult = fetcher.fetch(server)

    override fun close() {
        lease?.close()
        lease = null
    }
}
