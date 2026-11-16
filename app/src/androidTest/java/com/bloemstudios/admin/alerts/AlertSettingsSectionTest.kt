package com.bloemstudios.admin.alerts

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Server alerts" part of App settings: everything starts off, and each switch reports exactly what was asked. */
@RunWith(AndroidJUnit4::class)
class AlertSettingsSectionTest {
    @get:Rule val compose = createComposeRule()

    private val servers = listOf(AdminServer("a", "https://a.test", "Bloem Home"), AdminServer("b", "https://b.test", "Lab"))
    private val master = "Alert me when a server stops answering"

    private fun show(
        enabled: Boolean = false,
        details: Boolean = false,
        on: Set<String> = emptySet(),
        status: AlertDeliveryStatus = AlertDeliveryStatus.Off,
        refused: Boolean = false,
        onEnabled: (Boolean) -> Unit = {},
        onServer: (String, Boolean) -> Unit = { _, _ -> },
        onDetails: (Boolean) -> Unit = {},
        onSystem: () -> Unit = {},
    ) = compose.setContent {
        VeilAdminTheme {
            AlertSettingsSection(enabled, details, servers, on, status, refused, onEnabled, onServer, onDetails, onSystem)
        }
    }

    @Test fun aNewInstallHasEverySwitchOff() {
        show()
        compose.onNodeWithContentDescription(master).assertIsOff()
        compose.onNodeWithContentDescription("Bloem Home").assertIsOff()
        compose.onNodeWithContentDescription("Lab").assertIsOff()
        compose.onNodeWithContentDescription("Show details on lock screen").assertIsOff()
        compose.onNodeWithText("Notifications are off in system settings").assertDoesNotExist()
    }

    @Test fun theMasterSwitchAsksToTurnOnAndTheServerSwitchNamesItsServer() {
        val enabled = mutableListOf<Boolean>()
        val toggled = mutableListOf<Pair<String, Boolean>>()
        show(onEnabled = { enabled += it }, onServer = { id, on -> toggled += id to on })
        compose.onNodeWithContentDescription(master).performClick()
        compose.onNodeWithContentDescription("Lab").performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), enabled)
            assertEquals(listOf("b" to true), toggled)
        }
    }

    @Test fun savedChoicesShowAsOn() {
        show(enabled = true, details = true, on = setOf("a"), status = AlertDeliveryStatus.On)
        compose.onNodeWithContentDescription(master).assertIsOn()
        compose.onNodeWithContentDescription("Bloem Home").assertIsOn()
        compose.onNodeWithContentDescription("Lab").assertIsOff()
        compose.onNodeWithContentDescription("Show details on lock screen").assertIsOn()
    }

    @Test fun whenAndroidBlocksNotificationsTheSectionSaysSoAndOffersTheSystemSetting() {
        var opened = 0
        show(enabled = true, status = AlertDeliveryStatus.BlockedBySystem, onSystem = { opened += 1 })
        compose.onNodeWithText("Notifications are off in system settings").assertIsDisplayed()
        compose.onNodeWithTag(AlertSystemSettingsTag).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun aRefusedPermissionExplainsWhereToChangeIt() {
        show(refused = true)
        compose.onNodeWithText("Notifications are off in system settings").assertIsDisplayed()
        compose.onNodeWithContentDescription(master).assertIsOff()
    }

    @Test fun withNoSavedServersTheListSaysWhatToDo() {
        compose.setContent {
            VeilAdminTheme {
                AlertSettingsSection(false, false, emptyList(), emptySet(), AlertDeliveryStatus.Off, false, {}, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("Save a server to watch it").assertIsDisplayed()
    }
}
