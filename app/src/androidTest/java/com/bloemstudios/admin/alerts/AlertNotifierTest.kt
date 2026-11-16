package com.bloemstudios.admin.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.servers.AdminServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real notification service: what the alert says, what the lock screen shows, and that it goes away. */
@RunWith(AndroidJUnit4::class)
class AlertNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val server = AdminServer("alert-test", "https://alert-test.example.test", "Bloem Home")
    private val prefs = context.getSharedPreferences("alert-notifier-test", Context.MODE_PRIVATE)
    private lateinit var alerts: AlertPreferences

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().clear().commit()
        alerts = AlertPreferences(prefs)
        manager.cancelAll()
    }

    @After fun tearDown() {
        manager.cancelAll()
        context.deleteSharedPreferences("alert-notifier-test")
    }

    private fun current(): Notification? = manager.activeNotifications.firstOrNull { it.tag == server.id }?.notification

    /** The notification service works asynchronously: wait (up to five seconds) for [done], then return what is showing. */
    private fun settle(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    private fun posted(): Notification? { settle { current() != null }; return current() }
    private fun gone(): Notification? { settle { current() == null }; return current() }

    @Test fun theChannelKeepsItsIdAndIsNamedServers() {
        // The id is unchanged so a user's own choices for the channel survive; the name is the section's.
        AlertNotifier.ensureChannel(context)
        assertEquals("server_reachability", AlertNotifier.CHANNEL_ID)
        assertEquals("Servers", manager.getNotificationChannel(AlertNotifier.CHANNEL_ID)?.name)
    }

    @Test fun aDownAlertNamesTheServerAndShowsOnlyThatOnTheLockScreenByDefault() {
        AlertNotifier(context, alerts).down(server, failingSince = 1_000L, failures = 2)
        val notification = posted()
        assertNotNull(notification)
        assertEquals("Can't reach Bloem Home", notification!!.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        assertEquals("Can't reach Bloem Home", publicVersion!!.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Tap to open the server", publicVersion.extras.getString(Notification.EXTRA_TEXT))
        // Unlocked, it also says when the server stopped answering.
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("No answer since "))
    }

    @Test fun theLockScreenShowsDetailsOnlyWhenTheAdminSwitchedThemOn() {
        alerts.setShowDetailsOnLockScreen(true)
        AlertNotifier(context, alerts).down(server, failingSince = 1_000L, failures = 2)
        val notification = posted()!!
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertNull(notification.publicVersion)
    }

    @Test fun anAlertNeverCarriesAnAddressOrAToken() {
        AlertNotifier(context, alerts).down(server, failingSince = 1_000L, failures = 2)
        val extras = posted()!!.extras
        val text = extras.keySet().joinToString { extras.get(it).toString() }
        assertFalse(text.contains("example.test"))
        assertFalse(text.contains("https"))
    }

    @Test fun backReplacesTheDownAlertForThatServer() {
        val notifier = AlertNotifier(context, alerts)
        notifier.down(server, 1_000L, 2)
        notifier.back(server)
        settle { current()?.extras?.getString(Notification.EXTRA_TITLE) == "Bloem Home is reachable again" }
        assertEquals(1, manager.activeNotifications.count { it.tag == server.id })
        assertEquals("Bloem Home is reachable again", current()!!.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun clearTakesTheAlertDown() {
        val notifier = AlertNotifier(context, alerts)
        notifier.down(server, 1_000L, 2)
        posted()
        notifier.clear(server.id)
        assertNull(gone())
    }

    @Test fun clearAllTakesEveryAlertDown() {
        val notifier = AlertNotifier(context, alerts)
        notifier.down(server, 1_000L, 2)
        notifier.down(AdminServer("alert-test-2", "https://two.example.test", "Lab"), 1_000L, 2)
        settle { manager.activeNotifications.count { it.id == 1 } == 2 }
        notifier.clearAll()
        settle { manager.activeNotifications.none { it.id == 1 } }
        assertTrue(manager.activeNotifications.none { it.id == 1 })
    }
}
