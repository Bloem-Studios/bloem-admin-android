package com.bloemstudios.admin.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivityScopePreferencesTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("activity_scope_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun freshInstallationOpensAllServersWithoutChoosingAnIndividual() {
        val settings = ActivityScopePreferences(preferences())
        assertTrue(settings.allServers.value)
        assertNull(settings.selectedServerId.value)
    }

    @Test fun explicitSelectionPersistsAndAggregatePreservesLastIndividual() {
        val prefs = preferences()
        val settings = ActivityScopePreferences(prefs)
        settings.selectServer("b")
        assertFalse(settings.allServers.value)
        assertEquals("b", settings.selectedServerId.value)
        val reopened = ActivityScopePreferences(prefs)
        assertFalse(reopened.allServers.value)
        assertEquals("b", reopened.selectedServerId.value)
        reopened.selectAllServers()
        val aggregate = ActivityScopePreferences(prefs)
        assertTrue(aggregate.allServers.value)
        assertEquals("b", aggregate.selectedServerId.value)
    }

    @Test fun coldLaunchRequiresAnExplicitValidIndividualSelection() {
        assertEquals("allServers", activityStartRoute(true, "a", setOf("a")))
        assertEquals("adminGate/a", activityStartRoute(false, "a", setOf("a", "b")))
        assertEquals("allServers", activityStartRoute(false, "removed", setOf("a")))
        assertEquals("allServers", activityStartRoute(false, null, emptySet()))
        assertEquals("allServers", activityStartRoute(false, "a", emptySet()))
        assertEquals("allServers", activityStartRoute(false, null, setOf("a")))
    }
}
