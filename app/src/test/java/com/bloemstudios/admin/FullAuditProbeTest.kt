package com.bloemstudios.admin

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.users.UserSessionsViewModel
import com.bloemstudios.admin.requests.RequestsViewModel
import com.bloemstudios.admin.requests.MediaRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FullAuditProbeTest {
    @Test fun sessionFailureMustSurviveSuccessfulDeviceLoad() = runTest {
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.url.encodedPath.endsWith("login-sessions"))
                respond(V2Fixtures.problem(500, "internal_error", "Session lookup failed"), HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType,"application/json"))
            else respond(V2Fixtures.adminUserDevicesPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val vm = UserSessionsViewModel()
            vm.loadSessions("42", client)
            assertNotNull(vm.errorMessage.value)
            vm.loadDevices("42", client)
            assertNotNull("Device success must not hide a failed session lookup", vm.errorMessage.value)
        } finally { client.close() }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun repeatedApproveWhilePendingMustSendOnce() = runTest {
        val pending = CompletableDeferred<Unit>()
        val posted = AtomicInteger()
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posted.incrementAndGet()
                pending.await()
                respond("", HttpStatusCode.NoContent)
            } else respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val vm = RequestsViewModel()
            val request = MediaRequest("r", "test", "movie", "Test", "queued", "pending", "42")
            val first = async { vm.approve(request, client) }
            val second = async { vm.approve(request, client) }
            runCurrent()
            pending.complete(Unit)
            first.await(); second.await()
            assertEquals("Repeated approval while pending must be coalesced", 1, posted.get())
        } finally { pending.complete(Unit); client.close() }
    }
    @Test fun failedApprovalMustNotDisappearOnBackgroundRefresh() = runTest {
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            if (request.method == HttpMethod.Post)
                respond(V2Fixtures.problem(409, "conflict", "Approval refused"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType,"application/json"))
            else respond(V2Fixtures.mediaRequestsPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val vm = RequestsViewModel()
            vm.approve(MediaRequest("r", "test", "movie", "Test", "queued", "pending", "42"), client)
            assertNotNull(vm.errorMessage.value)
            vm.load(client)
            assertNotNull("Refresh must not erase unacknowledged action failure", vm.errorMessage.value)
        } finally { client.close() }
    }
}
