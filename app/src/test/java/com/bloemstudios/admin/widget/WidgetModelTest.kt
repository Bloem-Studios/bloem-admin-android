package com.bloemstudios.admin.widget

import com.bloemstudios.admin.alerts.CountsStatus
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.alerts.ServerStatus
import com.bloemstudios.admin.servers.AdminServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val home = AdminServer("a", "https://home.test", "Bloem Home")
    private val lab = AdminServer("b", "https://lab.test", "Lab")
    private val saved = listOf(home, lab)

    private fun known(streams: Int, requests: Int, reachable: Boolean = true) = ServerRecord(
        ServerStatus(reachable = reachable, checkedAt = 1L, counts = CountsStatus.Known, streams = streams, requests = requests),
    )

    @Test fun oneServerShowsItsNameReachabilityAndCounts() {
        val model = WidgetModels.map("a", saved, mapOf("a" to known(2, 3)))
        assertEquals(
            WidgetModel.Single("Bloem Home", Reach.Reachable, WidgetCounts.Known(streams = 2, requests = 3)),
            model,
        )
        assertTrue((model as WidgetModel.Single).live)
    }

    @Test fun theTallyDotIsLitOnlyWhileStreamsAreLive() {
        assertFalse((WidgetModels.map("a", saved, mapOf("a" to known(0, 3))) as WidgetModel.Single).live)
        assertTrue((WidgetModels.map("a", saved, mapOf("a" to known(1, 0))) as WidgetModel.Single).live)
    }

    @Test fun anUnreachableServerHidesCountsInsteadOfShowingOldOnes() {
        val model = WidgetModels.map("a", saved, mapOf("a" to known(5, 5, reachable = false))) as WidgetModel.Single
        assertEquals(Reach.NotReachable, model.reach)
        assertEquals(WidgetCounts.Hidden, model.counts)
        assertFalse(model.live)
    }

    @Test fun aMissingSessionSaysToSignIn() {
        val record = ServerRecord(ServerStatus(reachable = true, checkedAt = 1L, counts = CountsStatus.SignInNeeded))
        val model = WidgetModels.map("a", saved, mapOf("a" to record)) as WidgetModel.Single
        assertEquals(Reach.Reachable, model.reach)
        assertEquals(WidgetCounts.SignInNeeded, model.counts)
    }

    @Test fun countsTheServerCouldNotGiveAreUnavailable() {
        val record = ServerRecord(ServerStatus(reachable = true, checkedAt = 1L, counts = CountsStatus.Unavailable))
        assertEquals(WidgetCounts.Unavailable, (WidgetModels.map("a", saved, mapOf("a" to record)) as WidgetModel.Single).counts)
    }

    @Test fun aServerNeverCheckedIsNotChecked() {
        val model = WidgetModels.map("a", saved, emptyMap()) as WidgetModel.Single
        assertEquals(Reach.NotChecked, model.reach)
        assertEquals(WidgetCounts.Hidden, model.counts)
    }

    @Test fun aRemovedServerIsReportedAsRemoved() {
        assertEquals(WidgetModel.ServerRemoved, WidgetModels.map("gone", saved, emptyMap()))
    }

    @Test fun allServersAddsUpCountsAndReachability() {
        val model = WidgetModels.map(null, saved, mapOf("a" to known(2, 3), "b" to known(1, 4)))
        assertEquals(WidgetModel.All(servers = 2, reachable = 2, notReachable = 0, streams = 3, requests = 7, countedServers = 2), model)
        assertTrue((model as WidgetModel.All).live)
    }

    @Test fun allServersLeavesAnUnreachableServerOutOfTheTotals() {
        val model = WidgetModels.map(null, saved, mapOf("a" to known(2, 3), "b" to known(9, 9, reachable = false)))
        assertEquals(WidgetModel.All(servers = 2, reachable = 1, notReachable = 1, streams = 2, requests = 3, countedServers = 1), model)
    }

    @Test fun allServersWithNoSessionAnywhereAsksToSignIn() {
        val signIn = ServerRecord(ServerStatus(reachable = true, checkedAt = 1L, counts = CountsStatus.SignInNeeded))
        val model = WidgetModels.map(null, saved, mapOf("a" to signIn, "b" to signIn)) as WidgetModel.All
        assertEquals(0, model.countedServers)
        assertEquals(2, model.reachable)
    }

    @Test fun allServersWithNothingSavedIsEmpty() {
        assertEquals(WidgetModel.NoServers, WidgetModels.map(null, emptyList(), emptyMap()))
    }

    @Test fun theModelCarriesNothingButNamesAndCounts() {
        val text = WidgetModels.map("a", saved, mapOf("a" to known(2, 3))).toString()
        assertFalse(text.contains("http"))
        assertFalse(text.contains("home.test"))
    }
}
