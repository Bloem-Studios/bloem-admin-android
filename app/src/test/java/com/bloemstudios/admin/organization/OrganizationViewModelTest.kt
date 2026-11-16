package com.bloemstudios.admin.organization

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A canned answer for one route of the pass client. */
private class Route(val method: HttpMethod, val path: String, val status: HttpStatusCode, val body: String)

class OrganizationViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val routes = mutableListOf<Route>()
    private val sent = mutableListOf<HttpRequestData>()
    private var mintStatus = HttpStatusCode.OK
    private var mints = 0

    private fun on(method: HttpMethod, path: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        routes.removeAll { it.method == method && it.path == path }
        routes += Route(method, path, status, body)
    }

    private fun get(path: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) = on(HttpMethod.Get, "/api/bloem/v2/admin/organization$path", body, status)
    private fun patch(path: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) = on(HttpMethod.Patch, "/api/bloem/v2/admin/organization$path", body, status)

    private suspend fun opened(): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine {
            mints += 1
            if (mintStatus != HttpStatusCode.OK) respond(F.error("refused"), mintStatus, json)
            else respond(F.session(token = "pass-$mints", policyRevision = 7L + mints - 1), HttpStatusCode.OK, json)
        })
        val session = OrganizationSession(
            account, F.ORG_ID, clock = { Instant.parse("2026-11-13T12:00:00Z") },
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    sent += request
                    val route = routes.lastOrNull { it.method == request.method && it.path == request.url.encodedPath }
                    if (route == null) respond(F.error("not_found"), HttpStatusCode.NotFound, json)
                    else respond(route.body, route.status, json)
                }, recovery)
            },
        )
        session.open()
        return session
    }

    private fun resText(text: ErrorText?): Int? = (text as? ErrorText.Res)?.id

    // ---- Overview -------------------------------------------------------------------------------

    @Test
    fun `overview loads the summary`() = runTest {
        get("/overview", F.overview())
        val vm = OverviewViewModel()
        vm.load(opened())
        assertEquals("Acme Media", vm.state.value.summary?.name)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `overview names the owner from the people list, never by account number`() = runTest {
        get("/overview", F.overview())
        get("/people/4", F.personResponse(F.person(accountId = 4, email = "pat@acme.test", name = "Pat Owner")))
        val vm = OverviewViewModel()
        vm.load(opened())
        assertEquals("Pat Owner", vm.state.value.ownerName)
        assertTrue(vm.state.value.ownerResolved)
        assertEquals("the owner is read as a person of this organization", 1, sent.count { it.url.encodedPath.endsWith("/people/4") })
    }

    @Test
    fun `an owner without a display name is named by email`() = runTest {
        get("/overview", F.overview())
        get("/people/4", F.personResponse(F.person(accountId = 4, email = "pat@acme.test", name = "")))
        val vm = OverviewViewModel()
        vm.load(opened())
        assertEquals("pat@acme.test", vm.state.value.ownerName)
    }

    @Test
    fun `an owner who cannot be read leaves the overview and says nothing wrong`() = runTest {
        get("/overview", F.overview())
        get("/people/4", F.error("not_found"), HttpStatusCode.NotFound)
        val vm = OverviewViewModel()
        vm.load(opened())
        assertEquals("Acme Media", vm.state.value.summary?.name)
        assertNull(vm.state.value.ownerName)
        assertTrue(vm.state.value.ownerResolved)
        assertNull("the overview itself has no error", vm.state.value.error)
    }

    @Test
    fun `an organization without an owner reads no one`() = runTest {
        get("/overview", F.overview().replace(""""owner_account_id":4,""", ""))
        val vm = OverviewViewModel()
        vm.load(opened())
        assertNull(vm.state.value.ownerName)
        assertTrue("no request was made for an owner", sent.none { it.url.encodedPath.contains("/people/") })
    }

    @Test
    fun `a 403 says the account no longer administers the organization and marks the session denied`() = runTest {
        get("/overview", F.error("insufficient_organization_authority"), HttpStatusCode.Forbidden)
        val session = opened()
        val vm = OverviewViewModel()
        vm.load(session)
        assertEquals(R.string.organization_lost_authority, resText(vm.state.value.error))
        assertTrue(session.accessDenied.value)
        assertNull(vm.state.value.summary)
    }

    @Test
    fun `a failed reload keeps the last overview and shows the server's message`() = runTest {
        get("/overview", F.overview())
        val session = opened()
        val vm = OverviewViewModel()
        vm.load(session)
        get("/overview", F.error("tenant_unavailable", "Tenant administration is unavailable"), HttpStatusCode.ServiceUnavailable)
        vm.load(session)
        assertNotNull(vm.state.value.summary)
        assertEquals(ErrorText.Raw("Tenant administration is unavailable"), vm.state.value.error)
        assertFalse(session.accessDenied.value)
    }

    // ---- People ---------------------------------------------------------------------------------

    private fun peopleRoutes() {
        get("/groups", F.groups(F.group("3", "Family"), F.group("4", "Kids")))
        get("/people", F.peoplePage(F.person(5, name = "Sam"), F.person(6, "ana@example.test", "Ana", "suspended", 2), next = "n2", total = 3))
    }

    @Test
    fun `people load with the groups and report paging state`() = runTest {
        peopleRoutes()
        val vm = PeopleViewModel()
        vm.load(opened())
        val state = vm.state.value
        assertEquals(listOf("Sam", "Ana"), state.people.map { it.display_name })
        assertEquals(listOf("Family", "Kids"), state.groups.map { it.name })
        assertEquals("n2", state.nextCursor)
        assertEquals(3L, state.approximateTotal)
    }

    @Test
    fun `the filter is sent and load more appends without duplicates`() = runTest {
        peopleRoutes()
        val session = opened()
        val vm = PeopleViewModel()
        vm.load(session, PeopleFilter(query = "sa", statuses = setOf("active"), groupId = 3))
        val first = sent.last { it.url.encodedPath.endsWith("/people") }.url.parameters
        assertEquals("sa", first["query"])
        assertEquals("active", first["status"])
        assertEquals("3", first["group_id"])

        get("/people", F.peoplePage(F.person(6, "ana@example.test", "Ana", "suspended", 2), F.person(7, "kim@example.test", "Kim"), total = 3))
        vm.loadMore(session)
        assertEquals(listOf(5L, 6L, 7L), vm.state.value.people.map { it.account_id })
        assertEquals("n2", sent.last { it.url.encodedPath.endsWith("/people") }.url.parameters["cursor"])
        assertNull(vm.state.value.nextCursor)
    }

    @Test
    fun `people still load when the groups fail`() = runTest {
        peopleRoutes()
        get("/groups", F.error("tenant_unavailable"), HttpStatusCode.ServiceUnavailable)
        val vm = PeopleViewModel()
        vm.load(opened())
        assertEquals(2, vm.state.value.people.size)
        assertTrue(vm.state.value.groups.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test
    fun `suspending quotes the person's revision and replaces the row`() = runTest {
        peopleRoutes()
        val session = opened()
        val vm = PeopleViewModel()
        vm.load(session)
        patch("/people/5/memberships/current", F.personResponse(F.person(5, name = "Sam", status = "suspended", revision = 5)))

        vm.setSuspended(session, vm.state.value.people.first(), suspend = true)

        val request = sent.last { it.method == HttpMethod.Patch }
        val body = String(request.body.toByteArray())
        assertTrue(body, body.contains(""""status":"suspended"""") && body.contains(""""expected_revision":4"""))
        assertTrue(vm.state.value.people.first().isSuspended)
        assertEquals(R.string.organization_notice_suspended, resText(vm.state.value.notice))
        assertTrue(vm.state.value.pendingAccountIds.isEmpty())
    }

    @Test
    fun `a conflict reloads the list and does not retry the write`() = runTest {
        peopleRoutes()
        val session = opened()
        val vm = PeopleViewModel()
        vm.load(session)
        patch("/people/5/memberships/current", """{"error":"authorization_state_changed","message":"x","current_revision":9}""", HttpStatusCode.Conflict)

        vm.setSuspended(session, vm.state.value.people.first(), suspend = true)

        assertEquals("one attempt only", 1, sent.count { it.method == HttpMethod.Patch })
        assertEquals(R.string.organization_changed_conflict, resText(vm.state.value.error))
        assertEquals("the list was reloaded", 2, sent.count { it.url.encodedPath.endsWith("/people") })
        assertTrue(vm.state.value.pendingAccountIds.isEmpty())
    }

    @Test
    fun `suspending the owner explains why it is refused`() = runTest {
        peopleRoutes()
        val session = opened()
        val vm = PeopleViewModel()
        vm.load(session)
        patch("/people/5/memberships/current", """{"error":"validation_failed","message":"Validation failed"}""", HttpStatusCode.UnprocessableEntity)
        vm.setSuspended(session, vm.state.value.people.first(), suspend = true)
        assertEquals(R.string.organization_error_cannot_suspend, resText(vm.state.value.error))
    }

    @Test
    fun `an administrator cannot suspend their own membership from here`() = runTest {
        get("/groups", F.groups(F.group()))
        get("/people", F.peoplePage(F.person(5, name = "Me").replace("person-membership-5", F.MEMBERSHIP_ID)))
        val session = opened()
        val vm = PeopleViewModel()
        vm.load(session)
        vm.setSuspended(session, vm.state.value.people.single(), suspend = true)
        assertTrue(sent.none { it.method == HttpMethod.Patch })
    }

    @Test
    fun `moving a profile sends the group and the person's revision`() = runTest {
        peopleRoutes()
        val session = opened()
        val vm = PeopleViewModel()
        vm.load(session)
        patch("/people/5/profiles/p1", F.personResponse(F.person(5, name = "Sam", revision = 5, profiles = F.profile(groupId = 4, groupName = "Kids"))))

        val person = vm.state.value.people.first()
        vm.assignGroup(session, person, person.profiles.single(), vm.state.value.groups[1])

        val body = String(sent.last { it.method == HttpMethod.Patch }.body.toByteArray())
        assertTrue(body, body.contains(""""group_id":4""") && body.contains(""""expected_revision":4"""))
        assertEquals("Kids", vm.state.value.people.first().profiles.single().group_name)
        assertEquals(R.string.organization_notice_group_changed, resText(vm.state.value.notice))
    }

    @Test
    fun `the administrator is recognised as themselves by membership id`() {
        val self = PersonSummary(account_id = 1, membership_status = "active", security_revision = 1, membership_id = F.MEMBERSHIP_ID)
        val other = self.copy(membership_id = "someone-else")
        val context = AdminContext("organization:o", "Acme", AdminContextScope.Organization, "o", "active", AdminAuthority.OrganizationAdmin, membershipId = F.MEMBERSHIP_ID)
        assertTrue(self.isSelf(context))
        assertFalse(other.isSelf(context))
        assertFalse(self.isSelf(null))
    }

    // ---- Access groups, libraries ----------------------------------------------------------------

    @Test
    fun `access groups load read-only`() = runTest {
        get("/groups", F.groups(F.group(), F.group("4", "Kids", isDefault = true, members = 1)))
        val vm = AccessGroupsViewModel()
        vm.load(opened())
        assertEquals(listOf("Family", "Kids"), vm.state.value.groups.map { it.name })
        assertTrue(sent.all { it.method == HttpMethod.Get })
    }

    @Test
    fun `pausing a library quotes the entitlement revision and updates the card`() = runTest {
        get("/libraries", F.libraries(F.library(2, "Shared", "entitled", F.entitlement("active", 9))))
        val session = opened()
        val vm = LibrariesViewModel()
        vm.load(session)
        on(HttpMethod.Put, "/api/bloem/v2/admin/organization/entitlements/2", """{"entitlement":${F.entitlement("suspended", 10)}}""")

        vm.setStatus(session, vm.state.value.libraries.single(), "suspended")

        val body = String(sent.last { it.method == HttpMethod.Put }.body.toByteArray())
        assertTrue(body, body.contains(""""expected_revision":9"""))
        assertEquals("suspended", vm.state.value.libraries.single().entitlement?.status)
    }

    @Test
    fun `a library change refused as changed elsewhere reloads and says so`() = runTest {
        get("/libraries", F.libraries(F.library(2, "Shared", "entitled", F.entitlement("active", 9))))
        val session = opened()
        val vm = LibrariesViewModel()
        vm.load(session)
        on(HttpMethod.Put, "/api/bloem/v2/admin/organization/entitlements/2", """{"error":"authorization_state_changed","message":"x","current_revision":8}""", HttpStatusCode.Conflict)

        vm.setStatus(session, vm.state.value.libraries.single(), "suspended")

        assertEquals(R.string.organization_changed_conflict, resText(vm.state.value.error))
        assertEquals(2, sent.count { it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/libraries") })
    }

    // ---- Invitations ------------------------------------------------------------------------------

    private fun invitationRoutes() {
        get("/invitations", F.invitations(F.invitation()))
        get("/groups", F.groups(F.group()))
    }

    @Test
    fun `sending an invitation keeps the claim link in memory and refreshes the list`() = runTest {
        invitationRoutes()
        val session = opened()
        val vm = OrganizationInvitationsViewModel()
        vm.load(session)
        on(HttpMethod.Post, "/api/bloem/v2/admin/organization/invitations", F.handoff(F.invitation("i2", "kim@example.test"), "tok en/1"), HttpStatusCode.Created)

        val result = vm.send(session, " kim@example.test ", accessGroupId = 3, note = " hi ")

        val sentLink = (result as InvitationResult.Sent).link
        assertEquals("https://example.test/invite/tok%20en%2F1", sentLink.url)
        assertEquals(sentLink, vm.state.value.claimLink)
        val body = String(sent.last { it.method == HttpMethod.Post }.body.toByteArray())
        assertTrue(body, body.contains(""""email":"kim@example.test"""") && body.contains(""""access_group_id":3""") && body.contains(""""note":"hi"""") && body.contains(""""expected_revision":7"""))
        assertFalse(vm.state.value.toString().contains("tok en"))
        assertFalse(sentLink.toString().contains("tok"))

        vm.dismissClaimLink()
        assertNull(vm.state.value.claimLink)
    }

    @Test
    fun `an uncertain send is reported once, never retried, and the list is reloaded`() = runTest {
        invitationRoutes()
        val session = opened()
        val vm = OrganizationInvitationsViewModel()
        vm.load(session)
        on(HttpMethod.Post, "/api/bloem/v2/admin/organization/invitations", """{"error":"tenant_unavailable","message":"Tenant administration is unavailable"}""", HttpStatusCode.ServiceUnavailable)

        val result = vm.send(session, "kim@example.test", null, null)

        assertTrue(result is InvitationResult.Failed)
        assertEquals(1, sent.count { it.method == HttpMethod.Post })
        assertNull(vm.state.value.claimLink)
        assertEquals(2, sent.count { it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/invitations") })
    }

    @Test
    fun `an address that already has an account shows the server's reason`() = runTest {
        invitationRoutes()
        val session = opened()
        val vm = OrganizationInvitationsViewModel()
        vm.load(session)
        on(HttpMethod.Post, "/api/bloem/v2/admin/organization/invitations", F.error("email_taken", "An account with this email already exists"), HttpStatusCode.Conflict)
        val result = vm.send(session, "kim@example.test", null, null) as InvitationResult.Failed
        assertEquals(ErrorText.Raw("An account with this email already exists"), result.message)
    }

    @Test
    fun `a blank address is refused before anything is sent`() = runTest {
        invitationRoutes()
        val session = opened()
        val vm = OrganizationInvitationsViewModel()
        val result = vm.send(session, "  ", null, null)
        assertTrue(result is InvitationResult.Failed)
        assertTrue(sent.none { it.method == HttpMethod.Post })
    }

    @Test
    fun `resend replaces the link and revoke sends the revision`() = runTest {
        invitationRoutes()
        val session = opened()
        val vm = OrganizationInvitationsViewModel()
        vm.load(session)
        on(HttpMethod.Post, "/api/bloem/v2/admin/organization/invitations/i1/resend", F.handoff(token = "fresh"), HttpStatusCode.Created)
        val resent = vm.resend(session, vm.state.value.invitations.single()) as InvitationResult.Sent
        assertEquals("https://example.test/invite/fresh", resent.link.url)

        on(HttpMethod.Delete, "/api/bloem/v2/admin/organization/invitations/i1", "", HttpStatusCode.NoContent)
        assertTrue(vm.revoke(session, vm.state.value.invitations.single()))
        val body = String(sent.last { it.method == HttpMethod.Delete }.body.toByteArray())
        assertTrue(body, body.contains(""""expected_revision":7"""))
    }

    @Test
    fun `leaving the screen releases the claim link but rotation keeps it`() = runTest {
        invitationRoutes()
        val session = opened()
        val vm = OrganizationInvitationsViewModel()
        on(HttpMethod.Post, "/api/bloem/v2/admin/organization/invitations", F.handoff(), HttpStatusCode.Created)
        vm.send(session, "kim@example.test", null, null)
        vm.onSurfaceHidden(isChangingConfigurations = true)
        assertNotNull(vm.state.value.claimLink)
        vm.onSurfaceHidden(isChangingConfigurations = false)
        assertNull(vm.state.value.claimLink)
    }

    // ---- Activity, cohorts, decisions --------------------------------------------------------------

    @Test
    fun `activity pages older events by cursor`() = runTest {
        get("/activity", F.audit(F.auditEvent(3), F.auditEvent(2), next = "older"))
        val session = opened()
        val vm = OrganizationActivityViewModel()
        vm.load(session)
        assertEquals("older", vm.state.value.nextCursor)

        get("/activity", F.audit(F.auditEvent(2), F.auditEvent(1, outcome = "conflict")))
        vm.loadOlder(session)

        assertEquals(listOf(3L, 2L, 1L), vm.state.value.events.map { it.id })
        assertNull(vm.state.value.nextCursor)
        assertEquals("older", sent.last { it.url.encodedPath.endsWith("/activity") }.url.parameters["cursor"])
    }

    @Test
    fun `audit labels follow the web and unknown codes read as words`() {
        assertEquals(R.string.organization_action_membership_changed, auditActionLabelRes("people.membership_updated"))
        assertNull(auditActionLabelRes("something.new_thing"))
        assertEquals("Something new thing", auditActionFallback("something.new_thing"))
        assertEquals(R.string.organization_outcome_partial, auditOutcomeLabelRes("partial_failure"))
        assertNull(auditOutcomeLabelRes("success"))
        assertTrue(OrganizationAuditEvent(1, action = "a", outcome = "success").isSuccess)
        assertFalse(OrganizationAuditEvent(1, action = "a", outcome = "denied").isSuccess)
    }

    @Test
    fun `cohorts reload with archived ones on request`() = runTest {
        get("/entitlement-cohorts", F.cohorts(F.cohort()))
        val session = opened()
        val vm = CohortsViewModel()
        vm.load(session)
        assertEquals(1, vm.state.value.cohorts.size)
        get("/entitlement-cohorts", F.cohorts(F.cohort(), F.cohort("c2", archived = true)))
        vm.load(session, includeArchived = true)
        assertEquals(2, vm.state.value.cohorts.size)
        assertEquals("true", sent.last { it.url.encodedPath.endsWith("/entitlement-cohorts") }.url.parameters["include_archived"])
    }

    @Test
    fun `decisions page by cursor`() = runTest {
        get("/policy-decisions", F.decisions(F.decision(2), next = "c"))
        val session = opened()
        val vm = DecisionsViewModel()
        vm.load(session)
        get("/policy-decisions", F.decisions(F.decision(1, allowed = false)))
        vm.loadMore(session)
        assertEquals(listOf(true, false), vm.state.value.decisions.map { it.allowed })
        assertNull(vm.state.value.nextCursor)
    }
}
