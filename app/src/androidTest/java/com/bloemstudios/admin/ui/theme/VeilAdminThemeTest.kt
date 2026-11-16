package com.bloemstudios.admin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VeilAdminThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun schemeFor(mode: ThemeMode): ColorScheme {
        var scheme: ColorScheme? = null
        compose.setContent { VeilAdminTheme(mode) { scheme = MaterialTheme.colorScheme } }
        compose.runOnIdle { }
        return scheme!!
    }

    @Test
    fun darkIsTheWebAdminsGraphiteWithOrangeAsTheOnlyAccent() {
        val colors = schemeFor(ThemeMode.Dark)
        assertEquals(Color(0xFF141417), colors.background)
        assertEquals(Color(0xFFE8E8EC), colors.onBackground)
        assertEquals(Color(0xFF1C1C20), colors.surface)
        assertEquals(Color(0xFF28282E), colors.surfaceContainerHigh)
        assertEquals(Color(0xFF0F0F12), colors.surfaceContainerLowest)
        assertEquals(Color(0xFFA4A4AE), colors.onSurfaceVariant)
        assertEquals(Color(0xFFFA6400), colors.primary)
        assertEquals(Color(0xFFF46666), colors.error)
    }

    @Test
    fun lightIsAMatchingGraphiteWithADarkerOrangeThatCarriesWhiteText() {
        val colors = schemeFor(ThemeMode.Light)
        assertEquals(Color(0xFFF6F6F8), colors.background)
        assertEquals(Color(0xFF1A1A1F), colors.onBackground)
        assertEquals(Color(0xFFFFFFFF), colors.surface)
        assertEquals(Color(0xFFA04000), colors.primary)
        assertEquals(Color(0xFFFFFFFF), colors.onPrimary)
    }

    @Test
    fun theChoiceOverridesTheSystemInBothDirections() {
        // A fixed choice is independent of whatever the device is set to, and changing it re-themes at once.
        var mode by mutableStateOf(ThemeMode.Dark)
        var background = Color.Unspecified
        compose.setContent { VeilAdminTheme(mode) { background = MaterialTheme.colorScheme.background } }
        compose.runOnIdle { assertEquals(Color(0xFF141417), background) }
        mode = ThemeMode.Light
        compose.runOnIdle { assertEquals(Color(0xFFF6F6F8), background) }
    }
}
