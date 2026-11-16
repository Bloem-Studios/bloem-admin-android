package com.bloemstudios.admin.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Tells the widgets and the Quick Settings tiles that the stored numbers have changed. */
object SurfaceRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _revision = MutableStateFlow(0L)
    /** Bumped whenever the stored numbers, a widget's choice or the saved servers change; a live widget reads again. */
    val revision: StateFlow<Long> = _revision

    /** Every widget kind with the Glance widget that draws it. */
    private fun widget(kind: WidgetKind): GlanceAppWidget = when (kind) {
        WidgetKind.ServerStatus -> ServerStatusWidget()
        WidgetKind.NowPlaying -> NowPlayingWidget()
        WidgetKind.Requests -> RequestsWidget()
        WidgetKind.Libraries -> LibrariesWidget()
        WidgetKind.Nodes -> NodesWidget()
    }

    /** Redraws every placed widget and asks the system to refresh the tiles when they are visible. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        _revision.update { it + 1 }
        val surfaces = AlertsRuntime.surfacePreferences(app)
        val kinds = WidgetPlacement.placed(app).map { it.kind }.toSet()
        if (kinds.isNotEmpty()) {
            scope.launch { kinds.forEach { kind -> runCatching { widget(kind).updateAll(app) } } }
        }
        if (surfaces.tileAdded) {
            runCatching { TileService.requestListeningState(app, ComponentName(app, NowPlayingTileService::class.java)) }
        }
        if (surfaces.requestsTileAdded) {
            runCatching { TileService.requestListeningState(app, ComponentName(app, RequestsTileService::class.java)) }
        }
    }

    /**
     * The app saved something a widget shows (a request was decided, a scan or a backup started, any accepted write): draw what
     * is stored now, and read the servers again in a moment. Does nothing on a device with no widget or tile.
     */
    fun dataChanged(context: Context, reason: RefreshReason = RefreshReason.AppSaved) {
        val app = context.applicationContext
        val surfaces = AlertsRuntime.surfacePreferences(app)
        val widgets = WidgetPlacement.placed(app)
        if (widgets.isEmpty() && !surfaces.anyTile) return
        refreshAll(app)
        WidgetWork.refreshSoon(app, reason)
        // The Server status widget and the tiles draw what the server check found, so that is read again too.
        if (surfaces.anyTile || widgets.any { it.kind == WidgetKind.ServerStatus }) ServerCheckScheduler.checkNow(app)
    }
}

/**
 * Receives the system's widget events. It keeps the widgets' own 15-minute job (and the server check, for Server status) in step
 * with the widgets that exist: scheduled when the first is placed, kept after a reboot or an app update (the system sends every
 * widget an update then), and cancelled, with the cached data, when the last is removed.
 */
abstract class StaffWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // The system announces the first widget a moment before it lists it: schedule if it is listed, never cancel on an empty answer.
        WidgetWork.ensure(context)
        ServerCheckScheduler.reconcile(context, touchWhenUnwanted = false)
        // A widget that was just placed has nothing to show yet.
        WidgetWork.refreshSoon(context, RefreshReason.Placed, widgetKnown = true)
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Delivered to every widget after a reboot and after the app is updated, and again once a new widget is bound: the system's own
        // prompt to schedule. It is a widget that exists, so the job is scheduled whatever the list says.
        WidgetWork.ensure(context)
        ServerCheckScheduler.reconcile(context, touchWhenUnwanted = false)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        AlertsRuntime.surfacePreferences(context).removeWidgets(appWidgetIds)
        super.onDeleted(context, appWidgetIds)
        reconcileAll(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        reconcileAll(context)
    }

    /** A widget went: schedule what is still wanted and cancel the rest (the last widget takes the job and its cached data with it). */
    private fun reconcileAll(context: Context) {
        WidgetWork.reconcile(context)
        ServerCheckScheduler.reconcile(context)
    }
}

class ServerStatusWidgetReceiver : StaffWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ServerStatusWidget() }
class NowPlayingWidgetReceiver : StaffWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget() }
class RequestsWidgetReceiver : StaffWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = RequestsWidget() }
class LibrariesWidgetReceiver : StaffWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = LibrariesWidget() }
class NodesWidgetReceiver : StaffWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NodesWidget() }

/** The receivers by kind, for the system's widget previews. */
internal val widgetReceivers: List<kotlin.reflect.KClass<out GlanceAppWidgetReceiver>> = listOf(
    ServerStatusWidgetReceiver::class, NowPlayingWidgetReceiver::class, RequestsWidgetReceiver::class,
    LibrariesWidgetReceiver::class, NodesWidgetReceiver::class,
)
