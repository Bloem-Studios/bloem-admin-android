package com.bloemstudios.admin.organization

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The organization host with the support routes: the menu offers Now Playing only where the server says it can list streams,
 * and the Owner's support access shows its banner and ends. The server is a MockEngine; nothing touches a network.
 */
@RunWith(AndroidJUnit4::class)
class OrganizationSupportHostTest {
    @get:Rule val compose = createComposeRule()

    private val orgId = "10000000-0000-0000-0000-000000000001"
    private val accessId = "40000000-0000-0000-0000-000000000001"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val overview = """{"organization":{"id":"$orgId","slug":"acme","name":"Acme Media","status":"active","owner_account_id":4,""" +
        """"policy_revision":7,"is_default":false,"membership_count":12,"active_membership_count":10,"profile_count":25,""" +
        """"library_count":3,"entitlement_count":2}}"""
    private val capabilities = """{"password_reset":{"email":true,"link":true},"sessions":true,"devices":true,""" +
        """"profiles":{"add":true,"rename":true,"clear_pin":true,"remove":true},"streams":{"list":true,"stop":true,"message":true}}"""
    private val stream = """{"session_id":"st1","account_id":5,"person_name":"Sam","profile_id":"p1","profile_name":"Living room","title":"Pilot",""" +
        """"series_name":"Show","season_number":1,"episode_number":2,"media_type":"episode","started_at":"2026-11-14T08:00:00Z",""" +
        """"updated_at":"2026-11-14T08:30:00Z","position_seconds":1800.5,"duration_seconds":3600,"is_paused":false,"play_method":"transcode",""" +
        """"video_decision":"transcode","audio_decision":"direct","device":{"name":"Sam's TV","platform":"tvos","id":"d1","app":"Bloem tvOS"},""" +
        """"can_stop":true,"can_message":true}"""

    private val requests = CopyOnWriteArrayList<Pair<HttpMethod, String>>()

    private fun pass(support: Boolean) = if (support) {
        """{"access_token":"pass","expires_at":"2099-01-01T00:00:00Z","context":{"key":"organization:$orgId","scope":"organization",""" +
            """"organization_id":"$orgId","name":"Acme Media","status":"active","authority":"platform_admin","policy_revision":7,""" +
            """"access_basis":"support","support_access_id":"$accessId","support_expires_at":"2026-11-14T14:00:00Z"}}"""
    } else {
        """{"access_token":"pass","expires_at":"2099-01-01T00:00:00Z","context":{"key":"organization:$orgId","scope":"organization",""" +
            """"organization_id":"$orgId","membership_id":"m1","name":"Acme Media","status":"active","authority":"organization_admin",""" +
            """"policy_revision":7,"security_revision":11}}"""
    }

    private fun session(support: Boolean = false, route: (HttpMethod, String) -> Pair<HttpStatusCode, String>): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
            respond(pass(support), HttpStatusCode.OK, json)
        })
        return OrganizationSession(
            account, orgId,
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    requests += request.method to request.url.encodedPath
                    val (status, body) = route(request.method, request.url.encodedPath)
                    respond(body, status, json)
                }, recovery)
            },
            support = if (support) SupportAccessGrant("The administrator is locked out") else null,
        )
    }

    private fun serverWith(support: Boolean): (HttpMethod, String) -> Pair<HttpStatusCode, String> = { method, path ->
        when {
            path.endsWith("/overview") -> HttpStatusCode.OK to overview
            path.endsWith("/support-capabilities") ->
                if (support) HttpStatusCode.OK to capabilities else HttpStatusCode.NotFound to """{"error":"not_found","message":"Administrative resource not found"}"""
            path.endsWith("/streams") -> HttpStatusCode.OK to """{"streams":[$stream]}"""
            path.endsWith("/support-access") && method == HttpMethod.Delete -> HttpStatusCode.NoContent to ""
            else -> HttpStatusCode.NotFound to """{"error":"not_found","message":"Administrative resource not found"}"""
        }
    }

    private fun host(session: OrganizationSession, now: Instant = Instant.parse("2026-11-14T10:45:00Z"), onChoose: () -> Unit = {}) = compose.setContent {
        CompositionLocalProvider(LocalReducedMotionOverride provides true, LocalSupportClock provides { now }) {
            VeilAdminTheme {
                OrganizationAdminHost(session, useNavigationRail = true, onChooseContext = onChoose, onSignInRequired = {})
            }
        }
    }

    private fun count(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    @Test
    fun aServerThatOffersStreamsAddsNowPlayingToTheMenuAndShowsThem() {
        host(session(route = serverWith(support = true)))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shell-destination:organization.streams") }
        compose.onNodeWithTag("shell-destination:organization.streams", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Now playing").performClick()
        compose.waitUntil(5_000) { count("Show · S1E2 · Pilot") > 0 }
        compose.onNodeWithTag("stream-row:st1").assertExists()
        assertTrue("the streams were asked for with the pass", requests.any { it.second.endsWith("/streams") })
    }

    @Test
    fun anOlderServerAddsNothingNew() {
        host(session(route = serverWith(support = false)))
        compose.waitUntil(5_000) { count("About") > 0 }
        compose.waitForIdle()
        compose.onNodeWithTag("shell-destination:organization.streams", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, count("Now playing", substring = false))
        assertTrue(requests.any { it.second.endsWith("/support-capabilities") })
        assertTrue("no support route beyond the capabilities probe was called", requests.none { it.second.endsWith("/streams") })
    }

    @Test
    fun anOrdinaryAdministratorSeesNoSupportBanner() {
        host(session(route = serverWith(support = true)))
        compose.waitUntil(5_000) { count("About") > 0 }
        compose.onNodeWithTag("organization-support-banner").assertDoesNotExist()
    }

    @Test
    fun theOwnersSupportAccessShowsItsBannerOnEveryPageAndEndsOnlyAfterAsking() {
        var chose = 0
        host(session(support = true, route = serverWith(support = true)), onChoose = { chose++ })
        compose.waitUntil(5_000) { count("Platform support access") > 0 }
        compose.onNodeWithTag("organization-support-banner").assertIsDisplayed()
        compose.onNodeWithText("3 h 15 min left").assertExists()
        // The banner stays when the page changes.
        // A page that is read once (Now Playing polls, and two repeating loops keep a test from ever going idle).
        compose.onNodeWithText("Activity").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("organization-page") }
        compose.onNodeWithTag("organization-support-banner").assertExists()

        compose.onNodeWithTag("organization-support-end").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue("nothing was ended by the tap alone", requests.none { it.first == HttpMethod.Delete })
        compose.onNode(hasTestTag("organization-support-end-confirm") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { chose > 0 }
        assertTrue(requests.any { it.first == HttpMethod.Delete && it.second.endsWith("/organization/support-access") })
        assertEquals(1, chose)
    }

    @Test
    fun anEndThatFailsStaysOnTheBannerAndCanBeTriedAgain() {
        var chose = 0
        val failing: (HttpMethod, String) -> Pair<HttpStatusCode, String> = { method, path ->
            if (method == HttpMethod.Delete) HttpStatusCode.ServiceUnavailable to """{"error":"tenant_unavailable","message":"Tenant administration is unavailable"}"""
            else serverWith(true)(method, path)
        }
        host(session(support = true, route = failing), onChoose = { chose++ })
        compose.waitUntil(5_000) { count("Platform support access") > 0 }
        compose.onNodeWithTag("organization-support-end").performClick()
        compose.onNode(hasTestTag("organization-support-end-confirm") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("organization-support-end-error") }
        assertEquals(0, chose)
        compose.onNodeWithTag("organization-support-end").assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String): Boolean =
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
