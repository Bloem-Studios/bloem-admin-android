package com.bloemstudios.admin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.lock.LockScreen
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.AggregateStreamActions
import com.bloemstudios.admin.streams.LiveServerStreamsSection
import com.bloemstudios.admin.streams.SendMessageResult
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pictures of screens of the quality fixes that a fixture page cannot reach
 * (Now playing when the sessions cannot be read, the lock screen). They are written to the app's external files folder
 * as `quality-<name>.png`; the test asserts nothing about them, the tests beside it do.
 */
@RunWith(AndroidJUnit4::class)
class QualityScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val audit = AdminServer("audit", "https://fixture.invalid", "Audit Home")
    private val garden = AdminServer("garden", "https://fixture.invalid", "Garden")
    private val none = AggregateStreamActions(
        onPause = { _, _ -> }, onResume = { _, _ -> }, onStop = { _, _ -> }, onTerminate = { _, _ -> },
        onSendMessage = { _, _, _, _ -> SendMessageResult.Success }, onSignIn = {}, onRetry = {},
    )
    private val row = AdminSession(
        session_id = "s1", user_id = "u1", username = "maria", profile_id = "p1", media_title = "Dune: Part Two",
        media_type = "movie", play_method = "direct", position_seconds = 3600.0, file_duration = 9960.0, is_paused = false,
        client_name = "Infuse", stream_bitrate_kbps = 18000, effective_play_method = "direct",
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "quality-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun both(name: String, content: @Composable () -> Unit) {
        var mode by mutableStateOf(ThemeMode.Light)
        compose.setContent { VeilAdminTheme(mode) { Box(Modifier.fillMaxSize().background(VeilColors.Canvas)) { content() } } }
        capture("$name-light")
        compose.runOnIdle { mode = ThemeMode.Dark }
        capture("$name-dark")
    }

    @Test
    fun nowPlayingWhenTheSessionsCannotBeRead() = both("nowplaying-failed") {
        LazyColumn(Modifier.padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Large), contentPadding = PaddingValues(bottom = VeilSpacing.Large)) {
            item { LiveServerStreamsSection(audit, null, RealtimeState.Reconnecting, SessionFailure.Connection, false, none) }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(VeilSpacing.Medium)) }
            item { LiveServerStreamsSection(garden, listOf(row), RealtimeState.Live, null, false, none) }
        }
    }

    @Test
    fun nowPlayingWhileLoadingAndWhenStale() = both("nowplaying-loading-stale") {
        LazyColumn(Modifier.padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Large)) {
            item { LiveServerStreamsSection(audit, null, RealtimeState.Connecting, null, false, none) }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(VeilSpacing.Medium)) }
            item { LiveServerStreamsSection(garden, listOf(row), RealtimeState.Reconnecting, SessionFailure.Connection, false, none) }
        }
    }

    @Test
    fun lockScreen() = both("lock") { LockScreen(message = null, onUnlockRequested = {}) }
}
