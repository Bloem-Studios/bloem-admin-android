package com.bloemstudios.admin.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.Text
import com.bloemstudios.admin.R

internal val StatusWording = Wording(
    icon = R.drawable.lucide_server,
    title = R.string.widget_label,
    signIn = R.string.widget_sign_in_for_counts,
    unsupported = R.string.widget_counts_unavailable,
    unsupportedAll = R.string.widget_counts_unavailable,
    empty = R.string.widget_no_servers,
)

private val ServerTiers = listOf(
    TierSpec(height = 41f, minWidth = 190f), // the name and the streams, then whether it answers and what is waiting
    TierSpec(height = 56f, minWidth = 0f), // narrow: the name, whether it answers, then what is playing and waiting
)

/** Server status: whether each saved server answers, the streams playing and the requests waiting, and Refresh. */
@Composable
internal fun ServerStatusContent(state: WidgetState<ServerStatusView>, env: WidgetEnv, openApp: Action) {
    if (StateBody(state, StatusWording, env, openApp) { _, _ -> }) return
    val view = (state as WidgetState.Content<ServerStatusView>).data
    val single = view.summary as? WidgetModel.Single
    val reachable = view.servers - view.down
    ListWidget(
        ListSpec(
            icon = R.drawable.lucide_server, title = res(R.string.widget_label), tiers = ServerTiers, rows = view.rows, open = env.open(view.streamsLink),
            live = view.tally,
            status = if (view.down > 0) res(R.string.widget_status_down, view.down) else res(R.string.widget_all_reachable, reachable, view.servers),
            trailing = single?.serverName ?: res(R.string.widget_all_servers),
            staleLabel = view.staleAt?.let { res(R.string.widget_updated_at, timeOfDay(it)) },
            footerLine = res(R.string.widget_status_totals, reachable, view.servers, view.streams, view.requests),
            footerLineShort = res(R.string.widget_all_reachable, reachable, view.servers),
            stats = listOf(
                "$reachable/${view.servers}" to res(R.string.widget_figure_servers_up),
                view.streams.toString() to res(R.string.widget_figure_streams),
                view.requests.toString() to res(R.string.widget_figure_requests),
            ),
            figure = if (view.hasCounts) view.streams.toString() else "$reachable/${view.servers}",
            figureCaption = if (view.hasCounts) res(R.string.widget_streams) else res(R.string.widget_figure_servers_up),
            figureLive = view.tally,
            card = { row, tier, width, height -> ServerCard(row, tier, width, height, env) },
            topCard = { row, width -> ServerCard(row, tier = 0, width = width, height = 40.dp, env = env) },
        ),
        env,
    )
}

@Composable
private fun ServerCard(row: ServerRowView, tier: Int, width: Dp, height: Dp, env: WidgetEnv) {
    val context = LocalContext.current
    val (color, word) = when (row.reach) {
        Reach.NotReachable -> Tokens.Danger to res(R.string.widget_reach_not_reachable)
        Reach.Reachable -> Tokens.Ok to res(R.string.widget_reach_reachable)
        Reach.NotChecked -> Tokens.Muted to res(R.string.widget_reach_not_checked)
    }
    val counts = row.counts
    val streams = (counts as? WidgetCounts.Known)?.let { context.resources.getQuantityString(R.plurals.widget_streams_count, it.streams, it.streams) }
    val waiting = (counts as? WidgetCounts.Known)?.let { context.resources.getQuantityString(R.plurals.widget_requests_waiting, it.requests, it.requests) }
    val note = when {
        row.reach == Reach.NotReachable -> null
        counts == WidgetCounts.SignInNeeded -> context.getString(R.string.widget_sign_in_for_counts)
        counts == WidgetCounts.Unavailable -> context.getString(R.string.widget_counts_unavailable)
        else -> null
    }
    RowCard(GlanceModifier.fillMaxWidth().height(height), env.open(row.link)) {
        Column(cardPadding(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(row.name, style = RowTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                if (tier == 0 && streams != null && row.reach != Reach.NotReachable) { SpacerW(6); Text(streams, style = CaptionStyle, maxLines = 1) }
            }
            if (tier == 0) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    StateMark(color, word)
                    val tail = if (row.reach == Reach.NotReachable) null else waiting ?: note
                    if (tail != null) { SpacerW(4); Text("· $tail", style = CaptionStyle, maxLines = 1) }
                }
            } else {
                StateMark(color, word)
                if (row.reach != Reach.NotReachable) {
                    val short = (counts as? WidgetCounts.Known)?.let { context.getString(R.string.widget_waiting_short, it.requests) }
                    val line = listOfNotNull(streams, short).joinToString(" · ").ifEmpty { note }
                    if (line != null) Text(line, style = CaptionStyle, maxLines = 1)
                }
            }
        }
    }
}
