package com.bloemstudios.admin.widget

import kotlin.math.floor
import kotlin.math.min

/** What a footer under the cards says: nothing, one line of totals, or a card of figures (which takes the room that is left, up to [MAX_STATS]). */
enum class NpFooter(val height: Float) {
    None(0f), Line(18f), Stats(64f);

    companion object {
        const val MAX_STATS = 96f
    }
}

/** A card height and the least width at which that much detail is drawn. Specs are listed richest first. */
data class TierSpec(val height: Float, val minWidth: Float)

/**
 * Where everything goes for one size. Every list widget is drawn at the exact size the launcher gives it (`SizeMode.Exact`), and this
 * is the one rule that turns that size into a layout:
 *  - the number of cards follows the height (one, two, three, then a scrolling list);
 *  - the detail in a card follows the width (each tier has the least width it is drawn at);
 *  - whatever height is left after whole cards goes to the cards and a footer of totals, never an empty band.
 */
data class ListPlan(
    /** Count and the top row only. */
    val small: Boolean,
    val header: Boolean,
    /** The header on a short widget: the icon, what is live, the server and Refresh, with no title, to leave room for two cards. */
    val slimHeader: Boolean,
    /** Index into the tier specs. */
    val tier: Int,
    /** Whole cards the list holds at once; more than that scrolls. */
    val visible: Int,
    val cardHeight: Float,
    val footer: NpFooter,
    /** The footer's height: the stats card grows to use the room left, so a short list on a tall widget leaves no band. */
    val footerHeight: Float,
    val scrolls: Boolean,
) {
    /** The height of the list itself. */
    fun listHeight(gap: Float = Page.GAP.toFloat()): Float = if (visible == 0) 0f else visible * cardHeight + (visible - 1) * gap
}

object ListPlanner {
    private const val GAP = Page.GAP.toFloat()
    private const val PAGE_V = (Page.PAD_V * 2).toFloat()

    /** The slim header's height and the gap under it. */
    const val SLIM_HEADER = 16f
    const val SLIM_GAP = 2f

    /** The most a card grows to use up the height that is left; beyond it the footer takes the room. */
    private const val MAX_STRETCH = 12f
    private const val MAX_STRETCH_FEW = 20f

    /** Only part of a card is text, so only part of it grows with the person's font size. */
    fun scaled(dp: Float, fontScale: Float) = dp * (1f + 0.7f * (fontScale - 1f))

    fun plan(widthDp: Float, heightDp: Float, rows: Int, tiers: List<TierSpec>, fontScale: Float = 1f, stats: Boolean = true): ListPlan {
        val small = widthDp < 200f && heightDp < 200f
        if (small) return ListPlan(true, false, false, tiers.lastIndex, 0, 0f, NpFooter.None, 0f, false)

        val header = heightDp >= 96f
        val slim = header && heightDp < 150f
        val headerHeight = when {
            !header -> 0f
            slim -> scaled(SLIM_HEADER, fontScale) + SLIM_GAP
            else -> scaled(Page.HEADER.toFloat(), fontScale) + GAP
        }
        val avail = heightDp - PAGE_V - headerHeight
        val minWanted = when {
            rows == 0 -> 0
            heightDp >= 200f -> min(rows, 3)
            heightDp >= 96f -> min(rows, 2)
            else -> 1
        }
        val allowed = tiers.indices.filter { widthDp >= tiers[it].minWidth || it == tiers.lastIndex }

        fun height(index: Int) = scaled(tiers[index].height, fontScale)
        fun fit(index: Int) = floor((avail + GAP) / (height(index) + GAP)).toInt()

        val tier = allowed.firstOrNull { fit(it) >= minWanted } ?: tiers.lastIndex
        val visible = maxOf(if (rows > 0) 1 else 0, min(rows, fit(tier)))
        val base = height(tier)
        val used = if (visible == 0) 0f else visible * base + (visible - 1) * GAP
        val leftover = (avail - used).coerceAtLeast(0f)

        val tall = heightDp >= 200f && rows > 0
        val footer = when {
            stats && tall && widthDp >= 200f && leftover >= NpFooter.Stats.height + GAP -> NpFooter.Stats
            tall && leftover >= scaled(NpFooter.Line.height, fontScale) + GAP -> NpFooter.Line
            else -> NpFooter.None
        }
        val footerHeight = when (footer) {
            NpFooter.None -> 0f
            NpFooter.Line -> scaled(NpFooter.Line.height, fontScale)
            NpFooter.Stats -> min(leftover - GAP, NpFooter.MAX_STATS)
        }
        val afterFooter = (leftover - if (footer == NpFooter.None) 0f else footerHeight + GAP).coerceAtLeast(0f)
        val cap = if (visible <= 2 || (tier == 0 && tiers.size > 2)) MAX_STRETCH_FEW else MAX_STRETCH
        val stretch = if (visible == 0) 0f else min(afterFooter / visible, cap)
        return ListPlan(
            small = false, header = header, slimHeader = slim, tier = tier, visible = visible, cardHeight = base + stretch,
            footer = footer, footerHeight = footerHeight, scrolls = rows > visible,
        )
    }
}

/** How much of the app's session card a widget card carries. Each tier is a fixed height so a list of them is exact. */
enum class NpTier(val baseHeight: Float, val minWidth: Float) {
    /** The app's whole card: title, who, Source to Playback to Client (with the transcode detail), the bar, the time and bitrate. */
    Full(116f, 280f),

    /** Title and status, the method and who with the bitrate, the bar, then the time and the time left. Three fit a 4 x 4 widget. */
    Roomy(70f, 140f),

    /** Title, then the method and who, with the progress bar as the card's lower edge. Two of these and a slim header fit a 4 x 2 widget. */
    Compact(41f, 0f);

    val spec: TierSpec get() = TierSpec(baseHeight, minWidth)
}

/** [ListPlan] for Now playing, with the tier named. */
data class NpPlan(
    val small: Boolean,
    val header: Boolean,
    val slimHeader: Boolean,
    val tier: NpTier,
    val visible: Int,
    val cardHeight: Float,
    val footer: NpFooter,
    val footerHeight: Float,
    val scrolls: Boolean,
    val showBitrate: Boolean,
) {
    fun listHeight(gap: Float = Page.GAP.toFloat()): Float = if (visible == 0) 0f else visible * cardHeight + (visible - 1) * gap
}

object NowPlayingPlanner {
    const val SLIM_HEADER = ListPlanner.SLIM_HEADER
    const val SLIM_GAP = ListPlanner.SLIM_GAP

    fun plan(widthDp: Float, heightDp: Float, rows: Int, fontScale: Float = 1f): NpPlan {
        val tiers = NpTier.entries
        val plan = ListPlanner.plan(widthDp, heightDp, rows, tiers.map { it.spec }, fontScale)
        return NpPlan(
            small = plan.small, header = plan.header, slimHeader = plan.slimHeader, tier = tiers[plan.tier], visible = plan.visible,
            cardHeight = plan.cardHeight, footer = plan.footer, footerHeight = plan.footerHeight, scrolls = plan.scrolls,
            showBitrate = widthDp >= 220f,
        )
    }
}
