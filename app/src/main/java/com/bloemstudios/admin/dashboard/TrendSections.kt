package com.bloemstudios.admin.dashboard

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import java.time.ZoneId
import java.util.Locale

// The three trend sections: Concurrent streams (the one main chart, split by play method), then the smaller Playback
// activity and Egress. One range control, in the main panel, sets the window of all three; each draws its chart natively
// and keeps its last good chart beside a failed refresh.

private const val READINGS = 6

@Composable
private fun rememberLocale(): Locale {
    LocalConfiguration.current
    return Locale.getDefault()
}

@Composable
private fun LoadingLine() = EmptyLine(stringResource(R.string.dashboard_chart_loading))

// ---- Playback activity ----

@Composable
internal fun PlaybackActivitySection(insights: DashboardInsights, range: ChartRange) {
    val state = insights.activityFor(range)
    SectionPanel(DashboardSection.PlaybackActivity, stringResource(R.string.dashboard_playback_activity_title),
        caption = stringResource(range.captionRes()), small = true) {
        val activity = state.value
        if (activity != null) PlaybackBody(activity, range) else if (state.error == null) LoadingLine()
        SectionError(state)
    }
}

@Composable
private fun PlaybackBody(activity: PlaybackActivity, range: ChartRange) {
    val locale = rememberLocale()
    val zone = ZoneId.systemDefault()
    val columns = remember(activity) { activity.columns() }
    val total = columns.sumOf { it.total }
    if (total == 0L) {
        EmptyLine(stringResource(R.string.dashboard_playback_chart_empty, stringResource(range.phraseRes())))
        Text(stringResource(R.string.dashboard_playback_chart_empty_detail), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        return
    }
    val daily = activity.isDaily
    val direct = columns.sumOf { it.segments[0].toLong() }
    val stream = columns.sumOf { it.segments[1].toLong() }
    val transcode = columns.sumOf { it.segments[2].toLong() }
    val busiest = columns.reduce { best, next -> if (next.total >= best.total) next else best }
    val sessions = plural(R.plurals.dashboard_playback_sessions, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), formatChartCount(total.toDouble(), locale))
    val busiestSessions = plural(R.plurals.dashboard_playback_sessions, busiest.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        formatChartCount(busiest.total.toDouble(), locale))
    val busiestAt = formatColumnTimestamp(daily, busiest.t, zone, locale)
    val busiestText = stringResource(if (daily) R.string.dashboard_playback_busiest_daily else R.string.dashboard_playback_busiest_hourly, busiestAt, busiestSessions)
    val methods = stringResource(R.string.dashboard_playback_methods_description,
        formatChartCount(direct.toDouble(), locale), formatChartCount(stream.toDouble(), locale), formatChartCount(transcode.toDouble(), locale))
    val label = stringResource(if (daily) R.string.dashboard_playback_chart_daily else R.string.dashboard_playback_chart_hourly, stringResource(range.phraseRes()))
    val description = stringResource(R.string.dashboard_playback_chart_description, label, sessions, busiestText, methods)
    val title = stringResource(R.string.dashboard_playback_activity_title)
    val colors = chartColors()

    Text(sessions, style = MaterialTheme.typography.titleMedium.tabular())
    if (activity.reliability.finalized_sessions > 0) Text(
        stringResource(R.string.dashboard_playback_completion, Math.round(activity.reliability.completion_rate * 100).toInt()),
        style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    val startEdge = stringResource(range.edgeRes())
    val endEdge = stringResource(R.string.dashboard_chart_edge_now)
    TrendChartFrame(ChartKind.Playback, title, description, summaryLine = null, range = range,
        chart = { reveal ->
            ColumnsTrendChart(columns, { formatChartCount(it, locale) }, startEdge, endEdge, reveal)
            ChartLegend(listOf(
                colors.directPlay to stringResource(R.string.dashboard_playback_legend_item, stringResource(R.string.dashboard_playback_series_direct_play), formatChartCount(direct.toDouble(), locale)),
                colors.directStream to stringResource(R.string.dashboard_playback_legend_item, stringResource(R.string.dashboard_playback_series_direct_stream), formatChartCount(stream.toDouble(), locale)),
                colors.transcode to stringResource(R.string.dashboard_playback_legend_item, stringResource(R.string.dashboard_playback_series_transcode), formatChartCount(transcode.toDouble(), locale)),
            ))
        },
        values = {
            ValueRow(stringResource(R.string.dashboard_playback_series_direct_play), formatChartCount(direct.toDouble(), locale))
            ValueRow(stringResource(R.string.dashboard_playback_series_direct_stream), formatChartCount(stream.toDouble(), locale))
            ValueRow(stringResource(R.string.dashboard_playback_series_transcode), formatChartCount(transcode.toDouble(), locale))
            ValueRow(stringResource(R.string.dashboard_chart_total), formatChartCount(total.toDouble(), locale))
            ValueRow(stringResource(if (daily) R.string.dashboard_playback_busiest_label_daily else R.string.dashboard_playback_busiest_label_hourly),
                "$busiestAt · $busiestSessions")
        })
}

// ---- Egress and concurrent streams ----

@Composable
internal fun EgressSection(insights: DashboardInsights, range: ChartRange) {
    val state = insights.seriesFor(range)
    SectionPanel(DashboardSection.Egress, stringResource(R.string.dashboard_egress_title), caption = stringResource(range.captionRes()), small = true) {
        val series = state.value
        if (series != null) SampledBody(
            kind = ChartKind.Egress, range = range, series = series, field = TimeseriesField.EgressMbps,
            title = stringResource(R.string.dashboard_egress_title), chartLabel = R.string.dashboard_egress_chart_description,
            emptyText = R.string.dashboard_egress_chart_empty,
        ) else if (state.error == null) LoadingLine()
        SectionError(state)
    }
}

@Composable
internal fun ConcurrentStreamsSection(insights: DashboardInsights, range: ChartRange, onRange: (ChartRange) -> Unit) {
    val state = insights.seriesFor(range)
    SectionPanel(DashboardSection.ConcurrentStreams, stringResource(R.string.dashboard_concurrent_streams_title), caption = stringResource(range.captionRes())) {
        RangeControl(range, onRange, Modifier.padding(vertical = VeilSpacing.Small))
        val series = state.value
        if (series != null) StreamsBody(range, series) else if (state.error == null) LoadingLine()
        SectionError(state)
    }
}

/** The main chart: streams over the window stacked by how each is delivered, with its latest, peak and lowest count. */
@Composable
private fun StreamsBody(range: ChartRange, series: Timeseries) {
    val locale = rememberLocale()
    val zone = ZoneId.systemDefault()
    val points = remember(series) { buildStreamsSplit(series) }
    val present = remember(points) { points.filter { it.present } }
    if (present.isEmpty()) {
        EmptyLine(stringResource(R.string.dashboard_streams_chart_empty))
        Text(stringResource(R.string.dashboard_chart_empty_detail), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        return
    }
    val latestPoint = present.last()
    val peakPoint = present.reduce { best, next -> if (next.total >= best.total) next else best }
    fun count(v: Int) = formatChartCount(v.toDouble(), locale)
    val latest = count(latestPoint.total)
    val peak = count(peakPoint.total)
    val lowest = count(present.minOf { it.total })
    val description = stringResource(R.string.dashboard_streams_chart_description, stringResource(range.phraseRes())) + ". " +
        stringResource(R.string.dashboard_chart_summary_description, latest, peak, lowest) + "."
    val startEdge = stringResource(range.edgeRes())
    val endEdge = stringResource(R.string.dashboard_chart_edge_now)
    val title = stringResource(R.string.dashboard_concurrent_streams_title)
    val colors = chartColors()
    TrendChartFrame(ChartKind.Streams, title, description, stringResource(R.string.dashboard_chart_summary, latest, peak, lowest), range,
        chart = { reveal ->
            StackedStreamsChart(points, { formatChartCount(it, locale) }, startEdge, endEdge, reveal)
            ChartLegend(listOf(
                colors.directPlay to stringResource(R.string.dashboard_playback_series_direct_play),
                colors.directStream to stringResource(R.string.dashboard_playback_series_direct_stream),
                colors.transcode to stringResource(R.string.dashboard_playback_series_transcode),
            ))
        },
        values = {
            ValueRow(stringResource(R.string.dashboard_chart_latest), latest)
            ValueRow(stringResource(R.string.dashboard_chart_peak),
                "$peak · ${stringResource(R.string.dashboard_chart_peak_at, formatChartTimestamp(range, peakPoint.t, zone, locale, true))}")
            ValueRow(stringResource(R.string.dashboard_chart_lowest), lowest)
            ValueRow(stringResource(R.string.dashboard_chart_average), formatChartCount(present.sumOf { it.total.toDouble() } / present.size, locale))
            ValueRow(stringResource(R.string.dashboard_playback_series_direct_play), count(present.maxOf { it.direct }))
            ValueRow(stringResource(R.string.dashboard_playback_series_direct_stream), count(present.maxOf { it.stream }))
            ValueRow(stringResource(R.string.dashboard_playback_series_transcode), count(present.maxOf { it.transcode }))
            Text(stringResource(R.string.dashboard_chart_readings), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
                modifier = Modifier.padding(top = VeilSpacing.Small))
            pickReadings(points.map { LinePoint(it.t, if (it.present) it.total.toDouble() else null) }, READINGS).forEach { reading ->
                ValueRow(formatChartTimestamp(range, reading.t, zone, locale, true),
                    reading.value?.let { formatChartCount(it, locale) } ?: stringResource(R.string.dashboard_chart_reading_none))
            }
        })
}

/** The body both sampled charts share: a line over the window with its latest, peak and lowest value. */
@Composable
private fun SampledBody(
    kind: ChartKind, range: ChartRange, series: Timeseries, field: TimeseriesField, title: String,
    @androidx.annotation.StringRes chartLabel: Int, @androidx.annotation.StringRes emptyText: Int,
) {
    val locale = rememberLocale()
    val zone = ZoneId.systemDefault()
    val isRate = field != TimeseriesField.Streams
    val points = remember(series, field) { buildLinePoints(series, field) }
    val summary = remember(points) { summarize(points) }
    if (summary == null) {
        EmptyLine(stringResource(emptyText))
        Text(stringResource(R.string.dashboard_chart_empty_detail), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        return
    }
    val downloads = remember(series) { if (isRate && series.hasDownloadTraffic) buildLinePoints(series, TimeseriesField.DownloadMbps) else null }
    fun value(v: Double) = if (isRate) formatMbps(v, locale) else formatChartCount(v, locale)
    val latest = value(summary.latest)
    val peak = value(summary.peak)
    val lowest = value(summary.min)
    val description = stringResource(chartLabel, stringResource(range.phraseRes())) + ". " +
        stringResource(R.string.dashboard_chart_summary_description, latest, peak, lowest) + "."
    val total = if (isRate) series.egress_bytes?.let { formatDataTotal(it, locale) } else null
    val startEdge = stringResource(range.edgeRes())
    val endEdge = stringResource(R.string.dashboard_chart_edge_now)
    val colors = chartColors()
    total?.let {
        Text(stringResource(R.string.dashboard_egress_total_value, it), style = MaterialTheme.typography.titleMedium.tabular())
    }
    TrendChartFrame(kind, title, description, stringResource(R.string.dashboard_chart_summary, latest, peak, lowest), range,
        chart = { reveal ->
            LineTrendChart(points, downloads, minStep = if (isRate) 0.0 else 1.0,
                formatTick = { if (isRate) formatMbpsValue(it, locale) else formatChartCount(it, locale) },
                startEdge = startEdge, endEdge = endEdge, reveal = reveal)
            if (downloads != null) ChartLegend(listOf(
                colors.line to stringResource(R.string.dashboard_egress_series),
                colors.second to stringResource(R.string.dashboard_egress_downloads_series),
            ), dashedLast = true)
        },
        values = {
            ValueRow(stringResource(R.string.dashboard_chart_latest), latest)
            ValueRow(stringResource(R.string.dashboard_chart_peak),
                "$peak · ${stringResource(R.string.dashboard_chart_peak_at, formatChartTimestamp(range, summary.peakAt, zone, locale, true))}")
            ValueRow(stringResource(R.string.dashboard_chart_lowest), lowest)
            ValueRow(stringResource(R.string.dashboard_chart_average), value(summary.average))
            total?.let { ValueRow(stringResource(R.string.dashboard_chart_total), it) }
            Text(stringResource(R.string.dashboard_chart_readings), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
                modifier = Modifier.padding(top = VeilSpacing.Small))
            pickReadings(points, READINGS).forEach { reading ->
                ValueRow(formatChartTimestamp(range, reading.t, zone, locale, true),
                    reading.value?.let { value(it) } ?: stringResource(R.string.dashboard_chart_reading_none))
            }
        })
}
