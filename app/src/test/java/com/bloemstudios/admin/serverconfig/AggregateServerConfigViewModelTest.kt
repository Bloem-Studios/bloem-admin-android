package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

private fun statusJson(startedAt: String = "2026-01-01T00:00:00Z") =
    V2Fixtures.SERVER_STATUS.replace("2026-08-28T10:00:00Z", startedAt)
private fun settingsJson(vararg pairs: Pair<String, String>) =
    "{" + pairs.joinToString(",") { (k, v) -> """"$k":"$v"""" } + "}"

class AggregateServerConfigViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")

    // Round-robins settings then status responses on successive GETs, matching
    // ServerConfigViewModel.load()'s two sequential GETs per server. POSTs
    // (restart) don't consume a slot in the GET round-robin.
    private fun clientRespondingWith(settings: String, status: String): ApiClient {
        var getCall = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond(V2Fixtures.SERVER_RESTART, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                getCall++
                val body = if (getCall % 2 == 1) settings else status
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return ApiClient("https://x.test", "tok", engine)
    }

    private fun failingClient(): ApiClient {
        val engine = MockEngine { respond("""{"message":"nope"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }
        return ApiClient("https://x.test", "tok", engine)
    }

    @Test
    fun `load and restart use the v2 routes`() = runTest {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            seen += request.method.value + " " + request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/api/v2/admin/server/restart" -> V2Fixtures.SERVER_RESTART
                "/api/v2/admin/server/status" -> V2Fixtures.SERVER_STATUS
                else -> V2Fixtures.SETTINGS
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val viewModel = AggregateServerConfigViewModel()
        viewModel.load(listOf(serverA)) { client }
        viewModel.restartServer(serverA) { client }
        assertTrue(seen.toString(), seen.contains("GET /api/v2/admin/settings") && seen.contains("GET /api/v2/admin/server/status"))
        assertTrue(seen.toString(), seen.contains("POST /api/v2/admin/server/restart"))
        assertTrue(seen.toString(), seen.none { "/api/v1/" in it })
    }

    @Test
    fun `load sections settings and status per server`() = runTest {
        val viewModel = AggregateServerConfigViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(settingsJson("k" to "v1"), statusJson("2026-01-01T00:00:00Z")),
            "srv-b" to clientRespondingWith(settingsJson("k" to "v2"), statusJson("2026-02-02T00:00:00Z"))
        )

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertEquals("v1", byServer.getValue("srv-a").result.getOrThrow().settings.first().value)
        assertEquals("2026-02-02T00:00:00Z", byServer.getValue("srv-b").result.getOrThrow().status.started_at)
    }

    @Test
    fun `a failing server produces an error section and does not affect the other`() = runTest {
        val viewModel = AggregateServerConfigViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(settingsJson("k" to "v1"), statusJson()),
            "srv-b" to failingClient()
        )

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertTrue(byServer.getValue("srv-b").result.isFailure)
    }

    @Test
    fun `restarting one server reloads only that server's section`() = runTest {
        val viewModel = AggregateServerConfigViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(settingsJson("k" to "v1"), statusJson()),
            "srv-b" to clientRespondingWith(settingsJson("k" to "v2"), statusJson())
        )
        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }
        val originalBSection = viewModel.sections.value.first { it.server.id == "srv-b" }

        viewModel.restartServer(serverA) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertSame(originalBSection, byServer.getValue("srv-b"))
    }

    @Test
    fun `a slow poll cannot overwrite a faster action's result once both complete`() = runTest {
        val viewModel = AggregateServerConfigViewModel()
        val server = AdminServer("srv-x", "https://x.test", "Server X")
        val gate = CompletableDeferred<Unit>()
        var getCall = 0
        // GET sequence: 1=load's settings (gated), 2=load's status,
        // 3=restart's reload settings, 4=restart's reload status. POST
        // (restart itself) always succeeds immediately.
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond(V2Fixtures.SERVER_RESTART, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                getCall++
                when (getCall) {
                    1 -> {
                        gate.await()
                        respond(settingsJson("k" to "old"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    3 -> respond(settingsJson("k" to "new"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> respond(statusJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        val loadJob = launch { viewModel.load(listOf(server), clientFor) }
        yield()
        val actJob = launch { viewModel.restartServer(server, clientFor) }
        yield()

        assertTrue(viewModel.sections.value.isEmpty())

        gate.complete(Unit)
        loadJob.join()
        actJob.join()

        val section = viewModel.sections.value.first { it.server.id == "srv-x" }
        // act() necessarily ran after load() released the mutex, so its own
        // reload (settings "new") is what stands, not load()'s stale "old".
        assertEquals("new", section.result.getOrThrow().settings.first().value)
    }

    @Test
    fun `a failed action surfaces an error message instead of failing silently`() = runTest {
        val viewModel = AggregateServerConfigViewModel()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond("""{"type":"about:blank","title":"Conflict","status":409,"detail":"restart already in progress"}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(if (request.url.encodedPath.endsWith("/status")) statusJson() else settingsJson("k" to "v"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        viewModel.load(listOf(serverA), clientFor)
        assertNull(viewModel.errorMessage.value)

        viewModel.restartServer(serverA, clientFor)

        assertNotNull("a failed action must surface an error, not fail silently", viewModel.errorMessage.value)
    }
}
