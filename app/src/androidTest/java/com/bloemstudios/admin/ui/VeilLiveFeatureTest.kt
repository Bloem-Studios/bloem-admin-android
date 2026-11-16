package com.bloemstudios.admin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.activity.ActivityVeilContent
import com.bloemstudios.admin.activity.CompletionFilter
import com.bloemstudios.admin.activity.HistoryFilter
import com.bloemstudios.admin.activity.HistoryFilterUi
import com.bloemstudios.admin.users.UserChoice
import com.bloemstudios.admin.activity.AggregateActivityVeilContent
import com.bloemstudios.admin.activity.PlaybackHistoryEntry
import com.bloemstudios.admin.dashboard.AdminStats
import com.bloemstudios.admin.dashboard.DashboardVeilContent
import com.bloemstudios.admin.dashboard.SystemResources
import com.bloemstudios.admin.dashboard.SystemStats
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.AggregateStreamActions
import com.bloemstudios.admin.streams.AggregateStreamsVeilContent
import com.bloemstudios.admin.streams.SendMessageResult
import com.bloemstudios.admin.streams.StreamActions
import com.bloemstudios.admin.streams.StreamsVeilContent
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VeilLiveFeatureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun healthSectionShowsTheHostResources() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(AdminShellContext("Test server", false), singleServerDestinations(false),
                    "overview.dashboard", {}, false, {}) {
                    DashboardVeilContent(true, stats(7), SystemResources(true, sampled_at = "2026-10-12T00:00:00Z", system = SystemStats(42.5, 954, 3815, 123.4, 56.7)),
                        null, {})
                }
            }
        }
        compose.onNodeWithText("CPU", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Memory", substring = true).assertIsDisplayed()
    }

    @Test
    fun dashboardMetricsRenderInsideTheRealSingleServerContext() {
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    context = AdminShellContext("Test server", isAggregate = false),
                    destinations = singleServerDestinations(tenantsVisible = false),
                    selectedDestinationId = "overview.dashboard",
                    onDestinationSelected = {},
                    useNavigationRail = false,
                    onContextRequested = {},
                ) {
                    DashboardVeilContent(
                        hasLoadedOnce = true,
                        stats = stats(activeStreams = 7),
                        hostStats = SystemResources(available = false),
                        errorMessage = null,
                        onRetry = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Test server").assertIsDisplayed()
        compose.onNodeWithText("Now playing").assertIsDisplayed()
        // Until the live session list arrives there is no Active streams tile, and the
        // sampled stream count must not masquerade as current session data.
        compose.onNodeWithText("Active streams").assertDoesNotExist()
        compose.onNodeWithText("7").assertDoesNotExist()
    }

    @Test
    fun dashboardStaleMetricsRemainBesidePartialFailure() {
        var retries = 0
        compose.setContent {
            veilHost {
                DashboardVeilContent(
                    hasLoadedOnce = true,
                    stats = stats(activeStreams = 4),
                    hostStats = SystemResources(available = false),
                    errorMessage = "Stats are temporarily stale",
                    onRetry = { retries += 1 },
                )
            }
        }

        compose.onNodeWithTag("state-partialfailure")
            .assertStateDescription("Overview partially refreshed")
        compose.onNodeWithText("Movies").assertIsDisplayed()
        compose.onNodeWithText("Active streams").assertDoesNotExist()
        compose.onNodeWithText("4").assertDoesNotExist()
        compose.onNodeWithText("Try again")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun streamsUseSharedLoadingEmptyAndFailureStates() {
        var state by mutableStateOf(0)
        var retries = 0
        compose.setContent {
            veilHost {
                StreamsVeilContent(
                    hasLoadedOnce = state != 0,
                    sessions = emptyList(),
                    errorMessage = null,
                    sourceError = if (state == 2) com.bloemstudios.admin.realtime.SessionFailure.Connection else null,
                    sessionDataAvailable = state == 1,
                    onRetry = { retries += 1 },
                    actions = noStreamActions(),
                )
            }
        }

        compose.onNodeWithTag("state-loading")
            .assertStateDescription("Loading active streams")
        compose.runOnIdle { state = 1 }
        compose.onNodeWithText("No streams", useUnmergedTree = true)
            .assert(isHeading())
        compose.onNodeWithText("No streams").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { state = 2 }
        compose.onNodeWithTag("state-failure")
            .assertStateDescription("Active streams unavailable")
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun streamsRetainRowsTechnicalDataAndEveryActionCallback() {
        val calls = mutableListOf<String>()
        compose.setContent {
            veilHost {
                StreamsVeilContent(
                    hasLoadedOnce = true,
                    sessions = listOf(session("playing", paused = false), session("paused", paused = true)),
                    errorMessage = "The latest refresh failed",
                    onRetry = {},
                    actions = StreamActions(
                        onPause = { calls += "pause:${it.session_id}" },
                        onResume = { calls += "resume:${it.session_id}" },
                        onStop = { calls += "stop:${it.session_id}" },
                        onTerminate = { calls += "terminate:${it.session_id}" },
                        onSendMessage = { item, _, _ ->
                            calls += "message:${item.session_id}"
                            SendMessageResult.Success
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("state-partialfailure").assertIsDisplayed()
        compose.onAllNodesWithText("Series · S2E3 · Episode").assertCountEquals(2)
        compose.onAllNodesWithText("Web").assertCountEquals(2)
        compose.onAllNodesWithText("0:50 / 1:40").assertCountEquals(2)
        compose.onAllNodesWithText("Transcode").assertCountEquals(2)
        compose.onAllNodesWithText("HW VAAPI · to HEVC 720p").assertCountEquals(2)
        compose.onNodeWithContentDescription("Actions for user-playing").performClick()
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithContentDescription("Actions for user-playing").performClick()
        compose.onNodeWithText("Stop playback").performClick()
        compose.onNodeWithContentDescription("Actions for user-playing").performClick()
        compose.onNodeWithText("End session").performClick()
        compose.onNodeWithText("End session", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Actions for user-paused").performClick()
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithContentDescription("Actions for user-playing").performClick()
        compose.onNodeWithText("Message").performClick()
        compose.onNodeWithText("Message", substring = false).performTextInput("Hello")
        compose.onNodeWithText("Send").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf("pause:playing", "stop:playing", "terminate:playing", "resume:paused", "message:playing"),
                calls,
            )
        }
    }

    @Test
    fun streamModalsLockContextUntilDismissed() {
        var contextRequests = 0
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    context = AdminShellContext("Locked server", isAggregate = false),
                    destinations = singleServerDestinations(tenantsVisible = false),
                    selectedDestinationId = "live.streams",
                    onDestinationSelected = {},
                    useNavigationRail = false,
                    onContextRequested = { contextRequests += 1 },
                ) {
                    StreamsVeilContent(
                        hasLoadedOnce = true,
                        sessions = listOf(session("one", paused = false)),
                        errorMessage = null,
                        onRetry = {},
                        actions = noStreamActions(),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Actions for user-one").performClick()
        compose.onNodeWithContentDescription("Change server context from Locked server")
            .assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, contextRequests) }
        compose.onNodeWithText("Message").performClick()
        compose.onNodeWithContentDescription("Change server context from Locked server")
            .assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, contextRequests) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Actions for user-one").performClick()
        compose.onNodeWithText("End session").performClick()
        compose.onNodeWithContentDescription("Change server context from Locked server")
            .assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, contextRequests) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Change server context from Locked server").performClick()
        compose.runOnIdle { assertEquals(1, contextRequests) }
    }

    @Test
    fun streamMenuReleasesContextWhenItsPolledRowDisappears() {
        var contextRequests = 0
        var sessions by mutableStateOf(listOf(session("vanishing", paused = false)))
        compose.setContent {
            VeilAdminTheme {
                AdminShell(
                    context = AdminShellContext("Polling server", isAggregate = false),
                    destinations = singleServerDestinations(tenantsVisible = false),
                    selectedDestinationId = "live.streams",
                    onDestinationSelected = {},
                    useNavigationRail = false,
                    onContextRequested = { contextRequests += 1 },
                ) {
                    StreamsVeilContent(true, sessions, null, {}, noStreamActions())
                }
            }
        }

        compose.onNodeWithContentDescription("Actions for user-vanishing").performClick()
        compose.onNodeWithContentDescription("Change server context from Polling server").assertIsNotEnabled()
        compose.runOnIdle { sessions = emptyList() }
        compose.onNodeWithContentDescription("Change server context from Polling server").performClick()
        compose.runOnIdle { assertEquals(1, contextRequests) }
    }

    @Test
    fun aggregateStreamsKeepHealthyServerBesideExactServerRecovery() {
        val alpha = server("alpha", "Alpha")
        val beta = server("beta", "Beta")
        val gamma = server("gamma", "Gamma")
        var signedIn: AdminServer? = null
        var retried: AdminServer? = null
        compose.setContent {
            veilHost {
                AggregateStreamsVeilContent(
                    hasLoadedOnce = true,
                    sections = listOf(
                        ServerSectionResult(alpha, Result.success(listOf(session("alpha-session", false)))),
                        ServerSectionResult(beta, Result.failure(ApiError(401, "expired"))),
                        ServerSectionResult(gamma, Result.failure(IllegalStateException("offline"))),
                    ),
                    errorMessage = null,
                    actions = AggregateStreamActions(
                        onPause = { _, _ -> }, onResume = { _, _ -> }, onStop = { _, _ -> },
                        onTerminate = { _, _ -> }, onSendMessage = { _, _, _, _ -> SendMessageResult.Success }, onSignIn = { signedIn = it }, onRetry = { retried = it },
                    ),
                )
            }
        }

        compose.onNodeWithText("Alpha", useUnmergedTree = true).assert(isHeading())
        compose.onNodeWithText("Series · S2E3 · Episode").assertIsDisplayed()
        compose.onNodeWithText("Beta", useUnmergedTree = true).assert(isHeading())
        compose.onNodeWithText("Sign in").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertSame(beta, signedIn) }
        compose.onNodeWithText("Gamma", useUnmergedTree = true).assert(isHeading())
        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertSame(gamma, retried) }
        compose.onAllNodesWithText("Active streams unavailable").assertCountEquals(0)
    }

    @Test
    fun activityKeepsFilterPagingAndDistinctEmptyState() {
        var loadMore = 0
        var retries = 0
        var chosenUser: String? = "unset"
        var chosenStatus: CompletionFilter? = null
        var filter by mutableStateOf(HistoryFilter())
        var entries by mutableStateOf(emptyList<PlaybackHistoryEntry>())
        var errorMessage by mutableStateOf<String?>(null)
        compose.setContent {
            veilHost {
                ActivityVeilContent(
                    hasLoadedOnce = true,
                    entries = entries,
                    errorMessage = errorMessage,
                    hasMore = true,
                    filters = HistoryFilterUi(
                        filter = filter, users = listOf(UserChoice("42", "maria", "maria@example.test")), usersError = null,
                        profiles = emptyList(), profilesError = null,
                        onUserChange = { chosenUser = it }, onProfileChange = {}, onCompletionChange = { chosenStatus = it },
                    ),
                    onRetry = { retries += 1 },
                    onLoadMore = { loadMore += 1 },
                )
            }
        }

        compose.onNodeWithText("No activity yet", useUnmergedTree = true).assert(isHeading())
        compose.onNodeWithText("Playback history will appear here automatically").assertIsDisplayed()
        compose.onNodeWithText("Partial").performClick()
        compose.runOnIdle {
            assertEquals(CompletionFilter.Partial, chosenStatus)
            filter = HistoryFilter(userId = "42")
        }
        compose.onNodeWithText("No plays match these filters", useUnmergedTree = true).assert(isHeading())
        compose.onNodeWithText("Change the user, profile or status to see more").assertIsDisplayed()
        compose.onNodeWithText("maria").assertIsDisplayed()
        compose.runOnIdle {
            entries = listOf(history("page-one"))
            errorMessage = "Latest page failed"
        }
        compose.onNodeWithTag("state-partialfailure").assertIsDisplayed()
        compose.onNodeWithText("Title page-one").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Load more").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, loadMore)
            assertEquals("unset", chosenUser)
        }
    }

    @Test
    fun aggregateActivityKeepsHealthyServerBesideRecoveryAndPaging() {
        val alpha = server("alpha", "Alpha")
        val beta = server("beta", "Beta")
        val gamma = server("gamma", "Gamma")
        var signedIn: AdminServer? = null
        var retried: AdminServer? = null
        var loadedMore: AdminServer? = null
        var filterChanged by mutableStateOf("42")
        compose.setContent {
            veilHost {
                AggregateActivityVeilContent(
                    hasLoadedOnce = true,
                    sections = listOf(
                        ServerSectionResult(alpha, Result.success(listOf(history("history-one")))),
                        ServerSectionResult(beta, Result.failure(ApiError(403, "forbidden"))),
                        ServerSectionResult(gamma, Result.failure(IllegalStateException("offline"))),
                    ),
                    hasMoreByServer = mapOf(alpha.id to true),
                    errorMessage = "One action failed",
                    userIdFilter = filterChanged,
                    onFilterChange = { filterChanged = it },
                    onSignIn = { signedIn = it },
                    onRetry = { retried = it },
                    onLoadMore = { loadedMore = it },
                )
            }
        }

        compose.onAllNodesWithTag("state-partialfailure").assertCountEquals(3)
        compose.onNodeWithText("Title history-one").assertIsDisplayed()
        compose.onNodeWithText("Load more").performClick()
        compose.runOnIdle { assertSame(alpha, loadedMore) }
        compose.onNodeWithText("Sign in").performClick()
        compose.runOnIdle { assertSame(beta, signedIn) }
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.runOnIdle { assertSame(gamma, retried) }
        compose.onNodeWithText("Filter by user ID").performTextClearance()
        compose.onNodeWithText("Filter by user ID").performTextInput("7x")
        compose.runOnIdle { assertEquals("7x", filterChanged) }
    }

    @androidx.compose.runtime.Composable
    private fun veilHost(content: @androidx.compose.runtime.Composable () -> Unit) {
        VeilAdminTheme(content = content)
    }

    private fun stats(activeStreams: Int) = AdminStats(
        total_items = 10,
        total_files = 11,
        total_users = 12,
        total_movies = 13,
        total_movie_files = 14,
        total_shows = 15,
        total_show_files = 16,
        active_streams = activeStreams,
        total_storage_bytes = 1_000,
        watch_providers = emptyList(),
    )

    private fun session(id: String, paused: Boolean) = AdminSession(
        session_id = id,
        user_id = "user-id-$id",
        username = "user-$id",
        profile_id = "profile-$id",
        media_title = "Title $id",
        media_type = "episode",
        play_method = "transcode",
        position_seconds = 50.0,
        file_duration = 100.0,
        is_paused = paused,
        client_name = "Web",
        stream_bitrate_kbps = null,
        video_decision = "transcode",
        audio_decision = "direct",
        effective_play_method = null,
        transcode_hw_accel = "vaapi",
        source_video_resolution = "1080p",
        target_resolution = "720p",
        source_video_codec = "h264",
        target_video_codec = "hevc",
        source_audio_codec = null,
        target_audio_codec = null,
        series_name = "Series",
        episode_name = "Episode",
        season_number = 2,
        episode_number = 3,
    )

    private fun noStreamActions() = StreamActions(
        onPause = {},
        onResume = {},
        onStop = {},
        onTerminate = {},
        onSendMessage = { _, _, _ -> SendMessageResult.Success },
    )

    private fun history(id: String) = PlaybackHistoryEntry(
        session_id = id,
        user_id = "user-$id",
        username = "history-user",
        media_title = "Title $id",
        media_type = "movie",
        watched_seconds = 10.0,
        duration_seconds = 20.0,
        completed = true,
        started_at = "2026-08-31T00:00:00Z",
        ended_at = null,
    )

    private fun server(id: String, name: String) =
        AdminServer(id, "https://$id.example.com", name)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertStateDescription(expected: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
}
