package com.bloemstudios.admin.streams

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Now playing, when the live sessions cannot be read: one state, not a stack of "Reconnecting", "Loading live
 * sessions" and "Try again" at once. docs/design.md asks for what went wrong, how to fix it, and Try again.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingFailureStateTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = AdminServer("home", "https://fixture.invalid", "Audit Home")

    private fun row() = AdminSession(
        session_id = "s1", user_id = "u1", username = "ada", profile_id = "p1", media_title = "The River",
        media_type = "movie", play_method = "direct", position_seconds = 60.0, file_duration = 600.0, is_paused = false,
        client_name = "Silo Web", stream_bitrate_kbps = 4000,
    )

    private fun actions(onRetry: (AdminServer) -> Unit = {}, onSignIn: (AdminServer) -> Unit = {}) = AggregateStreamActions(
        onPause = { _, _ -> }, onResume = { _, _ -> }, onStop = { _, _ -> }, onTerminate = { _, _ -> },
        onSendMessage = { _, _, _, _ -> SendMessageResult.Success }, onSignIn = onSignIn, onRetry = onRetry,
    )

    private fun section(sessions: List<AdminSession>?, state: RealtimeState, error: SessionFailure?, expired: Boolean = false,
        actions: AggregateStreamActions = actions()) {
        compose.setContent {
            VeilAdminTheme {
                LazyColumn { item { LiveServerStreamsSection(server, sessions, state, error, expired, actions) } }
            }
        }
    }

    @Test
    fun noRowsAndAFailedReadIsOneErrorWithOneTryAgain() {
        var retries = 0
        section(null, RealtimeState.Reconnecting, SessionFailure.Connection, actions = actions(onRetry = { retries++ }))
        compose.onNodeWithTag("state-failure").assertIsDisplayed()
        compose.onNodeWithText("Active streams unavailable").assertIsDisplayed()
        compose.onNodeWithText(
            "The connection to this server was lost. Check that the server is running and that this device is online, then try again.",
        ).assertIsDisplayed()
        // What used to pile up beside the error.
        compose.onAllNodesWithText("Loading live sessions…").assertCountEquals(0)
        compose.onAllNodesWithText("Reconnecting").assertCountEquals(0)
        compose.onAllNodesWithText("Connection lost. Reconnecting and checking sessions.").assertCountEquals(0)
        compose.onAllNodesWithText("Try again").assertCountEquals(1)
        compose.onNodeWithText("Audit Home").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun theFirstReadIsLoadingWithNothingToRetry() {
        section(null, RealtimeState.Connecting, null)
        compose.onNodeWithText("Loading live sessions…").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onAllNodes(hasTestTag("state-failure")).assertCountEquals(0)
    }

    /** The socket is not in use and the page reads the list itself: that is loading, not a failure. */
    @Test
    fun pollingWithNothingReadYetIsStillLoading() {
        section(null, RealtimeState.Unavailable, SessionFailure.Unsupported)
        compose.onNodeWithText("Loading live sessions…").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun staleRowsKeepOneStatusAndOneLine() {
        section(listOf(row()), RealtimeState.Reconnecting, SessionFailure.Connection)
        compose.onNodeWithText("The River").assertIsDisplayed()
        compose.onAllNodesWithText("Reconnecting").assertCountEquals(1)
        compose.onAllNodesWithText("Live sessions unavailable. Showing the last reported data.").assertCountEquals(1)
        compose.onAllNodesWithText("Connection lost. Reconnecting and checking sessions.").assertCountEquals(0)
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun liveRowsShowOnlyTheLiveStatus() {
        section(listOf(row()), RealtimeState.Live, null)
        compose.onAllNodesWithText("Live").assertCountEquals(1)
        compose.onAllNodesWithText("Live sessions unavailable. Showing the last reported data.").assertCountEquals(0)
    }

    @Test
    fun aSignedOutServerOffersOnlySignIn() {
        var signIns = 0
        section(null, RealtimeState.SignInRequired, SessionFailure.SignInRequired, expired = true, actions = actions(onSignIn = { signIns++ }))
        compose.onNodeWithText("Sign in to continue").assertIsDisplayed()
        compose.onAllNodesWithText("Loading live sessions…").assertCountEquals(0)
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onNodeWithText("Sign in").performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
    }

    @Test
    fun theSingleServerPageShowsTheSameOneError() {
        var retries = 0
        compose.setContent {
            VeilAdminTheme {
                StreamsVeilContent(
                    hasLoadedOnce = true, sessions = emptyList(), errorMessage = null, onRetry = { retries++ },
                    actions = StreamActions({}, {}, {}, {}, { _, _, _ -> SendMessageResult.Success }),
                    sourceState = RealtimeState.Reconnecting, sourceError = SessionFailure.Connection, sessionDataAvailable = false,
                )
            }
        }
        compose.onNodeWithTag("state-failure").assertIsDisplayed()
        compose.onAllNodesWithText("Reconnecting").assertCountEquals(0)
        compose.onAllNodesWithText("Connection lost. Reconnecting and checking sessions.").assertCountEquals(0)
        compose.onAllNodesWithText("Try again").assertCountEquals(1)
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
