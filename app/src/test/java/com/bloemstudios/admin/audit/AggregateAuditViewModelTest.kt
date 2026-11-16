package com.bloemstudios.admin.audit

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

private fun auditResponseJson(ids: List<Long>, nextCursor: String? = null) =
    V2Fixtures.adminAuditLogsPage(*ids.map { V2Fixtures.adminAuditLog(it.toString()) }.toTypedArray(), nextCursor = nextCursor)

class AggregateAuditViewModelTest {
    @Test
    fun `old poll cannot publish after switching filters`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val client = ApiClient("https://a.test", "tok", MockEngine { request ->
            calls++
            if (calls == 2) { entered.complete(Unit); release.await() }
            val id = if (request.url.parameters["user_id"] == "new") 9L else 1L
            respond(auditResponseJson(listOf(id)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = AggregateAuditViewModel()
        vm.load(listOf(serverA), { client }, "old")
        val poll = launch { vm.pollRefresh(listOf(serverA), { client }, "old") }
        entered.await()
        val reload = launch { vm.load(listOf(serverA), { client }, "new") }
        yield()
        assertTrue(vm.sections.value.isEmpty())
        release.complete(Unit)
        poll.join(); reload.join()
        assertEquals(listOf(9L), vm.sections.value.single().result.getOrThrow().map { it.id.toLong() })
        client.close()
    }

    @Test
    fun `in flight poll cannot restore a removed server`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val client = ApiClient("https://a.test", "tok", MockEngine {
            calls++
            if (calls == 2) { entered.complete(Unit); release.await() }
            respond(auditResponseJson(listOf(1L)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = AggregateAuditViewModel()
        vm.load(listOf(serverA), { client })
        val poll = launch { vm.pollRefresh(listOf(serverA), { client }) }
        entered.await()
        vm.load(emptyList(), { client })
        release.complete(Unit); poll.join()
        assertTrue(vm.sections.value.isEmpty())
        assertTrue(vm.hasMoreByServer.value.isEmpty())
        client.close()
    }

    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")

    private fun clientRespondingWith(bodyJson: String): ApiClient {
        val engine = MockEngine { respond(bodyJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return ApiClient("https://x.test", "tok", engine)
    }

    private fun failingClient(): ApiClient {
        val engine = MockEngine { respond(V2Fixtures.problem(403, "permission_denied", "nope"), HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/problem+json")) }
        return ApiClient("https://x.test", "tok", engine)
    }

    @Test
    fun `load sections audit entries per server and captures each section's own cursor`() = runTest {
        val viewModel = AggregateAuditViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(auditResponseJson(listOf(1L), nextCursor = "a-page2")),
            "srv-b" to clientRespondingWith(auditResponseJson(listOf(2L, 3L)))
        )

        viewModel.load(listOf(serverA, serverB), { clients.getValue(it.id) })

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf(1L), byServer.getValue("srv-a").result.getOrThrow().map { it.id.toLong() })
        assertEquals(listOf(2L, 3L), byServer.getValue("srv-b").result.getOrThrow().map { it.id.toLong() })
        assertTrue(viewModel.hasMoreByServer.value.getValue("srv-a"))
        assertFalse(viewModel.hasMoreByServer.value.getValue("srv-b"))
    }

    @Test
    fun `a failing server produces an error section and does not affect the other`() = runTest {
        val viewModel = AggregateAuditViewModel()
        val clients = mapOf("srv-a" to clientRespondingWith(auditResponseJson(listOf(1L))), "srv-b" to failingClient())

        viewModel.load(listOf(serverA, serverB), { clients.getValue(it.id) })

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertTrue(byServer.getValue("srv-b").result.isFailure)
    }

    @Test
    fun `loadMore on one server's section appends using its own cursor, leaving the other server's section untouched`() = runTest {
        val viewModel = AggregateAuditViewModel()
        var callCount = 0
        var capturedCursor: String? = null
        val engineA = MockEngine { request ->
            callCount++
            if (callCount == 1) {
                respond(auditResponseJson(listOf(1L), nextCursor = "a-page2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                capturedCursor = request.url.parameters["cursor"]
                respond(auditResponseJson(listOf(2L)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val clients = mapOf(
            "srv-a" to ApiClient("https://a.test", "tok", engineA),
            "srv-b" to clientRespondingWith(auditResponseJson(listOf(9L)))
        )
        viewModel.load(listOf(serverA, serverB), { clients.getValue(it.id) })
        val originalBSection = viewModel.sections.value.first { it.server.id == "srv-b" }

        viewModel.loadMore(serverA, { clients.getValue(it.id) })

        assertEquals("a-page2", capturedCursor)
        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf(1L, 2L), byServer.getValue("srv-a").result.getOrThrow().map { it.id.toLong() })
        assertSame(originalBSection, byServer.getValue("srv-b"))
    }

    @Test
    fun `a slow reload cannot overwrite a faster loadMore's result once both complete`() = runTest {
        val viewModel = AggregateAuditViewModel()
        val server = AdminServer("srv-x", "https://x.test", "Server X")
        val gate = CompletableDeferred<Unit>()
        var blockedOnce = false
        val engine = MockEngine { request ->
            val cursor = request.url.parameters["cursor"]
            when {
                cursor == null && !blockedOnce -> {
                    blockedOnce = true
                    gate.await()
                    respond(auditResponseJson(listOf(1L), nextCursor = "page2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                cursor == null -> respond(auditResponseJson(listOf(1L), nextCursor = "page2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(auditResponseJson(listOf(2L)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        val loadJob = launch { viewModel.load(listOf(server), clientFor) }
        yield() // load() enters the mutex and blocks on the gated GET

        val loadMoreJob = launch { viewModel.loadMore(server, clientFor) }
        yield() // loadMore() queues up behind the mutex, still held by load()

        assertTrue(viewModel.sections.value.isEmpty())

        gate.complete(Unit)
        loadJob.join()
        loadMoreJob.join()

        // loadMore() necessarily ran after load() released the mutex (mutual
        // exclusion means there is no actual interleaving to race here), so
        // its append onto load()'s own page-1 result is the final state.
        val section = viewModel.sections.value.first { it.server.id == "srv-x" }
        assertEquals(listOf(1L, 2L), section.result.getOrThrow().map { it.id.toLong() })
    }

    @Test
    fun `a failed loadMore surfaces an error message instead of failing silently`() = runTest {
        val viewModel = AggregateAuditViewModel()
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond(auditResponseJson(listOf(1L), nextCursor = "page2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(V2Fixtures.problem(500, "internal_error", "boom"), HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        viewModel.load(listOf(serverA), clientFor)
        assertNull(viewModel.errorMessage.value)

        viewModel.loadMore(serverA, clientFor)

        assertNotNull("a failed loadMore must surface an error, not fail silently", viewModel.errorMessage.value)
    }

    @Test
    fun `poll keeps loaded pages for a server and dedupes by id keeping the newer copy`() = runTest {
        val viewModel = AggregateAuditViewModel()
        var call = 0
        val paths = mutableListOf<String>()
        val client = ApiClient("https://a.test", "tok", MockEngine { request ->
            call++
            paths += request.url.encodedPath
            val body = when (call) {
                1 -> auditResponseJson(listOf(3L, 2L), nextCursor = "page2")
                2 -> auditResponseJson(listOf(1L))
                else -> V2Fixtures.adminAuditLogsPage(
                    V2Fixtures.adminAuditLog("4"),
                    V2Fixtures.adminAuditLog("3", optional = """"user_id":"9""""),
                    nextCursor = "page2"
                )
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor)
        viewModel.loadMore(serverA, clientFor)

        viewModel.pollRefresh(listOf(serverA), clientFor)

        val rows = viewModel.sections.value.single().result.getOrThrow()
        assertEquals(listOf("4", "3", "2", "1"), rows.map { it.id })
        assertEquals("9", rows[1].user_id)
        assertTrue(paths.all { it == "/api/v2/admin/logs/audit" })
    }

    @Test
    fun `a failed poll keeps that server's loaded pages`() = runTest {
        val vm = AggregateAuditViewModel()
        var call = 0
        val cursors = mutableListOf<String?>()
        val client = ApiClient("https://a.test", "tok", MockEngine { request ->
            call++
            cursors += request.url.parameters["cursor"]
            when (call) {
                1 -> respond(auditResponseJson(listOf(2L), nextCursor = "p2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                2 -> respond(auditResponseJson(listOf(1L), nextCursor = "p3"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(V2Fixtures.problem(500, "internal_error", "boom"), HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            }
        })
        val clientFor: (AdminServer) -> ApiClient = { client }
        vm.load(listOf(serverA), clientFor)
        vm.loadMore(serverA, clientFor)

        vm.pollRefresh(listOf(serverA), clientFor)

        val section = vm.sections.value.single()
        assertTrue(section.result.isSuccess)
        assertEquals(listOf("2", "1"), section.result.getOrThrow().map { it.id })
        assertTrue(vm.hasMoreByServer.value.getValue("srv-a"))
        vm.loadMore(serverA, clientFor)
        assertEquals("p3", cursors.last())
    }

    @Test
    fun `load more after a filter change sends nothing`() = runTest {
        val vm = AggregateAuditViewModel()
        var calls = 0
        val client = ApiClient("https://a.test", "tok", MockEngine {
            calls++
            respond(auditResponseJson(listOf(1L), nextCursor = "p2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val clientFor: (AdminServer) -> ApiClient = { client }
        vm.load(listOf(serverA), clientFor, "1")

        vm.loadMore(serverA, clientFor, "2")

        assertEquals(1, calls)
        assertEquals(listOf("1"), vm.sections.value.single().result.getOrThrow().map { it.id })
    }
}
