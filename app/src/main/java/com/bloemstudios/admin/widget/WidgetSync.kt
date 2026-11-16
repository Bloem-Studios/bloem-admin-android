package com.bloemstudios.admin.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bloemstudios.admin.alerts.AlertPreferences
import com.bloemstudios.admin.network.AppDataSignal
import com.bloemstudios.admin.realtime.LiveCues
import com.bloemstudios.admin.realtime.LiveEnvironments
import com.bloemstudios.admin.servers.readSavedServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Connects the app to its widgets, from `App.onCreate`:
 *  - what the app saves reaches them (a request decided, a scan or backup started, any accepted write);
 *  - the settings that change what they may show redraw them;
 *  - when the app goes to the background they are read again, so they show what the person just saw;
 *  - while the app is in front, a change its events socket reports on a channel a widget shows is read straight away (at most
 *    once every 10 seconds per kind). Widgets never open sockets: the app's one socket per server does.
 */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed = false

    // Kept in a field: SharedPreferences holds its listeners weakly.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in WATCHED_KEYS) context?.let(SurfaceRefresher::refreshAll)
    }
    private var context: Context? = null

    /** The settings that change what a widget shows: the theme and "Show details on lock screen". */
    private val WATCHED_KEYS = setOf("theme_mode", "show_details_on_lock_screen")

    /** Writes that no widget shows anything of: signing in, the events socket's own ticket, push registration. */
    private val QUIET_PATHS = listOf("/auth/", "/events/", "/push")

    @Synchronized
    fun install(app: Context) {
        if (installed) return
        installed = true
        val application = app.applicationContext
        context = application
        application.getSharedPreferences("app_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        application.getSharedPreferences(AlertPreferences.FILE, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        AppDataSignal.listener = { event -> scope.launch { onEvent(application, event) } }
        installLiveFeed(application)
        // Lifecycle observers are added on the main thread: Application.onCreate is that.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Draw what the app has, and read again in a moment: the person has just seen newer data than the widget has.
                scope.launch { SurfaceRefresher.dataChanged(application, RefreshReason.Background) }
            }
        })
        publishPreviews(application)
    }

    private fun installLiveFeed(app: Context) {
        val feed = LiveWidgetFeed(
            scope = scope,
            clock = System::currentTimeMillis,
            foreground = { LiveEnvironments.current.foreground.value },
            placedKinds = { WidgetPlacement.placed(app).map { it.kind }.toSet() },
            serverIdFor = { origin -> serverForOrigin(readSavedServers(app), origin)?.id },
            refresh = { kind, serverIds -> runWidgetRefresh(app, setOf(kind), serverIds) },
        )
        LiveCues.listener = feed::onCue
    }

    /**
     * Android 15 and later let a widget draw its own picker preview. Each widget hands over its sample content (WidgetSamples),
     * so the picker shows the real layout in the system's light or dark setting. The system limits how often this may run;
     * a refusal is not an error.
     */
    private fun publishPreviews(app: Context) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        scope.launch {
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(app)
            widgetReceivers.forEach { receiver -> runCatching { manager.setWidgetPreviews(receiver) } }
        }
    }

    internal suspend fun onEvent(context: Context, event: AppDataSignal.Event) {
        when (event) {
            is AppDataSignal.RequestDecided -> SurfaceRefresher.dataChanged(context)
            is AppDataSignal.ScanStarted -> {
                follow(context, event.origin, JobKind.Scan, event.libraryId)
                SurfaceRefresher.dataChanged(context)
            }
            is AppDataSignal.BackupStarted -> {
                follow(context, event.origin, JobKind.Backup, null)
                SurfaceRefresher.dataChanged(context)
            }
            is AppDataSignal.Wrote -> if (QUIET_PATHS.none { it in event.path }) SurfaceRefresher.dataChanged(context)
        }
    }

    private suspend fun follow(context: Context, origin: String, kind: JobKind, libraryId: String?) {
        val server = serverForOrigin(readSavedServers(context), origin) ?: return
        JobProgressTracker.start(context, kind, server.id, libraryId)
    }
}
