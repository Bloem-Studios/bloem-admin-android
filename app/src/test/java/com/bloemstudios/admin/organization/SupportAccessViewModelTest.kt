package com.bloemstudios.admin.organization

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import com.bloemstudios.admin.ui.shell.AdminGroup
import com.bloemstudios.admin.ui.shell.aggregateDestinations
import com.bloemstudios.admin.ui.shell.organizationDestinations
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Owner's way in (who sees it, the list, the reason) and the gating of what the organization menu offers. */
class SupportAccessViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val accountRequests = mutableListOf<HttpRequestData>()
    private val accountBodies = mutableListOf<String>()
    private val passRequests = mutableListOf<HttpRequestData>()
    private var platformPage: (HttpRequestData) -> String = { F.platformOrganizations(F.platformOrganization(), F.platformOrganization("o2", "Beta", "suspended")) }
    private var memberships = F.memberships()
    private var mintStatus = HttpStatusCode.OK
    private var passClosed = 0

    private val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
        accountRequests += request
        accountBodies += String(request.body.toByteArray())
        when (request.url.encodedPath) {
            "/api/bloem/v2/organizations" -> respond(memberships, HttpStatusCode.OK, json)
            else -> if (mintStatus != HttpStatusCode.OK) respond(F.error("forbidden", "Not a platform administrator"), mintStatus, json)
            else respond(
                """{"access_token":"platform-pass","expires_at":"2026-11-14T10:15:00Z","context":{"key":"platform","scope":"platform","name":"Platform","status":"active","authority":"platform_admin"}}""",
                HttpStatusCode.OK, json,
            )
        }
    })

    private fun viewModel() = SupportOrganizationsViewModel { _, token ->
        ApiClient(com.bloemstudios.admin.network.ServerOrigin.parse("https://example.test"), AccessTokenSnapshot(token, 0), MockEngine { request ->
            passRequests += request
            respond(platformPage(request), HttpStatusCode.OK, json)
        })
    }

    @Test
    fun `the list is read with a platform pass that is used once`() = runTest {
        val vm = viewModel()
        vm.load(account)
        val mint = accountRequests.first { it.url.encodedPath == "/api/bloem/v2/admin/session" }
        assertEquals(setOf("scope"), Json.parseToJsonElement(accountBodies[accountRequests.indexOf(mint)]).jsonObject.keys)
        assertEquals("Bearer platform-pass", passRequests.single().headers[HttpHeaders.Authorization])
        assertEquals(listOf("Acme Media", "Beta"), vm.state.value.organizations.map { it.name })
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a search sends the text and replaces the list`() = runTest {
        val vm = viewModel()
        vm.load(account)
        platformPage = { F.platformOrganizations(F.platformOrganization("o3", "Acorn")) }
        vm.load(account, "ac")
        assertEquals("ac", passRequests.last().url.parameters["query"])
        assertEquals(listOf("Acorn"), vm.state.value.organizations.map { it.name })
    }

    @Test
    fun `more pages are appended`() = runTest {
        platformPage = { request ->
            if (request.url.parameters["cursor"] == null) F.platformOrganizations(F.platformOrganization("o1", "A"), next = "n1")
            else F.platformOrganizations(F.platformOrganization("o2", "B"))
        }
        val vm = viewModel()
        vm.load(account)
        assertEquals("n1", vm.state.value.nextCursor)
        vm.loadMore(account)
        assertEquals(listOf("A", "B"), vm.state.value.organizations.map { it.name })
        assertNull(vm.state.value.nextCursor)
        assertEquals("n1", passRequests.last().url.parameters["cursor"])
    }

    @Test
    fun `organizations the account belongs to are marked so they open the usual way`() = runTest {
        memberships = F.memberships(F.membership(id = "o2", name = "Beta"))
        val vm = viewModel()
        vm.load(account)
        assertEquals(setOf("o2"), vm.state.value.memberOrganizationIds)
    }

    @Test
    fun `an account that is not a platform administrator gets the server's words`() = runTest {
        mintStatus = HttpStatusCode.Forbidden
        val vm = viewModel()
        vm.load(account)
        assertEquals(ErrorText.Raw("Not a platform administrator"), vm.state.value.error)
        assertTrue(passRequests.isEmpty())
    }

    // ---- The reason ----------------------------------------------------------------------------------------

    @Test
    fun `the reason the page accepts is the one the server accepts`() {
        assertEquals(ReasonProblem.Short, reasonProblem(""))
        assertEquals(ReasonProblem.Short, reasonProblem("   too short   "))
        assertNull(reasonProblem("exactly ten"))
        assertNull(reasonProblem("x".repeat(500)))
        assertEquals(ReasonProblem.Long, reasonProblem("x".repeat(501)))
        assertEquals(ReasonProblem.Control, reasonProblem("one line\u0007 with a bell"))
    }

    // ---- Who is offered it -----------------------------------------------------------------------------

    private fun feature(tokens: String, owner: Boolean?): ApiClient = ApiClient("https://example.test", "t", MockEngine { request ->
        when (request.url.encodedPath) {
            "/api/bloem/v2/capabilities" -> respond("""{"feature_tokens":$tokens}""", HttpStatusCode.OK, json)
            "/api/v2/account/me" -> respond("""{"id":"1","username":"alex","email":"j@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""", HttpStatusCode.OK, json)
            "/api/v2/admin/users/1" -> if (owner == null) respond(F.error("forbidden"), HttpStatusCode.Forbidden, json)
            else respond(com.bloemstudios.admin.auth.V2Fixtures.adminUser().replace("\"is_owner\":false", "\"is_owner\":$owner"), HttpStatusCode.OK, json)
            else -> respond(F.error("not_found"), HttpStatusCode.NotFound, json)
        }
    })

    @Test
    fun `only the Owner of a server that advertises it is offered support access`() = runTest {
        assertTrue(feature("""["organization_support_v1"]""", owner = true).canOpenSupportAccess())
        assertFalse("an ordinary platform administrator", feature("""["organization_support_v1"]""", owner = false).canOpenSupportAccess())
        assertFalse("a server without the routes", feature("""["other"]""", owner = true).canOpenSupportAccess())
        assertFalse("any doubt is no", feature("""["organization_support_v1"]""", owner = null).canOpenSupportAccess())
    }

    // ---- Menus ------------------------------------------------------------------------------------------

    @Test
    fun `the organization menu shows Now Playing only where the server can list streams`() {
        assertFalse("organization.streams" in organizationDestinations().map { it.id })
        val shown = organizationDestinations(streamsVisible = true).map { it.id }
        assertTrue("organization.streams" in shown)
        assertEquals(organizationDestinations().map { it.id }, shown - "organization.streams")
        assertTrue(organizationDestinations(streamsVisible = true).all { it.group == AdminGroup.Organization })
    }

    @Test
    fun `support access is a platform page for the Owner and nothing else`() {
        assertFalse(singleServerDestinations(tenantsVisible = false).any { it.id == "platform.support-access" })
        assertTrue(singleServerDestinations(tenantsVisible = false, supportAccessVisible = true).any { it.id == "platform.support-access" })
        assertFalse(aggregateDestinations().any { it.id == "platform.support-access" })
        val organization = organizationDestinations(streamsVisible = true).map { it.id }.toSet()
        val platform = (singleServerDestinations(tenantsVisible = true, supportAccessVisible = true) + aggregateDestinations()).map { it.id }.toSet()
        assertTrue("no shared ids", organization.intersect(platform).isEmpty())
    }

    // ---- The count-down -------------------------------------------------------------------------------------

    @Test
    fun `time left counts down in whole minutes, rounded up`() {
        val end = Instant.parse("2026-11-14T14:00:00Z")
        assertEquals(SupportTimeLeft(4, 0), supportTimeLeft(Instant.parse("2026-11-14T10:00:00Z"), end))
        assertEquals(SupportTimeLeft(4, 0), supportTimeLeft(Instant.parse("2026-11-14T10:00:01Z"), end))
        assertEquals(SupportTimeLeft(3, 59), supportTimeLeft(Instant.parse("2026-11-14T10:01:01Z"), end))
        assertEquals(SupportTimeLeft(0, 1), supportTimeLeft(Instant.parse("2026-11-14T13:59:30Z"), end))
        assertEquals(SupportTimeLeft(0, 59), supportTimeLeft(Instant.parse("2026-11-14T13:01:00Z"), end))
        assertTrue(supportTimeLeft(end, end).isOver)
        assertTrue(supportTimeLeft(end.plusSeconds(5), end).isOver)
        assertTrue("no end means nothing left", supportTimeLeft(end, null).isOver)
    }
}
