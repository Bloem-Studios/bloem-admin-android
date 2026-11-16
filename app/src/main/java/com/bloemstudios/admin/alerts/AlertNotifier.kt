package com.bloemstudios.admin.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.R
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.theme.VeilPalette
import java.text.DateFormat
import java.util.Date

/**
 * Posts the "can't reach" and "reachable again" notifications. Text is the server's name and, once unlocked (or when the
 * admin switched on "Show details on lock screen"), when it stopped answering. Never a title, a person, an address or a
 * count of anything. Tapping opens that server through the app's own link, behind the app lock and the admin check.
 */
class AlertNotifier(private val context: Context, private val alerts: AlertPreferences) : AlertSink {
    override fun down(server: AdminServer, failingSince: Long?, failures: Int) {
        val title = context.getString(R.string.alerts_down_title, server.displayName)
        val details = failingSince?.let {
            context.getString(R.string.alerts_down_text_details, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
        } ?: context.getString(R.string.alerts_down_text_generic)
        val lockScreenDetails = alerts.showDetailsOnLockScreen.value
        // What anyone sees on a locked screen: the generic version, unless the admin chose otherwise.
        val publicVersion = build(title, context.getString(R.string.alerts_down_text_generic), server, silent = false).build()
        post(
            server,
            build(title, details, server, silent = false)
                .setVisibility(if (lockScreenDetails) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(if (lockScreenDetails) null else publicVersion)
                .build(),
        )
    }

    override fun back(server: AdminServer) {
        post(
            server,
            build(
                context.getString(R.string.alerts_back_title, server.displayName),
                context.getString(R.string.alerts_back_text),
                server,
                silent = true,
            )
                // The note says the server is fine: it tidies itself away.
                .setTimeoutAfter(BACK_NOTE_LIFETIME_MILLIS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    build(
                        context.getString(R.string.alerts_back_title, server.displayName),
                        context.getString(R.string.alerts_back_text),
                        server,
                        silent = true,
                    ).build(),
                )
                .build(),
        )
    }

    override fun clear(serverId: String) {
        NotificationManagerCompat.from(context).cancel(serverId, NOTIFICATION_ID)
    }

    /** Takes down every alert this app posted (alerts were switched off). */
    fun clearAll() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun build(title: String, text: String, server: AdminServer, silent: Boolean): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bloem)
            .setColor(VeilPalette.Dark.accent.toArgb())
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setGroup(GROUP)
            .setContentIntent(openServer(server))
    }

    private fun openServer(server: AdminServer): PendingIntent = PendingIntent.getActivity(
        context, server.id.hashCode(), openServerIntent(context, server.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked by canPost()
    private fun post(server: AdminServer, notification: android.app.Notification) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(server.id, NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "server_reachability"

        /** What a tap on an alert launches: this app, with the link that names the saved server and nothing else. */
        internal fun openServerIntent(context: Context, serverId: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(DeepLink.build(serverId)), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        private const val GROUP = "server_reachability"
        private const val NOTIFICATION_ID = 1
        private const val BACK_NOTE_LIFETIME_MILLIS = 6L * 60 * 60 * 1000

        /** Creates the "Servers" channel (id `server_reachability`, kept). Safe to call again; Android keeps the user's own channel choices. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.alerts_channel_description)
                // lockscreenVisibility is left unset on purpose: each notification chooses (private, with a generic public
                // version, unless the admin opted into details; see AlertNotifier.down), and a channel value would override it.
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        /** True when Android would show a notification from this app right now (permission granted and not switched off). */
        fun canPost(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= NOTIFICATION_PERMISSION_API &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        fun permissionGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < NOTIFICATION_PERMISSION_API ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
