package com.bloemstudios.admin.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.dashboard.SystemResources
import com.bloemstudios.admin.dashboard.SystemStats
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.dashboard.AdminStats
import com.bloemstudios.admin.dashboard.DashboardVeilContent
import com.bloemstudios.admin.ui.shell.*
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class ManagementVisualTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun dashboardRestingRefreshVisual() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Test server", false), singleServerDestinations(false),
                    "overview.dashboard", {}, false, {}) {
                    val pull = rememberPullToRefreshState()
                    Box(Modifier.fillMaxSize()) {
                        DashboardVeilContent(true, AdminStats(10, 11, 12, 13, 14, 15, 16, 7, 1000, emptyList()),
                            SystemResources(true, sampled_at = "2026-10-12T00:00:00Z", system = SystemStats(18.2, 2384, 7629, 1500000.0, 9200000.0)), null, {},
                            sessions = listOf(
                                AdminSession("one", "user-1", "Alex", "profile-1", "The River", "movie", "Direct play", 1200.0, 5400.0, false, "Living room"),
                                AdminSession("two", "user-1", "Sam", "profile-1", "Northern Lights · S2 E4", "episode", "Transcode", 450.0, 2700.0, true, "Android")),
                            onStreamsRequested = {})
                        PullToRefreshContainer(pull, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Open menu").assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), "management-dashboard.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
