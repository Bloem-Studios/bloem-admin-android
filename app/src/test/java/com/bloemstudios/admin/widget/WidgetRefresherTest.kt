package com.bloemstudios.admin.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.alerts.ServerRecord
import com.bloemstudios.admin.alerts.ServerStatus
import com.bloemstudios.admin.alerts.ServerStatusStore
import com.bloemstudios.admin.alerts.SurfacePreferences
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.delay
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
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class WidgetRefresherTest {
    private lateinit var context: Context
    private lateinit var surfaces: SurfacePreferences
    private lateinit var statuses: ServerStatusStore
    private lateinit var file: File
    private lateinit var cache: WidgetCache

    private val home = AdminServer("home", "https://home.test", "Home")
    private val lab = AdminServer("lab", "https://lab.test", "Lab")
    private var saved = listOf(home, lab)
    private var clock = 1_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("refresher_surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("refresher_status", Context.MODE_PRIVATE).edit().clear().commit()
        surfaces = SurfacePreferences(context.getSharedPreferences("refresher_surfaces", Context.MODE_PRIVATE))
        statuses = ServerStatusStore(context.getSharedPreferences("refresher_status", Context.MODE_PRIVATE))
        file = File(context.cacheDir, "refresher-cache-${System.nanoTime()}.json")
        cache = WidgetCache(file)
    }

    @After fun tearDown() { file.delete() }

    private class FakeSession(
        val sessions: Set<String> = setOf("home", "lab"),
        val sources: Map<String, WidgetSource> = emptyMap(),
        var decideError: Exception? = null,
    ) : WidgetSession {
        var closed = false
        val decided = mutableListOf<Triple<String, String, Boolean>>()
        override fun hasSession(server: AdminServer) = server.id in sessions
        override fun source(server: AdminServer) = sources.getValue(server.id)
        override suspend fun decide(server: AdminServer, requestId: String, approve: Boolean) {
            decideError?.let { throw it }
            decided += Triple(server.id, requestId, approve)
        }
        override fun close() { closed = true }
    }

    private class Source(
        val playing: () -> NowPlayingData = { NowPlayingData(1, streams = listOf(StreamItem("s1", "ana", "Harbour Lights"))) },
        val waiting: () -> RequestsData = { RequestsData(2, listOf(RequestItem("r1", "Glass Orchard", "ana"), RequestItem("r2", "Copper Hill", "ben"))) },
    ) : WidgetSource {
        var reads = 0
        override suspend fun nowPlaying() = playing().also { reads++ }
        override suspend fun requests() = waiting().also { reads++ }
        override suspend fun libraries() = LibrariesData().also { reads++ }
        override suspend fun nodes() = NodesData().also { reads++ }
    }

    private fun refresher(session: WidgetSession, timeout: Long = 5_000L) =
        WidgetRefresher({ saved }, surfaces, statuses, cache, session, { clock }, timeout)

    @Test fun onlyTheKindsAndServersThatAreShownAreRead() {
        surfaces.setWidget(1, WidgetKind.NowPlaying, "home")
        surfaces.setWidget(2, WidgetKind.Requests, null)
        surfaces.setWidget(3, WidgetKind.ServerStatus, null)
        val demand = refresher(FakeSession()).demand(saved).mapKeys { it.key.id }
        assertEquals(setOf(WidgetKind.NowPlaying, WidgetKind.Requests), demand["home"])
        assertEquals(setOf(WidgetKind.Requests), demand["lab"])
    }

    @Test fun serverStatusWidgetsNeedNoDetailReads() {
        surfaces.setWidget(1, WidgetKind.ServerStatus, null)
        assertTrue(refresher(FakeSession()).demand(saved).isEmpty())
    }

    @Test fun aRemovedServerIsNotRead() {
        surfaces.setWidget(1, WidgetKind.NowPlaying, "gone")
        assertTrue(refresher(FakeSession()).demand(saved).isEmpty())
    }

    @Test fun aRunStoresWhatEachWidgetNeeds() {
        surfaces.setWidget(1, WidgetKind.NowPlaying, "home")
        surfaces.setWidget(2, WidgetKind.Requests, "lab")
        val session = FakeSession(sources = mapOf("home" to Source(), "lab" to Source()))
        runBlocking { refresher(session).run() }
        assertEquals(1, cache.slice("home", WidgetKind.NowPlaying)?.nowPlaying?.total)
        assertEquals(2, cache.slice("lab", WidgetKind.Requests)?.requests?.pending)
        assertNull(cache.slice("home", WidgetKind.Requests))
        assertTrue("the store lease is released", session.closed)
    }

    @Test fun aServerWithoutASessionIsNeverContacted() {
        surfaces.setWidget(1, WidgetKind.NowPlaying, "home")
        val source = Source()
        runBlocking { refresher(FakeSession(sessions = emptySet(), sources = mapOf("home" to source))).run() }
        assertEquals(Outcome.SignInNeeded, cache.slice("home", WidgetKind.NowPlaying)?.outcome)
        assertEquals(0, source.reads)
    }

    @Test fun aServerTheHealthCheckSawDownIsNotAskedAgainAndKeepsItsLastData() {
        surfaces.setWidget(1, WidgetKind.NowPlaying, "home")
        val source = Source()
        runBlocking { refresher(FakeSession(sources = mapOf("home" to source))).run() }
        statuses.put("home", ServerRecord(ServerStatus(reachable = false, checkedAt = 2_000L)))
        clock = 9_000L
        runBlocking { refresher(FakeSession(sources = mapOf("home" to source))).run() }
        assertEquals(1, source.reads)
        val slice = cache.slice("home", WidgetKind.NowPlaying)
        assertEquals(Outcome.Unreachable, slice?.outcome)
        assertEquals(1_000L, slice?.dataAt)
        assertEquals(1, slice?.nowPlaying?.total)
    }

    @Test fun aSlowServerTimesOutWithoutHoldingUpTheOthers() {
        surfaces.setWidget(1, WidgetKind.NowPlaying, null)
        val slow = object : WidgetSource {
            override suspend fun nowPlaying(): NowPlayingData { delay(60_000); return NowPlayingData(0) }
            override suspend fun requests() = RequestsData(0)
            override suspend fun libraries() = LibrariesData()
            override suspend fun nodes() = NodesData()
        }
        runBlocking { refresher(FakeSession(sources = mapOf("home" to Source(), "lab" to slow)), timeout = 200L).run() }
        assertEquals(Outcome.Ok, cache.slice("home", WidgetKind.NowPlaying)?.outcome)
        assertEquals(Outcome.Unreachable, cache.slice("lab", WidgetKind.NowPlaying)?.outcome)
    }

    @Test fun aFeatureGoneFromTheServerClearsItsOldData() {
        surfaces.setWidget(1, WidgetKind.Requests, "home")
        var offered = true
        val source = Source(waiting = { if (offered) RequestsData(3, listOf(RequestItem("r", "Glass Orchard", "ana"))) else throw FeatureUnavailable() })
        runBlocking { refresher(FakeSession(sources = mapOf("home" to source))).run() }
        offered = false
        runBlocking { refresher(FakeSession(sources = mapOf("home" to source))).run() }
        val slice = cache.slice("home", WidgetKind.Requests)
        assertEquals(Outcome.Unavailable, slice?.outcome)
        assertFalse(slice!!.hasData)
        assertFalse("no title is left on disk", file.readText().contains("Glass Orchard"))
    }

    @Test fun aRemovedServerIsForgottenFromTheCache() {
        cache.put("gone", WidgetKind.NowPlaying, CachedSlice(Outcome.Ok, 1L, 1L, nowPlaying = NowPlayingData(1, streams = listOf(StreamItem("s", "ana", "T")))))
        surfaces.setWidget(1, WidgetKind.NowPlaying, "home")
        runBlocking { refresher(FakeSession(sources = mapOf("home" to Source()))).run() }
        assertNull(cache.slice("gone", WidgetKind.NowPlaying))
        assertFalse(file.readText().contains("ana\",\"title\":\"T"))
    }

    @Test fun aFreshReadOfTheRequestsClearsAFailedActionNotice() {
        surfaces.setWidget(1, WidgetKind.Requests, "home")
        cache.setNotice("home", ActionNotice("home", approve = true, at = 1L))
        runBlocking { refresher(FakeSession(sources = mapOf("home" to Source()))).run() }
        assertNull(cache.notice("home"))
    }

    // ---- Approve and Decline ----

    private fun decision(session: WidgetSession) = RequestDecision({ saved }, cache, session, { clock })

    private fun seedRequests() = cache.put(
        "home", WidgetKind.Requests,
        CachedSlice(Outcome.Ok, 1L, 1L, requests = RequestsData(5, listOf(RequestItem("r1", "Glass Orchard", "ana"), RequestItem("r2", "Copper Hill", "ben")))),
    )

    @Test fun approveCallsTheServerOnceAndDropsTheRequestFromTheWidget() {
        seedRequests()
        val session = FakeSession()
        assertEquals(DecisionResult.Done, runBlocking { decision(session).run("home", "r1", approve = true) })
        assertEquals(listOf(Triple("home", "r1", true)), session.decided)
        val data = cache.slice("home", WidgetKind.Requests)?.requests
        assertEquals(listOf("r2"), data?.items?.map { it.id })
        assertEquals(4, data?.pending)
        assertNull(cache.notice("home"))
    }

    @Test fun declineIsTheSameCallWithDeclineIntent() {
        seedRequests()
        val session = FakeSession()
        assertEquals(DecisionResult.Done, runBlocking { decision(session).run("home", "r2", approve = false) })
        assertEquals(listOf(Triple("home", "r2", false)), session.decided)
        assertEquals(listOf("r1"), cache.slice("home", WidgetKind.Requests)?.requests?.items?.map { it.id })
    }

    @Test fun aRefusedApproveKeepsTheRequestAndShowsCouldntApprove() {
        seedRequests()
        val session = FakeSession(decideError = ApiError(409, "already decided"))
        assertEquals(DecisionResult.Failed, runBlocking { decision(session).run("home", "r1", approve = true) })
        assertEquals(2, cache.slice("home", WidgetKind.Requests)?.requests?.items?.size)
        val notice = cache.notice("home")
        assertNotNull(notice)
        assertTrue(notice!!.approve)
    }

    @Test fun aFailedDeclineIsNotedAsADecline() {
        seedRequests()
        assertEquals(DecisionResult.Failed, runBlocking { decision(FakeSession(decideError = IOException("offline"))).run("home", "r1", approve = false) })
        assertFalse(cache.notice("home")!!.approve)
    }

    @Test fun noSessionMeansNothingIsSentAndTheWidgetSaysSo() {
        seedRequests()
        val session = FakeSession(sessions = emptySet())
        assertEquals(DecisionResult.Failed, runBlocking { decision(session).run("home", "r1", approve = true) })
        assertTrue(session.decided.isEmpty())
        assertNotNull(cache.notice("home"))
    }

    @Test fun aServerRemovedMeanwhileFailsWithoutACall() {
        seedRequests()
        val session = FakeSession()
        assertEquals(DecisionResult.Failed, runBlocking { decision(session).run("removed", "r1", approve = true) })
        assertTrue(session.decided.isEmpty())
    }

    @Test fun aSuccessfulDecisionClearsAnEarlierNotice() {
        seedRequests()
        cache.setNotice("home", ActionNotice("home", approve = true, at = 5L))
        runBlocking { decision(FakeSession()).run("home", "r1", approve = true) }
        assertNull(cache.notice("home"))
    }

    // ---- The cache file ----

    @Test fun theCacheSurvivesARestart() {
        seedRequests()
        assertEquals(5, WidgetCache(file).slice("home", WidgetKind.Requests)?.requests?.pending)
    }

    @Test fun aDamagedCacheFileReadsAsEmpty() {
        file.writeText("{ not json")
        assertNull(WidgetCache(file).slice("home", WidgetKind.Requests))
        WidgetCache(file).put("home", WidgetKind.Nodes, CachedSlice(Outcome.Ok, 1L, 1L, nodes = NodesData()))
        assertNotNull(WidgetCache(file).slice("home", WidgetKind.Nodes))
    }

    @Test fun theCacheLivesWhereBackupsDoNotReach() {
        val real = WidgetCache.forContext(context)
        real.put("home", WidgetKind.Nodes, CachedSlice(Outcome.Ok, 1L, 1L, nodes = NodesData()))
        assertTrue(File(context.noBackupFilesDir, WidgetCache.FILE_NAME).exists())
        real.clear()
    }

    // ---- The preferences that say which widget is which ----

    @Test fun aWidgetPlacedBeforeThereWereKindsIsServerStatus() {
        surfaces.setWidgetTarget(7, "home")
        assertEquals(WidgetKind.ServerStatus, surfaces.widgetKind(7))
        assertEquals(listOf(PlacedWidget(7, WidgetKind.ServerStatus, "home")), surfaces.placedWidgets())
    }

    @Test fun removingAWidgetForgetsItsKindAndServer() {
        surfaces.setWidget(1, WidgetKind.Nodes, "home")
        surfaces.removeWidgets(intArrayOf(1))
        assertTrue(surfaces.placedWidgets().isEmpty())
        assertEquals(WidgetKind.ServerStatus, surfaces.widgetKind(1))
    }

    @Test fun eitherTileKeepsTheCheckRunning() {
        assertFalse(surfaces.anyTile)
        surfaces.requestsTileAdded = true
        assertTrue(surfaces.anyTile)
        surfaces.requestsTileAdded = false
        surfaces.tileAdded = true
        assertTrue(surfaces.anyTile)
    }

    @Test fun anUnknownStoredKindFallsBackToServerStatus() {
        assertEquals(WidgetKind.ServerStatus, WidgetKind.fromStorage("hologram"))
        assertEquals(WidgetKind.Requests, WidgetKind.fromStorage(WidgetKind.Requests.storage))
    }
}
