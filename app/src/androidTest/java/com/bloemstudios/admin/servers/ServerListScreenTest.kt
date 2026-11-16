package com.bloemstudios.admin.servers

import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ServerListScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val fixtureId = UUID.randomUUID().toString()
    private val fixtureContext = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(base.filesDir, "server-list-$fixtureId").apply { mkdirs() }
    }
    private val tokenPrefs = base.getSharedPreferences("server-list-tokens-$fixtureId", Context.MODE_PRIVATE)
    private val store = ServerStore(fixtureContext, tokenPrefs, metadataWriter = {}, metadataReader = { emptyList() })

    @After fun cleanup() {
        store.close()
        base.deleteSharedPreferences("server-list-tokens-$fixtureId")
    }

    private fun seed() = runBlocking {
        store.addServer(AdminServer("loft", "https://loft.example.test", "Loft"), "token")
        store.addServer(AdminServer("den", "https://den.example.test", "Den"), "token")
        store.forgetCredentials("den")
    }

    private val opened = mutableListOf<String>()
    private var allServers = 0
    private var appSettings = 0
    private var backs = 0

    /** The page is taller than the screen, so the lazy list brings the App settings row into view first. */
    private fun scrollToAppSettings() {
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag(ServerListAppSettingsTag))
    }

    private fun launch(withBack: Boolean = true) {
        compose.setContent {
            VeilAdminTheme {
                ServerListScreen(
                    store = store,
                    onServerSelected = { opened += it.id },
                    onAppSettingsRequested = { appSettings++ },
                    onAllServersSelected = { allServers++ },
                    onBack = if (withBack) ({ backs++ }) else null,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun pageReadsLikeAWebSettingsPageWithTheSavedServersAndAppSettings() {
        seed()
        launch()
        compose.onNodeWithText("Manage servers").assertIsDisplayed()
        compose.onNodeWithTag(ServerListAllServersTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Saved servers").assertIsDisplayed()
        compose.onNodeWithTag(ServerRowTagPrefix + "loft").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(ServerRowTagPrefix + "den").assertIsDisplayed()
        compose.onNodeWithText("https://loft.example.test").assertIsDisplayed()
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        compose.onNodeWithTag(ServerListAddServerTag).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(ServerListAppSettingsTag).also { scrollToAppSettings() }.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("App").assertIsDisplayed()
    }

    @Test fun eachServerSaysWhetherThisDeviceIsSignedIn() {
        seed()
        launch()
        compose.onNodeWithTag(ServerSignInStateTagPrefix + "loft", useUnmergedTree = true).assertTextEquals("Signed in")
        compose.onNodeWithTag(ServerSignInStateTagPrefix + "den", useUnmergedTree = true).assertTextEquals("Signed out")
    }

    @Test fun signedOutServerHasNothingToForget() {
        seed()
        launch()
        compose.onNodeWithContentDescription("Actions for Den").performClick()
        compose.onNode(hasText("Forget credentials")).assertIsNotEnabled()
    }

    @Test fun forgettingCredentialsAsksFirstThenFlipsTheStateAndKeepsTheServer() {
        seed()
        launch()
        compose.onNodeWithContentDescription("Actions for Loft").performClick()
        compose.onNodeWithText("Forget credentials").performClick()
        compose.onNodeWithText("Forget credentials for Loft?").assertIsDisplayed()
        compose.onNodeWithTag(ServerSignInStateTagPrefix + "loft", useUnmergedTree = true).assertTextEquals("Signed in")
        // The dialog's own button is the second node with this label.
        compose.onAllNodesWithText("Forget credentials").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ServerSignInStateTagPrefix + "loft", useUnmergedTree = true).assertTextEquals("Signed out")
        compose.onNodeWithTag(ServerRowTagPrefix + "loft").assertIsDisplayed()
    }

    @Test fun renameChangesTheNameEverywhere() {
        seed()
        launch()
        compose.onNodeWithContentDescription("Actions for Loft").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rename Loft").assertIsDisplayed()
        compose.onNode(hasText("Loft") and androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("Attic")
        compose.onNodeWithText("Save changes").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Attic", store.servers.value.first { it.id == "loft" }.displayName) }
        compose.onNodeWithText("Attic").assertIsDisplayed()
    }

    @Test fun removeAsksFirstThenDropsTheServer() {
        seed()
        launch()
        compose.onNodeWithContentDescription("Actions for Den").performClick()
        compose.onNodeWithText("Remove server").performClick()
        compose.onNodeWithText("Remove Den?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, store.servers.value.size) }
        compose.onNodeWithContentDescription("Actions for Den").performClick()
        compose.onNodeWithText("Remove server").performClick()
        compose.onAllNodesWithText("Remove server").onLast().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("loft"), store.servers.value.map { it.id }) }
        compose.onNodeWithTag(ServerRowTagPrefix + "den").assertDoesNotExist()
    }

    @Test fun rowsAppSettingsAndAllServersCallBack() {
        seed()
        launch()
        compose.onNodeWithTag(ServerRowTagPrefix + "loft").performClick()
        compose.onNodeWithTag(ServerListAllServersTag).performClick()
        compose.onNodeWithTag(ServerListAppSettingsTag).also { scrollToAppSettings() }.performClick()
        compose.runOnIdle {
            assertEquals(listOf("loft"), opened)
            assertEquals(1, allServers)
            assertEquals(1, appSettings)
        }
    }

    @Test fun anInsecureAddressCannotBeOpenedAndSaysWhy() {
        runBlocking { store.addServer(AdminServer("plain", "http://plain.example.test", "Plain"), "token") }
        launch()
        compose.onNodeWithText("This server uses an insecure address. Remove it and add its HTTPS address.").assertIsDisplayed()
        compose.onNodeWithTag(ServerRowTagPrefix + "plain").performClick()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun emptyListSaysSoAndKeepsAddServerAndAppSettings() {
        launch()
        compose.onNodeWithText("No individual servers saved yet").assertIsDisplayed()
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        compose.onNodeWithTag(ServerListAppSettingsTag).also { scrollToAppSettings() }.assertIsDisplayed()
    }

    @Test fun backArrowIsThereOnlyWhereThereIsSomewhereToGo() {
        launch(withBack = true)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun noBackArrowOnTheRootOfTheStack() {
        launch(withBack = false)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
