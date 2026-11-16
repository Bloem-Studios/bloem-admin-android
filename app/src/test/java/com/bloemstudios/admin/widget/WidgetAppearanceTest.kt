package com.bloemstudios.admin.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilPalette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widgets follow the system's light or dark setting, or the app's own Appearance choice when it is not System. */
@RunWith(RobolectricTestRunner::class)
class WidgetAppearanceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val day = Color(0xFFFFFFFF)
    private val night = Color(0xFF000000)

    @After fun reset() { WidgetAppearance.mode = ThemeMode.System }

    @Test @Config(qualifiers = "night") fun systemFollowsTheDeviceAtNight() {
        assertEquals(night.toArgb(), Tokens.token(day, night, ThemeMode.System).getColor(context).toArgb())
    }

    @Test @Config(qualifiers = "notnight") fun systemFollowsTheDeviceByDay() {
        assertEquals(day.toArgb(), Tokens.token(day, night, ThemeMode.System).getColor(context).toArgb())
    }

    @Test @Config(qualifiers = "night") fun theAppsLightChoiceWinsOverANightDevice() {
        assertEquals(day.toArgb(), Tokens.token(day, night, ThemeMode.Light).getColor(context).toArgb())
    }

    @Test @Config(qualifiers = "notnight") fun theAppsDarkChoiceWinsOverADayDevice() {
        assertEquals(night.toArgb(), Tokens.token(day, night, ThemeMode.Dark).getColor(context).toArgb())
    }

    @Test @Config(qualifiers = "notnight") fun theCardTokenUsesTheAppsAppearanceSetting() {
        WidgetAppearance.mode = ThemeMode.Dark
        assertEquals(VeilPalette.Dark.card.toArgb(), Tokens.Card.getColor(context).toArgb())
        WidgetAppearance.mode = ThemeMode.Light
        assertEquals(VeilPalette.Light.card.toArgb(), Tokens.Card.getColor(context).toArgb())
    }
}
