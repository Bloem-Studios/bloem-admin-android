package com.bloemstudios.admin.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.VeilPalette
import com.bloemstudios.admin.ui.theme.VeilSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The dashboard's trend charts: what they draw, how they describe themselves, how their window and plain-number
 * view are switched, that they respect reduced motion and the theme, and that each server's section has its own.
 */
@RunWith(AndroidJUnit4::class)
class DashboardTrendChartsTest {
    @get:Rule val compose = createComposeRule()

    private val end: Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)
    private val hour: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS)

    /** Ten one-minute samples; egress climbs 1 to 10 Mbps and streams peak at 7. */
    private fun series(egressStart: Int = 1, scale: Int = 1000, downloads: Boolean = false, gap: Boolean = false): Timeseries {
        val streams = listOf(0, 1, 2, 3, 4, 5, 6, 7, 6, 2)
        val points = (0 until 10).filterNot { gap && it in 4..6 }.map { i ->
            TimeseriesPoint(end.minusSeconds(60L * (9 - i)).toString(), streams[i], (egressStart + i) * scale.toDouble(),
                download_egress_kbps = if (downloads) 500.0 else 0.0)
        }
        return Timeseries(resolution_seconds = 60, from = end.minusSeconds(540).toString(), to = end.toString(),
            oldest_sample_at = end.minusSeconds(540).toString(), egress_bytes = 2_500_000_000.0, points = points)
    }

    private fun playback(hours: Int = 24) = PlaybackActivity(
        hours = hours, bucket_seconds = 3600, to = Instant.now().toString(), profiles_active_24h = 2,
        buckets = listOf(PlaybackBucket(hour.minusSeconds(7200).toString(), 3, 1, 2), PlaybackBucket(hour.toString(), 4, 0, 0)),
        reliability = PlaybackReliability(finalized_sessions = 4, completed_sessions = 3, completion_rate = 0.75),
    )

    private val insights = DashboardInsights(
        activity = SectionState(playback()),
        egress24h = SectionState(series(downloads = true)),
        rangedSeries = mapOf(ChartRange.Week to SectionState(series(egressStart = 20)), ChartRange.Hour to SectionState(series(egressStart = 5))),
        rangedActivity = mapOf(ChartRange.Week to SectionState(playback(168).copy(bucket_seconds = 86_400,
            buckets = listOf(PlaybackBucket(hour.truncatedTo(ChronoUnit.DAYS).toString(), 9, 0, 0))))),
    )

    @Composable private fun Frame(largeFont: Boolean = false, reduced: Boolean? = null, mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, if (largeFont) 2f else density.fontScale),
            LocalReducedMotionOverride provides reduced,
        ) { VeilAdminTheme(mode) { content() } }
    }

    private fun setSingle(
        data: DashboardInsights = insights, largeFont: Boolean = false, reduced: Boolean? = true,
        ranges: ChartRanges? = null, onRange: ((ChartRange) -> Unit)? = null,
        theme: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) = compose.setContent {
        Frame(largeFont, reduced) {
            theme {
                DashboardVeilContent(true, null, null, null, {}, sessions = emptyList(), sourceState = RealtimeState.Live,
                    insights = data, chartRanges = ranges, onChartRange = onRange)
            }
        }
    }

    private fun description(tag: String): String {
        compose.onNodeWithTag(tag).performScrollTo()
        return compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")
    }

    /** The text of a chart's summary, legend and series names sits inside the chart's one described node, so it is read unmerged. */
    private fun inChart(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    private fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    // ---- What is drawn and how it is described ----

    @Test fun eachTrendSectionDrawsItsChartAndNamesItsWindow() {
        setSingle()
        listOf("playback", "egress", "streams").forEach { compose.onNodeWithTag("dashboard-chart-$it").performScrollTo().assertIsDisplayed() }
        listOf("Playback activity", "Egress", "Concurrent streams").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodesWithContentDescription("Show the last 24 hours").assertCountEquals(1)
    }

    @Test fun theEgressDescriptionGivesTheWindowTheLatestAndThePeak() {
        setSingle()
        assertEquals("Egress over the last 24 hours, in megabits per second. Latest 10 Mbps, peak 10 Mbps, lowest 1.0 Mbps.", description("dashboard-chart-egress"))
    }

    @Test fun theStreamsDescriptionCountsWholeStreams() {
        setSingle()
        assertEquals("Concurrent streams over the last 24 hours. Latest 2, peak 7, lowest 0.", description("dashboard-chart-streams"))
    }

    @Test fun thePlaybackDescriptionGivesTheWindowTotalBusiestHourAndMethods() {
        setSingle()
        val text = description("dashboard-chart-playback")
        assertTrue(text, text.startsWith("Playback sessions per hour over the last 24 hours, by play method. 10 sessions in total. "))
        assertTrue(text, "Busiest hour starts at" in text && "with 6 sessions" in text)
        assertTrue(text, text.endsWith("Direct play 7, direct stream 1, transcode 2."))
        compose.onNodeWithText("10 sessions").assertExists()
    }

    @Test fun playbackLegendNamesEveryMethodWithItsCount() {
        setSingle()
        listOf("Direct play 7", "Direct stream 1", "Transcode 2").forEach { inChart(it).performScrollTo().assertIsDisplayed() }
    }

    @Test fun egressShowsItsTotalAndMarksDownloadsAsASecondSeries() {
        setSingle()
        compose.onNodeWithText("Total 2.5 GB").assertExists()
        inChart("Downloads").assertExists()
        inChart("Latest 10 Mbps · Peak 10 Mbps · Lowest 1.0 Mbps").assertExists()
    }

    @Test fun withoutMeasuredDownloadsThereIsNoSecondSeries() {
        setSingle(insights.copy(egress24h = SectionState(series())))
        inChart("Downloads").assertDoesNotExist()
        inChart("Latest 10 Mbps · Peak 10 Mbps · Lowest 1.0 Mbps").assertExists()
    }

    @Test fun gapsInTheSamplesDoNotChangeTheNumbers() {
        setSingle(insights.copy(egress24h = SectionState(series(gap = true))))
        assertEquals("Egress over the last 24 hours, in megabits per second. Latest 10 Mbps, peak 10 Mbps, lowest 1.0 Mbps.", description("dashboard-chart-egress"))
    }

    @Test fun aSectionWithNothingSampledSaysSoInsteadOfDrawingAnEmptyChart() {
        val empty = Timeseries(from = end.minusSeconds(600).toString(), to = end.toString())
        setSingle(insights.copy(egress24h = SectionState(empty), activity = SectionState(playback().copy(buckets = emptyList()))))
        compose.onNodeWithText("No egress samples yet").assertExists()
        compose.onNodeWithText("No stream samples yet").assertExists()
        compose.onNodeWithText("No playback in the last 24 hours").assertExists()
        listOf("playback", "egress", "streams").forEach { compose.onNodeWithTag("dashboard-chart-$it").assertDoesNotExist() }
    }

    // ---- Window switching ----

    @Test fun theOneRangeControlReportsTheChoiceAndMarksTheShownWindow() {
        val picked = mutableListOf<ChartRange>()
        setSingle(onRange = { range -> picked += range }, ranges = ChartRanges().all(ChartRange.Week))
        // The week is what the screen was told to show, for every chart.
        assertTrue(description("dashboard-chart-egress").startsWith("Egress over the last 7 days"))
        assertTrue(description("dashboard-chart-streams").startsWith("Concurrent streams over the last 7 days"))
        compose.onNodeWithTag("dashboard-chart-range-week").assertIsSelected()
        compose.onNodeWithTag("dashboard-chart-range-day").assertIsNotSelected()
        tap("dashboard-chart-range-hour")
        compose.runOnIdle { assertEquals(listOf(ChartRange.Hour), picked) }
    }

    @Test fun thereIsOneRangeControlNotOnePerChart() {
        setSingle()
        compose.onAllNodesWithTag("dashboard-chart-range-day").assertCountEquals(1)
        compose.onAllNodesWithTag("dashboard-chart-range-egress-day").assertCountEquals(0)
    }

    @Test fun withoutAnOutsideOwnerTheSectionKeepsTheChoiceAndEveryChartFollowsIt() {
        setSingle()
        assertTrue(description("dashboard-chart-egress").contains("Latest 10 Mbps"))
        tap("dashboard-chart-range-week")
        compose.waitForIdle()
        assertEquals("Egress over the last 7 days, in megabits per second. Latest 29 Mbps, peak 29 Mbps, lowest 20 Mbps.", description("dashboard-chart-egress"))
        compose.onNodeWithTag("dashboard-chart-range-week").assertIsSelected()
        assertTrue(description("dashboard-chart-streams").startsWith("Concurrent streams over the last 7 days"))
        assertTrue(description("dashboard-chart-playback").startsWith("Playback sessions per day over the last 7 days"))
        compose.onAllNodesWithText("Last 7 days").assertCountEquals(3)
    }

    @Test fun aWeekOfPlaybackIsReadAsDays() {
        setSingle()
        tap("dashboard-chart-range-week")
        compose.waitForIdle()
        val text = description("dashboard-chart-playback")
        assertTrue(text, text.startsWith("Playback sessions per day over the last 7 days, by play method. 9 sessions in total. Busiest day is"))
    }

    @Test fun aWindowThatHasNotArrivedShowsLoadingNotTheOtherWindowsChart() {
        setSingle()
        tap("dashboard-chart-range-month")
        compose.waitForIdle()
        compose.onNodeWithTag("dashboard-chart-egress").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-chart-streams").assertDoesNotExist()
        compose.onAllNodesWithText("Loading…").assertCountEquals(3)
    }

    @Test fun aWindowThatFailedShowsItsFailureAndKeepsTheLastGoodChart() {
        val failed = insights.copy(rangedSeries = mapOf(
            ChartRange.Week to SectionState(series(egressStart = 20), ErrorText.Raw("Week is down")),
            ChartRange.Month to SectionState(null, ErrorText.Raw("Month is down")),
        ))
        setSingle(failed)
        tap("dashboard-chart-range-week")
        compose.waitForIdle()
        // Egress and Concurrent streams read the same endpoint, so both panels carry its failure.
        compose.onAllNodesWithText("Week is down").assertCountEquals(2)
        assertTrue(description("dashboard-chart-egress").contains("Latest 29 Mbps"))
        tap("dashboard-chart-range-month")
        compose.waitForIdle()
        compose.onAllNodesWithText("Month is down").assertCountEquals(2)
        compose.onNodeWithTag("dashboard-chart-egress").assertDoesNotExist()
    }

    @Test fun aFailedRefreshKeepsTheLastGoodChartBesideTheError() {
        setSingle(insights.copy(egress24h = SectionState(series(), ErrorText.Raw("Egress is down"))))
        // Egress and Concurrent streams read the same endpoint, so both panels carry its failure.
        compose.onAllNodesWithText("Egress is down").assertCountEquals(2)
        assertTrue(description("dashboard-chart-egress").contains("Latest 10 Mbps"))
        // The other trend sections are not affected.
        compose.onNodeWithTag("dashboard-chart-playback").assertExists()
    }

    @Test fun everyRangeButtonIsAFullTouchTarget() {
        setSingle()
        ChartRange.entries.forEach { range ->
            compose.onNodeWithTag("dashboard-chart-range-${range.tag}").performScrollTo()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test fun rangeButtonsSayWhatTheyShow() {
        setSingle()
        listOf("Show the last hour", "Show the last 24 hours", "Show the last 7 days", "Show the last 30 days").forEach {
            compose.onAllNodesWithContentDescription(it).assertCountEquals(1)
        }
        listOf("1h", "24h", "7d", "30d").forEach { compose.onAllNodesWithText(it).assertCountEquals(1) }
    }

    // ---- Plain numbers ----

    @Test fun theChartHasAPlainNumberAlternativeThatCanBeSwitchedToAndBack() {
        setSingle()
        compose.onNodeWithTag("dashboard-chart-values-egress").assertDoesNotExist()
        tap("dashboard-chart-toggle-egress")
        compose.waitForIdle()
        compose.onNodeWithTag("dashboard-chart-egress").assertDoesNotExist()
        val values = compose.onNodeWithTag("dashboard-chart-values-egress").performScrollTo()
        values.assertIsDisplayed()
        listOf("Latest", "Peak", "Lowest", "Average", "Total", "Readings").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("dashboard-chart-values-egress"))).assertExists()
        }
        compose.onNode(hasText("Average") and hasAnyAncestor(hasTestTag("dashboard-chart-values-egress"))).assertExists()
        compose.onNode(hasText("5.5 Mbps") and hasAnyAncestor(hasTestTag("dashboard-chart-values-egress"))).assertExists()
        // Other charts keep their chart.
        compose.onNodeWithTag("dashboard-chart-streams").assertExists()
        tap("dashboard-chart-toggle-egress")
        compose.waitForIdle()
        compose.onNodeWithTag("dashboard-chart-egress").assertExists()
        compose.onNodeWithTag("dashboard-chart-values-egress").assertDoesNotExist()
    }

    @Test fun thePlaybackNumbersListTheMethodsTheTotalAndTheBusiestHour() {
        setSingle()
        tap("dashboard-chart-toggle-playback")
        compose.waitForIdle()
        val inValues = { text: String -> compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("dashboard-chart-values-playback"))) }
        listOf("Direct play", "Direct stream", "Transcode", "Total", "Busiest hour", "7", "1", "2", "10").forEach { inValues(it).assertExists() }
    }

    @Test fun theSwitchNamesTheChartItBelongsTo() {
        setSingle()
        compose.onAllNodesWithContentDescription("Show values for Egress").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Show values for Concurrent streams").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Show values for Playback activity").assertCountEquals(1)
        tap("dashboard-chart-toggle-egress")
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Show chart for Egress").assertCountEquals(1)
    }

    // ---- Large text ----

    @Test fun chartsStayUsableAtTwoHundredPercentFont() {
        setSingle(largeFont = true)
        listOf("playback", "egress", "streams").forEach { compose.onNodeWithTag("dashboard-chart-$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("dashboard-chart-toggle-egress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-chart-range-month").performScrollTo().assertIsDisplayed()
        tap("dashboard-chart-toggle-egress")
        compose.waitForIdle()
        compose.onNodeWithTag("dashboard-chart-values-egress").assertIsDisplayed()
    }

    // ---- Pixels: themes and motion ----

    /** Pixels of [image] within a small distance of [target]. */
    private fun androidx.compose.ui.graphics.ImageBitmap.countNear(target: Color, tolerance: Float = 0.06f): Int {
        val map = toPixelMap()
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = map[x, y]
            if (Math.abs(c.red - target.red) < tolerance && Math.abs(c.green - target.green) < tolerance && Math.abs(c.blue - target.blue) < tolerance) n++
        }
        return n
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun chartImage(tag: String) = compose.onNodeWithTag(tag).performScrollTo().captureToImage()

    @Test fun theDarkThemeDrawsTheLineInTheTextColorOnTheCardSurface() {
        val line = VeilPalette.Dark.text
        val surface = VeilPalette.Dark.card
        setSingle()
        compose.waitForIdle()
        val image = chartImage("dashboard-chart-egress")
        assertTrue("the line is drawn: ${image.countNear(line)}", image.countNear(line) > 100)
        assertTrue("the surface is the background", image.countNear(surface) > image.width * image.height / 3)
        assertTrue("line reads against the surface", contrast(line, surface) >= 3.0)
    }

    @Test fun aLightSchemeGetsALegibleChartOnItsOwnSurface() {
        val light = VeilPalette.Light
        compose.setContent {
            Frame(mode = ThemeMode.Light, reduced = true) {
                DashboardVeilContent(true, null, null, null, {}, sessions = emptyList(), sourceState = RealtimeState.Live, insights = insights)
            }
        }
        compose.waitForIdle()
        val image = chartImage("dashboard-chart-egress")
        assertTrue("the chart sits on the light card", image.countNear(light.card) > image.width * image.height / 3)
        assertTrue("the line is drawn in the light text color: ${image.countNear(light.text)}", image.countNear(light.text) > 100)
        assertTrue(contrast(light.text, light.card) >= 3.0)
        assertTrue(contrast(light.muted, light.card) >= 4.5)
        // The main chart is stacked in the web's chart colors; here the streams are all unclassified (grey), so the
        // playback columns carry the direct-play color.
        val columns = chartImage("dashboard-chart-playback")
        assertTrue("stacked columns are drawn", columns.countNear(light.chart[0]) > 20)
    }

    @Test fun theMainChartIsLargerThanTheOthersAndTheyAreSmall() {
        setSingle()
        val main = compose.onNodeWithTag("dashboard-chart-streams").performScrollTo().fetchSemanticsNode().size.height
        val egress = compose.onNodeWithTag("dashboard-chart-egress").performScrollTo().fetchSemanticsNode().size.height
        val playback = compose.onNodeWithTag("dashboard-chart-playback").performScrollTo().fetchSemanticsNode().size.height
        assertTrue("main $main must be well over egress $egress", main > egress * 1.5)
        assertTrue("main $main must be well over playback $playback", main > playback * 1.5)
    }

    /** Both motion modes end on the whole chart: reduced motion skips the reveal, it does not skip the line. */
    private fun drawnLineCount(reduced: Boolean): Int {
        compose.setContent {
            Frame(reduced = reduced) {
                DashboardVeilContent(true, null, null, null, {}, sessions = emptyList(), sourceState = RealtimeState.Live, insights = insights)
            }
        }
        compose.waitForIdle()
        return chartImage("dashboard-chart-egress").countNear(VeilPalette.Dark.text)
    }

    @Test fun underReducedMotionTheWholeChartIsDrawn() { assertTrue(drawnLineCount(reduced = true) > 100) }

    @Test fun underNormalMotionTheWholeChartIsDrawnOnceTheRevealEnds() { assertTrue(drawnLineCount(reduced = false) > 100) }

    // ---- All servers ----

    private val north = AdminServer("a", "https://fixture.invalid", "North studio")
    private val garden = AdminServer("b", "https://fixture-b.invalid", "Garden studio")

    private fun setAggregate(vararg servers: Pair<AdminServer, DashboardInsights>) = compose.setContent {
        Frame(reduced = true) {
            Column(Modifier.testTag("list").verticalScroll(rememberScrollState()).padding(VeilSpacing.CompactHorizontal)) {
                servers.forEach { (server, data) ->
                    AggregateStatusSection(server, emptyList(), RealtimeState.Live, null, null, null, null, null, null, false, {}, {}, insights = data)
                }
            }
        }
    }

    @Test fun eachServersSectionHasItsOwnChartsAndItsOwnWindows() {
        setAggregate(north to insights, garden to insights)
        listOf("playback", "egress", "streams").forEach { compose.onAllNodesWithTag("dashboard-chart-$it").assertCountEquals(2) }
        // Server A's range control switches its charts to a week; B keeps its day.
        compose.onAllNodesWithTag("dashboard-chart-range-week")[0].performScrollTo().performClick()
        compose.waitForIdle()
        val descriptions = compose.onAllNodesWithTag("dashboard-chart-egress").fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ContentDescription].joinToString(" ") }
        assertTrue(descriptions.toString(), descriptions[0].contains("last 7 days") && descriptions[1].contains("last 24 hours"))
    }

    @Test fun aServerThatFailedKeepsItsLastGoodChartWhileTheOtherIsUntouched() {
        setAggregate(north to insights.copy(egress24h = SectionState(series(), ErrorText.Raw("North egress is down"))), garden to insights)
        compose.onAllNodesWithText("North egress is down").assertCountEquals(2)
        compose.onAllNodesWithTag("dashboard-chart-egress").assertCountEquals(2)
    }
}
