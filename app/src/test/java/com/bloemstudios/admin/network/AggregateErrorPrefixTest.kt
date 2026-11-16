package com.bloemstudios.admin.network

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.activity.AggregateActivityViewModel
import com.bloemstudios.admin.audit.AggregateAuditViewModel
import com.bloemstudios.admin.dashboard.DashboardViewModel
import com.bloemstudios.admin.jobs.AdminTask
import com.bloemstudios.admin.jobs.AggregateJobsViewModel
import com.bloemstudios.admin.requests.AggregateRequestsViewModel
import com.bloemstudios.admin.requests.MediaRequest
import com.bloemstudios.admin.serverconfig.AggregateServerConfigViewModel
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.tenants.TenantMembersState
import com.bloemstudios.admin.tenants.TenantsProbeState
import com.bloemstudios.admin.tenants.TenantsViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aggregate views name the server on every error, including the resource texts; single-server views never do. */
class AggregateErrorPrefixTest {
    private val server = AdminServer("srv-a", "https://a.test", "Server A")
    private val problemJson = headersOf(HttpHeaders.ContentType, "application/problem+json")
    private val upgrade = ErrorText.Prefixed("Server A", ErrorText.Res(R.string.error_client_upgrade_required))
    private val gone = V2Fixtures.problem(410, "client_upgrade_required", "This server no longer serves /api/v1.")

    private fun client(onRequest: (HttpMethod, String) -> Pair<HttpStatusCode, String>): ApiClient =
        ApiClient("https://a.test", null, MockEngine { request ->
            val (status, body) = onRequest(request.method, request.url.encodedPath)
            respond(body, status, problemJson)
        })

    private fun page(body: String) = HttpStatusCode.OK to body

    @Test
    fun `prefixed text is the server name then the inner text`() {
        val error = ApiError(410, "client_upgrade_required", "gone")
        assertEquals(ErrorText.Prefixed("Server A", ErrorText.Res(R.string.error_client_upgrade_required)), error.toErrorText("Server A", R.string.error_action_failed))
        assertEquals(ErrorText.Prefixed("Server A", ErrorText.Raw("Refused by the server.")), ApiError(409, "conflict", "Refused by the server.").toErrorText("Server A", R.string.error_action_failed))
    }

    @Test
    fun `aggregate jobs action upgrade error names the server`() = runTest {
        val api = client { method, _ ->
            if (method == HttpMethod.Post) HttpStatusCode.Gone to gone else page(V2Fixtures.adminTasksPage())
        }
        val vm = AggregateJobsViewModel()
        vm.run(server, AdminTask("scan", "Scan", "", "metadata", "idle", 0.0, "process")) { api }
        assertEquals(upgrade, vm.errorMessage.value)
    }

    @Test
    fun `aggregate requests action and load errors name the server`() = runTest {
        val api = client { method, _ ->
            if (method == HttpMethod.Post) HttpStatusCode.Gone to gone else page(V2Fixtures.mediaRequestsPage())
        }
        val vm = AggregateRequestsViewModel()
        vm.load(listOf(server)) { api }
        vm.approve(server, MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")) { api }
        assertEquals(upgrade, vm.actionError.value)

        val failing = client { _, _ -> HttpStatusCode.Gone to gone }
        val loading = AggregateRequestsViewModel()
        loading.load(listOf(server)) { failing }
        assertEquals(upgrade, loading.loadError.value)
    }

    @Test
    fun `aggregate server config restart upgrade error names the server`() = runTest {
        val api = client { method, _ ->
            if (method == HttpMethod.Post) HttpStatusCode.Gone to gone else page(V2Fixtures.SETTINGS)
        }
        val vm = AggregateServerConfigViewModel()
        vm.restartServer(server) { api }
        assertEquals(upgrade, vm.actionError.value)
    }

    @Test
    fun `aggregate audit load more upgrade error names the server`() = runTest {
        var calls = 0
        val api = client { _, _ ->
            if (++calls == 1) page(V2Fixtures.adminAuditLogsPage(V2Fixtures.adminAuditLog(), nextCursor = "p2"))
            else HttpStatusCode.Gone to gone
        }
        val vm = AggregateAuditViewModel()
        vm.load(listOf(server), { api })
        vm.loadMore(server, { api })
        assertEquals(upgrade, vm.errorMessage.value)
    }

    @Test
    fun `aggregate activity load more upgrade error names the server`() = runTest {
        var calls = 0
        val api = client { _, _ ->
            if (++calls == 1) page(V2Fixtures.adminPlaybackHistoryPage(V2Fixtures.adminPlaybackHistoryEntry(), nextCursor = "p2"))
            else HttpStatusCode.Gone to gone
        }
        val vm = AggregateActivityViewModel()
        vm.load(listOf(server), { api })
        vm.loadMore(server, { api })
        assertEquals(upgrade, vm.errorMessage.value)
    }

    @Test
    fun `tenants show the server's own text for api errors`() = runTest {
        val api = client { _, _ -> HttpStatusCode.Gone to gone }
        val vm = TenantsViewModel()
        vm.load(api)
        assertEquals(TenantsProbeState.Error(ErrorText.Res(R.string.error_client_upgrade_required)), vm.probeState.value)
        vm.loadMembers("t1", api)
        assertEquals(TenantMembersState.Error(ErrorText.Res(R.string.error_client_upgrade_required)), vm.membersState.value)
    }

    @Test
    fun `dashboard shows the server's own text for api errors and a resource for anything else`() = runTest {
        val refused = client { _, path ->
            if (path.endsWith("resources")) HttpStatusCode.ServiceUnavailable to V2Fixtures.problem(503, "unavailable", "Host sampling is down")
            else HttpStatusCode.Forbidden to V2Fixtures.problem(403, "permission_denied", "Admin access required")
        }
        val vm = DashboardViewModel()
        vm.load(refused)
        assertEquals(ErrorText.Raw("Admin access required"), vm.errorMessage.value)
        assertEquals(ErrorText.Raw("Host sampling is down"), vm.hostError.value)

        val broken = client { _, _ -> page("not json") }
        val other = DashboardViewModel()
        other.load(broken)
        assertTrue(other.errorMessage.value is ErrorText.Res)
    }
}
