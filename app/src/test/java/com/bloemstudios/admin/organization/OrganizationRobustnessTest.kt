package com.bloemstudios.admin.organization

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.deleteOrganizationEntitlement
import com.bloemstudios.admin.network.api.deleteOrganizationGroup
import com.bloemstudios.admin.network.api.getOrganizationOverview
import com.bloemstudios.admin.network.api.startOrganizationBulkJob
import com.bloemstudios.admin.network.api.updateOrganizationGroup
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What an administrator sees when the server takes an organization away (suspended, authority lost, hidden), and
 * the promise that renewing the pass never sends a write a second time.
 */
class OrganizationRobustnessTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val accountRequests = mutableListOf<HttpRequestData>()
    private val passRequests = mutableListOf<HttpRequestData>()
    private var mints = 0
    private var mintAnswer: (Int) -> Pair<HttpStatusCode, String> = { n -> HttpStatusCode.OK to F.session(token = "pass-$n", policyRevision = 6L + n) }
    private var passAnswer: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to F.overview() }

    private val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
        accountRequests += request
        mints += 1
        val (status, body) = mintAnswer(mints)
        respond(body, status, json)
    })

    private fun session() = OrganizationSession(
        account, F.ORG_ID, clock = { Instant.parse("2026-11-13T12:00:00Z") },
        clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
            ApiClient(origin, snapshot, MockEngine { request ->
                passRequests += request
                val (status, body) = passAnswer(request)
                respond(body, status, json)
            }, recovery)
        },
    )

    private fun bearer(request: HttpRequestData) = request.headers[HttpHeaders.Authorization]
    private fun res(text: ErrorText?) = (text as? ErrorText.Res)?.id

    // ---- Why the server said no -----------------------------------------------------------------------

    @Test
    fun `a suspended organization is told apart from lost authority when the pass is minted`() = runTest {
        mintAnswer = { HttpStatusCode.Forbidden to F.error("organization_suspended", "Organization is suspended") }
        val denied = try { session().open(); null } catch (error: OrganizationAccessDenied) { error }
        assertEquals(OrganizationDenial.Suspended, denied?.reason)

        mintAnswer = { HttpStatusCode.Forbidden to F.error("insufficient_organization_authority") }
        assertEquals(OrganizationDenial.LostAuthority, try { session().open(); null } catch (error: OrganizationAccessDenied) { error.reason })

        mintAnswer = { HttpStatusCode.NotFound to F.error("not_found") }
        assertEquals(OrganizationDenial.NotFound, try { session().open(); null } catch (error: OrganizationAccessDenied) { error.reason })
    }

    @Test
    fun `a 403 on a route marks the session denied with the reason and the matching text`() = runTest {
        val session = session()
        session.open()
        assertNull(session.denial.value)
        passAnswer = { HttpStatusCode.Forbidden to F.error("organization_suspended", "Organization is suspended") }

        val vm = OverviewViewModel()
        vm.load(session)

        assertEquals(OrganizationDenial.Suspended, session.denial.value)
        assertTrue(session.accessDenied.value)
        assertEquals(R.string.organization_suspended, res(vm.state.value.error))
    }

    @Test
    fun `lost authority reads differently from suspended`() = runTest {
        val session = session()
        session.open()
        passAnswer = { HttpStatusCode.Forbidden to F.error("insufficient_organization_authority") }

        val vm = OverviewViewModel()
        vm.load(session)

        assertEquals(OrganizationDenial.LostAuthority, session.denial.value)
        assertEquals(R.string.organization_lost_authority, res(vm.state.value.error))
    }

    @Test
    fun `the first reason sticks`() = runTest {
        val session = session()
        session.open()
        session.markDenied(OrganizationDenial.Suspended)
        session.markDenied(OrganizationDenial.LostAuthority)
        assertEquals(OrganizationDenial.Suspended, session.denial.value)
    }

    @Test
    fun `a pass the server refuses to renew mid-session marks the session denied before the read fails`() = runTest {
        val session = session()
        session.open()
        passAnswer = { HttpStatusCode.Unauthorized to F.error("authorization_state_stale") }
        mintAnswer = { HttpStatusCode.Forbidden to F.error("organization_suspended", "Organization is suspended") }

        try {
            session.client.getOrganizationOverview()
            fail("expected the read to be refused")
        } catch (denied: OrganizationAccessDenied) {
            assertEquals(OrganizationDenial.Suspended, denied.reason)
        }
        assertEquals(OrganizationDenial.Suspended, session.denial.value)
    }

    @Test
    fun `a 401 on the account token is not a denial`() = runTest {
        mintAnswer = { HttpStatusCode.Unauthorized to F.error("unauthorized") }
        try {
            session().open()
            fail("expected a sign-in failure")
        } catch (error: ApiError) {
            assertEquals(401, error.statusCode)
        }
    }

    // ---- A renewal never replays a write ----------------------------------------------------------------

    private suspend fun assertWriteSentOnce(call: suspend (OrganizationSession) -> Unit) {
        passAnswer = { request ->
            if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to F.error("authorization_state_stale")
            else HttpStatusCode.OK to "{}"
        }
        val session = session()
        session.open()
        try {
            call(session)
            fail("expected OrganizationStateChanged")
        } catch (_: OrganizationStateChanged) {
        }
        val writes = passRequests.filter { it.method != HttpMethod.Get }
        assertEquals("the write is sent once, never replayed with the renewed pass", 1, writes.size)
        assertEquals("the pass was renewed for the next action", "Bearer pass-1", bearer(writes.single()))
        assertEquals(2, accountRequests.size)
        assertEquals(8L, session.policyRevision)
    }

    @Test
    fun `a stale bulk job start is not replayed`() = runTest {
        assertWriteSentOnce { it.write { client, _ -> client.startOrganizationBulkJob("sel", BulkKind.SUSPEND) } }
    }

    @Test
    fun `a stale group save is not replayed`() = runTest {
        val body = AccessGroupDraft.of(AccessGroup(id = "3", name = "Family")).toBody(7)
        assertWriteSentOnce { it.write { client, _ -> client.updateOrganizationGroup(3, body) } }
    }

    @Test
    fun `a stale group delete is not replayed`() = runTest {
        assertWriteSentOnce { it.write { client, revision -> client.deleteOrganizationGroup(3, revision) } }
    }

    @Test
    fun `a stale give-back is not replayed`() = runTest {
        assertWriteSentOnce { it.write { client, _ -> client.deleteOrganizationEntitlement(2, 9) } }
    }

    @Test
    fun `a read refused as stale still renews once and retries`() = runTest {
        passAnswer = { request ->
            if (bearer(request) == "Bearer pass-1") HttpStatusCode.Unauthorized to F.error("tenant_session_required")
            else HttpStatusCode.OK to F.overview(policyRevision = 8)
        }
        val session = session()
        session.open()
        assertEquals(8L, session.client.getOrganizationOverview().policy_revision)
        assertEquals(listOf("Bearer pass-1", "Bearer pass-2"), passRequests.map(::bearer))
        assertFalse(session.accessDenied.value)
    }
}
