package com.bloemstudios.admin.users

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The User page on a device, against a scripted server: no credentials, no network. */
@RunWith(AndroidJUnit4::class)
class UserPageTest {
    @get:Rule val compose = createComposeRule()

    private companion object { const val WAIT = 30_000L }

    private class Person(
        val id: String,
        val name: String,
        val role: String = "user",
        var enabled: Boolean = true,
        val passwordLogin: Boolean = true,
        val owner: Boolean = false
    ) {
        fun json() = """{"id":"$id","username":"$name","email":"$name@example.test","role":"$role","permissions":[],"enabled":$enabled,""" +
            """"library_ids":null,"max_playback_quality":null,"max_streams":null,"max_transcodes":null,"transcode_allowed":null,""" +
            """"audio_transcode_allowed":null,"max_profiles":null,"download_allowed":null,"download_transcode_allowed":null,""" +
            """"requests_allowed":null,"access_group_id":null,"password_login":$passwordLogin,"password_change_required":false,""" +
            """"is_owner":$owner,"break_glass":false,"effective_policy":{"library_ids":null,"max_playback_quality":"1080p","max_streams":6,""" +
            """"max_transcodes":2,"max_profiles":4,"transcode_allowed":true,"audio_transcode_allowed":true,"download_allowed":true,""" +
            """"download_transcode_allowed":false,"requests_allowed":true,"permissions":[]},""" +
            """"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","last_active_at":null}"""
    }

    private class Call(val method: HttpMethod, val path: String, val ifMatch: String?, val body: String?)

    private class Fixture(val people: List<Person>, val meId: String = "1", var writeStatus: HttpStatusCode = HttpStatusCode.NoContent) {
        val calls = CopyOnWriteArrayList<Call>()
        val writes get() = calls.filter { it.method == HttpMethod.Put || it.method == HttpMethod.Delete }
        private val json = headersOf(HttpHeaders.ContentType, "application/json")
        private fun page(rows: List<String>) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""

        val client = ApiClient("https://fixture.invalid", "tok", MockEngine { request ->
            val path = request.url.encodedPath
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            calls += Call(request.method, path, request.headers[HttpHeaders.IfMatch], body)
            val userId = Regex("^/api/v2/admin/users/(\\d+)").find(path)?.groupValues?.get(1)
            when {
                request.method != HttpMethod.Get -> {
                    if (writeStatus == HttpStatusCode.PreconditionFailed) respond(
                        """{"type":"https://siloserver.org/docs/api/v2/problems/precondition_failed","title":"Precondition Failed","status":412,"detail":"changed","instance":"urn:silo:request:1"}""",
                        writeStatus, headersOf(HttpHeaders.ContentType, "application/problem+json")
                    ) else {
                        if (body?.contains("\"enabled\":false") == true) people.first { it.id == userId }.enabled = false
                        respond("", writeStatus)
                    }
                }
                path == "/api/v2/account/me" -> respond("""{"id":"$meId","username":"me","email":"me@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/users/capabilities" -> respond(
                    """{"available":true,"login_sessions":true,"account_devices":true,"account_downloads":false,"watch_summary":true,"password_reset_email":true}""",
                    HttpStatusCode.OK, json
                )
                path == "/api/v2/admin/users" -> respond(page(people.map { it.json() }), HttpStatusCode.OK, json)
                userId != null && path == "/api/v2/admin/users/$userId" -> respond(
                    people.first { it.id == userId }.json(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\""))
                )
                path.endsWith("/watch-summary") -> respond("""{"days":30,"since":"2026-01-01T00:00:00Z","plays":0,"completed_plays":0,"watched_seconds":0,"last_played_at":null}""", HttpStatusCode.OK, json)
                else -> respond(page(emptyList()), HttpStatusCode.OK, json)
            }
        })
    }

    private val alice = Person("1", "alice", role = "admin")
    private val bob = Person("2", "bob")
    private val carol = Person("3", "carol")

    private fun showUsers(fixture: Fixture) {
        compose.setContent { VeilAdminTheme { UsersScreen(fixture.client) } }
    }

    /** The confirming button of an open dialog: the one beside Cancel, not the page button with the same words. */
    private fun confirmButton(label: String) = compose.onNode(hasText(label) and hasAnySibling(hasText("Cancel")))

    private fun waitForText(text: String) {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun passwordField() = compose.onNode(hasSetTextAction() and hasText("New password"))

    private fun openRow(name: String, role: String = "User") {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("$name, $role, Active. Opens the user page.").performClick()
    }

    @Test
    fun aRowShowsNameEmailRoleAndStatusAndOpensThePage() {
        showUsers(Fixture(listOf(alice, bob, carol)))
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("bob@example.test").fetchSemanticsNodes().isNotEmpty() }
        // Status is a dot and a word of its own, beside the email and the role.
        assertTrue(compose.onAllNodesWithText("Active").fetchSemanticsNodes().size >= 2)
        assertTrue(compose.onAllNodesWithText("User").fetchSemanticsNodes().size >= 2)
        // The old overflow menu is gone.
        compose.onAllNodesWithText("Actions for bob").assertCountEqualsZero()

        openRow("bob")

        compose.onNodeWithText("Reset password").assertIsDisplayed()
        compose.onNodeWithText("Disable account").assertIsDisplayed()
        compose.onNodeWithText("Delete account").assertIsDisplayed()
        compose.onNodeWithText("Password sign-in").assertIsDisplayed()
        // The tabs scroll sideways; on a narrow screen the later ones start past the edge.
        listOf("Overview", "Access & limits", "Sign-in", "Activity").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        // No Downloads tab: the server does not offer account downloads.
        compose.onAllNodesWithText("Downloads").assertCountEqualsZero()
    }

    @Test
    fun backReturnsToTheListWithItsSearchKept() {
        showUsers(Fixture(listOf(alice, bob, carol)))
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("carol@example.test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("bo")
        compose.onAllNodesWithText("carol@example.test").assertCountEqualsZero()

        openRow("bob")
        compose.onNodeWithText("Reset password").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to users").performClick()

        compose.onAllNodesWithText("Reset password").assertCountEqualsZero()
        compose.onNodeWithText("bo").assertIsDisplayed()
        compose.onNodeWithText("bob@example.test").assertIsDisplayed()
        compose.onAllNodesWithText("carol@example.test").assertCountEqualsZero()
    }

    @Test
    fun actionsAreAtLeast48dpTall() {
        showUsers(Fixture(listOf(alice, bob)))
        openRow("bob")
        compose.onNodeWithText("Reset password").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Disable account").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Delete account").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Back to users").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun yourOwnAccountCannotBeDisabledOrDeleted() {
        showUsers(Fixture(listOf(alice, bob), meId = "1"))
        openRow("alice", role = "Admin")

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Reset password").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Disable account").assertCountEqualsZero()
        compose.onAllNodesWithText("Delete account").assertCountEqualsZero()
    }

    @Test
    fun theOwnerIsViewOnlyForAnOrganizationAdmin() {
        val owner = Person("9", "root", role = "admin", owner = true)
        showUsers(Fixture(listOf(alice, owner), meId = "1"))
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("root@example.test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("root, Owner, Active. Opens the user page.").performClick()

        waitForText("This is the server owner. Only the owner can change this account.")
        compose.onAllNodesWithText("Reset password").assertCountEqualsZero()
        compose.onAllNodesWithText("Disable account").assertCountEqualsZero()
        compose.onAllNodesWithText("Delete account").assertCountEqualsZero()
    }

    @Test
    fun anExternalSignInAccountOffersSetPasswordNotReset() {
        val fixture = Fixture(listOf(alice, Person("2", "bob", passwordLogin = false)))
        showUsers(fixture)
        openRow("bob")

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Set temporary password").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Reset password").assertCountEqualsZero()
        compose.onNodeWithText("External sign-in").assertIsDisplayed() // once: the header pill
        compose.onNodeWithText("Set temporary password").performClick()

        // The dialog's button is disabled until the password is acceptable.
        confirmButton("Set password").assertIsNotEnabled()
        passwordField().performTextInput("short")
        compose.onNodeWithText("The password needs at least 8 characters").assertIsDisplayed()
        passwordField().performTextReplacement("correct horse")
        confirmButton("Set password").assertIsEnabled().performClick()

        compose.waitUntil(WAIT) { fixture.writes.isNotEmpty() }
        val write = fixture.writes.single()
        assertEquals("/api/v2/admin/users/2", write.path)
        assertEquals("\"v1\"", write.ifMatch)
        assertEquals("""{"password":"correct horse","require_password_change":true}""", write.body)
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("bob can sign in with the new password and must change it at the next sign-in").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun disablingAsksFirstThenWritesWithTheFreshETag() {
        val fixture = Fixture(listOf(alice, bob))
        showUsers(fixture)
        openRow("bob")

        compose.onNodeWithText("Disable account").performClick()
        compose.onNodeWithText("Disable bob?").assertIsDisplayed()
        assertTrue(fixture.writes.isEmpty())
        confirmButton("Disable account").performClick()

        compose.waitUntil(WAIT) { fixture.writes.isNotEmpty() }
        assertEquals("""{"enabled":false}""", fixture.writes.single().body)
        assertEquals("\"v1\"", fixture.writes.single().ifMatch)
        // After the write the page reads bob again and now offers Enable.
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Enable account").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This account can’t sign in. Its profiles and history are kept.").assertIsDisplayed()
    }

    @Test
    fun deletingAsksFirstThenClosesThePage() {
        val fixture = Fixture(listOf(alice, bob))
        showUsers(fixture)
        openRow("bob")

        compose.onNodeWithText("Delete account").performClick()
        compose.onNodeWithText("Delete bob?").assertIsDisplayed()
        assertTrue(fixture.writes.isEmpty())
        confirmButton("Delete account").performClick()

        compose.waitUntil(WAIT) { fixture.writes.isNotEmpty() }
        assertEquals(HttpMethod.Delete, fixture.writes.single().method)
        assertEquals("/api/v2/admin/users/2", fixture.writes.single().path)
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Reset password").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun savingAccessAfterSomeoneElseChangedItShowsTheConflict() {
        val fixture = Fixture(listOf(alice, bob), writeStatus = HttpStatusCode.PreconditionFailed)
        showUsers(fixture)
        openRow("bob")

        compose.onNodeWithText("Access & limits").performClick()
        compose.onNodeWithText("Effective libraries: Unrestricted").assertIsDisplayed()
        compose.onNodeWithContentDescription("Inherit Max streams").performScrollTo().performClick()
        compose.onNodeWithText("Max streams").performTextReplacement("5")
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        compose.waitUntil(WAIT) { fixture.writes.isNotEmpty() }
        assertEquals("""{"max_streams":5}""", fixture.writes.single().body)
        assertEquals("\"v1\"", fixture.writes.single().ifMatch)
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("This user changed since it was loaded. Review it and try again.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, fixture.writes.size) // never retried
    }

    @Test
    fun theSignInTabListsSessionsAndDevices() {
        val fixture = Fixture(listOf(alice, bob))
        showUsers(fixture)
        openRow("bob")

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Sign-in").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sign-in").performClick()

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("No active sessions").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Password sign-in").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("No known devices").assertExists()
        assertTrue(fixture.calls.any { it.path == "/api/v2/admin/users/2/login-sessions" })
    }

    @Test
    fun inAllServersThePageNamesTheServerAndWritesOnlyThere() {
        val a = Fixture(listOf(alice, bob))
        val b = Fixture(listOf(Person("7", "dave")))
        val serverA = AdminServer("srv-a", "https://a.fixture.invalid", "Server A")
        val serverB = AdminServer("srv-b", "https://b.fixture.invalid", "Server B")
        val clients = mapOf("srv-a" to a.client, "srv-b" to b.client)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base.filesDir, "user-page-$id").apply { mkdirs() }
        }
        val store = ServerStore(context, base.getSharedPreferences("user-page-tokens-$id", Context.MODE_PRIVATE), metadataWriter = {}, metadataReader = { emptyList() })
        try {
            compose.setContent {
                VeilAdminTheme {
                    AggregateUsersScreen(listOf(serverA, serverB), store, { clients.getValue(it.id) }, {})
                }
            }
            compose.waitUntil(WAIT) { compose.onAllNodesWithText("dave@example.test").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("bob, User, Active. Opens the user page.").performClick()

            compose.waitUntil(WAIT) { compose.onAllNodesWithText("Server: Server A").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(WAIT) { compose.onAllNodesWithText("Disable account").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Disable account").performClick()
            confirmButton("Disable account").performClick()

            compose.waitUntil(WAIT) { a.writes.isNotEmpty() }
            assertEquals("/api/v2/admin/users/2", a.writes.single().path)
            assertTrue(b.writes.isEmpty())
            assertTrue(b.calls.none { it.path.startsWith("/api/v2/admin/users/2") })
        } finally {
            store.close()
            base.deleteSharedPreferences("user-page-tokens-$id")
        }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
    assertEquals(0, fetchSemanticsNodes().size)
}
