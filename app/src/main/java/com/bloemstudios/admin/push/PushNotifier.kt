package com.bloemstudios.admin.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.R
import com.bloemstudios.admin.alerts.AlertNotifier
import com.bloemstudios.admin.alerts.AlertPreferences
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.theme.VeilPalette

/**
 * Posts the notifications a Bloem server pushes. Privacy follows the same per-device "Show details on lock screen" switch
 * as the local server-down alerts: by default a locked screen shows only the lock-screen text the server sent ("New request
 * waiting"), never a name, a title or a count; with the switch on it shows the notification itself.
 *
 * A tap opens the exact item through the app's own link, so it passes the app lock and the admin check like every link.
 * Buttons appear only on a request notice. They run only on an unlocked device and never offer anything destructive.
 */
class PushNotifier(private val context: Context, private val alerts: AlertPreferences) : PushPoster {
    /** Posts [content] (already [PushContent.sanitized]) for [server]. Returns false when Android would not show it. */
    @SuppressLint("MissingPermission") // checked by canPost()
    override fun post(server: AdminServer, content: PushContent): Boolean {
        if (!AlertNotifier.canPost(context)) return false
        PushChannels.ensureAll(context)
        val tag = tagFor(server.id, content.kind, content.itemId)
        NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, build(server, content, tag))
        return true
    }

    /** What shows when the server could not be asked: no title, no name, nothing but that something is waiting. */
    @SuppressLint("MissingPermission")
    override fun postGeneric(server: AdminServer, deliveryId: String): Boolean {
        if (!AlertNotifier.canPost(context)) return false
        PushChannels.ensureAll(context)
        val notification = base(PushChannels.SERVERS)
            .setContentTitle(context.getString(R.string.push_generic_title))
            .setContentText(context.getString(R.string.push_generic_text))
            .setSubText(server.displayName)
            .setContentIntent(open(server.id, null, null, "generic:$deliveryId"))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base(PushChannels.SERVERS).setContentTitle(context.getString(R.string.push_generic_title)).build())
            .build()
        NotificationManagerCompat.from(context).notify("generic:${server.id}", NOTIFICATION_ID, notification)
        return true
    }

    /** Replaces a request notification with the outcome of its button, which then tidies itself away. */
    @SuppressLint("MissingPermission")
    override fun showActionResult(server: AdminServer?, tag: String, requestId: String, text: String, failed: Boolean) {
        if (!AlertNotifier.canPost(context)) return
        PushChannels.ensureAll(context)
        val builder = base(PushChannels.REQUESTS)
            .setContentTitle(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base(PushChannels.REQUESTS).setContentTitle(context.getString(R.string.push_lock_request)).build())
        if (server != null) builder.setContentIntent(open(server.id, "requests", requestId, tag))
        // A failed button stays until it is read; a done one goes after a moment.
        if (!failed) builder.setTimeoutAfter(RESULT_LIFETIME_MILLIS)
        NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build())
    }

    private fun build(server: AdminServer, content: PushContent, tag: String): Notification {
        val details = alerts.showDetailsOnLockScreen.value
        val builder = base(content.channel)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setSubText(server.displayName)
            .setGroup("push:${server.id}:${content.kind}")
            .setContentIntent(open(server.id, content.page, content.itemId, tag))
        content.badge?.let { builder.setNumber(it) }
        if (details) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            // What anyone sees on a locked screen: the server's lock-screen text and nothing else.
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(base(content.channel).setContentTitle(content.lockScreen).build())
        }
        if (content.kind == PushKind.REQUEST_WAITING && content.itemId != null) {
            if (PushContent.ACTION_APPROVE in content.actions) {
                builder.addAction(action(R.drawable.lucide_check, R.string.push_action_approve, PushActionReceiver.ACTION_APPROVE, server.id, content.itemId, tag))
            }
            if (PushContent.ACTION_DECLINE in content.actions) {
                builder.addAction(action(R.drawable.lucide_x, R.string.push_action_decline, PushActionReceiver.ACTION_DECLINE, server.id, content.itemId, tag))
            }
        }
        return builder.build()
    }

    private fun base(channelId: String): NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_bloem)
        .setColor(VeilPalette.Dark.accent.toArgb())
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)

    private fun open(serverId: String, page: String?, itemId: String?, tag: String): PendingIntent = PendingIntent.getActivity(
        context, ("open:$tag").hashCode(), openIntent(context, serverId, page, itemId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(icon: Int, label: Int, name: String, serverId: String, requestId: String, tag: String): NotificationCompat.Action {
        val intent = Intent(context, PushActionReceiver::class.java)
            .setAction(name)
            .putExtra(PushActionReceiver.EXTRA_SERVER, serverId)
            .putExtra(PushActionReceiver.EXTRA_REQUEST, requestId)
            .putExtra(PushActionReceiver.EXTRA_TAG, tag)
        val pending = PendingIntent.getBroadcast(
            context, ("$name:$tag").hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(icon, context.getString(label), pending)
            // Android runs it only on an unlocked device; the lock screen asks for the unlock first.
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 20
        private const val RESULT_LIFETIME_MILLIS = 8_000L

        /**
         * One notification per server, kind and item, so a repeated event updates the one showing instead of stacking, the
         * same collapsing the server asks of the relay.
         */
        fun tagFor(serverId: String, kind: String, itemId: String?): String = "push:$serverId:$kind:${itemId.orEmpty()}"

        /** What a tap launches: this app, with a link naming the saved server, the page and the item, and nothing else. */
        internal fun openIntent(context: Context, serverId: String, page: String?, itemId: String?): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(DeepLink.build(serverId, page, itemId)), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
