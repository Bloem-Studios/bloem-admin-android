package com.bloemstudios.admin.users

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.http.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The User page's support tools on a device, against a scripted server: reset link, temporary password, Profiles, Watching now. */
@RunWith(AndroidJUnit4::class)
class UserSupportToolsTest {
    @get:Rule val compose = createComposeRule()

    private companion object {
        const val WAIT = 30_000L
        const val CAPABILITIES = "/api/v2/admin/users/capabilities"
        fun capabilities(link: Boolean = true, email: Boolean = true, profiles: Boolean = true) =
            """{"available":true,"login_sessions":true,"account_devices":true,"account_downloads":false,"watch_summary":true,""" +
                """"password_reset_email":$email,"password_reset_link":$link,"profile_management":$profiles}"""
        const val LINK_ANSWER = """{"delivery":"link","delivery_status":"not_requested","expires_at":"2099-01-01T10:00:00Z","reset_url":"https://bloem.example/reset-password#only-in-this-test"}"""
    }

    private fun fixture(vararg people: FixturePerson, link: Boolean = true, email: Boolean = true, profiles: Boolean = true) =
        UserPageFixture(listOf(FixturePerson("1", "alice", role = "admin")) + people).also {
            it.routes[CAPABILITIES] = capabilities(link, email, profiles)
        }

    private fun show(fixture: UserPageFixture) = compose.setContent { VeilAdminTheme { UsersScreen(fixture.client) } }

    private fun waitForText(text: String) = compose.waitUntil(WAIT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForTag(tag: String) = compose.waitUntil(WAIT) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun open(name: String) {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("$name, User, Active. Opens the user page.").performClick()
        waitForText("Set temporary password")
        // The capabilities are read after the page opens.
        compose.waitForIdle()
    }

    // --- Reset password and temporary password ------------------------------------------------------------------

    @Test
    fun aPasswordAccountOffersResetAndATemporaryPassword() {
        show(fixture(FixturePerson("2", "bob")))
        open("bob")
        compose.onNodeWithText("Reset password").assertIsDisplayed()
        compose.onNodeWithText("Set temporary password").assertIsDisplayed()
    }

    @Test
    fun createLinkShowsTheLinkOnceWithCopyAndShareThenForgetsIt() {
        val server = fixture(FixturePerson("2", "bob")).also { it.resetResponse = LINK_ANSWER }
        show(server)
        open("bob")

        compose.onNodeWithText("Reset password").performClick()
        waitForTag("user-reset-link")
        compose.onNodeWithTag("user-reset-link").performClick()
        waitForTag("user-reset-link-done")

        val post = server.calls.single { it.method == HttpMethod.Post && it.path.endsWith("/password-reset") }
        assertEquals("/api/v2/admin/users/2/password-reset", post.path)
        assertEquals("""{"delivery":"link"}""", post.body)
        compose.onNodeWithText("https://bloem.example/reset-password#only-in-this-test").assertIsDisplayed()
        compose.onNodeWithTag("user-reset-link-copy").assertIsDisplayed()
        compose.onNodeWithTag("user-reset-link-share").assertIsDisplayed()

        compose.onNodeWithTag("user-reset-link-done").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("user-reset-link-done").fetchSemanticsNodes().isEmpty() }
        assertEquals(
            "nothing on screen still holds the link",
            0, compose.onAllNodesWithText("only-in-this-test", substring = true).fetchSemanticsNodes().size,
        )

        // Opening Reset password again asks afresh: the link is gone, not kept for the next time.
        compose.onNodeWithText("Reset password").performClick()
        waitForTag("user-reset-link")
        assertEquals(0, compose.onAllNodesWithText("only-in-this-test", substring = true).fetchSemanticsNodes().size)
        assertEquals("no second link was made", 1, server.calls.count { it.method == HttpMethod.Post && it.path.endsWith("/password-reset") })
    }

    @Test
    fun aServerThatCannotMakeLinksSaysSoAndOffersNoButton() {
        show(fixture(FixturePerson("2", "bob"), link = false))
        open("bob")
        compose.onNodeWithText("Reset password").performClick()
        waitForTag("user-reset-email")
        assertEquals(0, compose.onAllNodesWithTag("user-reset-link").fetchSemanticsNodes().size)
        compose.onNodeWithText("This server can't create reset links", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTurnedOffAccountGetsNoResetOption() {
        val server = fixture(FixturePerson("2", "bob", enabled = false))
        show(server)
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("bob", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("bob, User, Disabled. Opens the user page.").performClick()
        waitForText("Reset password")
        compose.waitForIdle()
        compose.onNodeWithText("Reset password").performClick()
        waitForText("This account is turned off. Turn it on to send a reset")
        assertEquals(0, compose.onAllNodesWithTag("user-reset-link").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("user-reset-email").fetchSemanticsNodes().size)
    }

    @Test
    fun aTemporaryPasswordIsOfferedForAnExternalSignInAccountToo() {
        show(fixture(FixturePerson("2", "bob", passwordLogin = false)))
        open("bob")
        assertEquals(0, compose.onAllNodesWithText("Reset password").fetchSemanticsNodes().size)
        compose.onNodeWithText("Set temporary password").assertIsDisplayed()
    }

    // --- Profiles ---------------------------------------------------------------------------------------------------

    private val profiles = """{"items":[""" +
        """{"id":"p1","name":"Bob","has_pin":true,"is_primary":true,"is_child":false,"last_seen_at":"2026-11-14T09:00:00Z"},""" +
        """{"id":"p2","name":"Kids","has_pin":false,"is_primary":false,"is_child":true,"last_seen_at":null}],"page":{"has_more":false}}"""

    private fun openProfiles(server: UserPageFixture) {
        server.routes["/api/v2/admin/users/2/profiles"] = profiles
        show(server)
        open("bob")
        compose.waitUntil(WAIT) { compose.onAllNodes(hasText("Profiles") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Profiles") and hasClickAction()).performClick()
        waitForTag("user-profile-row:p2")
    }

    private fun write(server: UserPageFixture, method: HttpMethod) = server.calls.single { it.method == method && it.path.contains("/profiles") }

    @Test
    fun profilesAreListedWithTheirPinPrimaryAndKidsMarks() {
        openProfiles(fixture(FixturePerson("2", "bob")))
        compose.onNodeWithText("Bob").assertIsDisplayed()
        compose.onNodeWithText("Primary").assertIsDisplayed()
        assertEquals("the profile's name and its Kids mark", 2, compose.onAllNodesWithText("Kids").fetchSemanticsNodes().size)
        compose.onNodeWithText("PIN").assertIsDisplayed()
    }

    @Test
    fun addingAProfileSendsItsName() {
        val server = fixture(FixturePerson("2", "bob"))
        openProfiles(server)
        compose.onNodeWithTag("user-profiles-add").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Maya")
        compose.onNodeWithTag("user-profile-confirm").performClick()
        compose.waitUntil(WAIT) { server.calls.any { it.method == HttpMethod.Post && it.path.endsWith("/profiles") } }
        val post = write(server, HttpMethod.Post)
        assertEquals("/api/v2/admin/users/2/profiles", post.path)
        assertEquals("""{"name":"Maya"}""", post.body)
    }

    @Test
    fun settingAPinSendsFourDigitsAndRemovingItSendsNull() {
        val server = fixture(FixturePerson("2", "bob"))
        openProfiles(server)

        compose.onNodeWithTag("user-profile-menu:p2").performScrollTo().performClick()
        compose.onNodeWithText("Set PIN").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("12")
        compose.onNodeWithText("The PIN must be exactly 4 digits").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("34")
        compose.onNodeWithTag("user-profile-confirm").performClick()
        compose.waitUntil(WAIT) { server.calls.any { it.method.value == "PATCH" } }
        assertEquals("""{"pin":"1234"}""", server.calls.first { it.method.value == "PATCH" }.body)
        assertEquals("/api/v2/admin/users/2/profiles/p2", server.calls.first { it.method.value == "PATCH" }.path)

        compose.onNodeWithTag("user-profile-menu:p1").performScrollTo().performClick()
        compose.onNodeWithText("Remove PIN").performClick()
        compose.onNodeWithTag("user-profile-confirm").performClick()
        compose.waitUntil(WAIT) { server.calls.count { it.method.value == "PATCH" } == 2 }
        val removal = server.calls.last { it.method.value == "PATCH" }
        assertEquals("/api/v2/admin/users/2/profiles/p1", removal.path)
        assertEquals("""{"pin":null}""", removal.body)
    }

    @Test
    fun theMainProfileCannotBeDeletedButAnotherCan() {
        val server = fixture(FixturePerson("2", "bob"))
        openProfiles(server)

        compose.onNodeWithTag("user-profile-menu:p1").performScrollTo().performClick()
        assertEquals(0, compose.onAllNodesWithText("Delete profile").fetchSemanticsNodes().size)
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithTag("user-profile-menu:p2").performScrollTo().performClick()
        compose.onNodeWithText("Delete profile").performClick()
        compose.onNodeWithTag("user-profile-confirm").performClick()
        compose.waitUntil(WAIT) { server.calls.any { it.method == HttpMethod.Delete && it.path.contains("/profiles/") } }
        assertEquals("/api/v2/admin/users/2/profiles/p2", server.calls.single { it.method == HttpMethod.Delete && it.path.contains("/profiles/") }.path)
    }

    @Test
    fun aServerWithoutProfileManagementHasNoProfilesTab() {
        show(fixture(FixturePerson("2", "bob"), profiles = false))
        open("bob")
        assertEquals(0, compose.onAllNodes(hasText("Profiles") and hasClickAction()).fetchSemanticsNodes().size)
    }

    // --- Watching now and times -------------------------------------------------------------------------------------

    @Test
    fun watchingNowShowsWhatTheAccountPlaysAndHidesWhenItPlaysNothing() {
        val playing = """{"session_id":"s1","user_id":"2","username":"bob","profile_id":"p1","media_title":"Dune: Part Two","media_type":"movie",""" +
            """"play_method":"direct","position_seconds":1800.0,"file_duration":7200.0,"is_paused":false,"profile_name":"Living room","client_name":"Infuse"}"""
        val server = fixture(FixturePerson("2", "bob"))
        server.routes["/api/v2/admin/sessions"] = UserPageFixture.page(playing)
        show(server)
        open("bob")
        waitForText("Watching now")
        compose.onNodeWithText("Dune: Part Two").assertIsDisplayed()
        compose.onNodeWithText("Profile Living room", substring = true).assertIsDisplayed()
        compose.onNodeWithText("30 of 120 min").assertIsDisplayed()
        assertTrue(server.calls.any { it.path == "/api/v2/admin/sessions" && it.query.contains("user_id=2") })
    }

    @Test
    fun noWatchingNowCardWhileTheAccountPlaysNothing() {
        show(fixture(FixturePerson("2", "bob")))
        open("bob")
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Access", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("Watching now").fetchSemanticsNodes().size)
    }

    @Test
    fun signInSessionsShowLastSeenAndExpiryAndDevicesShowLastSeen() {
        val server = fixture(FixturePerson("2", "bob"))
        server.routes["/api/v2/admin/users/2/login-sessions"] = UserPageFixture.page(
            """{"id":"ls1","device_name":"Pixel 8","ip_address":"192.0.2.14","created_at":"2026-11-01T10:00:00Z","expires_at":"2099-12-24T10:00:00Z","last_seen_at":"2026-11-14T09:30:00Z","current":false}""",
        )
        server.routes["/api/v2/admin/users/2/devices"] = UserPageFixture.page(
            """{"device_id":"d1","device_name":"Living room TV","device_platform":"tvos","last_seen_at":"2026-11-13T18:00:00Z","last_updated":null,"override_count":0,"profiles":[]}""",
        )
        show(server)
        open("bob")
        compose.onNode(hasText("Sign-in") and hasClickAction()).performScrollTo().performClick()
        waitForText("Pixel 8")
        waitForText("Living room TV")
        // The session row (Last seen, Expires) and the device row (Last seen).
        assertEquals(2, compose.onAllNodes(hasText("Last seen", substring = true)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasText("Expires", substring = true) and hasText("2099", substring = true)).fetchSemanticsNodes().size)
    }
}
