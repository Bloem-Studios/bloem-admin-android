package com.bloemstudios.admin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measures the palette: every text color reaches 4.5:1 on every surface it can sit on, in both schemes. */
class VeilPaletteContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun surfaces(p: VeilPalette) = mapOf(
        "canvas" to p.canvas,
        "sidebar" to p.sidebar,
        "card" to p.card,
        "raised" to p.raised,
        "selected" to p.selected,
    )

    private fun textColors(p: VeilPalette) = mapOf(
        "text" to p.text,
        "muted" to p.muted,
        "accent" to p.accent,
        "ok" to p.ok,
        "warning" to p.warning,
        "danger" to p.danger,
    )

    private fun check(name: String, p: VeilPalette) {
        val failures = mutableListOf<String>()
        for ((surfaceName, surface) in surfaces(p)) {
            for ((colorName, color) in textColors(p)) {
                val r = ratio(color, surface)
                if (r < 4.5) failures += "$name $colorName on $surfaceName = ${"%.2f".format(r)}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun darkTextColorsReachFourPointFiveOnEverySurface() = check("dark", VeilPalette.Dark)

    @Test fun lightTextColorsReachFourPointFiveOnEverySurface() = check("light", VeilPalette.Light)

    @Test fun textOnTheAccentFillAndOnDangerFillsReachFourPointFive() {
        for (p in listOf(VeilPalette.Dark, VeilPalette.Light)) {
            assertTrue("accent fill ${p.isDark}", ratio(p.onAccent, p.accent) >= 4.5)
            assertTrue("danger fill ${p.isDark}", ratio(p.onDanger, p.danger) >= 4.5)
        }
    }

    @Test fun fieldOutlinesReachThreeToOneOnTheSurfacesFieldsSitOn() {
        for (p in listOf(VeilPalette.Dark, VeilPalette.Light)) {
            for (s in listOf(p.canvas, p.card)) {
                assertTrue("outline ${p.isDark} on $s = ${ratio(p.outline, s)}", ratio(p.outline, s) >= 3.0)
            }
        }
    }

    @Test fun playMethodColorsReachThreeToOneAsMarksOnTheCardSurface() {
        for (p in listOf(VeilPalette.Dark, VeilPalette.Light)) {
            for (c in p.chart.take(3)) assertTrue("chart $c on ${p.isDark} = ${ratio(c, p.card)}", ratio(c, p.card) >= 3.0)
        }
    }

    @Test fun surfaceStepsAreVisibleNotFlat() {
        // Card over canvas and raised over card each step by at least 1.07:1; the drawer's selected row over the
        // sidebar by at least 1.1:1.
        for (p in listOf(VeilPalette.Dark, VeilPalette.Light)) {
            assertTrue("card/canvas ${p.isDark}", ratio(p.card, p.canvas) >= 1.07)
            assertTrue("raised/card ${p.isDark}", ratio(p.raised, p.card) >= 1.07)
            assertTrue("selected/sidebar ${p.isDark}", ratio(p.selected, p.sidebar) >= 1.1)
            val hairlineOnCard = p.hairline.compositeOver(p.card)
            assertTrue("hairline/card ${p.isDark}", ratio(hairlineOnCard, p.card) >= 1.1)
        }
    }
}
