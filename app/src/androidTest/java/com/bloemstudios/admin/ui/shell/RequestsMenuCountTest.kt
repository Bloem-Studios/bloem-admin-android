package com.bloemstudios.admin.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Needs approval count at the end of the Requests menu item, in the drawer and in the permanent drawer of a wide screen. */
@RunWith(AndroidJUnit4::class)
class RequestsMenuCountTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(badges: Map<String, Int>, rail: Boolean) {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), "live.streams", {}, rail, {},
                    destinationBadges = badges,
                ) { id -> Text("feature:$id") }
            }
        }
    }

    private val requestsRow = hasTestTag("shell-destination:work.requests")
    private fun badge() = compose.onAllNodes(hasTestTag("shell-destination-badge") and hasAnyAncestor(requestsRow), useUnmergedTree = true)

    @Test
    fun theMenuItemShowsTheCountWhenTheDrawerIsOpen() {
        show(mapOf("work.requests" to 3), rail = false)
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        badge().assertCountEquals(1)
        compose.onAllNodes(hasText("3") and hasAnyAncestor(requestsRow), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theWideScreensPermanentDrawerShowsItWithoutOpeningAnything() {
        show(mapOf("work.requests" to 12), rail = true)
        compose.waitForIdle()
        compose.onAllNodes(hasText("12") and hasAnyAncestor(requestsRow), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun noCountMeansNoBadge() {
        show(mapOf("work.requests" to 0), rail = true)
        compose.waitForIdle()
        assertEquals(0, badge().fetchSemanticsNodes().size)
    }

    @Test
    fun aLargeCountIsCapped() {
        show(mapOf("work.requests" to 250), rail = true)
        compose.waitForIdle()
        compose.onAllNodes(hasText("99+") and hasAnyAncestor(requestsRow), useUnmergedTree = true).assertCountEquals(1)
    }
}
