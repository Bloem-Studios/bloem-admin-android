package com.bloemstudios.admin.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.bloemstudios.admin.R
import com.bloemstudios.admin.streams.StreamMethod
import com.bloemstudios.admin.streams.chartIndex
import com.bloemstudios.admin.streams.formatBitrate
import com.bloemstudios.admin.streams.formatPlaybackClock
import com.bloemstudios.admin.streams.formatRemainingTime
import com.bloemstudios.admin.streams.labelRes

// ---- Now playing: the app's session cards, in small -------------------------------------------------------------------
// The words, the method colours and the transcode detail come from the app's own presentation (streams/StreamPresentation.kt,
// StreamMethodMarks.kt). The layout comes from NowPlayingPlanner: the cards follow the height, the detail follows the width.

internal val NowPlayingWording = Wording(
    icon = R.drawable.lucide_radio,
    title = R.string.widget_now_playing_label,
    signIn = R.string.widget_now_playing_sign_in,
    unsupported = R.string.widget_now_playing_unsupported,
    unsupportedAll = R.string.widget_now_playing_unsupported_all,
    empty = R.string.widget_now_playing_empty,
)

@Composable
internal fun NowPlayingContent(state: WidgetState<NowPlayingView>, env: WidgetEnv, openApp: Action) {
    // Every stream that was playing has ended since the last read: the same calm sentence as nothing playing.
    val effective = (state as? WidgetState.Content<NowPlayingView>)
        ?.takeIf { it.data.total == 0 && !it.data.detailsHidden }
        ?.let { WidgetState.Empty(it.staleAt) } ?: state
    if (StateBody(effective, NowPlayingWording, env, openApp) { last, _ ->
            Text(LocalContext.current.resources.getQuantityString(R.plurals.widget_streams_count, last.total, last.total), style = MutedStyle, maxLines = 1)
        }) return
    val content = effective as WidgetState.Content<NowPlayingView>
    val view = content.data
    val size = LocalSize.current
    val plan = NowPlayingPlanner.plan(size.width.value, size.height.value, view.rows.size, fontScale())
    val staleLabel = content.staleAt?.let { res(R.string.widget_updated_at, timeOfDay(it)) }

    WidgetPage(env.open(view.link)) {
        if (view.detailsHidden && size.width >= 200.dp) {
            // The lock screen, wide: the header and the three figures in one card, with no name or title anywhere.
            val slim = size.height < 150.dp
            PageHeader(
                R.drawable.lucide_radio, res(R.string.widget_now_playing_label), env, size.width, live = true,
                status = res(R.string.widget_live_count, view.total), trailing = view.serverName ?: res(R.string.widget_all_servers), staleLabel = staleLabel, slim = slim,
            )
            SpacerH(if (slim) NowPlayingPlanner.SLIM_GAP.toInt() else Page.GAP)
            val bandwidth = bandwidthParts(view.bandwidthKbps)
            StatsCard(
                listOf(
                    view.total.toString() to res(R.string.widget_figure_streams), view.transcoding.toString() to res(R.string.widget_figure_transcoding),
                    (bandwidth?.first ?: "–") to (bandwidth?.second ?: res(R.string.widget_figure_bandwidth)),
                ),
                null, env.open(view.link), GlanceModifier.defaultWeight(),
            )
            return@WidgetPage
        }
        if (plan.small || view.detailsHidden) {
            NowPlayingFigure(view, plan, env, staleLabel, size.width)
            return@WidgetPage
        }
        if (plan.header) {
            PageHeader(
                R.drawable.lucide_radio, res(R.string.widget_now_playing_label), env, size.width,
                live = true, status = res(R.string.widget_live_count, view.total),
                trailing = view.serverName ?: res(R.string.widget_all_servers), staleLabel = staleLabel, slim = plan.slimHeader,
            )
            SpacerH(if (plan.slimHeader) NowPlayingPlanner.SLIM_GAP.toInt() else Page.GAP)
        }
        val innerWidth = size.width - (Page.PAD_H * 2).dp
        val cardHeight = plan.cardHeight.dp
        if (plan.scrolls && WidgetRendering.lazyLists) {
            // Whole cards only: the list is exactly as tall as the cards it shows, and the rest is a swipe away.
            LazyColumn(GlanceModifier.fillMaxWidth().height(((plan.cardHeight + Page.GAP) * plan.visible).dp)) {
                itemsIndexed(view.rows) { index, row ->
                    Column(GlanceModifier.fillMaxWidth()) {
                        SessionCard(row, plan.tier, innerWidth, cardHeight, plan.showBitrate, env)
                        SpacerH(Page.GAP)
                    }
                }
            }
        } else {
            // Not scrolling: every card. When a screenshot cannot draw a scrolling list, the same cards in a column cut to the list's
            // height, which is what the list shows at rest.
            val listModifier = if (plan.scrolls) GlanceModifier.fillMaxWidth().height(plan.listHeight().dp) else GlanceModifier.fillMaxWidth()
            Column(listModifier) {
                view.rows.forEachIndexed { index, row ->
                    if (index > 0) SpacerH(Page.GAP)
                    SessionCard(row, plan.tier, innerWidth, cardHeight, plan.showBitrate, env)
                }
            }
        }
        if (plan.footer != NpFooter.None) {
            Spacer(GlanceModifier.defaultWeight())
            Footer(view, plan.footer, plan.footerHeight, size.width, env)
        }
    }
}

/** For screenshots: `GlanceRemoteViews` cannot fill a scrolling list, so the harness switches it off to see the cards. Always on in the app. */
internal object WidgetRendering {
    @Volatile var lazyLists: Boolean = true
}

// ---- One session -------------------------------------------------------------------------------------------------------

@Composable
private fun methodColor(method: StreamMethod): ColorProvider = method.chartIndex?.let { Tokens.chart(it) } ?: Tokens.Muted

/** "HW QSV · to H.264 1080p": what the transcode does, as the app writes it on a lit Playback step. */
@Composable
private fun transcodeDetail(row: StreamRowView): String? {
    val mode = when (val m = row.transcodeMode) {
        null -> null
        "sw" -> res(R.string.streams_mode_software)
        "pending" -> res(R.string.streams_mode_pending)
        "unknown" -> res(R.string.streams_mode_unknown)
        "audio" -> res(R.string.streams_mode_audio_only)
        else -> if (m.startsWith("hw:")) res(R.string.streams_mode_hardware, m.removePrefix("hw:")) else null
    }
    return listOfNotNull(mode, row.target?.let { res(R.string.streams_path_output, it) }).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun timeText(row: StreamRowView, now: Long): String {
    val position = formatPlaybackClock(row.positionAt(now))
    return row.durationSeconds?.takeIf { it > 0 }?.let { res(R.string.streams_time_progress, position, formatPlaybackClock(it)) } ?: position
}

/** "23m left", or "Paused" in the amber the app uses; null when the file's length is not known. */
@Composable
private fun leftText(row: StreamRowView, now: Long): String? =
    if (row.paused) res(R.string.widget_paused) else row.remainingAt(now)?.let { res(R.string.widget_time_left, formatRemainingTime(it)) }

@Composable
private fun StatusMark(row: StreamRowView, word: Boolean) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (row.paused) StatusDot(Tokens.Warning) else TallyDot()
        if (word) {
            SpacerW(4)
            Text(res(if (row.paused) R.string.status_paused else R.string.status_playing), style = if (row.paused) CaptionStyle.copy(color = Tokens.Warning) else CaptionStyle, maxLines = 1)
        }
    }
}

/** The method's dot and word; a transcode is lit in amber, with its detail when there is room. */
@Composable
private fun MethodLine(row: StreamRowView, detail: Boolean, modifier: GlanceModifier = GlanceModifier) {
    val lit = row.method == StreamMethod.Transcode
    val word = res(row.method.labelRes)
    Row(modifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
        StatusDot(methodColor(row.method))
        SpacerW(4)
        val text = if (lit && detail) listOfNotNull(word, transcodeDetail(row)).joinToString(" · ") else word
        Text(text, style = if (lit) CaptionStyle.copy(color = Tokens.Warning) else CaptionStyle, maxLines = 1)
    }
}

@Composable
private fun SessionCard(row: StreamRowView, tier: NpTier, width: Dp, height: Dp, showBitrate: Boolean, env: WidgetEnv) {
    val now = env.nowMillis
    val inner = width - 2.dp - 20.dp
    RowCard(GlanceModifier.fillMaxWidth().height(height), env.open(row.link)) {
        when (tier) {
            NpTier.Compact -> CompactBody(row, inner, now)
            NpTier.Roomy -> RoomyBody(row, inner, now, showBitrate)
            NpTier.Full -> FullBody(row, inner, now)
        }
    }
}

@Composable
private fun CompactBody(row: StreamRowView, inner: Dp, now: Long) {
    val who = listOfNotNull(row.userLabel, row.device).joinToString(" · ")
    val left = leftText(row, now)
    Column(GlanceModifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 4.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(row.title, style = RowTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (left != null && inner >= 190.dp) {
                SpacerW(6)
                Text(left, style = if (row.paused) CaptionStyle.copy(color = Tokens.Warning) else CaptionStyle, maxLines = 1)
            }
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            MethodLine(row, detail = false)
            if (who.isNotEmpty()) {
                SpacerW(4)
                Text("· $who", style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        BarTrack(row.progressAt(now), inner, 3.dp, if (row.paused) Tokens.Warning else Tokens.Text)
    }
}

/** Title and status; the method, who and the bitrate; the bar; the time and the time left. */
@Composable
private fun RoomyBody(row: StreamRowView, inner: Dp, now: Long, showBitrate: Boolean) {
    val who = listOfNotNull(row.userLabel, row.device).joinToString(" · ")
    val bitrate = formatBitrate(row.bitrateKbps)
    val left = leftText(row, now)
    val lit = row.method == StreamMethod.Transcode
    Column(GlanceModifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 4.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(row.title, style = CardTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            SpacerW(6)
            StatusMark(row, word = inner >= 200.dp)
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            MethodLine(row, detail = lit && inner >= 250.dp)
            if (!(lit && inner >= 250.dp) && who.isNotEmpty()) {
                SpacerW(4)
                Text("· $who", style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            } else Spacer(GlanceModifier.defaultWeight())
            if (showBitrate && bitrate != null) { SpacerW(6); Text(bitrate, style = CaptionStyle, maxLines = 1) }
        }
        Spacer(GlanceModifier.defaultWeight())
        BarTrack(row.progressAt(now), inner, 4.dp, if (row.paused) Tokens.Warning else Tokens.Text)
        SpacerH(2)
        Row(GlanceModifier.fillMaxWidth()) {
            Text(timeText(row, now), style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (left != null && inner >= 190.dp) Text(left, style = if (row.paused) CaptionStyle.copy(color = Tokens.Warning) else CaptionStyle, maxLines = 1)
        }
    }
}

/** The app's whole card: title and status, who, then Source to Playback to Client, the bar, the time and the bitrate. */
@Composable
private fun FullBody(row: StreamRowView, inner: Dp, now: Long) {
    val bitrate = formatBitrate(row.bitrateKbps)
    val lit = row.method == StreamMethod.Transcode
    val sourceWidth = inner * 0.30f
    val playbackWidth = inner * 0.38f
    val clientWidth = inner * 0.32f
    Column(GlanceModifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 4.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(row.title, style = CardTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            SpacerW(6)
            StatusMark(row, word = true)
        }
        Text(row.userLabel, style = CaptionStyle, maxLines = 1)
        SpacerH(2)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Top) {
            PathStep(res(R.string.streams_path_source), row.source ?: res(R.string.streams_detail_unknown_source), sourceWidth)
            // The playback step is the one a transcode lights: raised, in amber, with what it is doing.
            val stepModifier = GlanceModifier.width(playbackWidth).let { if (lit) it.background(Tokens.Raised).cornerRadius(4.dp) else it }
            Column(stepModifier.padding(horizontal = if (lit) 4.dp else 0.dp)) {
                Text(res(R.string.streams_path_playback), style = CaptionStyle, maxLines = 1)
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    StatusDot(methodColor(row.method))
                    SpacerW(4)
                    Text(res(row.method.labelRes), style = if (lit) ValueStyle.copy(color = Tokens.Warning) else ValueStyle, maxLines = 1)
                }
                if (lit) transcodeDetail(row)?.let { Text(it, style = CaptionStyle.copy(color = Tokens.Warning), maxLines = 1) }
            }
            PathStep(res(R.string.streams_path_client), row.client ?: res(R.string.streams_path_unknown_client), clientWidth)
        }
        Spacer(GlanceModifier.defaultWeight())
        BarTrack(row.progressAt(now), inner, 4.dp, if (row.paused) Tokens.Warning else Tokens.Text)
        SpacerH(2)
        Row(GlanceModifier.fillMaxWidth()) {
            Text(timeText(row, now), style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (bitrate != null) Text(bitrate, style = CaptionStyle, maxLines = 1)
        }
    }
}

@Composable
private fun PathStep(caption: String, value: String, width: Dp) {
    Column(GlanceModifier.width(width).padding(end = 4.dp)) {
        Text(caption, style = CaptionStyle, maxLines = 1)
        Text(value, style = ValueStyle, maxLines = 1)
    }
}

// ---- The small layout, and the totals ------------------------------------------------------------------------------------

/** Small, and the lock screen: "Live", the count, and (on the home screen) the top stream's title and bar. */
@Composable
private fun ColumnScope.NowPlayingFigure(view: NowPlayingView, plan: NpPlan, env: WidgetEnv, staleLabel: String?, width: Dp) {
    val context = LocalContext.current
    val top = view.rows.firstOrNull()
    SmallHeader(res(R.string.widget_live), live = true, env = env, width = width)
    Column(GlanceModifier.padding(start = 2.dp)) {
        Text(view.total.toString(), style = FigureStyle, maxLines = 1)
        Text(res(R.string.widget_streams), style = CaptionStyle, maxLines = 1)
        if (view.transcoding > 0 && top == null) Text(context.getString(R.string.widget_transcoding_count, view.transcoding), style = CaptionStyle, maxLines = 1)
    }
    Spacer(GlanceModifier.defaultWeight())
    if (top != null) {
        RowCard(GlanceModifier.fillMaxWidth().height(40.dp), env.open(top.link)) {
            Column(GlanceModifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 5.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(top.title, style = CardTitleStyle, maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                BarTrack(top.progressAt(env.nowMillis), width - (Page.PAD_H * 2).dp - 2.dp - 16.dp, 3.dp, if (top.paused) Tokens.Warning else Tokens.Text)
            }
        }
        SpacerH(4)
    } else if (staleLabel != null) {
        Text(staleLabel, style = CaptionStyle, maxLines = 1)
    }
}

/** Totals under the cards: one line, or three figures when there is room for them. */
@Composable
private fun Footer(view: NowPlayingView, kind: NpFooter, height: Float, width: Dp, env: WidgetEnv) {
    val context = LocalContext.current
    val streams = context.resources.getQuantityString(R.plurals.widget_streams_count, view.total, view.total)
    val bandwidth = bandwidthParts(view.bandwidthKbps)
    when (kind) {
        NpFooter.None -> Unit
        NpFooter.Line -> Text(
            // A narrow widget drops the transcoding count rather than cut a word.
            listOfNotNull(
                streams,
                context.getString(R.string.widget_transcoding_count, view.transcoding).takeIf { view.transcoding > 0 && width >= 220.dp },
                bandwidth?.let { "${it.first} ${it.second}" },
            ).joinToString(" · "),
            style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.padding(horizontal = 2.dp),
        )
        NpFooter.Stats -> RowCard(GlanceModifier.fillMaxWidth().height(height.dp), env.open(view.link)) {
            val big = height >= 84f
            Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Stat(view.total.toString(), res(R.string.widget_figure_streams), big, GlanceModifier.defaultWeight())
                Stat(view.transcoding.toString(), res(R.string.widget_figure_transcoding), big, GlanceModifier.defaultWeight())
                Stat(bandwidth?.first ?: "–", bandwidth?.second ?: res(R.string.widget_figure_bandwidth), big, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, big: Boolean, modifier: GlanceModifier) {
    Column(modifier) {
        Text(value, style = if (big) FigureStyle else FigureMediumStyle, maxLines = 1)
        Text(label, style = CaptionStyle, maxLines = 1)
    }
}

/** The sum of the streams' rates as a figure and its unit ("65.5", "Mbps"), or null when no server reported any. */
internal fun bandwidthParts(kbps: Long): Pair<String, String>? {
    val text = formatBitrate(kbps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) ?: return null
    return text.substringBefore(' ') to text.substringAfter(' ')
}
