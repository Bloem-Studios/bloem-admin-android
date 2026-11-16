package com.bloemstudios.admin.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageSearchSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf("overview.dashboard")

    private fun launch(
        destinations: List<AdminDestination> = singleServerDestinations(true),
        wide: Boolean = false,
        locked: Boolean = false,
    ) {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Loft", false), destinations, selected, { selected = it }, wide, {}) {
                    ShellInteractionLockEffect(active = locked)
                    Text("feature:$it")
                }
            }
        }
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription("Search pages").performClick()
        compose.waitForIdle()
    }

    private fun type(text: String) {
        compose.onNodeWithTag(SearchFieldTag).performTextInput(text)
        compose.waitForIdle()
    }

    private fun result(id: String) = compose.onNodeWithTag(SearchResultTagPrefix + id)

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun theTopBarHasASearchButtonThatOpensEveryPageOfTheContext() {
        launch()
        compose.onNodeWithTag(SearchSheetTag).assertDoesNotExist()
        compose.onNodeWithTag(SearchButtonTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        openSearch()
        compose.onNodeWithTag(SearchSheetTag).assertIsDisplayed()
        compose.onNodeWithTag(SearchGroupTagPrefix + "Monitor").assertExists()
        // The list is lazy: bring each page into view before asking for it.
        singleServerDestinations(true).forEach {
            compose.onNodeWithTag(SearchListTag).performScrollToNode(hasTestTag(SearchResultTagPrefix + it.id))
            result(it.id).assertExists()
        }
        compose.onNodeWithTag(SearchListTag).performScrollToNode(hasTestTag(SearchResultTagPrefix + "work.jobs"))
        result("work.jobs").assertHeightIsAtLeast(48.dp)
        // The web's own words: the page, then what it is for.
        compose.onNodeWithText("Background task schedules, runs and history").assertExists()
        compose.onNodeWithTag(SearchCountTag).assertTextEquals("11 pages")
    }

    @Test
    fun typingFindsPagesByLabelDescriptionAndKeyword() {
        launch()
        openSearch()
        type("scheduler") // a keyword: neither the label nor the description says it
        singleServerDestinations(true).filter { it.id != "work.jobs" }.forEach { result(it.id).assertDoesNotExist() }
        result("work.jobs").assertIsDisplayed()
        compose.onNodeWithTag(SearchCountTag).assertTextEquals("1 match")

        compose.onNodeWithContentDescription("Clear search").performClick()
        type("approve") // a word of the description
        result("work.requests").assertIsDisplayed()
        result("work.jobs").assertDoesNotExist()

        compose.onNodeWithContentDescription("Clear search").performClick()
        type("scheduled tasks") // the label
        result("work.jobs").assertIsDisplayed()

        compose.onNodeWithContentDescription("Clear search").performClick()
        type("zzzz")
        compose.onNodeWithTag(SearchEmptyTag).assertIsDisplayed()
        compose.onNodeWithText("No matching pages").assertIsDisplayed()
        compose.onNodeWithTag(SearchCountTag).assertTextEquals("0 matches")
    }

    @Test
    fun pickingAResultNavigatesAndClosesTheSearch() {
        launch()
        openSearch()
        type("transcode")
        result("live.streams").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("live.streams", selected) }
        compose.onNodeWithTag(SearchSheetTag).assertDoesNotExist()
        compose.onNodeWithText("feature:live.streams").assertIsDisplayed()
        compose.onNodeWithText("Now playing").assertIsDisplayed() // the top bar's title
        // The next search starts empty.
        openSearch()
        compose.onNodeWithTag(SearchCountTag).assertTextEquals("11 pages")
    }

    @Test
    fun backClosesTheSearchAndLeavesThePageAlone() {
        launch()
        openSearch()
        type("log")
        result("system.logs").assertIsDisplayed()
        back()
        compose.onNodeWithTag(SearchSheetTag).assertDoesNotExist()
        compose.runOnIdle { assertEquals("overview.dashboard", selected) }
        compose.onNodeWithText("feature:overview.dashboard").assertIsDisplayed()
        // Back out of the closed search is the system's again: the drawer closes on back as before.
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DrawerDestinationTagPrefix + "live.streams").assertIsDisplayed()
        back()
        compose.onNodeWithTag(DrawerDestinationTagPrefix + "live.streams").assertDoesNotExist()
    }

    @Test
    fun theBackArrowAlsoClosesIt() {
        launch()
        openSearch()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SearchSheetTag).assertDoesNotExist()
    }

    @Test
    fun searchOffersOnlyThePagesOfTheCurrentMenu() {
        launch(aggregateDestinations())
        openSearch()
        compose.onNodeWithTag(SearchCountTag).assertTextEquals("12 pages")
        type("server logs") // a keyword of Logs, which All servers does not have
        compose.onNodeWithTag(SearchEmptyTag).assertIsDisplayed()
        result("system.logs").assertDoesNotExist()
    }

    @Test
    fun organizationMenuIsSearchedWithTheWebsOrganizationWords() {
        selected = "organization.overview"
        launch(organizationDestinations())
        openSearch()
        compose.onNodeWithTag(SearchGroupTagPrefix + "Organization").assertExists()
        compose.onNodeWithTag(SearchCountTag).assertTextEquals("8 pages")
        type("denied")
        result("organization.decisions").assertIsDisplayed()
        compose.onAllNodesWithTag(SearchResultTagPrefix + "organization.people").assertCountEquals(0)
        result("organization.decisions").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("organization.decisions", selected) }
        compose.onNodeWithText("feature:organization.decisions").assertIsDisplayed()
    }

    @Test
    fun searchIsOffWhileADialogHasTheShellLocked() {
        launch(locked = true)
        compose.waitForIdle()
        compose.onNodeWithTag(SearchButtonTag).assertIsNotEnabled()
    }

    @Test
    fun searchButtonIsEnabledWhenNothingIsLocked() {
        launch()
        compose.onNodeWithTag(SearchButtonTag).assertIsEnabled()
    }

    @Test
    fun wideShellSearchesToo() {
        launch(wide = true)
        compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
        openSearch()
        type("accounts")
        result("people.users").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("people.users", selected) }
        compose.onNodeWithTag(SearchSheetTag).assertDoesNotExist()
    }
}
