package com.bloemstudios.admin.servers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReLoginFormTest {
    @get:Rule
    val compose = createComposeRule()

    private val identity = """{"server_id":"3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35"}"""
    private val providers = """{"password_login":true,"items":[
        {"id":"27","display_name":"Example Portal","mode":"oauth","default":true,"installation_id":"27","native_start_path":"/api/v2/auth/oauth/27/native/start"},
        {"id":"26","display_name":"Corp SSO","mode":"oauth","default":false,"installation_id":"26","native_start_path":"/api/v2/auth/oauth/26/native/start"}]}"""
    private val tokens = """{"access_token":"new-a","refresh_token":"new-r","expires_in":3600,"user":{"id":"u1","username":"admin","email":"a@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}}"""
    private val upgrade = """{"type":"https://siloserver.org/docs/api/v2/problems/client_upgrade_required","title":"Update","status":410,"detail":"Update the app.","instance":"urn:silo:request:t1"}"""

    private val paths = mutableListOf<String>()

    private fun connector(providersResponse: Pair<String, HttpStatusCode> = providers to HttpStatusCode.OK,
                          loginResponse: Pair<String, HttpStatusCode> = tokens to HttpStatusCode.OK): AddServerConnector {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val (body, status) = when (path) {
                "/api/v2/system/identity" -> identity to HttpStatusCode.OK
                "/api/v2/auth/providers" -> providersResponse
                "/api/v2/auth/login" -> loginResponse
                else -> error("unexpected $path")
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AddServerConnector(clientFactory = { origin, token -> ApiClient(origin, token, engine) })
    }

    private class Login { val calls = mutableListOf<Triple<String, String, String?>>() }

    private fun show(connector: AddServerConnector, login: Login, server: AdminServer) = compose.setContent {
        VeilAdminTheme {
            ReLoginForm(server = server, reason = null, connector = connector,
                onLoggedIn = { a, r, p -> login.calls += Triple(a, r, p) })
        }
    }

    @Test
    fun showsTheRememberedProviderFirstWithoutChangeServer() {
        show(connector(), Login(), AdminServer("s1", "https://example.test", "Bloem West", "26"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Sign in with Corp SSO") > 0 }
        val ys = listOf("Sign in with Corp SSO", "Sign in with Example Portal").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("remembered provider is listed first", ys[0] < ys[1])
        compose.onNodeWithText("Change server").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun upgradeRequiredShowsTheUpgradeCopy() {
        show(connector(providersResponse = upgrade to HttpStatusCode.Gone), Login(), AdminServer("s1", "https://example.test", "Bloem West"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("This server needs a newer version of Bloem Admin") > 0 }
        compose.onNodeWithText("Username").assertDoesNotExist()
    }

    @Test
    fun aFailingProvidersCallStillAllowsPasswordSignInAndClearsTheProvider() {
        val login = Login()
        show(connector(providersResponse = """{"message":"boom"}""" to HttpStatusCode.InternalServerError), login,
            AdminServer("s1", "https://example.test", "Bloem West", "26"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Username") > 0 }
        compose.onNodeWithText("Sign in with Corp SSO").assertDoesNotExist()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("admin")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("secret")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { login.calls.isNotEmpty() }
        assertEquals(listOf(Triple("new-a", "new-r", null as String?)), login.calls)
        // No admin probe: only discovery and the login itself.
        assertEquals(listOf("/api/v2/system/identity", "/api/v2/auth/providers", "/api/v2/auth/login"), paths)
    }

    @Test
    fun aRejectedPasswordShowsTheMappedCopyAndStoresNothing() {
        val login = Login()
        show(connector(loginResponse = """{"message":"no"}""" to HttpStatusCode.Unauthorized), login,
            AdminServer("s1", "https://example.test", "Bloem West"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Username") > 0 }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("admin")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("bad")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("The username or password wasn't accepted. Check them and try again") > 0 }
        assertEquals(0, login.calls.size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
