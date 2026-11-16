package com.bloemstudios.admin.dashboard

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.LocalReducedMotion
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilMotionTokens
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

// Native Compose Canvas charts for the dashboard's trend sections. Series colors are the web admin's chart colors
// (direct play, direct stream, transcode), the grid is the text color at 8%, and the value labels are drawn on their
// own gridlines. The concurrent-streams chart is the one large chart; the others are smaller.

/** Test tags: the chart, its plain-number view, the switch between them and each range button. */
internal fun chartTag(kind: ChartKind) = "dashboard-chart-${kind.tag}"
internal fun chartValuesTag(kind: ChartKind) = "dashboard-chart-values-${kind.tag}"
internal fun chartSummaryTag(kind: ChartKind) = "dashboard-chart-summary-${kind.tag}"
internal fun chartToggleTag(kind: ChartKind) = "dashboard-chart-toggle-${kind.tag}"

/** The one range control moves every trend chart, so its buttons are tagged by range alone. */
internal fun chartRangeTag(range: ChartRange) = "dashboard-chart-range-${range.tag}"

@StringRes internal fun ChartRange.labelRes(): Int = when (this) {
    ChartRange.Hour -> R.string.dashboard_chart_range_hour
    ChartRange.Day -> R.string.dashboard_chart_range_day
    ChartRange.Week -> R.string.dashboard_chart_range_week
    ChartRange.Month -> R.string.dashboard_chart_range_month
}

@StringRes internal fun ChartRange.showRes(): Int = when (this) {
    ChartRange.Hour -> R.string.dashboard_chart_show_hour
    ChartRange.Day -> R.string.dashboard_chart_show_day
    ChartRange.Week -> R.string.dashboard_chart_show_week
    ChartRange.Month -> R.string.dashboard_chart_show_month
}

@StringRes internal fun ChartRange.captionRes(): Int = when (this) {
    ChartRange.Hour -> R.string.dashboard_chart_caption_hour
    ChartRange.Day -> R.string.dashboard_chart_caption_day
    ChartRange.Week -> R.string.dashboard_chart_caption_week
    ChartRange.Month -> R.string.dashboard_chart_caption_month
}

@StringRes internal fun ChartRange.phraseRes(): Int = when (this) {
    ChartRange.Hour -> R.string.dashboard_chart_phrase_hour
    ChartRange.Day -> R.string.dashboard_chart_phrase_day
    ChartRange.Week -> R.string.dashboard_chart_phrase_week
    ChartRange.Month -> R.string.dashboard_chart_phrase_month
}

@StringRes internal fun ChartRange.edgeRes(): Int = when (this) {
    ChartRange.Hour -> R.string.dashboard_chart_edge_hour
    ChartRange.Day -> R.string.dashboard_chart_edge_day
    ChartRange.Week -> R.string.dashboard_chart_edge_week
    ChartRange.Month -> R.string.dashboard_chart_edge_month
}

@Immutable
internal class ChartColors(
    /** The single series of a chart that is not split by play method (egress): the text color. */
    val line: Color, val second: Color, val grid: Color, val label: Color,
    val directPlay: Color, val directStream: Color, val transcode: Color, val other: Color, val separator: Color,
)

@Composable
internal fun chartColors(): ChartColors = ChartColors(
    line = VeilColors.Text, second = VeilColors.Muted, grid = VeilColors.Hairline, label = VeilColors.Muted,
    // The web's fixed series order: direct play at the baseline, direct stream above it, transcode on top.
    directPlay = VeilColors.PlayDirect, directStream = VeilColors.PlayStream, transcode = VeilColors.PlayTranscode,
    other = VeilColors.Muted.copy(alpha = 0.5f), separator = VeilColors.Card,
)

/** The plot's height, without the labels around it: the main chart is large, the others small. */
internal val MainPlotHeight = 168.dp
internal val SmallPlotHeight = 64.dp

/** How long a chart takes to draw itself in: no time at all under reduced motion, so nothing animates. */
internal fun chartRevealMillis(reducedMotion: Boolean, motion: VeilMotionTokens = VeilMotionTokens()): Int =
    if (reducedMotion) 0 else motion.sheetMillis

/**
 * The reveal of a chart after the person changes its window: 0 to 1. A chart that is simply appearing, or refreshing
 * with the same window, is already drawn, so a poll or a page change never replays it.
 */
@Composable
private fun rememberReveal(range: ChartRange): Animatable<Float, AnimationVector1D> {
    val millis = chartRevealMillis(LocalReducedMotion.current)
    val motion = remember { VeilMotionTokens() }
    val reveal = remember { Animatable(1f) }
    var drawnFor by remember { mutableStateOf(range) }
    LaunchedEffect(range, millis) {
        if (range == drawnFor) return@LaunchedEffect
        drawnFor = range
        if (millis == 0) { reveal.snapTo(1f); return@LaunchedEffect }
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(millis, easing = motion.ease))
    }
    return reveal
}

// ---- The one range control ----

/** One control for every trend chart: the web's windows as a segmented row, the chosen one marked by a step, not by weight. */
@Composable
internal fun RangeControl(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    val labels = ChartRange.entries.map { stringResource(it.labelRes()) }
    val shows = ChartRange.entries.map { stringResource(it.showRes()) }
    SegmentedControl(
        options = labels,
        selectedIndex = ChartRange.entries.indexOf(selected),
        onSelect = { onSelect(ChartRange.entries[it]) },
        modifier = modifier,
        optionModifier = { index ->
            Modifier.testTag(chartRangeTag(ChartRange.entries[index])).semantics { contentDescription = shows[index] }
        },
    )
}

// ---- Frame: summary, chart or plain numbers, switch ----

/**
 * A chart with its summary line above and the switch to its plain numbers below. The summary and the chart are
 * one image to accessibility services, described by [description] (window, latest, peak); the numbers are the
 * alternative to it.
 */
@Composable
internal fun TrendChartFrame(
    kind: ChartKind, title: String, description: String, summaryLine: String?, range: ChartRange,
    chart: @Composable (Animatable<Float, AnimationVector1D>) -> Unit,
    values: @Composable ColumnScope.() -> Unit,
) {
    var showValues by rememberSaveable(kind.tag) { mutableStateOf(false) }
    val reveal = rememberReveal(range)
    CompositionLocalProvider(LocalContentColor provides VeilColors.Text) {
        Column(Modifier.fillMaxWidth()) {
            if (showValues) Column(Modifier.fillMaxWidth().testTag(chartValuesTag(kind)), content = values)
            else Column(Modifier.fillMaxWidth().testTag(chartTag(kind)).clearAndSetSemantics {
                contentDescription = description
                role = Role.Image
            }) {
                summaryLine?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted,
                        modifier = Modifier.padding(bottom = VeilSpacing.Small).testTag(chartSummaryTag(kind)))
                }
                chart(reveal)
            }
            val switchLabel = stringResource(if (showValues) R.string.dashboard_chart_show_chart_for else R.string.dashboard_chart_show_values_for, title)
            TextButton(
                onClick = { showValues = !showValues },
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(chartToggleTag(kind)).semantics { contentDescription = switchLabel },
            ) { Text(stringResource(if (showValues) R.string.dashboard_chart_show_chart else R.string.dashboard_chart_show_values)) }
        }
    }
}

/** One label and value of the plain-number view. */
@Composable
internal fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = VeilSpacing.Small), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), color = VeilColors.Text)
    }
}

// ---- The plot: gridlines, value labels centred on them, edge labels ----

/** Where the plotted area sits inside the canvas, and how to place a value on it. */
private class Plot(val left: Float, val right: Float, val top: Float, val height: Float, val axisTop: Double) {
    val width: Float get() = right - left
    fun y(value: Double): Float = (top + height - (value / axisTop * height).toFloat()).coerceIn(top, top + height)
    fun x(ratio: Float): Float = left + ratio * width
}

/**
 * The canvas every trend chart draws on: a gridline per tick with its label centred on it (the labels are drawn in
 * the canvas so they cannot drift off their lines), then [draw] for the series, then the two window edge labels.
 */
@Composable
private fun TrendPlot(
    plotHeight: Dp, ticks: List<Double>, axisTop: Double, formatTick: (Double) -> String,
    startEdge: String, endEdge: String, colors: ChartColors, draw: DrawScope.(Plot) -> Unit,
) {
    val measurer: TextMeasurer = rememberTextMeasurer()
    val style: TextStyle = MaterialTheme.typography.labelSmall.tabular().copy(color = colors.label)
    val density = LocalDensity.current
    val labelHeight = remember(style, density) { measurer.measure("0", style).size.height }
    val canvasHeight = plotHeight + with(density) { (labelHeight * 2.5f).toDp() }
    Canvas(Modifier.fillMaxWidth().height(canvasHeight)) {
        val gap = 8.dp.toPx()
        val labels = ticks.map { it to measurer.measure(formatTick(it), style) }
        val gutter = (labels.maxOfOrNull { it.second.size.width } ?: 0) + gap
        val plot = Plot(
            left = gutter, right = size.width, top = labelHeight / 2f,
            height = plotHeight.toPx(), axisTop = axisTop,
        )
        labels.forEach { (tick, layout) ->
            val y = plot.y(tick)
            drawLine(colors.grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
            drawText(layout, topLeft = Offset(gutter - gap - layout.size.width, y - layout.size.height / 2f))
        }
        draw(plot)
        val edgeY = plot.top + plot.height + labelHeight / 2f + 4.dp.toPx()
        drawText(measurer, startEdge, Offset(plot.left, edgeY), style)
        val end = measurer.measure(endEdge, style)
        drawText(end, topLeft = Offset(plot.right - end.size.width, edgeY))
    }
}

// ---- Stacked area: concurrent streams split by play method ----

/**
 * Concurrent streams over the window, stacked direct play, direct stream, transcode from the baseline up. A sample the
 * sampler missed breaks the stack into separate runs instead of dropping to zero.
 */
@Composable
internal fun StackedStreamsChart(
    points: List<StreamsSplit>, formatTick: (Double) -> String, startEdge: String, endEdge: String,
    reveal: Animatable<Float, AnimationVector1D>, plotHeight: Dp = MainPlotHeight,
) {
    val colors = chartColors()
    val (ticks, top) = remember(points) { splitAxisTop(points) }
    val xs = remember(points) { xRatios(points.map { LinePoint(it.t, null) }) }
    val runs = remember(points) { splitRuns(points) }
    TrendPlot(plotHeight, ticks, top, formatTick, startEdge, endEdge, colors) { plot ->
        clipRect(left = plot.left, top = 0f, right = plot.left + plot.width * reveal.value + 1f, bottom = size.height) {
            val layers = listOf(colors.directPlay, colors.directStream, colors.transcode, colors.other)
            runs.forEach { run ->
                if (run.size == 1) {
                    val point = run.single().value
                    val barWidth = 3.dp.toPx()
                    var bottom = plot.y(0.0)
                    layerValues(point).forEachIndexed { layer, count ->
                        if (count <= 0) return@forEachIndexed
                        val topY = plot.y(cumulative(point, layer).toDouble())
                        drawRect(layers[layer], Offset(plot.x(xs[run.single().index]) - barWidth / 2f, topY), Size(barWidth, bottom - topY))
                        bottom = topY
                    }
                    return@forEach
                }
                for (layer in layers.indices.reversed()) {
                    val upper = run.map { Offset(plot.x(xs[it.index]), plot.y(cumulative(it.value, layer).toDouble())) }
                    val lower = run.map { Offset(plot.x(xs[it.index]), plot.y(if (layer == 0) 0.0 else cumulative(it.value, layer - 1).toDouble())) }
                    if (upper.zip(lower).all { (u, l) -> u.y == l.y }) continue
                    val area = Path().apply {
                        moveTo(upper.first().x, upper.first().y)
                        upper.drop(1).forEach { lineTo(it.x, it.y) }
                        lower.reversed().forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(area, layers[layer])
                    // A hairline of the surface between layers keeps neighbours apart without relying on hue.
                    val edge = Path().apply { moveTo(upper.first().x, upper.first().y); upper.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(edge, colors.separator, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
                }
            }
        }
    }
}

private fun layerValues(p: StreamsSplit) = listOf(p.direct, p.stream, p.transcode, p.other)
private fun cumulative(p: StreamsSplit, upTo: Int) = layerValues(p).take(upTo + 1).sum()

/** The stretches of consecutive sampled points of a split, each with its index. */
internal fun splitRuns(points: List<StreamsSplit>): List<List<IndexedValue<StreamsSplit>>> {
    val runs = mutableListOf<List<IndexedValue<StreamsSplit>>>()
    var current = mutableListOf<IndexedValue<StreamsSplit>>()
    points.withIndex().forEach { point ->
        if (point.value.present) current += point
        else if (current.isNotEmpty()) { runs += current; current = mutableListOf() }
    }
    if (current.isNotEmpty()) runs += current
    return runs
}

// ---- Line chart ----

/**
 * A time line with an area wash. Missing samples break the line instead of dropping to zero, an isolated
 * sample is a dot, and the newest sample is marked. The value axis starts at zero.
 */
@Composable
internal fun LineTrendChart(
    points: List<LinePoint>, second: List<LinePoint>?, minStep: Double, formatTick: (Double) -> String,
    startEdge: String, endEdge: String, reveal: Animatable<Float, AnimationVector1D>, plotHeight: Dp = SmallPlotHeight,
) {
    val colors = chartColors()
    val axis = remember(points, second, minStep) { chartAxisTop(points + second.orEmpty(), minStep) }
    val (ticks, top) = axis
    val xs = remember(points) { xRatios(points) }
    val runs = remember(points) { plottedRuns(points) }
    val secondRuns = remember(second) { second?.let { plottedRuns(it) } }
    TrendPlot(plotHeight, ticks, top, formatTick, startEdge, endEdge, colors) { plot ->
        val line = 2.dp.toPx()
        clipRect(left = plot.left, top = 0f, right = plot.left + plot.width * reveal.value + 1f, bottom = size.height) {
            fun drawRuns(source: List<List<IndexedValue<LinePoint>>>, xRatio: (Int) -> Float, color: Color, fill: Boolean, dashed: Boolean) {
                source.forEach { run ->
                    val offsets = run.map { Offset(plot.x(xRatio(it.index)), plot.y(it.value.value!!)) }
                    if (offsets.size == 1) {
                        drawCircle(color, radius = 3.dp.toPx(), center = offsets.single())
                        return@forEach
                    }
                    val path = Path().apply { moveTo(offsets.first().x, offsets.first().y); offsets.drop(1).forEach { lineTo(it.x, it.y) } }
                    if (fill) {
                        val area = Path().apply {
                            addPath(path); lineTo(offsets.last().x, plot.y(0.0)); lineTo(offsets.first().x, plot.y(0.0)); close()
                        }
                        drawPath(area, color.copy(alpha = 0.12f))
                    }
                    drawPath(path, color, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
                }
            }
            drawRuns(runs, { xs[it] }, colors.line, fill = true, dashed = false)
            if (second != null && secondRuns != null) {
                val secondXs = xRatios(second)
                drawRuns(secondRuns, { secondXs[it] }, colors.second, fill = false, dashed = true)
            }
            runs.lastOrNull()?.lastOrNull()?.let { latest ->
                drawCircle(colors.line, radius = 3.5.dp.toPx(), center = Offset(plot.x(xs[latest.index]), plot.y(latest.value.value!!)))
            }
        }
    }
}

// ---- Stacked columns ----

/** Columns of sessions per bucket, stacked direct play, direct stream, transcode from the baseline up. */
@Composable
internal fun ColumnsTrendChart(
    columns: List<PlaybackColumn>, formatTick: (Double) -> String, startEdge: String, endEdge: String,
    reveal: Animatable<Float, AnimationVector1D>, plotHeight: Dp = SmallPlotHeight,
) {
    val colors = chartColors()
    val (ticks, top) = remember(columns) { columnAxisTop(columns) }
    val seriesColors = listOf(colors.directPlay, colors.directStream, colors.transcode)
    TrendPlot(plotHeight, ticks, top, formatTick, startEdge, endEdge, colors) { plot ->
        if (columns.isEmpty()) return@TrendPlot
        val slot = plot.width / columns.size
        val barWidth = (slot * 0.7f).coerceIn(1.dp.toPx(), 28.dp.toPx())
        val minSegment = 1.dp.toPx()
        columns.forEachIndexed { index, column ->
            val x = plot.left + index * slot + (slot - barWidth) / 2f
            var bottom = plot.y(0.0)
            column.segments.forEachIndexed { series, count ->
                if (count <= 0) return@forEachIndexed
                val segment = maxOf((count / top * plot.height).toFloat() * reveal.value, minSegment)
                drawRect(seriesColors[series], Offset(x, bottom - segment), Size(barWidth, segment))
                // A hairline of the surface between segments, so neighbours stay apart without relying on hue.
                drawLine(colors.separator, Offset(x, bottom - segment), Offset(x + barWidth, bottom - segment), strokeWidth = 1.dp.toPx())
                bottom -= segment
            }
        }
    }
}

/** Swatch and name of each series, so the colors are never the only way to tell them apart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartLegend(items: List<Pair<Color, String>>, dashedLast: Boolean = false) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = VeilSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
    ) {
        items.forEachIndexed { index, (color, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dashedLast && index == items.lastIndex) {
                    Canvas(Modifier.size(width = 14.dp, height = 8.dp)) {
                        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                    }
                } else Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(VeilSpacing.Small))
                Text(text, style = MaterialTheme.typography.labelMedium.tabular(), color = VeilColors.Muted)
            }
        }
    }
}
