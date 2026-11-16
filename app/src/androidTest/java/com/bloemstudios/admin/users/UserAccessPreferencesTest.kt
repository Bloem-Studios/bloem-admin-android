package com.bloemstudios.admin.users

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.http.HttpMethod
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The User page's Access & limits and Preferences tabs on a device, against a scripted server. */
@RunWith(AndroidJUnit4::class)
class UserAccessPreferencesTest {
    @get:Rule val compose = createComposeRule()

    private companion object {
        const val WAIT = 30_000L
        const val FAMILY = """{"id":"g1","name":"Family","description":"","library_ids":["1"],"max_playback_quality":"1080p","download_allowed":true,"download_transcode_allowed":false,"transcode_allowed":true,"audio_transcode_allowed":true,"max_streams":2,"max_transcodes":1,"max_remote_stream_bitrate_kbps":0,"max_local_stream_bitrate_kbps":0,"max_profiles":5,"allowed_permissions":["marker_edit"],"requests_allowed":true,"is_default":false,"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","member_count":3}"""
        const val MOVIES = """{"id":"1","name":"Movies","type":"movie","paths":[],"enabled":true}"""
        const val SHOWS = """{"id":"2","name":"Shows","type":"show","paths":[],"enabled":true}"""
        const val CONTRACT = """{"api_version":1,"revision":28,"definitions":[
            {"key":"playback.subtitle_mode","persistence":"remote","allowed_scopes":["profile","profile_device"],"value_schema":{"type":"enum","values":[{"value":"auto","label":"Auto"},{"value":"always","label":"Always on"},{"value":"off","label":"Off"}]},"default_value":"auto","category":"playback","label":"Subtitles"},
            {"key":"playback.show_forced_subtitles","persistence":"remote","allowed_scopes":["profile","profile_device"],"value_schema":{"type":"boolean"},"default_value":true,"category":"playback","label":"Forced subtitles"},
            {"key":"playback.max_bitrate_kbps","persistence":"remote","allowed_scopes":["profile"],"value_schema":{"type":"integer","nullable":true,"minimum":100,"maximum":200000},"default_value":null,"category":"playback","label":"Maximum bitrate"},
            {"key":"playback.subtitle_appearance","persistence":"remote","allowed_scopes":["profile"],"value_schema":{"type":"object"},"default_value":{"fontSize":"large"},"category":"playback","label":"Subtitle appearance"}
        ]}"""
    }

    private fun fixture(vararg people: FixturePerson, configure: UserPageFixture.() -> Unit = {}): UserPageFixture {
        val f = UserPageFixture(listOf(FixturePerson("1", "alice", role = "admin")) + people)
        f.routes["/api/v2/admin/access-groups"] = UserPageFixture.page(FAMILY)
        f.routes["/api/v2/libraries"] = UserPageFixture.page(MOVIES, SHOWS)
        f.routes["/api/v2/admin/users/policy-defaults"] = """{"admin":{"library_ids":null},"ungrouped":{"library_ids":["2"]}}"""
        f.routes["/api/v2/settings/contract"] = CONTRACT
        f.configure()
        return f
    }

    private fun open(f: UserPageFixture, name: String = "bob") {
        compose.setContent { VeilAdminTheme { UsersScreen(f.client) } }
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("$name, User, Active. Opens the user page.").performClick()
        waitForText("Reset password")
    }

    private fun waitForText(text: String) =
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun bitrateField() = compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Maximum bitrate"))

    private fun openTab(tab: String) {
        // The tab row scrolls sideways; a tab past the edge has to be scrolled to first.
        compose.onNodeWithText(tab).performScrollTo().performClick()
    }

    @Test
    fun accessGroupLibrariesAndPermissionsSaveInOneGuardedWrite() {
        val f = fixture(FixturePerson("2", "bob"))
        open(f)
        openTab("Access & limits")
        waitForText("Family")

        // Nothing to save yet.
        compose.onNodeWithText("Save changes").performScrollTo().assertIsNotEnabled()
        // Inherited libraries are named truthfully: this account sees every library, with no group.
        compose.onNodeWithText("Use the server default").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All libraries").assertIsDisplayed()

        // Picking the group changes what inherit means before anything is saved.
        compose.onNodeWithText("Family").performScrollTo().performClick()
        compose.onNodeWithText("Use the group’s libraries").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Movies").assertIsDisplayed()

        compose.onNodeWithText("Choose for this account").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Marker editing").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled().performClick()

        compose.waitUntil(WAIT) { f.writes.isNotEmpty() }
        val write = f.writes.single()
        assertEquals("/api/v2/admin/users/2", write.path)
        assertEquals("\"v1\"", write.ifMatch)
        // Choosing starts from what the account sees now (every library); the group's marker permission is assigned.
        assertEquals("""{"access_group_id":"g1","library_ids":["1","2"],"permissions":["marker_edit"]}""", write.body)
        waitForText("Saved")
    }

    @Test
    fun aGroupThatDoesNotAllowAPermissionLocksItAndSaysWhy() {
        val f = fixture(FixturePerson("2", "bob", groupId = "g1"))
        open(f)
        openTab("Access & limits")
        waitForText("Family")

        compose.onNodeWithContentDescription("Watch live TV").performScrollTo().assertIsNotEnabled()
        // Live TV and metadata curation: the group allows only marker editing.
        assertEquals(2, compose.onAllNodesWithText("Family group doesn’t allow this").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Marker editing").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("The Family group allows this").assertExists()
    }

    @Test
    fun anAdminHasNoGroupPicker() {
        val f = fixture(FixturePerson("3", "carol", role = "admin"))
        compose.setContent { VeilAdminTheme { UsersScreen(f.client) } }
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("carol", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("carol, Admin, Active. Opens the user page.").performClick()
        // Another admin's account is view-only for an organization admin, so there is no Reset password here.
        waitForText("Access & limits")
        openTab("Access & limits")

        waitForText("Admins don’t use access groups. Unset values follow the server default.")
        assertEquals(0, compose.onAllNodesWithText("No group").fetchSemanticsNodes().size)
    }

    @Test
    fun aConflictOnAnAccessSaveShowsTheServersValuesAndNeverRetries() {
        val bob = FixturePerson("2", "bob")
        val f = fixture(bob)
        // Someone else gave bob Live TV after the page was read; the server's 412 says so.
        f.onWrite = { bob.permissions = """["watch_live_tv"]""" }
        open(f)
        openTab("Access & limits")
        waitForText("Family")
        compose.onNodeWithContentDescription("Marker editing").performScrollTo().performClick()
        f.writeStatus = io.ktor.http.HttpStatusCode.PreconditionFailed
        compose.onNodeWithText("Save changes").performScrollTo().performClick()

        // The message stays after the form resets to the server's values.
        waitForText("This user changed since it was loaded. Review it and try again.")
        compose.waitUntil(WAIT) { f.calls.count { it.method == HttpMethod.Get && it.path == "/api/v2/admin/users/2" } >= 2 }
        compose.waitForIdle()
        compose.onNodeWithText("This user changed since it was loaded. Review it and try again.").assertExists()
        compose.onNodeWithContentDescription("Watch live TV").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Marker editing").performScrollTo().assertIsOff()
        assertEquals(1, f.writes.size) // never retried
        assertEquals("""{"permissions":["marker_edit"]}""", f.writes.single().body)
        assertEquals("\"v1\"", f.writes.single().ifMatch)
        compose.onNodeWithText("Save changes").performScrollTo().assertIsNotEnabled()
    }

    private fun withPreferences(f: UserPageFixture) {
        f.routes["/api/v2/admin/users/2/profiles"] = UserPageFixture.page(
            """{"id":"p1","name":"Main","has_pin":false,"is_primary":true,"is_child":false,"last_seen_at":null,"avatar":"","avatar_source":"none"}"""
        )
        f.routes["/api/v2/admin/users/2/settings/values"] = UserPageFixture.page(
            """{"key":"playback.subtitle_mode","scope":"profile","value":"auto","revision":2,"profile_id":"p1"}""",
            """{"key":"playback.show_forced_subtitles","scope":"profile","value":true,"revision":2,"profile_id":"p1"}""",
            """{"key":"playback.max_bitrate_kbps","scope":"profile","value":null,"revision":2,"profile_id":"p1"}""",
            """{"key":"playback.subtitle_appearance","scope":"profile","value":{"fontSize":"large"},"revision":2,"profile_id":"p1"}"""
        )
    }

    @Test
    fun preferencesShowTheProfileLevelAndEditOneRowAtATime() {
        val f = fixture(FixturePerson("2", "bob")).also(::withPreferences)
        open(f)
        openTab("Preferences")

        waitForText("Subtitles")
        // The tab can still be settling under the shell's top bar when its header appears, so bring the row into view.
        compose.onNodeWithText("Forced subtitles").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Subtitle appearance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View only").performScrollTo().assertIsDisplayed() // an object is shown, not edited here

        // A switch writes at once, to exactly that row.
        compose.onNodeWithContentDescription("Forced subtitles").performScrollTo().performClick()
        compose.waitUntil(WAIT) { f.writes.isNotEmpty() }
        val toggle = f.writes.single()
        assertEquals(HttpMethod.Put, toggle.method)
        assertEquals("/api/v2/admin/users/2/settings/values/playback.show_forced_subtitles", toggle.path)
        assertEquals("scope=profile&profile_id=p1", toggle.query)
        assertEquals("""{"value":false}""", toggle.body)
        assertEquals(null, toggle.ifMatch)
    }

    @Test
    fun aChoiceAndANumberAreEditedWithTheContractsOwnControls() {
        val f = fixture(FixturePerson("2", "bob")).also(::withPreferences)
        open(f)
        openTab("Preferences")
        waitForText("Subtitles")

        compose.onNodeWithContentDescription("Subtitles: Auto").performClick()
        compose.onNodeWithText("Always on").performClick()
        compose.waitUntil(WAIT) { f.writes.size == 1 }
        assertEquals("""{"value":"always"}""", f.writes[0].body)

        // The number is checked against the contract before anything is sent.
        bitrateField().performScrollTo().performTextReplacement("50")
        compose.onNodeWithText("Enter a number from 100 to 200000").assertIsDisplayed()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsNotEnabled()
        bitrateField().performTextReplacement("5000")
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(WAIT) { f.writes.size == 2 }
        assertEquals("""{"value":5000}""", f.writes[1].body)
    }

    @Test
    fun removingASettingAsksFirstThenDeletesThatRow() {
        val f = fixture(FixturePerson("2", "bob")).also(::withPreferences)
        open(f)
        openTab("Preferences")
        waitForText("Subtitles")

        compose.onNodeWithContentDescription("Remove Subtitles").performScrollTo().performClick()
        compose.onNodeWithText("Go back to the app default?").assertIsDisplayed()
        assertTrue(f.writes.isEmpty())
        compose.onNodeWithText("Use app default").performClick()

        compose.waitUntil(WAIT) { f.writes.isNotEmpty() }
        val delete = f.writes.single()
        assertEquals(HttpMethod.Delete, delete.method)
        assertEquals("/api/v2/admin/users/2/settings/values/playback.subtitle_mode", delete.path)
        assertEquals("scope=profile&profile_id=p1", delete.query)
    }

    @Test
    fun addingASettingStartsAtTheValueItReplaces() {
        val f = fixture(FixturePerson("2", "bob")).also(::withPreferences)
        f.routes["/api/v2/admin/users/2/settings/values"] = UserPageFixture.page(
            """{"key":"playback.show_forced_subtitles","scope":"profile","value":true,"revision":2,"profile_id":"p1"}"""
        )
        open(f)
        openTab("Preferences")
        waitForText("Forced subtitles")

        compose.onNodeWithText("Add a setting").performScrollTo().performClick()
        compose.onNodeWithText("Subtitles").performClick() // the dialog's checkbox row
        compose.onNodeWithText("Set").performClick()

        compose.waitUntil(WAIT) { f.writes.isNotEmpty() }
        // The app default from the contract, so nothing changes until the admin edits it.
        assertEquals("""{"value":"auto"}""", f.writes.single().body)
        assertEquals("scope=profile&profile_id=p1", f.writes.single().query)
    }

    @Test
    fun inAllServersPreferencesWriteOnlyToThePagesServer() {
        val a = fixture(FixturePerson("2", "bob")).also(::withPreferences)
        val b = fixture(FixturePerson("7", "dave"))
        val serverA = AdminServer("srv-a", "https://a.fixture.invalid", "Server A")
        val serverB = AdminServer("srv-b", "https://b.fixture.invalid", "Server B")
        val clients = mapOf("srv-a" to a.client, "srv-b" to b.client)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(base.filesDir, "user-prefs-$id").apply { mkdirs() }
        }
        val store = ServerStore(context, base.getSharedPreferences("user-prefs-tokens-$id", Context.MODE_PRIVATE), metadataWriter = {}, metadataReader = { emptyList() })
        try {
            compose.setContent {
                VeilAdminTheme { AggregateUsersScreen(listOf(serverA, serverB), store, { clients.getValue(it.id) }, {}) }
            }
            compose.waitUntil(WAIT) { compose.onAllNodesWithText("dave@example.test").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("bob, User, Active. Opens the user page.").performClick()
            waitForText("Server: Server A")
            openTab("Preferences")
            waitForText("Forced subtitles")
            compose.onNodeWithContentDescription("Forced subtitles").performScrollTo().performClick()

            compose.waitUntil(WAIT) { a.writes.isNotEmpty() }
            assertEquals("/api/v2/admin/users/2/settings/values/playback.show_forced_subtitles", a.writes.single().path)
            assertTrue(b.writes.isEmpty())
            assertTrue(b.calls.none { it.path.startsWith("/api/v2/admin/users/2") })
        } finally {
            store.close()
            base.deleteSharedPreferences("user-prefs-tokens-$id")
        }
    }
}
