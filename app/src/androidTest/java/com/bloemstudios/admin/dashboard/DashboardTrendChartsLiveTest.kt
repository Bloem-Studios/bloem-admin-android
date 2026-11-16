package com.bloemstudios.admin.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real dashboard screen and view model against a mock engine that answers each chart endpoint for the window asked. */
@RunWith(AndroidJUnit4::class)
class DashboardTrendChartsLiveTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    /** Lifecycle registries only accept a state on the main thread. */
    private fun owner(state: Lifecycle.State): Owner {
        lateinit var owner: Owner
        compose.runOnUiThread { owner = Owner().also { it.registry.currentState = state } }
        return owner
    }

    private val ts = "/api/v2/admin/stats/timeseries"
    private val pb = "/api/v2/admin/stats/playback-activity"

    private fun show(fixture: TrendChartsFixture, owner: Owner) = compose.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalReducedMotionOverride provides true) {
            VeilAdminTheme { fixture.Render() }
        }
    }

    private fun description(tag: String): String {
        compose.onNodeWithTag(tag).performScrollTo()
        return compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")
    }

    private fun waitForChart(tag: String, containing: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTagExists(tag) && runCatching { description(tag).contains(containing) }.getOrDefault(false)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    @Test fun thePollReadsTheDayAndTheHourAndNothingWider() {
        val fixture = TrendChartsFixture()
        try {
            show(fixture, owner(Lifecycle.State.STARTED))
            waitForChart("dashboard-chart-egress", "the last 24 hours")
            assertTrue(fixture.count("$ts hours=24") >= 1 && fixture.count("$ts hours=1") >= 1 && fixture.count("$pb hours=24") >= 1)
            assertEquals(0, fixture.count("$ts hours=168") + fixture.count("$ts hours=720") + fixture.count("$pb hours=168"))
            assertEquals(ChartRanges(), fixture.model.ranges.value)
        } finally { fixture.close() }
    }

    @Test fun choosingAWeekMovesEveryChartAndReadsEachWindowOnce() {
        val fixture = TrendChartsFixture()
        try {
            show(fixture, owner(Lifecycle.State.STARTED))
            waitForChart("dashboard-chart-egress", "the last 24 hours")
            tap("dashboard-chart-range-week")
            waitForChart("dashboard-chart-egress", "the last 7 days")
            assertEquals(ChartRanges().all(ChartRange.Week), fixture.model.ranges.value)
            assertTrue(description("dashboard-chart-egress").contains("Latest 12 Mbps, peak 40 Mbps, lowest 2.0 Mbps"))
            // Concurrent streams reads the same endpoint, so the one request serves both charts.
            waitForChart("dashboard-chart-streams", "the last 7 days")
            assertEquals(1, fixture.count("$ts hours=168"))
            // Playback activity reads its own endpoint, and a week of it is read as days.
            waitForChart("dashboard-chart-playback", "per day over the last 7 days")
            assertEquals(1, fixture.count("$pb hours=168"))
        } finally { fixture.close() }
    }

    @Test fun anHourIsAnswerFromTheNowReadWithoutAnotherRequest() {
        val fixture = TrendChartsFixture()
        try {
            show(fixture, owner(Lifecycle.State.STARTED))
            waitForChart("dashboard-chart-streams", "the last 24 hours")
            val before = fixture.count("$ts hours=1")
            tap("dashboard-chart-range-hour")
            waitForChart("dashboard-chart-streams", "the last hour")
            assertEquals(before, fixture.count("$ts hours=1"))
        } finally { fixture.close() }
    }

    @Test fun aWindowThatFailsShowsNoChartThenRecoversWhenChosenAgain() {
        val fixture = TrendChartsFixture()
        try {
            fixture.failing.set(setOf("$ts hours=720"))
            show(fixture, owner(Lifecycle.State.STARTED))
            waitForChart("dashboard-chart-egress", "the last 24 hours")
            tap("dashboard-chart-range-month")
            compose.waitUntil(10_000) { fixture.count("$ts hours=720") == 1 }
            compose.waitForIdle()
            compose.onNodeWithTag("dashboard-section-egress").performScrollTo()
            assertTrue("a failed window draws no chart", compose.onAllNodesWithTagExists("dashboard-chart-egress").not())
            compose.onNode(hasText("Loading…") and hasAnyAncestor(hasTestTag("dashboard-section-egress"))).assertDoesNotExist()
            fixture.failing.set(emptySet())
            tap("dashboard-chart-range-day")
            waitForChart("dashboard-chart-egress", "the last 24 hours")
            tap("dashboard-chart-range-month")
            waitForChart("dashboard-chart-egress", "the last 30 days")
            assertEquals(2, fixture.count("$ts hours=720"))
        } finally { fixture.close() }
    }

    @Test fun nothingIsReadWhileTheScreenIsNotInTheForeground() {
        val fixture = TrendChartsFixture()
        val owner = owner(Lifecycle.State.CREATED)
        try {
            show(fixture, owner)
            compose.waitForIdle()
            Thread.sleep(1_000)
            compose.waitForIdle()
            assertTrue("no read while stopped: ${fixture.requests}", fixture.requests.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            waitForChart("dashboard-chart-egress", "the last 24 hours")
            assertTrue(fixture.requests.isNotEmpty())
        } finally {
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
            fixture.close()
        }
    }
}
