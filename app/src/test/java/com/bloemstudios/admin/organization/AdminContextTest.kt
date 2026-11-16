package com.bloemstudios.admin.organization

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdminContextTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val statusOk = V2Fixtures.SERVER_STATUS

    private val admin = OrganizationMembership("o1", "a", "Acme", false, "m1", "admin", 7, 11)
    private val member = OrganizationMembership("o2", "b", "Beta", false, "m2", "user", 3, 5)

    /** Answers the capability check, the platform probe and the membership list the way a Bloem server does. */
    private fun server(platform: HttpStatusCode, memberships: String, organizations: HttpStatusCode = HttpStatusCode.OK) =
        ApiClient("https://example.test", "account-token", MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/bloem/v2/capabilities" -> respond("""{"api":"bloem/v2","features":{"organization_memberships":true},"feature_tokens":[]}""", HttpStatusCode.OK, json)
                "/api/v2/admin/server/status" -> respond(if (platform == HttpStatusCode.OK) statusOk else F.error("forbidden"), platform, json)
                "/api/bloem/v2/organizations" -> respond(if (organizations == HttpStatusCode.OK) memberships else F.error("not_found"), organizations, json)
                else -> error("unexpected ${request.url}")
            }
        })

    @Test
    fun `an organization administrator is offered only the organizations they administer`() {
        val contexts = adminContextsFor(listOf(admin, member), platformAdmin = false)
        assertEquals(listOf("organization:o1"), contexts.map { it.key })
        val context = contexts.single()
        assertEquals(AdminContextScope.Organization, context.scope)
        assertEquals("o1", context.organizationId)
        assertEquals("Acme", context.name)
        assertEquals("active", context.status)
        assertEquals(AdminAuthority.OrganizationAdmin, context.authority)
        assertEquals(7L, context.policyRevision)
    }

    @Test
    fun `a platform administrator gets Platform first and every membership`() {
        val contexts = adminContextsFor(listOf(admin, member), platformAdmin = true)
        assertEquals(listOf("platform", "organization:o1", "organization:o2"), contexts.map { it.key })
        assertEquals(AdminContextScope.Platform, contexts[0].scope)
        assertEquals(null, contexts[0].organizationId)
        assertTrue(contexts.all { it.authority == AdminAuthority.PlatformAdmin })
    }

    @Test
    fun `neither platform nor organization administrator gets nothing`() {
        assertTrue(adminContextsFor(listOf(member), platformAdmin = false).isEmpty())
        assertTrue(adminContextsFor(emptyList(), platformAdmin = false).isEmpty())
    }

    @Test
    fun `discovery finds Platform and organizations for a platform administrator`() = runTest {
        val contexts = server(HttpStatusCode.OK, F.memberships(F.membership())).discoverAdminContexts()
        assertEquals(listOf("platform", "organization:${F.ORG_ID}"), contexts.map { it.key })
    }

    @Test
    fun `discovery admits an organization-only administrator after the platform probe is refused`() = runTest {
        val contexts = server(HttpStatusCode.Forbidden, F.memberships(F.membership(), F.membership("o2", role = "user"))).discoverAdminContexts()
        assertEquals(listOf("organization:${F.ORG_ID}"), contexts.map { it.key })
        assertEquals(AdminAuthority.OrganizationAdmin, contexts.single().authority)
    }

    @Test
    fun `discovery finds nothing for an ordinary member`() = runTest {
        val contexts = server(HttpStatusCode.Forbidden, F.memberships(F.membership(role = "user"))).discoverAdminContexts()
        assertTrue(contexts.isEmpty())
    }

    @Test
    fun `a server without organizations leaves a platform administrator with Platform alone`() = runTest {
        val contexts = server(HttpStatusCode.OK, "", HttpStatusCode.NotFound).discoverAdminContexts()
        assertEquals(listOf("platform"), contexts.map { it.key })
    }

    @Test
    fun `an unauthorized probe is not turned into no access`() = runTest {
        try {
            server(HttpStatusCode.Unauthorized, F.memberships()).discoverAdminContexts()
            fail("a 401 must reach the caller so the re-login flow runs")
        } catch (error: ApiError) {
            assertEquals(401, error.statusCode)
        }
    }
}
