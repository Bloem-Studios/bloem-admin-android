package com.bloemstudios.admin.dashboard

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.activity.PlaybackHistoryEntry
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.serverconfig.ServerStatus
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The dashboard follows the web's default layout: tiles, health, now playing, trends, top lists, libraries, users, recent activity.
 * Concurrent streams, which the web leaves out of its default, follows the two trends it does show.
 */
@RunWith(AndroidJUnit4::class)
class DashboardSectionOrderTest {
    @get:Rule val compose = createComposeRule()

    private val stats = AdminStats(24, 28, 3, 20, 24, 4, 4, 99, 1_000_000_000L, emptyList())
    private val host = SystemResources(true, sampled_at = "2026-10-12T00:00:00Z", system = SystemStats(18.2, 2384, 7629, 1_500_000.0, 9_200_000.0))
    private val session = AdminSession("one", "user-1", "Alex", "profile-1", "The River", "movie", "Direct play", 1200.0, 5400.0, false, "Living room")
    private val now = Instant.now().toString()
    private val status = ServerStatus(started_at = Instant.now().minusSeconds(3 * 86_400 + 4 * 3600).toString(),
        restart_required = false, restart_requested = false,
        health = ServerHealth(HealthComponent(true, true, 1.2), HealthComponent(false), errors_24h = 3, warnings_24h = 5))
    private val full = DashboardInsights(
        status = SectionState(status),
        build = SectionState(BuildInfo(true, 312, false, "v1.4", "abc")),
        nodes = SectionState(listOf(DashboardNode("n1", "Node 1", true, true), DashboardNode("n2", "Node 2", true, false))),
        activity = SectionState(PlaybackActivity(buckets = listOf(PlaybackBucket(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString(), 3, 1, 2)), profiles_active_24h = 2,
            reliability = PlaybackReliability(finalized_sessions = 4, completed_sessions = 3, completion_rate = 0.75))),
        egressNow = SectionState(Timeseries(points = listOf(TimeseriesPoint(now, 1, 12_000.0)))),
        egress24h = SectionState(Timeseries(points = listOf(TimeseriesPoint(now, 1, 12_000.0), TimeseriesPoint(now, 1, 2_000.0)))),
        top = SectionState(TopActivity(titles = listOf(TopTitle("m1", "Northern Lights", "series", 9, 36_000.0)),
            profiles = listOf(TopProfile("u1", "alex", "p1", "Kids", 9, 36_000.0)))),
        libraries = SectionState(listOf(DashboardLibrary("l1", "Films", "movies", listOf("/a", "/b"), true), DashboardLibrary("l2", "Old", "shows", listOf("/c"), false))),
        users = SectionState(listOf(AdminUser("u1", "maria", "m@example.test", "admin", true, last_active_at = now))),
        recent = SectionState(listOf(PlaybackHistoryEntry("s1", "u1", "sam", "Harbour", "movie", 60.0, 5400.0, false, now))),
    )
    private val orderedTags = DashboardSection.entries.map { it.tag }

    @Composable private fun Frame(largeFont: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 2f else density.fontScale)) {
            VeilAdminTheme { content() }
        }
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun assertTagsInOrder(expected: List<String>) {
        val tops = expected.map { it to top(it) }
        assertEquals("sections must be placed top to bottom in the web's order: $tops", tops.sortedBy { it.second }.map { it.first }, expected)
        assertTrue("sections must not overlap: $tops", tops.zipWithNext().all { (a, b) -> a.second < b.second })
    }

    private fun setSingle(
        insights: DashboardInsights = full, stats: AdminStats? = this.stats, sessions: List<AdminSession>? = listOf(session),
        host: SystemResources? = this.host, largeFont: Boolean = false, onStreams: (() -> Unit)? = {},
    ) = compose.setContent {
        Frame(largeFont) {
            DashboardVeilContent(true, stats, host, null, {}, sessions = sessions, onStreamsRequested = onStreams,
                sourceState = RealtimeState.Live, insights = insights, onUsersRequested = {})
        }
    }

    @Test fun singleServerSectionsFollowTheWebOrder() {
        setSingle()
        assertTagsInOrder(orderedTags)
    }

    @Test fun singleServerSectionsFollowTheWebOrderAtTwoHundredPercentFont() {
        setSingle(largeFont = true)
        assertTagsInOrder(orderedTags)
    }

    @Test fun tilesAreTheWebsSixInTheWebsOrder() {
        setSingle()
        val ids = listOf("ActiveStreams", "EgressNow", "ProfilesActive", "Movies", "Shows", "Storage")
        val positions = ids.map { id -> compose.onNodeWithTag("dashboard-tile-$id").fetchSemanticsNode().positionInRoot }
        // Two per row, left to right then top to bottom.
        for ((i, p) in positions.withIndex()) {
            if (i > 0) assertTrue("tile ${ids[i]} must come after ${ids[i - 1]}: $positions",
                p.y > positions[i - 1].y || (p.y == positions[i - 1].y && p.x > positions[i - 1].x))
        }
        compose.onNodeWithText("Active streams").assertIsDisplayed()
        compose.onNodeWithText("Egress now").assertIsDisplayed()
        compose.onNodeWithText("Profiles · 24h").assertIsDisplayed()
        compose.onNodeWithText("Movies").assertIsDisplayed()
        compose.onNodeWithText("Shows").assertIsDisplayed()
        compose.onNodeWithText("Storage").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-tile-EgressNow").assertIsDisplayed().assert(hasText("12 Mbps"))
        // The long totals list is gone.
        listOf("Total Items", "Total Files", "Total Users", "Movie Files", "Show Files", "Sampled health", "Watch Providers").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test fun aTileWithoutDataIsNotShown() {
        // No live sessions yet, no profile activity, no fresh egress sample.
        setSingle(insights = full.copy(activity = SectionState(), egressNow = SectionState()), sessions = null)
        compose.onNodeWithTag("dashboard-tile-Movies").assertIsDisplayed()
        listOf("ActiveStreams", "EgressNow", "ProfilesActive").forEach { id ->
            compose.onNodeWithTag("dashboard-tile-$id").assertDoesNotExist()
        }
    }

    @Test fun aStaleEgressSampleIsNotShownAsCurrent() {
        val old = Instant.now().minusSeconds(600).toString()
        setSingle(insights = full.copy(egressNow = SectionState(Timeseries(points = listOf(TimeseriesPoint(old, 1, 12_000.0))))))
        compose.onNodeWithTag("dashboard-tile-EgressNow").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-tile-ProfilesActive").assertIsDisplayed()
    }

    @Test fun withoutAnyDataOnlyNowPlayingShows() {
        setSingle(insights = DashboardInsights(), stats = null, sessions = null, host = SystemResources(available = false))
        compose.onNodeWithTag(DashboardSection.NowPlaying.tag).assertIsDisplayed()
        compose.onNodeWithTag(DashboardSection.Tiles.tag).assertDoesNotExist()
        compose.onNodeWithText("Loading live sessions…").assertIsDisplayed()
    }

    @Test fun nowPlayingKeepsManageStreamsAndTheIdleLine() {
        var opened = 0
        setSingle(sessions = emptyList(), onStreams = { opened++ })
        compose.onNodeWithText("Nothing playing right now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Manage streams").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun nowPlayingShowsFourStreamsThenALinkToTheRest() {
        val many = (1..6).map { session.copy(session_id = "s$it", media_title = "Title $it") }
        var opened = 0
        setSingle(sessions = many, onStreams = { opened++ })
        (1..4).forEach { compose.onNodeWithText("Title $it").assertExists() }
        compose.onNodeWithText("Title 5").assertDoesNotExist()
        compose.onNodeWithText("+2 more active streams").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun healthIsOneCompactStripWithTheWebsCells() {
        setSingle()
        listOf("Version", "Uptime", "Postgres", "Redis", "Nodes", "Errors · 24h").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("312 · v1.4").assertExists()
        compose.onNodeWithText("3d 4h").assertExists()
        compose.onNodeWithText("Not configured").assertExists()
        compose.onNodeWithText("1/2").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("5 warnings").assertExists()
        compose.onNodeWithText("CPU", substring = true).assertExists()
        compose.onNodeWithText("Memory", substring = true).assertExists()
        val bounds = compose.onNodeWithTag(DashboardSection.Health.tag).fetchSemanticsNode().size
        // Two figures to a row, each with its label above it: a few rows, not a long panel and not a wall of tiles.
        assertTrue("the health section is a few rows, not a long panel: $bounds", bounds.height < 900)
    }

    @Test fun aStaleHostSampleIsLabelledInsideTheHealthSection() {
        setSingle(host = host.copy(stale = true))
        compose.onNodeWithText("Last sample", substring = true).assertExists()
    }

    @Test fun aFailedSectionKeepsItsPlaceAndShowsTheFailure() {
        setSingle(insights = full.copy(top = SectionState(null, ErrorText.Raw("Top activity is down"))))
        assertTagsInOrder(orderedTags)
        // Titles and profiles read one request; the failure shows on the titles panel and the profiles panel says it is unavailable.
        compose.onNodeWithText("Top activity is down").assertExists()
        compose.onNodeWithTag(DashboardSection.TopProfiles.tag).assertExists()
        compose.onNodeWithText("Unavailable").assertExists()
    }

    @Test fun listsShowTheWebsNamesAndRows() {
        setSingle()
        listOf("Now playing", "Playback activity", "Egress", "Top titles", "Most active profiles", "Libraries", "Users", "Recent activity")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Northern Lights").assertExists()
        compose.onNodeWithText("Kids · alex").assertExists()
        compose.onNodeWithText("Films").assertExists()
        compose.onNodeWithText("Disabled").assertExists()
        compose.onNodeWithText("maria").assertExists()
        compose.onNodeWithText("Harbour").assertExists()
        // The trends are charts now; their numbers live in each chart's summary and description.
        listOf("playback", "egress", "streams").forEach { compose.onNodeWithTag("dashboard-chart-$it").assertExists() }
        compose.onNodeWithText("Concurrent streams").assertExists()
        compose.onNodeWithText("Manage users").assertExists()
    }

    @Test fun watchProvidersAreNotPartOfTheDefaultDashboard() {
        setSingle(stats = stats.copy(watch_providers = listOf(WatchProviderStats("trakt", "Trakt", true, true, true, 5, 4, 2, 3, null, 10, 1, 0, 0, 0, 0, 0, 0, 0))))
        compose.onNodeWithText("Trakt", substring = true).assertDoesNotExist()
    }

    // ---- All servers ----

    private val server = AdminServer("a", "https://fixture.invalid", "North studio")

    private fun setAggregate(vararg sections: @Composable () -> Unit, largeFont: Boolean = false) = compose.setContent {
        Frame(largeFont) {
            LazyColumn(Modifier.testTag("list"), contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) { sections.forEach { s -> item { s() } } }
        }
    }

    @Test fun eachServersSectionFollowsTheSameOrder() {
        setAggregate({
            AggregateStatusSection(server, listOf(session), RealtimeState.Live, null, host, null, stats, null, now, false, {}, {}, insights = full)
        })
        compose.onNodeWithText("North studio").assertIsDisplayed()
        assertTagsInOrder(orderedTags)
    }

    @Test fun eachServersSectionFollowsTheSameOrderAtTwoHundredPercentFont() {
        setAggregate({
            AggregateStatusSection(server, listOf(session), RealtimeState.Live, null, host, null, stats, null, now, false, {}, {}, insights = full)
        }, largeFont = true)
        assertTagsInOrder(orderedTags)
    }

    @Test fun aServerThatNeedsSignInShowsNoSectionsOnlyTheSignInAction() {
        var signIns = 0
        setAggregate({
            AggregateStatusSection(server, listOf(session.copy(media_title = "Hidden private title")), RealtimeState.SignInRequired,
                SessionFailure.SignInRequired, host, null, stats, null, null, true, { signIns++ }, {}, insights = full)
        })
        compose.onNodeWithText("Sign in").performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
        orderedTags.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("Hidden private title").assertDoesNotExist()
    }

    @Test fun aServerWithoutDataShowsNowPlayingAndNoTiles() {
        setAggregate({
            AggregateStatusSection(server, null, RealtimeState.Connecting, null, null, null, null, null, null, false, {}, {})
        })
        compose.onNodeWithTag(DashboardSection.NowPlaying.tag).assertIsDisplayed()
        compose.onNodeWithTag(DashboardSection.Tiles.tag).assertDoesNotExist()
    }

    @Test fun twoServersEachHaveTheirOwnOrderedSections() {
        val other = AdminServer("b", "https://fixture-b.invalid", "Garden studio")
        setAggregate(
            { AggregateStatusSection(server, listOf(session), RealtimeState.Live, null, host, null, stats, null, now, false, {}, {}, insights = full) },
            { AggregateStatusSection(other, emptyList(), RealtimeState.Live, null, null, null, stats, null, null, false, {}, {}) },
        )
        compose.onNodeWithText("North studio").assertIsDisplayed()
        compose.onNodeWithTag("list").performScrollToNode(hasText("Garden studio"))
        compose.onNodeWithText("Garden studio").assertIsDisplayed()
    }
}
