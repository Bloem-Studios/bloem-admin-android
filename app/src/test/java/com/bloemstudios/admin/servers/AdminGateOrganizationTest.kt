package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.AdminAuthority
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who the gate admits: platform admin, organization-only admin, or neither. */
class AdminGateOrganizationTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requested = mutableListOf<String>()
    private val CAPABILITIES = """{"api":"bloem/v2","features":{"organization_memberships":true},"feature_tokens":[]}"""

    private fun server(
        status: HttpStatusCode,
        memberships: String = F.memberships(),
        organizations: HttpStatusCode = HttpStatusCode.OK,
    ) = ApiClient("https://example.test", "account-token", MockEngine { request ->
        requested += request.url.encodedPath
        when (request.url.encodedPath) {
            "/api/v2/admin/server/status" ->
                if (status == HttpStatusCode.OK) respond(V2Fixtures.SERVER_STATUS, status, json) else respond(F.error("forbidden"), status, json)
            "/api/bloem/v2/capabilities" -> respond(CAPABILITIES, HttpStatusCode.OK, json)
            "/api/bloem/v2/organizations" ->
                if (organizations == HttpStatusCode.OK) respond(memberships, organizations, json) else respond(F.error("refused"), organizations, json)
            else -> error("the gate must not call ${request.url.encodedPath}")
        }
    })

    @Test
    fun `a platform administrator lands in the platform context as before`() = runTest {
        val gate = AdminGateViewModel()
        gate.checkAccess(server(HttpStatusCode.OK, F.memberships(F.membership())))
        assertEquals(AdminGateState.Authorized, gate.state.value)
        assertFalse("no organization lookup is needed to admit a platform administrator", "/api/bloem/v2/organizations" in requested)
    }

    @Test
    fun `an organization-only administrator is admitted into its organization`() = runTest {
        val gate = AdminGateViewModel()
        gate.checkAccess(server(HttpStatusCode.Forbidden, F.memberships(F.membership(name = "Acme Media"), F.membership("o2", "Other", role = "user"))))
        val state = gate.state.value as AdminGateState.OrganizationOnly
        assertEquals(listOf("Acme Media"), state.contexts.map { it.name })
        assertEquals(AdminAuthority.OrganizationAdmin, state.contexts.single().authority)
        assertEquals(F.ORG_ID, state.contexts.single().organizationId)
    }

    @Test
    fun `on a plain Silo server a refused account is turned away after one capability check and no organization lookup`() = runTest {
        val plain = ApiClient("https://example.test", "account-token", MockEngine { request ->
            requested += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/v2/admin/server/status" -> respond(F.error("forbidden"), HttpStatusCode.Forbidden, json)
                "/api/bloem/v2/capabilities" -> respond("404 page not found", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/plain"))
                else -> error("a plain Silo server must not be sent ${request.url.encodedPath}")
            }
        })
        val gate = AdminGateViewModel()
        gate.checkAccess(plain)
        assertEquals(AdminGateState.Unauthorized, gate.state.value)
        assertEquals(listOf("/api/v2/admin/server/status", "/api/bloem/v2/capabilities"), requested)
    }

    @Test
    fun `an account that administers nothing is turned away`() = runTest {
        val gate = AdminGateViewModel()
        gate.checkAccess(server(HttpStatusCode.Forbidden, F.memberships(F.membership(role = "user"))))
        assertEquals(AdminGateState.Unauthorized, gate.state.value)

        gate.checkAccess(server(HttpStatusCode.Forbidden, F.memberships()))
        assertEquals(AdminGateState.Unauthorized, gate.state.value)

        gate.checkAccess(server(HttpStatusCode.Forbidden, organizations = HttpStatusCode.NotFound))
        assertEquals("a server without organizations", AdminGateState.Unauthorized, gate.state.value)
    }

    @Test
    fun `an expired sign-in still goes to re-login whichever probe noticed`() = runTest {
        val gate = AdminGateViewModel()
        gate.checkAccess(server(HttpStatusCode.Unauthorized))
        assertEquals(AdminGateState.NeedsLogin(), gate.state.value)

        gate.checkAccess(server(HttpStatusCode.Forbidden, organizations = HttpStatusCode.Unauthorized))
        assertEquals(AdminGateState.NeedsLogin(), gate.state.value)
    }

    @Test
    fun `an unreachable organization lookup is a failure to show, not a refusal`() = runTest {
        val gate = AdminGateViewModel()
        gate.checkAccess(server(HttpStatusCode.Forbidden, organizations = HttpStatusCode.ServiceUnavailable))
        assertTrue(gate.state.value is AdminGateState.Failed)
    }
}
