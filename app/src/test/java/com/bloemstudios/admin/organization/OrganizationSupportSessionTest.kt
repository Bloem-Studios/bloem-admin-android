package com.bloemstudios.admin.organization

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
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
import org.junit.Assert.fail
import org.junit.Test

/** The session's support parts: the Owner's break-glass pass and its renewal, ending it, and the capabilities gate. */
class OrganizationSupportSessionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val mints = mutableListOf<String>()
    private val passRequests = mutableListOf<HttpRequestData>()
    private var mintStatus = HttpStatusCode.OK
    private var mintBody: () -> String = { F.supportSessionPass() }
    private var passHandler: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to F.supportCapabilities() }

    private val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
        mints += String(request.body.toByteArray())
        if (mintStatus != HttpStatusCode.OK) respond(F.error("not_found"), mintStatus, json)
        else respond(mintBody(), HttpStatusCode.OK, json)
    })

    private fun session(grant: SupportAccessGrant? = null) = OrganizationSession(
        account, F.ORG_ID, clock = { now },
        clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
            ApiClient(origin, snapshot, MockEngine { request ->
                passRequests += request
                val (status, body) = passHandler(request)
                respond(body, status, json)
            }, recovery)
        },
        support = grant,
    )

    private fun supportBody(index: Int) = Json.parseToJsonElement(mints[index]).jsonObject.getValue("support").jsonObject

    // ---- Opening and renewing the Owner's access --------------------------------------------------------

    @Test
    fun `the first pass states the reason and learns the access`() = runTest {
        val grant = SupportAccessGrant("The administrator is locked out")
        val session = session(grant)
        val context = session.open()

        assertEquals("The administrator is locked out", supportBody(0).getValue("reason").jsonPrimitive.content)
        assertFalse("access_id" in supportBody(0).keys)
        assertEquals(AccessBasis.Support, context.accessBasis)
        assertTrue(context.isSupport)
        assertEquals(AdminAuthority.PlatformAdmin, context.authority)
        assertEquals(F.SUPPORT_ACCESS_ID, context.supportAccessId)
        assertEquals(Instant.parse("2026-11-14T14:00:00Z"), context.supportExpiresAt)
        assertEquals(F.SUPPORT_ACCESS_ID, grant.accessId)
        assertTrue(session.isSupportAccess)
    }

    @Test
    fun `a renewal names the access and never repeats the reason`() = runTest {
        val session = session(SupportAccessGrant("The administrator is locked out"))
        session.open()
        session.refresh()
        session.open()

        assertEquals(setOf("reason"), supportBody(0).keys)
        assertEquals(setOf("access_id"), supportBody(1).keys)
        assertEquals(setOf("access_id"), supportBody(2).keys)
        assertEquals(F.SUPPORT_ACCESS_ID, supportBody(1).getValue("access_id").jsonPrimitive.content)
    }

    @Test
    fun `a renewal the server no longer finds is the end of the access`() = runTest {
        val session = session(SupportAccessGrant("The administrator is locked out"))
        session.open()
        mintStatus = HttpStatusCode.NotFound
        try {
            session.refresh()
            fail("expected the access to be over")
        } catch (denied: OrganizationAccessDenied) {
            assertEquals(OrganizationDenial.SupportEnded, denied.reason)
        }
    }

    @Test
    fun `a first request the server turns away is the same not found as ever`() = runTest {
        mintStatus = HttpStatusCode.NotFound
        try {
            session(SupportAccessGrant("The administrator is locked out")).open()
            fail("expected a refusal")
        } catch (denied: OrganizationAccessDenied) {
            assertEquals(OrganizationDenial.NotFound, denied.reason)
        }
    }

    @Test
    fun `an ordinary pass is not support access`() = runTest {
        mintBody = { F.session() }
        val session = session()
        val context = session.open()
        assertEquals(AccessBasis.Membership, context.accessBasis)
        assertNull(context.supportAccessId)
        assertFalse(session.isSupportAccess)
        assertFalse("support" in Json.parseToJsonElement(mints[0]).jsonObject.keys)
    }

    @Test
    fun `an Owner who is also a member gets the ordinary pass and no access to renew`() = runTest {
        mintBody = { F.session() }
        val grant = SupportAccessGrant("The administrator is locked out")
        val session = session(grant)
        val context = session.open()
        assertEquals(AccessBasis.Membership, context.accessBasis)
        assertNull(grant.accessId)
        assertFalse(session.isSupportAccess)
    }

    @Test
    fun `a support pass nobody asked for is refused`() = runTest {
        // An ordinary open that comes back as the Owner's support authority is not a pass this app asked for.
        try {
            session().open()
            fail("expected a refusal")
        } catch (denied: OrganizationAccessDenied) {
            assertEquals(OrganizationDenial.LostAuthority, denied.reason)
        }
    }

    // ---- Grants ---------------------------------------------------------------------------------------

    @Test
    fun `a grant is kept per server and organization and forgotten when it runs out`() {
        val grant = SupportAccessGrants.start("server-a", "org-1", "A good enough reason")
        assertTrue(SupportAccessGrants.current("server-a", "org-1", now) === grant)
        assertNull(SupportAccessGrants.current("server-b", "org-1", now))
        assertNull(SupportAccessGrants.current("server-a", "org-2", now))

        grant.expiresAt = now.plusSeconds(60)
        assertNotNull(SupportAccessGrants.current("server-a", "org-1", now))
        assertNull("past its end it is gone", SupportAccessGrants.current("server-a", "org-1", now.plusSeconds(61)))
        assertNull(SupportAccessGrants.current("server-a", "org-1", now))
    }

    @Test
    fun `a new opening replaces the earlier grant`() {
        val first = SupportAccessGrants.start("server-c", "org-1", "The first reason given")
        val second = SupportAccessGrants.start("server-c", "org-1", "The second reason given")
        assertTrue(SupportAccessGrants.current("server-c", "org-1", now) === second)
        SupportAccessGrants.forget(first)
        assertTrue(SupportAccessGrants.current("server-c", "org-1", now) === second)
        SupportAccessGrants.forget(second)
        assertNull(SupportAccessGrants.current("server-c", "org-1", now))
    }

    // ---- Ending the access ----------------------------------------------------------------------------

    @Test
    fun `ending the access calls delete support-access with the pass`() = runTest {
        val grant = SupportAccessGrants.start("server-d", "org-1", "The administrator is locked out")
        passHandler = { HttpStatusCode.NoContent to "" }
        val session = session(grant)
        session.open()
        session.endSupportAccess()

        val request = passRequests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/api/bloem/v2/admin/organization/support-access", request.url.encodedPath)
        assertEquals("Bearer pass.jwt.value", request.headers[HttpHeaders.Authorization])
        assertNull("the grant is forgotten", SupportAccessGrants.current("server-d", "org-1", now))
    }

    @Test
    fun `an access that already ended counts as ended`() = runTest {
        passHandler = { HttpStatusCode.NotFound to F.error("not_found") }
        val session = session(SupportAccessGrant("The administrator is locked out"))
        session.open()
        session.endSupportAccess()
        assertEquals(1, passRequests.size)
    }

    @Test
    fun `an access the server can no longer renew is already over`() = runTest {
        val grant = SupportAccessGrants.start("server-e", "org-1", "The administrator is locked out")
        passHandler = { HttpStatusCode.Unauthorized to F.error("authorization_state_stale") }
        val session = session(grant)
        session.open()
        mintStatus = HttpStatusCode.NotFound
        session.endSupportAccess()
        assertNull(SupportAccessGrants.current("server-e", "org-1", now))
    }

    @Test
    fun `a stale pass is renewed and the end is asked once more`() = runTest {
        var calls = 0
        passHandler = {
            calls += 1
            if (calls == 1) HttpStatusCode.Unauthorized to F.error("authorization_state_stale") else HttpStatusCode.NoContent to ""
        }
        val session = session(SupportAccessGrant("The administrator is locked out"))
        session.open()
        session.endSupportAccess()
        assertEquals(2, calls)
        assertEquals("the pass was renewed by its access id", 2, mints.size)
        assertEquals(setOf("access_id"), supportBody(1).keys)
    }

    // ---- Capabilities ---------------------------------------------------------------------------------

    @Test
    fun `nothing is offered until the server has said what it can do`() = runTest {
        mintBody = { F.session() }
        val session = session()
        session.open()
        assertEquals(SupportCapabilities.NONE, session.supportCapabilities.value)
        session.loadSupportCapabilities()
        assertTrue(session.supportCapabilities.value.canReset)
        assertTrue(session.supportCapabilities.value.streams.list)
    }

    @Test
    fun `what the server says false stays hidden`() = runTest {
        mintBody = { F.session() }
        passHandler = { HttpStatusCode.OK to F.supportCapabilities(email = false, devices = false, remove = false, list = false) }
        val session = session()
        session.open()
        session.loadSupportCapabilities()
        val caps = session.supportCapabilities.value
        assertFalse(caps.password_reset.email)
        assertTrue(caps.password_reset.link)
        assertFalse(caps.devices)
        assertFalse(caps.profiles.remove)
        assertFalse(caps.streams.list)
    }

    @Test
    fun `an older server with no such route shows nothing and is asked only once`() = runTest {
        mintBody = { F.session() }
        passHandler = { HttpStatusCode.NotFound to F.error("not_found") }
        val session = session()
        session.open()
        session.loadSupportCapabilities()
        session.loadSupportCapabilities()
        assertEquals(SupportCapabilities.NONE, session.supportCapabilities.value)
        assertEquals(1, passRequests.size)
    }

    @Test
    fun `a failed ask shows nothing and asks again next time`() = runTest {
        mintBody = { F.session() }
        var healthy = false
        passHandler = { if (healthy) HttpStatusCode.OK to F.supportCapabilities() else HttpStatusCode.ServiceUnavailable to F.error("tenant_unavailable") }
        val session = session()
        session.open()
        session.loadSupportCapabilities()
        assertEquals(SupportCapabilities.NONE, session.supportCapabilities.value)
        healthy = true
        session.loadSupportCapabilities()
        assertTrue(session.supportCapabilities.value.hasPersonSupport)
    }

    @Test
    fun `a refusal of the organization while asking is recorded`() = runTest {
        mintBody = { F.session() }
        passHandler = { HttpStatusCode.Forbidden to F.error("organization_suspended") }
        val session = session()
        session.open()
        session.loadSupportCapabilities()
        assertEquals(OrganizationDenial.Suspended, session.denial.value)
    }
}
