package com.bloemstudios.admin.platform

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.getPlatformOrganization
import com.bloemstudios.admin.network.api.suspendPlatformOrganization
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlatformSessionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var now = Instant.parse("2026-11-14T12:00:00Z")

    private val accountRequests = mutableListOf<HttpRequestData>()
    private val passRequests = mutableListOf<HttpRequestData>()
    private val passes = ArrayDeque(listOf("pass-1", "pass-2", "pass-3"))
    private var mintStatus = HttpStatusCode.OK
    private var mintScope = "platform"
    private var passHandler: (HttpRequestData) -> Pair<HttpStatusCode, String> = {
        HttpStatusCode.OK to PlatformFixtures.organization()
    }

    private val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
        accountRequests += request
        if (mintStatus != HttpStatusCode.OK) respond(PlatformFixtures.error("forbidden"), mintStatus, json)
        else respond(PlatformFixtures.platformSession(token = passes.removeFirst(), scope = mintScope), HttpStatusCode.OK, json)
    })

    private fun session() = PlatformSession(
        account = account, clock = { now },
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
    fun `the first read mints a platform pass with the account token and uses only the pass on platform routes`() = runTest {
        val session = session()

        session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }

        val mint = accountRequests.single()
        assertEquals("/api/bloem/v2/admin/session", mint.url.encodedPath)
        assertEquals("Bearer account-token", bearer(mint))
        assertTrue("the mint asks for the platform scope", String(mint.body.toByteArray()).contains("\"scope\":\"platform\""))
        assertEquals("Bearer pass-1", bearer(passRequests.single()))
        assertTrue("the account token never reaches a platform route", passRequests.none { bearer(it) == "Bearer account-token" })
    }

    @Test
    fun `the pass is kept for the next read`() = runTest {
        val session = session()
        session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }
        session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }

        assertEquals(1, accountRequests.size)
        assertEquals(listOf("Bearer pass-1", "Bearer pass-1"), passRequests.map(::bearer))
    }

    @Test
    fun `reads started together share one mint`() = runTest {
        val session = session()
        listOf(
            async { session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) } },
            async { session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) } },
        ).awaitAll()

        assertEquals(1, accountRequests.size)
    }

    @Test
    fun `a pass for another scope is refused`() = runTest {
        mintScope = "organization"
        try {
            session().read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }
            fail("expected a refusal")
        } catch (error: ApiError) {
            assertEquals(403, error.statusCode)
        }
        assertTrue("nothing was sent with that pass", passRequests.isEmpty())
    }

    @Test
    fun `a refused mint reaches the caller as the server's answer`() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            mintStatus = status
            try {
                session().read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }
                fail("expected the refusal for $status")
            } catch (error: ApiError) {
                assertEquals(status.value, error.statusCode)
            }
        }
    }

    @Test
    fun `a read refused as stale re-mints the pass and retries once with it`() = runTest {
        for (code in listOf("authorization_state_stale", "tenant_session_required")) {
            accountRequests.clear(); passRequests.clear(); passes.clear(); passes.addAll(listOf("pass-1", "pass-2"))
            passHandler = { request ->
                if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to PlatformFixtures.error(code)
                else HttpStatusCode.OK to PlatformFixtures.organization()
            }
            val session = session()

            val organization = session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }

            assertEquals("Acme Media", organization.name)
            assertEquals("for $code", listOf("Bearer pass-1", "Bearer pass-2"), passRequests.map(::bearer))
            assertEquals("for $code", 2, accountRequests.size)
        }
    }

    @Test
    fun `a write refused as stale renews the pass, is not replayed, and tells the caller the state changed`() = runTest {
        passHandler = { request ->
            if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to PlatformFixtures.error("authorization_state_stale")
            else HttpStatusCode.OK to PlatformFixtures.lifecycleAnswer("suspended")
        }
        val session = session()
        session.read { /* opens the pass */ }

        try {
            session.write { it.suspendPlatformOrganization(PlatformFixtures.ORG_ID, 7) }
            fail("expected the state change")
        } catch (changed: PlatformStateChanged) {
            assertEquals(false, changed.wasConflict)
        }

        assertEquals("the write was sent once", 1, passRequests.count { it.url.encodedPath.endsWith("/suspend") })
        assertEquals("the pass was renewed for the next call", 2, accountRequests.size)
    }

    @Test
    fun `a revision conflict renews the pass and tells the caller it was a conflict`() = runTest {
        passHandler = { HttpStatusCode.Conflict to """{"error":"authorization_state_changed","message":"Authorization state changed; reload and retry","current_revision":9}""" }
        val session = session()

        try {
            session.write { it.suspendPlatformOrganization(PlatformFixtures.ORG_ID, 7) }
            fail("expected the conflict")
        } catch (changed: PlatformStateChanged) {
            assertEquals(true, changed.wasConflict)
        }
        assertEquals(1, passRequests.size)
    }

    @Test
    fun `another refusal of a write is the server's own answer`() = runTest {
        passHandler = { HttpStatusCode.NotFound to PlatformFixtures.error("not_found", "Administrative resource not found") }
        try {
            session().write { it.suspendPlatformOrganization(PlatformFixtures.ORG_ID, 7) }
            fail("expected the refusal")
        } catch (error: ApiError) {
            assertEquals(404, error.statusCode)
        }
    }

    @Test
    fun `a pass close to its end is renewed before a write`() = runTest {
        passHandler = { HttpStatusCode.OK to PlatformFixtures.lifecycleAnswer("suspended") }
        val session = session()
        session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }
        now = Instant.parse("2026-11-14T12:14:30Z")

        session.write { it.suspendPlatformOrganization(PlatformFixtures.ORG_ID, 7) }

        assertEquals(2, accountRequests.size)
        assertEquals("Bearer pass-2", bearer(passRequests.last()))
    }

    @Test
    fun `closing drops the pass and a later read mints a new one`() = runTest {
        val session = session()
        session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }
        session.close()
        session.read { it.getPlatformOrganization(PlatformFixtures.ORG_ID) }

        assertEquals(2, accountRequests.size)
    }
}
