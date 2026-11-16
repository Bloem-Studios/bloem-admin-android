package com.bloemstudios.admin.organization

import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.getOrganizationOverview
import com.bloemstudios.admin.network.api.setOrganizationMembershipStatus
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrganizationSessionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var now = Instant.parse("2026-11-13T12:00:00Z")

    /** Everything the account client and the pass client were asked, in order. */
    private val accountRequests = mutableListOf<HttpRequestData>()
    private val passRequests = mutableListOf<HttpRequestData>()
    private val passes = ArrayDeque(listOf("pass-1", "pass-2", "pass-3"))
    private var policyRevisions = ArrayDeque(listOf(7L, 8L, 9L))
    private var mintStatus = HttpStatusCode.OK
    private var mintBody: (String, Long) -> String = { token, revision -> F.session(token = token, policyRevision = revision) }
    private var passHandler: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to F.overview() }

    private val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
        accountRequests += request
        if (mintStatus != HttpStatusCode.OK) respond(F.error("refused"), mintStatus, json)
        else respond(mintBody(passes.removeFirst(), policyRevisions.removeFirst()), HttpStatusCode.OK, json)
    })

    private fun session(organizationId: String = F.ORG_ID) = OrganizationSession(
        account = account, organizationId = organizationId, clock = { now },
        clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
            ApiClient(origin, snapshot, MockEngine { request ->
                passRequests += request
                val (status, body) = passHandler(request)
                respond(body, status, json)
            }, recovery)
        },
    )

    private fun bearer(request: HttpRequestData) = request.headers[HttpHeaders.Authorization]

    @Test
    fun `opening mints an organization pass with the account token and serves requests with the pass`() = runTest {
        val session = session()
        val context = session.open()

        assertEquals("Bearer account-token", bearer(accountRequests.single()))
        assertEquals("organization:${F.ORG_ID}", context.key)
        assertEquals(AdminAuthority.OrganizationAdmin, context.authority)
        assertEquals(7L, session.policyRevision)

        session.client.getOrganizationOverview()
        assertEquals("Bearer pass-1", bearer(passRequests.single()))
        assertTrue("the account token never reaches an organization route", passRequests.none { bearer(it) == "Bearer account-token" })
    }

    @Test
    fun `a pass for another organization or scope is refused`() = runTest {
        mintBody = { token, revision -> F.session(token = token, organizationId = "someone-else", policyRevision = revision) }
        try {
            session().open()
            fail("expected a refusal")
        } catch (_: OrganizationAccessDenied) {
        }
    }

    @Test
    fun `a forbidden or hidden organization is access denied but a 401 is not`() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
            mintStatus = status
            try {
                session().open()
                fail("expected a refusal for $status")
            } catch (_: OrganizationAccessDenied) {
            }
        }
        mintStatus = HttpStatusCode.Unauthorized
        try {
            session().open()
            fail("expected an ApiError")
        } catch (error: ApiError) {
            assertEquals(401, error.statusCode)
        }
    }

    @Test
    fun `a read refused as stale re-mints the pass and retries once with it`() = runTest {
        passHandler = { request ->
            if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to F.error("authorization_state_stale")
            else HttpStatusCode.OK to F.overview(policyRevision = 8)
        }
        val session = session()
        session.open()

        val overview = session.client.getOrganizationOverview()

        assertEquals(8L, overview.policy_revision)
        assertEquals(listOf("Bearer pass-1", "Bearer pass-2"), passRequests.map(::bearer))
        assertEquals(2, accountRequests.size)
        assertEquals(8L, session.policyRevision)
    }

    @Test
    fun `parallel stale reads share one re-mint`() = runTest {
        passHandler = { request ->
            if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to F.error("tenant_session_required")
            else HttpStatusCode.OK to F.overview()
        }
        val session = session()
        session.open()

        (1..3).map { async { session.client.getOrganizationOverview() } }.awaitAll()

        assertEquals("one mint to open and one to recover", 2, accountRequests.size)
    }

    @Test
    fun `losing the organization while re-minting surfaces access denied`() = runTest {
        passHandler = { HttpStatusCode.Unauthorized to F.error("authorization_state_stale") }
        val session = session()
        session.open()
        mintStatus = HttpStatusCode.Forbidden

        try {
            session.client.getOrganizationOverview()
            fail("expected access denied")
        } catch (_: OrganizationAccessDenied) {
        }
    }

    @Test
    fun `a stale write is not replayed, the pass is refreshed and the caller must reload`() = runTest {
        passHandler = { request ->
            if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to F.error("authorization_state_stale")
            else HttpStatusCode.OK to F.personResponse()
        }
        val session = session()
        session.open()

        try {
            session.write { client, _ -> client.setOrganizationMembershipStatus(5, "suspended", 4) }
            fail("expected OrganizationStateChanged")
        } catch (_: OrganizationStateChanged) {
        }

        assertEquals("the write went out once", 1, passRequests.size)
        assertEquals(8L, session.policyRevision)
        session.write { client, _ -> client.setOrganizationMembershipStatus(5, "suspended", 4) }
        assertEquals("Bearer pass-2", bearer(passRequests.last()))
    }

    @Test
    fun `a revision conflict refreshes the pass and asks for a reload`() = runTest {
        passHandler = { HttpStatusCode.Conflict to """{"error":"authorization_state_changed","message":"x","current_revision":9}""" }
        val session = session()
        session.open()

        try {
            session.write { client, _ -> client.setOrganizationMembershipStatus(5, "active", 4) }
            fail("expected OrganizationStateChanged")
        } catch (_: OrganizationStateChanged) {
        }
        assertEquals(2, accountRequests.size)
    }

    @Test
    fun `other write failures pass through untouched`() = runTest {
        passHandler = { HttpStatusCode.UnprocessableEntity to F.error("validation_failed", "Owner can't be suspended") }
        val session = session()
        session.open()

        try {
            session.write { client, _ -> client.setOrganizationMembershipStatus(5, "suspended", 4) }
            fail("expected an ApiError")
        } catch (error: ApiError) {
            assertEquals(422, error.statusCode)
        }
        assertEquals("no needless re-mint", 1, accountRequests.size)
    }

    @Test
    fun `a pass about to expire is refreshed before a write`() = runTest {
        passHandler = { HttpStatusCode.OK to F.personResponse() }
        val session = session()
        session.open()
        now = Instant.parse("2026-11-13T12:14:30Z")

        session.write { client, _ -> client.setOrganizationMembershipStatus(5, "suspended", 4) }

        assertEquals(2, accountRequests.size)
        assertEquals("Bearer pass-2", bearer(passRequests.single()))
    }

    @Test
    fun `a fresh pass is not re-minted before a write`() = runTest {
        passHandler = { HttpStatusCode.OK to F.personResponse() }
        val session = session()
        session.open()
        now = Instant.parse("2026-11-13T12:05:00Z")

        session.write { client, revision -> assertEquals(7L, revision); client.setOrganizationMembershipStatus(5, "suspended", 4) }

        assertEquals(1, accountRequests.size)
    }

    @Test
    fun `the context is published and cleared on close`() = runTest {
        val session = session()
        assertEquals(null, session.context.value)
        session.open()
        assertNotNull(session.context.value)
        assertFalse(session.toString().contains("pass-1"))
        session.close()
    }
}
