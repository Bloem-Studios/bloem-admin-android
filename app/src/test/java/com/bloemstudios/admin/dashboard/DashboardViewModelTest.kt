package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

private const val STATS_JSON = V2Fixtures.ADMIN_STATS
private const val RESOURCES_JSON = V2Fixtures.SYSTEM_RESOURCES
private const val RESOURCES_UNAVAILABLE_JSON = V2Fixtures.SYSTEM_RESOURCES_UNAVAILABLE

// Routes the two admin endpoints this ViewModel now fetches concurrently to
// separate canned responses, keyed off the request path, so tests can
// assert on each independently without caring which one the engine sees
// first.
private fun dualEndpointEngine(
    statsJson: String = STATS_JSON,
    hostStatsJson: String = RESOURCES_JSON,
    onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {}
) = MockEngine { request ->
    onRequest(request)
    val content = if (request.url.encodedPath == "/api/v2/admin/system/resources") hostStatsJson else statsJson
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json")
    )
}

class DashboardViewModelTest {
    @Test fun `sampling does not compete with repository session reads`() = runTest {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val client = ApiClient("https://example.test", null, dualEndpointEngine(onRequest = { paths += it.url.encodedPath }))
        try {
            DashboardViewModel().load(client)
            assertFalse("Only the shared repository may read sessions", paths.contains("/api/v2/admin/sessions"))
        } finally { client.close() }
    }

    @Test
    fun `host health publishes while catalog request is delayed`() = runTest {
        val statsEntered = CompletableDeferred<Unit>()
        val releaseStats = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v2/admin/stats" -> { statsEntered.complete(Unit); releaseStats.await(); STATS_JSON }
                "/api/v2/admin/sessions" -> """{"items":[]}"""
                else -> RESOURCES_JSON
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val model = DashboardViewModel()
        val loading = launch { model.load(client) }
        try {
            statsEntered.await()
            kotlinx.coroutines.withTimeout(5000) { model.hostStats.first { it != null } }
            assertFalse(loading.isCompleted)
            assertNull(model.stats.value)
        } finally { releaseStats.complete(Unit); loading.join(); client.close() }
    }

    @Test
    fun `late result from an older client cannot overwrite the newest dashboard state`() = runTest {
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldEngine = MockEngine { request ->
            if (request.url.encodedPath == "/api/v2/admin/stats") {
                oldEntered.complete(Unit)
                releaseOld.await()
            }
            val content = if (request.url.encodedPath == "/api/v2/admin/system/resources") {
                RESOURCES_JSON
            } else {
                STATS_JSON
            }
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val newStats = STATS_JSON.replace("\"total_items\":4200", "\"total_items\":9999")
        val oldClient = ApiClient("https://old.test", null, oldEngine)
        val newClient = ApiClient("https://new.test", null, dualEndpointEngine(statsJson = newStats))
        val viewModel = DashboardViewModel()

        val oldLoad = launch { viewModel.load(oldClient) }
        oldEntered.await()
        viewModel.load(newClient)
        releaseOld.complete(Unit)
        oldLoad.join()

        assertEquals(9999, viewModel.stats.value?.total_items)
    }

    @Test
    fun `dashboard load propagates cancellation without publishing an error`() = runTest {
        val engine = MockEngine { throw CancellationException("screen left") }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()
        var cancelled = false

        try {
            viewModel.load(client)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load decodes stats`() = runTest {
        val engine = MockEngine {
            respond(
                content = STATS_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        val stats = viewModel.stats.value
        assertNotNull(stats)
        assertEquals(4200, stats!!.total_items)
        assertEquals(3, stats.active_streams)
        assertEquals(987654321098L, stats.total_storage_bytes)
        assertEquals(5L, stats.watch_providers.single().connected_profiles)
        assertEquals("2026-08-25T00:00:00Z", stats.watch_providers.single().last_sync_completed_at)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load decodes stats with null last sync`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                {
                  "total_items": 0, "total_files": 0, "total_users": 0, "advisory_titles": 0,
                  "total_movies": 0, "total_movie_files": 0,
                  "total_shows": 0, "total_show_files": 0,
                  "active_streams": 0, "total_storage_bytes": 0,
                  "watch_providers": [{
                    "provider": "trakt", "display_name": "Trakt", "registered": true,
                    "scrobbling": true, "exporting": true,
                    "connected_profiles": 0, "enabled_profiles": 0,
                    "export_enabled_profiles": 0, "scrobble_enabled_profiles": 0,
                    "sync_runs_24h": 0, "sync_errors_24h": 0,
                    "imported_watched_24h": 0, "imported_progress_24h": 0,
                    "exported_watched_24h": 0, "pending_exports": 0,
                    "failed_exports": 0, "open_scrobbles": 0, "scrobbles_24h": 0
                  }]
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        assertNull(viewModel.stats.value?.watch_providers?.single()?.last_sync_completed_at)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load surfaces a 401 as an error message`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.problem(401, "authentication_required", "invalid or expired token"),
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        assertNull(viewModel.stats.value)
        assertEquals(ErrorText.Raw("invalid or expired token"), viewModel.errorMessage.value)
    }

    @Test
    fun `forceRefresh appends the refresh query param`() = runTest {
        val capturedPaths = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath + "?" + request.url.encodedQuery)
            respond(
                content = STATS_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client, forceRefresh = true)

        assertEquals("/api/v2/admin/stats?refresh=true", capturedPaths.single { it.startsWith("/api/v2/admin/stats?") })
    }

    @Test
    fun `stats read v2 and add refresh only when forced`() = runTest {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val client = ApiClient("https://example.test", null, dualEndpointEngine(onRequest = {
            if (it.url.encodedPath == "/api/v2/admin/stats") seen += "?" + it.url.encodedQuery
        }))
        val viewModel = DashboardViewModel()
        viewModel.load(client)
        viewModel.load(client, forceRefresh = true)
        assertEquals(listOf("?", "?refresh=true"), seen.toList())
    }

    @Test
    fun `load decodes available system resources`() = runTest {
        val client = ApiClient("https://example.test", null, dualEndpointEngine())
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        val resources = viewModel.hostStats.value
        assertNotNull(resources)
        assertTrue(resources!!.available)
        assertFalse(resources.stale)
        val system = resources.system!!
        assertEquals(42.0, system.cpu_pct!!, 0.0)
        assertEquals(1000L, system.mem_used_mb)
        assertEquals(4000L, system.mem_total_mb)
        assertEquals(123.0, system.net_rx_bps!!, 0.0)
        assertEquals(56.0, system.net_tx_bps!!, 0.0)
        assertEquals("2026-01-01T00:00:00Z", resources.sampled_at)
        assertFalse(viewModel.hostUnavailable.value)
    }

    @Test
    fun `network rates from v2 are bits per second and show as bytes`() {
        val stats = SystemStats(net_rx_bps = 8000.0, net_tx_bps = 16.0)
        assertEquals(1000.0, stats.netRxBytesPerSec!!, 0.0)
        assertEquals(2.0, stats.netTxBytesPerSec!!, 0.0)
        assertNull(SystemStats().netRxBytesPerSec)
    }

    @Test
    fun `health card hides when resources are unavailable`() = runTest {
        val client = ApiClient("https://example.test", null, dualEndpointEngine(hostStatsJson = RESOURCES_UNAVAILABLE_JSON))
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        assertNull(viewModel.hostStats.value)
        assertNull(viewModel.hostError.value)
        assertNull(viewModel.errorMessage.value)
        assertTrue(viewModel.hostUnavailable.value)
        assertNotNull(viewModel.stats.value)
    }

    @Test
    fun `stale resources are marked stale`() = runTest {
        val client = ApiClient("https://example.test", null, dualEndpointEngine(hostStatsJson = V2Fixtures.SYSTEM_RESOURCES_STALE))
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        val resources = viewModel.hostStats.value
        assertNotNull(resources)
        assertTrue(resources!!.stale)
        assertEquals(42.0, resources.system!!.cpu_pct!!, 0.0)
        assertNull(viewModel.hostError.value)
    }

    @Test
    fun `resources failure keeps the primary stats and reports health unavailable`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("resources")) respond(V2Fixtures.problem(503, "unavailable", "Resources are unavailable."),
                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            else respond(STATS_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        assertNotNull(viewModel.stats.value)
        assertNull(viewModel.hostStats.value)
        assertEquals(ErrorText.Raw("Resources are unavailable."), viewModel.hostError.value)
    }

    @Test
    fun `load fetches resources alongside stats without a refresh param on initial load and poll`() = runTest {
        val capturedRequests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val engine = dualEndpointEngine(onRequest = { request ->
            capturedRequests.add(request.url.encodedPath to request.url.encodedQuery)
        })
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client)
        viewModel.load(client)

        val resourceRequests = capturedRequests.filter { it.first == "/api/v2/admin/system/resources" }
        assertEquals(2, resourceRequests.size)
        resourceRequests.forEach { (_, query) -> assertEquals("", query) }
        assertEquals(2, capturedRequests.count { it.first == "/api/v2/admin/stats" })
    }

    @Test
    fun `plain load omits the refresh query param`() = runTest {
        val capturedPaths = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath + "?" + request.url.encodedQuery)
            respond(
                content = STATS_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = DashboardViewModel()

        viewModel.load(client)

        assertEquals("/api/v2/admin/stats?", capturedPaths.single { it.startsWith("/api/v2/admin/stats?") })
    }
    @Test
    fun `primary stats publish before supplementary host stats finish`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("resources")) {
                entered.complete(Unit)
                gate.await()
                respond(RESOURCES_UNAVAILABLE_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond(STATS_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val vm = DashboardViewModel()
        val loading = launch { vm.load(client) }
        try {
            entered.await()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(1_000) { vm.stats.first { it != null } }
            }
            assertFalse(loading.isCompleted)
        } finally { gate.complete(Unit); loading.join(); client.close() }
    }

}
