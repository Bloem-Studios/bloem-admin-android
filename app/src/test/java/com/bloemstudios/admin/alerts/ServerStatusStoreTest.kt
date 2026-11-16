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
class ServerStatusStoreTest {
    private lateinit var context: Context

    @Before fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("status_store_test", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("surface_prefs_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = ServerStatusStore(context.getSharedPreferences("status_store_test", Context.MODE_PRIVATE))
    private fun surfaces() = SurfacePreferences(context.getSharedPreferences("surface_prefs_test", Context.MODE_PRIVATE))

    @Test fun recordsSurviveARestart() {
        val record = ServerRecord(
            ServerStatus(reachable = true, checkedAt = 5L, counts = CountsStatus.Known, streams = 2, requests = 3),
            ReachabilityState(failures = 1, failingSince = 4L),
        )
        store().put("a", record)
        assertEquals(mapOf("a" to record), store().records())
    }

    @Test fun retainDropsRemovedServersAndReportsThem() {
        val s = store()
        s.put("a", ServerRecord())
        s.put("gone", ServerRecord(reach = ReachabilityState(failures = 2, downNotified = true)))
        val removed = s.retain(setOf("a"))
        assertEquals(setOf("gone"), removed)
        assertEquals(setOf("a"), s.records().keys)
    }

    @Test fun unreadableStoredDataIsTreatedAsEmpty() {
        context.getSharedPreferences("status_store_test", Context.MODE_PRIVATE).edit().putString("records", "{not json").commit()
        assertTrue(store().records().isEmpty())
    }

    @Test fun nothingStoredHoldsATokenOrAnAddress() {
        store().put("a", ServerRecord(ServerStatus(reachable = true, counts = CountsStatus.Known, streams = 1, requests = 1)))
        val raw = context.getSharedPreferences("status_store_test", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("http"))
        assertFalse(raw.contains("token", ignoreCase = true))
    }

    @Test fun widgetTargetsAreRememberedPerWidgetAndForgottenWhenTheWidgetIsRemoved() {
        val s = surfaces()
        s.setWidgetTarget(11, "a")
        s.setWidgetTarget(12, null)
        assertEquals(mapOf(11 to "a", 12 to null), surfaces().widgetTargets())
        assertTrue(surfaces().hasWidget(12))
        s.removeWidgets(intArrayOf(11))
        assertEquals(mapOf(12 to null), surfaces().widgetTargets())
        assertFalse(surfaces().hasWidget(11))
    }

    @Test fun theTileFlagStartsOffAndFollowsTheTile() {
        val s = surfaces()
        assertFalse(s.tileAdded)
        s.tileAdded = true
        assertTrue(surfaces().tileAdded)
    }
}
