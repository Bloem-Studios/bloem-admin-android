package com.bloemstudios.admin.apikeys

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The API keys page on a device, against a scripted server: what each row shows, Load more, and Revoke with If-Match. */
@RunWith(AndroidJUnit4::class)
class ApiKeysScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Call(val method: HttpMethod, val path: String, val query: String, val ifMatch: String?)

    private fun user(id: String, name: String, role: String, owner: Boolean = false) =
        """{"id":"$id","username":"$name","email":"$name@example.test","role":"$role","permissions":[],"enabled":true,"library_ids":null,""" +
            """"max_playback_quality":null,"max_streams":null,"max_transcodes":null,"transcode_allowed":null,"audio_transcode_allowed":null,""" +
            """"max_profiles":null,"download_allowed":null,"download_transcode_allowed":null,"requests_allowed":null,"access_group_id":null,""" +
            """"password_login":true,"password_change_required":false,"is_owner":$owner,"break_glass":false,"effective_policy":{"library_ids":null,""" +
            """"max_playback_quality":"1080p","max_streams":6,"max_transcodes":2,"max_profiles":4,"transcode_allowed":true,"audio_transcode_allowed":true,""" +
            """"download_allowed":true,"download_transcode_allowed":false,"requests_allowed":true,"permissions":[]},""" +
            """"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","last_active_at":null}"""

    private fun key(id: String, owner: String, label: String, scopes: String = "[]", lastUsed: String? = null, tier: String = "standard") =
        """{"id":"$id","user_id":"$owner","label":"$label","key_prefix":"bk_$id","rate_tier":"$tier","scopes":$scopes,"created_at":"2026-11-01T10:00:00Z",""" +
            """"username":"u$owner"${if (lastUsed != null) ""","last_used_at":"$lastUsed"""" else ""}}"""

    private class Server(val meId: String, val users: String, val pages: MutableList<String>) {
        val calls = CopyOnWriteArrayList<Call>()
        var deleteStatus = HttpStatusCode.NoContent
        private val json = headersOf(HttpHeaders.ContentType, "application/json")

        val client = ApiClient("https://fixture.invalid", "tok", MockEngine { request ->
            val path = request.url.encodedPath
            calls += Call(request.method, path, request.url.encodedQuery, request.headers[HttpHeaders.IfMatch])
            when {
                path == "/api/v2/admin/api-keys/capabilities" -> respond("""{"available":true}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/api-keys" -> respond(pages.removeAt(0), HttpStatusCode.OK, json)
                path == "/api/v2/account/me" -> respond("""{"id":"$meId","username":"me","email":"m@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/users" -> respond(users, HttpStatusCode.OK, json)
                request.method == HttpMethod.Get -> respond(
                    """{"id":"11","user_id":"2","label":"x","key_prefix":"bk_11","rate_tier":"standard","scopes":[],"created_at":"2026-11-01T10:00:00Z"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"rev-7\"")),
                )
                request.method == HttpMethod.Delete -> respond("", deleteStatus)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })
    }

    private fun page(vararg rows: String, next: String? = null) =
        """{"items":[${rows.joinToString(",")}],"page":{"has_more":${next != null}${if (next != null) ""","next_cursor":"$next"""" else ""}}}"""

    private fun users(vararg rows: String) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""

    private fun show(server: Server) = compose.setContent { VeilAdminTheme { ApiKeysScreen(server.client) } }

    private fun waitForText(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun admin(meId: String = "1") = Server(
        meId = meId,
        users = users(user("1", "alice", "admin", owner = true), user("2", "bob", "user"), user("3", "carol", "admin")),
        pages = mutableListOf(
            page(
                key("11", "2", "Requests bot", scopes = """["requests:write","library:read"]""", lastUsed = "2026-11-14T09:30:00Z"),
                key("12", "2", "Never used key"),
                key("13", "3", "Carol's key", tier = "elevated"),
                next = "c1",
            ),
            page(key("14", "2", "Second page key")),
        ),
    )

    @Test
    fun eachKeyShowsItsOwnerScopeAndLastUsed() {
        show(admin())
        waitForText("Requests bot")
        assertEquals("two of the three keys belong to u2", 2, compose.onAllNodesWithText("Owner u2", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Access: requests:write, library:read").assertIsDisplayed()
        assertEquals("the two keys with no scopes have full access", 2, compose.onAllNodesWithText("Access: Full access", substring = true).fetchSemanticsNodes().size)
        assertEquals("keys 12 and 13 were never used", 2, compose.onAllNodesWithText("Never used").fetchSemanticsNodes().size)
        compose.onNodeWithText("Last used", substring = true).assertExists()
        compose.onNodeWithText("Elevated rate limit").assertExists()
        compose.onNodeWithText("Create keys and change rate limits in the web admin").assertIsDisplayed()
    }

    @Test
    fun loadMoreAddsTheNextPage() {
        val server = admin()
        show(server)
        waitForText("Requests bot")
        compose.onNodeWithTag("api-keys-load-more").performScrollTo().performClick()
        waitForText("Second page key")
        assertEquals("c1", server.calls.last { it.path == "/api/v2/admin/api-keys" }.query.substringAfter("cursor=").substringBefore("&"))
        assertEquals(0, compose.onAllNodesWithTag("api-keys-load-more").fetchSemanticsNodes().size)
    }

    @Test
    fun revokeAsksFirstThenDeletesWithTheVersionItRead() {
        val server = admin()
        show(server)
        waitForText("Requests bot")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("api-key-revoke:11").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("api-key-revoke:11").performScrollTo().performClick()
        compose.onNodeWithText("Revoke Requests bot?").assertIsDisplayed()
        assertTrue("nothing is sent before the person confirms", server.calls.none { it.method == HttpMethod.Delete })
        compose.onNodeWithTag("api-key-revoke-confirm").performClick()

        compose.waitUntil(30_000) { server.calls.any { it.method == HttpMethod.Delete } }
        val delete = server.calls.single { it.method == HttpMethod.Delete }
        assertEquals("/api/v2/admin/api-keys/11", delete.path)
        assertEquals("\"rev-7\"", delete.ifMatch)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Requests bot").fetchSemanticsNodes().isEmpty() }
        waitForText("Revoked Requests bot")
    }

    @Test
    fun anAdminCannotRevokeAnotherAdminsKey() {
        // Signed in as carol's fellow admin who is not the owner.
        val server = Server(
            meId = "5",
            users = users(user("1", "alice", "admin", owner = true), user("3", "carol", "admin"), user("5", "dave", "admin")),
            pages = mutableListOf(page(key("13", "3", "Carol's key"), key("15", "1", "Owner's key"))),
        )
        show(server)
        waitForText("Carol's key")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Only the server owner can revoke this key").fetchSemanticsNodes().size == 2 }
        assertEquals(0, compose.onAllNodesWithTag("api-key-revoke:13").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("api-key-revoke:15").fetchSemanticsNodes().size)
    }
}
