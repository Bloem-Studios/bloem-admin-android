package com.bloemstudios.admin.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VeilStateSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingIsFixedStaticSkeletonWithoutCopyOrAction() {
        var clicks = 0
        compose.setContent {
            VeilAdminTheme {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Loading,
                    title = "Hidden loading title",
                    message = "Hidden loading message",
                    stateDescription = "Loading records",
                    action = VeilStateSurfaceAction("Hidden retry") { clicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("state-loading")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(192.dp)
            .assertStateDescription("Loading records")
        val loadingBars = compose.onAllNodes(
            SemanticsMatcher("has loading-bar-prefixed test tag") { node ->
                node.config.contains(SemanticsProperties.TestTag) &&
                    node.config[SemanticsProperties.TestTag].startsWith("state-loading-bar-")
            },
        )
        loadingBars.assertCountEquals(3)
        compose.onNodeWithTag("state-loading-bar-1").assertIsDisplayed()
        compose.onNodeWithTag("state-loading-bar-2").assertIsDisplayed()
        compose.onNodeWithTag("state-loading-bar-3").assertIsDisplayed()
        val widths = loadingBars.fetchSemanticsNodes().map { it.boundsInRoot.width }
        assertTrue(widths.all { it > 0f })
        assertEquals(3, widths.toSet().size)
        compose.onNodeWithText("Hidden loading title").assertDoesNotExist()
        compose.onNodeWithText("Hidden loading message").assertDoesNotExist()
        compose.onNodeWithText("Hidden retry").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun emptyRendersAccessibleCopyWithoutAnAction() {
        compose.setContent {
            VeilAdminTheme {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Empty,
                    title = "Nothing here",
                    message = "Records will appear here.",
                    stateDescription = "No records",
                )
            }
        }

        compose.onNodeWithTag("state-empty")
            .assertIsDisplayed()
            .assertStateDescription("No records")
        compose.onNodeWithText("Nothing here", useUnmergedTree = true)
            .assert(isHeading())
            .assertIsDisplayed()
        compose.onNodeWithText("Records will appear here.").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun failureActionMeetsTouchTargetAndForwardsOneClick() {
        var clicks = 0
        compose.setContent {
            VeilAdminTheme {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Failure,
                    title = "Could not load",
                    message = "Check the connection.",
                    stateDescription = "Load failed",
                    action = VeilStateSurfaceAction("Try again") { clicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("state-failure")
            .assertIsDisplayed()
            .assertStateDescription("Load failed")
        compose.onNodeWithText("Could not load", useUnmergedTree = true)
            .assert(isHeading())
            .assertIsDisplayed()
        compose.onNodeWithText("Check the connection.").assertIsDisplayed()
        compose.onNodeWithText("Try again")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun partialFailureHasDistinctCopyAndOptionalActionContract() {
        var clicks = 0
        compose.setContent {
            VeilAdminTheme {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.PartialFailure,
                    title = "Some servers unavailable",
                    message = "Healthy results remain visible.",
                    stateDescription = "Partial results",
                    action = VeilStateSurfaceAction("Review servers") { clicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("state-partialfailure")
            .assertIsDisplayed()
            .assertStateDescription("Partial results")
        compose.onNodeWithText("Some servers unavailable", useUnmergedTree = true)
            .assert(isHeading())
            .assertIsDisplayed()
        compose.onNodeWithText("Healthy results remain visible.").assertIsDisplayed()
        compose.onNodeWithText("Review servers")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun recompositionUsesLatestMessageAndCallback() {
        var originalClicks = 0
        var latestClicks = 0
        var currentMessage by mutableStateOf("Original failure")
        var currentCallback by mutableStateOf<() -> Unit>({ originalClicks += 1 })
        compose.setContent {
            VeilAdminTheme {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Failure,
                    title = "Refresh failed",
                    message = currentMessage,
                    stateDescription = "Refresh failed",
                    action = VeilStateSurfaceAction("Retry", currentCallback),
                )
            }
        }

        compose.onNodeWithText("Original failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, originalClicks)
            assertEquals(0, latestClicks)
            currentMessage = "Latest failure"
            currentCallback = { latestClicks += 1 }
        }
        compose.onNodeWithText("Original failure").assertDoesNotExist()
        compose.onNodeWithText("Latest failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, originalClicks)
            assertEquals(1, latestClicks)
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertStateDescription(
        expected: String,
    ) = assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
}
