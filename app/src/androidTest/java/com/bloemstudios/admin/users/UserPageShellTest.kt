package com.bloemstudios.admin.users

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.bloemstudios.admin.users.detail.UserPageSwipeTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The User page inside the real shell: top bar and drawer stay usable, back and destinations leave the page. */
@RunWith(AndroidJUnit4::class)
class UserPageShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val WAIT = 30_000L
        // The drawer's destination tags are "shell-destination:<id>".
        const val DESTINATION = "shell-destination:"
    }

    private fun showInShell(fixture: UserPageFixture) {
        compose.setContent {
            VeilAdminTheme {
                var selected by remember { mutableStateOf("people.users") }
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                    { selected = it }, false, {},
                ) { id -> if (id == "people.users") UsersScreen(fixture.client) else Text("feature:$id") }
            }
        }
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openRow(name: String, role: String = "User") {
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("$name, $role, Active. Opens the user page.").performClick()
        waitForText("Reset password")
    }

    private fun people(count: Int = 2) = buildList {
        add(FixturePerson("1", "alice", role = "admin"))
        for (i in 2..count) add(FixturePerson("$i", "user%02d".format(i)))
    }

    @Test
    fun aSidewaysSwipeChangesTheUsersTabAndNeverLeavesThePage() {
        showInShell(UserPageFixture(people()))
        openRow("user02")
        fun swipe(left: Boolean) {
            compose.onNodeWithTag(UserPageSwipeTag).performTouchInput { if (left) swipeLeft() else swipeRight() }
            compose.waitForIdle()
        }
        fun assertTab(label: String) = compose.onNode(hasText(label) and isSelected()).assertExists()

        // Before the first tab there is nothing: the page stays, and the shell does not change page.
        swipe(left = false)
        compose.onNodeWithContentDescription("Back to users").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("feature:", substring = true).fetchSemanticsNodes().size)
        assertTab("Overview")

        swipe(left = true)
        assertTab("Access & limits")
        swipe(left = true)
        assertTab("Sign-in")
        swipe(left = false)
        assertTab("Access & limits")
        compose.onNodeWithContentDescription("Back to users").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("feature:", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun thePageIsInsideTheShellNotOverIt() {
        showInShell(UserPageFixture(people()))
        openRow("user02")

        // The shell's own controls are still there and usable: menu, page title, context dropdown.
        compose.onNodeWithContentDescription("Open menu").assertIsDisplayed()
        compose.onNodeWithText("Users").assertIsDisplayed()
        compose.onNodeWithText("Loft").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to users").assertIsDisplayed()
        // The list is not drawn behind the page.
        assertEquals(0, compose.onAllNodesWithText("Invitations").fetchSemanticsNodes().size)
    }

    @Test
    fun systemBackAndTheBackArrowReturnToTheListWithItsSearchKept() {
        showInShell(UserPageFixture(people(4)))
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("user04@example.test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("user02")
        assertEquals(0, compose.onAllNodesWithText("user04@example.test").fetchSemanticsNodes().size)

        openRow("user02")
        systemBack()
        compose.onNodeWithText("user02@example.test").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("user04@example.test").fetchSemanticsNodes().size)

        openRow("user02")
        compose.onNodeWithContentDescription("Back to users").performClick()
        compose.onNodeWithText("user02@example.test").assertIsDisplayed()
    }

    @Test
    fun theListKeepsItsScrollPositionWhenThePageCloses() {
        showInShell(UserPageFixture(people(60)))
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("user02@example.test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToIndex(45)
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("user46@example.test").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("user02@example.test").fetchSemanticsNodes().size)

        openRow("user46")
        systemBack()

        // Still scrolled down: the row we opened is on screen and the top of the list is not.
        compose.onNodeWithText("user46@example.test").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("user02@example.test").fetchSemanticsNodes().size)
    }

    @Test
    fun backClosesAnOpenDrawerBeforeThePage() {
        showInShell(UserPageFixture(people()))
        openRow("user02")

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DESTINATION + "overview.dashboard").assertIsDisplayed()

        systemBack()
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag(DESTINATION + "overview.dashboard").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Reset password").assertIsDisplayed() // the page is still open

        systemBack()
        compose.onNodeWithText("user02@example.test").assertIsDisplayed()
    }

    @Test
    fun changingDestinationLeavesThePage() {
        showInShell(UserPageFixture(people()))
        openRow("user02")

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag(DESTINATION + "overview.dashboard").performClick()
        waitForText("feature:overview.dashboard")

        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag(DESTINATION + "people.users").performClick()
        // Back on Users: the list, not the page.
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("user02@example.test").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("Reset password").fetchSemanticsNodes().size)
    }

    @Test
    fun backAsksBeforeDroppingUnsavedAccessEdits() {
        showInShell(UserPageFixture(people()))
        openRow("user02")
        compose.onNodeWithText("Access & limits").performClick()
        compose.onNodeWithContentDescription("Inherit Max streams").performScrollTo().performClick()
        compose.onNodeWithText("Max streams").performScrollTo().performTextReplacement("5")

        systemBack()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Max streams").assertIsDisplayed() // still on the page

        systemBack()
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("user02@example.test").fetchSemanticsNodes().isNotEmpty() }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithTag(tag: String) =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
