package com.bloemstudios.admin

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MainActivityThemeTest {
    @Test fun lightActivityThemeHasNoPlatformActionBarAndUsesTheLightCanvasWindowColor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wrapper = ContextThemeWrapper(context, R.style.Theme_BloemAdmin)
        val attrs = wrapper.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowActionBar,
            android.R.attr.windowNoTitle,
            android.R.attr.colorBackground,
        ))
        assertFalse(attrs.getBoolean(0, true))
        assertTrue(attrs.getBoolean(1, false))
        assertEquals(Color.rgb(0xF6, 0xF6, 0xF8), attrs.getColor(2, Color.TRANSPARENT))
        attrs.recycle()
    }

    @Config(qualifiers = "night")
    @Test fun darkActivityThemeUsesTheDarkCanvasWindowColor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wrapper = ContextThemeWrapper(context, R.style.Theme_BloemAdmin)
        val attrs = wrapper.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        assertEquals(Color.rgb(0x14, 0x14, 0x17), attrs.getColor(0, Color.TRANSPARENT))
        attrs.recycle()
    }
}
