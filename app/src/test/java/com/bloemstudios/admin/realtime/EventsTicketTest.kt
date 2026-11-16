package com.bloemstudios.admin.realtime

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EventsTicketTest {
    @Test fun `ticket mint uses existing unauthorized recovery without changing principal epoch`() = runTest {
        var requests = 0
        val api = ApiClient("https://fixture.test", "old-fixture-access", MockEngine { request ->
            requests++
            assertEquals("/api/v2/events/ws-ticket", request.url.encodedPath)
            assertTrue(request.headers[HttpHeaders.Authorization] == if (requests == 1) "Bearer old-fixture-access" else "Bearer rotated-fixture-access")
            if (requests == 1) respond("{}", HttpStatusCode.Unauthorized, jsonHeaders)
            else respond(ticket(), headers = jsonHeaders)
        }, unauthorizedRecovery = UnauthorizedRecovery { RecoveryResult.Retry(AccessTokenSnapshot("rotated-fixture-access", 1)) })
        try {
            val epoch = api.currentPrincipalEpoch
            api.mintEventsSocketTicket(epoch)
            assertEquals(2, requests)
            assertEquals(epoch, api.currentPrincipalEpoch)
            assertEquals(epoch, api.principalEpoch.value)
        } finally { api.close() }
    }

    @Test fun `replacement during recovery cannot install old principal refreshed credentials`() = runTest {
        lateinit var api: ApiClient
        api = ApiClient("https://fixture.test", "old-fixture-access", MockEngine {
            respond("{}", HttpStatusCode.Unauthorized, jsonHeaders)
        }, unauthorizedRecovery = UnauthorizedRecovery {
            api.replaceAuthenticatedPrincipal("new-principal-access")
            RecoveryResult.Retry(AccessTokenSnapshot("old-principal-rotated-access", 100))
        })
        try {
            val epoch = api.currentPrincipalEpoch
            try { api.mintEventsSocketTicket(epoch); fail("Expected terminal authority failure") }
            catch (failure: SessionTransportException) { assertEquals(SessionFailure.SignInRequired, failure.failure) }
            assertTrue(api.accessTokenSnapshotForTest().token == "new-principal-access")
            assertEquals(epoch + 1, api.principalEpoch.value)
        } finally { api.close() }
    }

    @Test fun `bound refresh refuses newer credentials belonging to a replaced principal`() = runTest {
        val api = ApiClient("https://fixture.test", "initial", MockEngine { respond(ticket(), headers = jsonHeaders) })
        try {
            val captured = api.currentPrincipalEpoch
            api.replaceAuthenticatedPrincipal("replacement")
            try {
                api.refreshAccessToken(AccessTokenSnapshot("stale-authority", 100), requiredPrincipalEpoch = captured)
                fail("Expected replaced principal rejection")
            } catch (_: IllegalStateException) { }
            assertTrue(api.accessTokenSnapshotForTest().token == "replacement")
        } finally { api.close() }
    }

    @Test fun `both replacement paths publish epoch before returning and token refresh does not`() = runTest {
        val api = ApiClient("https://fixture.test", "old-fixture-access", MockEngine { respond(ticket(), headers = jsonHeaders) })
        try {
            api.replaceAuthenticatedPrincipal("replacement")
            assertEquals(1L, api.principalEpoch.value)
            api.replaceAuthenticatedPrincipal(AccessTokenSnapshot("another-replacement", 5))
            assertEquals(2L, api.principalEpoch.value)
            api.refreshAccessToken(AccessTokenSnapshot("rotation", 6))
            assertEquals(2L, api.principalEpoch.value)
        } finally { api.close() }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun ticket() = """{"ticket":"${"x".repeat(43)}","protocol":"silo.events.v2","expires_in":30,"max_connection_seconds":300}"""
}
