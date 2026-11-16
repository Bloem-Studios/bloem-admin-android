package com.bloemstudios.admin.navigation

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsAtLeast
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.AdminApp
import com.bloemstudios.admin.lock.AppLockPreferences
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.TokenPreferencesOpenResult
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.servers.SensitiveViewModelStoreRegistry
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivityScopeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val fixtureId = UUID.randomUUID().toString()
    private val fixtureContext = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(base.filesDir, "scope-test-$fixtureId").apply { mkdirs() }
    }
    private val prefs = base.getSharedPreferences("scope-test-$fixtureId", Context.MODE_PRIVATE)
    private val tokenPrefs = base.getSharedPreferences("scope-tokens-$fixtureId", Context.MODE_PRIVATE)
    private var store = ServerStore(fixtureContext, tokenPrefs, metadataWriter = {}, metadataReader = { emptyList() })
    private val settings = ActivityScopePreferences(prefs)
    private val sensitiveStores = SensitiveViewModelStoreRegistry()
    private var restoreNavigationState: Bundle? = null
    private val navigationGeneration = mutableStateOf(0)
    private lateinit var nav: NavHostController

    @After fun cleanup() {
        sensitiveStores.clearAll()
        store.close()
        base.deleteSharedPreferences("scope-test-$fixtureId")
        base.deleteSharedPreferences("scope-tokens-$fixtureId")
    }

    private fun seed(count: Int) = runBlocking {
        repeat(count) { index ->
            val id = "scope-$index"
            store.addServer(AdminServer(id, "https://scope-$index.example.test", "Fixture $index"), "fixture")
            store.forgetCredentials(id) // Gate reaches local NeedsLogin, with no server request.
        }
    }

    private fun launch() {
        compose.setContent {
            VeilAdminTheme {
                key(navigationGeneration.value) {
                    nav = rememberNavController()
                    // The saved state has to be restored before NavHost sets its graph, which happens later in this same
                    // composition, so it cannot wait for a SideEffect. It acts on the controller this composition just
                    // created, once per controller, and remember returns whether it restored (never Unit).
                    remember(nav) { restoreNavigationState?.let { nav.restoreState(it); true } ?: false }
                    AdminApp(store, AppLockPreferences(prefs), com.bloemstudios.admin.ui.theme.ThemePreferences(prefs), settings, false, nav, sensitiveStores)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertRoute(route: String, serverId: String? = null) = compose.runOnIdle {
        assertEquals(route, nav.currentBackStackEntry?.destination?.route)
        if (serverId != null) assertEquals(serverId, nav.currentBackStackEntry?.arguments?.getString("serverId"))
    }

    @Test fun emptyColdLaunchHasAddServerAndCancelKeepsAggregatePreference() {
        launch()
        assertRoute("allServers")
        // Nothing saved: a welcome with the one action, and no shell around it.
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
        compose.onNodeWithText("Add server").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertRoute("allServers")
        compose.runOnIdle { assertTrue(settings.allServers.value); assertNull(settings.selectedServerId.value) }
    }

    @Test fun switcherReachesAddServerAndTheDrawerReachesManageServersAndSettings() {
        seed(1)
        launch()
        assertRoute("allServers")
        compose.onNodeWithText("Add a Silo server").assertDoesNotExist()
        // The switcher sheet ends with Add server; Manage servers is in the drawer.
        compose.onNodeWithContentDescription("Change server context from All servers").performClick()
        compose.onNodeWithTag("shell-switcher-add").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("shell-manage-servers").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertRoute("serverList")
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertRoute("allServers")
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("shell-settings").assertIsDisplayed().performClick()
        // A page of its own, not a dialog: its own route and a back arrow, and back returns to where it was opened.
        assertRoute("appSettings")
        compose.onNodeWithText("Require device unlock").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Require device unlock").assertDoesNotExist()
        assertRoute("allServers")
    }

    @Test fun acceptedAdditionAppearsWithoutRestartOrScopePreferenceChange() {
        launch()
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        seed(1)
        compose.waitForIdle()
        compose.onNodeWithText("Fixture 0").assertIsDisplayed()
        compose.onNodeWithText("Add a Silo server").assertDoesNotExist()
        assertRoute("allServers")
        compose.runOnIdle { assertTrue(settings.allServers.value); assertNull(settings.selectedServerId.value) }
    }

    @Test fun oneServerDefaultStillLaunchesAggregateStatus() {
        seed(1)
        launch()
        assertRoute("allServers")
        compose.onNodeWithText("Fixture 0").assertIsDisplayed()
        compose.runOnIdle { assertTrue(settings.allServers.value); assertNull(settings.selectedServerId.value) }
    }

    @Test fun manyServersScopeSelectionPersistsAndManagementDoesNotOverwriteIt() {
        seed(3)
        launch()
        compose.onNodeWithContentDescription("Change server context from All servers").performClick()
        compose.onNode(hasText("Fixture 1") and hasClickAction()).performClick()
        assertRoute("adminGate/{serverId}", "scope-1")
        compose.runOnIdle {
            val reopened = ActivityScopePreferences(prefs)
            assertFalse(reopened.allServers.value)
            assertEquals("adminGate/scope-1", activityStartRoute(reopened.allServers.value,
                reopened.selectedServerId.value, store.servers.value.map { it.id }.toSet()))
        }
        compose.onNodeWithText(base.getString(com.bloemstudios.admin.R.string.veil_back_to_servers)).performClick()
        assertRoute("serverList")
        compose.runOnIdle { assertFalse(settings.allServers.value); assertEquals("scope-1", settings.selectedServerId.value) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertRoute("adminGate/{serverId}", "scope-1")
    }

    @Test fun theSignInScreenHasTheShellsTopBarWithItsTitleAndDropdownButNoMenu() {
        seed(1)
        settings.selectServer("scope-0")
        launch()
        assertRoute("adminGate/{serverId}", "scope-0")
        // "Sign in" is also the form's own heading and button: the bar's is the one on the dropdown's row.
        val dropdown = compose.onNodeWithContentDescription("Change server context from Fixture 0")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val barTitle = compose.onAllNodesWithText("Sign in").fetchSemanticsNodes().minByOrNull { it.boundsInRoot.top }!!
        compose.runOnIdle {
            assertTrue("title is on the dropdown's row", barTitle.boundsInRoot.top < dropdown.bottom &&
                barTitle.boundsInRoot.bottom > dropdown.top && barTitle.boundsInRoot.right <= dropdown.left + 1)
        }
        compose.onNodeWithContentDescription("Open menu").assertDoesNotExist()
    }

    @Test fun aggregateSwitchOnFromIndividualGatePersistsAcrossFreshNavigation() {
        seed(1)
        settings.selectServer("scope-0")
        launch()
        compose.onNodeWithContentDescription("Change server context from Fixture 0").performClick()
        compose.onNode(hasText("All servers") and hasClickAction()).performClick()
        assertRoute("allServers")
        compose.runOnIdle {
            assertTrue(ActivityScopePreferences(prefs).allServers.value)
            navigationGeneration.value++
        }
        compose.waitForIdle()
        assertRoute("allServers")
        compose.onNodeWithContentDescription("Change server context from All servers").performClick()
        compose.onNode(hasText("Fixture 0") and hasClickAction()).performClick()
        assertRoute("adminGate/{serverId}", "scope-0")
        compose.runOnIdle { navigationGeneration.value++ }
        compose.waitForIdle()
        assertRoute("adminGate/{serverId}", "scope-0")
    }

    @Test fun recoveredCredentialsNoticeRemainsVisibleOnOperationalStartup() {
        store.close()
        store = ServerStore(fixtureContext, TokenPreferencesOpenResult(tokenPrefs, true, true), metadataWriter = {}, metadataReader = { emptyList() })
        launch()
        compose.onNodeWithText(base.getString(com.bloemstudios.admin.R.string.credentials_recovered_title)).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertRoute("allServers")
    }

    @Test fun unavailableCredentialStorageRemainsVisibleOnOperationalStartup() {
        store.close()
        store = ServerStore(fixtureContext, TokenPreferencesOpenResult(tokenPrefs, false, false), metadataWriter = {}, metadataReader = { emptyList() })
        launch()
        compose.onNodeWithText(base.getString(com.bloemstudios.admin.R.string.credential_storage_unavailable_title)).assertIsDisplayed()
    }

    @Test fun directIndividualRootBackOpensServers() {
        seed(1)
        settings.selectServer("scope-0")
        launch()
        assertRoute("adminGate/{serverId}", "scope-0")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertRoute("serverList")
        compose.runOnIdle { assertFalse(settings.allServers.value) }
    }

    @Test fun removedColdSelectionFallsBackAndRepairsPreference() {
        seed(1)
        settings.selectServer("removed")
        launch()
        assertRoute("allServers")
        compose.runOnIdle { assertTrue(settings.allServers.value) }
    }

    @Test fun selectedRemovalWhileManagementVisiblePreservesManagementAndRemovesInvalidGate() {
        seed(1)
        settings.selectServer("scope-0")
        launch()
        compose.onNodeWithText(base.getString(com.bloemstudios.admin.R.string.veil_back_to_servers)).performClick()
        runBlocking { store.removeServer("scope-0") }
        compose.waitForIdle()
        assertRoute("serverList")
        compose.runOnIdle { assertTrue(settings.allServers.value); compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertRoute("allServers")
    }

    @Test fun restoredRemovedGateFallsBackToAggregate() {
        seed(2)
        launch()
        var snapshot: Bundle? = null
        compose.runOnIdle {
            nav.navigate("adminGate/scope-1")
            snapshot = nav.saveState()
            nav.popBackStack()
        }
        runBlocking { store.removeServer("scope-1") }
        compose.runOnIdle {
            restoreNavigationState = snapshot
            navigationGeneration.value++
        }
        compose.waitForIdle()
        restoreNavigationState = null
        assertRoute("allServers")
        compose.runOnIdle { assertTrue(settings.allServers.value) }
    }

    @Test fun aggregateReloginAndVisibleGateRemovalDoNotRetainDeletedContent() {
        seed(2)
        launch()
        compose.runOnIdle { nav.navigate("adminGate/scope-1") }
        assertRoute("adminGate/{serverId}", "scope-1")
        compose.runOnIdle { assertTrue(settings.allServers.value); assertNull(settings.selectedServerId.value) }
        runBlocking { store.removeServer("scope-1") }
        compose.waitForIdle()
        assertRoute("allServers")
    }
}
