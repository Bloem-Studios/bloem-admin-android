package com.bloemstudios.admin.invitations

import androidx.lifecycle.ViewModelProvider
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.SensitiveViewModelStoreRegistry
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InvitationsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problemJson = headersOf(HttpHeaders.ContentType, "application/problem+json")

    private fun invitation(id: String = "1", email: String = "new@example.com") =
        Invitation(
            id = id, email = email, delivery = "email_sent", role = "user", create_profile = true, show_tour = true,
            invited_by = "1", status = "pending", expires_at = "2026-09-01T00:00:00.000Z", created_at = "2026-08-25T12:00:00.000Z"
        )

    @Test
    fun `load decodes a full invitation with string ids`() = runTest {
        val full = V2Fixtures.adminInvitation(
            id = "11", email = "user@example.com",
            optional = """"access_group_id":"5","accepted_at":"2026-08-26T00:00:00.000Z","accepted_user_id":"6""""
        ).replace(""""library_ids":null""", """"library_ids":["1","2"]""").replace(""""note":"""", """"note":"some note""")
        val engine = MockEngine { respond(V2Fixtures.adminInvitationsPage(full), HttpStatusCode.OK, json) }
        val viewModel = InvitationsViewModel()

        viewModel.load(ApiClient("https://example.test", null, engine))

        val invitation = viewModel.invitations.value.single()
        assertEquals("11", invitation.id)
        assertEquals("5", invitation.access_group_id)
        assertEquals(listOf("1", "2"), invitation.library_ids)
        assertEquals("some note", invitation.note)
        assertEquals("Admin", invitation.invited_by_name)
        assertEquals("email_sent", invitation.delivery)
        assertEquals("2026-08-26T00:00:00.000Z", invitation.accepted_at)
        assertEquals("6", invitation.accepted_user_id)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load decodes an invitation with only the always-present fields`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.adminInvitationsPage(V2Fixtures.adminInvitation(email = "")), HttpStatusCode.OK, json)
        }
        val viewModel = InvitationsViewModel()

        viewModel.load(ApiClient("https://example.test", null, engine))

        val invitation = viewModel.invitations.value.single()
        assertEquals("", invitation.email)
        assertNull(invitation.access_group_id)
        assertNull(invitation.library_ids)
        assertNull(invitation.accepted_at)
        assertNull(invitation.accepted_user_id)
    }

    @Test
    fun `load follows the cursor to the last page`() = runTest {
        val cursors = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cursors += request.url.parameters["cursor"]
            if (request.url.parameters["cursor"] == null) {
                respond(V2Fixtures.adminInvitationsPage(V2Fixtures.adminInvitation("1"), nextCursor = "p2"), HttpStatusCode.OK, json)
            } else {
                respond(V2Fixtures.adminInvitationsPage(V2Fixtures.adminInvitation("2")), HttpStatusCode.OK, json)
            }
        }
        val viewModel = InvitationsViewModel()

        viewModel.load(ApiClient("https://example.test", null, engine))

        assertEquals(listOf<String?>(null, "p2"), cursors)
        assertEquals(listOf("1", "2"), viewModel.invitations.value.map { it.id })
    }

    @Test
    fun `invitation create shows the claim link once`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            if (requestCount == 1) {
                capturedPath = request.url.encodedPath
                capturedMethod = request.method
                capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond(V2Fixtures.invitationDelivery("sent", "https://example.com/claim/xyz"), HttpStatusCode.Created, json)
            } else {
                respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
            }
        }
        val viewModel = InvitationsViewModel()

        val result = viewModel.send("new@example.com", "user", "welcome!", ApiClient("https://example.test", null, engine))

        assertTrue(result is InvitationActionResult.Success)
        result as InvitationActionResult.Success
        assertEquals("new@example.com", result.invitation.email)
        assertTrue(result.emailSent)
        assertFalse(result.deliveryFailed)
        assertEquals("https://example.com/claim/xyz", result.claimUrl)
        assertEquals("/api/v2/admin/invitations", capturedPath)
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("""{"email":"new@example.com","role":"user","note":"welcome!"}""", capturedBody)
        assertEquals(2, requestCount)
        assertEquals(result, viewModel.pendingActionResult.value)

        viewModel.consumePendingActionResult()

        assertNull(viewModel.pendingActionResult.value)
        // The reloaded list never carries the link.
        assertTrue(viewModel.invitations.value.isEmpty())
    }

    @Test
    fun `email delivery failure is flagged`() = runTest {
        val viewModel = InvitationsViewModel()

        val failed = viewModel.send("new@example.com", "user", null, sendClient("failed_or_unknown"))
        failed as InvitationActionResult.Success
        assertTrue(failed.deliveryFailed)
        assertFalse(failed.emailSent)
        // The link is still shown so the admin can hand it over directly.
        assertEquals("https://example.test/claim/secret", failed.claimUrl)

        val sent = viewModel.send("new@example.com", "user", null, sendClient("sent")) as InvitationActionResult.Success
        assertFalse(sent.deliveryFailed)
        assertTrue(sent.emailSent)

        // Nothing was emailed, but nothing failed either.
        for (status in listOf("not_configured", "not_requested")) {
            val other = viewModel.send("new@example.com", "user", null, sendClient(status)) as InvitationActionResult.Success
            assertFalse(status, other.deliveryFailed)
            assertFalse(status, other.emailSent)
        }
    }

    @Test
    fun `an unrecognised delivery status is treated as a failure`() = runTest {
        val result = InvitationsViewModel().send("a@example.com", "user", null, sendClient("something_new")) as InvitationActionResult.Success
        assertTrue(result.deliveryFailed)
    }

    @Test
    fun `create is submitted once and never retried`() = runTest {
        var posts = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                posts++
                respond(V2Fixtures.problem(401, "token_refresh_required", "Refresh"), HttpStatusCode.Unauthorized, problemJson)
            } else respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://example.test", "tok", engine)

        val result = InvitationsViewModel().send("new@example.com", "user", null, client)

        assertTrue(result is InvitationActionResult.Failure)
        assertEquals(1, posts)
    }

    @Test
    fun `closing the surface rejects a late one-time claim URL`() = runTest {
        val requestEntered = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            if (requestCount == 1) {
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(V2Fixtures.invitationDelivery("failed_or_unknown", "https://example.test/claim/secret"), HttpStatusCode.Created, json)
            } else {
                respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
            }
        }
        val viewModel = InvitationsViewModel()
        val client = ApiClient("https://example.test", null, engine)
        val pending = async { viewModel.send("new@example.com", "user", null, client) }
        requestEntered.await()

        viewModel.closeSurface()
        releaseResponse.complete(Unit)
        pending.await()
        runCurrent()

        assertNull(viewModel.pendingActionResult.value)
    }

    @Test
    fun `configuration recreation preserves a visible one-time claim URL`() = runTest {
        val viewModel = InvitationsViewModel()
        val result = viewModel.send("new@example.com", "user", null, sendClient("failed_or_unknown"))

        viewModel.onSurfaceHidden(isChangingConfigurations = true)

        assertEquals(result, viewModel.pendingActionResult.value)
    }

    @Test
    fun `leaving the invitations surface consumes its one-time claim URL`() = runTest {
        val viewModel = InvitationsViewModel()
        viewModel.send("new@example.com", "user", null, sendClient("failed_or_unknown"))

        viewModel.onSurfaceHidden(isChangingConfigurations = false)

        assertNull(viewModel.pendingActionResult.value)
    }

    @Test
    fun `principal generation change consumes its one-time claim URL`() = runTest {
        val registry = SensitiveViewModelStoreRegistry()
        val firstScope = registry.scope("server-a", principalGeneration = 1)
        val viewModel = ViewModelProvider.create(firstScope.owner)[InvitationsViewModel::class.java]
        viewModel.send("new@example.com", "user", null, sendClient("failed_or_unknown"))

        registry.scope("server-a", principalGeneration = 2)

        assertNull(viewModel.pendingActionResult.value)
    }

    @Test
    fun `send surfaces a conflict problem detail`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.problem(409, "conflict", "An account with this email already exists"), HttpStatusCode.Conflict, problemJson)
        }
        val viewModel = InvitationsViewModel()

        val result = viewModel.send("dupe@example.com", "user", null, ApiClient("https://example.test", null, engine))

        assertTrue(result is InvitationActionResult.Failure)
        assertEquals(ErrorText.Raw("An account with this email already exists"), (result as InvitationActionResult.Failure).message)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `send surfaces a permission problem detail`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.problem(403, "permission_denied", "You may not grant this role"), HttpStatusCode.Forbidden, problemJson)
        }

        val result = InvitationsViewModel().send("new@example.com", "admin", null, ApiClient("https://example.test", null, engine))

        assertEquals(ErrorText.Raw("You may not grant this role"), (result as InvitationActionResult.Failure).message)
    }

    @Test
    fun `resend posts to the resend endpoint, reports delivery and reloads`() = runTest {
        val capturedPaths = mutableListOf<String>()
        val capturedMethods = mutableListOf<HttpMethod>()
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath)
            capturedMethods.add(request.method)
            if (request.method == HttpMethod.Post) {
                respond(V2Fixtures.invitationDelivery("not_configured", "https://example.test/claim/new"), HttpStatusCode.Created, json)
            } else respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
        }
        val viewModel = InvitationsViewModel()

        val result = viewModel.resend(invitation("1"), ApiClient("https://example.test", null, engine))

        result as InvitationActionResult.Success
        assertFalse(result.emailSent)
        assertFalse(result.deliveryFailed)
        assertEquals("https://example.test/claim/new", result.claimUrl)
        assertEquals("/api/v2/admin/invitations/1/resend", capturedPaths.first())
        assertEquals(HttpMethod.Post, capturedMethods.first())
        assertEquals("/api/v2/admin/invitations", capturedPaths[1])
    }

    @Test
    fun `revoke sends DELETE without a precondition and reloads on success`() = runTest {
        val capturedPaths = mutableListOf<String>()
        val capturedMethods = mutableListOf<HttpMethod>()
        var ifMatch: String? = null
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath)
            capturedMethods.add(request.method)
            if (request.method == HttpMethod.Delete) {
                ifMatch = request.headers[HttpHeaders.IfMatch]
                respond("", HttpStatusCode.NoContent)
            } else respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
        }
        val viewModel = InvitationsViewModel()

        val result = viewModel.revoke(invitation("7", "gone@example.com"), ApiClient("https://example.test", null, engine))

        assertTrue(result is RevokeInvitationResult.Success)
        assertEquals("/api/v2/admin/invitations/7", capturedPaths.first())
        assertEquals(HttpMethod.Delete, capturedMethods.first())
        assertNull(ifMatch)
        assertEquals("/api/v2/admin/invitations", capturedPaths[1])
    }

    @Test
    fun `revoke surfaces a 404`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.problem(404, "not_found", "Invitation not found"), HttpStatusCode.NotFound, problemJson)
        }

        val result = InvitationsViewModel().revoke(invitation("9"), ApiClient("https://example.test", null, engine))

        assertEquals(ErrorText.Raw("Invitation not found"), (result as RevokeInvitationResult.Failure).message)
    }

    @Test
    fun `load surfaces a 401 and still invokes onUnauthorized`() = runTest {
        var unauthorizedCalled = false
        val engine = MockEngine {
            respond(V2Fixtures.problem(401, "session_expired", "Session expired"), HttpStatusCode.Unauthorized, problemJson)
        }
        val client = ApiClient("https://example.test", "expired-token", engine)
        client.onUnauthorized = { unauthorizedCalled = true }
        val viewModel = InvitationsViewModel()

        viewModel.load(client)

        assertTrue(unauthorizedCalled)
        assertEquals(ErrorText.Raw("Session expired"), viewModel.errorMessage.value)
        assertTrue(viewModel.invitations.value.isEmpty())
    }

    private fun sendClient(deliveryStatus: String): ApiClient {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            if (requestCount == 1) {
                respond(V2Fixtures.invitationDelivery(deliveryStatus, "https://example.test/claim/secret"), HttpStatusCode.Created, json)
            } else {
                respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
            }
        }
        return ApiClient("https://example.test", null, engine)
    }

    private fun cancelledReloadClient(reloadStarted: CompletableDeferred<Unit>): ApiClient {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond(V2Fixtures.invitationDelivery(claimUrl = "https://example.test/claim/one-time"), HttpStatusCode.Created, json)
            } else {
                reloadStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
                respond(V2Fixtures.adminInvitationsPage(), HttpStatusCode.OK, json)
            }
        }
        return ApiClient("https://example.test", null, engine)
    }

    @Test
    fun `send keeps the claim link when the reload after the 201 is cancelled`() = runTest {
        val reloadStarted = CompletableDeferred<Unit>()
        val viewModel = InvitationsViewModel()
        val job = launch { viewModel.send("new@example.com", "user", null, cancelledReloadClient(reloadStarted)) }
        runCurrent()
        reloadStarted.await()
        job.cancel()
        job.join()

        val kept = viewModel.pendingActionResult.value as InvitationActionResult.Success
        assertEquals("https://example.test/claim/one-time", kept.claimUrl)
    }

    @Test
    fun `resend keeps the claim link when the reload after the 201 is cancelled`() = runTest {
        val reloadStarted = CompletableDeferred<Unit>()
        val viewModel = InvitationsViewModel()
        val job = launch { viewModel.resend(invitation(), cancelledReloadClient(reloadStarted)) }
        runCurrent()
        reloadStarted.await()
        job.cancel()
        job.join()

        val kept = viewModel.pendingActionResult.value as InvitationActionResult.Success
        assertEquals("https://example.test/claim/one-time", kept.claimUrl)
    }
}
