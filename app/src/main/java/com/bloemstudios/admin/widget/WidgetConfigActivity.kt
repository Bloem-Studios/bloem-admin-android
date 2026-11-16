package com.bloemstudios.admin.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.readSavedServers
import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.theme.ThemePreferences
import com.bloemstudios.admin.ui.theme.SystemBarsFollowTheme
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * Asked by the launcher when a widget is placed (and from "Reconfigure"): which saved server, or All servers, the widget
 * shows. One activity serves all five widgets; the kind comes from the widget the launcher is placing. It lists server names
 * only. It does not sit behind the app lock because it shows nothing the widget will not.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var kind = WidgetKind.ServerStatus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out places no widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        kind = kindOfWidget(this, widgetId)
        val theme = ThemePreferences(getSharedPreferences("app_settings", Context.MODE_PRIVATE))
        setContent {
            val mode by theme.themeMode.collectAsState()
            VeilAdminTheme(mode) {
                SystemBarsFollowTheme()
                var servers by remember { mutableStateOf<List<AdminServer>?>(null) }
                LaunchedEffect(Unit) { servers = readSavedServers(this@WidgetConfigActivity) }
                WidgetChoice(servers, kind) { serverId -> choose(serverId) }
            }
        }
    }

    private fun choose(serverId: String?) {
        val context = applicationContext
        AlertsRuntime.surfacePreferences(context).setWidget(widgetId, kind, serverId)
        WidgetWork.reconcile(context)
        WidgetWork.refreshSoon(context, RefreshReason.Placed)
        ServerCheckScheduler.reconcile(context)
        if (kind == WidgetKind.ServerStatus) ServerCheckScheduler.checkNow(context)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        // Draw what is known right away; the check that was just asked for redraws it with fresh numbers.
        SurfaceRefresher.refreshAll(context)
        finish()
    }
}

@Composable
internal fun WidgetChoice(servers: List<AdminServer>?, kind: WidgetKind, onChoose: (serverId: String?) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VeilColors.Canvas).safeDrawingPadding().padding(VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
    ) {
        Text(
            stringResource(R.string.widget_choose_title), style = MaterialTheme.typography.titleLarge, color = VeilColors.Text,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (kind == WidgetKind.ServerStatus) R.string.widget_choose_hint_status else R.string.widget_choose_hint_detail),
            style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted,
        )
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            item { ListRowCard(title = stringResource(R.string.widget_all_servers), onClick = { onChoose(null) }) }
            items(servers.orEmpty(), key = { it.id }) { server ->
                ListRowCard(title = server.displayName, onClick = { onChoose(server.id) })
            }
        }
    }
}

/** Which widget the launcher is placing, from the provider it chose. An unknown provider is Server status. */
internal fun kindOfWidget(context: Context, widgetId: Int): WidgetKind {
    val provider = runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className }.getOrNull()
    return when (provider?.substringAfterLast('.')) {
        NowPlayingWidgetReceiver::class.java.simpleName -> WidgetKind.NowPlaying
        RequestsWidgetReceiver::class.java.simpleName -> WidgetKind.Requests
        LibrariesWidgetReceiver::class.java.simpleName -> WidgetKind.Libraries
        NodesWidgetReceiver::class.java.simpleName -> WidgetKind.Nodes
        else -> WidgetKind.ServerStatus
    }
}
