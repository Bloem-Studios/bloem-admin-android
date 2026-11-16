package com.bloemstudios.admin.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class TrendSeriesTest {
    private val minute = 60_000L
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    // ---- Ranges ----

    @Test fun `ranges are the web's four windows in its hours`() {
        assertEquals(listOf(1, 24, 168, 720), ChartRange.entries.map { it.hours })
        assertEquals(ChartRange.Day, ChartRange.Default)
    }

    @Test fun `one range choice moves all three charts`() {
        val ranges = ChartRanges().all(ChartRange.Week)
        assertEquals(ChartRange.Week, ranges.playback)
        assertEquals(ChartRange.Week, ranges.egress)
        assertEquals(ChartRange.Week, ranges.streams)
    }

    // ---- Concurrent streams split by play method ----

    @Test fun `streams split into direct play, direct stream and transcode, with the unclassified rest and gaps kept`() {
        val series = Timeseries(
            resolution_seconds = 60, from = "2026-11-13T10:00:00Z", to = "2026-11-13T10:03:00Z",
            points = listOf(
                TimeseriesPoint("2026-11-13T10:00:00Z", streams = 5, direct = 2, remux = 1, transcode = 1),
                // A server that does not split the count: everything is unclassified.
                TimeseriesPoint("2026-11-13T10:02:00Z", streams = 3),
            ),
        )
        val split = buildStreamsSplit(series)
        assertEquals(4, split.size)
        assertEquals(listOf(true, false, true, false), split.map { it.present })
        assertEquals(listOf(2, 1, 1, 1), listOf(split[0].direct, split[0].stream, split[0].transcode, split[0].other))
        assertEquals(5, split[0].total)
        assertEquals(listOf(0, 0, 0, 3), listOf(split[2].direct, split[2].stream, split[2].transcode, split[2].other))
        assertEquals(listOf(listOf(0), listOf(2)), splitRuns(split).map { run -> run.map { it.index } })
    }

    @Test fun `the streams axis covers the tallest stack in whole streams`() {
        val split = listOf(StreamsSplit(0, true, 3, 2, 1, 0), StreamsSplit(60_000, true, 1, 0, 0, 0))
        val (ticks, top) = splitAxisTop(split)
        assertEquals(0.0, ticks.first(), 0.0)
        assertTrue(top >= 6.0)
        assertTrue(ticks.all { it == Math.floor(it) })
    }

    // ---- Timeseries to line points ----

    @Test fun `missing minutes become gaps and sampled zeros stay zeros`() {
        val series = Timeseries(
            resolution_seconds = 60, from = "2026-11-13T10:00:00Z", to = "2026-11-13T10:04:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 2, 0.0), TimeseriesPoint("2026-11-13T10:03:00Z", 0, 4_000.0)),
        )
        val points = buildLinePoints(series) { it.streams.toDouble() }
        assertEquals(5, points.size)
        assertEquals(listOf(2.0, null, null, 0.0, null), points.map { it.value })
        assertEquals(at("2026-11-13T10:00:00Z"), points.first().t)
        assertEquals(at("2026-11-13T10:04:00Z"), points.last().t)
    }

    @Test fun `no response is no points`() {
        assertTrue(buildLinePoints(null) { it.streams.toDouble() }.isEmpty())
    }

    @Test fun `a malformed window plots the samples that arrived`() {
        val series = Timeseries(from = "nonsense", to = "", points = listOf(
            TimeseriesPoint("2026-11-13T10:00:00Z", 1, 1.0), TimeseriesPoint("not a time", 9, 9.0), TimeseriesPoint("2026-11-13T10:01:00Z", 3, 1.0)))
        val points = buildLinePoints(series) { it.streams.toDouble() }
        assertEquals(listOf(1.0, 3.0), points.map { it.value })
    }

    @Test fun `a reversed window plots the samples that arrived`() {
        val series = Timeseries(from = "2026-11-13T11:00:00Z", to = "2026-11-13T10:00:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T10:30:00Z", 4, 0.0)))
        assertEquals(listOf(4.0), buildLinePoints(series) { it.streams.toDouble() }.map { it.value })
    }

    @Test fun `a response without a window plots the samples that arrived`() {
        val series = Timeseries(points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 1, 5.0)))
        assertEquals(1, buildLinePoints(series) { it.egress_kbps }.size)
    }

    @Test fun `a non finite value is a gap not a number`() {
        val series = Timeseries(resolution_seconds = 60, from = "2026-11-13T10:00:00Z", to = "2026-11-13T10:01:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 1, Double.NaN), TimeseriesPoint("2026-11-13T10:01:00Z", 1, Double.POSITIVE_INFINITY)))
        assertEquals(listOf(null, null), buildLinePoints(series) { it.egress_kbps }.map { it.value })
    }

    @Test fun `the grid follows the server's bucket width not a minute`() {
        val series = Timeseries(resolution_seconds = 1800, from = "2026-11-06T10:00:00Z", to = "2026-11-13T10:00:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 5, 1.0)))
        val points = buildLinePoints(series) { it.streams.toDouble() }
        assertEquals(7 * 48 + 1, points.size)
        assertEquals(5.0, points.last().value)
        assertEquals(1800_000L, points[1].t - points[0].t)
    }

    @Test fun `a zero or negative resolution falls back to a minute`() {
        val series = Timeseries(resolution_seconds = 0, from = "2026-11-13T10:00:00Z", to = "2026-11-13T10:02:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T10:01:00Z", 7, 0.0)))
        val points = buildLinePoints(series) { it.streams.toDouble() }
        assertEquals(listOf(null, 7.0, null), points.map { it.value })
    }

    @Test fun `a sample outside the window is dropped`() {
        val series = Timeseries(resolution_seconds = 60, from = "2026-11-13T10:00:00Z", to = "2026-11-13T10:01:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T09:00:00Z", 8, 0.0), TimeseriesPoint("2026-11-13T12:00:00Z", 8, 0.0)))
        assertEquals(listOf(null, null), buildLinePoints(series) { it.streams.toDouble() }.map { it.value })
    }

    @Test fun `a huge window is capped instead of allocating without bound`() {
        val series = Timeseries(resolution_seconds = 1, from = "2000-01-01T00:00:00Z", to = "2026-01-01T00:00:00Z")
        assertTrue(buildLinePoints(series) { it.streams.toDouble() }.size <= MAX_CHART_BUCKETS)
    }

    @Test fun `egress is plotted in Mbps and downloads are an optional second series`() {
        val series = Timeseries(resolution_seconds = 60, from = "2026-11-13T10:00:00Z", to = "2026-11-13T10:00:00Z",
            points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 1, 12_500.0, download_egress_kbps = 2_000.0)))
        assertEquals(12.5, buildLinePoints(series, TimeseriesField.EgressMbps).single().value)
        assertEquals(2.0, buildLinePoints(series, TimeseriesField.DownloadMbps).single().value)
        assertTrue(series.hasDownloadTraffic)
        assertFalse(Timeseries(points = listOf(TimeseriesPoint("2026-11-13T10:00:00Z", 1, 1.0))).hasDownloadTraffic)
    }

    // ---- Summary: latest, peak, min ----

    @Test fun `summary reports latest peak and minimum over the sampled points only`() {
        val points = listOf(LinePoint(0, 3.0), LinePoint(1, null), LinePoint(2, 9.0), LinePoint(3, 0.5), LinePoint(4, null))
        val summary = summarize(points)!!
        assertEquals(0.5, summary.latest, 0.0)
        assertEquals(9.0, summary.peak, 0.0)
        assertEquals(0.5, summary.min, 0.0)
        assertEquals(3, summary.sampled)
    }

    @Test fun `summary of nothing sampled is null`() {
        assertNull(summarize(emptyList()))
        assertNull(summarize(listOf(LinePoint(0, null), LinePoint(1, null))))
    }

    @Test fun `summary of a single point is that point three ways`() {
        val summary = summarize(listOf(LinePoint(0, 4.0)))!!
        assertEquals(listOf(4.0, 4.0, 4.0), listOf(summary.latest, summary.peak, summary.min))
    }

    // ---- Playback activity columns ----

    @Test fun `quiet hours are zero filled so columns keep their place`() {
        val now = at("2026-11-13T12:30:00Z")
        val columns = buildPlaybackColumns(
            listOf(PlaybackBucket("2026-11-13T11:00:00Z", 2, 1, 0), PlaybackBucket("2026-11-13T12:00:00Z", 0, 0, 4)),
            hours = 3, bucketSeconds = 3600, now = now,
        )
        // 09:30 snaps to 09:00; the window covers 09:00 to 12:00 inclusive.
        assertEquals(listOf(9, 10, 11, 12), columns.map { Instant.ofEpochMilli(it.t).atZone(java.time.ZoneOffset.UTC).hour })
        assertEquals(listOf(listOf(0, 0, 0), listOf(0, 0, 0), listOf(2, 1, 0), listOf(0, 0, 4)), columns.map { it.segments })
    }

    @Test fun `an hourly one hour window covers the two buckets the server can return`() {
        val columns = buildPlaybackColumns(emptyList(), hours = 1, bucketSeconds = 3600, now = at("2026-11-13T12:30:00Z"))
        assertEquals(2, columns.size)
    }

    @Test fun `daily buckets zero fill on the day grid`() {
        val columns = buildPlaybackColumns(
            listOf(PlaybackBucket("2026-11-10T00:00:00Z", 1, 0, 0)), hours = 24 * 7, bucketSeconds = 86_400, now = at("2026-11-13T12:00:00Z"))
        assertEquals(8, columns.size)
        assertEquals(1L, columns.single { it.total > 0 }.total)
        assertTrue(columns.all { it.t % 86_400_000L == 0L })
    }

    @Test fun `a bucket off the grid snaps into the column that contains it`() {
        val columns = buildPlaybackColumns(
            listOf(PlaybackBucket("2026-11-13T12:20:00Z", 5, 0, 0)), hours = 2, bucketSeconds = 3600, now = at("2026-11-13T12:30:00Z"))
        assertEquals(5L, columns.last().total)
    }

    @Test fun `a malformed or out of window bucket is ignored`() {
        val columns = buildPlaybackColumns(
            listOf(PlaybackBucket("garbage", 1, 1, 1), PlaybackBucket("2020-01-01T00:00:00Z", 9, 9, 9)),
            hours = 2, bucketSeconds = 3600, now = at("2026-11-13T12:30:00Z"))
        assertTrue(columns.all { it.total == 0L })
    }

    @Test fun `a zero bucket width falls back to hourly`() {
        val columns = buildPlaybackColumns(emptyList(), hours = 2, bucketSeconds = 0, now = at("2026-11-13T12:30:00Z"))
        assertEquals(3, columns.size)
        assertEquals(3_600_000L, columns[1].t - columns[0].t)
    }

    @Test fun `the server's window end anchors the grid`() {
        val playback = PlaybackActivity(hours = 2, bucket_seconds = 3600, to = "2026-11-13T12:59:59Z", profiles_active_24h = 0,
            buckets = listOf(PlaybackBucket("2026-11-13T12:00:00Z", 1, 0, 0)))
        // A phone clock a day behind must not misplace the newest column.
        val columns = playback.columns(clockNow = at("2026-11-12T12:00:00Z"))
        assertEquals(1L, columns.last().total)
        assertEquals(at("2026-11-13T12:00:00Z"), columns.last().t)
    }

    @Test fun `column totals sum a single large column without overflow`() {
        val column = PlaybackColumn(0, listOf(Int.MAX_VALUE, Int.MAX_VALUE, 1))
        assertEquals(Int.MAX_VALUE.toLong() * 2 + 1, column.total)
    }

    // ---- Axis scale ----

    @Test fun `ticks are round numbers covering the data from zero`() {
        assertEquals(listOf(0.0, 5.0, 10.0), niceTicks(0.0, 9.0, 3, 0.0))
        assertEquals(listOf(0.0, 50.0, 100.0), niceTicks(0.0, 87.0, 3, 0.0))
    }

    @Test fun `a flat zero series still gets a readable axis`() {
        val ticks = niceTicks(0.0, 0.0, 3, 0.0)
        assertEquals(0.0, ticks.first(), 0.0)
        assertTrue(ticks.last() > 0.0)
    }

    @Test fun `counts never get fractional gridlines when the step is pinned to one`() {
        val ticks = niceTicks(0.0, 1.0, 3, 1.0)
        assertEquals(listOf(0.0, 1.0), ticks)
        assertTrue(niceTicks(0.0, 2.0, 4, 1.0).all { it == Math.floor(it) })
    }

    @Test fun `ticks are ascending and cover the maximum for a spread of magnitudes`() {
        listOf(0.001, 0.37, 1.0, 7.0, 12.5, 999.0, 1_000.0, 123_456.0, 9.9e9, 4.2e15).forEach { max ->
            val ticks = niceTicks(0.0, max, 3, 0.0)
            assertTrue("ticks for $max: $ticks", ticks.size >= 2 && ticks.zipWithNext().all { (a, b) -> a < b } && ticks.last() >= max)
            assertTrue(ticks.size <= 12)
        }
    }

    @Test fun `non finite or inverted bounds degrade to a valid axis`() {
        assertTrue(niceTicks(Double.NaN, Double.POSITIVE_INFINITY, 3, 0.0).size >= 2)
        val inverted = niceTicks(10.0, 0.0, 3, 0.0)
        assertTrue(inverted.first() <= 0.0 && inverted.last() >= 10.0)
    }

    @Test fun `negative values are not scaled below the axis floor`() {
        assertEquals(0.0, chartAxisTop(listOf(LinePoint(0, -5.0), LinePoint(1, null)), 1.0).first.first(), 0.0)
    }

    @Test fun `axis top covers the largest value and ignores gaps`() {
        val (_, top) = chartAxisTop(listOf(LinePoint(0, 3.0), LinePoint(1, null), LinePoint(2, 8.4)), 0.0)
        assertTrue(top >= 8.4)
        assertTrue(top <= 20.0)
    }

    @Test fun `a huge value still yields a finite axis`() {
        val (ticks, top) = chartAxisTop(listOf(LinePoint(0, 1.7e308)), 0.0)
        assertTrue(top.isFinite() && ticks.all { it.isFinite() })
    }

    @Test fun `column axis counts sessions`() {
        val columns = listOf(PlaybackColumn(0, listOf(1, 1, 1)), PlaybackColumn(1, listOf(0, 0, 0)), PlaybackColumn(2, listOf(4, 0, 2)))
        val (ticks, top) = columnAxisTop(columns)
        assertTrue(top >= 6.0)
        assertTrue(ticks.all { it == Math.floor(it) })
    }

    // ---- Paths ----

    @Test fun `a gap breaks the line into separate runs and a lone sample is its own run`() {
        val runs = plottedRuns(listOf(LinePoint(0, 1.0), LinePoint(1, 2.0), LinePoint(2, null), LinePoint(3, 5.0), LinePoint(4, null)))
        assertEquals(listOf(2, 1), runs.map { it.size })
    }

    @Test fun `readings are spread evenly and keep both ends`() {
        val points = (0..99).map { LinePoint(it.toLong(), it.toDouble()) }
        val picked = pickReadings(points, 6)
        assertEquals(6, picked.size)
        assertEquals(0L, picked.first().t)
        assertEquals(99L, picked.last().t)
        assertEquals(points, pickReadings(points, 200))
        assertEquals(listOf(points.last()), pickReadings(points, 1))
        assertTrue(pickReadings(points, 0).isEmpty())
        assertTrue(pickReadings(emptyList(), 6).isEmpty())
    }

    @Test fun `x positions follow time and a single point sits in the middle`() {
        assertEquals(listOf(0.5f), xRatios(listOf(LinePoint(100, 1.0))))
        assertEquals(listOf(0f, 0.25f, 1f), xRatios(listOf(LinePoint(0, 1.0), LinePoint(25, 1.0), LinePoint(100, 1.0))))
        // Identical timestamps fall back to even spacing instead of dividing by zero.
        assertEquals(listOf(0f, 1f), xRatios(listOf(LinePoint(5, 1.0), LinePoint(5, 1.0))))
    }

    // ---- Formatting ----

    @Test fun `mbps axis labels keep one decimal for a trickle`() {
        assertEquals("0.5", formatMbpsValue(0.5, Locale.US))
        assertEquals("12", formatMbpsValue(12.4, Locale.US))
        assertEquals("0", formatMbpsValue(0.0, Locale.US))
        assertEquals("—", formatMbpsValue(Double.NaN, Locale.US))
        assertEquals("1,200", formatMbpsValue(1200.0, Locale.US))
    }

    @Test fun `data totals use decimal units and never read 1000 KB`() {
        assertEquals("0 B", formatDataTotal(0.0, Locale.US))
        assertEquals("999 B", formatDataTotal(999.0, Locale.US))
        assertEquals("1.0 KB", formatDataTotal(1_000.0, Locale.US))
        assertEquals("1.0 MB", formatDataTotal(999_999.0, Locale.US))
        assertEquals("100 GB", formatDataTotal(99_999_000_000.0, Locale.US))
        assertEquals("12.3 TB", formatDataTotal(12.34e12, Locale.US))
        assertEquals("—", formatDataTotal(-1.0, Locale.US))
        assertEquals("—", formatDataTotal(Double.NaN, Locale.US))
        assertNotNull(formatDataTotal(1e30, Locale.US))
    }

    @Test fun `chart timestamps read as clock times for short windows and dates for long ones`() {
        val utc = java.time.ZoneOffset.UTC
        val t = Instant.parse("2026-11-13T14:05:00Z").toEpochMilli()
        assertEquals("2:05 PM", formatChartTimestamp(ChartRange.Day, t, utc, Locale.US, withTime = true).replace('\u202f', ' '))
        assertEquals("2:05 PM", formatChartTimestamp(ChartRange.Hour, t, utc, Locale.US, withTime = false).replace('\u202f', ' '))
        assertEquals("Nov 13", formatChartTimestamp(ChartRange.Week, t, utc, Locale.US, withTime = false))
        assertEquals("Nov 13, 2:05 PM", formatChartTimestamp(ChartRange.Month, t, utc, Locale.US, withTime = true).replace('\u202f', ' '))
        assertEquals("Nov 13", formatColumnTimestamp(true, t, utc, Locale.US))
    }

    @Test fun `counts are grouped for the locale`() {
        assertEquals("1,234", formatChartCount(1234.0, Locale.US))
        assertEquals("0", formatChartCount(0.0, Locale.US))
    }
}
