package com.bloemstudios.admin.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerCheckerTest {
    private val home = AdminServer("a", "https://home.test", "Bloem Home")
    private val lab = AdminServer("b", "https://lab.test", "Lab")

    private lateinit var context: Context
    private lateinit var alerts: AlertPreferences
    private lateinit var surfaces: SurfacePreferences
    private lateinit var statuses: ServerStatusStore
    private var saved = listOf(home, lab)
    private var now = 1_000L

    private val answers = mutableMapOf("https://home.test" to true, "https://lab.test" to true)
    private val probed = mutableListOf<String>()
    private val countCalls = mutableListOf<String>()
    private var counts: (AdminServer) -> CountsResult = { CountsResult.Known(streams = 2, requests = 3) }
    private val sink = RecordingSink()

    private class RecordingSink : AlertSink {
        val events = mutableListOf<String>()
        override fun down(server: AdminServer, failingSince: Long?, failures: Int) { events += "down:${server.id}" }
        override fun back(server: AdminServer) { events += "back:${server.id}" }
        override fun clear(serverId: String) { events += "clear:$serverId" }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("checker_alerts", "checker_status", "checker_surfaces").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        alerts = AlertPreferences(context.getSharedPreferences("checker_alerts", Context.MODE_PRIVATE))
        statuses = ServerStatusStore(context.getSharedPreferences("checker_status", Context.MODE_PRIVATE))
        surfaces = SurfacePreferences(context.getSharedPreferences("checker_surfaces", Context.MODE_PRIVATE))
    }

    private fun checker() = ServerChecker(
        servers = { saved },
        alerts = alerts,
        surfaces = surfaces,
        statuses = statuses,
        probe = { baseUrl -> probed += baseUrl; answers.getValue(baseUrl) },
        counts = { server -> countCalls += server.id; counts(server) },
        sink = sink,
        clock = { now },
    )

    private fun run(): Boolean = runBlocking { checker().runOnce() }

    private fun alertOn(vararg ids: String) {
        alerts.setEnabled(true)
        ids.forEach { alerts.setServerEnabled(it, true) }
    }

    @Test fun nothingSwitchedOnMeansNoNetworkAndNoRerun() {
        assertFalse(run())
        assertTrue(probed.isEmpty())
        assertTrue(sink.events.isEmpty())
    }

    @Test fun onlyServersSwitchedOnAreChecked() {
        alertOn("a")
        assertTrue(run())
        assertEquals(listOf("https://home.test"), probed)
    }

    @Test fun oneMissIsQuietAndTwoRaiseOneAlert() {
        alertOn("a")
        answers["https://home.test"] = false
        run()
        assertTrue(sink.events.isEmpty())
        now += 900_000
        run()
        assertEquals(listOf("down:a"), sink.events)
        run()
        assertEquals(listOf("down:a"), sink.events)
    }

    @Test fun aServerThatFlapsNeverRaisesAnAlert() {
        alertOn("a")
        listOf(false, true, false, true, false).forEach { up ->
            answers["https://home.test"] = up
            run()
        }
        assertTrue(sink.events.isEmpty())
    }

    @Test fun recoveryRaisesOneBackNote() {
        alertOn("a")
        answers["https://home.test"] = false
        run(); run()
        answers["https://home.test"] = true
        run(); run()
        assertEquals(listOf("down:a", "back:a"), sink.events)
    }

    @Test fun theMasterSwitchOffStopsEveryAlertButKeepsWidgetData() {
        alertOn("a")
        surfaces.setWidgetTarget(1, "a")
        alerts.setEnabled(false)
        answers["https://home.test"] = false
        run(); run(); run()
        assertTrue(sink.events.isEmpty())
        assertEquals(false, statuses.records().getValue("a").status.reachable)
    }

    @Test fun anAlertForAServerSwitchedOffIsTakenDownOnTheNextRun() {
        alertOn("a")
        answers["https://home.test"] = false
        run(); run()
        alerts.setServerEnabled("a", false)
        surfaces.setWidgetTarget(1, null)
        run()
        assertEquals(listOf("down:a", "clear:a"), sink.events)
    }

    @Test fun anAlertForAServerNoLongerWatchedAtAllIsStillTakenDown() {
        alertOn("a")
        answers["https://home.test"] = false
        run(); run()
        alerts.setEnabled(false)
        assertFalse(run())
        assertEquals(listOf("down:a", "clear:a"), sink.events)
    }

    @Test fun aRemovedServerIsForgottenAndItsAlertIsTakenDown() {
        alertOn("a", "b")
        answers["https://lab.test"] = false
        run(); run()
        assertEquals(listOf("down:b"), sink.events)
        saved = listOf(home)
        probed.clear()
        run()
        assertEquals(listOf("clear:b"), sink.events.drop(1))
        assertEquals(listOf("https://home.test"), probed)
        assertEquals(setOf("a"), statuses.records().keys)
        assertEquals(setOf("a"), alerts.enabledServerIds.value)
    }

    @Test fun whenTheLastWatchedServerIsRemovedTheRunSaysToStop() {
        alertOn("b")
        saved = listOf(home)
        assertFalse(run())
        assertTrue(probed.isEmpty())
    }

    @Test fun countsAreReadOnlyForServersAWidgetOrTheTileShows() {
        alertOn("a", "b")
        surfaces.setWidgetTarget(1, "b")
        run()
        assertEquals(listOf("b"), countCalls)
        val a = statuses.records().getValue("a").status
        assertEquals(CountsStatus.NotChecked, a.counts)
        val b = statuses.records().getValue("b").status
        assertEquals(CountsStatus.Known, b.counts)
        assertEquals(2, b.streams)
        assertEquals(3, b.requests)
    }

    @Test fun anUnreachableServerIsNotAskedForCounts() {
        surfaces.setWidgetTarget(1, null)
        answers["https://lab.test"] = false
        run()
        assertEquals(listOf("a"), countCalls)
        assertEquals(false, statuses.records().getValue("b").status.reachable)
    }

    @Test fun aMissingSessionIsRecordedAsSignInNeeded() {
        surfaces.setWidgetTarget(1, "a")
        counts = { CountsResult.SignInNeeded }
        run()
        val status = statuses.records().getValue("a").status
        assertEquals(CountsStatus.SignInNeeded, status.counts)
        assertNull(status.streams)
    }

    @Test fun aServerThatCannotGiveCountsIsRecordedAsUnavailable() {
        surfaces.setWidgetTarget(1, "a")
        counts = { CountsResult.Unavailable }
        run()
        assertEquals(CountsStatus.Unavailable, statuses.records().getValue("a").status.counts)
    }

    @Test fun oneServersFailureNeverStopsTheOthers() {
        alertOn("a", "b")
        surfaces.setWidgetTarget(1, null)
        counts = { server -> if (server.id == "a") throw IllegalStateException("boom") else CountsResult.Known(1, 1) }
        run()
        assertEquals(CountsStatus.Unavailable, statuses.records().getValue("a").status.counts)
        assertEquals(CountsStatus.Known, statuses.records().getValue("b").status.counts)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotSwallowed() {
        alertOn("a")
        runBlocking {
            ServerChecker(
                servers = { saved }, alerts = alerts, surfaces = surfaces, statuses = statuses,
                probe = { throw CancellationException("stopped") }, counts = { CountsResult.Unavailable },
                sink = sink, clock = { now },
            ).runOnce()
        }
    }

    @Test fun theCheckTimeIsRecorded() {
        alertOn("a")
        now = 4_242L
        run()
        assertEquals(4_242L, statuses.records().getValue("a").status.checkedAt)
    }
}
