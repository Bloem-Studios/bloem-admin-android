package com.bloemstudios.admin.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
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
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.bloemstudios.admin.R

// The shared frame of the list widgets (Requests, Libraries, Nodes, Server status): the page, a header, whole cards that follow the
// height, a footer of totals, and the small layout. Now playing has its own cards but uses the same pieces.

/** One widget's own parts, so the frame can lay them out. */
internal class ListSpec<R>(
    val icon: Int,
    val title: String,
    /** The tiers, richest first, as the planner reads them. */
    val tiers: List<TierSpec>,
    val rows: List<R>,
    /** Opens the widget's page in the app. */
    val open: Action,
    /** The header: a live mark and its words ("3 waiting"), the server, and the time when the data is old. */
    val live: Boolean,
    val status: String?,
    val trailing: String?,
    val staleLabel: String?,
    /** The footer: one line of totals (null: none), and the figures for the stats card (empty: none). */
    val footerLine: String?,
    /** The footer line on a narrow widget, where the whole line would be cut. */
    val footerLineShort: String? = null,
    val stats: List<Pair<String, String>>,
    /** The small layout: the figure, what it counts, and whether it is live. */
    val figure: String,
    val figureCaption: String,
    val figureLive: Boolean,
    /** Draws one card in tier [tier] (an index into [tiers]), [width] wide and [height] tall. */
    val card: @Composable (row: R, tier: Int, width: Dp, height: Dp) -> Unit,
    /** The small layout's one card for the top row, or null. */
    val topCard: (@Composable (row: R, width: Dp) -> Unit)? = null,
    /** A line under the header (a failed button). */
    val notice: String? = null,
    val privacyHidden: Boolean = false,
    /** True when the stats card makes sense at this size (not for a list whose footer is only a line). */
    val statsFooter: Boolean = true,
)

@Composable
internal fun <R> ListWidget(spec: ListSpec<R>, env: WidgetEnv) {
    val size = LocalSize.current
    val plan = ListPlanner.plan(size.width.value, size.height.value, spec.rows.size, spec.tiers, fontScale(), stats = spec.statsFooter && spec.stats.isNotEmpty())
    WidgetPage(spec.open) {
        when {
            spec.privacyHidden && size.width >= 200.dp -> CountsPage(spec, plan, size, env)
            plan.small || spec.privacyHidden -> SmallPage(spec, size, env)
            else -> CardsPage(spec, plan, size, env)
        }
    }
}

@Composable
private fun <R> ColumnScope.CardsPage(spec: ListSpec<R>, plan: ListPlan, size: DpSize, env: WidgetEnv) {
    if (plan.header) {
        PageHeader(spec.icon, spec.title, env, size.width, live = spec.live, status = spec.status, trailing = spec.trailing, staleLabel = spec.staleLabel, slim = plan.slimHeader)
        SpacerH(if (plan.slimHeader) ListPlanner.SLIM_GAP.toInt() else Page.GAP)
    }
    val innerWidth = size.width - (Page.PAD_H * 2).dp
    val cardHeight = plan.cardHeight.dp
    spec.notice?.let { Text(it, style = CaptionStyle.copy(color = Tokens.Danger), maxLines = 1, modifier = GlanceModifier.padding(horizontal = 2.dp)); SpacerH(2) }
    if (plan.scrolls && WidgetRendering.lazyLists) {
        LazyColumn(GlanceModifier.fillMaxWidth().height(((plan.cardHeight + Page.GAP) * plan.visible).dp)) {
            itemsIndexed(spec.rows) { _, row ->
                Column(GlanceModifier.fillMaxWidth()) {
                    spec.card(row, plan.tier, innerWidth, cardHeight)
                    SpacerH(Page.GAP)
                }
            }
        }
    } else {
        val listModifier = if (plan.scrolls) GlanceModifier.fillMaxWidth().height(plan.listHeight().dp) else GlanceModifier.fillMaxWidth()
        Column(listModifier) {
            spec.rows.forEachIndexed { index, row ->
                if (index > 0) SpacerH(Page.GAP)
                spec.card(row, plan.tier, innerWidth, cardHeight)
            }
        }
    }
    if (plan.footer != NpFooter.None) {
        Spacer(GlanceModifier.defaultWeight())
        when (plan.footer) {
            NpFooter.Line -> (if (size.width < 230.dp) spec.footerLineShort ?: spec.footerLine else spec.footerLine)
                ?.let { Text(it, style = CaptionStyle, maxLines = 1, modifier = GlanceModifier.padding(horizontal = 2.dp)) }
            NpFooter.Stats -> StatsCard(spec.stats, plan.footerHeight, spec.open)
            NpFooter.None -> Unit
        }
    }
}

/** The lock screen's wide layout: the header, and the figures in one card that takes the rest of the height. */
@Composable
private fun <R> ColumnScope.CountsPage(spec: ListSpec<R>, plan: ListPlan, size: DpSize, env: WidgetEnv) {
    val slim = size.height < 150.dp
    PageHeader(spec.icon, spec.title, env, size.width, live = spec.live, status = null, trailing = spec.trailing, staleLabel = spec.staleLabel, slim = slim)
    SpacerH(if (slim) ListPlanner.SLIM_GAP.toInt() else Page.GAP)
    StatsCard(spec.stats, null, spec.open, GlanceModifier.defaultWeight())
}

/** The small layout: the mark and what it counts, the figure, and the top row's card. */
@Composable
private fun <R> ColumnScope.SmallPage(spec: ListSpec<R>, size: DpSize, env: WidgetEnv) {
    SmallHeader(spec.title, spec.figureLive, env, size.width)
    Column(GlanceModifier.padding(start = 2.dp)) {
        Text(spec.figure, style = FigureStyle, maxLines = 1)
        Text(spec.figureCaption, style = CaptionStyle, maxLines = 1)
        spec.staleLabel?.let { Text(it, style = CaptionStyle, maxLines = 1) }
    }
    Spacer(GlanceModifier.defaultWeight())
    val top = spec.rows.firstOrNull()
    val topCard = spec.topCard
    if (top != null && topCard != null && !spec.privacyHidden) {
        topCard(top, size.width - (Page.PAD_H * 2).dp)
        SpacerH(4)
    }
}

/** Figures in a card: each a number and what it counts. [height] null fills the space the caller gives through [modifier]. */
@Composable
internal fun StatsCard(stats: List<Pair<String, String>>, height: Float?, open: Action, modifier: GlanceModifier = GlanceModifier) {
    val sized = if (height != null) modifier.fillMaxWidth().height(height.dp) else modifier.fillMaxWidth()
    RowCard(sized, open) {
        val big = (height ?: 90f) >= 84f
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            stats.forEach { (value, label) ->
                Column(GlanceModifier.defaultWeight()) {
                    Text(value, style = if (big) FigureStyle else FigureMediumStyle, maxLines = 1)
                    Text(label, style = CaptionStyle, maxLines = 1)
                }
            }
        }
    }
}

/** A card's text: the title (and what sits at its right) over one muted line, in a column that fills the card. */
@Composable
internal fun CardText(
    title: String, right: String?, rightColor: ColorProvider = Tokens.Muted, line: String?, width: Dp,
    titleStyle: androidx.glance.text.TextStyle = RowTitleStyle,
) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(title, style = titleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (right != null && width >= 190.dp) {
            SpacerW(6)
            Text(right, style = CaptionStyle.copy(color = rightColor), maxLines = 1)
        }
    }
    if (line != null) Text(line, style = CaptionStyle, maxLines = 1)
}

/** A state's dot and word: a node or server. */
@Composable
internal fun StateMark(color: ColorProvider, word: String) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        StatusDot(color)
        SpacerW(4)
        Text(word, style = CaptionStyle, maxLines = 1)
    }
}

/** The padding every list card keeps inside its ring. */
internal fun cardPadding(): GlanceModifier = GlanceModifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 4.dp)
