package com.bloemstudios.admin.organization

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.createOrganizationInvitation
import com.bloemstudios.admin.network.api.getOrganizationOverview
import com.bloemstudios.admin.network.api.listOrganizationActivity
import com.bloemstudios.admin.network.api.listOrganizationCohorts
import com.bloemstudios.admin.network.api.listOrganizationGroups
import com.bloemstudios.admin.network.api.listOrganizationInvitations
import com.bloemstudios.admin.network.api.listOrganizationLibraries
import com.bloemstudios.admin.network.api.listOrganizationMemberships
import com.bloemstudios.admin.network.api.listOrganizationPeople
import com.bloemstudios.admin.network.api.listOrganizationPolicyDecisions
import com.bloemstudios.admin.network.api.mintAdminSession
import com.bloemstudios.admin.network.api.resendOrganizationInvitation
import com.bloemstudios.admin.network.api.revokeOrganizationInvitation
import com.bloemstudios.admin.network.api.setOrganizationEntitlementStatus
import com.bloemstudios.admin.network.api.setOrganizationMembershipStatus
import com.bloemstudios.admin.network.api.setOrganizationProfileGroup
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrganizationApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String): ApiClient =
        ApiClient("https://example.test", F.PASS, MockEngine { request ->
            seen += request
            respond(body, status, json)
        })

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject

    @Test
    fun `memberships decode the server list`() = runTest {
        val rows = client(body = F.memberships(F.membership(), F.membership(id = "o2", name = "Beta", role = "user")))
            .listOrganizationMemberships()
        assertEquals(listOf("Acme Media", "Beta"), rows.map { it.name })
        assertEquals("admin", rows[0].membership_role)
        assertEquals(7L, rows[0].policy_revision)
        assertEquals("/api/bloem/v2/organizations", seen.single().url.encodedPath)
    }

    @Test
    fun `minting an organization pass sends scope and organization id only`() = runTest {
        val response = client(body = F.session()).mintAdminSession(AdminSessionRequest("organization", F.ORG_ID))
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/bloem/v2/admin/session", request.url.encodedPath)
        assertEquals(setOf("scope", "organization_id"), bodyOf(request).keys)
        assertEquals("Acme Media", response.context.name)
        assertEquals(7L, response.context.policy_revision)
        assertFalse("pass must not print", response.toString().contains(F.PASS))
    }

    @Test
    fun `platform scope never carries an organization id`() = runTest {
        client(body = F.session()).mintAdminSession(AdminSessionRequest("platform"))
        assertEquals(setOf("scope"), bodyOf(seen.single()).keys)
    }

    @Test
    fun `overview decodes counts and revision`() = runTest {
        val overview = client(body = F.overview(policyRevision = 9)).getOrganizationOverview()
        assertEquals("Acme Media", overview.name)
        assertEquals(9L, overview.policy_revision)
        assertEquals(10L, overview.active_membership_count)
        assertEquals(4L, overview.owner_account_id)
        assertEquals("/api/bloem/v2/admin/organization/overview", seen.single().url.encodedPath)
    }

    @Test
    fun `people send only the filters that are set`() = runTest {
        val page = client(body = F.peoplePage(F.person(), F.person(accountId = 6, status = "suspended"), next = "n2", total = 40))
            .listOrganizationPeople(PeopleFilter(query = " sam ", statuses = setOf("suspended", "active"), groupId = 3), cursor = "n1")
        val params = seen.single().url.parameters
        assertEquals("sam", params["query"])
        assertEquals("active,suspended", params["status"])
        assertEquals("3", params["group_id"])
        assertEquals("n1", params["cursor"])
        assertEquals("50", params["limit"])
        assertNull(params["sort"])
        assertEquals("n2", page.next_cursor)
        assertEquals(40L, page.approximate_total)
        assertEquals(listOf("Sam", "Sam"), page.items.map { it.display_name })
        assertTrue(page.items[1].isSuspended)
        assertEquals("Family", page.items[0].profiles.single().group_name)
    }

    @Test
    fun `an empty filter sends no query parameters except the page size`() = runTest {
        client(body = F.peoplePage()).listOrganizationPeople(PeopleFilter(), cursor = null)
        assertEquals(setOf("limit"), seen.single().url.parameters.names())
    }

    @Test
    fun `membership status change is a PATCH with the person's revision and no replay body surprises`() = runTest {
        val person = client(body = F.personResponse(F.person(status = "suspended", revision = 5)))
            .setOrganizationMembershipStatus(accountId = 5, status = "suspended", revision = 4)
        val request = seen.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/api/bloem/v2/admin/organization/people/5/memberships/current", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals("suspended", body.getValue("status").jsonPrimitive.content)
        assertEquals("4", body.getValue("expected_revision").jsonPrimitive.content)
        assertEquals(5L, person.security_revision)
    }

    @Test
    fun `profile group change names the profile in the path and the group in the body`() = runTest {
        client(body = F.personResponse()).setOrganizationProfileGroup(accountId = 5, profileId = "p 1", groupId = 8, revision = 4)
        val request = seen.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/api/bloem/v2/admin/organization/people/5/profiles/p%201", request.url.encodedPath)
        assertEquals("8", bodyOf(request).getValue("group_id").jsonPrimitive.content)
    }

    @Test
    fun `groups decode string ids and null sets`() = runTest {
        val groups = client(body = F.groups(F.group(), F.group(id = "4", name = "Kids", isDefault = true))).listOrganizationGroups()
        assertEquals(listOf("Family", "Kids"), groups.map { it.name })
        assertEquals(listOf("1", "2"), groups[0].library_ids)
        assertNull(groups[0].allowed_permissions)
        assertTrue(groups[1].is_default)
        assertEquals(6L, groups[0].member_count)
    }

    @Test
    fun `libraries decode entitlement and its revision`() = runTest {
        val libraries = client(body = F.libraries(F.library(), F.library(2, "Shared", "entitled", F.entitlement("suspended", 12))))
            .listOrganizationLibraries()
        assertTrue(libraries[0].isOwned)
        assertNull(libraries[0].entitlement)
        assertEquals("suspended", libraries[1].entitlement?.status)
        assertEquals(12L, libraries[1].entitlement?.security_revision)
    }

    @Test
    fun `entitlement pause is a PUT with the entitlement revision`() = runTest {
        val updated = client(body = """{"entitlement":${F.entitlement("suspended", 10)}}""")
            .setOrganizationEntitlementStatus(folderId = 2, status = "suspended", revision = 9)
        val request = seen.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/bloem/v2/admin/organization/entitlements/2", request.url.encodedPath)
        assertEquals("9", bodyOf(request).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("suspended", updated.status)
    }

    @Test
    fun `invitation create omits what is unset and returns the claim token`() = runTest {
        val handoff = client(HttpStatusCode.Created, F.handoff())
            .createOrganizationInvitation(CreateOrganizationInvitation("new@example.test", expected_revision = 7))
        val body = bodyOf(seen.single())
        assertEquals(setOf("email", "expected_revision"), body.keys)
        assertEquals("claim-token-value", handoff.claim_token)
        assertFalse(handoff.toString().contains("claim-token-value"))
        assertEquals("3", handoff.invitation.access_group_id)
    }

    @Test
    fun `resend posts the revision body and revoke deletes with one`() = runTest {
        client(HttpStatusCode.Created, F.handoff()).resendOrganizationInvitation("i1", revision = 7)
        val resend = seen.single()
        assertEquals("/api/bloem/v2/admin/organization/invitations/i1/resend", resend.url.encodedPath)
        assertEquals("7", bodyOf(resend).getValue("expected_revision").jsonPrimitive.content)

        seen.clear()
        client(HttpStatusCode.NoContent, "").revokeOrganizationInvitation("i1", revision = 7)
        val revoke = seen.single()
        assertEquals(HttpMethod.Delete, revoke.method)
        assertEquals("7", bodyOf(revoke).getValue("expected_revision").jsonPrimitive.content)
    }

    @Test
    fun `invitation list decodes optional fields`() = runTest {
        val rows = client(body = F.invitations(F.invitation(), F.invitation("i2", status = "accepted", groupId = null)))
            .listOrganizationInvitations()
        assertTrue(rows[0].isPending)
        assertNull(rows[1].access_group_id)
    }

    @Test
    fun `activity decodes events and cursor`() = runTest {
        val page = client(body = F.audit(F.auditEvent(1), F.auditEvent(2, outcome = "conflict", actor = "null"), next = "older"))
            .listOrganizationActivity(cursor = null)
        assertEquals(2, page.events.size)
        assertNull(page.events[1].actor_account_id)
        assertEquals("older", page.next_cursor)
        assertTrue(seen.single().url.parameters.names().isEmpty())
    }

    @Test
    fun `cohorts and decisions are read-only lists`() = runTest {
        val cohorts = client(body = F.cohorts(F.cohort(), F.cohort("c2", archived = true))).listOrganizationCohorts(includeArchived = true)
        assertEquals("true", seen.single().url.parameters["include_archived"])
        assertTrue(cohorts[1].archived)
        assertNull(cohorts[0].policy.library_ids)

        seen.clear()
        val page = client(body = F.decisions(F.decision(), F.decision(2, allowed = false), next = "c")).listOrganizationPolicyDecisions(cursor = "b")
        assertEquals("b", seen.single().url.parameters["cursor"])
        assertEquals(listOf(true, false), page.decisions.map { it.allowed })
        assertEquals(1, page.decisions[0].resource.size)
        assertEquals("c", page.next_cursor)
    }

    @Test
    fun `a refused organization route surfaces the server's status and code`() = runTest {
        val failure = try {
            client(HttpStatusCode.Forbidden, F.error("insufficient_organization_authority")).getOrganizationOverview()
            fail("expected a failure")
            return@runTest
        } catch (error: ApiError) {
            error
        }
        assertEquals(403, failure.statusCode)
        assertEquals("insufficient_organization_authority", failure.code)
    }
}
