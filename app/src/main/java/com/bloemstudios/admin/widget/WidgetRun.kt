package com.bloemstudios.admin.widget

import android.content.Context
import com.bloemstudios.admin.alerts.AlertsRuntime
import kotlinx.coroutines.CancellationException

/**
 * Which widgets exist, as the system says. A thin layer over [WidgetInventory] that carries the test seam, so the scheduler, the
 * refresher and the screens all ask the same question the same way.
 */
object WidgetPlacement {
    /** Test seam: the system's answer about which widgets exist. */
    internal var widgetIds: (Context) -> WidgetIds = { SystemWidgetIds(it) }

    /** The widgets that exist now, with the app's records brought in line (see [WidgetInventory.sync]). */
    fun placed(context: Context): List<PlacedWidget> {
        val app = context.applicationContext
        return WidgetInventory.sync(AlertsRuntime.surfacePreferences(app), widgetIds(app))
    }
}

/** When the widgets were last read, so a burst of "the app went to the background" does not read them again and again. */
internal object RefreshLog {
    private const val FILE = "widget_refresh_log"
    private const val KEY = "last_run_at"

    fun lastRunAt(context: Context): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY, -1L).takeIf { it >= 0 }

    fun record(context: Context, at: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(KEY, at).apply()
    }
}

/**
 * Reads what the placed widgets show (every kind and server, or only [kinds] and [serverIds]), redraws them, and plans the
 * follow-up that keeps Now playing fresh while something plays. The one function the periodic job, the one-shots and the
 * app's own live-socket trigger all use. Never throws: a server that does not answer leaves the widget its last data.
 */
suspend fun runWidgetRefresh(context: Context, kinds: Set<WidgetKind>? = null, serverIds: Set<String>? = null, nowMillis: Long = System.currentTimeMillis()) {
    val app = context.applicationContext
    if (WidgetPlacement.placed(app).isEmpty()) {
        // A run with no widget left is the job's own cue to stop (a removal whose broadcast was missed).
        WidgetWork.cancel(app)
        return
    }
    // Every run re-arms the 15-minute job, so a job that was lost (an update, a force stop) comes back with the next one-shot.
    WidgetWork.ensure(app)
    val refresher = WidgetRefreshRuntime.refresherFactory?.invoke(app) ?: WidgetRefresher.create(app)
    try {
        refresher.run(kinds, serverIds)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Left as it was.
    }
    RefreshLog.record(app, nowMillis)
    WidgetRefreshRuntime.afterRun(app)
    planFollowUp(app, nowMillis)
}

/** After a read: while something plays, ask to be read again at the soonest stream end (not before the floor, not after the ceiling); otherwise not at all. */
internal fun planFollowUp(context: Context, nowMillis: Long) {
    val app = context.applicationContext
    val nowPlayingPlaced = WidgetPlacement.placed(app).any { it.kind == WidgetKind.NowPlaying }
    val delay = if (nowPlayingPlaced) {
        ActiveCadence.nextDelayMillis(nowMillis, ActiveCadence.streamsIn(WidgetCache.forContext(app).snapshot().slicesOf(WidgetKind.NowPlaying)))
    } else null
    WidgetWork.scheduleFollowUp(app, delay)
}
