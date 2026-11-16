package com.bloemstudios.admin.push

import com.bloemstudios.admin.alerts.AlertSink
import com.bloemstudios.admin.servers.AdminServer

/**
 * The local server-down check keeps its notification, as the kinds `admin.server_down` and `admin.server_up`.
 *
 * `admin.server_down` is the existing per-server "watch" switch: the check only runs for servers the admin switched on, so
 * the check itself is that switch and needs no second one. `admin.server_up` is the "back" note, which has its own
 * per-server switch like every other kind. Clearing is never gated: a switched-off alert that is already showing should still
 * go away when the server is back.
 */
class KindGatedAlertSink(private val inner: AlertSink, private val preferences: PushPreferences) : AlertSink {
    override fun down(server: AdminServer, failingSince: Long?, failures: Int) = inner.down(server, failingSince, failures)

    override fun back(server: AdminServer) {
        if (preferences.isKindEnabled(server.id, PushKind.SERVER_UP)) inner.back(server)
    }

    override fun clear(serverId: String) = inner.clear(serverId)
}
