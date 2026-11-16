package com.bloemstudios.admin.platform

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.organization.OrganizationSummary
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformOrganizationViewModelTest {
    private val id = PlatformFixtures.ORG_ID
    private val pat = PlatformFixtures.membership(id = "m4", account = 4, username = "pat", email = "pat@acme.test", role = "admin")
    private val sam = PlatformFixtures.membership(id = "m5", account = 5, username = "sam", email = "sam@acme.test", role = "user", status = "suspended")

    /** A server that knows one organization, with [members] and the status changes the test allows. */
    private fun server(
        organization: String = PlatformFixtures.organizationJson(),
        members: (HttpRequestData) -> String = { PlatformFixtures.membershipsPage(pat, sam) },
        write: (HttpRequestData) -> Pair<HttpStatusCode, String>? = { null },
    ): PlatformHarness = PlatformHarness(route = { request ->
        val path = request.url.encodedPath
        write(request) ?: when {
            request.method == HttpMethod.Get && path.endsWith("/memberships") -> HttpStatusCode.OK to members(request)
            request.method == HttpMethod.Get && path.endsWith(id) -> HttpStatusCode.OK to PlatformFixtures.organization(organization)
            else -> HttpStatusCode.NotFound to PlatformFixtures.error("not_found")
        }
    })

    private suspend fun PlatformOrganizationViewModel.open(harness: PlatformHarness, support: Boolean = false) =
        load(harness.session, harness.account, id, supportAvailable = support)

    @Test
    fun `the organization loads with its counts, owner by name and members with role and status`() = runTest {
        val harness = server()
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertEquals("Acme Media", state.organization?.name)
        assertEquals(12L, state.organization?.membership_count)
        assertEquals("pat", state.ownerName)
        assertTrue(state.ownerResolved)
        assertEquals(listOf("pat", "sam"), state.members.map { it.label })
        assertEquals(listOf("admin", "user"), state.members.map { it.legacy_role })
        assertEquals(listOf("active", "suspended"), state.members.map { it.status })
    }

    @Test
    fun `the owner is looked for on the next pages until found, and no further`() = runTest {
        val owner = PlatformFixtures.membership(id = "m4", account = 4, username = "pat")
        val harness = server(members = { request ->
            when (request.url.parameters["cursor"]) {
                null -> PlatformFixtures.membershipsPage(sam, next = "p2")
                "p2" -> PlatformFixtures.membershipsPage(PlatformFixtures.membership(id = "m6", account = 6, username = "ana"), next = "p3")
                "p3" -> PlatformFixtures.membershipsPage(owner, next = "p4")
                else -> PlatformFixtures.membershipsPage(next = null)
            }
        })
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertEquals("pat", viewModel.state.value.ownerName)
        assertEquals("the list on screen is still its first page", listOf("sam"), viewModel.state.value.members.map { it.label })
        assertEquals("p2", viewModel.state.value.membersNext)
        assertEquals(listOf(null, "p2", "p3"), harness.passCalls("/memberships").map { it.url.parameters["cursor"] })
    }

    @Test
    fun `an owner who is not among the members is never shown as an account number`() = runTest {
        val harness = server(members = { PlatformFixtures.membershipsPage(sam) })
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertNull(viewModel.state.value.ownerName)
        assertTrue(viewModel.state.value.ownerResolved)
        assertEquals(4L, viewModel.state.value.organization?.owner_account_id)
    }

    @Test
    fun `a member without a username is named by email`() = runTest {
        val harness = server(members = { PlatformFixtures.membershipsPage(PlatformFixtures.membership(account = 4, username = "", email = "pat@acme.test")) })
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertEquals("pat@acme.test", viewModel.state.value.ownerName)
    }

    @Test
    fun `show more members appends the next page`() = runTest {
        val harness = server(members = { request ->
            if (request.url.parameters["cursor"] == "p2") PlatformFixtures.membershipsPage(sam)
            else PlatformFixtures.membershipsPage(pat, next = "p2")
        })
        val viewModel = PlatformOrganizationViewModel()
        viewModel.open(harness)

        viewModel.loadMoreMembers(harness.session)

        assertEquals(listOf("pat", "sam"), viewModel.state.value.members.map { it.label })
        assertNull(viewModel.state.value.membersNext)
    }

    @Test
    fun `members that cannot be read leave the organization on screen`() = runTest {
        val harness = server(write = { request ->
            if (request.url.encodedPath.endsWith("/memberships")) HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("tenant_unavailable", "tenant_unavailable") else null
        })
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertEquals("Acme Media", viewModel.state.value.organization?.name)
        assertTrue(viewModel.state.value.membersError)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `an organization the server does not have is not found`() = runTest {
        val harness = PlatformHarness(route = { HttpStatusCode.NotFound to PlatformFixtures.error("not_found", "Administrative resource not found") })
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertTrue(viewModel.state.value.notFound)
        assertNull(viewModel.state.value.organization)
    }

    @Test
    fun `a failed read of the organization names the page and keeps nothing`() = runTest {
        val harness = PlatformHarness(route = { HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("tenant_unavailable", "tenant_unavailable") })
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertEquals(ErrorText.Http(R.string.platform_org_load_error, 503), viewModel.state.value.error)
        assertFalse(viewModel.state.value.notFound)
    }

    // ---- Open as administrator ---------------------------------------------------------------------------

    private fun summary(status: String = "active") =
        OrganizationSummary(id = id, name = "Acme Media", status = status, policy_revision = 7)

    @Test
    fun `an active organization the account belongs to opens with its membership`() {
        assertEquals(OpenAccess.Membership, openAccessFor(summary(), setOf(id), supportAvailable = false))
        assertEquals("a member opens as a member even when support access exists", OpenAccess.Membership, openAccessFor(summary(), setOf(id), supportAvailable = true))
    }

    @Test
    fun `an active organization the account is not in opens only through support access`() {
        assertEquals(OpenAccess.Support, openAccessFor(summary(), emptySet(), supportAvailable = true))
        assertEquals(OpenAccess.None, openAccessFor(summary(), emptySet(), supportAvailable = false))
    }

    @Test
    fun `a suspended or setting up organization is never opened`() {
        for (status in listOf("suspended", "initializing")) {
            assertEquals(OpenAccess.None, openAccessFor(summary(status), setOf(id), supportAvailable = true))
            assertEquals(OpenAccess.None, openAccessFor(summary(status), emptySet(), supportAvailable = true))
        }
    }

    @Test
    fun `the load decides how it opens from the account's own organizations`() = runTest {
        val member = server().also { it.accountMemberOf(id) }
        val viewModel = PlatformOrganizationViewModel()
        viewModel.open(member)
        assertEquals(OpenAccess.Membership, viewModel.state.value.openAccess)

        val owner = server()
        val supported = PlatformOrganizationViewModel()
        supported.open(owner, support = true)
        assertEquals(OpenAccess.Support, supported.state.value.openAccess)

        val none = PlatformOrganizationViewModel()
        none.open(server())
        assertEquals(OpenAccess.None, none.state.value.openAccess)
    }

    @Test
    fun `if the account's own organizations cannot be read nothing extra is offered`() = runTest {
        val harness = server()
        harness.accountOrganizations = "not json"
        val viewModel = PlatformOrganizationViewModel()

        viewModel.open(harness)

        assertEquals(OpenAccess.None, viewModel.state.value.openAccess)
        assertEquals("Acme Media", viewModel.state.value.organization?.name)
    }

    // ---- Suspend and reactivate --------------------------------------------------------------------------

    @Test
    fun `suspend quotes the revision it showed, then shows the new status with the counts it had`() = runTest {
        var body = ""
        val harness = server(write = { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/suspend")) {
                body = request.bodyText()
                HttpStatusCode.OK to PlatformFixtures.lifecycleAnswer("suspended", revision = 8)
            } else null
        })
        harness.accountMemberOf(id)
        val viewModel = PlatformOrganizationViewModel()
        viewModel.open(harness)

        viewModel.setSuspended(harness.session, suspended = true)

        assertEquals("""{"expected_revision":7}""", body)
        val state = viewModel.state.value
        assertEquals("suspended", state.organization?.status)
        assertEquals(8L, state.organization?.policy_revision)
        assertEquals(12L, state.organization?.membership_count)
        assertEquals(PlatformNotice.Suspended("Acme Media"), state.notice)
        assertEquals("a suspended organization cannot be opened", OpenAccess.None, state.openAccess)
        assertFalse(state.busy)
    }

    @Test
    fun `reactivate sends to the reactivate route and the organization can be opened again`() = runTest {
        val harness = server(
            organization = PlatformFixtures.organizationJson(status = "suspended"),
            write = { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/reactivate")) HttpStatusCode.OK to PlatformFixtures.lifecycleAnswer("active", revision = 9)
                else null
            },
        )
        harness.accountMemberOf(id)
        val viewModel = PlatformOrganizationViewModel()
        viewModel.open(harness)
        assertEquals(OpenAccess.None, viewModel.state.value.openAccess)

        viewModel.setSuspended(harness.session, suspended = false)

        assertEquals(1, harness.passCalls("/reactivate").size)
        assertEquals("active", viewModel.state.value.organization?.status)
        assertEquals(PlatformNotice.Reactivated("Acme Media"), viewModel.state.value.notice)
        assertEquals(OpenAccess.Membership, viewModel.state.value.openAccess)
    }

    @Test
    fun `a revision conflict is not applied, reloads the organization and says it changed`() = runTest {
        var reads = 0
        val harness = PlatformHarness(route = { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path.endsWith("/suspend") ->
                    HttpStatusCode.Conflict to """{"error":"authorization_state_changed","message":"Authorization state changed; reload and retry","current_revision":9}"""
                path.endsWith("/memberships") -> HttpStatusCode.OK to PlatformFixtures.membershipsPage(pat)
                path.endsWith(id) -> {
                    reads++
                    HttpStatusCode.OK to PlatformFixtures.organization(PlatformFixtures.organizationJson(revision = if (reads == 1) 7 else 9))
                }
                else -> HttpStatusCode.NotFound to PlatformFixtures.error("not_found")
            }
        })
        val viewModel = PlatformOrganizationViewModel()
        viewModel.open(harness)

        viewModel.setSuspended(harness.session, suspended = true)

        assertEquals("the write was sent once", 1, harness.passCalls("/suspend").size)
        assertEquals("active", viewModel.state.value.organization?.status)
        assertEquals("the reload brought the newer revision", 9L, viewModel.state.value.organization?.policy_revision)
        assertEquals(PlatformNotice.Changed, viewModel.state.value.notice)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `a refused suspend leaves the organization as it was and says what to do`() = runTest {
        val harness = server(write = { request ->
            if (request.url.encodedPath.endsWith("/suspend")) HttpStatusCode.ServiceUnavailable to PlatformFixtures.error("tenant_unavailable", "tenant_unavailable") else null
        })
        val viewModel = PlatformOrganizationViewModel()
        viewModel.open(harness)

        viewModel.setSuspended(harness.session, suspended = true)

        assertEquals("active", viewModel.state.value.organization?.status)
        assertEquals(ErrorText.Http(R.string.platform_org_suspend_error, 503), viewModel.state.value.error)
        assertNull(viewModel.state.value.notice)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `nothing is sent before an organization has loaded`() = runTest {
        val harness = server()
        val viewModel = PlatformOrganizationViewModel()

        viewModel.setSuspended(harness.session, suspended = true)

        assertTrue(harness.passRequests.isEmpty())
    }
}
