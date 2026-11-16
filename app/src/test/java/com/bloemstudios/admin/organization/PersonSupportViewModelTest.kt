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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The person page's support actions: gating on the server's capabilities, what each write sends, and what the page then shows. */
class PersonSupportViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = "/api/bloem/v2/admin/organization"
    private val sent = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private class Route(val method: HttpMethod, val path: String, val status: HttpStatusCode, val body: String)

    private val routes = mutableListOf<Route>()
    private var person = F.supportPerson(revision = 4)
    private var passes = 0

    private fun on(method: HttpMethod, path: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        routes.removeAll { it.method == method && it.path == "$base$path" }
        routes += Route(method, "$base$path", status, body)
    }

    private fun standard(caps: String = F.supportCapabilities()) {
        on(HttpMethod.Get, "/support-capabilities", caps)
        on(HttpMethod.Get, "/people/5/", F.personResponse(person))
        on(HttpMethod.Get, "/groups", F.groups(F.group()))
        on(HttpMethod.Get, "/activity", F.audit())
        on(HttpMethod.Get, "/people/5/sessions", F.supportSessions(F.supportSession("s1"), F.supportSession("s2", "Phone")))
        on(HttpMethod.Get, "/people/5/devices", F.supportDevices(F.supportDevice("d1")))
    }

    private suspend fun opened(): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine {
            passes += 1
            respond(F.session(token = "pass-$passes", policyRevision = 7L), HttpStatusCode.OK, json)
        })
        val session = OrganizationSession(
            account, F.ORG_ID, clock = { Instant.parse("2026-11-13T12:00:00Z") },
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    sent += request
                    bodies += String(request.body.toByteArray())
                    val route = routes.lastOrNull { it.method == request.method && it.path.trimEnd('/') == request.url.encodedPath.trimEnd('/') }
                    if (route == null) respond(F.error("not_found"), HttpStatusCode.NotFound, json)
                    else respond(route.body, route.status, json)
                }, recovery)
            },
        )
        session.open()
        return session
    }

    private suspend fun page(): Pair<OrganizationSession, PersonDetailViewModel> {
        val session = opened()
        val vm = PersonDetailViewModel()
        vm.load(session, 5)
        return session to vm
    }

    private fun writes(method: HttpMethod) = sent.filter { it.method == method }
    private fun bodyOfLast(method: HttpMethod, path: String): kotlinx.serialization.json.JsonObject {
        val index = sent.indexOfLast { it.method == method && it.url.encodedPath == "$base$path" }
        return Json.parseToJsonElement(bodies[index]).jsonObject
    }

    // ---- Gating ------------------------------------------------------------------------------------------

    @Test
    fun `a server without the support routes offers nothing new`() = runTest {
        standard()
        on(HttpMethod.Get, "/support-capabilities", F.error("not_found"), HttpStatusCode.NotFound)
        val (_, vm) = page()
        val state = vm.state.value
        assertEquals(SupportCapabilities.NONE, state.capabilities)
        assertFalse(state.sessions.loaded)
        assertFalse(state.devices.loaded)
        assertTrue("no sign-in or device was even asked for", sent.none { it.url.encodedPath.endsWith("/sessions") || it.url.encodedPath.endsWith("/devices") })
        assertNotNull("the person still loads", state.person)
    }

    @Test
    fun `sign-ins and devices load when the server offers them`() = runTest {
        standard()
        val (_, vm) = page()
        val state = vm.state.value
        assertTrue(state.capabilities.sessions)
        assertEquals(listOf("s1", "s2"), state.sessions.items.map { it.id })
        assertEquals(listOf("d1"), state.devices.items.map { it.device_id })
        assertNull(state.protectedMessage)
    }

    @Test
    fun `a capability the server says false is not asked for`() = runTest {
        standard(F.supportCapabilities(sessions = false, devices = false, email = false))
        val (_, vm) = page()
        assertFalse(vm.state.value.sessions.loaded)
        assertTrue(sent.none { it.url.encodedPath.endsWith("/sessions") || it.url.encodedPath.endsWith("/devices") })
    }

    @Test
    fun `a suspended person is offered no support lists`() = runTest {
        person = F.person(status = "suspended")
        standard()
        val (_, vm) = page()
        assertFalse(vm.state.value.sessions.loaded)
    }

    @Test
    fun `your own page is offered no support`() = runTest {
        // The pass's own membership is this person's membership.
        person = F.supportPerson().replace("person-membership-5", F.MEMBERSHIP_ID)
        standard()
        val (_, vm) = page()
        assertFalse(vm.state.value.sessions.loaded)
        assertTrue(sent.none { it.url.encodedPath.endsWith("/sessions") || it.url.encodedPath.endsWith("/devices") })
    }

    @Test
    fun `a protected person is explained and never listed`() = runTest {
        standard()
        on(HttpMethod.Get, "/people/5/sessions", F.protectedTarget("organization_owner"), HttpStatusCode.Conflict)
        on(HttpMethod.Get, "/people/5/devices", F.protectedTarget("organization_owner"), HttpStatusCode.Conflict)
        val (_, vm) = page()
        val state = vm.state.value
        assertEquals(ErrorText.Res(R.string.organization_support_protected_owner), state.protectedMessage)
        assertTrue(state.sessions.unavailable)
        assertTrue(state.devices.unavailable)
    }

    @Test
    fun `a failed list read says unavailable and leaves the page`() = runTest {
        standard()
        on(HttpMethod.Get, "/people/5/devices", F.error("tenant_unavailable"), HttpStatusCode.ServiceUnavailable)
        val (_, vm) = page()
        assertTrue(vm.state.value.devices.unavailable)
        assertFalse(vm.state.value.sessions.unavailable)
        assertNotNull(vm.state.value.person)
        assertNull(vm.state.value.error)
    }

    // ---- Reset ---------------------------------------------------------------------------------------------

    @Test
    fun `a link reset quotes the person's revision and keeps the link once, in memory`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", F.reset("link", "not_requested", F.RESET_URL), HttpStatusCode.Created)
        val (session, vm) = page()

        assertTrue(vm.resetPassword(session, ResetDelivery.LINK))

        val body = bodyOfLast(HttpMethod.Post, "/people/5/password-reset")
        assertEquals("link", body.getValue("delivery").jsonPrimitive.content)
        assertEquals("4", body.getValue("expected_revision").jsonPrimitive.content)
        val link = vm.state.value.resetLink
        assertEquals(F.RESET_URL, link?.url)
        assertEquals("Sam", link?.personLabel)
        assertFalse("the link never prints", link.toString().contains("one-time-secret"))
        assertFalse(vm.state.value.toString().contains("one-time-secret"))

        vm.dismissResetLink()
        assertNull(vm.state.value.resetLink)
    }

    @Test
    fun `an emailed reset says it was sent, or that the server could not confirm it`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", F.reset("email", "sent"), HttpStatusCode.Created)
        val (session, vm) = page()
        assertTrue(vm.resetPassword(session, ResetDelivery.EMAIL))
        assertEquals(ErrorText.Res(R.string.organization_support_notice_reset_sent), vm.state.value.notice)
        assertNull(vm.state.value.resetLink)

        on(HttpMethod.Post, "/people/5/password-reset", F.reset("email", "failed_or_unknown"), HttpStatusCode.Created)
        assertTrue(vm.resetPassword(session, ResetDelivery.EMAIL))
        assertEquals(ErrorText.Res(R.string.organization_support_notice_reset_unconfirmed), vm.state.value.notice)
    }

    @Test
    fun `email is not sent when the server cannot deliver it`() = runTest {
        standard(F.supportCapabilities(email = false))
        val (session, vm) = page()
        assertFalse(vm.resetPassword(session, ResetDelivery.EMAIL))
        assertTrue(writes(HttpMethod.Post).isEmpty())
    }

    @Test
    fun `no reset at all when the server offers neither`() = runTest {
        standard(F.supportCapabilities(email = false, link = false))
        val (session, vm) = page()
        assertFalse(vm.resetPassword(session, ResetDelivery.LINK))
        assertTrue(writes(HttpMethod.Post).isEmpty())
    }

    @Test
    fun `a refused reset shows who is protected and reads the person again`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", F.protectedTarget("organization_admin"), HttpStatusCode.Conflict)
        val (session, vm) = page()
        val reads = sent.count { it.url.encodedPath == "$base/people/5" }
        assertFalse(vm.resetPassword(session, ResetDelivery.LINK))
        assertEquals(ErrorText.Res(R.string.organization_support_protected_admin), vm.state.value.error)
        assertNull(vm.state.value.resetLink)
        assertFalse(vm.state.value.pending)
        assertEquals(reads + 1, sent.count { it.url.encodedPath == "$base/people/5" })
    }

    @Test
    fun `a stale revision reloads and asks the administrator to try again`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", """{"error":"authorization_state_changed","message":"changed","current_revision":9}""", HttpStatusCode.Conflict)
        val (session, vm) = page()
        assertFalse(vm.resetPassword(session, ResetDelivery.LINK))
        assertEquals(ErrorText.Res(R.string.organization_changed_conflict), vm.state.value.error)
        assertEquals("the pass was renewed", 2, passes)
        assertEquals("the write was sent once", 1, writes(HttpMethod.Post).size)
    }

    @Test
    fun `a rate limit shows the server's words`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", F.error("rate_limited", "Too many support actions; try again later"), HttpStatusCode.TooManyRequests)
        val (session, vm) = page()
        assertFalse(vm.resetPassword(session, ResetDelivery.LINK))
        assertEquals(ErrorText.Raw("Too many support actions; try again later"), vm.state.value.error)
    }

    @Test
    fun `an issued link the server did not return is not pretended`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", F.reset("link", "not_requested"), HttpStatusCode.Created)
        val (session, vm) = page()
        assertTrue(vm.resetPassword(session, ResetDelivery.LINK))
        assertNull(vm.state.value.resetLink)
        assertEquals(ErrorText.Res(R.string.organization_support_error_link_missing), vm.state.value.error)
    }

    // ---- Sign-outs ------------------------------------------------------------------------------------------

    @Test
    fun `signing out everywhere takes in the raised revision and reads the lists again`() = runTest {
        standard()
        on(HttpMethod.Delete, "/people/5/sessions", F.signedOut(count = 2, revision = 5))
        val (session, vm) = page()
        on(HttpMethod.Get, "/people/5/", F.personResponse(F.supportPerson(revision = 5)))
        on(HttpMethod.Get, "/people/5/sessions", F.supportSessions())

        assertTrue(vm.signOutEverywhere(session))

        assertEquals("4", bodyOfLast(HttpMethod.Delete, "/people/5/sessions").getValue("expected_revision").jsonPrimitive.content)
        assertEquals(5L, vm.state.value.person?.security_revision)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_signed_out_all), vm.state.value.notice)
        assertTrue(vm.state.value.sessions.items.isEmpty())
        assertTrue(vm.state.value.sessions.loaded)
    }

    @Test
    fun `signing out one session names it and quotes no revision`() = runTest {
        standard()
        on(HttpMethod.Delete, "/people/5/sessions/s2", "", HttpStatusCode.NoContent)
        val (session, vm) = page()
        val target = vm.state.value.sessions.items.first { it.id == "s2" }
        assertTrue(vm.signOutSession(session, target))
        val request = sent.last { it.method == HttpMethod.Delete }
        assertEquals("$base/people/5/sessions/s2", request.url.encodedPath)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_signed_out), vm.state.value.notice)
    }

    @Test
    fun `signing out one device names it`() = runTest {
        standard()
        on(HttpMethod.Delete, "/people/5/devices/d1", "", HttpStatusCode.NoContent)
        val (session, vm) = page()
        assertTrue(vm.signOutDevice(session, vm.state.value.devices.items.single()))
        assertEquals("$base/people/5/devices/d1", sent.last { it.method == HttpMethod.Delete }.url.encodedPath)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_device_signed_out), vm.state.value.notice)
    }

    @Test
    fun `sign-outs are not sent when the server does not offer them`() = runTest {
        standard(F.supportCapabilities(sessions = false, devices = false))
        val (session, vm) = page()
        val target = SupportSession("s1")
        assertFalse(vm.signOutEverywhere(session))
        assertFalse(vm.signOutSession(session, target))
        assertFalse(vm.signOutDevice(session, SupportDevice("d1")))
        assertTrue(writes(HttpMethod.Delete).isEmpty())
    }

    // ---- Profiles ------------------------------------------------------------------------------------------

    private val living = ProfileSummary("p1", "Living room", 3, "Family", "", revision = 3, has_pin = true, is_primary = false)
    private val main = ProfileSummary("p0", "Sam", 3, "Family", "", revision = 2, has_pin = false, is_primary = true)

    @Test
    fun `adding a profile sends the trimmed name and the chosen group`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/profiles", F.profileResponse(F.supportProfile("p9", "Kids")), HttpStatusCode.Created)
        val (session, vm) = page()
        val group = AccessGroup(id = "3", name = "Family")
        assertTrue(vm.addProfile(session, "  Kids ", group))
        val body = bodyOfLast(HttpMethod.Post, "/people/5/profiles")
        assertEquals("Kids", body.getValue("name").jsonPrimitive.content)
        assertEquals("3", body.getValue("group_id").jsonPrimitive.content)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_profile_added), vm.state.value.notice)
    }

    @Test
    fun `a blank profile name is refused on the page without a request`() = runTest {
        standard()
        val (session, vm) = page()
        assertFalse(vm.addProfile(session, "   ", null))
        assertFalse(vm.renameProfile(session, living, ""))
        assertEquals(ErrorText.Res(R.string.organization_support_error_name), vm.state.value.error)
        assertTrue(writes(HttpMethod.Post).isEmpty() && writes(HttpMethod.Patch).isEmpty())
    }

    @Test
    fun `renaming quotes the profile's own revision`() = runTest {
        standard()
        on(HttpMethod.Patch, "/people/5/profiles/p1/details", F.profileResponse(F.supportProfile(name = "Bedroom", revision = 4)))
        val (session, vm) = page()
        assertTrue(vm.renameProfile(session, living, " Bedroom "))
        val body = bodyOfLast(HttpMethod.Patch, "/people/5/profiles/p1/details")
        assertEquals("3", body.getValue("expected_revision").jsonPrimitive.content)
        assertEquals("Bedroom", body.getValue("name").jsonPrimitive.content)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_profile_renamed), vm.state.value.notice)
    }

    @Test
    fun `a name clash is shown with the server's words`() = runTest {
        standard()
        on(HttpMethod.Patch, "/people/5/profiles/p1/details", F.error("name_conflict", "A profile with this name already exists"), HttpStatusCode.Conflict)
        val (session, vm) = page()
        assertFalse(vm.renameProfile(session, living, "Sam"))
        assertEquals(ErrorText.Raw("A profile with this name already exists"), vm.state.value.error)
    }

    @Test
    fun `a rejected name or group says the server refused it`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/profiles", """{"error":"validation_failed","message":"validation failed","fields":{"name":"bad"}}""", HttpStatusCode.UnprocessableEntity)
        val (session, vm) = page()
        assertFalse(vm.addProfile(session, "x", null))
        assertEquals(ErrorText.Res(R.string.organization_support_error_profile_refused), vm.state.value.error)
    }

    @Test
    fun `clearing a PIN quotes the revision`() = runTest {
        standard()
        on(HttpMethod.Delete, "/people/5/profiles/p1/pin", F.profileOnly(F.supportProfile(revision = 4, hasPin = false)))
        val (session, vm) = page()
        assertTrue(vm.clearPin(session, living))
        assertEquals("3", bodyOfLast(HttpMethod.Delete, "/people/5/profiles/p1/pin").getValue("expected_revision").jsonPrimitive.content)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_pin_cleared), vm.state.value.notice)
    }

    @Test
    fun `removing a profile quotes the revision, and the main profile is never offered`() = runTest {
        standard()
        on(HttpMethod.Delete, "/people/5/profiles/p1", "", HttpStatusCode.NoContent)
        val (session, vm) = page()
        assertFalse("the main profile cannot be removed", vm.removeProfile(session, main))
        assertTrue(writes(HttpMethod.Delete).isEmpty())
        assertTrue(vm.removeProfile(session, living))
        assertEquals("3", bodyOfLast(HttpMethod.Delete, "/people/5/profiles/p1").getValue("expected_revision").jsonPrimitive.content)
        assertEquals(ErrorText.Res(R.string.organization_support_notice_profile_removed), vm.state.value.notice)
    }

    @Test
    fun `profile actions the server does not offer are not sent`() = runTest {
        standard(F.supportCapabilities(add = false, rename = false, clearPin = false, remove = false))
        val (session, vm) = page()
        assertFalse(vm.addProfile(session, "Kids", null))
        assertFalse(vm.renameProfile(session, living, "Bedroom"))
        assertFalse(vm.clearPin(session, living))
        assertFalse(vm.removeProfile(session, living))
        assertTrue(sent.none { it.method != HttpMethod.Get })
    }

    @Test
    fun `leaving the page lets go of the link`() = runTest {
        standard()
        on(HttpMethod.Post, "/people/5/password-reset", F.reset("link", "not_requested", F.RESET_URL), HttpStatusCode.Created)
        val (session, vm) = page()
        vm.resetPassword(session, ResetDelivery.LINK)
        assertNotNull(vm.state.value.resetLink)
        val clear = PersonDetailViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }
        clear.invoke(vm)
        assertNull(vm.state.value.resetLink)
    }
}
