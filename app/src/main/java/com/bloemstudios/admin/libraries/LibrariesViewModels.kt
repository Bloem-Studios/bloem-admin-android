package com.bloemstudios.admin.libraries

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap


/** The Libraries page of the server that is open. */
class LibrariesViewModel : ViewModel() {
    val page = LibraryPage()
    suspend fun load(client: ApiClient) = page.refresh(client)
}

/** The Libraries page of All servers: one [LibraryPage] per server, each acting only on its own server. */
class AggregateLibrariesViewModel : ViewModel() {
    private val pages = ConcurrentHashMap<String, LibraryPage>()

    fun pageFor(server: AdminServer): LibraryPage = pages.getOrPut(server.id) { LibraryPage(serverName = server.displayName) }

    /** True while any server has a scan or refresh under way. */
    fun anyActive(servers: List<AdminServer>): Boolean = servers.any { pageFor(it).isActive() }

    /** Reads every server at once. A server that cannot be reached marks only its own section. */
    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) = coroutineScope {
        servers.map { server -> async { loadOne(server, clientFor) } }.awaitAll()
        Unit
    }

    suspend fun loadOne(server: AdminServer, clientFor: (AdminServer) -> ApiClient) {
        val page = pageFor(server)
        try {
            server.validatedOrigin()
            // The timeout runs on the IO dispatcher so it measures real time, as fanOutToServers does.
            val done = withContext(Dispatchers.IO) { withTimeoutOrNull(SECTION_TIMEOUT_MILLIS) { page.refresh(clientFor(server)); true } }
            if (done == null) page.reportLoadFailure(java.util.concurrent.TimeoutException("Server request timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            page.reportLoadFailure(e)
        }
    }

    private companion object {
        const val SECTION_TIMEOUT_MILLIS = 15_000L
    }
}
