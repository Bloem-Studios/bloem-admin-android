package com.bloemstudios.admin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.Configuration
import androidx.work.Data
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.widget.PlacedWidget
import com.bloemstudios.admin.widget.WidgetKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class PushWakeIdTest {
    @Test fun readsTheChannelsFromAWellFormedWake() {
        assertEquals(setOf("nodes", "requests"), PushWake.channelsOf("widgets-nodes-requests-1763254800"))
        assertEquals(setOf("sessions"), PushWake.channelsOf("widgets-sessions-1"))
        assertEquals(setOf("libraries", "nodes", "requests", "sessions"), PushWake.channelsOf("widgets-libraries-nodes-requests-sessions-1763254800"))
    }

    @Test fun anythingElseIsNotAWake() {
        for (id in listOf(
            "", "widgets", "widgets-1763254800", "widgets--1763254800", "widgets-nodes", "widgets-nodes-abc", "widgets-nodes-",
            "widgets-traffic-1763254800", "widgets-nodes-revenue-1763254800", "widget-nodes-1763254800", "Widgets-nodes-1763254800",
            "0b9e1c4a-7d2f-5a3e-9c11-2f6d8e4a7b90", "widgets-nodes-1763254800-extra", "widgets-nodes-12345678901234",
        )) {
            assertNull("accepted \"$id\"", PushWake.channelsOf(id))
        }
    }

    @Test fun aWireIsAWakeOnlyInBackgroundWakeMode() {
        val wake = mapOf("bloem_delivery_id" to "dev1.widgets-nodes-1763254800", "bloem_mode" to "background_wake")
        assertEquals(setOf("nodes"), PushWire.parse(wake)!!.wakeChannels)
        assertFalse(PushWire.parse(wake)!!.showsNotification)
        // The same id in an alert is not a wake, and a wake mode with an ordinary id names no channel.
        assertNull(PushWire.parse(wake + ("bloem_mode" to "private_alert"))!!.wakeChannels)
        assertNull(PushWire.parse(mapOf("bloem_delivery_id" to "dev1.0b9e", "bloem_mode" to "background_wake"))!!.wakeChannels)
        assertNull(PushWire.parse(mapOf("bloem_delivery_id" to "dev1.widgets-traffic-1763254800", "bloem_mode" to "background_wake"))!!.wakeChannels)
    }

    @Test fun aWakeIdFitsWhatTheRelayAndThisParserAccept() {
        val longest = "dev1." + "widgets-" + WidgetChannels.all.sorted().joinToString("-") + "-1763254800"
        assertTrue(longest.length <= 128)
        assertEquals(WidgetChannels.all, PushWire.parse(mapOf("bloem_delivery_id" to longest, "bloem_mode" to "background_wake"))!!.wakeChannels)
    }
}

class WidgetChannelsTest {
    @Test fun eachWidgetKindShowsTheChannelTheServerPushesAbout() {
        assertEquals("sessions", WidgetChannels.forKind(WidgetKind.NowPlaying))
        assertEquals("requests", WidgetChannels.forKind(WidgetKind.Requests))
        assertEquals("libraries", WidgetChannels.forKind(WidgetKind.Libraries))
        assertEquals("nodes", WidgetChannels.forKind(WidgetKind.Nodes))
        assertNull("a server status widget has its own check", WidgetChannels.forKind(WidgetKind.ServerStatus))
        assertEquals(setOf("sessions", "requests", "libraries", "nodes"), WidgetChannels.all)
    }

    @Test fun aChangedChannelAffectsTheWidgetsThatShowIt() {
        assertEquals(setOf(WidgetKind.Requests), WidgetChannels.kindsFor(setOf("requests")))
        assertEquals(setOf(WidgetKind.Nodes, WidgetKind.NowPlaying), WidgetChannels.kindsFor(setOf("nodes", "sessions")))
        assertTrue(WidgetChannels.kindsFor(emptySet()).isEmpty())
        assertTrue(WidgetChannels.kindsFor(setOf("traffic")).isEmpty())
    }

    @Test fun theReportForAServerIsItsOwnWidgetsPlusAllServersWidgets() {
        val widgets = listOf(
            PlacedWidget(1, WidgetKind.Requests, "a"),
            PlacedWidget(2, WidgetKind.Nodes, "b"),
            PlacedWidget(3, WidgetKind.Libraries, null),
            PlacedWidget(4, WidgetKind.Requests, "a"),
            PlacedWidget(5, WidgetKind.ServerStatus, "a"),
        )
        assertEquals(WidgetReport(true, listOf("libraries", "requests")), WidgetReport.forServer(widgets, "a"))
        assertEquals(WidgetReport(true, listOf("libraries", "nodes")), WidgetReport.forServer(widgets, "b"))
        assertEquals(WidgetReport(true, listOf("libraries")), WidgetReport.forServer(widgets, "c"))
        assertEquals(WidgetReport.None, WidgetReport.forServer(emptyList(), "a"))
        assertEquals(WidgetReport.None, WidgetReport.forServer(listOf(PlacedWidget(1, WidgetKind.ServerStatus, null)), "a"))
        assertEquals("libraries,requests", WidgetReport.forServer(widgets, "a").key)
        assertEquals("", WidgetReport.None.key)
    }
}

@RunWith(RobolectricTestRunner::class)
class PushWakeReceiverTest {
    private lateinit var preferences: PushPreferences
    private lateinit var registrations: PushRegistrationStore
    private val server = AdminServer("srv-1", "https://bloem.example.test", "Home")
    private val poster = RecordingPosterForWake()
    private val woken = mutableListOf<Pair<String, Set<String>>>()
    private var fetched = false

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("push_wake_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("push_wake_regs", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = PushPreferences(context.getSharedPreferences("push_wake_prefs", Context.MODE_PRIVATE))
        registrations = PushRegistrationStore(context.getSharedPreferences("push_wake_regs", Context.MODE_PRIVATE))
        registrations.put("srv-1", PushRegistration("dev1", "bad_secret"))
    }

    private fun receiver(saved: List<AdminServer> = listOf(server)) = PushReceiver(
        { saved }, registrations, preferences,
        PushContentClient(engineFactory = { MockEngine { fetched = true; respond("{}", HttpStatusCode.OK) } }), poster,
        WidgetWaker { id, channels -> woken += id to channels },
    )

    private val wake = mapOf("bloem_delivery_id" to "dev1.widgets-nodes-requests-1763254800", "bloem_mode" to "background_wake")

    @Test fun aWakeFromARegisteredServerRefreshesItsWidgetsAndShowsNothing() = runBlocking {
        assertEquals(PushOutcome.Woke, receiver().receive(wake))
        assertEquals(listOf("srv-1" to setOf("nodes", "requests")), woken)
        assertFalse("a wake never reads the notification route", fetched)
        assertTrue("a wake never posts anything", poster.anything.isEmpty())
    }

    @Test fun aWakeFromAnUnknownDeviceOrAServerNoLongerSavedDoesNothing() = runBlocking {
        assertEquals(PushOutcome.Ignored, receiver().receive(wake + ("bloem_delivery_id" to "stranger.widgets-nodes-1763254800")))
        assertEquals(PushOutcome.Ignored, receiver(saved = emptyList()).receive(wake))
        assertTrue(woken.isEmpty() && !fetched && poster.anything.isEmpty())
    }

    @Test fun aMalformedWakeDoesNothing() = runBlocking {
        for (id in listOf("dev1.widgets-traffic-1763254800", "dev1.widgets-nodes", "dev1.0b9e", "dev1.widgets-nodes-1763254800-x")) {
            assertEquals(PushOutcome.Ignored, receiver().receive(wake + ("bloem_delivery_id" to id)))
        }
        assertTrue(woken.isEmpty() && !fetched && poster.anything.isEmpty())
    }

    @Test fun aWakeIsNotSwitchedOffByAKindSwitchBecauseItIsNotAKind() = runBlocking {
        for (kind in PushKinds.all) preferences.setKindEnabled("srv-1", kind.id, false)
        assertEquals(PushOutcome.Woke, receiver().receive(wake))
    }
}

private class RecordingPosterForWake : PushPoster {
    val anything = mutableListOf<String>()
    override fun post(server: AdminServer, content: PushContent): Boolean { anything += "post"; return true }
    override fun postGeneric(server: AdminServer, deliveryId: String): Boolean { anything += "generic"; return true }
    override fun showActionResult(server: AdminServer?, tag: String, requestId: String, text: String, failed: Boolean) { anything += "result" }
}

@RunWith(RobolectricTestRunner::class)
class WidgetWakeJobTest {
    private lateinit var context: Context
    private lateinit var originalStep: WidgetRefreshStep

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalStep = WidgetRefreshGateway.step
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build())
    }

    @After fun restore() {
        WidgetRefreshGateway.step = originalStep
    }

    @Test fun aWakeIsOneExpeditedJobWithOnlyANetworkConstraintOnAndroid12AndLater() {
        val request = WorkManagerWidgetWaker.request("srv-1", setOf("requests", "nodes"), sdk = 31)
        assertTrue(request.workSpec.expedited)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(listOf("nodes", "requests"), request.workSpec.input.getStringArray(WorkManagerWidgetWaker.KEY_CHANNELS)!!.toList())
        assertEquals("srv-1", request.workSpec.input.getString(WorkManagerWidgetWaker.KEY_SERVER))
    }

    @Test fun beforeAndroid12TheJobIsNotExpeditedBecauseThatWouldPutANotificationOnScreen() {
        // Expedited work on Android 11 and earlier runs as a foreground service with a notification. A wake never shows anything.
        assertFalse(WorkManagerWidgetWaker.request("srv-1", setOf("nodes"), sdk = 30).workSpec.expedited)
        assertFalse(WorkManagerWidgetWaker.request("srv-1", setOf("nodes"), sdk = 26).workSpec.expedited)
    }

    @Test fun aNewerWakeReplacesOneThatHasNotStarted() {
        val waker = WorkManagerWidgetWaker(context)
        waker.wake("srv-1", setOf("nodes"))
        waker.wake("srv-1", setOf("requests"))
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerWidgetWaker.UNIQUE).get()
        val live = infos.filter { !it.state.isFinished }
        assertEquals("only the newest wake is waiting", 1, live.size)
    }

    @Test fun theJobRefreshesOnlyTheWidgetKindsTheChannelsShow() = runBlocking {
        val seen = mutableListOf<Set<WidgetKind>>()
        WidgetRefreshGateway.step = WidgetRefreshStep { _, kinds -> seen += kinds }
        val worker = TestListenableWorkerBuilder<WidgetWakeWorker>(context)
            .setInputData(Data.Builder().putString("server", "srv-1").putStringArray("channels", arrayOf("requests", "traffic", "nodes")).build())
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("an unknown channel is dropped", listOf(setOf(WidgetKind.Requests, WidgetKind.Nodes)), seen)
    }
}

@RunWith(RobolectricTestRunner::class)
class WidgetChangeHookTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The real App.onCreate has already registered the listener; start from none.
        PushRuntime.resetForTest(context)
        context.getSharedPreferences(com.bloemstudios.admin.alerts.SurfacePreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PushPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() { PushRuntime.resetForTest(context) }

    private fun surfaces() = com.bloemstudios.admin.alerts.SurfacePreferences(
        context.getSharedPreferences(com.bloemstudios.admin.alerts.SurfacePreferences.FILE, Context.MODE_PRIVATE),
    )

    @Test fun addingAndRemovingAWidgetTellsTheServersWhenAServerCanPush() {
        var calls = 0
        PushRuntime.onWidgetsChanged = { calls++ }
        PushRuntime.preferences(context).setStatus("srv-1", PushStatus.Registered)
        PushRuntime.watchWidgets(context)
        surfaces().setWidget(7, WidgetKind.Requests, "srv-1")
        assertTrue("adding a widget was not reported", calls >= 1)
        val afterAdd = calls
        surfaces().removeWidgets(intArrayOf(7))
        assertTrue("removing a widget was not reported", calls > afterAdd)
    }

    @Test fun aDeviceThatCannotPushIsNeverTold() {
        var calls = 0
        PushRuntime.onWidgetsChanged = { calls++ }
        PushRuntime.preferences(context).setStatus("silo", PushStatus.NotAvailable)
        PushRuntime.watchWidgets(context)
        surfaces().setWidget(7, WidgetKind.Requests, "silo")
        surfaces().removeWidgets(intArrayOf(7))
        assertEquals("a device with no server able to push must not start any work for a widget", 0, calls)
    }

    @Test fun otherSurfaceChangesAreNotWidgetChanges() {
        var calls = 0
        PushRuntime.onWidgetsChanged = { calls++ }
        PushRuntime.preferences(context).setStatus("srv-1", PushStatus.Registered)
        PushRuntime.watchWidgets(context)
        context.getSharedPreferences(com.bloemstudios.admin.alerts.SurfacePreferences.FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("tile_added", true).putBoolean("requests_tile_added", true).commit()
        assertEquals(0, calls)
    }

    @Test fun watchingTwiceDoesNotReportTwice() {
        var calls = 0
        PushRuntime.onWidgetsChanged = { calls++ }
        PushRuntime.preferences(context).setStatus("srv-1", PushStatus.Registered)
        PushRuntime.watchWidgets(context)
        PushRuntime.watchWidgets(context)
        surfaces().setWidget(1, WidgetKind.Nodes, null)
        // A widget is recorded as two keys (its server and its kind), so one listener sees two changes for one widget.
        assertEquals("one listener, however often the app asks", 2, calls)
    }
}
