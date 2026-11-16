package com.bloemstudios.admin.push

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.alerts.AlertPreferences
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.links.DeepLinkTarget
import com.bloemstudios.admin.servers.AdminServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PushNotifierTest {
    private lateinit var context: Context
    private lateinit var alerts: AlertPreferences
    private lateinit var notifier: PushNotifier
    private lateinit var manager: NotificationManager
    private val server = AdminServer("srv-1", "https://bloem.example.test", "Home")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("push_notifier_alerts", Context.MODE_PRIVATE).edit().clear().commit()
        alerts = AlertPreferences(context.getSharedPreferences("push_notifier_alerts", Context.MODE_PRIVATE))
        notifier = PushNotifier(context, alerts)
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun request(id: String = "req-1", badge: Int? = 3) = PushContent(
        "d1", PushKind.REQUEST_WAITING, "requests", "sam requested Dune", "3 waiting", "New request waiting",
        "requests", id, "request", listOf("approve", "decline"), badge,
    ).sanitized()!!

    private fun backup() = PushContent(
        "d2", PushKind.BACKUP_FAILED, "backups", "Backup failed on Home", "disk full", "A backup failed",
        "backups", "backups", "backup", emptyList(), null,
    ).sanitized()!!

    private fun posted(): List<android.service.notification.StatusBarNotification> = manager.activeNotifications.toList()

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun byDefaultTheLockScreenShowsOnlyTheServersLockScreenText() {
        assertTrue(notifier.post(server, request()))
        val n = posted().single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull("a private notification needs a public version", public)
        assertEquals("New request waiting", public.title())
        assertNull("the public version carries no body", public.text())
        // Nothing of the requester, the title or the count in any text the public version carries.
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { public.extras.getCharSequence(it)?.toString() }
        assertEquals(listOf("New request waiting"), texts)
        assertFalse(texts.any { it.contains("sam") || it.contains("Dune") || it.contains("3 waiting") || it.contains("Home") })
        assertEquals("sam requested Dune", n.title())
        assertEquals("3 waiting", n.text())
    }

    @Test fun withShowDetailsOnTheNotificationIsPublic_andNeedsNoSeparateVersion() {
        alerts.setShowDetailsOnLockScreen(true)
        notifier.post(server, request())
        val n = posted().single().notification
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertNull(n.publicVersion)
    }

    @Test fun aRequestNoticeHasApproveAndDeclineThatNeedAnUnlockedDevice() {
        notifier.post(server, request())
        val actions = posted().single().notification.actions
        assertEquals(listOf("Approve", "Decline"), actions.map { it.title.toString() })
        val compat = NotificationCompat.getAction(posted().single().notification, 0)!!
        assertTrue("Approve must require the device to be unlocked", compat.isAuthenticationRequired)
        assertTrue("Decline must require the device to be unlocked", NotificationCompat.getAction(posted().single().notification, 1)!!.isAuthenticationRequired)
        assertFalse(compat.showsUserInterface)
    }

    @Test fun theButtonsCarryOnlyTheServerTheRequestAndTheTagToTheAppsOwnReceiver() {
        notifier.post(server, request("req-9"))
        val approve = shadowOf(posted().single().notification.actions[0].actionIntent)
        assertTrue(approve.isBroadcast)
        val intent = approve.savedIntent
        assertEquals(PushActionReceiver.ACTION_APPROVE, intent.action)
        assertEquals(PushActionReceiver::class.java.name, intent.component?.className)
        assertEquals("srv-1", intent.getStringExtra(PushActionReceiver.EXTRA_SERVER))
        assertEquals("req-9", intent.getStringExtra(PushActionReceiver.EXTRA_REQUEST))
        assertEquals(PushNotifier.tagFor("srv-1", PushKind.REQUEST_WAITING, "req-9"), intent.getStringExtra(PushActionReceiver.EXTRA_TAG))
        val decline = shadowOf(posted().single().notification.actions[1].actionIntent).savedIntent
        assertEquals(PushActionReceiver.ACTION_DECLINE, decline.action)
        // Immutable: another app cannot rewrite which request the button decides.
        assertTrue(shadowOf(posted().single().notification.actions[0].actionIntent).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test fun otherKindsOfferNoButtons_andNothingDestructiveIsEverOffered() {
        notifier.post(server, backup())
        assertNull(posted().single().notification.actions)
        val labels = PushKinds.all.flatMap { kind ->
            val c = PushContent("d", kind.id, "", "t", "b", "l", "requests", "x", "s", listOf("approve", "decline", "delete", "restart", "retry"), null).sanitized()
            c?.actions.orEmpty()
        }.toSet()
        assertEquals(setOf("approve", "decline"), labels)
    }

    @Test fun aTapOpensTheExactItemThroughTheAppsOwnLink() {
        notifier.post(server, request("req-42"))
        val tap = shadowOf(posted().single().notification.contentIntent).savedIntent
        assertEquals(android.content.Intent.ACTION_VIEW, tap.action)
        assertEquals(MainActivity::class.java.name, tap.component?.className)
        assertEquals(DeepLinkTarget("srv-1", "work.requests", "req-42"), DeepLink.parse(tap.dataString))
        assertNull("only the link travels", tap.extras)
    }

    @Test fun eachKindOpensItsOwnPage() {
        val cases = mapOf(
            PushKind.SCAN_FAILED to ("libraries" to "content.libraries"),
            PushKind.BACKUP_FAILED to ("backups" to "system.backups"),
            PushKind.NODE_OFFLINE to ("nodes" to "playback.nodes"),
            PushKind.PLUGIN_FAILED to ("plugins" to "system.plugins"),
        )
        for ((kind, pages) in cases) {
            val content = PushContent("d", kind, "", "t", "b", "l", pages.first, "7", "s", emptyList(), null).sanitized()!!
            val intent = PushNotifier.openIntent(context, "srv-1", content.page, content.itemId)
            assertEquals(DeepLinkTarget("srv-1", pages.second, "7"), DeepLink.parse(intent.dataString))
        }
    }

    @Test fun eachSectionPostsOnItsOwnChannel() {
        notifier.post(server, request())
        notifier.post(server, backup())
        val channels = posted().associate { it.notification.channelId to it.notification.title() }
        assertEquals(setOf(PushChannels.REQUESTS, PushChannels.BACKUPS), channels.keys)
        val names = PushChannels.ids.associateWith { manager.getNotificationChannel(it)?.name?.toString() }
        assertEquals(listOf("Servers", "Requests", "Libraries", "Backups", "Nodes", "Plugins"), PushChannels.ids.map { names[it] })
    }

    @Test fun aRepeatedEventForOneItemUpdatesTheNotificationInsteadOfStacking() {
        notifier.post(server, request("req-1", badge = 1))
        notifier.post(server, request("req-1", badge = 2))
        assertEquals(1, posted().size)
        notifier.post(server, request("req-2", badge = 3))
        assertEquals(2, posted().size)
    }

    @Test fun notificationsAreGroupedByServerAndKind() {
        notifier.post(server, request())
        assertEquals("push:srv-1:admin.request_waiting", posted().single().notification.group)
    }

    @Test fun theRequestCountBecomesTheAppIconNumber() {
        notifier.post(server, request(badge = 5))
        assertEquals(5, posted().single().notification.number)
    }

    @Test fun whenThePhoneCouldNotAskTheServerOnlyAGenericNoticeShows() {
        assertTrue(notifier.postGeneric(server, "d1"))
        val n = posted().single().notification
        assertEquals("New notification", n.title())
        assertEquals("Open the app to see it", n.text())
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("New notification", n.publicVersion.title())
        assertFalse(n.title()!!.contains("request", ignoreCase = true))
    }

    @Test fun nothingIsPostedWhenAndroidWouldNotShowIt() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.post(server, request()))
        assertFalse(notifier.postGeneric(server, "d1"))
        assertTrue(posted().isEmpty())
    }

    @Test fun aFailedButtonSaysSoAndStays_aDoneOneTidiesItselfAway() {
        val tag = PushNotifier.tagFor("srv-1", PushKind.REQUEST_WAITING, "req-1")
        notifier.showActionResult(server, tag, "req-1", "Couldn't approve, open the app", failed = true)
        val failed = posted().single().notification
        assertEquals("Couldn't approve, open the app", failed.title())
        assertEquals(0L, failed.timeoutAfter)
        notifier.showActionResult(server, tag, "req-1", "Approved", failed = false)
        val done = posted().single().notification
        assertEquals("Approved", done.title())
        assertTrue(done.timeoutAfter > 0)
        assertNull("a result carries no buttons", done.actions)
        assertEquals("Request update", done.publicVersion.title())
    }
}
