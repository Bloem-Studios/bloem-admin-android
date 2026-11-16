package com.bloemstudios.admin.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ServerCheckSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        listOf(AlertPreferences.FILE, SurfacePreferences.FILE, ServerStatusStore.FILE).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun tearDown() {
        ServerCheckRuntime.checkerFactory = null
        com.bloemstudios.admin.widget.WidgetPlacement.widgetIds = { com.bloemstudios.admin.widget.SystemWidgetIds(it) }
        ServerCheckRuntime.afterRun = { com.bloemstudios.admin.widget.SurfaceRefresher.refreshAll(it) }
    }

    private fun periodic(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(ServerCheckScheduler.UNIQUE_PERIODIC).get()
        .filter { it.state != WorkInfo.State.CANCELLED }

    private fun alertsOnFor(vararg ids: String) {
        val alerts = AlertsRuntime.alertPreferences(context)
        alerts.setEnabled(true)
        ids.forEach { alerts.setServerEnabled(it, true) }
    }

    @Test fun nothingIsScheduledWhileEverythingIsOff() {
        ServerCheckScheduler.reconcile(context)
        assertTrue(periodic().isEmpty())
    }

    @Test fun switchingAnAlertOnSchedulesOnePeriodicCheckEveryFifteenMinutes() {
        alertsOnFor("a")
        ServerCheckScheduler.reconcile(context)
        val info = periodic().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test fun theCheckNeedsANetworkAndAHealthyBattery() {
        alertsOnFor("a")
        ServerCheckScheduler.reconcile(context)
        val constraints = periodic().single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging())
    }

    @Test fun reconcilingAgainKeepsTheSameWorkInsteadOfStackingMore() {
        alertsOnFor("a")
        ServerCheckScheduler.reconcile(context)
        val first = periodic().single().id
        ServerCheckScheduler.reconcile(context)
        ServerCheckScheduler.reconcile(context)
        assertEquals(first, periodic().single().id)
    }

    @Test fun aServerSwitchWithTheMasterOffSchedulesNothing() {
        val alerts = AlertsRuntime.alertPreferences(context)
        alerts.setServerEnabled("a", true)
        ServerCheckScheduler.reconcile(context)
        assertTrue(periodic().isEmpty())
    }

    @Test fun switchingEverythingOffCancelsTheSchedule() {
        alertsOnFor("a")
        ServerCheckScheduler.reconcile(context)
        assertEquals(1, periodic().size)
        AlertsRuntime.alertPreferences(context).setEnabled(false)
        ServerCheckScheduler.reconcile(context)
        assertTrue(periodic().isEmpty())
    }

    /** The widgets the system says exist (the real answer comes from the launcher). */
    private fun placed(vararg widgets: Pair<com.bloemstudios.admin.widget.WidgetKind, Int>) {
        com.bloemstudios.admin.widget.WidgetPlacement.widgetIds = { _ ->
            com.bloemstudios.admin.widget.WidgetIds { kind -> widgets.filter { it.first == kind }.map { it.second }.toSet() }
        }
    }

    @Test fun aServerStatusWidgetOrTheTileKeepsTheCheckRunningWithoutAlerts() {
        placed(com.bloemstudios.admin.widget.WidgetKind.ServerStatus to 7)
        ServerCheckScheduler.reconcile(context)
        assertEquals(1, periodic().size)
        placed()
        AlertsRuntime.surfacePreferences(context).tileAdded = true
        ServerCheckScheduler.reconcile(context)
        assertEquals(1, periodic().size)
        AlertsRuntime.surfacePreferences(context).tileAdded = false
        ServerCheckScheduler.reconcile(context)
        assertTrue(periodic().isEmpty())
    }

    @Test fun theDetailWidgetsDoNotNeedTheServerCheckBecauseTheyHaveTheirOwnJob() {
        placed(com.bloemstudios.admin.widget.WidgetKind.Requests to 9, com.bloemstudios.admin.widget.WidgetKind.NowPlaying to 10)
        ServerCheckScheduler.reconcile(context)
        assertTrue("health probes and counts are for alerts, Server status and the tile", periodic().isEmpty())
    }

    @Test fun startingTheAppTouchesNothingWhenNothingIsWanted() {
        ServerCheckScheduler.reconcile(context, touchWhenUnwanted = false)
        assertTrue(periodic().isEmpty())
    }

    // The worker.

    private fun fakeChecker(keep: Boolean, calls: MutableList<String> = mutableListOf()): ServerChecker {
        return ServerChecker(
            servers = { calls += "servers"; if (keep) listOf(AdminServer("a", "https://a.test", "A")) else emptyList() },
            alerts = AlertsRuntime.alertPreferences(context),
            surfaces = AlertsRuntime.surfacePreferences(context),
            statuses = AlertsRuntime.statusStore(context),
            probe = { calls += "probe"; true },
            counts = { CountsResult.Unavailable },
            sink = object : AlertSink {
                override fun down(server: AdminServer, failingSince: Long?, failures: Int) = Unit
                override fun back(server: AdminServer) = Unit
                override fun clear(serverId: String) = Unit
            },
        )
    }

    @Test fun theWorkerRunsTheCheckerAndRefreshesTheWidgetAndTile() {
        alertsOnFor("a")
        val calls = mutableListOf<String>()
        ServerCheckRuntime.checkerFactory = { fakeChecker(keep = true, calls) }
        var refreshed = 0
        ServerCheckRuntime.afterRun = { refreshed += 1 }
        val result = runBlocking { TestListenableWorkerBuilder<ServerCheckWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("servers", "probe"), calls)
        assertEquals(1, refreshed)
    }

    @Test fun whenTheLastWatchedServerIsGoneTheWorkerStopsTheSchedule() {
        alertsOnFor("a")
        ServerCheckScheduler.reconcile(context)
        assertEquals(1, periodic().size)
        ServerCheckRuntime.checkerFactory = { fakeChecker(keep = false) }
        ServerCheckRuntime.afterRun = {}
        runBlocking { TestListenableWorkerBuilder<ServerCheckWorker>(context).build().doWork() }
        assertTrue(periodic().isEmpty())
    }
}
