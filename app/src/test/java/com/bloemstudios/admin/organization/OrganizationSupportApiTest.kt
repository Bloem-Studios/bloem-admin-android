package com.bloemstudios.admin.organization

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.addSupportProfile
import com.bloemstudios.admin.network.api.clearSupportProfilePin
import com.bloemstudios.admin.network.api.createSupportPasswordReset
import com.bloemstudios.admin.network.api.endSupportAccess
import com.bloemstudios.admin.network.api.getBloemFeatureTokens
import com.bloemstudios.admin.network.api.getSupportCapabilities
import com.bloemstudios.admin.network.api.listPlatformOrganizations
import com.bloemstudios.admin.network.api.listSupportDevices
import com.bloemstudios.admin.network.api.listSupportSessions
import com.bloemstudios.admin.network.api.listSupportStreams
import com.bloemstudios.admin.network.api.messageSupportStream
import com.bloemstudios.admin.network.api.mintAdminSession
import com.bloemstudios.admin.network.api.removeSupportProfile
import com.bloemstudios.admin.network.api.renameSupportProfile
import com.bloemstudios.admin.network.api.signOutSupportDevice
import com.bloemstudios.admin.network.api.signOutSupportEverywhere
import com.bloemstudios.admin.network.api.signOutSupportSession
import com.bloemstudios.admin.network.api.stopSupportStream
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

/** Every organization support call: the request it makes (method, path, body) and how the answer decodes. */
class OrganizationSupportApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<HttpRequestData>()
    private val base = "/api/bloem/v2/admin/organization"

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, headers: io.ktor.http.Headers = json): ApiClient =
        ApiClient("https://example.test", F.PASS, MockEngine { request ->
            seen += request
            respond(body, status, headers)
        })

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject

    // ---- Capabilities --------------------------------------------------------------------------------

    @Test
    fun `capabilities decode what the server can do`() = runTest {
        val caps = client(body = F.supportCapabilities(email = false, clearPin = false, message = false)).getSupportCapabilities()
        assertEquals(HttpMethod.Get, seen.single().method)
        assertEquals("$base/support-capabilities", seen.single().url.encodedPath)
        assertFalse(caps.password_reset.email)
        assertTrue(caps.password_reset.link)
        assertTrue(caps.sessions && caps.devices)
        assertTrue(caps.profiles.add && caps.profiles.rename && caps.profiles.remove)
        assertFalse(caps.profiles.clear_pin)
        assertTrue(caps.streams.list && caps.streams.stop)
        assertFalse(caps.streams.message)
        assertTrue(caps.hasPersonSupport)
    }

    @Test
    fun `a capabilities answer that names nothing means nothing`() = runTest {
        val caps = client(body = "{}").getSupportCapabilities()
        assertEquals(SupportCapabilities.NONE, caps)
        assertFalse(caps.hasPersonSupport)
    }

    @Test
    fun `an older server answers 404 for the capabilities route`() = runTest {
        try {
            client(HttpStatusCode.NotFound, F.error("not_found")).getSupportCapabilities()
            fail("expected a 404")
        } catch (error: ApiError) {
            assertEquals(404, error.statusCode)
        }
    }

    @Test
    fun `the public feature list carries the support token`() = runTest {
        val tokens = client(body = """{"api":"bloem","feature_tokens":["a","organization_support_v1"]}""").getBloemFeatureTokens()
        assertEquals("/api/bloem/v2/capabilities", seen.single().url.encodedPath)
        assertTrue("organization_support_v1" in tokens)
    }

    // ---- Password reset ------------------------------------------------------------------------------

    @Test
    fun `an emailed reset sends delivery and the person's revision`() = runTest {
        val answer = client(HttpStatusCode.Created, F.reset("email", "sent")).createSupportPasswordReset(5, ResetDelivery.EMAIL, 4)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$base/people/5/password-reset", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals(setOf("expected_revision", "delivery"), body.keys)
        assertEquals("4", body.getValue("expected_revision").jsonPrimitive.content)
        assertEquals("email", body.getValue("delivery").jsonPrimitive.content)
        assertEquals("sent", answer.reset.delivery_status)
        assertNull(answer.reset.reset_url)
        assertEquals(4L, answer.person.security_revision)
    }

    @Test
    fun `a link reset returns the one-time link and hides it when printed`() = runTest {
        val answer = client(HttpStatusCode.Created, F.reset("link", "not_requested", F.RESET_URL)).createSupportPasswordReset(5, ResetDelivery.LINK, 4)
        assertEquals("link", bodyOf(seen.single()).getValue("delivery").jsonPrimitive.content)
        assertEquals(F.RESET_URL, answer.reset.reset_url)
        assertFalse("the link must not print", answer.reset.toString().contains("one-time-secret"))
        assertFalse(answer.toString().contains("one-time-secret"))
    }

    @Test
    fun `a refused reset carries the server's reason for the protected person`() = runTest {
        try {
            client(HttpStatusCode.Conflict, F.protectedTarget("organization_owner")).createSupportPasswordReset(5, ResetDelivery.LINK, 4)
            fail("expected a 409")
        } catch (error: ApiError) {
            assertEquals(409, error.statusCode)
            assertEquals("target_protected", error.code)
            assertEquals("organization_owner", error.reason)
        }
    }

    // ---- Sessions and devices ------------------------------------------------------------------------

    @Test
    fun `sessions decode without any address`() = runTest {
        val rows = client(body = F.supportSessions(F.supportSession("s1"), F.supportSession("s2", profile = null))).listSupportSessions(5)
        assertEquals(HttpMethod.Get, seen.single().method)
        assertEquals("$base/people/5/sessions", seen.single().url.encodedPath)
        assertEquals(listOf("s1", "s2"), rows.map { it.id })
        assertEquals("Sam's TV", rows[0].device_name)
        assertEquals("Living room", rows[0].profile_name)
        assertNull(rows[1].profile_name)
        assertEquals("password", rows[0].auth_method)
    }

    @Test
    fun `signing out everywhere quotes the revision and returns the raised one`() = runTest {
        val answer = client(body = F.signedOut(count = 3, revision = 5)).signOutSupportEverywhere(5, 4)
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$base/people/5/sessions", request.url.encodedPath)
        assertEquals(setOf("expected_revision"), bodyOf(request).keys)
        assertEquals("4", bodyOf(request).getValue("expected_revision").jsonPrimitive.content)
        assertEquals(3L, answer.revoked.sessions)
        assertEquals(5L, answer.person.security_revision)
    }

    @Test
    fun `signing out one session names it in the path and sends no body`() = runTest {
        client(HttpStatusCode.NoContent, "").signOutSupportSession(5, "s 1")
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$base/people/5/sessions/s%201", request.url.encodedPath)
        assertEquals(0, request.body.toByteArray().size)
    }

    @Test
    fun `devices decode with the profiles used on each`() = runTest {
        val rows = client(body = F.supportDevices(F.supportDevice("d1"), F.supportDevice("d2", "Phone", profiles = ""))).listSupportDevices(5)
        assertEquals("$base/people/5/devices", seen.single().url.encodedPath)
        assertEquals(listOf("d1", "d2"), rows.map { it.device_id })
        assertEquals("Living room", rows[0].profiles.single().profile_name)
        assertTrue(rows[1].profiles.isEmpty())
        assertEquals("tvos", rows[0].platform)
    }

    @Test
    fun `signing out one device is a delete with no body`() = runTest {
        client(HttpStatusCode.NoContent, "").signOutSupportDevice(5, "d1")
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$base/people/5/devices/d1", request.url.encodedPath)
        assertEquals(0, request.body.toByteArray().size)
    }

    // ---- Profiles ------------------------------------------------------------------------------------

    @Test
    fun `adding a profile sends the trimmed name and the group when chosen`() = runTest {
        val answer = client(HttpStatusCode.Created, F.profileResponse(F.supportProfile("p9", "Kids"))).addSupportProfile(5, "  Kids ", 3)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$base/people/5/profiles", request.url.encodedPath)
        assertEquals(setOf("name", "group_id"), bodyOf(request).keys)
        assertEquals("Kids", bodyOf(request).getValue("name").jsonPrimitive.content)
        assertEquals("p9", answer.profile.id)
        assertEquals(3L, answer.profile.revision)
        assertEquals(4L, answer.person.security_revision)
    }

    @Test
    fun `adding a profile without a group leaves the group out`() = runTest {
        client(HttpStatusCode.Created, F.profileResponse()).addSupportProfile(5, "Kids", null)
        assertEquals(setOf("name"), bodyOf(seen.single()).keys)
    }

    @Test
    fun `renaming quotes the profile's revision`() = runTest {
        val answer = client(body = F.profileResponse(F.supportProfile(name = "Bedroom", revision = 4))).renameSupportProfile(5, "p1", " Bedroom ", 3)
        val request = seen.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("$base/people/5/profiles/p1/details", request.url.encodedPath)
        assertEquals("3", bodyOf(request).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("Bedroom", bodyOf(request).getValue("name").jsonPrimitive.content)
        assertEquals(4L, answer.profile.revision)
    }

    @Test
    fun `clearing a PIN sends the revision as a delete body and returns the profile`() = runTest {
        val profile = client(body = F.profileOnly(F.supportProfile(hasPin = false, revision = 4))).clearSupportProfilePin(5, "p1", 3)
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$base/people/5/profiles/p1/pin", request.url.encodedPath)
        assertEquals(setOf("expected_revision"), bodyOf(request).keys)
        assertFalse(profile.has_pin)
        assertEquals(4L, profile.revision)
    }

    @Test
    fun `removing a profile quotes the revision and answers 204`() = runTest {
        client(HttpStatusCode.NoContent, "").removeSupportProfile(5, "p1", 3)
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$base/people/5/profiles/p1", request.url.encodedPath)
        assertEquals("3", bodyOf(request).getValue("expected_revision").jsonPrimitive.content)
    }

    @Test
    fun `a profile id is encoded in the path`() = runTest {
        client(HttpStatusCode.NoContent, "").removeSupportProfile(5, "a/b", 3)
        assertEquals("$base/people/5/profiles/a%2Fb", seen.single().url.encodedPath)
    }

    @Test
    fun `a profile revision conflict decodes as the stale-state code`() = runTest {
        try {
            client(HttpStatusCode.Conflict, """{"error":"authorization_state_changed","message":"The profile changed","current_revision":9}""")
                .renameSupportProfile(5, "p1", "x", 3)
            fail("expected a 409")
        } catch (error: ApiError) {
            assertEquals("authorization_state_changed", error.code)
        }
    }

    @Test
    fun `profile summaries from a server without support routes still decode`() {
        val person = Json { ignoreUnknownKeys = true }.decodeFromString<PersonResponse>(F.personResponse()).person
        assertEquals(0L, person.profiles.single().revision)
        assertFalse(person.profiles.single().has_pin)
        assertFalse(person.profiles.single().is_primary)
    }

    // ---- Streams -------------------------------------------------------------------------------------

    @Test
    fun `streams decode and carry no client address`() = runTest {
        val page = client(body = F.supportStreams(F.supportStream("st1"), F.supportStream("st2", series = null, canStop = false), next = "n1")).listSupportStreams(null, 100)
        val request = seen.single()
        assertEquals("$base/streams", request.url.encodedPath)
        assertEquals("100", request.url.parameters["limit"])
        assertNull(request.url.parameters["cursor"])
        assertEquals(listOf("st1", "st2"), page.streams.map { it.session_id })
        assertEquals("n1", page.next_cursor)
        val first = page.streams[0]
        assertEquals("Sam", first.person_name)
        assertEquals("transcode", first.play_method)
        assertEquals(3600L, first.duration_seconds)
        assertEquals("Bloem tvOS", first.device.app)
        assertTrue(first.can_stop && first.can_message)
        assertFalse(page.streams[1].can_stop)
        assertNull(page.streams[1].series_name)
    }

    @Test
    fun `the next page sends the cursor`() = runTest {
        client(body = F.supportStreams()).listSupportStreams("n1", 50)
        assertEquals("n1", seen.single().url.parameters["cursor"])
        assertEquals("50", seen.single().url.parameters["limit"])
    }

    @Test
    fun `stopping a stream posts an empty body without a reason`() = runTest {
        val result = client(body = F.stopped()).stopSupportStream("st1")
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$base/streams/st1/stop", request.url.encodedPath)
        assertTrue(bodyOf(request).isEmpty())
        assertTrue(result.authority_revoked && result.client_notified)
        assertFalse(result.already_revoked)
    }

    @Test
    fun `stopping a stream can carry a reason`() = runTest {
        client(body = F.stopped(already = true)).stopSupportStream("st1", " shared screen ")
        assertEquals("shared screen", bodyOf(seen.single()).getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `a message sends the command identity once`() = runTest {
        val receipt = client(HttpStatusCode.Accepted, F.messageReceipt(delivery = "fallback_scheduled"))
            .messageSupportStream("st1", " Hello ", " Please turn it down ", "11111111-1111-1111-1111-111111111111", 7)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$base/streams/st1/message", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals(setOf("command_id", "sequence", "message", "title"), body.keys)
        assertEquals("Please turn it down", body.getValue("message").jsonPrimitive.content)
        assertEquals("Hello", body.getValue("title").jsonPrimitive.content)
        assertEquals("7", body.getValue("sequence").jsonPrimitive.content)
        assertEquals("fallback_scheduled", receipt.delivery)
        assertEquals("applied", receipt.outcome)
    }

    @Test
    fun `a message without a title leaves it out`() = runTest {
        client(body = F.messageReceipt(outcome = "replayed")).messageSupportStream("st1", "  ", "Hi", "c", 1)
        assertFalse("title" in bodyOf(seen.single()).keys)
    }

    @Test
    fun `a stale message sequence reports the latest one`() = runTest {
        val stale = headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Silo-Latest-Sequence" to listOf("12"))
        try {
            client(HttpStatusCode.Conflict, F.error("stale_sequence"), stale).messageSupportStream("st1", null, "Hi", "c", 1)
            fail("expected a 409")
        } catch (error: ApiError) {
            assertEquals("stale_sequence", error.code)
            assertEquals(12L, error.latestSequence)
        }
    }

    // ---- Support access ------------------------------------------------------------------------------

    @Test
    fun `opening support access sends a reason and no membership`() = runTest {
        val response = client(body = F.supportSessionPass()).mintAdminSession(
            AdminSessionRequest("organization", F.ORG_ID, AdminSessionSupport(reason = "The administrator is locked out")),
        )
        val body = bodyOf(seen.single())
        assertEquals(setOf("scope", "organization_id", "support"), body.keys)
        assertEquals(setOf("reason"), body.getValue("support").jsonObject.keys)
        assertEquals("support", response.context.access_basis)
        assertEquals(F.SUPPORT_ACCESS_ID, response.context.support_access_id)
        assertEquals("2026-11-14T14:00:00Z", response.context.support_expires_at)
        assertEquals("platform_admin", response.context.authority)
        assertNull(response.context.membership_id)
    }

    @Test
    fun `renewing support access names the access and never repeats the reason`() = runTest {
        client(body = F.supportSessionPass()).mintAdminSession(
            AdminSessionRequest("organization", F.ORG_ID, AdminSessionSupport(access_id = F.SUPPORT_ACCESS_ID)),
        )
        val support = bodyOf(seen.single()).getValue("support").jsonObject
        assertEquals(setOf("access_id"), support.keys)
    }

    @Test
    fun `an ordinary pass still sends no support object`() = runTest {
        client(body = F.session()).mintAdminSession(AdminSessionRequest("organization", F.ORG_ID))
        assertFalse("support" in bodyOf(seen.single()).keys)
    }

    @Test
    fun `ending support access is a delete with no body`() = runTest {
        client(HttpStatusCode.NoContent, "").endSupportAccess()
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$base/support-access", request.url.encodedPath)
        assertEquals(0, request.body.toByteArray().size)
    }

    @Test
    fun `the platform organization list sends the search and decodes the page`() = runTest {
        val page = client(body = F.platformOrganizations(F.platformOrganization(), F.platformOrganization("o2", "Beta", "suspended"), next = "n2"))
            .listPlatformOrganizations(" acme ", null, 50)
        val request = seen.single()
        assertEquals("/api/bloem/v2/admin/platform/organizations/", request.url.encodedPath)
        assertEquals("acme", request.url.parameters["query"])
        assertEquals("50", request.url.parameters["limit"])
        assertEquals(listOf("Acme Media", "Beta"), page.organizations.map { it.name })
        assertEquals("suspended", page.organizations[1].status)
        assertEquals("n2", page.next_cursor)
    }

    @Test
    fun `reasons are accepted at 10 to 500 characters without control characters`() {
        assertNull(SupportReason.valid("too short"))
        assertEquals("exactly ten", SupportReason.valid("  exactly ten  "))
        assertNull(SupportReason.valid("x".repeat(501)))
        assertEquals(500, SupportReason.valid("x".repeat(500))?.length)
        assertNull(SupportReason.valid("line one\nline two is here"))
    }
}
