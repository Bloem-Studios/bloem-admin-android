package com.bloemstudios.admin.shortcuts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.servers.AdminServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShortcutSpecsTest {
    private val a = AdminServer("a", "https://a.test", "Bloem Home")
    private val b = AdminServer("b", "https://b.test", "Lab")
    private val c = AdminServer("c", "https://c.test", "Garden")

    private lateinit var context: Context

    @Before fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("recent_servers_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun recents() = RecentServers(context.getSharedPreferences("recent_servers_test", Context.MODE_PRIVATE))

    @Test fun eachRecentServerGetsNowPlayingAndRequests() {
        val specs = dynamicShortcutSpecs(listOf(a, b), recentIds = listOf("b", "a"))
        assertEquals(
            listOf("server:b:now-playing", "server:b:requests", "server:a:now-playing", "server:a:requests"),
            specs.map { it.id },
        )
        assertEquals(listOf("b", "b", "a", "a"), specs.map { it.serverId })
        assertEquals(listOf("now-playing", "requests", "now-playing", "requests"), specs.map { it.slug })
        assertEquals(listOf("Lab", "Lab", "Bloem Home", "Bloem Home"), specs.map { it.serverName })
    }

    @Test fun onlyTheTwoMostRecentServersAreOffered() {
        val specs = dynamicShortcutSpecs(listOf(a, b, c), recentIds = listOf("c", "a", "b"))
        assertEquals(setOf("c", "a"), specs.map { it.serverId }.toSet())
        assertEquals(4, specs.size)
    }

    @Test fun aServerThatWasRemovedIsNotOffered() {
        val specs = dynamicShortcutSpecs(listOf(a), recentIds = listOf("gone", "a"))
        assertEquals(setOf("a"), specs.map { it.serverId }.toSet())
    }

    @Test fun noRecentServersMeansNoDynamicShortcuts() {
        assertTrue(dynamicShortcutSpecs(listOf(a, b), recentIds = emptyList()).isEmpty())
        assertTrue(dynamicShortcutSpecs(emptyList(), recentIds = listOf("a")).isEmpty())
    }

    @Test fun theLinkOfEachShortcutPointsAtItsServerAndPage() {
        val spec = dynamicShortcutSpecs(listOf(a), listOf("a")).first()
        assertEquals("bloemadmin://server/a/now-playing", spec.link)
    }

    @Test fun openingAServerMovesItToTheFront() {
        val r = recents()
        r.touch("a"); r.touch("b"); r.touch("a")
        assertEquals(listOf("a", "b"), recents().ids())
    }

    @Test fun onlyTheLastFiveAreKept() {
        val r = recents()
        listOf("1", "2", "3", "4", "5", "6").forEach(r::touch)
        assertEquals(listOf("6", "5", "4", "3", "2"), recents().ids())
    }

    @Test fun removedServersAreForgotten() {
        val r = recents()
        r.touch("a"); r.touch("gone")
        r.retain(setOf("a"))
        assertEquals(listOf("a"), recents().ids())
    }
}
