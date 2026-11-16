package com.bloemstudios.admin.platform

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.listPlatformOrganizations
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.fanOutToServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One server's organizations. [supported] is false for a server that has no organizations (a plain Silo server, or an older build). */
data class ServerOrganizations(
    val supported: Boolean,
    val organizations: List<OrganizationSummary> = emptyList(),
    val nextCursor: String? = null,
)

/** The rows of [organizations] whose name or short name contains [query], ignoring case. A blank query keeps every row. */
fun filterOrganizations(organizations: List<OrganizationSummary>, query: String): List<OrganizationSummary> {
    val needle = query.trim()
    if (needle.isEmpty()) return organizations
    return organizations.filter { it.name.contains(needle, ignoreCase = true) || it.slug.contains(needle, ignoreCase = true) }
}

/**
 * All servers › Organizations: one section for each saved server, read concurrently. A server that cannot be read (signed out,
 * unreachable, no platform access) fails its own section only. Every read of one server mints its own short platform pass
 * through a [PlatformSession], uses it and drops it: nothing here keeps a pass between reads.
 */
class AggregatePlatformOrganizationsViewModel(
    private val sessionFactory: (ApiClient) -> PlatformSession = { PlatformSession(it) },
) : ViewModel() {
    private val _sections = MutableStateFlow<List<ServerSectionResult<ServerOrganizations>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<ServerOrganizations>>> = _sections

    private val gate = LatestRequestGate()

    @Synchronized
    private fun publish(result: ServerSectionResult<ServerOrganizations>, servers: List<AdminServer>) {
        val byId = _sections.value.associateBy { it.server.id } + (result.server.id to result)
        _sections.value = servers.mapNotNull { byId[it.id] }
    }

    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        val generation = gate.begin()
        _sections.value = _sections.value.filter { row -> servers.any { it.id == row.server.id } }
        fanOutToServers(servers, clientFor, onResult = { result ->
            if (gate.isCurrent(generation)) publish(result, servers)
        }) { client -> readFirstPage(client) }
    }

    /** Adds the next page of one server's list; the other servers are not asked. */
    suspend fun loadMore(server: AdminServer, clientFor: (AdminServer) -> ApiClient) {
        val current = _sections.value.firstOrNull { it.server.id == server.id }?.result?.getOrNull() ?: return
        val cursor = current.nextCursor ?: return
        val result = try {
            Result.success(
                sessionFactory(clientFor(server)).use { session ->
                    session.read { it.listPlatformOrganizations(null, cursor, PlatformOrganizationsViewModel.PAGE_SIZE) }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        result.onSuccess { page ->
            val servers = _sections.value.map { it.server }
            publish(
                ServerSectionResult(
                    server,
                    Result.success(current.copy(organizations = current.organizations + page.organizations, nextCursor = page.next_cursor?.takeIf(String::isNotBlank))),
                ),
                servers,
            )
        }
    }

    private suspend fun readFirstPage(client: ApiClient): ServerOrganizations {
        if (!client.supportsPlatformOrganizations()) return ServerOrganizations(supported = false)
        return sessionFactory(client).use { session ->
            val page = session.read { it.listPlatformOrganizations(null, null, PlatformOrganizationsViewModel.PAGE_SIZE) }
            ServerOrganizations(supported = true, organizations = page.organizations, nextCursor = page.next_cursor?.takeIf(String::isNotBlank))
        }
    }
}
