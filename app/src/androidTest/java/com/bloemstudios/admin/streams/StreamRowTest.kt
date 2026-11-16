package com.bloemstudios.admin.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Now Playing row and the details it opens to. The test device is CI's 320 x 640 dp screen, so
 * every row here is laid out at 288 dp (the page's 16 dp gutters) and must not run off it.
 */
@RunWith(AndroidJUnit4::class)
class StreamRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val episode = AdminSession(
        session_id = "ep", user_id = "u1", username = "alex", profile_id = "p1", media_title = "Who Is Alive?",
        media_type = "episode", play_method = "transcode", position_seconds = 754.0, file_duration = 2652.0,
        is_paused = false, profile_name = "Living room", device_platform = "android", client_name = "Silo Android",
        client_label = "Silo Android 1.4.2", client_label_full = "Silo Android 1.4.2 (88) beta", client_ip = "203.0.113.9",
        stream_bitrate_kbps = 8200, video_decision = "transcode", audio_decision = "transcode",
        effective_play_method = "transcode", transcode_hw_accel = "nvenc", tone_map_mode = "hardware",
        source_video_codec = "hevc", source_video_resolution = "2160p", target_video_codec = "h264", target_resolution = "1080p",
        source_audio_codec = "truehd", source_audio_channels = 8, source_audio_language = "en", target_audio_codec = "aac",
        target_audio_channels = 6, series_name = "Severance", episode_name = "Who Is Alive?", season_number = 2, episode_number = 3,
        source_container = "mkv", source_bitrate_kbps = 24_500, output_container = "mpegts", output_protocol = "hls",
        stream_location = "remote", started_at = "2026-11-13T14:02:30Z", updated_at = "2026-11-13T14:14:50Z",
        routing_workload = "video_transcode", routing_execution = "transcode", routing_execution_node_name = "gpu-1",
        routing_egress = "api", reporting_node = "main",
    )

    private val direct = AdminSession(
        session_id = "film", user_id = "u2", username = "amy", profile_id = "p2", media_title = "The River",
        media_type = "movie", play_method = "direct", position_seconds = 1200.0, file_duration = 5400.0, is_paused = true,
        client_name = "Web", video_decision = "direct", audio_decision = "direct", effective_play_method = "direct",
        source_video_codec = "h264", source_video_resolution = "1920x1080", source_audio_codec = "eac3", source_audio_channels = 6,
    )

    @Composable
    private fun Host(reduced: Boolean = true, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalReducedMotionOverride provides reduced) {
            VeilAdminTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF070B11)).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    content()
                }
            }
        }
    }

    /** The row is at most the page's width less its two 16 dp gutters: nothing runs off a 320 dp screen. */
    private fun SemanticsNodeInteraction.assertFitsTheGutters() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val widthDp = fetchSemanticsNode().size.width / density
        // The page's two 16 dp gutters leave the screen's width less 32 dp (288 dp on CI's 320 dp screen).
        val screenDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.let { it.widthPixels / it.density }
        assertTrue("row is $widthDp dp wide on a $screenDp dp screen", widthDp <= screenDp - 32f + 0.5f)
    }

    private fun tag(id: String) = STREAM_ROW_TAG_PREFIX + id
    private fun detailsTag(id: String) = STREAM_DETAILS_TAG_PREFIX + id

    private fun describedBy(fragment: String) = SemanticsMatcher("content description contains \"$fragment\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(fragment) } == true
    }

    @Test
    fun aTranscodeShowsWhoWhatAndHowAtPhoneWidth() {
        compose.setContent { Host { StreamRow(episode) } }

        compose.onNodeWithTag(tag("ep")).assertFitsTheGutters()
        compose.onNodeWithText("Severance · S2E3 · Who Is Alive?").assertIsDisplayed()
        compose.onNodeWithText("alex · Living room").assertIsDisplayed()
        compose.onNodeWithText("Silo Android 1.4.2 · Android").assertIsDisplayed()
        compose.onNodeWithText("Playing").assertIsDisplayed()
        compose.onNodeWithText("Transcode").assertIsDisplayed()
        compose.onNodeWithText("12:34 / 44:12").assertIsDisplayed()
        // The signal path: source, playback decision (the lit step) and client, then the bitrate.
        compose.onNodeWithText("Source").assertIsDisplayed()
        compose.onNodeWithText("Playback").assertIsDisplayed()
        compose.onNodeWithText("Client").assertIsDisplayed()
        compose.onNodeWithText("HEVC 4K · MKV").assertIsDisplayed()
        compose.onNodeWithText("HW NVENC · to H.264 1080p").assertIsDisplayed()
        compose.onNodeWithText("8.2 Mbps").assertIsDisplayed()
    }

    @Test
    fun aPausedDirectPlayNamesItselfAndShowsNoConversion() {
        compose.setContent { Host { StreamRow(direct) } }

        compose.onNodeWithText("The River").assertIsDisplayed()
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText("Direct play").assertIsDisplayed()
        compose.onNodeWithText("20:00 / 1:30:00").assertIsDisplayed()
        compose.onNodeWithText("H.264 1080p").assertIsDisplayed()
        compose.onNodeWithText("Web").assertIsDisplayed()
    }

    @Test
    fun aRowWithNothingButTheRequiredFieldsStillRenders() {
        val bare = AdminSession(
            session_id = "bare", user_id = "u3", username = "kim", profile_id = "p3", media_title = "Live channel",
            media_type = "movie", play_method = "", position_seconds = 61.0, file_duration = null, is_paused = false,
        )
        compose.setContent { Host { StreamRow(bare) } }

        compose.onNodeWithText("Live channel").assertIsDisplayed()
        compose.onNodeWithText("kim").assertIsDisplayed()
        compose.onNodeWithText("Unknown").assertIsDisplayed()
        // Without a length there is a position and no bar, no remaining time.
        compose.onNodeWithText("1:01").assertIsDisplayed()
        compose.onNodeWithText("left", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingARowOpensItsDetailsAndTappingAgainClosesThem() {
        compose.setContent { Host { StreamRow(episode, showClientIp = true) } }

        compose.onNodeWithTag(detailsTag("ep"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(tag("ep")).performClick()

        val details = compose.onNodeWithTag(detailsTag("ep"), useUnmergedTree = true)
        details.performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Container").assertExists()
        compose.onNodeWithText("MKV → HLS · MPEG-TS").assertExists()
        compose.onNodeWithText("24.5 Mbps").assertExists()
        compose.onNodeWithText("Silo Android 1.4.2 (88) beta").assertExists()
        compose.onNodeWithText("Transcode: gpu-1, Server: main").assertExists()
        compose.onNodeWithText("Remote").assertExists()
        compose.onNodeWithText("HEVC 4K · Output → H.264 1080p").assertExists()
        compose.onNodeWithText("TrueHD 7.1 · Output → AAC 5.1").assertExists()
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("Started").assertExists()
        compose.onNodeWithText("Last update").assertExists()
        compose.onNodeWithTag(tag("ep")).assertFitsTheGutters()

        compose.onNodeWithTag(tag("ep")).performClick()
        compose.onNodeWithTag(detailsTag("ep"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theClientAddressShowsOnlyWhenTheCallerIsPlatformAdmin() {
        var platform by mutableStateOf(false)
        compose.setContent { Host { StreamRow(episode, showClientIp = platform) } }

        compose.onNodeWithTag(tag("ep")).performClick()
        compose.onNodeWithText("Client IP").assertDoesNotExist()
        compose.onNodeWithText("203.0.113.9").assertDoesNotExist()

        compose.runOnIdle { platform = true }
        compose.onNodeWithText("Client IP").assertExists()
        compose.onNodeWithText("203.0.113.9").assertExists()
    }

    @Test
    fun theRowsDescriptionReadsTheKeyFacts() {
        compose.setContent { Host { StreamRow(episode) } }

        val node = compose.onNode(describedBy("Severance · S2E3 · Who Is Alive?"))
        node.assertExists()
        val description = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString(" ")
        listOf("alex · Living room", "Silo Android 1.4.2 · Android", "Playing", "Transcode", "12:34 of 44:12, 31m left", "HEVC 4K → H.264 1080p", "TrueHD 7.1 → AAC 5.1", "HW NVENC")
            .forEach { assertTrue("description lacks \"$it\": $description", description.contains(it)) }
        assertEquals("Details hidden", node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        node.performClick()
        assertEquals("Details shown", compose.onNode(describedBy("Severance")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun theCornerControlsStayReachableAndDoNotToggleTheRow() {
        var tapped = 0
        compose.setContent {
            Host {
                StreamRow(episode, actions = { Button(onClick = { tapped += 1 }) { Text("Menu") } })
            }
        }

        compose.onNodeWithText("Menu").performClick()
        compose.runOnIdle { assertEquals(1, tapped) }
        compose.onNodeWithTag(detailsTag("ep"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aPlayingStreamMovesForwardBetweenPollsAndAPausedOneDoesNot() {
        var now = 1_000L
        var current by mutableStateOf(episode.copy(position_seconds = 10.0, file_duration = 100.0))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalStreamClock provides { now }) {
                Host(reduced = false) { StreamRow(current) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("0:10 / 1:40").assertExists()

        now = 4_000L
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("0:13 / 1:40").assertExists()

        // A new report replaces the projection instead of adding to it.
        compose.runOnIdle { current = current.copy(position_seconds = 20.0) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("0:20 / 1:40").assertExists()

        // Pausing holds the reported position however long the clock runs.
        compose.runOnIdle { current = current.copy(is_paused = true) }
        compose.mainClock.advanceTimeByFrame()
        now = 60_000L
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("0:20 / 1:40").assertExists()
    }

    @Test
    fun withReducedMotionNothingMovesBetweenPolls() {
        var now = 1_000L
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalStreamClock provides { now }) {
                Host(reduced = true) { StreamRow(episode.copy(position_seconds = 10.0, file_duration = 100.0)) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        now = 30_000L
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("0:10 / 1:40").assertExists()
    }

    @Test
    fun aMovingStreamStopsAtTheEndOfTheFile() {
        var now = 0L
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalStreamClock provides { now }) {
                Host(reduced = false) { StreamRow(episode.copy(position_seconds = 98.0, file_duration = 100.0)) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        now = 120_000L
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("1:40 / 1:40").assertExists()
    }

    @Test
    fun theDetailsFitAtALargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                Host { StreamRow(episode, showClientIp = true) }
            }
        }

        compose.onNodeWithTag(tag("ep")).performClick()
        compose.onNodeWithTag(tag("ep")).assertFitsTheGutters()
        compose.onNodeWithText("203.0.113.9").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun severalRowsKeepTheirOwnExpandedState() {
        compose.setContent {
            Host {
                Column {
                    StreamRow(episode)
                    StreamRow(direct)
                }
            }
        }

        compose.onNodeWithTag(tag("film")).performClick()
        compose.onNodeWithTag(detailsTag("film"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(detailsTag("ep"), useUnmergedTree = true).assertDoesNotExist()
    }
}

