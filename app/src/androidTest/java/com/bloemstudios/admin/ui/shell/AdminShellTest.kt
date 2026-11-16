package com.bloemstudios.admin.ui.shell

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.R
import com.bloemstudios.admin.servers.AdminGateContent
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminGateState
import com.bloemstudios.admin.servers.SingleServerAdminShell
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun swipePage(left: Boolean) {
        compose.onNodeWithTag("shell-page").performTouchInput { if (left) swipeLeft() else swipeRight() }
        compose.waitForIdle()
    }

    @Test
    fun swipingThePageWalksThePagesInDrawerOrderWithoutWrapping() {
        var selected by mutableStateOf("live.streams")
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                    { selected = it }, false, {},
                ) { androidx.compose.material3.Text("feature:$it", Modifier.fillMaxSize()) }
            }
        }
        // Drawer order: dashboard, now playing, logs, audit (the web keeps it with Logs), libraries, requests, users, ...
        swipePage(left = true)
        compose.runOnIdle { assertEquals("system.logs", selected) }
        swipePage(left = true)
        compose.runOnIdle { assertEquals("system.audit", selected) }
        swipePage(left = true)
        compose.runOnIdle { assertEquals("content.libraries", selected) }
        swipePage(left = false)
        compose.runOnIdle { assertEquals("system.audit", selected) }
        swipePage(left = false)
        compose.runOnIdle { assertEquals("system.logs", selected) }
        swipePage(left = false)
        compose.runOnIdle { assertEquals("live.streams", selected) }
        swipePage(left = false)
        compose.runOnIdle { assertEquals("overview.dashboard", selected) }
        // Nothing before the first page, and a swipe right does not open the drawer either.
        swipePage(left = false)
        compose.runOnIdle { assertEquals("overview.dashboard", selected) }
        compose.onNodeWithTag("shell-destination:overview.dashboard").assertDoesNotExist()
        compose.onNodeWithText("feature:overview.dashboard").assertExists()
    }

    @Test
    fun aLockedShellIgnoresPageSwipes() {
        var selected by mutableStateOf("live.streams")
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                    { selected = it }, false, {},
                ) {
                    ShellInteractionLockEffect(active = true)
                    androidx.compose.material3.Text("feature:$it", Modifier.fillMaxSize())
                }
            }
        }
        swipePage(left = true)
        compose.runOnIdle { assertEquals("live.streams", selected) }
    }

    private fun openDrawer() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
    }

    private fun destination(id: String) = compose.onNodeWithTag(DrawerDestinationTagPrefix + id)

    @Test
    fun compactShellHasATopBarAndNoBottomBarOrMoreSheet() {
        var selected by mutableStateOf("overview.dashboard")
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                    { selected = it }, false, {},
                ) { Text("feature:$it") }
            }
        }
        compose.onNodeWithContentDescription("Open menu").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
        compose.onNodeWithText("feature:overview.dashboard").assertIsDisplayed()
        listOf("More", "Status", "Streams", "Jobs", "History", "Servers", "Add Server").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        // The drawer is not composed until it is opened.
        destination("live.streams").assertDoesNotExist()
        compose.onNodeWithContentDescription("Manage servers").assertDoesNotExist()
    }

    @Test
    fun drawerListsTheWebGroupsInOrderAndSelectingClosesIt() {
        var selected by mutableStateOf("overview.dashboard")
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(true), selected,
                    { selected = it }, false, {},
                ) { Text("feature:$it") }
            }
        }
        openDrawer()
        val groups = listOf("Platform", "Monitor", "Manage", "Configure")
        // A server's menu is the web's platform groups; the Organization group belongs only to an
        // organization's own menu.
        val groupTops = groups.map {
            compose.onNodeWithTag(DrawerGroupTagPrefix + it).assertExists().fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(groups + "Organization", AdminGroup.entries.map { it.name })
        compose.onNodeWithTag(DrawerGroupTagPrefix + "Organization").assertDoesNotExist()
        assertEquals(groupTops.sorted(), groupTops)
        val ids = listOf(
            "people.tenants" to "Organizations", "overview.dashboard" to "Dashboard", "live.streams" to "Now playing",
            "system.logs" to "Logs", "system.audit" to "Audit", "content.libraries" to "Libraries",
            "work.requests" to "Requests", "people.users" to "Users", "live.activity" to "Playback history",
            "system.server" to "Server settings", "work.jobs" to "Scheduled tasks",
        )
        val itemTops = ids.map { (id, label) ->
            destination(id).assertExists().assertHeightIsAtLeast(48.dp)
                .assert(hasText(label)).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(itemTops.sorted(), itemTops)
        destination("overview.dashboard").assertIsSelected()
        destination("live.streams").assertIsNotSelected()

        destination("live.streams").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("live.streams", selected) }
        compose.onNodeWithText("feature:live.streams").assertIsDisplayed()
        compose.onNodeWithText("Now playing").assertIsDisplayed() // the title, now that the drawer is gone
        destination("live.streams").assertDoesNotExist()
    }

    @Test
    fun drawerFootIsManageServersSettingsAndSignOutInThatOrder() {
        var manage = 0
        var settings = 0
        var signedOut = 0
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalAppSettingsRequest provides { settings++ }) {
                    AdminShell(
                        AdminShellContext("Loft", false), singleServerDestinations(false), "overview.dashboard",
                        {}, false, { manage++ },
                        activityScope = ActivityScopeControl(false, "loft", listOf(ActivityScopeOption("loft", "Loft")), {}, {},
                            onSignOut = { signedOut++ }),
                    ) { Text("feature:$it") }
                }
            }
        }
        openDrawer()
        compose.onNodeWithTag(DrawerManageServersTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assert(hasText("Manage servers"))
        compose.onNodeWithTag(DrawerSettingsTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assert(hasText("Settings"))
        compose.onNodeWithTag(DrawerSignOutTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assert(hasText("Sign out"))
        // The old modal-dialog name is gone.
        compose.onNodeWithText("App settings").assertDoesNotExist()
        val tops = listOf(DrawerManageServersTag, DrawerSettingsTag, DrawerSignOutTag).map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Manage servers, Settings, Sign out, top to bottom", tops.sorted(), tops)

        compose.onNodeWithTag(DrawerManageServersTag).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, manage) }
        compose.onNodeWithTag(DrawerManageServersTag).assertDoesNotExist()
        openDrawer()
        compose.onNodeWithTag(DrawerSettingsTag).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(1, manage) }
        compose.onNodeWithTag(DrawerSettingsTag).assertDoesNotExist()
    }

    @Test
    fun signOutAsksFirstNamingTheServerAndCancelChangesNothing() {
        var signedOut = 0
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), "overview.dashboard",
                    {}, false, {},
                    activityScope = ActivityScopeControl(false, "loft", listOf(ActivityScopeOption("loft", "Loft")), {}, {},
                        onSignOut = { signedOut++ }),
                ) { Text("feature:$it") }
            }
        }
        openDrawer()
        compose.onNodeWithTag(DrawerSignOutTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sign out of Loft?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, signedOut) }
        compose.onNodeWithText("Sign out of Loft?").assertDoesNotExist()

        openDrawer()
        compose.onNodeWithTag(DrawerSignOutTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SignOutConfirmTag).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, signedOut) }
    }

    @Test
    fun signOutOnAllServersNamesAllOfThem() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("All servers", true), singleServerDestinations(false), "overview.dashboard",
                    {}, false, {},
                    activityScope = ActivityScopeControl(true, null, listOf(ActivityScopeOption("loft", "Loft")), {}, {}, onSignOut = {}),
                ) { Text("feature:$it") }
            }
        }
        openDrawer()
        compose.onNodeWithTag(DrawerSignOutTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sign out of all servers?").assertIsDisplayed()
    }

    @Test
    fun settingsAndSignOutAreLeftOutWhereTheHostProvidesNone() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Loft", false), singleServerDestinations(false),
                    "overview.dashboard", {}, false, {}) { Text(it) }
            }
        }
        openDrawer()
        compose.onNodeWithTag(DrawerSettingsTag).assertDoesNotExist()
        compose.onNodeWithTag(DrawerSignOutTag).assertDoesNotExist()
        compose.onNodeWithTag(DrawerManageServersTag).assertIsDisplayed()
    }

    @Test
    fun backClosesAnOpenDrawerBeforeAnythingElse() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Loft", false), singleServerDestinations(false),
                    "overview.dashboard", {}, false, {}) { Text(it) }
            }
        }
        openDrawer()
        destination("live.streams").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        destination("live.streams").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open menu").assertIsDisplayed()
    }

    @Test
    fun lockedShellDisablesDrawerItemsAndTheContextDropdown() {
        var selections = 0
        var manage = 0
        var locked by mutableStateOf(false)
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalAppSettingsRequest provides {}) {
                    AdminShell(
                        AdminShellContext("Loft", false), singleServerDestinations(false), "overview.dashboard",
                        { selections++ }, false, { manage++ },
                        activityScope = ActivityScopeControl(false, "loft",
                            listOf(ActivityScopeOption("loft", "Loft")), {}, {}, onSignOut = {}),
                    ) {
                        ShellInteractionLockEffect(active = locked)
                        Text(it)
                    }
                }
            }
        }
        openDrawer()
        destination("live.streams").assertIsEnabled()
        compose.runOnIdle { locked = true }
        compose.waitForIdle()
        listOf(DrawerDestinationTagPrefix + "live.streams", DrawerDestinationTagPrefix + "overview.dashboard",
            DrawerManageServersTag, DrawerSettingsTag, DrawerSignOutTag).forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(0, selections); assertEquals(0, manage) }
        compose.onNodeWithContentDescription("Change server context from Loft").assertIsNotEnabled()
        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        destination("live.streams").assertIsEnabled()
    }

    @Test
    fun topBarIsOneRowAtMostSixtyFourDpWithMenuTitleSearchAndContext() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Loft", false), singleServerDestinations(false),
                    "work.jobs", {}, false, {},
                    activityScope = ActivityScopeControl(false, "loft",
                        listOf(ActivityScopeOption("loft", "Loft")), {}, {}),
                    statusContent = { Box(Modifier.testTag("status")) }) { Text(it) }
            }
        }
        val menu = compose.onNodeWithContentDescription("Open menu").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Scheduled tasks").fetchSemanticsNode().boundsInRoot
        val context = compose.onNodeWithContentDescription("Change server context from Loft")
            .fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val search = compose.onNodeWithContentDescription("Search pages").fetchSemanticsNode().boundsInRoot
        assertTrue("menu, title, search, context share one row, in that order",
            menu.right <= title.left + 1 && title.right <= search.left + 1 && search.right <= context.left + 1 &&
                maxOf(menu.top, title.top, search.top, context.top) <
                minOf(menu.bottom, title.bottom, search.bottom, context.bottom))
        assertTrue("search is a 48dp target", (search.bottom - search.top) / compose.density.density >= 48f)
        val barBottom = minOf(
            compose.onNodeWithTag("status").fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithText("work.jobs").fetchSemanticsNode().boundsInRoot.top,
        )
        val barTop = compose.onNodeWithContentDescription("Open menu").fetchSemanticsNode().boundsInRoot.top
        val heights = maxOf(menu.bottom, title.bottom, context.bottom) - minOf(menu.top, title.top, context.top)
        assertTrue("bar content is ${heights / density}dp", heights / density <= 64f)
        assertTrue("bar is ${(barBottom - barTop) / density}dp", (barBottom - barTop) / density <= 64f + 8f)
    }

    @Test
    fun wideShellKeepsTheDrawerOpenWithoutAMenuButton() {
        var selected by mutableStateOf("overview.dashboard")
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalAppSettingsRequest provides {}) {
                    AdminShell(AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                        { selected = it }, true, {},
                        activityScope = ActivityScopeControl(false, "loft", listOf(ActivityScopeOption("loft", "Loft")), {}, {}, onSignOut = {}),
                    ) { Text("feature:$it") }
                }
            }
        }
        compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
        destination("overview.dashboard").assertIsDisplayed().assertIsSelected()
        destination("work.jobs").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("work.jobs", selected) }
        compose.onNodeWithText("feature:work.jobs").assertIsDisplayed()
        destination("work.jobs").assertIsSelected()
        // The foot (Manage servers, Settings, Sign out) stays pinned below the scrolling destinations.
        compose.onNodeWithTag(DrawerManageServersTag).assertIsDisplayed()
        compose.onNodeWithTag(DrawerSettingsTag).assertIsDisplayed()
    }

    @Test
    fun conditionalOrganizationsIsTheOnlyPlatformPageAddedAfterProbeSuccess() {
        assertEquals(
            emptyList<String>(),
            singleServerDestinations(false).filter { it.group == AdminGroup.Platform }.map { it.id },
        )
        assertEquals(
            listOf("people.tenants"),
            singleServerDestinations(true).filter { it.group == AdminGroup.Platform }.map { it.id },
        )
    }

    @Test
    fun needsLoginSuppressesAuthorizedShellAndBackUsesTheSharedCallback() {
        var backRequests = 0
        val backLabel = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.veil_back_to_servers)

        compose.setContent {
            VeilAdminTheme {
                AdminGateContent(
                    state = AdminGateState.NeedsLogin(),
                    authorizedContent = { Text("authorized-shell") },
                    loginContent = { Text("re-login-content") },
                    onBackToServers = { backRequests += 1 },
                )
            }
        }

        compose.onNodeWithText("authorized-shell").assertDoesNotExist()
        compose.onNodeWithText("re-login-content").assertIsDisplayed()
        compose.onNodeWithText(backLabel)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, backRequests) }
    }

    @Test
    fun noAccessAndCantReachPagesOfferRetryAndBackToServers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val retryLabel = context.getString(R.string.action_retry)
        val backLabel = context.getString(R.string.veil_back_to_servers)
        var state by mutableStateOf<AdminGateState>(AdminGateState.Unauthorized)
        var retries = 0
        var backs = 0
        compose.setContent {
            VeilAdminTheme {
                AdminGateContent(
                    state = state,
                    authorizedContent = { Text("authorized-shell") },
                    loginContent = { Text("re-login-content") },
                    onBackToServers = { backs += 1 },
                    onRetry = { retries += 1 },
                )
            }
        }
        for (page in listOf<AdminGateState>(AdminGateState.Unauthorized, AdminGateState.Failed(ErrorText.Raw("Gateway trouble")))) {
            retries = 0; backs = 0
            compose.runOnIdle { state = page }
            compose.onNodeWithText(retryLabel).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(backLabel).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle { assertEquals("retry on $page", 1, retries); assertEquals("back on $page", 1, backs) }
        }
    }

    @Test
    fun authorizedGateInvokesOnlyAuthorizedContent() {
        compose.setContent {
            VeilAdminTheme {
                AdminGateContent(
                    state = AdminGateState.Authorized,
                    authorizedContent = { Text("authorized-shell") },
                    loginContent = { Text("re-login-content") },
                    onBackToServers = {},
                )
            }
        }

        compose.onNodeWithText("authorized-shell").assertIsDisplayed()
        compose.onNodeWithText("re-login-content").assertDoesNotExist()
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.veil_back_to_servers),
        ).assertDoesNotExist()
    }

    @Test
    fun generationRecreatesFeatureContentButRetainsSelectedDestination() {
        var generation by mutableStateOf(1L)
        val created = mutableListOf<Pair<Long, String>>()
        val disposed = mutableListOf<Pair<Long, String>>()

        compose.setContent {
            VeilAdminTheme {
                SingleServerGateHarness(
                    generation = generation,
                    tenantsVisible = true,
                    onRendered = { destination ->
                        val generationAtCreation = generation
                        DisposableEffect(Unit) {
                            created += generationAtCreation to destination
                            onDispose { disposed += generationAtCreation to destination }
                        }
                    },
                )
            }
        }

        openDrawer()
        destination("people.tenants").performClick()
        compose.onNodeWithText("feature:1:people.tenants").assertIsDisplayed()
        compose.runOnIdle { generation = 2L }
        compose.onNodeWithText("feature:2:people.tenants").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(1L to "people.tenants", 2L to "people.tenants"), created.takeLast(2))
            assertEquals(1, disposed.count { it == 1L to "people.tenants" })
        }
    }

    @Test
    fun tenantRemovalNormalizesBeforeDispatchAndContextUsesSharedCallback() {
        var tenantsVisible by mutableStateOf(true)
        var contextRequests = 0
        val rendered = mutableListOf<String>()
        val selections = mutableListOf<String>()

        compose.setContent {
            VeilAdminTheme {
                SingleServerGateHarness(
                    generation = 1L,
                    tenantsVisible = tenantsVisible,
                    onContextRequested = { contextRequests += 1 },
                    onSelectionChanged = { selections += it },
                    onRendered = { destination -> rendered += destination },
                )
            }
        }

        openDrawer()
        destination("people.tenants").performClick()
        compose.onNodeWithText("feature:1:people.tenants").assertIsDisplayed()
        compose.runOnIdle {
            rendered.clear()
            tenantsVisible = false
        }
        // Organizations sat alone in Platform: with it gone the shell opens on Now playing.
        compose.onNodeWithText("feature:1:live.streams").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(rendered.contains("people.tenants"))
            assertEquals("live.streams", selections.last())
        }
        compose.onNodeWithContentDescription("Change server context from Loft").performClick()
        compose.runOnIdle { assertEquals(1, contextRequests) }
    }

    @Test
    fun coldStartRemovedTenantFallsBackToNowPlayingBeforeFirstFeatureDispatch() {
        val rendered = mutableListOf<String>()
        val selections = mutableListOf<String>()

        compose.setContent {
            VeilAdminTheme {
                SingleServerGateHarness(
                    generation = 1L,
                    tenantsVisible = false,
                    initialSelectedDestinationId = "people.tenants",
                    onSelectionChanged = { selections += it },
                    onRendered = { destination -> rendered += destination },
                )
            }
        }

        compose.onNodeWithText("feature:1:live.streams").assertIsDisplayed()
        compose.onNodeWithText("feature:1:overview.dashboard").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("live.streams", rendered.first())
            assertEquals("live.streams", selections.last())
        }
    }

    @Test
    fun wideShellListsSupportedAggregateDestinationsByName() {
        var selected by mutableStateOf("live.streams")
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("All servers", true), aggregateDestinations(),
                    selected, { selected = it }, true, {}) { Text(it) }
            }
        }
        listOf(
            "live.streams" to "Now playing", "overview.dashboard" to "Dashboard", "live.activity" to "Playback history",
            "work.requests" to "Requests", "work.jobs" to "Scheduled tasks", "people.users" to "Users",
            "system.server" to "Server settings", "system.audit" to "Audit",
        ).forEach { (id, label) ->
            destination(id).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assert(hasText(label))
        }
        destination("system.logs").assertDoesNotExist()
        destination("people.tenants").assertDoesNotExist()
        destination("work.jobs").performScrollTo().performClick()
        compose.onNodeWithText("work.jobs").assertIsDisplayed()
        destination("work.jobs").assertIsSelected()
    }

    @Test
    fun drawerSelectionCanReturnAndRemovedDestinationsDisappear() {
        var selected by mutableStateOf("live.streams")
        var destinations by mutableStateOf(singleServerDestinations(false))
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Loft", false), destinations, selected,
                    { selected = it }, false, {}) { Text(it) }
            }
        }
        // The drawer is a scrolling list: on a small screen the page to tap or check may be past its edge.
        openDrawer()
        destination("live.activity").performScrollTo().performClick()
        compose.onNodeWithText("live.activity").assertIsDisplayed()
        openDrawer()
        destination("work.jobs").performScrollTo().performClick()
        openDrawer()
        destination("live.activity").performScrollTo().performClick()
        compose.onNodeWithText("live.activity").assertIsDisplayed()
        compose.runOnIdle { destinations = destinations.filterNot { it.id == "live.activity" } }
        compose.waitForIdle()
        openDrawer()
        destination("live.activity").assertDoesNotExist()
        destination("live.streams").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unsupportedExternalSelectionIsNormalizedBeforeContentIsRendered() {
        var selected by mutableStateOf("removed.destination")
        val rendered = mutableListOf<String>()

        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                    { selected = it }, false, {},
                ) {
                    DisposableEffect(it) {
                        rendered += it
                        onDispose {}
                    }
                    androidx.compose.material3.Text(it)
                }
            }
        }

        // An unknown page falls back to the first page, Now Playing.
        compose.onNodeWithText("live.streams").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("live.streams", selected)
            assertEquals(listOf("live.streams"), rendered.distinct())
        }
    }

    @Test
    fun removedSelectedChildDisposesUnsupportedContentImmediatelyAndFallsBackWithinGroup() {
        var selected by mutableStateOf("live.activity")
        var destinations by mutableStateOf(singleServerDestinations(false))
        compose.mainClock.autoAdvance = false

        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), destinations, selected, { selected = it }, false, {},
                ) { androidx.compose.material3.Text("sentinel:$it") }
            }
        }

        compose.onNodeWithText("sentinel:live.activity").assertIsDisplayed()
        compose.runOnIdle { destinations = destinations.filterNot { it.id == "live.activity" } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("sentinel:live.activity").assertDoesNotExist()
        // The page's own group (Manage) is tried first: its first page is Libraries.
        compose.onAllNodesWithText("sentinel:content.libraries").assertCountEquals(1)
        compose.runOnIdle { assertEquals("content.libraries", selected) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("sentinel:content.libraries").assertIsDisplayed()
    }

    @Test
    fun emptyDestinationsDisposePreviouslySelectedContentImmediately() {
        var destinations by mutableStateOf(singleServerDestinations(false))
        compose.mainClock.autoAdvance = false

        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), destinations, "overview.dashboard", {}, false, {},
                ) { androidx.compose.material3.Text("empty-sentinel:$it") }
            }
        }

        compose.onNodeWithText("empty-sentinel:overview.dashboard").assertIsDisplayed()
        compose.runOnIdle { destinations = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("empty-sentinel:overview.dashboard").assertDoesNotExist()
    }

    @Test
    fun explicitContextDisableAndActiveLockBothBlockActivation() {
        var activations = 0
        var contextAllowed by mutableStateOf(true)
        var leaseActive by mutableStateOf(false)
        val description = "Change server context from Loft"

        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), "overview.dashboard", {},
                    false, { activations += 1 }, contextEnabled = contextAllowed,
                    activityScope = ActivityScopeControl(false, "loft",
                        listOf(ActivityScopeOption("loft", "Loft")), { activations++ }, { activations++ },
                        onAddServer = { activations++ }, onSignOut = { activations++ }),
                ) {
                    ShellInteractionLockEffect(active = leaseActive)
                    androidx.compose.material3.Text(it)
                }
            }
        }

        // Allowed: Manage servers in the drawer acts once.
        openDrawer()
        compose.onNodeWithTag(DrawerManageServersTag).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, activations); contextAllowed = false }
        // Disabled: the switcher does not open, and the drawer's Manage servers and Sign out are off with it.
        compose.onNodeWithContentDescription(description).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(ServerSwitcherSheetTag).assertDoesNotExist()
        openDrawer()
        compose.onNodeWithTag(DrawerManageServersTag).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(DrawerSignOutTag).assertIsNotEnabled().performClick()
        destination("live.streams").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, activations) }
        compose.runOnIdle {
            contextAllowed = true
            leaseActive = true
        }
        compose.waitForIdle()
        // A lease holds the whole shell: nothing in it activates.
        compose.onNodeWithContentDescription(description)
            .assertIsNotEnabled().performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag(DrawerManageServersTag).fetchSemanticsNodes().isEmpty()) openDrawer()
        compose.onNodeWithTag(DrawerManageServersTag).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(DrawerSignOutTag).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(ServerSwitcherSheetTag).assertDoesNotExist()
        compose.onNodeWithText("All servers").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun overlappingLeasesAndSelectedContentDisposalKeepContextLockedUntilFinalRelease() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        val description = "Change server context from Loft"

        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    AdminShellContext("Loft", false), singleServerDestinations(false), "overview.dashboard", {},
                    false, {},
                ) {
                    ShellInteractionLockEffect(first)
                    ShellInteractionLockEffect(second)
                    androidx.compose.material3.Text(it)
                }
            }
        }

        compose.onNodeWithContentDescription(description).assertIsNotEnabled()
        compose.runOnIdle { first = false }
        compose.onNodeWithContentDescription(description).assertIsNotEnabled()
        compose.runOnIdle { second = false }
        compose.onNodeWithContentDescription(description).assertHasClickAction()
    }

    @Test
    fun outgoingSelectedContentReleasesItsLeaseWhenMotionDisposesIt() {
        var selected by mutableStateOf("overview.dashboard")
        val description = "Change server context from Loft"
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides false) {
                VeilAdminTheme {
                    AdminShell(
                        AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                        { selected = it }, false, {},
                    ) {
                        ShellInteractionLockEffect(active = it == "overview.dashboard")
                        androidx.compose.material3.Text(it)
                    }
                }
            }
        }

        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(description).assertIsNotEnabled()
        compose.runOnIdle { selected = "live.streams" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(description).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithContentDescription(description).assertHasClickAction()
    }

    @Test
    fun statusAndHeaderRemainAvailableAtDoubleFontScaleWithMinimumTargets() {
        var selected by mutableStateOf("live.streams")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                VeilAdminTheme {
                    AdminShell(
                        AdminShellContext("A very long production server name", false),
                        singleServerDestinations(false), selected, { selected = it }, false, {},
                        statusContent = { androidx.compose.material3.Text("Operational") },
                    ) { androidx.compose.material3.Text(it) }
                }
            }
        }

        compose.onNodeWithText("A very long production server name").assertIsDisplayed()
        compose.onNodeWithText("Operational").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Change server context from A very long production server name")
            .assertHeightIsAtLeast(48.dp)
        openDrawer()
        listOf("work.jobs", "people.users").forEach { destination(it).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        destination("work.jobs").performClick()
        compose.runOnIdle { assertEquals("work.jobs", selected) }
        compose.onNodeWithText("work.jobs").assertIsDisplayed()
    }

    @Test
    fun reducedMotionReplacesImmediatelyWhileNormalMotionRetainsOutgoingBriefly() {
        var selected by mutableStateOf("overview.dashboard")
        var reduced by mutableStateOf(false)
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides reduced) {
                VeilAdminTheme {
                    AdminShell(
                        AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                        { selected = it }, false, {},
                    ) { androidx.compose.material3.Text(it) }
                }
            }
        }

        compose.runOnIdle { selected = "live.streams" }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText("overview.dashboard").assertCountEquals(1)
        compose.onAllNodesWithText("live.streams").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("overview.dashboard").assertDoesNotExist()

        compose.runOnIdle {
            reduced = true
            selected = "work.jobs"
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("live.streams").assertDoesNotExist()
        compose.onNodeWithText("work.jobs").assertIsDisplayed()
    }

    @Test
    fun rapidNormalMotionSelectionSettlesOnlyOnLatestDestination() {
        var selected by mutableStateOf("overview.dashboard")
        compose.mainClock.autoAdvance = false

        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides false) {
                VeilAdminTheme {
                    AdminShell(
                        AdminShellContext("Loft", false), singleServerDestinations(false), selected,
                        { selected = it }, false, {},
                    ) { androidx.compose.material3.Text("rapid:$it") }
                }
            }
        }

        compose.runOnIdle { selected = "live.streams" }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { selected = "work.jobs" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("rapid:work.jobs").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("rapid:overview.dashboard").assertDoesNotExist()
        compose.onNodeWithText("rapid:live.streams").assertDoesNotExist()
        compose.onAllNodesWithText("rapid:work.jobs").assertCountEquals(1)
    }

    @Test
    fun injectedInsetsAreAppliedOnceForCompactAndRail() {
        var compactZeroTop = -1
        var compactInsetTop = -1
        var railZeroTop = -1
        var railInsetTop = -1

        compose.setContent {
            VeilAdminTheme {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            AdminShellLayout(
                                context = AdminShellContext("Loft", false),
                                destinations = singleServerDestinations(false),
                                selectedDestinationId = "overview.dashboard",
                                onDestinationSelected = {},
                                useNavigationRail = false,
                                onContextRequested = {},
                                statusContent = null,
                                contextEnabled = true,
                                shellWindowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned {
                                    compactZeroTop = it.positionInRoot().y.toInt()
                                })
                            }
                        }
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            AdminShellLayout(
                                context = AdminShellContext("Loft", false),
                                destinations = singleServerDestinations(false),
                                selectedDestinationId = "overview.dashboard",
                                onDestinationSelected = {},
                                useNavigationRail = false,
                                onContextRequested = {},
                                statusContent = null,
                                contextEnabled = true,
                                shellWindowInsets = WindowInsets(top = 24.dp),
                            ) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned {
                                    compactInsetTop = it.positionInRoot().y.toInt()
                                })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            AdminShellLayout(
                                context = AdminShellContext("Loft", false),
                                destinations = singleServerDestinations(false),
                                selectedDestinationId = "overview.dashboard",
                                onDestinationSelected = {},
                                useNavigationRail = true,
                                onContextRequested = {},
                                statusContent = null,
                                contextEnabled = true,
                                shellWindowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned {
                                    railZeroTop = it.positionInRoot().y.toInt()
                                })
                            }
                        }
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            AdminShellLayout(
                                context = AdminShellContext("Loft", false),
                                destinations = singleServerDestinations(false),
                                selectedDestinationId = "overview.dashboard",
                                onDestinationSelected = {},
                                useNavigationRail = true,
                                onContextRequested = {},
                                statusContent = null,
                                contextEnabled = true,
                                shellWindowInsets = WindowInsets(top = 24.dp),
                            ) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned {
                                    railInsetTop = it.positionInRoot().y.toInt()
                                })
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val expectedInsetPx = with(compose.density) { 24.dp.roundToPx() }
        assertEquals(expectedInsetPx, compactInsetTop - compactZeroTop)
        assertEquals(expectedInsetPx, railInsetTop - railZeroTop)
    }
}

@Composable
private fun SingleServerGateHarness(
    generation: Long,
    tenantsVisible: Boolean,
    initialSelectedDestinationId: String = "overview.dashboard",
    onContextRequested: () -> Unit = {},
    onSelectionChanged: (String) -> Unit = {},
    onRendered: @Composable (destinationId: String) -> Unit,
) {
    var selectedDestinationId by rememberSaveable {
        mutableStateOf(initialSelectedDestinationId)
    }

    AdminGateContent(
        state = AdminGateState.Authorized,
        authorizedContent = {
            SingleServerAdminShell(
                context = AdminShellContext("Loft", false),
                principalGeneration = generation,
                tenantsVisible = tenantsVisible,
                selectedDestinationId = selectedDestinationId,
                onSelectedDestinationChanged = {
                    selectedDestinationId = it
                    onSelectionChanged(it)
                },
                useNavigationRail = false,
                onContextRequested = onContextRequested,
            ) { destinationId ->
                onRendered(destinationId)
                Text("feature:$generation:$destinationId")
            }
        },
        loginContent = { Text("test-login") },
        onBackToServers = onContextRequested,
    )
}
