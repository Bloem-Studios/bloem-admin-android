package com.bloemstudios.admin.servers

import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.auth.OAuthRedirects
import com.bloemstudios.admin.lock.AppLockScreen
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * With "Require device unlock" on, opening the browser stops the activity and the lock removes the
 * sign-in form. The sign-in must still complete when the redirect arrives at resume. The emulator has no
 * enrolled credential, so the lock's own bypass unlocks it again; what matters is that the form left
 * composition (asserted) and the sign-in survived.
 */
@RunWith(AndroidJUnit4::class)
class ProviderSignInLockTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val serverId = "3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35"
    private val identity = """{"server_id":"$serverId"}"""
    private val providers = """{"password_login":true,"items":[
        {"id":"26","display_name":"Corp SSO","mode":"oauth","default":true,"installation_id":"26","native_start_path":"/api/v2/auth/oauth/26/native/start"}]}"""
    private val completion = """{"access_token":"new-a","refresh_token":"new-r","expires_in":3600}"""

    @After fun clean() { OAuthRedirects.end(); OAuthRedirects.takeOrphan() }

    @Test
    fun reLoginCompletesAfterTheLockRemovedTheFormAndTheRedirectArrivesAtResume() {
        val urls = CopyOnWriteArrayList<String>()
        val logins = CopyOnWriteArrayList<Triple<String, String, String?>>()
        val removedByLock = AtomicBoolean(false)
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v2/system/identity" -> identity
                "/api/v2/auth/providers" -> providers
                "/api/v2/auth/oauth/complete" -> completion
                else -> error("unexpected ${request.url.encodedPath}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val connector = AddServerConnector(clientFactory = { origin, token -> ApiClient(origin, token, engine) })
        val scenario = compose.activityRule.scenario
        compose.setContent {
            VeilAdminTheme {
                val owner = rememberProviderSignInOwner()
                owner.openBrowser = { _, url -> urls += url; true }
                ProviderSignInResumeEffect(owner)
                AppLockScreen(enabled = true) {
                    DisposableEffect(Unit) { onDispose { removedByLock.set(true) } }
                    ReLoginForm(
                        server = AdminServer("s1", "https://example.test", "Bloem West"), reason = null, connector = connector,
                        onLoggedIn = { a, r, p -> logins += Triple(a, r, p) },
                    )
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sign in with Corp SSO").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Sign in with Corp SSO") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { urls.isNotEmpty() }

        // The Custom Tab opens: the activity stops, the lock engages and removes the form.
        scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { removedByLock.get() }
        assertTrue("the lock removed the form", removedByLock.get())

        // The redirect arrives while stopped; the activity resumes (the bypass unlocks it).
        val state = Url(urls.single()).parameters["app_state"]!!
        OAuthRedirects.deliver("org.siloserver.silo:/auth/callback?code=one-time&state=$state&server=$serverId&iss=https://example.test")
        scenario.moveToState(Lifecycle.State.RESUMED)

        compose.waitUntil(10_000) { logins.isNotEmpty() }
        assertEquals(listOf(Triple("new-a", "new-r", "26")), logins)
    }
}
