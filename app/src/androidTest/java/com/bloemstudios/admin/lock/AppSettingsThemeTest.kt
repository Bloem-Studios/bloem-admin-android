package com.bloemstudios.admin.lock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** App settings offers System, Light and Dark, marks the one in use and reports a choice. */
@RunWith(AndroidJUnit4::class)
class AppSettingsThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theThreeChoicesAreOfferedAndTheCurrentOneIsMarked() {
        var mode by mutableStateOf(ThemeMode.System)
        val picked = mutableListOf<ThemeMode>()
        compose.setContent {
            VeilAdminTheme {
                AppSettingsScreen(
                    requireDeviceUnlock = false, onRequireDeviceUnlockChanged = {}, onBack = {},
                    themeMode = mode, onThemeModeChanged = { picked += it; mode = it },
                )
            }
        }
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithTag(ThemeOptionTagPrefix + "system").assertIsSelected()
        compose.onNodeWithTag(ThemeOptionTagPrefix + "dark").assertIsNotSelected().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeOptionTagPrefix + "dark").assertIsSelected()
        compose.onNodeWithTag(ThemeOptionTagPrefix + "light").performClick()
        compose.runOnIdle { assertEquals(listOf(ThemeMode.Dark, ThemeMode.Light), picked) }
    }
}
