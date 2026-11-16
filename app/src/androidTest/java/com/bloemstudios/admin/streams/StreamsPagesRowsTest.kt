package com.bloemstudios.admin.streams

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Now Playing pages draw the same row, with their own stream controls, and open its details on a tap. */
@RunWith(AndroidJUnit4::class)
class StreamsPagesRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun session(id: String, user: String = "user-$id", paused: Boolean = false) = AdminSession(
        session_id = id, user_id = "u-$id", username = user, profile_id = "p-$id", media_title = "Title $id",
        media_type = "movie", play_method = "transcode", position_seconds = 60.0, file_duration = 600.0, is_paused = paused,
        client_name = "Silo Web", client_ip = if (id == "1") "198.51.100.1" else "198.51.100.7",
        stream_bitrate_kbps = 4000, video_decision = "transcode", audio_decision = "direct", effective_play_method = "transcode",
        transcode_hw_accel = "vaapi", source_video_codec = "h264", source_video_resolution = "1080p", target_video_codec = "hevc",
        target_resolution = "720p", source_container = "mkv", reporting_node = "main",
    )

    private fun noStreamActions() = StreamActions(
        onPause = {}, onResume = {}, onStop = {}, onTerminate = {},
        onSendMessage = { _, _, _ -> SendMessageResult.Success },
    )

    private fun noAggregateActions() = AggregateStreamActions(
        onPause = { _, _ -> }, onResume = { _, _ -> }, onStop = { _, _ -> }, onTerminate = { _, _ -> }, onSendMessage = { _, _, _, _ -> SendMessageResult.Success },
        onSignIn = {}, onRetry = {},
    )

    @Test
    fun anOrganizationContextNeverGetsTheClientAddress() {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                VeilAdminTheme { StreamsVeilContent(true, listOf(session("1")), null, {}, noStreamActions()) }
            }
        }
        compose.onNodeWithTag(STREAM_ROW_TAG_PREFIX + "1").performClick()
        compose.onNodeWithText("Container").assertIsDisplayed()
        compose.onAllNodesWithText("Client IP").assertCountEquals(0)
        compose.onAllNodesWithText("198.51.100.1").assertCountEquals(0)
    }

    @Test
    fun thePlatformAdminSeesTheClientAddressOnTheStreamsPage() {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                VeilAdminTheme { StreamsVeilContent(true, listOf(session("1")), null, {}, noStreamActions(), showClientIp = true) }
            }
        }
        compose.onNodeWithTag(STREAM_ROW_TAG_PREFIX + "1").performClick()
        compose.onNodeWithText("Client IP").assertIsDisplayed()
        compose.onNodeWithText("198.51.100.1").assertIsDisplayed()
    }

    @Test
    fun theOverflowMenuStillRunsTheControlsAndDoesNotOpenTheDetails() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                VeilAdminTheme {
                    StreamsVeilContent(
                        true, listOf(session("1", paused = false)), null, {},
                        StreamActions(
                            onPause = { calls += "pause:${it.session_id}" }, onResume = {}, onStop = { calls += "stop:${it.session_id}" },
                            onTerminate = {}, onSendMessage = { s, _, _ -> calls += "message:${s.session_id}"; SendMessageResult.Success },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Actions for user-1").performClick()
        compose.onNodeWithText("Stop playback").performClick()
        compose.onNodeWithContentDescription("Actions for user-1").performClick()
        compose.onNodeWithText("Pause").performClick()
        compose.runOnIdle { assertEquals(listOf("stop:1", "pause:1"), calls) }
        compose.onAllNodesWithText("Container").assertCountEquals(0)
    }

    @Test
    fun everyServersSectionShowsTheSameRowsAndOpensThemIndependently() {
        val alpha = AdminServer("a", "https://alpha.invalid", "Alpha")
        val beta = AdminServer("b", "https://beta.invalid", "Beta")
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                VeilAdminTheme {
                    LazyColumn {
                        items(listOf(alpha to session("alpha"), beta to session("beta", user = "sam", paused = true))) { (server, row) ->
                            LiveServerStreamsSection(server, listOf(row), RealtimeState.Live, null, false, noAggregateActions(), showClientIp = true)
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Title alpha").assertIsDisplayed()
        compose.onNodeWithText("Title beta").assertIsDisplayed()
        compose.onAllNodesWithText("HW VAAPI · to HEVC 720p").assertCountEquals(2)
        compose.onAllNodesWithText("4.0 Mbps").assertCountEquals(2)
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithTag(STREAM_ROW_TAG_PREFIX + "beta").performScrollTo().performClick()
        compose.onNodeWithTag(STREAM_DETAILS_TAG_PREFIX + "beta", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(STREAM_DETAILS_TAG_PREFIX + "alpha", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Client IP").assertExists()
    }

    @Test
    fun theAllServersPageUsesTheSameRowAndKeepsTheClientAddressForThePlatformAdmin() {
        val alpha = AdminServer("a", "https://alpha.invalid", "Alpha")
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                VeilAdminTheme {
                    AggregateStreamsVeilContent(
                        hasLoadedOnce = true,
                        sections = listOf(ServerSectionResult(alpha, Result.success(listOf(session("alpha"))))),
                        errorMessage = null,
                        actions = noAggregateActions(),
                        showClientIp = true,
                    )
                }
            }
        }

        compose.onNodeWithText("Title alpha").assertIsDisplayed()
        compose.onNodeWithTag(STREAM_ROW_TAG_PREFIX + "alpha").performClick()
        compose.onNodeWithText("198.51.100.7").assertIsDisplayed()
    }

    @Test
    fun theAllServersRowMenuOffersMessageAndSendsItToThatServerAndSession() {
        val alpha = AdminServer("a", "https://alpha.invalid", "Alpha")
        val beta = AdminServer("b", "https://beta.invalid", "Beta")
        val sent = mutableListOf<List<String?>>()
        val actions = AggregateStreamActions(
            onPause = { _, _ -> }, onResume = { _, _ -> }, onStop = { _, _ -> }, onTerminate = { _, _ -> },
            onSendMessage = { server, session, title, message -> sent += listOf(server.id, session.session_id, title, message); SendMessageResult.Success },
            onSignIn = {}, onRetry = {},
        )
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotionOverride provides true) {
                VeilAdminTheme {
                    AggregateStreamsVeilContent(
                        hasLoadedOnce = true,
                        sections = listOf(
                            ServerSectionResult(alpha, Result.success(listOf(session("one", "Ana")))),
                            ServerSectionResult(beta, Result.success(listOf(session("two", "Bo")))),
                        ),
                        errorMessage = null,
                        actions = actions,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Actions for Bo").performClick()
        compose.onNodeWithText("Message").performClick()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("Back in five")
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(listOf(listOf("b", "two", null, "Back in five")), sent)
        compose.onNodeWithText("Message sent").assertIsDisplayed()
    }
}
