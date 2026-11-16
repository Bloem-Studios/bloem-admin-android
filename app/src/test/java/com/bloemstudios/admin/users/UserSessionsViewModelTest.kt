package com.bloemstudios.admin.users

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UserSessionsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problemJson = headersOf(HttpHeaders.ContentType, "application/problem+json")

    private fun client(handler: MockRequestHandler) = ApiClient("https://example.test", "tok", MockEngine(handler))

    @Test
    fun `no device revoke action is offered`() {
        assertFalse(UserSessionsViewModel::class.java.methods.any { it.name.contains("revokeDevice", ignoreCase = true) })
        val dialogState = UserSessionsViewModel::class.java.methods.map { it.name }
        assertFalse(dialogState.any { it.contains("device", ignoreCase = true) && it.contains("revoke", ignoreCase = true) })
    }

    @Test
    fun `loadSessions decodes a full session`() = runTest {
        val client = client {
            respond(V2Fixtures.loginSessionsPage(V2Fixtures.loginSession("session-1", "iPhone", current = true, optional = """"device_id":"device-1","device_platform":"iOS"""")), HttpStatusCode.OK, json)
        }
        val viewModel = UserSessionsViewModel()

        viewModel.loadSessions("42", client)

        val session = viewModel.sessions.value.single()
        assertEquals("session-1", session.id)
        assertEquals("iPhone", session.device_name)
        assertEquals("203.0.113.7", session.ip_address)
        assertEquals("device-1", session.device_id)
        assertEquals("iOS", session.device_platform)
        assertTrue(session.current)
        assertNull(session.last_seen_at)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `loadSessions follows cursors`() = runTest {
        val paths = mutableListOf<String>()
        val client = client { request ->
            paths += request.url.encodedPath + "?" + request.url.parameters["cursor"]
            respond(
                if (request.url.parameters["cursor"] == null) V2Fixtures.loginSessionsPage(V2Fixtures.loginSession("a"), nextCursor = "n2")
                else V2Fixtures.loginSessionsPage(V2Fixtures.loginSession("b")),
                HttpStatusCode.OK, json
            )
        }
        val viewModel = UserSessionsViewModel()

        viewModel.loadSessions("42", client)

        assertEquals(listOf("/api/v2/admin/users/42/login-sessions?null", "/api/v2/admin/users/42/login-sessions?n2"), paths)
        assertEquals(listOf("a", "b"), viewModel.sessions.value.map { it.id })
    }

    @Test
    fun `loadSessions surfaces an error`() = runTest {
        val client = client { respond(V2Fixtures.problem(403, "permission_denied", "Admin access required"), HttpStatusCode.Forbidden, problemJson) }
        val viewModel = UserSessionsViewModel()

        viewModel.loadSessions("42", client)

        assertEquals(ErrorText.Raw("Admin access required"), viewModel.errorMessage.value)
        assertTrue(viewModel.sessions.value.isEmpty())
    }

    @Test
    fun `loadDevices decodes a device`() = runTest {
        val client = client { respond(V2Fixtures.adminUserDevicesPage(V2Fixtures.adminUserDevice("device-1", "iPhone")), HttpStatusCode.OK, json) }
        val viewModel = UserSessionsViewModel()

        viewModel.loadDevices("42", client)

        val device = viewModel.devices.value.single()
        assertEquals("device-1", device.device_id)
        assertEquals("iPhone", device.device_name)
        assertEquals("tvOS", device.device_platform)
        assertEquals(2, device.override_count)
        assertEquals("Household", device.profiles.single().profile_name)
        assertNull(device.last_updated)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `loadDevices surfaces an error`() = runTest {
        val client = client { respond(V2Fixtures.problem(403, "permission_denied", "Admin access required"), HttpStatusCode.Forbidden, problemJson) }
        val viewModel = UserSessionsViewModel()

        viewModel.loadDevices("42", client)

        assertEquals(ErrorText.Raw("Admin access required"), viewModel.errorMessage.value)
        assertTrue(viewModel.devices.value.isEmpty())
    }

    @Test
    fun `revokeSession sends DELETE to the single-session endpoint and reloads on success`() = runTest {
        val calls = mutableListOf<String>()
        val client = client { request ->
            calls += request.method.value + " " + request.url.encodedPath
            if (request.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent)
            else respond(V2Fixtures.loginSessionsPage(), HttpStatusCode.OK, json)
        }
        val viewModel = UserSessionsViewModel()

        val result = viewModel.revokeSession("42", "session-1", client)

        assertTrue(result is RevokeResult.Success)
        assertEquals(listOf("DELETE /api/v2/admin/users/42/login-sessions/session-1", "GET /api/v2/admin/users/42/login-sessions"), calls)
    }

    @Test
    fun `revokeSession surfaces the server's error and does not touch the shared error banner`() = runTest {
        val client = client { respond(V2Fixtures.problem(404, "not_found", "Session not found"), HttpStatusCode.NotFound, problemJson) }
        val viewModel = UserSessionsViewModel()

        val result = viewModel.revokeSession("42", "gone", client)

        assertEquals(RevokeResult.Failure(ErrorText.Raw("Session not found")), result)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `revoke all reports the revoked count`() = runTest {
        val calls = mutableListOf<String>()
        val client = client { request ->
            calls += request.method.value + " " + request.url.encodedPath
            if (request.method == HttpMethod.Delete) respond(V2Fixtures.loginSessionsRevoked(3), HttpStatusCode.OK, json)
            else respond(V2Fixtures.loginSessionsPage(), HttpStatusCode.OK, json)
        }
        val viewModel = UserSessionsViewModel()

        val result = viewModel.revokeAllSessions("42", client)

        assertEquals(RevokeResult.Success(3), result)
        assertEquals(listOf("DELETE /api/v2/admin/users/42/login-sessions", "GET /api/v2/admin/users/42/login-sessions"), calls)
    }

    @Test
    fun `revokeAllSessions surfaces the server's error`() = runTest {
        val client = client { respond(V2Fixtures.problem(500, "internal_error", "Something broke"), HttpStatusCode.InternalServerError, problemJson) }

        val result = UserSessionsViewModel().revokeAllSessions("42", client)

        assertEquals(RevokeResult.Failure(ErrorText.Raw("Something broke")), result)
    }

    @Test
    fun `successful session retry retains unresolved devices failure`() = runTest {
        val vm = UserSessionsViewModel()
        val client = client { request ->
            if (request.url.encodedPath.endsWith("devices")) respond(V2Fixtures.problem(503, "dependency_unavailable", "Devices unavailable"), HttpStatusCode.ServiceUnavailable, problemJson)
            else respond(V2Fixtures.loginSessionsPage(), HttpStatusCode.OK, json)
        }
        try {
            vm.loadDevices("42", client)
            vm.loadSessions("42", client)
            assertEquals(ErrorText.Raw("Devices unavailable"), vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `successful revoke followed by failed reload keeps success result and load failure`() = runTest {
        val client = client { request ->
            when {
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> respond(V2Fixtures.problem(503, "dependency_unavailable", "Reload failed"), HttpStatusCode.ServiceUnavailable, problemJson)
            }
        }
        try {
            val vm = UserSessionsViewModel()
            assertTrue(vm.revokeSession("42", "s", client) is RevokeResult.Success)
            assertEquals(ErrorText.Raw("Reload failed"), vm.errorMessage.value)
        } finally { client.close() }
    }

    @Test
    fun `sections load independently and failed session stays unloaded until successful retry`() = runTest {
        val sessionEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failSessions = true
        val client = client { request ->
            if (request.url.encodedPath.endsWith("login-sessions") && failSessions) {
                sessionEntered.complete(Unit); release.await()
                respond(V2Fixtures.problem(503, "dependency_unavailable", "Sessions unavailable"), HttpStatusCode.ServiceUnavailable, problemJson)
            } else if (request.url.encodedPath.endsWith("devices")) respond(V2Fixtures.adminUserDevicesPage(), HttpStatusCode.OK, json)
            else respond(V2Fixtures.loginSessionsPage(), HttpStatusCode.OK, json)
        }
        try {
            val vm = UserSessionsViewModel()
            assertFalse(vm.sessionsLoaded.value)
            assertFalse(vm.devicesLoaded.value)
            val sessionJob = launch { vm.loadSessions("42", client) }
            sessionEntered.await()
            assertTrue(vm.sessionsLoading.value)
            vm.loadDevices("42", client)
            assertTrue(vm.devicesLoaded.value)
            assertFalse(vm.devicesLoading.value)
            assertFalse(vm.sessionsLoaded.value)
            release.complete(Unit); sessionJob.join()
            assertFalse(vm.sessionsLoading.value)
            assertFalse(vm.sessionsLoaded.value)
            assertNotNull(vm.sessionsError.value)
            assertNull(vm.devicesError.value)
            failSessions = false
            vm.loadSessions("42", client)
            assertTrue(vm.sessionsLoaded.value)
            assertNull(vm.sessionsError.value)
            assertNull(vm.errorMessage.value)
        } finally { release.complete(Unit); client.close() }
    }
}
