package com.bloemstudios.admin.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.bloemstudios.admin.R

// ---- Requests: the app's request cards ----------------------------------------------------------------------------------------

internal val RequestsWording = Wording(
    icon = R.drawable.lucide_inbox,
    title = R.string.widget_requests_label,
    signIn = R.string.widget_requests_sign_in,
    unsupported = R.string.widget_requests_unsupported,
    unsupportedAll = R.string.widget_requests_unsupported_all,
    empty = R.string.widget_requests_empty,
)

private val RequestTiers = listOf(
    TierSpec(height = 78f, minWidth = 220f), // title, who and age, then Approve and Decline as the page has them
    TierSpec(height = 41f, minWidth = 190f), // title, who and age, with the two as icon buttons at the right
    TierSpec(height = 74f, minWidth = 0f), // narrow: title, who and age, then the two icon buttons side by side
)

@Composable
internal fun RequestsContent(state: WidgetState<RequestsView>, env: WidgetEnv, openApp: Action) {
    if (StateBody(state, RequestsWording, env, openApp) { last, _ -> Text(LocalContext.current.getString(R.string.widget_requests_waiting_count, last.pending), style = MutedStyle, maxLines = 1) }) return
    val content = state as WidgetState.Content<RequestsView>
    val view = content.data
    val context = LocalContext.current
    val oldest = view.rows.mapNotNull { it.ageMillis }.maxOrNull()?.let { formatAge(context, it) }
    ListWidget(
        ListSpec(
            icon = R.drawable.lucide_inbox, title = res(R.string.widget_requests_label), tiers = RequestTiers, rows = view.rows, open = env.open(view.link),
            live = view.pending > 0, status = res(R.string.widget_waiting_short, view.pending), trailing = view.serverName ?: res(R.string.widget_all_servers),
            staleLabel = content.staleAt?.let { res(R.string.widget_updated_at, timeOfDay(it)) },
            footerLine = waitingText(view.pending),
            footerLineShort = res(R.string.widget_waiting_short, view.pending),
            stats = listOf(view.pending.toString() to res(R.string.widget_figure_waiting), (oldest ?: "–") to res(R.string.widget_figure_oldest)),
            figure = view.pending.toString(), figureCaption = res(R.string.widget_requests), figureLive = view.pending > 0,
            card = { row, tier, width, height -> RequestCard(row, tier, width, height, env) },
            topCard = { row, width -> RequestCard(row, tier = 1, width = width, height = 40.dp, env = env, buttons = false) },
            notice = view.failedAction?.let { res(if (it.approve) R.string.widget_approve_failed else R.string.widget_decline_failed) },
            privacyHidden = view.detailsHidden,
        ),
        env,
    )
}

@Composable
private fun RequestCard(row: RequestRowView, tier: Int, width: Dp, height: Dp, env: WidgetEnv, buttons: Boolean = true) {
    val context = LocalContext.current
    val meta = listOfNotNull(row.who, row.ageMillis?.let { formatAge(context, it) }).joinToString(" · ")
    RowCard(GlanceModifier.fillMaxWidth().height(height), env.open(row.link)) {
        when {
            tier == 0 && buttons -> Column(cardPadding()) {
                CardText(row.title, null, line = meta.ifEmpty { null }, width = width, titleStyle = CardTitleStyle)
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth().height(28.dp)) {
                    DecideButton(true, row, env, labelled = true, modifier = GlanceModifier.defaultWeight())
                    SpacerW(8)
                    DecideButton(false, row, env, labelled = true, modifier = GlanceModifier.defaultWeight())
                }
            }
            tier == 2 && buttons -> Column(cardPadding()) {
                CardText(row.title, null, line = meta.ifEmpty { null }, width = width)
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth().height(28.dp)) {
                    DecideButton(true, row, env, labelled = false, modifier = GlanceModifier.defaultWeight())
                    SpacerW(8)
                    DecideButton(false, row, env, labelled = false, modifier = GlanceModifier.defaultWeight())
                }
            }
            else -> Row(cardPadding(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(row.title, style = RowTitleStyle, maxLines = 1)
                    if (meta.isNotEmpty()) Text(meta, style = CaptionStyle, maxLines = 1)
                }
                if (buttons) {
                    SpacerW(6)
                    DecideButton(true, row, env, labelled = false, modifier = GlanceModifier.width(32.dp).height(28.dp))
                    SpacerW(6)
                    DecideButton(false, row, env, labelled = false, modifier = GlanceModifier.width(32.dp).height(28.dp))
                }
            }
        }
    }
}

/** "1 request waiting", "4 requests waiting". */
@Composable
internal fun waitingText(count: Int): String = LocalContext.current.resources.getQuantityString(R.plurals.widget_requests_waiting, count, count)

/**
 * Approve (the app's outlined button: an accent edge and accent text) and Decline (a raised fill in the text color). The widget is only
 * reachable on an unlocked phone: a tap acts, and the next read shows the result.
 */
@Composable
private fun DecideButton(approve: Boolean, row: RequestRowView, env: WidgetEnv, labelled: Boolean, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val action = env.decide(row.serverId, row.requestId, approve)
    val description = context.getString(if (approve) R.string.widget_approve_description else R.string.widget_decline_description, row.title)
    val sized = modifier.fillMaxHeight()
    val icon = if (approve) R.drawable.lucide_check else R.drawable.lucide_x
    val tint = if (approve) Tokens.Accent else Tokens.Text
    val content: @Composable () -> Unit = {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.CenterVertically, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Lucide(icon, tint = tint, size = 14, description = description)
            if (labelled) {
                SpacerW(6)
                Text(context.getString(if (approve) R.string.widget_approve else R.string.widget_decline), style = RowTitleStyle.copy(color = tint), maxLines = 1)
            }
        }
    }
    if (approve) {
        Box(sized.background(Tokens.Accent).cornerRadius(10.dp).padding(1.dp).clickable(action)) {
            Box(GlanceModifier.fillMaxSize().background(Tokens.Card).cornerRadius(9.dp)) { content() }
        }
    } else {
        Box(sized.background(Tokens.Raised).cornerRadius(10.dp).clickable(action)) { content() }
    }
}

// ---- Libraries and scans: the app's library rows ----------------------------------------------------------------------------

internal val LibrariesWording = Wording(
    icon = R.drawable.lucide_library,
    title = R.string.widget_libraries_label,
    signIn = R.string.widget_libraries_sign_in,
    unsupported = R.string.widget_libraries_unsupported,
    unsupportedAll = R.string.widget_libraries_unsupported_all,
    empty = R.string.widget_libraries_empty,
)

private val LibraryTiers = listOf(
    TierSpec(height = 41f, minWidth = 0f), // name and scan state, when it was scanned; a refresh under way adds its bar as the card's lower edge
)

@Composable
internal fun LibrariesContent(state: WidgetState<LibrariesView>, env: WidgetEnv, openApp: Action) {
    if (StateBody(state, LibrariesWording, env, openApp) { last, _ -> Text(LocalContext.current.getString(R.string.widget_libraries_summary_total, last.total, last.scanning), style = MutedStyle, maxLines = 1) }) return
    val content = state as WidgetState.Content<LibrariesView>
    val view = content.data
    ListWidget(
        ListSpec(
            icon = R.drawable.lucide_library, title = res(R.string.widget_libraries_label), tiers = LibraryTiers, rows = view.rows, open = env.open(view.link),
            live = view.scanning > 0,
            status = if (view.scanning > 0) res(R.string.widget_scanning_count, view.scanning) else res(R.string.widget_libraries_count, view.total),
            trailing = view.serverName ?: res(R.string.widget_all_servers),
            staleLabel = content.staleAt?.let { res(R.string.widget_updated_at, timeOfDay(it)) },
            footerLine = res(R.string.widget_libraries_summary, view.scanning, view.withWarnings),
            footerLineShort = res(R.string.widget_libraries_count, view.total),
            stats = listOf(
                view.total.toString() to res(R.string.widget_figure_libraries), view.scanning.toString() to res(R.string.widget_figure_scanning),
                view.withWarnings.toString() to res(R.string.widget_figure_warnings),
            ),
            figure = view.total.toString(), figureCaption = res(R.string.widget_libraries_caption), figureLive = view.scanning > 0,
            card = { row, tier, width, height -> LibraryCard(row, tier, width, height, env) },
            topCard = { row, width -> LibraryCard(row, tier = 1, width = width, height = 40.dp, env = env) },
            privacyHidden = view.detailsHidden,
        ),
        env,
    )
}

@Composable
private fun LibraryCard(row: LibraryRowView, tier: Int, width: Dp, height: Dp, env: WidgetEnv) {
    val context = LocalContext.current
    val scan = scanState(row)
    val last = row.lastScannedAt?.let { context.getString(R.string.widget_scanned_ago, formatAge(context, (env.nowMillis - it).coerceAtLeast(0))) } ?: context.getString(R.string.widget_scan_never)
    val percent = row.scan?.percent
    RowCard(GlanceModifier.fillMaxWidth().height(height), env.open(row.link)) {
        Column(cardPadding(), verticalAlignment = if (percent == null) Alignment.Vertical.CenterVertically else Alignment.Vertical.Top) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(row.name, style = RowTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                when {
                    scan != null && width >= 190.dp -> { SpacerW(6); ScanMark(scan.first, scan.second) }
                    scan != null -> { SpacerW(6); TallyDot() }
                    row.warning -> { SpacerW(6); Lucide(R.drawable.lucide_triangle_alert, tint = Tokens.Warning, size = 14, description = res(R.string.widget_scan_warning)) }
                }
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                if (row.warning && scan != null) { Lucide(R.drawable.lucide_triangle_alert, tint = Tokens.Warning, size = 12, description = res(R.string.widget_scan_warning)); SpacerW(4) }
                Text(last, style = CaptionStyle, maxLines = 1)
            }
            // The bar of a refresh that reports its progress; a scan the server does not measure shows only its word.
            if (percent != null) {
                Spacer(GlanceModifier.defaultWeight())
                BarTrack(percent / 100f, width - 2.dp - 20.dp, 3.dp)
            }
        }
    }
}

/** What a library is doing now, as the word and whether it is live: "Scanning", "Scan queued", "Refreshing 40%". Null when idle. */
@Composable
private fun scanState(row: LibraryRowView): Pair<String, Boolean>? {
    val scan = row.scan ?: return null
    return when (scan.state) {
        "queued" -> res(R.string.widget_scan_queued) to false
        "refreshing" -> (scan.percent?.let { res(R.string.widget_scan_refreshing_percent, it) } ?: res(R.string.widget_scan_refreshing)) to true
        else -> res(R.string.widget_scan_running) to true
    }
}

@Composable
private fun ScanMark(word: String, live: Boolean) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (live) { TallyDot(); SpacerW(4) }
        Text(word, style = CaptionStyle, maxLines = 1)
    }
}

// ---- Nodes and resources: the app's node cards ---------------------------------------------------------------------------------

internal val NodesWording = Wording(
    icon = R.drawable.lucide_cpu,
    title = R.string.widget_nodes_label,
    signIn = R.string.widget_nodes_sign_in,
    unsupported = R.string.widget_nodes_unsupported,
    unsupportedAll = R.string.widget_nodes_unsupported_all,
    empty = R.string.widget_nodes_empty,
)

private val NodeTiers = listOf(
    TierSpec(height = 84f, minWidth = 250f), // name and state, then CPU, memory and disk as three bars
    TierSpec(height = 41f, minWidth = 190f), // name and state, then the three figures in a line
    TierSpec(height = 56f, minWidth = 0f), // narrow: the state is its dot, and the figures take two lines
)

@Composable
internal fun NodesContent(state: WidgetState<NodesView>, env: WidgetEnv, openApp: Action) {
    if (StateBody(state, NodesWording, env, openApp) { last, _ -> Text(LocalContext.current.getString(R.string.widget_nodes_summary_total, last.total, last.needAttention), style = MutedStyle, maxLines = 1) }) return
    val content = state as WidgetState.Content<NodesView>
    val view = content.data
    ListWidget(
        ListSpec(
            icon = R.drawable.lucide_cpu, title = res(R.string.widget_nodes_label), tiers = NodeTiers, rows = view.rows, open = env.open(view.link),
            live = view.needAttention > 0,
            status = if (view.needAttention > 0) res(R.string.widget_nodes_attention, view.needAttention) else res(R.string.widget_nodes_count, view.total),
            trailing = view.serverName ?: res(R.string.widget_all_servers),
            staleLabel = content.staleAt?.let { res(R.string.widget_updated_at, timeOfDay(it)) },
            footerLine = res(R.string.widget_nodes_summary_total, view.total, view.needAttention),
            footerLineShort = if (view.needAttention > 0) res(R.string.widget_nodes_attention, view.needAttention) else res(R.string.widget_nodes_count, view.total),
            stats = listOf(view.total.toString() to res(R.string.widget_figure_nodes), view.needAttention.toString() to res(R.string.widget_figure_attention)),
            figure = view.total.toString(), figureCaption = if (view.needAttention > 0) res(R.string.widget_nodes_attention, view.needAttention) else res(R.string.widget_nodes_caption),
            figureLive = view.needAttention > 0,
            card = { row, tier, width, height -> NodeCard(row, tier, width, height, env) },
            topCard = { row, width -> NodeCard(row, tier = 1, width = width, height = 40.dp, env = env) },
            privacyHidden = view.detailsHidden,
        ),
        env,
    )
}

@Composable
private fun nodeStateMark(state: String): Pair<androidx.glance.unit.ColorProvider, String> = when (state) {
    "healthy" -> Tokens.Ok to res(R.string.nodes_state_healthy)
    "draining" -> Tokens.Warning to res(R.string.nodes_state_draining)
    "drained" -> Tokens.Muted to res(R.string.nodes_state_drained)
    "unhealthy" -> Tokens.Danger to res(R.string.nodes_state_unhealthy)
    else -> Tokens.Muted to res(R.string.nodes_state_disabled)
}

@Composable
private fun NodeCard(row: NodeRowView, tier: Int, width: Dp, height: Dp, env: WidgetEnv) {
    val (color, word) = nodeStateMark(row.state)
    val load = listOf(
        R.string.nodes_cpu to row.cpuPercent, R.string.nodes_memory to row.memoryPercent, R.string.nodes_disk to row.diskPercent,
    ).mapNotNull { (label, percent) -> percent?.let { "${res(label)} ${res(R.string.nodes_percent, it)}" } }
    RowCard(GlanceModifier.fillMaxWidth().height(height), env.open(row.link)) {
        Column(cardPadding(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(row.name, style = if (tier == 0) CardTitleStyle else RowTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                SpacerW(6)
                // A narrow card gives the name the room and shows the state as its dot, with the word in the description.
                if (tier == 2 || width < 190.dp) StatusDot(color) else StateMark(color, word)
            }
            when {
                load.isEmpty() && tier != 2 -> Text(res(R.string.nodes_no_reading), style = CaptionStyle, maxLines = 1)
                tier == 0 -> {
                    Spacer(GlanceModifier.defaultWeight())
                    listOf(R.string.nodes_cpu to row.cpuPercent, R.string.nodes_memory to row.memoryPercent, R.string.nodes_disk to row.diskPercent)
                        .forEach { (label, percent) -> if (percent != null) LoadBar(res(label), percent, width - 2.dp - 20.dp) }
                }
                tier == 2 -> {
                    // The word is only added where the color could be missed: a node that is not healthy.
                    val tail = word.takeIf { row.state != "healthy" }
                    if (load.isEmpty()) {
                        Text(res(R.string.nodes_no_reading), style = CaptionStyle, maxLines = 1)
                        Text(word, style = CaptionStyle.copy(color = color), maxLines = 1)
                    } else {
                        Text(load.take(2).joinToString(" · "), style = CaptionStyle, maxLines = 1)
                        val last = listOfNotNull(load.getOrNull(2), tail).joinToString(" · ")
                        if (last.isNotEmpty()) Text(last, style = CaptionStyle, maxLines = 1)
                    }
                }
                else -> Text(load.joinToString(" · "), style = CaptionStyle, maxLines = 1)
            }
        }
    }
}

/** One load figure as the node page shows it: the name, a bar and the percentage. */
@Composable
private fun LoadBar(label: String, percent: Int, width: Dp) {
    val labelWidth = 56.dp
    val valueWidth = 36.dp
    Row(GlanceModifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(label, style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.width(labelWidth))
        BarTrack(percent / 100f, (width - labelWidth - valueWidth).coerceAtLeast(20.dp), 4.dp)
        Text(res(R.string.nodes_percent, percent), style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.width(valueWidth).padding(start = 6.dp))
    }
}
