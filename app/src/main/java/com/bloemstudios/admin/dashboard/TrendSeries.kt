package com.bloemstudios.admin.dashboard

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The numbers behind the dashboard's trend charts, kept free of Compose and Android resources so the
// series building, scaling and formatting can be unit tested. They follow the web's
// timeseriesSeries.ts, playbackActivitySeries.ts and charts/chartMath.ts.

/** The windows a chart offers, in the order the picker shows them (the web's WIDGET_RANGE_ORDER). */
enum class ChartRange(val hours: Int, val tag: String) {
    Hour(1, "hour"),
    Day(24, "day"),
    Week(168, "week"),
    Month(720, "month");

    companion object {
        /** The window every sampled chart starts on (the web's SAMPLED_RANGES default). */
        val Default: ChartRange = Day
    }
}

/** The three trend charts. */
enum class ChartKind(val tag: String) { Playback("playback"), Egress("egress"), Streams("streams") }

/** The window each chart of one server is showing. */
data class ChartRanges(
    val playback: ChartRange = ChartRange.Default,
    val egress: ChartRange = ChartRange.Default,
    val streams: ChartRange = ChartRange.Default,
) {
    operator fun get(kind: ChartKind): ChartRange = when (kind) {
        ChartKind.Playback -> playback
        ChartKind.Egress -> egress
        ChartKind.Streams -> streams
    }

    /** One window for every chart: the dashboard has a single range control. */
    fun all(range: ChartRange): ChartRanges = ChartRanges(range, range, range)

    fun with(kind: ChartKind, range: ChartRange): ChartRanges = when (kind) {
        ChartKind.Playback -> copy(playback = range)
        ChartKind.Egress -> copy(egress = range)
        ChartKind.Streams -> copy(streams = range)
    }
}

/** One sample on a line chart. A null [value] is a minute the sampler missed: the line breaks there. */
internal data class LinePoint(val t: Long, val value: Double?)

/** Which number of a timeseries sample a line plots. Rates are in Mbps, the unit the web charts. */
internal enum class TimeseriesField(val select: (TimeseriesPoint) -> Double) {
    Streams({ it.streams.toDouble() }),
    EgressMbps({ it.egress_kbps / KBPS_PER_MBPS_TREND }),
    DownloadMbps({ it.download_egress_kbps / KBPS_PER_MBPS_TREND }),
}

private const val KBPS_PER_MBPS_TREND = 1_000.0
private const val DEFAULT_RESOLUTION_SECONDS = 60L
private const val DEFAULT_PLAYBACK_BUCKET_SECONDS = 3600L
private const val HOUR_MS = 3_600_000L

/** The most columns or points a series is expanded to, so a malformed window cannot allocate without bound. */
internal const val MAX_CHART_BUCKETS = 10_000

/** True once any sample in the window carries measured download traffic; only then is the second line drawn. */
internal val Timeseries.hasDownloadTraffic: Boolean get() = points.any { it.download_egress_kbps > 0.0 }

internal fun buildLinePoints(series: Timeseries?, field: TimeseriesField): List<LinePoint> = buildLinePoints(series, field.select)

/**
 * The dense series for the response window. The endpoint omits minutes the sampler never wrote, so the
 * window is re-expanded here and missing buckets become gaps: "the server was down" and "nobody was
 * watching" are different facts, and only the second is a real zero.
 */
internal fun buildLinePoints(series: Timeseries?, select: (TimeseriesPoint) -> Double): List<LinePoint> {
    if (series == null) return emptyList()
    val stepMs = (series.resolution_seconds.takeIf { it > 0 }?.toLong() ?: DEFAULT_RESOLUTION_SECONDS) * 1_000L
    val samples = series.points.mapNotNull { point ->
        val t = parseMillis(point.t) ?: return@mapNotNull null
        LinePoint(t, select(point).takeIf { it.isFinite() })
    }
    val from = parseMillis(series.from)
    val to = parseMillis(series.to)
    // A missing or malformed window: plot whatever samples arrived rather than nothing.
    if (from == null || to == null || to < from) return samples
    val start = Math.floorDiv(from, stepMs) * stepMs
    val end = Math.floorDiv(to, stepMs) * stepMs
    val count = min((end - start) / stepMs + 1, MAX_CHART_BUCKETS.toLong()).toInt()
    val filled = arrayOfNulls<Double>(count)
    samples.forEach { sample ->
        if (sample.t < start) return@forEach
        val index = (sample.t - start) / stepMs
        if (index < count) filled[index.toInt()] = sample.value
    }
    return List(count) { LinePoint(start + it * stepMs, filled[it]) }
}

private fun parseMillis(value: String?): Long? =
    value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Latest, highest and lowest sampled values of a window; gaps do not count. */
internal data class LineSummary(
    val latest: Double, val latestAt: Long, val peak: Double, val peakAt: Long, val min: Double, val average: Double, val sampled: Int,
)

internal fun summarize(points: List<LinePoint>): LineSummary? {
    val sampled = points.filter { it.value != null && it.value.isFinite() }
    if (sampled.isEmpty()) return null
    val latest = sampled.last()
    val peak = sampled.reduce { best, next -> if (next.value!! >= best.value!!) next else best }
    return LineSummary(
        latest = latest.value!!, latestAt = latest.t, peak = peak.value!!, peakAt = peak.t,
        min = sampled.minOf { it.value!! }, average = sampled.sumOf { it.value!! } / sampled.size, sampled = sampled.size,
    )
}

/** One sample of concurrent streams split by how they are delivered; [present] false is a gap the sampler missed. */
internal data class StreamsSplit(val t: Long, val present: Boolean, val direct: Int, val stream: Int, val transcode: Int, val other: Int) {
    val total: Int get() = direct + stream + transcode + other
}

/**
 * The window's concurrent streams as stacked layers: direct play, direct stream (the server's remux), transcode, and
 * whatever the server counted but did not classify ([StreamsSplit.other], also all of a sample on a server that
 * does not split). Missing samples stay gaps, as in [buildLinePoints].
 */
internal fun buildStreamsSplit(series: Timeseries?): List<StreamsSplit> {
    val totals = buildLinePoints(series) { it.streams.toDouble() }
    val direct = buildLinePoints(series) { it.direct.toDouble() }
    val remux = buildLinePoints(series) { it.remux.toDouble() }
    val transcode = buildLinePoints(series) { it.transcode.toDouble() }
    return totals.indices.map { i ->
        val total = totals[i].value
        if (total == null) StreamsSplit(totals[i].t, false, 0, 0, 0, 0)
        else {
            val d = (direct[i].value ?: 0.0).toInt().coerceAtLeast(0)
            val r = (remux[i].value ?: 0.0).toInt().coerceAtLeast(0)
            val t = (transcode[i].value ?: 0.0).toInt().coerceAtLeast(0)
            StreamsSplit(totals[i].t, true, d, r, t, (total.toInt() - d - r - t).coerceAtLeast(0))
        }
    }
}

/** The value axis of the stacked streams chart: whole streams only. */
internal fun splitAxisTop(points: List<StreamsSplit>): Pair<List<Double>, Double> {
    val ticks = niceTicks(0.0, (points.filter { it.present }.maxOfOrNull { it.total } ?: 0).toDouble(), 3, 1.0)
    return ticks to (ticks.last().takeIf { it > 0 } ?: 1.0)
}

/** Playback starts of one bucket, split direct play, direct stream (the server's remux), transcode. */
internal data class PlaybackColumn(val t: Long, val segments: List<Int>) {
    val total: Long get() = segments.sumOf { it.toLong() }
}

/**
 * The columns of the window, oldest first. The endpoint returns only buckets that saw a session, so the quiet
 * ones are filled in: skipping them would compress the window and misplace every other column. Both edges
 * snap to the bucket grid the server truncates on, so the leading bucket is the one that contains
 * `now - hours`, partial or not.
 */
internal fun buildPlaybackColumns(buckets: List<PlaybackBucket>, hours: Int, bucketSeconds: Int, now: Long): List<PlaybackColumn> {
    val stepMs = (bucketSeconds.takeIf { it > 0 }?.toLong() ?: DEFAULT_PLAYBACK_BUCKET_SECONDS) * 1_000L
    val to = Math.floorDiv(now, stepMs) * stepMs
    val from = min(to, Math.floorDiv(now - max(0, hours) * HOUR_MS, stepMs) * stepMs)
    val count = min((to - from) / stepMs + 1, MAX_CHART_BUCKETS.toLong()).toInt()
    val segments = Array(count) { intArrayOf(0, 0, 0) }
    buckets.forEach { bucket ->
        val t = parseMillis(bucket.hour) ?: return@forEach
        val index = (Math.floorDiv(t, stepMs) * stepMs - from) / stepMs
        if (index < 0 || index >= count) return@forEach
        segments[index.toInt()] = intArrayOf(bucket.direct, bucket.remux, bucket.transcode)
    }
    return List(count) { PlaybackColumn(from + it * stepMs, segments[it].toList()) }
}

/** The columns anchored on the server's window end when the response carries it: a phone clock a minute off must not drop the newest bucket. */
internal fun PlaybackActivity.columns(clockNow: Long = System.currentTimeMillis()): List<PlaybackColumn> =
    buildPlaybackColumns(buckets, hours, bucket_seconds, parseMillis(to) ?: clockNow)

/** Buckets wider than a day are read as dates; hourly ones as clock times. */
internal val PlaybackActivity.isDaily: Boolean get() = bucket_seconds >= 86_400

// ---- Axis scale ----

private const val MAX_AXIS_VALUE = 1e300

private fun niceNumber(range: Double, roundToNearest: Boolean): Double {
    val exponent = floor(log10(range))
    val fraction = range / 10.0.pow(exponent)
    val nice = if (roundToNearest) {
        when { fraction < 1.5 -> 1.0; fraction < 3 -> 2.0; fraction < 7 -> 5.0; else -> 10.0 }
    } else {
        when { fraction <= 1 -> 1.0; fraction <= 2 -> 2.0; fraction <= 5 -> 5.0; else -> 10.0 }
    }
    return nice * 10.0.pow(exponent)
}

private fun roundTo(value: Double, decimals: Int): Double = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

/**
 * Axis ticks rounded to human numbers, ascending, evenly spaced and always covering `[min, max]`. [count] is
 * the wanted tick count, not a guarantee. A [minStep] above zero keeps counts from getting fractional gridlines.
 */
internal fun niceTicks(min: Double, max: Double, count: Int = 3, minStep: Double = 0.0): List<Double> {
    var lo = min.takeIf { it.isFinite() } ?: 0.0
    var hi = max.takeIf { it.isFinite() } ?: 0.0
    if (lo > hi) lo = hi.also { hi = lo }
    lo = lo.coerceIn(-MAX_AXIS_VALUE, MAX_AXIS_VALUE)
    hi = hi.coerceIn(-MAX_AXIS_VALUE, MAX_AXIS_VALUE)
    // A flat series still needs a readable axis: grow the top instead of collapsing everything onto one gridline.
    if (lo == hi) hi = lo + if (lo == 0.0) 1.0 else abs(lo)
    val target = max(2, count)
    val rawStep = niceNumber(niceNumber(hi - lo, false) / (target - 1), true)
    val step = if (minStep.isFinite() && minStep > 0) max(rawStep, minStep) else rawStep
    val decimals = (-floor(log10(step)).toInt()).coerceIn(0, 10)
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val ticks = ArrayList<Double>()
    for (i in 0..MAX_TICKS) {
        val value = roundTo(start + i * step, decimals)
        ticks += value
        if (value >= end) break
    }
    return ticks
}

private const val MAX_TICKS = 1000

/** The value axis of a line chart: ticks from zero up past the largest sample, and the top the plot is scaled to. */
internal fun chartAxisTop(points: List<LinePoint>, minStep: Double): Pair<List<Double>, Double> {
    val highest = points.mapNotNull { it.value }.filter { it.isFinite() }.maxOrNull() ?: 0.0
    val ticks = niceTicks(0.0, max(0.0, highest), 3, minStep)
    return ticks to (ticks.last().takeIf { it > 0 } ?: 1.0)
}

/** The value axis of the stacked columns: whole sessions only. */
internal fun columnAxisTop(columns: List<PlaybackColumn>): Pair<List<Double>, Double> {
    val ticks = niceTicks(0.0, (columns.maxOfOrNull { it.total } ?: 0L).toDouble(), 3, 1.0)
    return ticks to (ticks.last().takeIf { it > 0 } ?: 1.0)
}

/** The stretches of consecutive sampled points, each point with its index in [points]. A gap ends a run; an isolated sample is a run of one. */
internal fun plottedRuns(points: List<LinePoint>): List<List<IndexedValue<LinePoint>>> {
    val runs = mutableListOf<List<IndexedValue<LinePoint>>>()
    var current = mutableListOf<IndexedValue<LinePoint>>()
    points.withIndex().forEach { point ->
        if (point.value.value != null && point.value.value!!.isFinite()) current += point
        else if (current.isNotEmpty()) { runs += current; current = mutableListOf() }
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

/** Up to [count] points spread evenly over the window, first and last included: the plain-number view of a long series. */
internal fun pickReadings(points: List<LinePoint>, count: Int): List<LinePoint> {
    if (points.size <= count || count < 2) return if (count < 2) points.takeLast(max(count, 0)) else points
    return (0 until count).map { points[it * (points.size - 1) / (count - 1)] }.distinct()
}

/** Where each point sits along the time axis, 0 to 1. One point sits in the middle. */
internal fun xRatios(points: List<LinePoint>): List<Float> {
    if (points.size <= 1) return points.map { 0.5f }
    val first = points.first().t
    val span = points.last().t - first
    return points.mapIndexed { index, point ->
        if (span <= 0) index.toFloat() / (points.size - 1) else ((point.t - first).toDouble() / span).toFloat()
    }
}

// ---- Formatting ----

/** An egress rate without its unit; small rates keep one decimal so a trickle does not round to a flat "0". */
fun formatMbpsValue(mbps: Double, locale: Locale = Locale.getDefault()): String {
    if (!mbps.isFinite()) return "—"
    val decimals = if (mbps > 0 && mbps < 10) 1 else 0
    return NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals }.format(mbps)
}

/** A whole count, grouped for the locale. */
internal fun formatChartCount(value: Double, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 0 }.format(value)

private val DATA_TOTAL_UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

/**
 * Data transferred over a window, in decimal units to match the Mbps beside it, with one decimal below 100.
 * The unit and precision are chosen on the value as displayed, so it never reads "1,000 KB" (it reads "1.0 MB").
 */
internal fun formatDataTotal(bytes: Double, locale: Locale = Locale.getDefault()): String {
    if (!bytes.isFinite() || bytes < 0) return "—"
    var value = bytes
    var unit = 0
    while (true) {
        val decimals = if (unit > 0 && value < 100) 1 else 0
        val rounded = roundTo(value, decimals)
        if (rounded >= 1000 && unit < DATA_TOTAL_UNITS.lastIndex) { value /= 1000; unit += 1; continue }
        val shown = if (unit > 0 && rounded < 100) 1 else 0
        val format = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = shown; maximumFractionDigits = shown }
        return "${format.format(rounded)} ${DATA_TOTAL_UNITS[unit]}"
    }
}

/**
 * A moment on a chart. Hours and days read as clock times; from a week up the day is what tells moments apart,
 * so it is shown, with the time too where there is room ([withTime]).
 */
internal fun formatChartTimestamp(range: ChartRange, epochMillis: Long, zone: ZoneId, locale: Locale, withTime: Boolean): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(moment)
    if (range == ChartRange.Hour || range == ChartRange.Day) return time
    val day = DateTimeFormatter.ofPattern("MMM d", locale).format(moment)
    return if (withTime) "$day, $time" else day
}

/** A column of a playback chart: a clock time for hourly buckets, a date for daily ones. */
internal fun formatColumnTimestamp(daily: Boolean, epochMillis: Long, zone: ZoneId, locale: Locale): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return if (daily) DateTimeFormatter.ofPattern("MMM d", locale).format(moment)
    else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(moment)
}
