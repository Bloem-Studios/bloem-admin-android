package com.bloemstudios.admin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.fixtures.FixtureServer
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.VeilColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The app pages the widgets are set beside in the widget screenshots, for the pages the screenshot tour has no picture of: Libraries.
 * Same fixtures, same theme, same device as the tour. Run on your own emulator.
 */
@RunWith(AndroidJUnit4::class)
class WidgetReferenceScreensTest {
    @get:Rule val compose = createComposeRule()

    private var mode by mutableStateOf(ThemeMode.Dark)
    private val outDir: File by lazy {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)?.apply { mkdirs() }
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalReducedMotionOverride provides true) {
            VeilAdminTheme(mode) { Box(Modifier.fillMaxSize().background(VeilColors.Canvas)) { content() } }
        }
    }

    private fun shoot(name: String) {
        for (themeMode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = themeMode
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "$name-${themeMode.storageValue}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun libraries() {
        show { FixtureServer("content.libraries") }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Movies").fetchSemanticsNodes().isNotEmpty() }
        shoot("libraries")
    }
}
