package com.bloemstudios.admin.organization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The organization host on a device: opening, refusal, sign-in loss and what the shell offers. The
 * server is a MockEngine answering exactly what the real handlers write; nothing here touches a network.
 */
@RunWith(AndroidJUnit4::class)
class OrganizationHostTest {
    @get:Rule val compose = createComposeRule()

    private val orgId = "10000000-0000-0000-0000-000000000001"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val overview = """{"organization":{"id":"$orgId","slug":"acme","name":"Acme Media","status":"active","owner_account_id":4,""" +
        """"policy_revision":7,"is_default":false,"membership_count":12,"active_membership_count":10,"profile_count":25,""" +
        """"library_count":3,"entitlement_count":2}}"""

    private fun session(mint: HttpStatusCode = HttpStatusCode.OK, route: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to overview }): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine {
            if (mint != HttpStatusCode.OK) respond("""{"error":"refused","message":"Refused"}""", mint, json)
            else respond(
                """{"access_token":"pass","expires_at":"2099-01-01T00:00:00Z","context":{"key":"organization:$orgId","scope":"organization",""" +
                    """"organization_id":"$orgId","membership_id":"m1","name":"Acme Media","status":"active","authority":"organization_admin",""" +
                    """"policy_revision":7,"security_revision":11}}""",
                HttpStatusCode.OK, json,
            )
        })
        return OrganizationSession(
            account, orgId,
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    val (status, body) = route(request.url.encodedPath)
                    respond(body, status, json)
                }, recovery)
            },
        )
    }

    private fun host(
        session: OrganizationSession,
        rail: Boolean = true,
        onChoose: () -> Unit = {},
        onSignIn: () -> Unit = {},
        onUnavailable: (String) -> Unit = {},
    ) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotionOverride provides true) {
            VeilAdminTheme {
                OrganizationAdminHost(
                    session, useNavigationRail = rail, onChooseContext = onChoose, onSignInRequired = onSignIn,
                    onContextUnavailable = onUnavailable,
                )
            }
        }
    }

    @Test
    fun anOrganizationAdministratorLandsOnTheOrganizationOverview() {
        host(session())
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("About") > 0 }
        compose.onNodeWithText("About").assertIsDisplayed()
        // The organization's own name stands where the server name goes.
        compose.onNodeWithText("Acme Media", substring = true).assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun theMenuIsTheWebsOrganizationMenuAndNothingPlatform() {
        host(session())
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("About") > 0 }
        // The menu lists the web's organization pages, each by its own drawer item.
        listOf("overview", "people", "cohorts", "groups", "libraries", "invitations", "decisions", "activity")
            .forEach { compose.onNodeWithTag("shell-destination:organization.$it", useUnmergedTree = true).assertExists() }
        listOf("People", "Cohorts", "Access groups", "Libraries", "Invitations", "Access checks", "Activity")
            .forEach { assertEquals(it, 1, compose.onAllNodesWithTextCount(it, exact = true)) }
        listOf("Dashboard", "Playback history", "Scheduled tasks", "Requests", "Users", "Organizations", "Settings", "Audit", "Logs").forEach {
            assertEquals("platform page '$it' must not be offered", 0, compose.onAllNodesWithTextCount(it, exact = true))
        }
    }

    @Test
    fun movingBetweenPagesLoadsThemWithThePass() {
        host(session(route = { path ->
            when {
                path.endsWith("/overview") -> HttpStatusCode.OK to overview
                path.endsWith("/groups") -> HttpStatusCode.OK to """{"groups":[]}"""
                else -> HttpStatusCode.NotFound to """{"error":"not_found","message":"Administrative resource not found"}"""
            }
        }))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("About") > 0 }
        compose.onNodeWithText("Access groups").performClick()
        // The page loaded with the pass. On CI's 320dp-wide screen the permanent drawer this host is
        // forced into (rail = true) leaves the page too narrow to be "displayed", so assert presence.
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("No access groups yet") > 0 }
        compose.onNodeWithText("No access groups yet").assertExists()
    }

    @Test
    fun aRefusedOrganizationSaysSoAndOffersContextChoice() {
        var chose = 0
        host(session(mint = HttpStatusCode.Forbidden), onChoose = { chose++ })
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("You no longer administer this organization") > 0 }
        compose.onNodeWithText("You no longer administer this organization").assertIsDisplayed()
        compose.onNodeWithText("Choose another organization").performClick()
        assertEquals(1, chose)
    }

    @Test
    fun anOrganizationTheServerHidesIsNotShownToTheAccount() {
        host(session(mint = HttpStatusCode.NotFound))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("You don't have access to this organization") > 0 }
        compose.onNodeWithTag("organization-denied").assertIsDisplayed()
    }

    @Test
    fun anOrganizationRouteRefusingMidSessionLeavesTheOrganization() {
        host(session(route = { HttpStatusCode.Forbidden to """{"error":"insufficient_organization_authority","message":"Organization administrator authority required"}""" }))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("You no longer administer this organization") > 0 }
        compose.onNodeWithTag("organization-denied").assertIsDisplayed()
    }

    @Test
    fun aSuspendedOrganizationSaysSoAndIsReportedOnceSoTheContextListDropsIt() {
        val unavailable = mutableListOf<String>()
        host(
            session(route = { HttpStatusCode.Forbidden to """{"error":"organization_suspended","message":"Organization is suspended"}""" }),
            onUnavailable = { unavailable += it },
        )
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("This organization is suspended.") > 0 }
        compose.onNodeWithText("This organization is suspended. Contact the server owner.").assertIsDisplayed()
        compose.onNodeWithText("Choose another organization").assertIsDisplayed()
        compose.waitUntil(5_000) { unavailable.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(orgId), unavailable)
    }

    @Test
    fun anExpiredSignInGoesToTheGateForReLogin() {
        var signIn = 0
        host(session(mint = HttpStatusCode.Unauthorized), onSignIn = { signIn++ })
        compose.waitUntil(5_000) { signIn > 0 }
        assertTrue(signIn >= 1)
    }

    @Test
    fun oneOrganizationOpensDirectlyAndSeveralAskWhich() {
        val opened = mutableListOf<String>()
        val acme = AdminContext("organization:a", "Acme Media", AdminContextScope.Organization, "a", "active", AdminAuthority.OrganizationAdmin)
        val beta = acme.copy(key = "organization:b", name = "Beta TV", organizationId = "b")
        compose.setContent { VeilAdminTheme { OrganizationAdmission("Main server", listOf(acme)) { opened += it.organizationId!! } } }
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun severalOrganizationsShowAChoice() {
        val opened = mutableListOf<String>()
        val acme = AdminContext("organization:a", "Acme Media", AdminContextScope.Organization, "a", "active", AdminAuthority.OrganizationAdmin)
        val beta = acme.copy(key = "organization:b", name = "Beta TV", organizationId = "b")
        compose.setContent { VeilAdminTheme { OrganizationAdmission("Main server", listOf(acme, beta)) { opened += it.organizationId!! } } }
        compose.onNodeWithText("Choose an organization").assertIsDisplayed()
        assertTrue("nothing opens until one is picked", opened.isEmpty())
        compose.onNodeWithText("Beta TV").performClick()
        assertEquals(listOf("b"), opened)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, exact: Boolean = false): Int =
    onAllNodesWithText(text, substring = !exact).fetchSemanticsNodes().size
