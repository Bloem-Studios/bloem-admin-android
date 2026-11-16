package com.bloemstudios.admin.alerts

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilPalette
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The few colors platform surfaces need as resources are the palette's own tokens, so the look stays one look. */
@RunWith(RobolectricTestRunner::class)
class NativeColorsMatchPaletteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun shortcutSurfaceIsTheDarkCanvas() {
        assertEquals(VeilPalette.Dark.canvas.toArgb(), context.getColor(R.color.native_surface))
    }

    @Test fun shortcutGlyphIsTheDarkText() {
        assertEquals(VeilPalette.Dark.text.toArgb(), context.getColor(R.color.native_glyph))
    }
}
