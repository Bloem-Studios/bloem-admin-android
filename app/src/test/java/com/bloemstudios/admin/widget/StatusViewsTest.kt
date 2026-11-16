package com.bloemstudios.admin.widget

import com.bloemstudios.admin.alerts.CountsStatus
import com.bloemstudios.admin.alerts.ReachabilityState
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.alerts.ServerStatus
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.servers.AdminServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extended Server status widget: one row per server, and the tally dot. */
class StatusViewsTest {
    private val now = 50_000_000L
    private val home = AdminServer("a", "https://home.test", "Home")
    private val lab = AdminServer("b", "https://lab.test", "Lab")
    private val office = AdminServer("c", "https://office.test", "Office")
    private val saved = listOf(home, lab, office)

    private fun up(streams: Int, requests: Int = 0, at: Long = now - 60_000L) =
        ServerRecord(ServerStatus(true, at, CountsStatus.Known, streams, requests))

    private fun down(at: Long = now - 60_000L, since: Long = now - 600_000L) =
        ServerRecord(ServerStatus(false, at), ReachabilityState(failures = 3, failingSince = since))

    private fun content(state: WidgetState<ServerStatusView>) = (state as WidgetState.Content).data

    @Test fun eachServerGetsARowWithItsStreamCount() {
        val view = content(StatusViews.map(null, saved, mapOf("a" to up(3, 2), "b" to up(0, 1), "c" to up(1)), now))
        assertEquals(setOf("Home", "Lab", "Office"), view.rows.map { it.name }.toSet())
        assertEquals(3, view.rows.first { it.name == "Home" }.streams)
        assertEquals(4, view.streams)
        assertEquals(3, view.requests)
    }

    @Test fun aServerThatIsDownComesFirstAndLightsTheTally() {
        val view = content(StatusViews.map(null, saved, mapOf("a" to up(0), "b" to down(), "c" to up(0)), now))
        assertEquals("Lab", view.rows.first().name)
        assertEquals(Reach.NotReachable, view.rows.first().reach)
        assertEquals(now - 600_000L, view.rows.first().downSince)
        assertEquals(1, view.down)
        assertTrue(view.tally)
    }

    @Test fun aStreamPlayingAlsoLightsTheTally() {
        assertTrue(content(StatusViews.map(null, saved, mapOf("a" to up(1), "b" to up(0), "c" to up(0)), now)).tally)
    }

    @Test fun allUpAndQuietLeavesTheTallyOff() {
        assertFalse(content(StatusViews.map(null, saved, mapOf("a" to up(0), "b" to up(0), "c" to up(0)), now)).tally)
    }

    @Test fun oneServerKeepsTheLongStandingSummary() {
        val view = content(StatusViews.map("a", saved, mapOf("a" to up(2, 3)), now))
        assertEquals(WidgetModel.Single("Home", Reach.Reachable, WidgetCounts.Known(2, 3)), view.summary)
        assertEquals(1, view.rows.size)
    }

    @Test fun nothingCheckedYetIsLoadingNotNotReachable() {
        assertEquals(WidgetState.Loading, StatusViews.map(null, saved, emptyMap(), now))
        assertEquals(WidgetState.Loading, StatusViews.map("a", saved, emptyMap(), now))
    }

    @Test fun aRemovedServerAndNoServersHaveTheirOwnStates() {
        assertEquals(WidgetState.ServerRemoved, StatusViews.map("zzz", saved, emptyMap(), now))
        assertEquals(WidgetState.NoServers, StatusViews.map(null, emptyList(), emptyMap(), now))
    }

    @Test fun aMissingSessionStillShowsThatTheServerAnswers() {
        val record = ServerRecord(ServerStatus(true, now, CountsStatus.SignInNeeded))
        val view = content(StatusViews.map("a", saved, mapOf("a" to record), now))
        assertEquals(WidgetCounts.SignInNeeded, view.rows.single().counts)
        assertFalse(view.hasCounts)
    }

    @Test fun anOldCheckIsMarkedWithItsTimeAndAFreshOneIsNot() {
        val old = now - WidgetViews.FRESH_WINDOW_MILLIS - 1
        assertEquals(old, content(StatusViews.map(null, saved, mapOf("a" to up(0, at = old), "b" to up(0), "c" to up(0)), now)).staleAt)
        assertNull(content(StatusViews.map(null, saved, mapOf("a" to up(0), "b" to up(0), "c" to up(0)), now)).staleAt)
    }

    @Test fun linksOpenNowPlayingAndRequestsOnTheRightServer() {
        val one = content(StatusViews.map("a", saved, mapOf("a" to up(0)), now))
        assertEquals(DeepLink.build("a", "now-playing"), one.streamsLink)
        assertEquals(DeepLink.build("a", "requests"), one.requestsLink)
        assertEquals(DeepLink.build("a", "now-playing"), one.rows.single().link)
        val all = content(StatusViews.map(null, saved, mapOf("a" to up(0), "b" to up(0), "c" to up(0)), now))
        assertEquals(DeepLink.buildAll("now-playing"), all.streamsLink)
        assertEquals(DeepLink.build("b", "now-playing"), all.rows.first { it.name == "Lab" }.link)
    }

    @Test fun theRequestsTileSumsWaitingRequestsOfServersThatAnsweredRecently() {
        val old = now - TileModels.STALE_AFTER_MILLIS - 1
        assertEquals(TileModel.Waiting(5), TileModels.mapRequests(mapOf("a" to up(0, 2), "b" to up(0, 3), "c" to up(0, 9, at = old)), now))
        assertEquals(TileModel.Waiting(0), TileModels.mapRequests(mapOf("a" to up(4, 0)), now))
        assertEquals(TileModel.NoData, TileModels.mapRequests(emptyMap(), now))
        assertEquals(TileModel.NoData, TileModels.mapRequests(mapOf("a" to down()), now))
    }
}
