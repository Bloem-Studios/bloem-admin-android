package com.bloemstudios.admin.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The widgets' own background work, apart from the server check that backs alerts and Server status.
 *
 * What was wrong before: the detail widgets (Now playing, Requests, Libraries, Nodes) were read only inside the server check,
 * and that check was scheduled from a note the app kept about each widget. A widget with no note (placed by an earlier build, or
 * restored by the launcher) was never in the plan, so nothing refreshed it; and nothing refreshed anything when the app left
 * the screen, a push arrived or a stream was about to end. Now the schedule follows the widgets the system says exist
 * ([WidgetPlacement]), runs every 15 minutes with a network and nothing else, and has a one-shot path for each moment between.
 * A run reads each server once for each thing a placed widget shows: for Now playing that is one request per server, where
 * going through the server check would have cost a health probe, the counts and then the sessions again.
 */
object WidgetWork {
    const val PERIODIC = "admin-widgets-refresh"
    const val SOON = "admin-widgets-refresh-soon"
    const val FOLLOW_UP = "admin-widgets-follow-up"
    const val INTERVAL_MINUTES = 15L
    private const val FLEX_MINUTES = 5L

    private const val KEY_ONLY_NOW_PLAYING = "only_now_playing"

    internal val constraints: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Makes the schedule match the widgets that exist: one periodic job while any admin widget is placed, none when the last is
     * removed. Safe to call as often as you like (it keeps the job that is already there). Pass [touchWhenUnwanted] = false at
     * app start so a phone with no widget never even opens WorkManager. Returns whether any widget exists.
     */
    fun reconcile(context: Context, touchWhenUnwanted: Boolean = true): Boolean {
        val app = context.applicationContext
        val wanted = WidgetPlacement.placed(app).isNotEmpty()
        if (!wanted && !touchWhenUnwanted) return false
        val manager = WorkManager.getInstance(app)
        if (wanted) {
            manager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                // A flex window of 5 minutes, so the first run is 10 to 15 minutes out and not at once. Without it the job is runnable the
                // moment it is scheduled, the system runs it, and WorkManager re-schedules the same job id from inside that run, which can
                // drop the job (seen on a real system image: the 15-minute job sometimes was not there a moment after a widget was placed).
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES, FLEX_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
        } else {
            cancel(app)
        }
        return wanted
    }

    /**
     * Schedules the job if any widget exists and does nothing otherwise: it never cancels. The enable and update paths use it, because
     * the system can announce a first widget a moment before it lists it, and an empty answer then must not undo anything.
     */
    fun ensure(context: Context): Boolean = reconcile(context, touchWhenUnwanted = false)

    /** Stops everything and forgets what the widgets held: with no widget left, nothing is there to draw it. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = WorkManager.getInstance(app)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(SOON)
        manager.cancelUniqueWork(FOLLOW_UP)
        WidgetCache.forContext(app).clear()
    }

    /**
     * A refresh soon, for [reason]. A burst of the same reason becomes one run (the latest replaces the one waiting), and a
     * reason with a minimum gap is dropped when the last run was too recent. Does nothing when no widget exists.
     */
    fun refreshSoon(context: Context, reason: RefreshReason, nowMillis: Long = System.currentTimeMillis(), widgetKnown: Boolean = false) {
        val app = context.applicationContext
        // [widgetKnown]: the system has just announced a widget (see `StaffWidgetReceiver.onEnabled`) and may not list it yet.
        if (!widgetKnown && WidgetPlacement.placed(app).isEmpty()) return
        if (!RefreshPolicy.allowed(reason, nowMillis, RefreshLog.lastRunAt(app))) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            SOON,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(constraints).setInitialDelay(reason.delayMillis, TimeUnit.MILLISECONDS).build(),
        )
    }

    /** The one-shot that follows a run while something plays (see [ActiveCadence]); a null [delayMillis] cancels it. */
    internal fun scheduleFollowUp(context: Context, delayMillis: Long?) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (delayMillis == null) {
            manager.cancelUniqueWork(FOLLOW_UP)
            return
        }
        manager.enqueueUniqueWork(
            FOLLOW_UP,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                // The follow-up asks only for the streams: everything else keeps its 15-minute rhythm.
                .setInputData(Data.Builder().putBoolean(KEY_ONLY_NOW_PLAYING, true).build())
                .build(),
        )
    }

    internal fun onlyNowPlaying(data: Data): Boolean = data.getBoolean(KEY_ONLY_NOW_PLAYING, false)
}

/** Test seam for a run: lets a test supply the reader without a network or a keystore, and skip the redraw. */
internal object WidgetRefreshRuntime {
    var refresherFactory: ((Context) -> WidgetRefresher)? = null
    var afterRun: (Context) -> Unit = { SurfaceRefresher.refreshAll(it) }
}

/** The periodic job and every one-shot: read the widgets' servers, redraw, plan the follow-up. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val only = if (WidgetWork.onlyNowPlaying(inputData)) setOf(WidgetKind.NowPlaying) else null
        runWidgetRefresh(applicationContext, kinds = only)
        return Result.success()
    }
}
