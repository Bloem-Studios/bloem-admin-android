package com.bloemstudios.admin.jobs

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

private fun taskJson(key: String, state: String = "idle") = V2Fixtures.adminTask(key, state)

class AggregateJobsViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")

    private fun clientRespondingWith(bodyJson: String): ApiClient {
        val engine = MockEngine { respond(bodyJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return ApiClient("https://x.test", "tok", engine)
    }

    private fun failingClient(): ApiClient {
        val engine = MockEngine { respond(V2Fixtures.problem(401, "authentication_required", "nope"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }
        return ApiClient("https://x.test", "tok", engine)
    }

    @Test
    fun `load sections tasks per server`() = runTest {
        val viewModel = AggregateJobsViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(V2Fixtures.adminTasksPage(taskJson("scan"))),
            "srv-b" to clientRespondingWith(V2Fixtures.adminTasksPage(taskJson("cleanup"), taskJson("thumbs")))
        )

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf("scan"), byServer.getValue("srv-a").result.getOrThrow().map { it.key })
        assertEquals(listOf("cleanup", "thumbs"), byServer.getValue("srv-b").result.getOrThrow().map { it.key })
    }

    @Test
    fun `a failing server produces an error section and does not affect the other`() = runTest {
        val viewModel = AggregateJobsViewModel()
        val clients = mapOf("srv-a" to clientRespondingWith(V2Fixtures.adminTasksPage(taskJson("scan"))), "srv-b" to failingClient())

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertTrue(byServer.getValue("srv-b").result.isFailure)
    }

    @Test
    fun `running a task on one server reloads only that server's section`() = runTest {
        val viewModel = AggregateJobsViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(V2Fixtures.adminTasksPage(taskJson("scan"))),
            "srv-b" to clientRespondingWith(V2Fixtures.adminTasksPage(taskJson("cleanup")))
        )
        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }
        val originalBSection = viewModel.sections.value.first { it.server.id == "srv-b" }

        viewModel.run(serverA, AdminTask("scan", "Scan", "d", "c", "idle", 0.0, "process")) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertSame(originalBSection, byServer.getValue("srv-b"))
    }

    @Test
    fun `a slow poll cannot overwrite a faster action's result once both complete`() = runTest {
        val viewModel = AggregateJobsViewModel()
        val server = AdminServer("srv-x", "https://x.test", "Server X")
        val gate = CompletableDeferred<Unit>()
        var blockedOnce = false
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && !blockedOnce -> {
                    blockedOnce = true
                    gate.await()
                    respond(V2Fixtures.adminTasksPage(taskJson("scan", "idle")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.method == HttpMethod.Post -> respond("", HttpStatusCode.OK)
                else -> respond(V2Fixtures.adminTasksPage(taskJson("scan", "running")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        val loadJob = launch { viewModel.load(listOf(server), clientFor) }
        yield()
        val runJob = launch { viewModel.run(server, AdminTask("scan", "Scan", "d", "c", "idle", 0.0, "process"), clientFor) }
        yield()

        assertTrue(viewModel.sections.value.isEmpty())

        gate.complete(Unit)
        loadJob.join()
        runJob.join()

        val section = viewModel.sections.value.first { it.server.id == "srv-x" }
        assertEquals("running", section.result.getOrThrow().first().state)
    }

    @Test
    fun `a failed action surfaces an error message instead of failing silently`() = runTest {
        val viewModel = AggregateJobsViewModel()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond(V2Fixtures.problem(409, "conflict", "already running"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(V2Fixtures.adminTasksPage(taskJson("scan")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }

        viewModel.load(listOf(serverA), clientFor)
        assertNull(viewModel.errorMessage.value)

        viewModel.run(serverA, AdminTask("scan", "Scan", "d", "c", "idle", 0.0, "process"), clientFor)

        assertNotNull("a failed action must surface an error, not fail silently", viewModel.errorMessage.value)
    }
}
