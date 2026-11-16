package com.bloemstudios.admin.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilPalette
import java.text.DateFormat
import java.util.Date

/** The three layouts every widget has: small, medium and large. */
enum class WidgetSize(val size: DpSize) {
    Small(DpSize(110.dp, 110.dp)),
    // The size a launcher must give for the layout to be used. Medium is 110 dp tall because Responsive picks the largest layout
    // that fits: a 4 x 2 widget on a launcher that gives it 112 dp must still get Medium and not fall back to Small.
    Medium(DpSize(250.dp, 110.dp)),
    Large(DpSize(250.dp, 270.dp));

    companion object {
        /** All three, for `SizeMode.Responsive`. */
        val all: Set<DpSize> = entries.map { it.size }.toSet()

        /** Only medium and large, for the widgets that have no small layout. */
        val mediumAndLarge: Set<DpSize> = setOf(Medium.size, Large.size)

        fun of(size: DpSize): WidgetSize = when {
            size.width < 200.dp -> Small
            size.height < 200.dp -> Medium
            else -> Large
        }
    }
}

/** What a widget needs from outside to draw and act. The real one starts activities and callbacks; the screenshot harness has no-ops. */
interface WidgetEnv {
    val nowMillis: Long
    fun open(link: String): Action
    fun refresh(): Action
    fun decide(serverId: String, requestId: String, approve: Boolean): Action
}

/**
 * The app's Appearance setting as the widgets see it. System follows the launcher's light or dark setting; Light and Dark are
 * the app's own choice. A widget is drawn by the launcher, so this is set when the widget reads its data and a change of the
 * setting redraws every widget (see WidgetSync).
 */
object WidgetAppearance {
    @Volatile
    var mode: com.bloemstudios.admin.ui.theme.ThemeMode = com.bloemstudios.admin.ui.theme.ThemeMode.System
}

/** The palette's tokens as Glance colors, following [WidgetAppearance]. */
internal object Tokens {
    internal fun token(day: Color, night: Color, mode: com.bloemstudios.admin.ui.theme.ThemeMode = WidgetAppearance.mode): ColorProvider = when (mode) {
        com.bloemstudios.admin.ui.theme.ThemeMode.Light -> ColorProvider(day)
        com.bloemstudios.admin.ui.theme.ThemeMode.Dark -> ColorProvider(night)
        com.bloemstudios.admin.ui.theme.ThemeMode.System -> ColorProvider(day = day, night = night)
    }

    val Canvas get() = token(VeilPalette.Light.canvas, VeilPalette.Dark.canvas)
    val Card get() = token(VeilPalette.Light.card, VeilPalette.Dark.card)
    val Raised get() = token(VeilPalette.Light.raised, VeilPalette.Dark.raised)
    val Outline get() = token(VeilPalette.Light.outline, VeilPalette.Dark.outline)
    /** The chart's fixed colors, which mark how a stream is delivered. Same in both schemes. */
    fun chart(index: Int): ColorProvider = token(VeilPalette.Light.chart[index], VeilPalette.Dark.chart[index])
    val Hairline get() = token(VeilPalette.Light.hairline, VeilPalette.Dark.hairline)
    val Text get() = token(VeilPalette.Light.text, VeilPalette.Dark.text)
    val Muted get() = token(VeilPalette.Light.muted, VeilPalette.Dark.muted)
    val Accent get() = token(VeilPalette.Light.accent, VeilPalette.Dark.accent)
    val Tally get() = token(VeilPalette.Light.tally, VeilPalette.Dark.tally)
    val Ok get() = token(VeilPalette.Light.ok, VeilPalette.Dark.ok)
    val Warning get() = token(VeilPalette.Light.warning, VeilPalette.Dark.warning)
    val Danger get() = token(VeilPalette.Light.danger, VeilPalette.Dark.danger)
}

// The type scale at widget sizes. Glance draws with the system sans (it cannot bundle a font) at the spec's sizes and
// weights; it has no 600 weight (Medium is 500) and no tabular figures.
internal val FigureStyle get() = TextStyle(color = Tokens.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
internal val FigureMediumStyle get() = TextStyle(color = Tokens.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
internal val TitleStyle get() = TextStyle(color = Tokens.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
internal val RowTitleStyle get() = TextStyle(color = Tokens.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
internal val BodyStyle get() = TextStyle(color = Tokens.Text, fontSize = 12.sp)
internal val MutedStyle get() = TextStyle(color = Tokens.Muted, fontSize = 12.sp)
internal val CaptionStyle get() = TextStyle(color = Tokens.Muted, fontSize = 11.sp)
internal val LabelStyle get() = TextStyle(color = Tokens.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
internal val CardTitleStyle get() = TextStyle(color = Tokens.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
internal val ValueStyle get() = TextStyle(color = Tokens.Text, fontSize = 11.sp)

@Composable
internal fun res(id: Int, vararg args: Any): String = LocalContext.current.getString(id, *args)

// ---- The look of the app's own pages: the canvas, with cards on it ----------------------------------------------------------

/** The page's padding, and the room a header and a gap take. Every layout computes how many rows fit from these. */
internal object Page {
    const val PAD_H = 8
    const val PAD_V = 4
    const val HEADER = 18
    const val GAP = 4
}

/** The text scale the person chose. Row heights that hold text grow with it, so a line is never cut off. */
@Composable
internal fun fontScale(): Float = LocalContext.current.resources.configuration.fontScale.coerceAtLeast(1f)

/** The widget's page: the app's `canvas`, rounded, with its cards on it, the way the app's own pages draw. Tapping anywhere else opens [open]. */
@Composable
internal fun WidgetPage(open: Action?, content: @Composable ColumnScope.() -> Unit) {
    val base = GlanceModifier.fillMaxSize().appWidgetBackground().background(Tokens.Canvas).cornerRadius(16.dp)
        .padding(horizontal = Page.PAD_H.dp, vertical = Page.PAD_V.dp)
    Column(modifier = if (open != null) base.clickable(open) else base, content = content)
}

/**
 * A card as the app draws one: the `card` surface, a hairline edge, 12 dp corners. The edge is a ring: a hairline-colored box with
 * the card box inset by 1 dp inside it, because a widget has no border. [modifier] sets the size; the content is laid out inside.
 */
@Composable
internal fun RowCard(modifier: GlanceModifier, action: Action?, content: @Composable () -> Unit) {
    val ring = modifier.background(Tokens.Hairline).cornerRadius(12.dp).padding(1.dp)
    Box(if (action != null) ring.clickable(action) else ring) {
        Box(GlanceModifier.fillMaxSize().background(Tokens.Card).cornerRadius(11.dp)) { content() }
    }
}

/** A bar of exact length: the app's progress bar (track `raised`, fill in text color, amber when paused), drawn from boxes so its length is a dp value. */
@Composable
internal fun BarTrack(fraction: Float?, width: Dp, height: Dp = 4.dp, fill: ColorProvider = Tokens.Text) {
    val radius = height / 2
    Box(GlanceModifier.width(width).height(height).background(Tokens.Raised).cornerRadius(radius)) {
        val shown = (fraction ?: 0f).coerceIn(0f, 1f)
        if (shown > 0f) Box(GlanceModifier.width((width * shown).coerceAtLeast(height)).height(height).background(fill).cornerRadius(radius)) {}
    }
}

/**
 * Which app a widget belongs to (docs/design.md, "Widgets"): the app's own mark, and its home-screen name
 * ("Bloem Admin") beside it where there is room, before the widget's own title. Small widgets show the mark alone. It is identification,
 * not content, so it is drawn in `muted` and never takes the accent or the tally dot.
 */
object AppTagRules {
    /** The mark's size: 14 to 16 dp. The slim header is only 16 tall, so it uses the small end. */
    const val MARK_DP = 16
    const val MARK_SLIM_DP = 14

    /** The name sits beside the mark from this width; below it the mark stands alone. */
    const val NAME_FROM_DP = 260f

    /** The server's name, the least important thing in the header, needs room for the tag as well. */
    const val SERVER_FROM_DP = 360f

    fun showsName(widthDp: Float) = widthDp >= NAME_FROM_DP
    fun showsServer(widthDp: Float) = widthDp >= SERVER_FROM_DP
}

/**
 * The app tag: the mark, and "Bloem Admin ·" when the width allows. The title follows it. It is one nested row, because Glance keeps at
 * most ten children in a row and a header with the tag spread across it lost its Refresh button.
 */
@Composable
internal fun AppTag(width: Dp, slim: Boolean = false, separator: Boolean = true) {
    val size = if (slim) AppTagRules.MARK_SLIM_DP else AppTagRules.MARK_DP
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Image(
            ImageProvider(R.drawable.widget_app_mark), res(R.string.app_name), GlanceModifier.size(size.dp),
            colorFilter = ColorFilter.tint(Tokens.Muted),
        )
        if (AppTagRules.showsName(width.value)) {
            Text(
                " " + res(R.string.app_name) + if (separator) " ·" else "", style = LabelStyle, maxLines = 1,
                modifier = GlanceModifier.padding(start = 3.dp, end = 4.dp),
            )
        } else {
            SpacerW(6)
        }
    }
}

/** The small layout's top row: the mark alone (top-left), what is live and what this is, and Refresh. */
@Composable
internal fun SmallHeader(title: String, live: Boolean, env: WidgetEnv, width: Dp) {
    Row(GlanceModifier.fillMaxWidth().height((20 * fontScale()).dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
        SpacerW(2)
        AppTag(0.dp, slim = false) // small: the mark alone
        if (live) { TallyDot(); SpacerW(4) }
        Text(title, style = RowTitleStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (width >= 120.dp) Box(GlanceModifier.size(width = 32.dp, height = 20.dp).clickable(env.refresh()), contentAlignment = Alignment.Center) {
            Lucide(R.drawable.lucide_rotate_cw, size = 14, description = res(R.string.widget_refresh))
        }
    }
}

/**
 * The header of a page-style widget: icon and title, what is live, the server (or "Updated 14:32" when the data is old), and Refresh.
 * What does not fit is dropped from the right: the server first, then the live count.
 */
@Composable
internal fun PageHeader(
    icon: Int, title: String, env: WidgetEnv, width: Dp,
    live: Boolean = false, status: String? = null, trailing: String? = null, showRefresh: Boolean = true,
    /** "Updated 14:32", when the data is older than its window. It always shows: on a narrow header it takes the title's place. */
    staleLabel: String? = null,
    /** The short widget's header: no title, only the icon, what is live, the server and Refresh. */
    slim: Boolean = false,
) {
    val scale = fontScale()
    val rowHeight = ((if (slim) NowPlayingPlanner.SLIM_HEADER else Page.HEADER.toFloat()) * scale).dp
    val wide = width >= 250.dp
    val narrowStale = staleLabel != null && !wide
    Row(GlanceModifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.Vertical.CenterVertically) {
        SpacerW(2)
        // Which app this is, before the widget's own title ("Bloem Admin · Now playing").
        AppTag(width, slim = slim, separator = !slim)
        when {
            narrowStale -> Text(staleLabel!!, style = CaptionStyle, maxLines = 1)
            slim -> { Lucide(icon, size = 12); SpacerW(6) }
            else -> Text(title, style = RowTitleStyle, maxLines = 1)
        }
        if (staleLabel == null && status != null && (slim || width >= 300.dp)) {
            // The status takes the room that is left and gives way with an ellipsis, so Refresh is never pushed off the end.
            Row(GlanceModifier.defaultWeight().padding(start = if (slim) 0.dp else 8.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                if (live) { TallyDot(); SpacerW(4) }
                Text(status, style = CaptionStyle, maxLines = 1)
            }
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        val right = if (staleLabel != null) staleLabel.takeIf { wide } else trailing.takeIf { AppTagRules.showsServer(width.value) }
        if (right != null) {
            Text(right, style = CaptionStyle, maxLines = 1)
            SpacerW(2)
        }
        if (showRefresh) Box(GlanceModifier.size(width = 32.dp, height = rowHeight).clickable(env.refresh()), contentAlignment = Alignment.Center) {
            Lucide(R.drawable.lucide_rotate_cw, size = if (slim) 12 else 14, description = res(R.string.widget_refresh))
        }
    }
}

/** The widget's card: the `card` surface at 16 dp padding (12 on small), tapping anywhere else opens [open]. */
@Composable
internal fun WidgetCard(size: WidgetSize, open: Action?, content: @Composable () -> Unit) {
    // Medium is only about 124 dp tall on a phone, so it gives its height to rows, not to padding.
    val padding = when (size) {
        WidgetSize.Small -> GlanceModifier.padding(12.dp)
        WidgetSize.Medium -> GlanceModifier.padding(horizontal = 16.dp, vertical = 8.dp)
        WidgetSize.Large -> GlanceModifier.padding(16.dp)
    }
    val base = GlanceModifier.fillMaxSize().appWidgetBackground().background(Tokens.Card).cornerRadius(16.dp).then(padding)
    Column(modifier = if (open != null) base.clickable(open) else base, content = { content() })
}

@Composable
internal fun Lucide(icon: Int, tint: ColorProvider = Tokens.Muted, size: Int = 16, description: String? = null, modifier: GlanceModifier = GlanceModifier) {
    Image(ImageProvider(icon), description, modifier.size(size.dp), colorFilter = ColorFilter.tint(tint))
}

/** The signature dot: something is live right now, or something needs you. Used for nothing else. */
@Composable
internal fun TallyDot(modifier: GlanceModifier = GlanceModifier) {
    Image(ImageProvider(R.drawable.widget_tally_dot), null, modifier.size(8.dp), colorFilter = ColorFilter.tint(Tokens.Tally))
}

/** A small status dot in a status color, followed by its word. Never the tally dot. */
@Composable
internal fun StatusDot(color: ColorProvider) {
    Image(ImageProvider(R.drawable.widget_tally_dot), null, GlanceModifier.size(8.dp), colorFilter = ColorFilter.tint(color))
}

/** The title row: icon and name on the left, Refresh on the right. [refresh] is null on a layout too small for it. */
@Composable
internal fun Header(
    icon: Int, title: String, size: WidgetSize, env: WidgetEnv, live: Boolean = false, staleAt: Long? = null,
    showRefresh: Boolean = size != WidgetSize.Small,
) {
    val height = when (size) { WidgetSize.Small -> 20.dp; WidgetSize.Medium -> 26.dp; WidgetSize.Large -> 32.dp }
    Row(GlanceModifier.fillMaxWidth().height(height), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Lucide(icon, size = 16)
        Spacer(GlanceModifier.width(8.dp))
        Text(title, style = MutedStyle, maxLines = 1, modifier = GlanceModifier.defaultWeight())
        // On the larger layouts "Updated 14:32" sits in the header: the rows below use all the height.
        if (staleAt != null && size != WidgetSize.Small) { Text(res(R.string.widget_updated_at, timeOfDay(staleAt)), style = CaptionStyle, maxLines = 1); SpacerW(6) }
        // One tally dot per widget: here in the header (beside Refresh on the larger layouts).
        if (live) { TallyDot(); if (showRefresh) Spacer(GlanceModifier.width(4.dp)) }
        if (showRefresh) RefreshButton(env, size)
    }
}

@Composable
internal fun RefreshButton(env: WidgetEnv, size: WidgetSize) {
    Box(GlanceModifier.size(width = 48.dp, height = if (size == WidgetSize.Medium) 26.dp else 32.dp).clickable(env.refresh()), contentAlignment = Alignment.Center) {
        Lucide(R.drawable.lucide_rotate_cw, size = 16, description = res(R.string.widget_refresh))
    }
}

/** "Updated 14:32" in muted text, only when the data is older than its refresh window. Small layouts draw it under the figure. */
@Composable
internal fun StaleLine(staleAt: Long?, size: WidgetSize = WidgetSize.Small) {
    if (staleAt == null || size != WidgetSize.Small) return
    Text(res(R.string.widget_updated_at, timeOfDay(staleAt)), style = CaptionStyle, maxLines = 1)
}

internal fun timeOfDay(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** "now", "5 min", "2 h", "3 d": how long ago, for a row. */
internal fun formatAge(context: Context, millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> context.getString(R.string.widget_age_now)
        minutes < 60 -> context.getString(R.string.widget_age_minutes, minutes.toInt())
        minutes < 60 * 24 -> context.getString(R.string.widget_age_hours, (minutes / 60).toInt())
        else -> context.getString(R.string.widget_age_days, (minutes / (60 * 24)).toInt())
    }
}

@Composable
internal fun SpacerH(dp: Int) = Spacer(GlanceModifier.height(dp.dp))

@Composable
internal fun SpacerW(dp: Int) = Spacer(GlanceModifier.width(dp.dp))

/** What a widget says in each state that is not data. */
internal class Wording(
    val icon: Int,
    val title: Int,
    val signIn: Int,
    val unsupported: Int,
    val unsupportedAll: Int,
    val empty: Int,
    val loading: Int = R.string.widget_loading,
)

/**
 * The body of every state that is not data: the page, the header, and one card with one calm sentence in it, with the last data (when
 * there is some) underneath via [last]. Returns true when it drew something, so the caller skips the data layout.
 */
@Composable
internal fun <T> StateBody(
    state: WidgetState<T>,
    wording: Wording,
    env: WidgetEnv,
    openApp: Action,
    last: @Composable (T, Long?) -> Unit,
): Boolean {
    val line: String
    val live: Boolean
    when (state) {
        is WidgetState.Content<*> -> return false
        WidgetState.NoServers -> { line = res(R.string.widget_no_servers); live = false }
        WidgetState.ServerRemoved -> { line = res(R.string.widget_server_removed); live = false }
        WidgetState.Loading -> { line = res(wording.loading); live = false }
        is WidgetState.SignedOut -> { line = res(wording.signIn); live = false }
        is WidgetState.Unsupported -> { line = res(if (state.server == null) wording.unsupportedAll else wording.unsupported); live = false }
        is WidgetState.Empty -> { line = res(wording.empty); live = false }
        is WidgetState.Unreachable<*> -> {
            line = if (state.server != null) res(R.string.widget_cant_reach, state.server) else res(R.string.widget_cant_reach_many, state.count)
            live = true
        }
    }
    @Suppress("UNCHECKED_CAST")
    val unreachable = state as? WidgetState.Unreachable<T>
    val staleAt = unreachable?.at ?: (state as? WidgetState.Empty)?.staleAt
    val size = LocalSize.current
    val scale = fontScale()
    val showHeader = size.height >= 96.dp
    WidgetPage(openApp) {
        if (showHeader) {
            PageHeader(wording.icon, res(wording.title), env, size.width, staleLabel = staleAt?.let { res(R.string.widget_updated_at, timeOfDay(it)) })
            SpacerH(Page.GAP)
        }
        RowCard(GlanceModifier.fillMaxWidth().defaultWeight(), null) {
            Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(line, style = if (live) CardTitleStyle else MutedStyle, maxLines = if (size.height < 100.dp) 2 else 4)
                if (unreachable?.last != null) {
                    SpacerH(4)
                    last(unreachable.last, unreachable.at)
                }
                // Without a header there is no room for the time of the data up there, so it goes under the sentence.
                if (!showHeader && staleAt != null) Text(res(R.string.widget_updated_at, timeOfDay(staleAt)), style = CaptionStyle, maxLines = 1)
            }
        }
    }
    return true
}

/** A figure with its caption: "3" over "streams". */
@Composable
internal fun Figure(value: String, caption: String, big: Boolean = true, tally: Boolean = false) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (tally) { TallyDot(); SpacerW(8) }
        Text(value, style = if (big) FigureStyle else FigureMediumStyle, maxLines = 1)
    }
    Text(caption, style = CaptionStyle, maxLines = 1)
}

/** A dot and a word for a node or server state. */
@Composable
internal fun StatusWord(color: ColorProvider, word: String) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        StatusDot(color)
        SpacerW(4)
        Text(word, style = CaptionStyle, maxLines = 1)
    }
}

@Composable
internal fun Hairline() {
    Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Tokens.Hairline)) {}
}

@Composable
internal fun currentSize(): WidgetSize = WidgetSize.of(LocalSize.current)
