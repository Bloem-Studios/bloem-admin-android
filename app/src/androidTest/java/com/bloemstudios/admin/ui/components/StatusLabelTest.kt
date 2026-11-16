package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.VeilPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The status label is quiet text; only the live tone draws the orange tally dot, and only exceptions take a text color. */
@RunWith(AndroidJUnit4::class)
class StatusLabelTest {
    @get:Rule val compose = createComposeRule()

    private fun ImageBitmap.count(target: Color, tolerance: Float = 0.08f): Int {
        val map = toPixelMap()
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = map[x, y]
            if (Math.abs(c.red - target.red) < tolerance && Math.abs(c.green - target.green) < tolerance && Math.abs(c.blue - target.blue) < tolerance) n++
        }
        return n
    }

    private fun show(mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        VeilAdminTheme(mode) { Box(Modifier.testTag("host").background(VeilPalette.Dark.card).padding(16.dp)) { content() } }
    }

    private val tally = VeilPalette.Dark.tally

    @Test fun aQuietLabelHasNoOrangeAnywhere() {
        show { StatusLabel("Active") }
        compose.onNodeWithText("Active").assertIsDisplayed()
        assertEquals(0, compose.onNodeWithTag("host").captureToImage().count(tally))
    }

    @Test fun aWarningLabelIsAmberTextAndStillNoOrange() {
        show { StatusLabel("Paused", tone = StatusTone.Warning, dot = true) }
        val image = compose.onNodeWithTag("host").captureToImage()
        assertEquals("no tally orange on a warning", 0, image.count(tally, 0.05f))
        assertTrue("warning color is drawn", image.count(VeilPalette.Dark.warning) > 0)
    }

    @Test fun aFailureIsTheOnlyRed() {
        show { StatusLabel("Failed", tone = StatusTone.Danger, dot = true) }
        assertTrue(compose.onNodeWithTag("host").captureToImage().count(VeilPalette.Dark.danger) > 0)
    }

    @Test fun theLiveToneDrawsTheOrangeTallyDot() {
        show { StatusLabel("Playing", tone = StatusTone.Live) }
        assertTrue("the tally dot is drawn", compose.onNodeWithTag("host").captureToImage().count(tally) > 20)
    }

    @Test fun aLiveLabelCanBeWithoutItsDotAndThenIsQuiet() {
        show { StatusLabel("Playing", tone = StatusTone.Live, dot = false) }
        assertEquals(0, compose.onNodeWithTag("host").captureToImage().count(tally))
    }

    @Test fun theLightSchemeUsesTheDarkerOrangeForTextAndTheBrighterOneForTheDot() {
        show(ThemeMode.Light) { TallyDot() }
        assertTrue(compose.onNodeWithTag("host").captureToImage().count(VeilPalette.Light.tally) > 20)
    }
}
