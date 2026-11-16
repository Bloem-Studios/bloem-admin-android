package com.bloemstudios.admin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.bloemstudios.admin.alerts.SurfacePreferences

/** Where the system says each kind's widgets are. A seam: tests pass fake ids. */
fun interface WidgetIds {
    fun idsOf(kind: WidgetKind): Set<Int>
}

/** The real answer: the launcher's own list of this app's widgets, not anything the app wrote down. */
internal class SystemWidgetIds(private val context: Context) : WidgetIds {
    override fun idsOf(kind: WidgetKind): Set<Int> =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WidgetInventory.receiverOf(kind))).toSet()
}

/**
 * Which widgets really exist. The app keeps a record of each one (its kind and the server it shows, written by the
 * configuration screen), but a record is only a note: a widget the app never heard about (placed by an earlier build, or
 * restored by the launcher onto a new phone, or whose configuration screen was skipped) has none, and a widget the user
 * removed while the app was not running leaves one behind. Everything that decides whether background work is wanted asks
 * here, and the answer comes from the system:
 *  - a widget the system has and the app has no record of is adopted (it shows All servers, the same as before);
 *  - a record whose widget the system no longer has is dropped;
 *  - a record whose kind differs from the provider the system placed is corrected.
 */
object WidgetInventory {
    fun receiverOf(kind: WidgetKind): Class<out GlanceAppWidgetReceiver> = when (kind) {
        WidgetKind.ServerStatus -> ServerStatusWidgetReceiver::class.java
        WidgetKind.NowPlaying -> NowPlayingWidgetReceiver::class.java
        WidgetKind.Requests -> RequestsWidgetReceiver::class.java
        WidgetKind.Libraries -> LibrariesWidgetReceiver::class.java
        WidgetKind.Nodes -> NodesWidgetReceiver::class.java
    }

    /**
     * Brings the records in line with [ids] and returns the widgets that exist. When the system cannot be asked, the
     * records are left as they were and are returned unchanged: a failed question never removes anyone's widget.
     */
    fun sync(surfaces: SurfacePreferences, ids: WidgetIds): List<PlacedWidget> {
        val system: Map<WidgetKind, Set<Int>> = try {
            WidgetKind.entries.associateWith { ids.idsOf(it) }
        } catch (_: RuntimeException) {
            return surfaces.placedWidgets()
        }
        val kindOf = buildMap { system.forEach { (kind, set) -> set.forEach { put(it, kind) } } }
        val recorded = surfaces.placedWidgets()

        val stale = recorded.filter { it.id !in kindOf }.map { it.id }
        if (stale.isNotEmpty()) surfaces.removeWidgets(stale.toIntArray())
        for (widget in recorded) {
            val actual = kindOf[widget.id] ?: continue
            if (widget.kind != actual) surfaces.setWidget(widget.id, actual, widget.target)
        }
        val recordedIds = recorded.map { it.id }.toSet()
        for ((id, kind) in kindOf) {
            if (id !in recordedIds) surfaces.setWidget(id, kind, null)
        }
        return surfaces.placedWidgets()
    }

    /** The kinds of widget that exist, for the work that only some kinds need. */
    fun kinds(widgets: List<PlacedWidget>): Set<WidgetKind> = widgets.map { it.kind }.toSet()
}
