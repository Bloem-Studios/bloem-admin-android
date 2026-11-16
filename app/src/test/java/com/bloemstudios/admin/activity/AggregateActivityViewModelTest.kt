package com.bloemstudios.admin.activity

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun entryJson(id: String, title: String = "m $id") = V2Fixtures.adminPlaybackHistoryEntry(id, title)

private fun pageJson(vararg ids: String, next: String? = null) =
    V2Fixtures.adminPlaybackHistoryPage(*ids.map { entryJson(it) }.toTypedArray(), nextCursor = next)

class AggregateActivityViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")

    private fun clientRespondingWith(bodyJson: String): ApiClient {
        val engine = MockEngine { respond(bodyJson, HttpStatusCode.OK, jsonHeaders) }
        return ApiClient("https://x.test", "tok", engine)
    }

    private fun failingClient(): ApiClient {
        val engine = MockEngine { respond(V2Fixtures.problem(401, "invalid_token", "Sign in again"), HttpStatusCode.Unauthorized, jsonHeaders) }
        return ApiClient("https://x.test", "tok", engine)
    }

    private fun ids(vm: AggregateActivityViewModel, server: String) =
        vm.sections.value.first { it.server.id == server }.result.getOrThrow().map { it.session_id }

    @Test
    fun `load sections activity per server`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(pageJson("a1")),
            "srv-b" to clientRespondingWith(pageJson("b1", "b2"))
        )
        viewModel.load(listOf(serverA, serverB), { clients.getValue(it.id) })
        assertEquals(listOf("a1"), ids(viewModel, "srv-a"))
        assertEquals(listOf("b1", "b2"), ids(viewModel, "srv-b"))
        assertEquals(mapOf("srv-a" to false, "srv-b" to false), viewModel.hasMoreByServer.value)
    }

    @Test
    fun `a failing server produces an error section and does not affect the other section`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val clients = mapOf("srv-a" to clientRespondingWith(pageJson("a1")), "srv-b" to failingClient())
        viewModel.load(listOf(serverA, serverB), { clients.getValue(it.id) })
        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertTrue(byServer.getValue("srv-b").result.isFailure)
    }

    @Test
    fun `each server pages independently`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val cursorsA = mutableListOf<String?>()
        val cursorsB = mutableListOf<String?>()
        val engineA = MockEngine { request ->
            cursorsA += request.url.parameters["cursor"]
            if (cursorsA.size == 1) respond(pageJson("a1", next = "ca1"), HttpStatusCode.OK, jsonHeaders)
            else respond(pageJson("a2"), HttpStatusCode.OK, jsonHeaders)
        }
        val engineB = MockEngine { request ->
            cursorsB += request.url.parameters["cursor"]
            respond(pageJson("b1", next = "cb1"), HttpStatusCode.OK, jsonHeaders)
        }
        val clients = mapOf("srv-a" to ApiClient("https://a.test", "tok", engineA), "srv-b" to ApiClient("https://b.test", "tok", engineB))
        val clientFor: (AdminServer) -> ApiClient = { clients.getValue(it.id) }
        viewModel.load(listOf(serverA, serverB), clientFor)
        val originalB = viewModel.sections.value.first { it.server.id == "srv-b" }
        assertEquals(mapOf("srv-a" to true, "srv-b" to true), viewModel.hasMoreByServer.value)

        viewModel.loadMore(serverA, clientFor)

        assertEquals(listOf(null, "ca1"), cursorsA)
        assertEquals(listOf<String?>(null), cursorsB) // B untouched
        assertEquals(listOf("a1", "a2"), ids(viewModel, "srv-a"))
        assertSame(originalB, viewModel.sections.value.first { it.server.id == "srv-b" })
        assertEquals(false, viewModel.hasMoreByServer.value["srv-a"])
        assertEquals(true, viewModel.hasMoreByServer.value["srv-b"])

        viewModel.loadMore(serverB, clientFor)
        assertEquals(listOf<String?>(null, "cb1"), cursorsB)
    }

    @Test
    fun `changing the user filter restarts every server from the first page`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val cursors = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cursors += request.url.parameters["cursor"]
            respond(pageJson("a1", next = "c1"), HttpStatusCode.OK, jsonHeaders)
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor, "u1")
        viewModel.loadMore(serverA, clientFor, "u1")
        viewModel.load(listOf(serverA), clientFor, "u2")
        viewModel.loadMore(serverA, clientFor, "u2")
        assertEquals(listOf(null, "c1", null, "c1"), cursors)
    }

    @Test
    fun `a slow poll cannot overwrite a faster loadMore's result once both complete`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val server = AdminServer("srv-x", "https://x.test", "Server X")
        val gate = CompletableDeferred<Unit>()
        var blockedOnce = false
        val engine = MockEngine { request ->
            val cursor = request.url.parameters["cursor"]
            when {
                cursor == null && !blockedOnce -> {
                    blockedOnce = true
                    gate.await()
                    respond(pageJson("old", next = "c1"), HttpStatusCode.OK, jsonHeaders)
                }
                cursor == null -> respond(pageJson("old", next = "c1"), HttpStatusCode.OK, jsonHeaders)
                else -> respond(pageJson("appended"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        val loadJob = launch { viewModel.load(listOf(server), clientFor) }
        yield()
        val loadMoreJob = launch { viewModel.loadMore(server, clientFor) }
        yield()
        assertTrue(viewModel.sections.value.isEmpty())

        gate.complete(Unit)
        loadJob.join()
        loadMoreJob.join()

        assertEquals(listOf("old", "appended"), ids(viewModel, "srv-x"))
    }

    @Test
    fun `a failed loadMore surfaces an error message instead of failing silently`() = runTest {
        val viewModel = AggregateActivityViewModel()
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond(pageJson("a1", next = "c1"), HttpStatusCode.OK, jsonHeaders)
            else respond(V2Fixtures.problem(500, "internal_error", "Internal error"), HttpStatusCode.InternalServerError, jsonHeaders)
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor)
        assertNull(viewModel.errorMessage.value)
        viewModel.loadMore(serverA, clientFor)
        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun `pollRefresh refreshes first pages but keeps a section's later pages and cursor`() = runTest {
        val viewModel = AggregateActivityViewModel()
        var callsA = 0
        val cursorsA = mutableListOf<String?>()
        val engineA = MockEngine { request ->
            cursorsA += request.url.parameters["cursor"]
            when (++callsA) {
                1 -> respond(pageJson("a1", "a2", next = "c1"), HttpStatusCode.OK, jsonHeaders)
                2 -> respond(pageJson("a3", next = "c2"), HttpStatusCode.OK, jsonHeaders)
                3 -> respond(V2Fixtures.adminPlaybackHistoryPage(entryJson("new"), entryJson("a1", "a1 refreshed"), entryJson("a2"), nextCursor = "cX"), HttpStatusCode.OK, jsonHeaders)
                else -> respond(pageJson("a4"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        var callsB = 0
        val engineB = MockEngine { callsB++; respond(pageJson("b$callsB"), HttpStatusCode.OK, jsonHeaders) }
        val clients = mapOf("srv-a" to ApiClient("https://a.test", "tok", engineA), "srv-b" to ApiClient("https://b.test", "tok", engineB))
        val clientFor: (AdminServer) -> ApiClient = { clients.getValue(it.id) }

        viewModel.load(listOf(serverA, serverB), clientFor)
        viewModel.loadMore(serverA, clientFor)
        viewModel.pollRefresh(listOf(serverA, serverB), clientFor)

        assertEquals(listOf("new", "a1", "a2", "a3"), ids(viewModel, "srv-a"))
        assertEquals("a1 refreshed", viewModel.sections.value.first { it.server.id == "srv-a" }.result.getOrThrow()[1].media_title)
        assertEquals(listOf("b2"), ids(viewModel, "srv-b")) // single-page section replaced
        assertEquals(true, viewModel.hasMoreByServer.value["srv-a"])

        viewModel.loadMore(serverA, clientFor)
        assertEquals("c2", cursorsA[3])
        assertEquals(listOf("new", "a1", "a2", "a3", "a4"), ids(viewModel, "srv-a"))
    }

    @Test
    fun `a failed poll keeps a section's loaded pages`() = runTest {
        val viewModel = AggregateActivityViewModel()
        var n = 0
        val engine = MockEngine {
            when (++n) {
                1 -> respond(pageJson("a1", next = "c1"), HttpStatusCode.OK, jsonHeaders)
                2 -> respond(pageJson("a2"), HttpStatusCode.OK, jsonHeaders)
                else -> respond(V2Fixtures.problem(500, "internal_error", "Internal error"), HttpStatusCode.InternalServerError, jsonHeaders)
            }
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor)
        viewModel.loadMore(serverA, clientFor)
        viewModel.pollRefresh(listOf(serverA), clientFor)
        assertEquals(listOf("a1", "a2"), ids(viewModel, "srv-a"))
    }

    @Test
    fun `pollRefresh still refreshes a section with only a single page loaded`() = runTest {
        val viewModel = AggregateActivityViewModel()
        var callCount = 0
        val engine = MockEngine { callCount++; respond(pageJson("s1"), HttpStatusCode.OK, jsonHeaders) }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor)
        viewModel.pollRefresh(listOf(serverA), clientFor)
        assertEquals(2, callCount)
    }

    @Test
    fun `a poll for another filter is ignored and leaves rows and cursors under the active filter`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val requests = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { request ->
            requests += request.url.parameters["user_id"] to request.url.parameters["cursor"]
            val other = request.url.parameters["user_id"] == "new"
            respond(if (other) pageJson("new1", next = "c-new") else pageJson("old1", next = "c-old"), HttpStatusCode.OK, jsonHeaders)
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor, "old")

        viewModel.pollRefresh(listOf(serverA), clientFor, "new")

        assertEquals(listOf("old1"), ids(viewModel, "srv-a"))
        assertEquals(listOf<Pair<String?, String?>>("old" to null), requests)
        viewModel.loadMore(serverA, clientFor, "old")
        assertEquals("old" to "c-old", requests.last())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a poll that lands during Load more merges into the appended page instead of replacing it`() = runTest {
        val viewModel = AggregateActivityViewModel()
        val gate = CompletableDeferred<Unit>()
        val cursors = mutableListOf<String?>()
        var firstPages = 0
        val engine = MockEngine { request ->
            val cursor = request.url.parameters["cursor"]
            cursors += cursor
            when {
                cursor == null && firstPages++ == 0 -> respond(pageJson("old", next = "c1"), HttpStatusCode.OK, jsonHeaders)
                cursor == null -> respond(pageJson("fresh", next = "c9"), HttpStatusCode.OK, jsonHeaders)
                cursor == "c1" -> { gate.await(); respond(pageJson("appended", next = "c2"), HttpStatusCode.OK, jsonHeaders) }
                else -> respond(pageJson("last"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        viewModel.load(listOf(serverA), clientFor)

        val more = launch { viewModel.loadMore(serverA, clientFor) }
        runCurrent()
        val poll = launch { viewModel.pollRefresh(listOf(serverA), clientFor) }
        runCurrent()
        gate.complete(Unit)
        more.join(); poll.join()

        assertEquals(listOf("fresh", "old", "appended"), ids(viewModel, "srv-a"))
        viewModel.loadMore(serverA, clientFor)
        assertEquals("c2", cursors.last())
        assertEquals(listOf("fresh", "old", "appended", "last"), ids(viewModel, "srv-a"))
    }
}
