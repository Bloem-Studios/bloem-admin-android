package com.bloemstudios.admin.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.network.AppDataSignal
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * The widgets' own job: scheduled while any admin widget exists (read from the system, not from a note), network only, every 15
 * minutes; cancelled with the last widget; scheduled again after boot or an update; and a one-shot for each moment in between.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetWorkTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val home = AdminServer("home", "https://home.test", "Home")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        listOf("native_surfaces", "server_status", "widget_refresh_log", "alert_preferences").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        WidgetCache.forContext(context).clear()
        placed()
        WidgetRefreshRuntime.afterRun = {}
    }

    @After fun tearDown() {
        WidgetPlacement.widgetIds = { SystemWidgetIds(it) }
        WidgetRefreshRuntime.refresherFactory = null
        WidgetRefreshRuntime.afterRun = { SurfaceRefresher.refreshAll(it) }
        WidgetCache.forContext(context).clear()
    }

    /** The widgets the system says exist. */
    private fun placed(vararg widgets: Pair<WidgetKind, Int>) {
        WidgetPlacement.widgetIds = { WidgetIds { kind -> widgets.filter { it.first == kind }.map { it.second }.toSet() } }
    }

    private fun work(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { it.state != WorkInfo.State.CANCELLED }

    // ---- Scheduled while a widget exists ----

    @Test fun aPhoneWithNoWidgetNeverOpensTheScheduler() {
        assertFalse(WidgetWork.reconcile(context, touchWhenUnwanted = false))
        assertTrue(work(WidgetWork.PERIODIC).isEmpty())
    }

    @Test fun aWidgetTheAppHasNoNoteOfIsStillScheduled_theBugThatLeftAPhoneWithNoJob() {
        // Placed by an earlier build, or restored by the launcher: the system has it, the app's notes do not.
        placed(WidgetKind.NowPlaying to 41)
        assertFalse("no note exists", AlertsRuntime.surfacePreferences(context).hasWidget(41))
        assertTrue(WidgetWork.reconcile(context))
        val job = work(WidgetWork.PERIODIC).single()
        assertEquals(WorkInfo.State.ENQUEUED, job.state)
        assertEquals(TimeUnit.MINUTES.toMillis(15), job.periodicityInfo?.repeatIntervalMillis)
        assertTrue("and it now has a note", AlertsRuntime.surfacePreferences(context).hasWidget(41))
    }

    @Test fun theJobNeedsANetworkAndNothingElse() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetWork.reconcile(context)
        val constraints = work(WidgetWork.PERIODIC).single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse("a widget still refreshes on a low battery", constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresDeviceIdle())
    }

    @Test fun anyOfTheFiveKindsKeepsTheJobAlive() {
        for (kind in WidgetKind.entries) {
            placed(kind to 3)
            WidgetWork.reconcile(context)
            assertEquals("$kind", 1, work(WidgetWork.PERIODIC).size)
            placed()
            WidgetWork.reconcile(context)
            assertTrue("$kind removed", work(WidgetWork.PERIODIC).isEmpty())
        }
    }

    @Test fun reconcilingAgainKeepsTheSameJobInsteadOfStackingMore() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetWork.reconcile(context)
        val first = work(WidgetWork.PERIODIC).single().id
        repeat(3) { WidgetWork.reconcile(context) }
        assertEquals(first, work(WidgetWork.PERIODIC).single().id)
    }

    // ---- Cancelled when the last one goes ----

    @Test fun removingTheLastWidgetCancelsEveryJobAndForgetsWhatTheyHeld() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetWork.reconcile(context)
        WidgetWork.scheduleFollowUp(context, 300_000L)
        WidgetCache.forContext(context).put("home", WidgetKind.NowPlaying, CachedSlice(Outcome.Ok, 1L, 1L, nowPlaying = NowPlayingData(1, streams = listOf(StreamItem("s", "ana", "A private title")))))
        assertEquals(1, work(WidgetWork.FOLLOW_UP).size)

        placed()
        assertFalse(WidgetWork.reconcile(context))
        assertTrue(work(WidgetWork.PERIODIC).isEmpty())
        assertTrue(work(WidgetWork.FOLLOW_UP).isEmpty())
        assertTrue(work(WidgetWork.SOON).isEmpty())
        assertNull("no title or user name is kept for a widget that is gone", WidgetCache.forContext(context).slice("home", WidgetKind.NowPlaying))
    }

    @Test fun aNoteForAWidgetThatWasRemovedDoesNotKeepTheJobAlive() {
        AlertsRuntime.surfacePreferences(context).setWidget(9, WidgetKind.Requests, null)
        placed()
        assertFalse(WidgetWork.reconcile(context))
        assertTrue(work(WidgetWork.PERIODIC).isEmpty())
    }

    // ---- Boot and app update ----

    @Test fun afterBootTheJobIsScheduledAgainAndTheWidgetsAreReadOnce() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, work(WidgetWork.PERIODIC).size)
        val once = work(WidgetWork.SOON).single()
        assertEquals(RefreshReason.Boot.delayMillis, once.initialDelayMillis)
    }

    @Test fun afterAnAppUpdateTheSameHappens() {
        placed(WidgetKind.Libraries to 4)
        WidgetBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(1, work(WidgetWork.PERIODIC).size)
        assertEquals(1, work(WidgetWork.SOON).size)
    }

    @Test fun aBootOnAPhoneWithNoWidgetDoesNothing() {
        WidgetBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(work(WidgetWork.PERIODIC).isEmpty())
        assertTrue(work(WidgetWork.SOON).isEmpty())
    }

    @Test fun otherBroadcastsAreIgnored() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetBootReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(work(WidgetWork.PERIODIC).isEmpty())
    }

    // ---- The refresh triggers ----

    @Test fun theRefreshButtonReadsAtOnce() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetWork.refreshSoon(context, RefreshReason.UserRefresh, nowMillis = 1_000L)
        val once = work(WidgetWork.SOON).single()
        assertEquals(0L, once.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, once.constraints.requiredNetworkType)
    }

    @Test fun aBurstOfTheSameReasonLeavesOneRunWaiting() {
        placed(WidgetKind.NowPlaying to 41)
        repeat(5) { WidgetWork.refreshSoon(context, RefreshReason.AppSaved, nowMillis = 1_000L) }
        assertEquals(1, work(WidgetWork.SOON).size)
        assertEquals(RefreshReason.AppSaved.delayMillis, work(WidgetWork.SOON).single().initialDelayMillis)
    }

    @Test fun theAppGoingToTheBackgroundReadsOnceAMinuteAtMost() {
        placed(WidgetKind.NowPlaying to 41)
        RefreshLog.record(context, 100_000L)
        WidgetWork.refreshSoon(context, RefreshReason.Background, nowMillis = 100_000L + 30_000L)
        assertTrue("30 seconds after a run: too soon", work(WidgetWork.SOON).isEmpty())
        WidgetWork.refreshSoon(context, RefreshReason.Background, nowMillis = 100_000L + 61_000L)
        assertEquals(1, work(WidgetWork.SOON).size)
        assertEquals(RefreshReason.Background.delayMillis, work(WidgetWork.SOON).single().initialDelayMillis)
    }

    @Test fun aPushReadsSoonAndIsNotHeldBackByTheGap() {
        placed(WidgetKind.Requests to 9)
        RefreshLog.record(context, 100_000L)
        WidgetWork.refreshSoon(context, RefreshReason.Push, nowMillis = 100_001L)
        assertEquals(RefreshReason.Push.delayMillis, work(WidgetWork.SOON).single().initialDelayMillis)
    }

    @Test fun nothingIsScheduledForAPhoneWithNoWidget() {
        WidgetWork.refreshSoon(context, RefreshReason.UserRefresh)
        assertTrue(work(WidgetWork.SOON).isEmpty())
    }

    @Test fun whenTheAppSavesSomethingTheWidgetsAreReadSoon() {
        placed(WidgetKind.Requests to 9)
        runBlocking { WidgetSync.onEvent(context, AppDataSignal.RequestDecided("https://home.test")) }
        assertEquals(RefreshReason.AppSaved.delayMillis, work(WidgetWork.SOON).single().initialDelayMillis)
    }

    @Test fun anyAcceptedWriteCountsButSignInPushAndTheSocketTicketDoNot() {
        placed(WidgetKind.NowPlaying to 41)
        runBlocking { WidgetSync.onEvent(context, AppDataSignal.Wrote("https://home.test", "/api/v2/events/ws-ticket")) }
        runBlocking { WidgetSync.onEvent(context, AppDataSignal.Wrote("https://home.test", "/api/bloem/v2/admin/push/register")) }
        runBlocking { WidgetSync.onEvent(context, AppDataSignal.Wrote("https://home.test", "/api/v2/auth/token")) }
        assertTrue(work(WidgetWork.SOON).isEmpty())
        runBlocking { WidgetSync.onEvent(context, AppDataSignal.Wrote("https://home.test", "/api/v2/admin/sessions/s1/stop")) }
        assertEquals(1, work(WidgetWork.SOON).size)
    }

    // ---- The faster rhythm while something plays ----

    @Test fun theFollowUpAsksOnlyForTheStreamsAndWaitsTheGivenTime() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetWork.scheduleFollowUp(context, 420_000L)
        val job = work(WidgetWork.FOLLOW_UP).single()
        assertEquals(420_000L, job.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        assertTrue(WidgetWork.onlyNowPlaying(workDataOf("only_now_playing" to true)))
        assertFalse(WidgetWork.onlyNowPlaying(workDataOf()))
    }

    @Test fun askingForNoFollowUpCancelsTheOneWaiting() {
        WidgetWork.scheduleFollowUp(context, 420_000L)
        WidgetWork.scheduleFollowUp(context, null)
        assertTrue(work(WidgetWork.FOLLOW_UP).isEmpty())
    }

    // ---- A run: the request rate, the redraw, the follow-up ----

    private class CountingSource(val playing: () -> NowPlayingData) : WidgetSource {
        var sessions = 0
        var others = 0
        override suspend fun nowPlaying() = playing().also { sessions++ }
        override suspend fun requests() = RequestsData(0).also { others++ }
        override suspend fun libraries() = LibrariesData().also { others++ }
        override suspend fun nodes() = NodesData().also { others++ }
    }

    private fun refresher(source: WidgetSource, widgets: List<PlacedWidget>, clock: () -> Long = { 1_000_000L }) = WidgetRefresher(
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
        clock = clock,
        placedWidgets = { widgets },
    )

    private val playingOne = NowPlayingData(1, streams = listOf(StreamItem("s", "ana", "T", positionSeconds = 600.0, durationSeconds = 7_200.0)))

    @Test fun aRunWithOnlyNowPlayingMakesOneRequestPerServer() {
        val widgets = listOf(PlacedWidget(41, WidgetKind.NowPlaying, null))
        placed(WidgetKind.NowPlaying to 41)
        val source = CountingSource { playingOne }
        WidgetRefreshRuntime.refresherFactory = { refresher(source, widgets) }
        runBlocking { runWidgetRefresh(context, nowMillis = 1_000_000L) }
        assertEquals("one request to the server, no health probe and no counts", 1, source.sessions)
        assertEquals(0, source.others)
    }

    @Test fun eachOtherKindAddsOnlyItsOwnRead() {
        val widgets = listOf(PlacedWidget(41, WidgetKind.NowPlaying, null), PlacedWidget(42, WidgetKind.Nodes, null))
        placed(WidgetKind.NowPlaying to 41, WidgetKind.Nodes to 42)
        val source = CountingSource { playingOne }
        WidgetRefreshRuntime.refresherFactory = { refresher(source, widgets) }
        runBlocking { runWidgetRefresh(context, nowMillis = 1_000_000L) }
        assertEquals(1, source.sessions)
        assertEquals(1, source.others)
    }

    @Test fun aRunRedrawsTheWidgetsAndNotesWhenItRan() {
        placed(WidgetKind.NowPlaying to 41)
        var redraws = 0
        WidgetRefreshRuntime.afterRun = { redraws++ }
        WidgetRefreshRuntime.refresherFactory = { refresher(CountingSource { playingOne }, listOf(PlacedWidget(41, WidgetKind.NowPlaying, null))) }
        runBlocking { runWidgetRefresh(context, nowMillis = 5_000_000L) }
        assertEquals(1, redraws)
        assertEquals(5_000_000L, RefreshLog.lastRunAt(context))
    }

    @Test fun whileSomethingPlaysAFollowUpIsScheduledWithinTheRhythm() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetRefreshRuntime.refresherFactory = { refresher(CountingSource { playingOne }, listOf(PlacedWidget(41, WidgetKind.NowPlaying, null))) }
        runBlocking { runWidgetRefresh(context, nowMillis = 1_000_000L) }
        val follow = work(WidgetWork.FOLLOW_UP).single()
        assertTrue("never sooner than 5 minutes", follow.initialDelayMillis >= ActiveCadence.FLOOR_MILLIS)
        assertTrue("faster than the 15-minute idle rhythm", follow.initialDelayMillis <= ActiveCadence.CEILING_MILLIS)
    }

    @Test fun whenNothingPlaysTheFollowUpIsCancelled() {
        placed(WidgetKind.NowPlaying to 41)
        WidgetWork.scheduleFollowUp(context, 300_000L)
        WidgetRefreshRuntime.refresherFactory = { refresher(CountingSource { NowPlayingData(0) }, listOf(PlacedWidget(41, WidgetKind.NowPlaying, null))) }
        runBlocking { runWidgetRefresh(context, nowMillis = 1_000_000L) }
        assertTrue(work(WidgetWork.FOLLOW_UP).isEmpty())
    }

    @Test fun noFollowUpIsScheduledWhenNowPlayingIsNotOnTheHomeScreen() {
        placed(WidgetKind.Requests to 9)
        WidgetRefreshRuntime.refresherFactory = { refresher(CountingSource { playingOne }, listOf(PlacedWidget(9, WidgetKind.Requests, null))) }
        runBlocking { runWidgetRefresh(context, nowMillis = 1_000_000L) }
        assertTrue(work(WidgetWork.FOLLOW_UP).isEmpty())
    }

    @Test fun theWorkerRunsTheReadAndSucceeds() {
        placed(WidgetKind.NowPlaying to 41)
        val source = CountingSource { playingOne }
        WidgetRefreshRuntime.refresherFactory = { refresher(source, listOf(PlacedWidget(41, WidgetKind.NowPlaying, null))) }
        val result = runBlocking { TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, source.sessions)
        assertNotNull(RefreshLog.lastRunAt(context))
    }

    @Test fun theFollowUpWorkerReadsOnlyTheStreams() {
        val widgets = listOf(PlacedWidget(41, WidgetKind.NowPlaying, null), PlacedWidget(42, WidgetKind.Libraries, null))
        placed(WidgetKind.NowPlaying to 41, WidgetKind.Libraries to 42)
        val source = CountingSource { playingOne }
        WidgetRefreshRuntime.refresherFactory = { refresher(source, widgets) }
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).setInputData(workDataOf("only_now_playing" to true)).build()
        runBlocking { worker.doWork() }
        assertEquals(1, source.sessions)
        assertEquals("the libraries keep their 15-minute rhythm", 0, source.others)
    }

    @Test fun aServerThatDoesNotAnswerLeavesTheLastDataAndDoesNotFailTheRun() {
        placed(WidgetKind.NowPlaying to 41)
        var up = true
        val source = CountingSource { if (up) playingOne else throw java.io.IOException("down") }
        val widgets = listOf(PlacedWidget(41, WidgetKind.NowPlaying, null))
        WidgetRefreshRuntime.refresherFactory = { refresher(source, widgets) }
        runBlocking { runWidgetRefresh(context, nowMillis = 1_000_000L) }
        up = false
        runBlocking { runWidgetRefresh(context, nowMillis = 2_000_000L) }
        val slice = WidgetCache.forContext(context).slice("home", WidgetKind.NowPlaying)!!
        assertEquals(Outcome.Unreachable, slice.outcome)
        assertEquals("the last good data stays, with the time it was read", 1_000_000L, slice.dataAt)
        assertNotNull(slice.nowPlaying)
    }
}
