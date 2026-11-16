package com.bloemstudios.admin.requests

import com.bloemstudios.admin.auth.V2Fixtures
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RequestsViewModelTest {
    @Test
    fun `decline sends the empty JSON object accepted by the server decoder`() = runTest {
        var declineBody: String? = null
        var declineContentType: ContentType? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/decline")) {
                declineBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes().decodeToString()
                declineContentType = request.body.contentType
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = RequestsViewModel()
        val target = MediaRequest("req-1", "tmdb", "movie", "X", "queued", "pending", "1")

        viewModel.decline(target, client)

        assertEquals("{}", declineBody)
        assertEquals(ContentType.Application.Json, declineContentType)
    }

    @Test
    fun `decline sends the reason`() = runTest {
        var path: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                path = request.url.encodedPath
                body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond(V2Fixtures.mediaRequest("req-1", "pending", "declined"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = RequestsViewModel()
        val target = MediaRequest("req-1", "tmdb", "movie", "X", "queued", "pending", "1")

        viewModel.decline(target, client, "Already in the library")

        assertEquals("/api/v2/admin/requests/req-1/decline", path)
        assertEquals("""{"reason":"Already in the library"}""", body)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `retry and cancel post to v2 with an empty object`() = runTest {
        val posts = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts += request.url.encodedPath to (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond(V2Fixtures.mediaRequest("req-1"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = RequestsViewModel()
        val target = MediaRequest("req-1", "tmdb", "movie", "X", "queued", "failed", "1")

        viewModel.retry(target, client)
        viewModel.cancel(target, client)

        assertEquals(listOf("/api/v2/admin/requests/req-1/retry" to "{}", "/api/v2/admin/requests/req-1/cancel" to "{}"), posts)
    }

    @Test
    fun `the queue reads the counts then one page of the opened view`() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            respond(
                if (request.url.encodedPath.endsWith("/counts")) """{"needs_approval":2,"in_progress":5,"failed":1,"done":9}"""
                else V2Fixtures.mediaRequestsPage(V2Fixtures.mediaRequest("r1"), V2Fixtures.mediaRequest("r2", optional = "")),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = RequestsViewModel()

        viewModel.load(client)

        assertEquals(listOf("r1", "r2"), viewModel.state.value.requests.map { it.id })
        assertEquals("1", viewModel.state.value.requests[0].requested_by_user_id)
        assertNull(viewModel.state.value.requests[1].requested_by_user_id)
        assertEquals("/api/v2/admin/requests/counts", seen[0])
        assertEquals("/api/v2/admin/requests?view=needs_approval&limit=50", seen[1])
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load decodes requests envelope`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.mediaRequestsPage(V2Fixtures.mediaRequest("139513934062288898", "queued", "failed", "One Second After", """"requested_by_user_id":"68","last_error":"one or more fulfillment targets failed"""")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = RequestsViewModel()

        viewModel.load(client)

        assertEquals(1, viewModel.state.value.requests.size)
        assertEquals("One Second After", viewModel.state.value.requests.first().title)
    }

    @Test
    fun `approve posts to approve endpoint`() = runTest {
        // approve() reloads requests on success (reload only on success),
        // so a second GET request follows the mutating POST. Capture every
        // request path and assert on the first one — the mutating call
        // this test is about — rather than a single var that the reload's
        // GET would silently overwrite.
        val capturedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath)
            respond(content = V2Fixtures.mediaRequestsPage(), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = RequestsViewModel()
        val target = MediaRequest("req-1", "tmdb", "movie", "X", "queued", "pending", "1")

        viewModel.approve(target, client)

        assertEquals("/api/v2/admin/requests/req-1/approve", capturedPaths.first())
    }

    @Test
    fun `successful approval followed by failed reload reports only load failure`() = runTest {
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) respond("", HttpStatusCode.NoContent)
            else respond(V2Fixtures.problem(503, "dependency_unavailable", "Reload failed"), HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = RequestsViewModel()
            vm.approve(MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1"), client)
            assertNull(vm.actionError.value)
            assertEquals(com.bloemstudios.admin.network.ErrorText.Raw("Reload failed"), vm.loadError.value)
            assertEquals(vm.loadError.value, vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `conflicting action is dropped while successful mutation is still reloading`() = runTest {
        val reloading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val posts = AtomicInteger()
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts.incrementAndGet()
                respond("", HttpStatusCode.NoContent)
            } else {
                reloading.complete(Unit)
                release.await()
                respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val vm = RequestsViewModel()
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "failed", "1")
            val first = launch { vm.approve(target, client) }
            reloading.await()
            assertEquals(setOf("r"), vm.pendingKeys.value)
            val conflicting = launch { vm.cancel(target, client) }
            // Both intents have entered the view model before reconciliation completes.
            runCurrent()
            release.complete(Unit)
            first.join(); conflicting.join()
            assertTrue(vm.pendingKeys.value.isEmpty())
            assertEquals(1, posts.get())
        } finally { release.complete(Unit); client.close() }
    }

    @Test
    fun `cancelled approval releases entity for a later explicit retry`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val block = CompletableDeferred<Unit>()
        val posts = AtomicInteger()
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                if (posts.incrementAndGet() == 1) { entered.complete(Unit); block.await() }
                respond("", HttpStatusCode.NoContent)
            } else respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = RequestsViewModel()
            val target = MediaRequest("r", "test", "movie", "Test", "queued", "failed", "1")
            val first = launch { vm.approve(target, client) }
            entered.await()
            first.cancelAndJoin()
            assertTrue(vm.pendingKeys.value.isEmpty())
            vm.retry(target, client)
            assertEquals(2, posts.get())
            assertNull(vm.errorMessage.value)
        } finally { block.complete(Unit); client.close() }
    }

    @Test
    fun `action error stays prioritized until dismissed without clearing load error`() = runTest {
        var failLoad = true
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> respond(V2Fixtures.problem(409, "conflict", "Action refused"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
                failLoad -> respond(V2Fixtures.problem(503, "dependency_unavailable", "Load unavailable"), HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val vm = RequestsViewModel()
            vm.load(client)
            vm.approve(MediaRequest("r", "test", "movie", "Test", "queued", "pending", "1"), client)
            assertEquals(com.bloemstudios.admin.network.ErrorText.Raw("Action refused"), vm.errorMessage.value)
            vm.dismissActionError()
            assertEquals(com.bloemstudios.admin.network.ErrorText.Raw("Load unavailable"), vm.errorMessage.value)
            failLoad = false
            vm.load(client)
            assertNull(vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `delayed older success cannot clear newer action failure`() = runTest {
        assertDelayedActionOutcome(olderFails = false, newerFails = true, tryDuplicate = true)
    }

    @Test
    fun `delayed older failure cannot replace newer action success`() = runTest {
        assertDelayedActionOutcome(olderFails = true, newerFails = false)
    }

    @Test
    fun `delayed older failure cannot resurrect a dismissed newer action failure`() = runTest {
        assertDelayedActionOutcome(olderFails = true, newerFails = true, dismissNewer = true)
    }

    private suspend fun assertDelayedActionOutcome(
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
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts.incrementAndGet()
                val older = request.url.encodedPath.contains("/old/")
                if (older) { olderEntered.complete(Unit); releaseOlder.await() }
                else if (tryDuplicate) { newerEntered.complete(Unit); releaseNewer.await() }
                if (if (older) olderFails else newerFails) {
                    val message = if (older) "Older failed" else "Newer failed"
                    respond(V2Fixtures.problem(409, "conflict", message), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
                } else respond("", HttpStatusCode.NoContent)
            } else respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val vm = RequestsViewModel()
            val older = MediaRequest("old", "test", "movie", "Old", "queued", "pending", "1")
            val newer = MediaRequest("new", "test", "movie", "New", "queued", "pending", "1")
            val delayed = launch { vm.approve(older, client) }
            olderEntered.await()
            if (tryDuplicate) {
                val newerJob = launch { vm.approve(newer, client) }
                newerEntered.await()
                vm.retry(older, client)
                releaseNewer.complete(Unit); newerJob.join()
            } else vm.approve(newer, client)
            val newerError = if (newerFails) com.bloemstudios.admin.network.ErrorText.Raw("Newer failed") else null
            assertEquals(newerError, vm.actionError.value)
            if (dismissNewer) vm.dismissActionError()
            val expected = if (dismissNewer) null else newerError
            releaseOlder.complete(Unit); delayed.join()
            assertEquals(expected, vm.actionError.value)
            assertEquals(expected, vm.errorMessage.value)
            assertTrue(vm.pendingKeys.value.isEmpty())
            assertEquals(2, posts.get())
        } finally { releaseOlder.complete(Unit); releaseNewer.complete(Unit); client.close() }
    }
}
