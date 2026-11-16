package com.bloemstudios.admin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Which scheme the app uses: follow the system, or one chosen in App settings. */
enum class ThemeMode(val storageValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageValue == value } ?: System
    }
}

/** Whether [mode] resolves to the dark scheme when the system is dark ([systemDark]) or light. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

internal fun VeilPalette.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = raised,
        onPrimaryContainer = text,
        secondary = muted,
        onSecondary = canvas,
        secondaryContainer = selected,
        onSecondaryContainer = text,
        tertiary = ok,
        onTertiary = canvas,
        tertiaryContainer = raised,
        onTertiaryContainer = text,
        error = danger,
        onError = onDanger,
        errorContainer = raised,
        onErrorContainer = text,
        background = canvas,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceBright = raised,
        surfaceDim = canvas,
        surfaceContainerLowest = sidebar,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        outline = outline,
        outlineVariant = hairline,
        scrim = Color.Black,
        inverseSurface = text,
        inverseOnSurface = canvas,
        inversePrimary = accent,
        // No tonal tint: raised surfaces such as dialogs stay graphite instead of picking up the orange.
        surfaceTint = Color.Transparent,
    )
}

@Composable
fun VeilAdminTheme(themeMode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val palette = if (themeMode.isDark(isSystemInDarkTheme())) VeilPalette.Dark else VeilPalette.Light
    val systemReduced = systemReducedMotion()
    val reducedMotion = LocalReducedMotionOverride.current ?: (LocalReducedMotion.current || systemReduced)
    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalVeilPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            typography = VeilTypography.material3,
            shapes = VeilShapes.material3,
            content = {
                CompositionLocalProvider(LocalContentColor provides palette.text) { content() }
            },
        )
    }
}
