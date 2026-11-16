package com.bloemstudios.admin.requests

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

private fun requestJson(id: String, status: String = "queued", outcome: String = "pending") =
    V2Fixtures.mediaRequest(id, status, outcome)

private fun requestsResponseJson(vararg ids: String) = V2Fixtures.mediaRequestsPage(*ids.map { requestJson(it) }.toTypedArray())

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AggregateRequestsViewModelTest {
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
    fun `load sections requests per server`() = runTest {
        val viewModel = AggregateRequestsViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(requestsResponseJson("r1")),
            "srv-b" to clientRespondingWith(requestsResponseJson("r2", "r3"))
        )

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf("r1"), byServer.getValue("srv-a").result.getOrThrow().map { it.id })
        assertEquals(listOf("r2", "r3"), byServer.getValue("srv-b").result.getOrThrow().map { it.id })
    }

    @Test
    fun `a failing server produces an error section and does not affect the other`() = runTest {
        val viewModel = AggregateRequestsViewModel()
        val clients = mapOf("srv-a" to clientRespondingWith(requestsResponseJson("r1")), "srv-b" to failingClient())

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertTrue(byServer.getValue("srv-b").result.isFailure)
    }

    @Test
    fun `approving a request on one server reloads only that server's section`() = runTest {
        val viewModel = AggregateRequestsViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(requestsResponseJson("r1")),
            "srv-b" to clientRespondingWith(requestsResponseJson("r2"))
        )
        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }
        val originalBSection = viewModel.sections.value.first { it.server.id == "srv-b" }
        val requestA = viewModel.sections.value.first { it.server.id == "srv-a" }.result.getOrThrow().first()

        viewModel.approve(serverA, requestA) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertSame(originalBSection, byServer.getValue("srv-b"))
    }

    @Test
    fun `a slow poll cannot overwrite a faster action's result once both complete`() = runTest {
        val viewModel = AggregateRequestsViewModel()
        val server = AdminServer("srv-x", "https://x.test", "Server X")
        val gate = CompletableDeferred<Unit>()
        var blockedOnce = false
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && !blockedOnce -> {
                    blockedOnce = true
                    gate.await()
                    respond(requestsResponseJson("r1"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.method == HttpMethod.Post -> respond("", HttpStatusCode.OK)
                else -> respond(
                    V2Fixtures.mediaRequestsPage(requestJson("r1", status = "approved", outcome = "success")),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        val target = MediaRequest("r1", "radarr", "movie", "Movie r1", "queued", "pending", "1")

        val loadJob = launch { viewModel.load(listOf(server), clientFor) }
        yield()
        val actJob = launch { viewModel.approve(server, target, clientFor) }
        yield()

        assertTrue(viewModel.sections.value.isEmpty())

        gate.complete(Unit)
        loadJob.join()
        actJob.join()

        val section = viewModel.sections.value.first { it.server.id == "srv-x" }
        assertEquals("approved", section.result.getOrThrow().first().status)
    }

    @Test
    fun `a failed action surfaces an error message instead of failing silently`() = runTest {
        val viewModel = AggregateRequestsViewModel()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond(V2Fixtures.problem(409, "conflict", "already approved"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(requestsResponseJson("r1"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        val target = MediaRequest("r1", "radarr", "movie", "Movie r1", "queued", "pending", "1")

        viewModel.load(listOf(serverA), clientFor)
        assertNull(viewModel.errorMessage.value)

        viewModel.approve(serverA, target, clientFor)

        assertNotNull("a failed action must surface an error, not fail silently", viewModel.errorMessage.value)
    }

    @Test
    fun `conflicting intent is dropped even while waiting for server poll lock`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val posts = AtomicInteger()
        var initial = true
        val client = ApiClient("https://a.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts.incrementAndGet()
                respond("", HttpStatusCode.NoContent)
            } else {
                if (initial) { initial = false; entered.complete(Unit); release.await() }
                respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val vm = AggregateRequestsViewModel()
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")
            val poll = launch { vm.load(listOf(serverA)) { client } }
            entered.await()
            val first = launch { vm.approve(serverA, target) { client } }
            val second = launch { vm.decline(serverA, target) { client } }
            runCurrent()
            assertEquals(setOf("srv-a:r"), vm.pendingKeys.value)
            release.complete(Unit)
            poll.join(); first.join(); second.join()
            assertTrue(vm.pendingKeys.value.isEmpty())
            assertEquals(1, posts.get())
        } finally { release.complete(Unit); client.close() }
    }

    @Test
    fun `same request id on another server can finish while first server is pending`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val postedA = AtomicInteger()
        val postedB = AtomicInteger()
        val a = ApiClient("https://a.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) { postedA.incrementAndGet(); entered.complete(Unit); release.await(); respond("", HttpStatusCode.NoContent) }
            else respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val b = ApiClient("https://b.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) { postedB.incrementAndGet(); respond("", HttpStatusCode.NoContent) }
            else respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = AggregateRequestsViewModel()
            val clients: (AdminServer) -> ApiClient = { if (it.id == serverA.id) a else b }
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")
            vm.load(listOf(serverA, serverB), clients)
            val first = launch { vm.approve(serverA, target, clients) }
            entered.await()
            vm.approve(serverB, target, clients)
            assertFalse(first.isCompleted)
            assertEquals(1, postedB.get())
            release.complete(Unit); first.join()
            assertEquals(1, postedA.get())
        } finally { release.complete(Unit); a.close(); b.close() }
    }

    @Test
    fun `action failure survives successful aggregate refresh`() = runTest {
        val vm = AggregateRequestsViewModel()
        val client = ApiClient("https://a.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) respond(V2Fixtures.problem(409, "conflict", "The request was refused."), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            vm.approve(serverA, MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")) { client }
            val failure = vm.errorMessage.value
            vm.load(listOf(serverA)) { client }
            assertNotNull(failure)
            assertEquals(failure, vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `successful aggregate action reload failure is independently dismissible from action error`() = runTest {
        var failPost = true
        var failLoad = true
        val client = ApiClient("https://a.test", null, MockEngine { request ->
            when {
                request.method == HttpMethod.Post && failPost -> respond(V2Fixtures.problem(409, "conflict", "The request was refused."), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
                request.method == HttpMethod.Post -> respond("", HttpStatusCode.NoContent)
                failLoad -> respond(V2Fixtures.problem(503, "dependency_unavailable", "Reload unavailable"), HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val vm = AggregateRequestsViewModel()
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")
            vm.load(listOf(serverA)) { client }
            vm.approve(serverA, target) { client }
            assertNotNull(vm.actionError.value)
            assertNotEquals(vm.loadError.value, vm.errorMessage.value)
            vm.dismissActionError()
            assertEquals(vm.loadError.value, vm.errorMessage.value)
            failPost = false
            vm.approve(serverA, target) { client }
            assertNull(vm.actionError.value)
            assertNotNull(vm.loadError.value)
            failLoad = false
            vm.retrySection(serverA) { client }
            assertNull(vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `failed aggregate mutation still reconciles without erasing action outcome`() = runTest {
        val gets = AtomicInteger()
        val client = ApiClient("https://a.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) respond(V2Fixtures.problem(409, "conflict", "The request was refused."), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            else { gets.incrementAndGet(); respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        })
        try {
            val vm = AggregateRequestsViewModel()
            vm.load(listOf(serverA)) { client }
            vm.approve(serverA, MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")) { client }
            assertEquals(2, gets.get())
            assertEquals(com.bloemstudios.admin.network.ErrorText.Prefixed("Server A", com.bloemstudios.admin.network.ErrorText.Raw("The request was refused.")), vm.actionError.value)
            assertEquals(vm.actionError.value, vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `delayed older aggregate success cannot clear newer server action failure`() = runTest {
        assertDelayedServerOutcome(olderFails = false, newerFails = true, tryDuplicate = true)
    }

    @Test
    fun `delayed older aggregate failure cannot replace newer server action success`() = runTest {
        assertDelayedServerOutcome(olderFails = true, newerFails = false)
    }

    @Test
    fun `delayed older aggregate failure cannot resurrect dismissed newer failure`() = runTest {
        assertDelayedServerOutcome(olderFails = true, newerFails = true, dismissNewer = true)
    }

    private suspend fun assertDelayedServerOutcome(
        olderFails: Boolean,
        newerFails: Boolean,
        dismissNewer: Boolean = false,
        tryDuplicate: Boolean = false
    ) = kotlinx.coroutines.coroutineScope {
        val olderEntered = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val newerEntered = CompletableDeferred<Unit>()
        val releaseNewer = CompletableDeferred<Unit>()
        val posts = AtomicInteger()
        fun client(older: Boolean) = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts.incrementAndGet()
                if (older) { olderEntered.complete(Unit); releaseOlder.await() }
                else if (tryDuplicate) { newerEntered.complete(Unit); releaseNewer.await() }
                if (if (older) olderFails else newerFails) {
                    val message = if (older) "Older failed" else "Newer failed"
                    respond(V2Fixtures.problem(409, "conflict", message), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
                } else respond("", HttpStatusCode.NoContent)
            } else respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val a = client(true)
        val b = client(false)
        try {
            val vm = AggregateRequestsViewModel()
            val clientFor: (AdminServer) -> ApiClient = { if (it.id == serverA.id) a else b }
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")
            vm.load(listOf(serverA, serverB), clientFor)
            val delayed = launch { vm.approve(serverA, target, clientFor) }
            olderEntered.await()
            if (tryDuplicate) {
                val newerJob = launch { vm.approve(serverB, target, clientFor) }
                newerEntered.await()
                vm.retryRequest(serverA, target, clientFor)
                releaseNewer.complete(Unit); newerJob.join()
            } else vm.approve(serverB, target, clientFor)
            val newerError = if (newerFails) com.bloemstudios.admin.network.ErrorText.Prefixed("Server B", com.bloemstudios.admin.network.ErrorText.Raw("Newer failed")) else null
            assertEquals(newerError, vm.actionError.value)
            if (dismissNewer) vm.dismissActionError()
            val expected = if (dismissNewer) null else newerError
            releaseOlder.complete(Unit); delayed.join()
            assertEquals(expected, vm.actionError.value)
            assertEquals(expected, vm.errorMessage.value)
            assertTrue(vm.pendingKeys.value.isEmpty())
            assertEquals(2, posts.get())
        } finally { releaseOlder.complete(Unit); releaseNewer.complete(Unit); a.close(); b.close() }
    }

    @Test
    fun `older action accepted before server lock cannot clear newer result after acquiring lock`() = runTest {
        val pollEntered = CompletableDeferred<Unit>()
        val releasePoll = CompletableDeferred<Unit>()
        var firstGet = true
        val a = ApiClient("https://a.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) respond("", HttpStatusCode.NoContent)
            else {
                if (firstGet) { firstGet = false; pollEntered.complete(Unit); releasePoll.await() }
                respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val b = ApiClient("https://b.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) respond(V2Fixtures.problem(409, "conflict", "Newer failed"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(requestsResponseJson("r"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = AggregateRequestsViewModel()
            val clientFor: (AdminServer) -> ApiClient = { if (it.id == serverA.id) a else b }
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1")
            val poll = launch { vm.load(listOf(serverA), clientFor) }
            pollEntered.await()
            val older = launch { vm.approve(serverA, target, clientFor) }
            runCurrent()
            assertEquals(setOf("srv-a:r"), vm.pendingKeys.value)
            vm.approve(serverB, target, clientFor)
            val newerError = com.bloemstudios.admin.network.ErrorText.Prefixed("Server B", com.bloemstudios.admin.network.ErrorText.Raw("Newer failed"))
            assertEquals(newerError, vm.actionError.value)
            releasePoll.complete(Unit); poll.join(); older.join()
            assertEquals(newerError, vm.actionError.value)
            assertEquals(newerError, vm.errorMessage.value)
            assertTrue(vm.pendingKeys.value.isEmpty())
        } finally { releasePoll.complete(Unit); a.close(); b.close() }
    }
}
