package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.auth.login
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class AdminGateViewModelTest {
    @Test
    fun `single server feature adapter maps the exact destination order and rejects unknown ids`() {
        assertEquals(
            listOf(
                "overview.dashboard",
                "live.streams",
                "live.activity",
                "work.jobs",
                "playback.nodes",
                "system.backups",
                "system.plugins",
                "content.libraries",
                "work.requests",
                "people.users",
                "people.tenants",
                "system.server",
                "system.audit",
                "system.logs",
                "platform.support-access",
                "system.api-keys",
                "platform.organizations",
            ),
            SingleServerFeature.entries.map { it.destinationId },
        )
        SingleServerFeature.entries.forEach { feature ->
            assertSame(feature, singleServerFeatureFor(feature.destinationId))
        }

        val failure = assertThrows(IllegalStateException::class.java) {
            singleServerFeatureFor("people.invitations")
        }
        assertEquals("Unsupported single-server destination: people.invitations", failure.message)
    }

    @Test
    fun `tenants destination is hidden until the platform pass exists`() {
        assertFalse(TENANTS_DESTINATION_VISIBLE)
        assertFalse(
            singleServerDestinations(TENANTS_DESTINATION_VISIBLE).any { it.id == "people.tenants" },
        )
    }

    @Test
    fun `single server selection normalization stays supported and preserves removed group`() {
        val withoutTenants = singleServerDestinations(tenantsVisible = false)

        assertEquals(
            "live.activity",
            normalizeSingleServerDestinationId("live.activity", withoutTenants),
        )
        assertEquals(
            // Organizations sat alone in the Platform group: with it gone, the app opens on Now playing.
            "live.streams",
            normalizeSingleServerDestinationId("people.tenants", withoutTenants),
        )
        assertEquals(
            "live.streams",
            normalizeSingleServerDestinationId("stale.destination", withoutTenants),
        )
    }

    @Test
    fun `first visit without credentials goes directly to login without probing`() = runTest {
        var requests = 0
        val client = ApiClient("https://example.test", null, MockEngine {
            requests++
            respond("denied", HttpStatusCode.Unauthorized)
        })
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(client)

        assertEquals(AdminGateState.NeedsLogin(), viewModel.state.value)
        assertEquals(0, requests)
    }

    @Test
    fun gateAuthorizesAfterTransparentColdStartRefresh() = runTest {
        val client = recoveringClient(firstStatus = 401, retryStatus = 200)
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(client)

        assertEquals(AdminGateState.Authorized, viewModel.state.value)
    }

    @Test
    fun `admin gate probe hits the v2 admin server status path`() = runTest {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(V2Fixtures.SERVER_STATUS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(ApiClient("https://example.test", "token", engine))

        assertEquals("/api/v2/admin/server/status", path)
        assertEquals(AdminGateState.Authorized, viewModel.state.value)
    }

    @Test
    fun `checkAccess sets Authorized on 200`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.SERVER_STATUS,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", "token", engine)
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(client)

        assertTrue(viewModel.state.value is AdminGateState.Authorized)
    }

    @Test
    fun `checkAccess sets Unauthorized on 403`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"forbidden","message":"Admin access required"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", "token", engine)
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(client)

        assertTrue(viewModel.state.value is AdminGateState.Unauthorized)
    }

    @Test
    fun `checkAccess sets NeedsLogin on 401`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"Token expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", "expired-token", engine)
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(client)

        assertTrue(viewModel.state.value is AdminGateState.NeedsLogin)
    }

    @Test
    fun `successful re-login rewrites the client token and re-running checkAccess succeeds`() = runTest {
        // Simulates the gate's re-login flow: an expired-token probe returns
        // NeedsLogin, the shared login() function is used to obtain a fresh
        // token (mirroring what ReLoginForm does), the SAME client instance
        // has its token rewritten via ApiClient.replaceAuthenticatedPrincipal (mirroring
        // what ServerStore.updateAccessToken does to a cached client), and
        // checkAccess is re-run -- it must now send the new token and land
        // on Authorized.
        var capturedFirstAuthHeader: String? = null
        var capturedSecondAuthHeader: String? = null
        var probeCallCount = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v2/admin/server/status" -> {
                    probeCallCount++
                    if (probeCallCount == 1) {
                        capturedFirstAuthHeader = request.headers[HttpHeaders.Authorization]
                        respond(
                            content = """{"error":"unauthorized","message":"Token expired"}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        capturedSecondAuthHeader = request.headers[HttpHeaders.Authorization]
                        respond(
                            content = V2Fixtures.SERVER_STATUS,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
                "/api/v2/auth/login" -> {
                    respond(
                        content = V2Fixtures.TOKEN_PAIR.replace("\"a\"", "\"fresh-token\""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = ApiClient("https://example.test", "expired-token", engine)
        val viewModel = AdminGateViewModel()

        viewModel.checkAccess(client)
        assertTrue(viewModel.state.value is AdminGateState.NeedsLogin)
        assertEquals("Bearer expired-token", capturedFirstAuthHeader)

        val response = login(client, "alex", "correct-password")
        client.replaceAuthenticatedPrincipal(response.access_token)
        viewModel.checkAccess(client)

        assertTrue(viewModel.state.value is AdminGateState.Authorized)
        assertEquals("Bearer fresh-token", capturedSecondAuthHeader)
    }

    private fun recoveringClient(firstStatus: Int, retryStatus: Int): ApiClient {
        var calls = 0
        val engine = MockEngine {
            calls++
            val status = HttpStatusCode.fromValue(if (calls == 1) firstStatus else retryStatus)
            if (status.isSuccess()) {
                respond(
                    content = V2Fixtures.SERVER_STATUS,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = """{"error":"unauthorized","message":"Token expired"}""",
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        return ApiClient(
            origin = ServerOrigin.parse("https://example.test"),
            initialAccessToken = AccessTokenSnapshot("old", 0L),
            engine = engine,
            unauthorizedRecovery = UnauthorizedRecovery {
                RecoveryResult.Retry(AccessTokenSnapshot("new", 1L))
            }
        )
    }

}
