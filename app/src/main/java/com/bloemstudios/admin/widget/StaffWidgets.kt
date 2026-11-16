package com.bloemstudios.admin.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.readSavedServers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a widget reads when it draws: the saved servers, the last answers, and the privacy settings of this device. */
internal class WidgetLoad(
    val servers: List<AdminServer>,
    val records: Map<String, ServerRecord>,
    val cache: WidgetCache.Snapshot,
    val target: String?,
    val surface: Surface,
    val showDetailsOnLockScreen: Boolean,
    val nowMillis: Long,
) {
    val detail: Detail get() = WidgetPrivacy.detail(surface, showDetailsOnLockScreen)

    fun inputs() = WidgetInputs(
        target = target,
        servers = servers,
        slice = cache::slice,
        reachable = { records[it]?.status?.reachable },
        notice = cache::notice,
        detail = detail,
        nowMillis = nowMillis,
    )

    companion object {
        suspend fun read(context: Context, widgetId: Int, clock: () -> Long = System::currentTimeMillis): WidgetLoad = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val surfaces = AlertsRuntime.surfacePreferences(app)
            // The app's Appearance setting (System, Light, Dark) decides the widget's colors, as it does the app's.
            WidgetAppearance.mode = com.bloemstudios.admin.ui.theme.ThemePreferences(app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)).themeMode.value
            val category = runCatching {
                AppWidgetManager.getInstance(app).getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            }.getOrDefault(android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            WidgetLoad(
                servers = readSavedServers(app),
                records = AlertsRuntime.statusStore(app).records(),
                cache = WidgetCache.forContext(app).snapshot(),
                // A widget with no saved choice (placed before a choice was possible) shows All servers.
                target = surfaces.widgetTargets()[widgetId],
                surface = if (category == android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) Surface.Keyguard else Surface.HomeScreen,
                showDetailsOnLockScreen = AlertsRuntime.alertPreferences(app).showDetailsOnLockScreen.value,
                nowMillis = clock(),
            )
        }
    }
}

/** The real [WidgetEnv]: links open the app, Refresh and the decision buttons run a callback. */
internal class GlanceEnv(private val context: Context, override val nowMillis: Long) : WidgetEnv {
    override fun open(link: String): Action = openLinkAction(context, link)

    override fun refresh(): Action = actionRunCallback<RefreshCallback>()

    override fun decide(serverId: String, requestId: String, approve: Boolean): Action = actionRunCallback<DecideCallback>(
        actionParametersOf(ServerKey to serverId, RequestKey to requestId, ApproveKey to approve),
    )
}

internal fun openLinkAction(context: Context, link: String): Action = actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
)

internal val ServerKey = ActionParameters.Key<String>("server")
internal val RequestKey = ActionParameters.Key<String>("request")
internal val ApproveKey = ActionParameters.Key<Boolean>("approve")

/** Refresh: read the servers again now. Nothing is changed on any server. */
class RefreshCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Never held back by a gap: it is the person asking. Server status (health and counts) is read too.
        WidgetWork.refreshSoon(context, RefreshReason.UserRefresh)
        ServerCheckScheduler.checkNow(context)
    }
}

/** Approve or Decline one request. The widget is only reachable unlocked; this runs the call, then reloads. */
class DecideCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serverId = parameters[ServerKey] ?: return
        val requestId = parameters[RequestKey] ?: return
        val approve = parameters[ApproveKey] ?: return
        val app = context.applicationContext
        val session = StoreWidgetSession(app)
        try {
            val decision = RequestDecision({ readSavedServers(app) }, WidgetCache.forContext(app), session)
            withTimeoutOrNull(DECISION_TIMEOUT_MILLIS) { decision.run(serverId, requestId, approve) }
                ?: WidgetCache.forContext(app).setNotice(serverId, ActionNotice(serverId, approve, System.currentTimeMillis()))
        } finally {
            session.close()
        }
        // Draw the result now, then read the queue again so the list fills up.
        SurfaceRefresher.dataChanged(app)
    }

    private companion object {
        const val DECISION_TIMEOUT_MILLIS = 8_000L
    }
}

/**
 * A staff widget: a card that draws a [WidgetState] in three sizes. It reads what the last check stored (never the
 * network, so it draws at once) and reads again whenever the check or the app bumps [SurfaceRefresher.revision].
 */
abstract class StaffWidget(val kind: WidgetKind, sizes: Set<DpSize>) : GlanceAppWidget() {
    // The exact size the launcher gives, so the layout follows a drag of the widget's edge continuously (see NowPlayingPlanner).
    override val sizeMode: SizeMode = SizeMode.Exact

    internal abstract fun stateOf(load: WidgetLoad): Any

    @Composable
    internal abstract fun Draw(state: Any, env: WidgetEnv, openApp: Action)

    /** Sample content for the system's widget picker (Android 15 and later). */
    @Composable
    internal abstract fun DrawSample(env: WidgetEnv, openApp: Action)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val first = WidgetLoad.read(context, widgetId)
        provideContent {
            // Glance keeps this composition alive between updates, so what was read once would go stale: the next check bumps
            // the revision and the widget reads again here.
            val revision by SurfaceRefresher.revision.collectAsState()
            var load by remember { mutableStateOf(first) }
            LaunchedEffect(revision) { if (revision != 0L) load = WidgetLoad.read(context, widgetId) }
            val openApp = openLinkAction(context, WidgetViews.link(load.target, kind))
            Draw(stateOf(load), GlanceEnv(context, load.nowMillis), openApp)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { DrawSample(NoopEnv(context), openLinkAction(context, DeepLink.buildAll())) }
    }
}

/** An env whose actions all just open the app: previews and screenshots. */
internal class NoopEnv(private val context: Context, override val nowMillis: Long = WidgetSamples.NOW) : WidgetEnv {
    override fun open(link: String): Action = openLinkAction(context, link)
    override fun refresh(): Action = openLinkAction(context, DeepLink.buildAll())
    override fun decide(serverId: String, requestId: String, approve: Boolean): Action = openLinkAction(context, DeepLink.buildAll())
}

class ServerStatusWidget : StaffWidget(WidgetKind.ServerStatus, WidgetSize.all) {
    override fun stateOf(load: WidgetLoad): Any = StatusViews.map(load.target, load.servers, load.records, load.nowMillis)

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Draw(state: Any, env: WidgetEnv, openApp: Action) = ServerStatusContent(state as WidgetState<ServerStatusView>, env, openApp)

    @Composable
    override fun DrawSample(env: WidgetEnv, openApp: Action) = ServerStatusContent(WidgetSamples.status(), env, openApp)
}

class NowPlayingWidget : StaffWidget(WidgetKind.NowPlaying, WidgetSize.all) {
    override fun stateOf(load: WidgetLoad): Any = WidgetViews.nowPlaying(load.inputs())

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Draw(state: Any, env: WidgetEnv, openApp: Action) = NowPlayingContent(state as WidgetState<NowPlayingView>, env, openApp)

    @Composable
    override fun DrawSample(env: WidgetEnv, openApp: Action) = NowPlayingContent(WidgetSamples.nowPlaying(), env, openApp)
}

class RequestsWidget : StaffWidget(WidgetKind.Requests, WidgetSize.all) {
    override fun stateOf(load: WidgetLoad): Any = WidgetViews.requests(load.inputs())

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Draw(state: Any, env: WidgetEnv, openApp: Action) = RequestsContent(state as WidgetState<RequestsView>, env, openApp)

    @Composable
    override fun DrawSample(env: WidgetEnv, openApp: Action) = RequestsContent(WidgetSamples.requests(), env, openApp)
}

class LibrariesWidget : StaffWidget(WidgetKind.Libraries, WidgetSize.mediumAndLarge) {
    override fun stateOf(load: WidgetLoad): Any = WidgetViews.libraries(load.inputs())

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Draw(state: Any, env: WidgetEnv, openApp: Action) = LibrariesContent(state as WidgetState<LibrariesView>, env, openApp)

    @Composable
    override fun DrawSample(env: WidgetEnv, openApp: Action) = LibrariesContent(WidgetSamples.libraries(), env, openApp)
}

class NodesWidget : StaffWidget(WidgetKind.Nodes, WidgetSize.mediumAndLarge) {
    override fun stateOf(load: WidgetLoad): Any = WidgetViews.nodes(load.inputs())

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Draw(state: Any, env: WidgetEnv, openApp: Action) = NodesContent(state as WidgetState<NodesView>, env, openApp)

    @Composable
    override fun DrawSample(env: WidgetEnv, openApp: Action) = NodesContent(WidgetSamples.nodes(), env, openApp)
}
