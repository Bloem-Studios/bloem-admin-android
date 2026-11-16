package com.bloemstudios.admin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class VeilTokensTest {
    @Test
    fun darkSchemeIsTheWebAdminsGraphite() {
        val dark = VeilPalette.Dark
        assertEquals(Color(0xFF141417), dark.canvas)
        assertEquals(Color(0xFF1C1C20), dark.card)
        assertEquals(Color(0xFF28282E), dark.raised)
        assertEquals(Color(0xFF0F0F12), dark.sidebar)
        assertEquals(Color(0xFFE8E8EC), dark.text)
        assertEquals(Color(0xFFA4A4AE), dark.muted)
    }

    @Test
    fun bloemOrangeIsTheOnlyAccentAndTheChartColorsAreTheWebs() {
        assertEquals(Color(0xFFFA6400), VeilPalette.Dark.accent)
        assertEquals(Color(0xFFFA6400), VeilPalette.Dark.tally)
        assertEquals(
            listOf(Color(0xFF5F74EE), Color(0xFF169E88), Color(0xFFBF7F22), Color(0xFFC2578F), Color(0xFF3F97CF)),
            VeilPalette.ChartColors,
        )
        assertEquals(VeilPalette.ChartColors, VeilPalette.Light.chart)
    }

    @Test
    fun geometryTokensAreStable() {
        assertEquals(16.dp, VeilSpacing.CompactHorizontal)
        assertEquals(12.dp, VeilShapes.PrimaryCardCorner)
        assertEquals(10.dp, VeilShapes.ControlCorner)
        assertEquals(48.dp, VeilSpacing.MinimumTouchTarget)
    }

    @Test
    fun motionTokensMatchTheDesignRules() {
        // 150 to 200 ms for feedback, 250 ms for entering, one easing curve.
        assertEquals(150, VeilMotionTokens().fastMillis)
        assertEquals(200, VeilMotionTokens().normalMillis)
        assertEquals(250, VeilMotionTokens().sheetMillis)
    }

    @Test
    fun themeModeRoundTripsThroughStorageAndDefaultsToSystem() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStorage(it.storageValue)) }
        assertEquals(ThemeMode.System, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStorage("sepia"))
    }

    @Test
    fun themeModeResolvesAgainstTheSystem() {
        assertEquals(true, ThemeMode.System.isDark(systemDark = true))
        assertEquals(false, ThemeMode.System.isDark(systemDark = false))
        assertEquals(false, ThemeMode.Light.isDark(systemDark = true))
        assertEquals(true, ThemeMode.Dark.isDark(systemDark = false))
    }
}
