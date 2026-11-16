package com.bloemstudios.admin.ui.shell

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerContextHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dropdownListsEachServersOrganizationsUnderItAndOpensOne() {
        val opened = mutableListOf<ScopeOrganization>()
        val servers = mutableListOf<String>()
        val acme = ScopeOrganization("loft", "org-1", "Acme Media")
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(
                    AdminShellContext("Acme Media", false),
                    activityScope = ActivityScopeControl(false, "loft",
                        listOf(ActivityScopeOption("loft", "Loft"), ActivityScopeOption("den", "Den")),
                        {}, { servers += it },
                        organizations = listOf(acme, ScopeOrganization("den", "org-2", "Den Club")),
                        selectedOrganizationId = "org-1",
                        onSelectOrganization = { opened += it }),
                    onClick = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Change server context from Acme Media").performClick()
        val tops = listOf("Loft", "Acme Media", "Den", "Den Club").map { label ->
            compose.onAllNodesWithText(label).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        }
        assertEquals("organizations sit under their server", tops.sorted(), tops)
        compose.onAllNodesWithText("Den Club")[0].performClick()
        compose.runOnIdle {
            assertEquals(listOf(ScopeOrganization("den", "org-2", "Den Club")), opened)
            assertEquals(emptyList<String>(), servers)
        }
        compose.onNodeWithContentDescription("Change server context from Acme Media").performClick()
        compose.onAllNodesWithText("Loft")[0].performClick()
        compose.runOnIdle { assertEquals(listOf("loft"), servers) }
    }

    @Test
    fun dropdownPicksAServerOrAllServersAndKeepsDuplicateNamesDistinct() {
        val selections = mutableListOf<String>()
        var allSelections = 0
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(
                    AdminShellContext("All servers", true),
                    activityScope = ActivityScopeControl(true, null,
                        listOf(ActivityScopeOption("a", "Same"), ActivityScopeOption("b", "Same")),
                        { allSelections++ }, { selections += it }), onClick = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Change server context from All servers").performClick()
        compose.onAllNodesWithText("Same").assertCountEquals(2)
        compose.onAllNodesWithText("Same")[1].performClick()
        compose.runOnIdle { assertEquals(listOf("b"), selections) }
        compose.onAllNodesWithText("Same").assertCountEquals(0)

        compose.onNodeWithContentDescription("Change server context from All servers").performClick()
        compose.onAllNodesWithText("All servers")[1].performClick()
        compose.runOnIdle { assertEquals(1, allSelections) }
    }

    @Test
    fun theSwitcherIsNameAndChevronAndItsSheetMarksTheCurrentOneAndEndsWithAddServer() {
        val calls = mutableListOf<String>()
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(
                    AdminShellContext("Loft", false),
                    activityScope = ActivityScopeControl(false, "loft",
                        listOf(ActivityScopeOption("loft", "Loft"), ActivityScopeOption("den", "Den")),
                        { calls += "all" }, { calls += "server:$it" },
                        onAddServer = { calls += "add" }),
                    onClick = {},
                )
            }
        }
        compose.onNodeWithTag(ServerSwitcherTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(ServerSwitcherSheetTag).assertDoesNotExist()
        compose.onNodeWithTag(ServerSwitcherTag).performClick()
        compose.onNodeWithTag(ServerSwitcherSheetTag).assertIsDisplayed()
        // The current one is marked; the others are not.
        compose.onNodeWithTag(ServerSwitcherRowTagPrefix + "loft").assertIsSelected()
        compose.onNodeWithTag(ServerSwitcherRowTagPrefix + "den").assertIsNotSelected()
        compose.onNodeWithTag(ServerSwitcherRowTagPrefix + "all").assertIsNotSelected()
        // Add server is the last row, under the servers. Manage servers is not here: it is in the drawer's foot.
        val den = compose.onNodeWithTag(ServerSwitcherRowTagPrefix + "den").fetchSemanticsNode().boundsInRoot.bottom
        val add = compose.onNodeWithTag(ServerSwitcherAddTag).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertTrue("Add server sits under the servers", add.top >= den)
        compose.onNodeWithText("Add server").assertIsDisplayed()
        compose.onNodeWithText("Manage servers").assertDoesNotExist()

        // A row closes the sheet, then acts, once.
        compose.onNodeWithTag(ServerSwitcherAddTag).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("add"), calls) }
        compose.onNodeWithTag(ServerSwitcherSheetTag).assertDoesNotExist()
    }

    @Test
    fun theSheetLeavesOutAddServerWhereTheHostOffersNone() {
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("Loft", false),
                    activityScope = ActivityScopeControl(false, "loft", listOf(ActivityScopeOption("loft", "Loft")), {}, {}),
                    onClick = {})
            }
        }
        compose.onNodeWithTag(ServerSwitcherTag).performClick()
        compose.onNodeWithTag(ServerSwitcherAddTag).assertDoesNotExist()
    }

    @Test
    fun headerIsOneRowWithoutAddServer() {
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("All servers", true),
                    activityScope = ActivityScopeControl(true, null,
                        listOf(ActivityScopeOption("a", "Loft")), {}, {}), onClick = {})
            }
        }
        val bounds = compose.onRoot().getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("header is $height tall", height <= 64.dp)
        compose.onNodeWithText("Add a Silo server").assertDoesNotExist()
        compose.onNodeWithText("Select server").assertDoesNotExist()
    }

    @Test
    fun emptyAndLockedScopeControlsCannotChangeScope() {
        var enabled by androidx.compose.runtime.mutableStateOf(true)
        var selections = 0
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("All servers", true), enabled,
                    activityScope = ActivityScopeControl(true, null, emptyList(), { selections++ }, {}), onClick = {})
            }
        }
        compose.onNodeWithContentDescription("Change server context from All servers").performClick()
        compose.onAllNodesWithText("All servers")[1].assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, selections); enabled = false }
        compose.onNodeWithContentDescription("Change server context from All servers").assertIsNotEnabled()
        compose.onAllNodesWithText("All servers").assertCountEquals(1)
    }

    @Test
    fun noGearOnTheHeaderAndManagementIsOnlyReachedFromTheDrawer() {
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("Test server", false),
                    activityScope = ActivityScopeControl(false, "a", listOf(ActivityScopeOption("a", "Test server")), {}, {}),
                    onClick = {})
            }
        }
        compose.onNodeWithContentDescription("Manage servers").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
    }

    @Test
    fun withoutAScopeControlTheDropdownOpensManagementAndObeysTheLock() {
        var opened = 0
        var enabled by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("Test server", false), enabled, onClick = { opened++ })
            }
        }
        compose.onNodeWithContentDescription("Change server context from Test server").performClick()
        compose.runOnIdle { assertEquals(1, opened); enabled = false }
        compose.onNodeWithContentDescription("Change server context from Test server").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun enabledSingleServerContextIsAccessibleAndActivatesOnce() {
        var activations = 0
        val description = "Change server context from Test server"

        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("Test server", isAggregate = false)) {
                    activations += 1
                }
            }
        }

        compose.onNodeWithContentDescription(description, useUnmergedTree = false)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun aggregateContextUsesVeilAllServersLabelAndActivates() {
        var activations = 0
        val description = "Change server context from All servers"

        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("All servers", isAggregate = true)) {
                    activations += 1
                }
            }
        }

        compose.onAllNodesWithText("All servers", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithContentDescription(description, useUnmergedTree = false)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun disabledContextIsLegibleAndDoesNotActivate() {
        var activations = 0
        val description = "Change server context from Test server"

        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(
                    context = AdminShellContext("Test server", isAggregate = false),
                    enabled = false,
                ) {
                    activations += 1
                }
            }
        }

        compose.onNodeWithContentDescription(description, useUnmergedTree = false)
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertEquals(0, activations) }
    }

    @Test
    fun headerCarriesTheScreenTitleAndTheDropdownButNoMenuOrSearch() {
        compose.setContent {
            VeilAdminTheme {
                ServerContextHeader(AdminShellContext("Loft", false), title = "Checking access", onClick = {})
            }
        }
        compose.onNodeWithText("Checking access").assertIsDisplayed()
        compose.onNodeWithContentDescription("Change server context from Loft").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        // There is no navigation to open here, so neither the menu nor the page search is offered.
        compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search pages").assertDoesNotExist()
        val title = compose.onNodeWithText("Checking access").fetchSemanticsNode().boundsInRoot
        val context = compose.onNodeWithContentDescription("Change server context from Loft")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("title and dropdown share one row, title first", title.right <= context.left + 1 &&
            maxOf(title.top, context.top) < minOf(title.bottom, context.bottom))
        val bounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("header is ${bounds.bottom - bounds.top} tall", bounds.bottom - bounds.top <= 64.dp)
    }

    @Test
    fun headerIsTheSameRowTheShellUsesWithTheSameTitleStyle() {
        compose.setContent {
            VeilAdminTheme {
                androidx.compose.foundation.layout.Column {
                    ServerContextHeader(AdminShellContext("Loft", false), title = "Sign in", onClick = {})
                    ShellTopBar("Dashboard", AdminShellContext("Loft", false), true, null, {}, null)
                }
            }
        }
        val rows = compose.onAllNodesWithContentDescription("Change server context from Loft").fetchSemanticsNodes()
        assertEquals(2, rows.size)
        // Same height, and the dropdown sits at the same distance from the end of the row.
        val heights = listOf("Sign in", "Dashboard").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.let { b -> b.bottom - b.top }
        }
        assertEquals(heights[0], heights[1], 0.5f)
        assertEquals(rows[0].boundsInRoot.right, rows[1].boundsInRoot.right, 0.5f)
        assertEquals(rows[0].boundsInRoot.bottom - rows[0].boundsInRoot.top,
            rows[1].boundsInRoot.bottom - rows[1].boundsInRoot.top, 0.5f)
    }
}

@RunWith(AndroidJUnit4::class)
class GateTopBarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: com.bloemstudios.admin.servers.AdminGateState, onBack: () -> Unit = {}) {
        compose.setContent {
            VeilAdminTheme {
                com.bloemstudios.admin.servers.AdminGateHeader(state, "Loft", null, onBack)
            }
        }
    }

    @Test
    fun everyGateScreenShowsItsTitleAndTheServerDropdownInOneRow() {
        val states = listOf(
            com.bloemstudios.admin.servers.AdminGateState.Checking to "Checking access",
            com.bloemstudios.admin.servers.AdminGateState.NeedsLogin() to "Sign in",
            com.bloemstudios.admin.servers.AdminGateState.OrganizationOnly(emptyList()) to "Organizations",
            com.bloemstudios.admin.servers.AdminGateState.Unauthorized to "No access",
            com.bloemstudios.admin.servers.AdminGateState.Failed(
                com.bloemstudios.admin.network.ErrorText.Res(com.bloemstudios.admin.R.string.error_admin_gate_unreachable),
            ) to "Can't reach server",
        )
        var shown by androidx.compose.runtime.mutableStateOf<com.bloemstudios.admin.servers.AdminGateState>(states[0].first)
        compose.setContent {
            VeilAdminTheme {
                com.bloemstudios.admin.servers.AdminGateHeader(shown, "Loft", null) {}
            }
        }
        states.forEach { (state, title) ->
            compose.runOnIdle { shown = state }
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithContentDescription("Change server context from Loft").assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
            val bounds = compose.onRoot().getUnclippedBoundsInRoot()
            assertTrue("$title bar is ${bounds.bottom - bounds.top} tall", bounds.bottom - bounds.top <= 64.dp)
        }
    }

    @Test
    fun theDropdownStillSendsYouBackToTheServerList() {
        var back = 0
        show(com.bloemstudios.admin.servers.AdminGateState.Checking) { back++ }
        compose.onNodeWithContentDescription("Change server context from Loft").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test
    fun nothingIsDrawnOnceAccessIsGrantedBecauseTheShellBringsItsOwnBar() {
        show(com.bloemstudios.admin.servers.AdminGateState.Authorized)
        compose.onNodeWithContentDescription("Change server context from Loft").assertDoesNotExist()
    }

    /** The page title in the top bar is titleLarge, 20/28. */
    @Test
    fun theTopBarTitleIsTwentySp() {
        compose.setContent {
            VeilAdminTheme { ServerContextHeader(AdminShellContext("Loft", false), title = "Sign in", onClick = {}) }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Sign in")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertEquals(20f, style.fontSize.value, 0f)
        assertEquals(28f, style.lineHeight.value, 0f)
    }
}
