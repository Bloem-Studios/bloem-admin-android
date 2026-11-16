package com.bloemstudios.admin.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.bloemstudios.admin.R
import com.bloemstudios.admin.alerts.AlertNotifier

/**
 * The notification kinds. The ids are stable keys: payloads, settings and analytics use them, so
 * they are never renamed. Two are produced by this app's own server checks (a server that is down cannot push); the rest
 * come from a Bloem server.
 */
object PushKind {
    const val SERVER_DOWN = "admin.server_down"
    const val SERVER_UP = "admin.server_up"
    const val REQUEST_WAITING = "admin.request_waiting"
    const val SCAN_FAILED = "admin.scan_failed"
    const val BACKUP_FAILED = "admin.backup_failed"
    const val NODE_OFFLINE = "admin.node_offline"
    const val PLUGIN_FAILED = "admin.plugin_failed"
}

/** [local] kinds come from this app's checks; their switches never reach the server's filter. */
data class PushKindInfo(val id: String, val channelId: String, @StringRes val label: Int, val local: Boolean)

object PushKinds {
    val all: List<PushKindInfo> = listOf(
        PushKindInfo(PushKind.SERVER_DOWN, PushChannels.SERVERS, R.string.push_kind_server_down, local = true),
        PushKindInfo(PushKind.SERVER_UP, PushChannels.SERVERS, R.string.push_kind_server_up, local = true),
        PushKindInfo(PushKind.REQUEST_WAITING, PushChannels.REQUESTS, R.string.push_kind_request_waiting, local = false),
        PushKindInfo(PushKind.SCAN_FAILED, PushChannels.LIBRARIES, R.string.push_kind_scan_failed, local = false),
        PushKindInfo(PushKind.BACKUP_FAILED, PushChannels.BACKUPS, R.string.push_kind_backup_failed, local = false),
        PushKindInfo(PushKind.NODE_OFFLINE, PushChannels.NODES, R.string.push_kind_node_offline, local = false),
        PushKindInfo(PushKind.PLUGIN_FAILED, PushChannels.PLUGINS, R.string.push_kind_plugin_failed, local = false),
    )

    fun byId(id: String): PushKindInfo? = all.firstOrNull { it.id == id }
}

/** One Android channel per app section. The Servers channel keeps the id the local server-down alerts already used. */
object PushChannels {
    const val SERVERS = AlertNotifier.CHANNEL_ID
    const val REQUESTS = "push_requests"
    const val LIBRARIES = "push_libraries"
    const val BACKUPS = "push_backups"
    const val NODES = "push_nodes"
    const val PLUGINS = "push_plugins"

    /** The channel the server names ("requests", "libraries", ...), or null for a name this build does not know. */
    fun idFor(serverChannel: String): String? = when (serverChannel) {
        "servers" -> SERVERS
        "requests" -> REQUESTS
        "libraries" -> LIBRARIES
        "backups" -> BACKUPS
        "nodes" -> NODES
        "plugins" -> PLUGINS
        else -> null
    }

    private class Spec(val id: String, @StringRes val name: Int, @StringRes val description: Int, val importance: Int)

    private val specs = listOf(
        Spec(SERVERS, R.string.push_channel_servers, R.string.alerts_channel_description, NotificationManager.IMPORTANCE_HIGH),
        Spec(REQUESTS, R.string.push_channel_requests, R.string.push_channel_requests_description, NotificationManager.IMPORTANCE_DEFAULT),
        Spec(LIBRARIES, R.string.push_channel_libraries, R.string.push_channel_libraries_description, NotificationManager.IMPORTANCE_DEFAULT),
        Spec(BACKUPS, R.string.push_channel_backups, R.string.push_channel_backups_description, NotificationManager.IMPORTANCE_HIGH),
        Spec(NODES, R.string.push_channel_nodes, R.string.push_channel_nodes_description, NotificationManager.IMPORTANCE_DEFAULT),
        Spec(PLUGINS, R.string.push_channel_plugins, R.string.push_channel_plugins_description, NotificationManager.IMPORTANCE_DEFAULT),
    )

    val ids: List<String> = specs.map { it.id }

    /** Creates every channel. Safe to call again; Android keeps the user's own choices for a channel that exists. */
    fun ensureAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        specs.forEach { spec ->
            manager.createNotificationChannel(
                NotificationChannel(spec.id, context.getString(spec.name), spec.importance).apply {
                    description = context.getString(spec.description)
                    // lockscreenVisibility stays unset: each notification is private with a generic public version, or
                    // public when the admin allowed details, and a channel value would override that.
                    setShowBadge(spec.id == REQUESTS)
                },
            )
        }
    }
}
