package com.bloemstudios.admin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The one Bloem look: the web admin's graphite neutrals (dark: web app.css "Midnight Cinema"), a matching light
 * graphite scheme, and Bloem orange as the only accent.
 *
 * Every text color here reaches 4.5:1 on every surface of its scheme (see VeilPaletteContrastTest):
 * orange and the semantic ok, warning and danger colors are tuned per scheme, not shared.
 */
@Immutable
class VeilPalette(
    val isDark: Boolean,
    /** The page. */
    val canvas: Color,
    /** The drawer: the darkest (dark) or the quietest (light) surface. */
    val sidebar: Color,
    /** Rows, panels and cards. */
    val card: Color,
    /** Filled fields, chips, dialogs, hover: one clear step above [card]. */
    val raised: Color,
    /** The current page in the drawer and the chosen row in a list. */
    val selected: Color,
    /** Dividers and card edges: the text color at 8-10%. */
    val hairline: Color,
    val text: Color,
    val muted: Color,
    /** Field and control outlines, 3:1 against the surfaces they sit on. */
    val outline: Color,
    /** Bloem orange as text, and as the fill under [onAccent]. Darker on light so both reach 4.5:1. */
    val accent: Color,
    val onAccent: Color,
    /** The tally dot: live right now. Used for nothing else. */
    val tally: Color,
    val ok: Color,
    val warning: Color,
    val danger: Color,
    val onDanger: Color,
    /** The web's chart colors, in its fixed order: direct play, direct stream, transcode, then two spares. */
    val chart: List<Color>,
) {
    companion object {
        val ChartColors = listOf(
            Color(0xFF5F74EE), Color(0xFF169E88), Color(0xFFBF7F22), Color(0xFFC2578F), Color(0xFF3F97CF),
        )

        val Dark = VeilPalette(
            isDark = true,
            canvas = Color(0xFF141417),
            sidebar = Color(0xFF0F0F12),
            card = Color(0xFF1C1C20),
            raised = Color(0xFF28282E),
            selected = Color(0xFF202023),
            hairline = Color(0x14E8E8EC),
            text = Color(0xFFE8E8EC),
            muted = Color(0xFFA4A4AE),
            outline = Color(0xFF787882),
            accent = Color(0xFFFA6400),
            onAccent = Color(0xFF141417),
            tally = Color(0xFFFA6400),
            ok = Color(0xFF57CB60),
            warning = Color(0xFFEAB532),
            danger = Color(0xFFF46666),
            onDanger = Color(0xFF141417),
            chart = ChartColors,
        )

        val Light = VeilPalette(
            isDark = false,
            canvas = Color(0xFFF6F6F8),
            sidebar = Color(0xFFEEEEF2),
            card = Color(0xFFFFFFFF),
            raised = Color(0xFFE9E9EE),
            selected = Color(0xFFDDDDE1),
            hairline = Color(0x1A1A1A1F),
            text = Color(0xFF1A1A1F),
            muted = Color(0xFF5A5A65),
            outline = Color(0xFF85858F),
            accent = Color(0xFFA04000),
            onAccent = Color(0xFFFFFFFF),
            tally = Color(0xFFE85D00),
            ok = Color(0xFF1A6A2B),
            warning = Color(0xFF7F4E00),
            danger = Color(0xFFB42626),
            onDanger = Color(0xFFFFFFFF),
            chart = ChartColors,
        )
    }
}

internal val LocalVeilPalette = staticCompositionLocalOf { VeilPalette.Dark }

/** The current scheme's colors. Read inside composables, so they follow the theme. */
object VeilColors {
    val Canvas: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.canvas
    val Sidebar: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.sidebar
    val Card: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.card
    val Raised: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.raised
    val Selected: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.selected
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.hairline
    val Text: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.muted
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.outline
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.onAccent
    val Tally: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.tally
    val Ok: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.ok
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.warning
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.danger
    val OnDanger: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.onDanger
    val PlayDirect: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.chart[0]
    val PlayStream: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.chart[1]
    val PlayTranscode: Color @Composable @ReadOnlyComposable get() = LocalVeilPalette.current.chart[2]
}
