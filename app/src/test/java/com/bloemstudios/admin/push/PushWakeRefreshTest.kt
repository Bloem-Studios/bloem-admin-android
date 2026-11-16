package com.bloemstudios.admin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.widget.CachedSlice
import com.bloemstudios.admin.widget.LibrariesData
import com.bloemstudios.admin.widget.NodesData
import com.bloemstudios.admin.widget.NowPlayingData
import com.bloemstudios.admin.widget.Outcome
import com.bloemstudios.admin.widget.PlacedWidget
import com.bloemstudios.admin.widget.RequestsData
import com.bloemstudios.admin.widget.StreamItem
import com.bloemstudios.admin.widget.WidgetCache
import com.bloemstudios.admin.widget.WidgetIds
import com.bloemstudios.admin.widget.WidgetKind
import com.bloemstudios.admin.widget.WidgetPlacement
import com.bloemstudios.admin.widget.WidgetRefreshRuntime
import com.bloemstudios.admin.widget.WidgetRefresher
import com.bloemstudios.admin.widget.WidgetSession
import com.bloemstudios.admin.widget.WidgetSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A silent wake meets the widgets in one place, [WidgetRefreshGateway], and that goes through the same refresh as the 15-minute job,
 * the Refresh button, a push and the live socket: the placed widgets are read once and drawn again.
 */
@RunWith(RobolectricTestRunner::class)
class PushWakeRefreshTest {
    private lateinit var context: Context
    private val home = AdminServer("home", "https://home.test", "Home")
    private var originalStep: WidgetRefreshStep = WidgetRefreshGateway.step

    private class Source : WidgetSource {
        var sessions = 0
        var requests = 0
        var libraries = 0
        var nodes = 0
        override suspend fun nowPlaying() = NowPlayingData(1, streams = listOf(StreamItem("s", "ana", "T"))).also { sessions++ }
        override suspend fun requests() = RequestsData(0).also { requests++ }
        override suspend fun libraries() = LibrariesData().also { libraries++ }
        override suspend fun nodes() = NodesData().also { nodes++ }
    }

    private val source = Source()
    private var redraws = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        listOf("native_surfaces", "server_status", "widget_refresh_log").forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        WidgetCache.forContext(context).clear()
        // Three widgets are placed: the system says so.
        val ids = mapOf(WidgetKind.NowPlaying to 41, WidgetKind.Requests to 42, WidgetKind.Libraries to 43)
        WidgetPlacement.widgetIds = { WidgetIds { kind -> setOfNotNull(ids[kind]) } }
        val widgets = ids.map { (kind, id) -> PlacedWidget(id, kind, null) }
        WidgetRefreshRuntime.refresherFactory = {
            WidgetRefresher(
                servers = { listOf(home) },
                surfaces = AlertsRuntime.surfacePreferences(context),
                statuses = AlertsRuntime.statusStore(context),
                cache = WidgetCache.forContext(context),
                session = object : WidgetSession {
                    override fun hasSession(server: AdminServer) = true
                    override fun source(server: AdminServer) = source
                    override suspend fun decide(server: AdminServer, requestId: String, approve: Boolean) = Unit
                    override fun close() = Unit
                },
                clock = { 1_000_000L },
                placedWidgets = { widgets },
            )
        }
        WidgetRefreshRuntime.afterRun = { redraws++ }
        originalStep = WidgetRefreshGateway.step
    }

    @After fun tearDown() {
        WidgetRefreshGateway.step = originalStep
        WidgetPlacement.widgetIds = { com.bloemstudios.admin.widget.SystemWidgetIds(it) }
        WidgetRefreshRuntime.refresherFactory = null
        WidgetRefreshRuntime.afterRun = { com.bloemstudios.admin.widget.SurfaceRefresher.refreshAll(it) }
        WidgetCache.forContext(context).clear()
    }

    @Test fun theGatewayUsesTheWidgetsOwnRefreshByDefault() {
        // The default step, not a fake: what a woken job really runs.
        runBlocking { WidgetRefreshGateway.step.refresh(context, setOf(WidgetKind.NowPlaying)) }
        assertEquals("the sessions are read once", 1, source.sessions)
        assertEquals("only what the wake named: the other placed widgets keep their rhythm", 0, source.requests + source.libraries)
        assertEquals("and the widgets are drawn again", 1, redraws)
        assertNotNull(WidgetCache.forContext(context).slice("home", WidgetKind.NowPlaying))
    }

    @Test fun aWakeNamingRequestsAndNodesRefreshesTheRequestsWidgetOnly() {
        val worker = TestListenableWorkerBuilder<WidgetWakeWorker>(context)
            .setInputData(workDataOf(WorkManagerWidgetWaker.KEY_SERVER to "home", WorkManagerWidgetWaker.KEY_CHANNELS to arrayOf(WidgetChannels.REQUESTS, WidgetChannels.NODES)))
            .build()
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("the requests widget is placed and was named", 1, source.requests)
        assertEquals("no nodes widget is placed, so nothing is read for it", 0, source.nodes)
        assertEquals("the sessions were not named", 0, source.sessions)
        assertEquals(1, redraws)
    }

    @Test fun aWakeRefreshesThePlacedWidgetsAndNotesWhenItRan() {
        val worker = TestListenableWorkerBuilder<WidgetWakeWorker>(context)
            .setInputData(workDataOf(WorkManagerWidgetWaker.KEY_SERVER to "home", WorkManagerWidgetWaker.KEY_CHANNELS to arrayOf(WidgetChannels.SESSIONS, WidgetChannels.LIBRARIES)))
            .build()
        runBlocking { worker.doWork() }
        assertEquals(1, source.sessions)
        assertEquals(1, source.libraries)
        assertEquals(1, redraws)
        assertNotNull("the run is on record, so the background trigger spaces itself from it", com.bloemstudios.admin.widget.RefreshLog.lastRunAt(context))
    }

    @Test fun aWakeWhileSomethingPlaysKeepsTheFasterRhythmGoing() {
        val worker = TestListenableWorkerBuilder<WidgetWakeWorker>(context)
            .setInputData(workDataOf(WorkManagerWidgetWaker.KEY_SERVER to "home", WorkManagerWidgetWaker.KEY_CHANNELS to arrayOf(WidgetChannels.SESSIONS)))
            .build()
        // A stream with a known length, so the follow-up is planned from it.
        WidgetRefreshRuntime.refresherFactory = {
            WidgetRefresher(
                servers = { listOf(home) }, surfaces = AlertsRuntime.surfacePreferences(context), statuses = AlertsRuntime.statusStore(context),
                cache = WidgetCache.forContext(context),
                session = object : WidgetSession {
                    override fun hasSession(server: AdminServer) = true
                    override fun source(server: AdminServer) = object : WidgetSource by source {
                        override suspend fun nowPlaying() = NowPlayingData(1, streams = listOf(StreamItem("s", "ana", "T", positionSeconds = 60.0, durationSeconds = 7_200.0)))
                    }
                    override suspend fun decide(server: AdminServer, requestId: String, approve: Boolean) = Unit
                    override fun close() = Unit
                },
                clock = { System.currentTimeMillis() },
                placedWidgets = { listOf(PlacedWidget(41, WidgetKind.NowPlaying, null)) },
            )
        }
        runBlocking { worker.doWork() }
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(com.bloemstudios.admin.widget.WidgetWork.FOLLOW_UP).get().filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, infos.size)
    }

    private fun soon() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(com.bloemstudios.admin.widget.WidgetWork.SOON).get().filter { it.state != WorkInfo.State.CANCELLED }

    @Test fun aWakeIsNotRefreshedTwice() {
        // A wake is one job (WidgetWakeWorker). The hook for ordinary notifications leaves a wake, and an ignored push, alone.
        PushRuntime.refreshWidgetsAfter(context, PushOutcome.Woke)
        PushRuntime.refreshWidgetsAfter(context, PushOutcome.Ignored)
        assertTrue(soon().isEmpty())
    }

    @Test fun anOrdinaryNotificationRefreshesTheWidgetsOnce() {
        PushRuntime.refreshWidgetsAfter(context, PushOutcome.Posted)
        PushRuntime.refreshWidgetsAfter(context, PushOutcome.PostedGeneric)
        assertEquals(1, soon().size)
    }

    @Test fun aWakeOnAPhoneWithNoWidgetDoesNothingAndLeavesNoJob() {
        WidgetPlacement.widgetIds = { WidgetIds { emptySet() } }
        runBlocking { WidgetRefreshGateway.step.refresh(context, setOf(WidgetKind.NowPlaying)) }
        assertEquals(0, source.sessions)
        assertEquals(0, redraws)
    }
}
