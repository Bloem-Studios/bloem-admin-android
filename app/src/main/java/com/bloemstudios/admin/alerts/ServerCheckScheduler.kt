package com.bloemstudios.admin.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bloemstudios.admin.push.KindGatedAlertSink
import com.bloemstudios.admin.push.PushRuntime
import com.bloemstudios.admin.servers.readSavedServers
import com.bloemstudios.admin.widget.SurfaceRefresher
import com.bloemstudios.admin.widget.WidgetKind
import com.bloemstudios.admin.widget.WidgetPlacement
import java.util.concurrent.TimeUnit

/** The device-local files behind alerts, the widget and the tile. */
object AlertsRuntime {
    fun alertPreferences(context: Context) =
        AlertPreferences(context.applicationContext.getSharedPreferences(AlertPreferences.FILE, Context.MODE_PRIVATE))

    fun surfacePreferences(context: Context) =
        SurfacePreferences(context.applicationContext.getSharedPreferences(SurfacePreferences.FILE, Context.MODE_PRIVATE))

    fun statusStore(context: Context) =
        ServerStatusStore(context.applicationContext.getSharedPreferences(ServerStatusStore.FILE, Context.MODE_PRIVATE))
}

/**
 * The one periodic check behind server-down alerts, the home-screen widget and the Quick Settings tile. It is scheduled
 * only while something needs it: an alert switched on for a saved server, a placed widget, or the tile in use.
 * Every 15 minutes (Android's minimum), only with a network, and not while the battery is low.
 */
object ServerCheckScheduler {
    const val UNIQUE_PERIODIC = "server-check"
    const val UNIQUE_NOW = "server-check-now"
    const val INTERVAL_MINUTES = 15L
    private const val FLEX_MINUTES = 5L

    internal val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Makes the schedule match the choices on this device. Pass [touchWhenUnwanted] = false at app start so a device
     * that uses none of this never even opens WorkManager.
     */
    fun reconcile(context: Context, touchWhenUnwanted: Boolean = true) {
        val alerts = AlertsRuntime.alertPreferences(context)
        val surfaces = AlertsRuntime.surfacePreferences(context)
        val wanted = WatchPlanner.wantsBackgroundWork(
            // Which servers are still saved is checked by the run itself, which stops the schedule if none remain.
            alertedServerIds = alerts.alertedServerIds(alerts.enabledServerIds.value),
            // Only Server status draws what this check finds (reachability and the two counts). The other four widgets are
            // read by their own job (WidgetWork), and the widgets counted here are the ones the system says exist.
            widgetCount = WidgetPlacement.placed(context).count { it.kind == WidgetKind.ServerStatus },
            tileAdded = surfaces.anyTile,
        )
        if (!wanted && !touchWhenUnwanted) return
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (wanted) {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ServerCheckWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES, FLEX_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
        } else {
            cancel(context)
        }
    }

    /** One check soon, so a freshly switched-on alert or a new widget does not wait up to 15 minutes. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ServerCheckWorker>().setConstraints(constraints).build(),
        )
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
        workManager.cancelUniqueWork(UNIQUE_NOW)
    }
}

/** Test seam: lets a test supply the checker without a network, keystore or notification service. */
internal object ServerCheckRuntime {
    var checkerFactory: ((Context) -> ServerChecker)? = null
    var afterRun: (Context) -> Unit = { SurfaceRefresher.refreshAll(it) }
}

class ServerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        // Brings the records of which widgets exist in line with the system's list before the plan reads them.
        WidgetPlacement.placed(context)
        val factory = ServerCheckRuntime.checkerFactory
        val keepGoing = if (factory != null) {
            factory(context).runOnce()
        } else {
            val counts = StoreCountsSource(context)
            try {
                ServerChecker(
                    servers = { readSavedServers(context) },
                    alerts = AlertsRuntime.alertPreferences(context),
                    surfaces = AlertsRuntime.surfacePreferences(context),
                    statuses = AlertsRuntime.statusStore(context),
                    probe = HttpHealthProbe(),
                    counts = counts,
                    // The local down and back notifications are the kinds admin.server_down and admin.server_up.
                    sink = KindGatedAlertSink(AlertNotifier(context, AlertsRuntime.alertPreferences(context)), PushRuntime.preferences(context)),
                ).runOnce()
            } finally {
                counts.close()
            }
        }
        // Nothing left to watch (for example the only watched server was removed): stop until the admin asks again.
        if (!keepGoing) ServerCheckScheduler.cancel(context)
        ServerCheckRuntime.afterRun(context)
        return Result.success()
    }
}
