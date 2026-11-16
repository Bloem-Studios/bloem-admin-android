package com.bloemstudios.admin.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertPreferencesTest {
    private lateinit var context: Context

    private fun prefs() = AlertPreferences(context.getSharedPreferences("alert_preferences_test", Context.MODE_PRIVATE))

    @Before fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("alert_preferences_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun everythingIsOffUntilTheAdminTurnsItOn() {
        val alerts = prefs()
        assertFalse(alerts.enabled.value)
        assertFalse(alerts.showDetailsOnLockScreen.value)
        assertFalse(alerts.isServerEnabled("a"))
        assertTrue(alerts.alertedServerIds(setOf("a", "b")).isEmpty())
    }

    @Test fun aServerSwitchDoesNothingWhileTheMasterSwitchIsOff() {
        val alerts = prefs()
        alerts.setServerEnabled("a", true)
        assertTrue(alerts.isServerEnabled("a"))
        assertTrue(alerts.alertedServerIds(setOf("a")).isEmpty())
        alerts.setEnabled(true)
        assertEquals(setOf("a"), alerts.alertedServerIds(setOf("a", "b")))
    }

    @Test fun choicesSurviveARestart() {
        prefs().apply { setEnabled(true); setServerEnabled("a", true); setShowDetailsOnLockScreen(true) }
        val again = prefs()
        assertTrue(again.enabled.value)
        assertTrue(again.showDetailsOnLockScreen.value)
        assertEquals(setOf("a"), again.enabledServerIds.value)
    }

    @Test fun switchingAServerOffRemovesIt() {
        val alerts = prefs()
        alerts.setServerEnabled("a", true)
        alerts.setServerEnabled("a", false)
        assertFalse(prefs().isServerEnabled("a"))
    }

    @Test fun aRemovedServerIsForgotten() {
        val alerts = prefs()
        alerts.setEnabled(true)
        alerts.setServerEnabled("a", true)
        alerts.setServerEnabled("gone", true)
        alerts.retainServers(setOf("a"))
        assertEquals(setOf("a"), prefs().enabledServerIds.value)
    }

    @Test fun anAlertedServerMustStillBeSaved() {
        val alerts = prefs()
        alerts.setEnabled(true)
        alerts.setServerEnabled("gone", true)
        assertTrue(alerts.alertedServerIds(setOf("a")).isEmpty())
    }
}
