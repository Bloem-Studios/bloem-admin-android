package com.bloemstudios.admin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakePushSession(
    private val capable: Map<String, Boolean?> = emptyMap(),
    private val signedIn: Set<String> = emptySet(),
    private val wakes: Set<String> = emptySet(),
) : PushServerSession {
    val widgetCalls = mutableListOf<Triple<String, String, WidgetReport>>()
    var widgetsError: Exception? = null

    override suspend fun supportsWidgetWakes(server: AdminServer) = server.id in wakes

    override suspend fun setWidgets(server: AdminServer, deviceId: String, report: WidgetReport) {
        widgetsError?.let { throw it }
        widgetCalls += Triple(server.id, deviceId, report)
    }

    val probes = mutableListOf<String>()
    val registered = mutableListOf<Pair<String, PushRegisterRequest>>()
    val kindCalls = mutableListOf<Triple<String, String, Map<String, Boolean>>>()
    var registerError: Exception? = null
    var kindsError: Exception? = null
    private var counter = 0

    override fun hasSession(server: AdminServer) = server.id in signedIn
    override suspend fun pushCapable(server: AdminServer): Boolean? {
        probes += server.id
        return capable[server.id]
    }

    override suspend fun register(server: AdminServer, request: PushRegisterRequest): PushRegistered {
        registerError?.let { throw it }
        registered += server.id to request
        counter++
        return PushRegistered("dev-${server.id}", "bad_token_$counter")
    }

    override suspend fun setKinds(server: AdminServer, deviceId: String, kinds: Map<String, Boolean>) {
        kindsError?.let { throw it }
        kindCalls += Triple(server.id, deviceId, kinds)
    }

    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
class PushRegistrarTest {
    private lateinit var context: Context
    private lateinit var preferences: PushPreferences
    private lateinit var registrations: PushRegistrationStore

    private val bloem = AdminServer("bloem", "https://bloem.example.test", "Home")
    private val silo = AdminServer("silo", "https://silo.example.test", "Plain Silo")
    private val offline = AdminServer("offline", "https://down.example.test", "Down")
    private var now = 1_000_000L
    private var fcm: String? = "f".repeat(64)
    private var allowed = true

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("push_test_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("push_test_regs", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = PushPreferences(context.getSharedPreferences("push_test_prefs", Context.MODE_PRIVATE))
        registrations = PushRegistrationStore(context.getSharedPreferences("push_test_regs", Context.MODE_PRIVATE))
    }

    private fun registrar(
        session: PushServerSession,
        saved: List<AdminServer> = listOf(bloem, silo),
        firebase: Boolean = true,
        retire: PushContentClient = PushContentClient(engineFactory = { MockEngine { respond("", HttpStatusCode.NoContent) } }),
        placed: () -> List<com.bloemstudios.admin.widget.PlacedWidget> = { emptyList() },
    ) = PushRegistrar(
        servers = { saved }, session = session, registrations = registrations, preferences = preferences, content = retire,
        fcmToken = { fcm }, firebaseConfigured = firebase, notificationsAllowed = { allowed }, clock = { now },
        widgets = PlacedWidgets { placed() },
    )

    @Test fun aPlainSiloServerIsOnlyAskedTheCapabilityQuestionAndNeverRegistered() = runBlocking {
        val session = FakePushSession(capable = mapOf("silo" to false), signedIn = setOf("silo"))
        assertEquals(0, registrar(session).reconcile())
        assertEquals(listOf("silo"), session.probes)
        assertTrue("a plain Silo server must never get a registration", session.registered.isEmpty())
        assertNull(registrations.get("silo"))
        assertEquals(PushStatus.NotAvailable, preferences.status("silo"))
    }

    @Test fun aBloemServerThatListsTheTokenIsRegisteredOnceWithThisInstallation() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        assertEquals(1, registrar(session).reconcile())
        val (id, request) = session.registered.single()
        assertEquals("bloem", id)
        assertEquals("android", request.platform)
        assertEquals(fcm, request.token)
        assertEquals(preferences.installationId(), request.installationId)
        assertEquals(PushRegistration("dev-bloem", "bad_token_1", PushRegistrar.sha256(fcm!!), now), registrations.get("bloem"))
        assertEquals(PushStatus.Registered, preferences.status("bloem"))
    }

    @Test fun theSameTokenIsNotRegisteredAgainBecauseThatWouldReplaceTheDisplayToken() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        val r = registrar(session)
        r.reconcile()
        now += 60_000
        assertEquals(0, r.reconcile())
        assertEquals(1, session.registered.size)
        assertEquals("bad_token_1", registrations.get("bloem")!!.displayToken)
    }

    @Test fun aRotatedFcmTokenAndAWeekOfAgeBothRegisterAgain() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        val r = registrar(session)
        r.reconcile()
        fcm = "e".repeat(64)
        assertEquals(1, r.reconcile())
        now += PushRegistrar.REFRESH_AFTER_MILLIS + 1
        assertEquals(1, r.reconcile())
        assertEquals(3, session.registered.size)
    }

    @Test fun onlyTheServerKindsAreSentAsSwitchedOff() = runBlocking {
        preferences.setKindEnabled("bloem", PushKind.BACKUP_FAILED, false)
        preferences.setKindEnabled("bloem", PushKind.SERVER_UP, false) // local: never leaves the device
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        registrar(session).reconcile()
        assertEquals(listOf(PushKind.BACKUP_FAILED), session.registered.single().second.disabledKinds)
    }

    @Test fun aServerThatCouldNotBeAskedIsLeftAsItWasAndNothingIsSent() = runBlocking {
        preferences.setStatus("offline", PushStatus.Registered)
        val session = FakePushSession(capable = mapOf("offline" to null), signedIn = setOf("offline"))
        assertEquals(0, registrar(session, saved = listOf(offline)).reconcile())
        assertTrue(session.registered.isEmpty())
        assertEquals(PushStatus.Registered, preferences.status("offline"))
    }

    @Test fun aServerWithNoSignInIsSkippedWithoutAnyRequest() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = emptySet())
        registrar(session).reconcile()
        assertTrue(session.probes.isEmpty())
        assertTrue(session.registered.isEmpty())
    }

    @Test fun aServerThatStopsListingPushLosesItsRegistration() = runBlocking {
        registrations.put("bloem", PushRegistration("dev-bloem", "bad_old", PushRegistrar.sha256(fcm!!), now))
        val session = FakePushSession(capable = mapOf("bloem" to false), signedIn = setOf("bloem"))
        registrar(session).reconcile()
        assertNull(registrations.get("bloem"))
        assertEquals(PushStatus.NotAvailable, preferences.status("bloem"))
    }

    @Test fun withoutFirebaseNothingIsRegisteredButEachServerStillSaysTheRightThing() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true, "silo" to false), signedIn = setOf("bloem", "silo"))
        assertEquals(0, registrar(session, firebase = false).reconcile())
        assertTrue(session.registered.isEmpty())
        assertEquals(PushStatus.NotConfigured, preferences.status("bloem"))
        assertEquals(PushStatus.NotAvailable, preferences.status("silo"))
    }

    @Test fun withNoNotificationPermissionTheDeviceIsRegisteredButSaysItNeedsPermission() = runBlocking {
        allowed = false
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        registrar(session).reconcile()
        assertEquals(PushStatus.NeedsPermission, preferences.status("bloem"))
        assertNotNull(registrations.get("bloem"))
    }

    @Test fun aFailedRegistrationIsRemembered_andRetriedOnTheNextPass() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        val r = registrar(session)
        session.registerError = java.io.IOException("offline")
        assertEquals(0, r.reconcile())
        assertEquals(PushStatus.Failed, preferences.status("bloem"))
        session.registerError = null
        assertEquals(1, r.reconcile())
        assertEquals(PushStatus.Registered, preferences.status("bloem"))
    }

    @Test fun aServerThatRefusesWithServiceUnavailableIsNotAvailableNotFailed() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        session.registerError = ApiError(503, "admin_push_unavailable", "off")
        registrar(session).reconcile()
        assertEquals(PushStatus.NotAvailable, preferences.status("bloem"))
    }

    @Test fun aRemovedServersRegistrationAndChoicesAreForgotten() = runBlocking {
        registrations.put("gone", PushRegistration("dev-gone", "bad_x"))
        preferences.setKindEnabled("gone", PushKind.SCAN_FAILED, false)
        preferences.setStatus("gone", PushStatus.Registered)
        registrar(FakePushSession()).reconcile()
        assertNull(registrations.get("gone"))
        assertTrue(preferences.disabledFor("gone").isEmpty())
        assertEquals(PushStatus.Unknown, preferences.status("gone"))
    }

    @Test fun switchingAServerKindIsKeptHereAndSentToTheServer() = runBlocking {
        registrations.put("bloem", PushRegistration("dev-bloem", "bad_x", "h", now))
        val session = FakePushSession(signedIn = setOf("bloem"))
        registrar(session).setKind(bloem, PushKind.NODE_OFFLINE, false)
        assertFalse(preferences.isKindEnabled("bloem", PushKind.NODE_OFFLINE))
        assertEquals(Triple("bloem", "dev-bloem", mapOf(PushKind.NODE_OFFLINE to false)), session.kindCalls.single())
    }

    @Test fun switchingALocalKindNeverReachesTheServer() = runBlocking {
        registrations.put("bloem", PushRegistration("dev-bloem", "bad_x", "h", now))
        val session = FakePushSession(signedIn = setOf("bloem"))
        registrar(session).setKind(bloem, PushKind.SERVER_UP, false)
        assertFalse(preferences.isKindEnabled("bloem", PushKind.SERVER_UP))
        assertTrue(session.kindCalls.isEmpty())
    }

    @Test fun aSwitchTheServerCouldNotHearMakesTheNextPassSendTheChoicesAgain() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        val r = registrar(session)
        r.reconcile()
        session.kindsError = java.io.IOException("offline")
        r.setKind(bloem, PushKind.SCAN_FAILED, false)
        assertFalse("the choice is kept on the device even when the server was not told", preferences.isKindEnabled("bloem", PushKind.SCAN_FAILED))
        session.kindsError = null
        assertEquals(1, r.reconcile())
        assertEquals(listOf(PushKind.SCAN_FAILED), session.registered.last().second.disabledKinds)
    }

    @Test fun unregisteringRetiresByDisplayTokenAndDropsTheRecord() = runBlocking {
        registrations.put("bloem", PushRegistration("dev-bloem", "bad_secret"))
        var seen: Triple<HttpMethod, String, String?>? = null
        val client = PushContentClient(engineFactory = {
            MockEngine { request ->
                seen = Triple(request.method, request.url.encodedPath, request.headers[HttpHeaders.Authorization])
                respond("", HttpStatusCode.NoContent)
            }
        })
        registrar(FakePushSession(), retire = client).unregister(bloem)
        assertEquals(Triple(HttpMethod.Post, "/api/bloem/v2/admin/push/devices/dev-bloem/retire", "Bearer bad_secret"), seen)
        assertNull(registrations.get("bloem"))
    }

    @Test fun aServerThatDoesNotAnswerCannotHangSignOutAndTheRecordIsStillDropped() = runBlocking {
        registrations.put("bloem", PushRegistration("dev-bloem", "bad_secret"))
        val client = PushContentClient(timeoutMillis = 100, engineFactory = { MockEngine { kotlinx.coroutines.delay(10_000); respond("", HttpStatusCode.NoContent) } })
        val started = System.currentTimeMillis()
        registrar(FakePushSession(), retire = client).unregister(bloem)
        assertTrue("unregister waited ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 4_000)
        assertNull(registrations.get("bloem"))
    }

    @Test fun unregisteringAServerThatWasNeverRegisteredSendsNothing() = runBlocking {
        var called = false
        val client = PushContentClient(engineFactory = { MockEngine { called = true; respond("", HttpStatusCode.NoContent) } })
        registrar(FakePushSession(), retire = client).unregister(silo)
        assertFalse(called)
    }

    @Test fun theInstallationIdIsStableAndTheDisplayTokenNeverAppearsInToString() {
        assertEquals(preferences.installationId(), preferences.installationId())
        assertTrue(preferences.installationId().length >= 8)
        assertFalse(PushRegistration("dev", "bad_super_secret").toString().contains("super_secret"))
        assertEquals("dev", registrations.let { it.put("s", PushRegistration("dev", "bad_x")); it.serverFor("dev")!!.second.deviceId })
        assertEquals("s", registrations.serverFor("dev")!!.first)
        assertNull(registrations.serverFor("other"))
    }


    private fun widget(id: Int, kind: com.bloemstudios.admin.widget.WidgetKind, target: String?) = com.bloemstudios.admin.widget.PlacedWidget(id, kind, target)

    @Test fun aServerThatSendsWakesIsToldWhichWidgetsAreOnThisDeviceWhenItRegisters() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"), wakes = setOf("bloem"))
        val placed = listOf(widget(1, com.bloemstudios.admin.widget.WidgetKind.Requests, "bloem"), widget(2, com.bloemstudios.admin.widget.WidgetKind.NowPlaying, "bloem"))
        registrar(session, placed = { placed }).reconcile()
        val request = session.registered.single().second
        assertEquals(true, request.widgetsPlaced)
        assertEquals(listOf("requests", "sessions"), request.widgetChannels)
        assertEquals("requests,sessions", registrations.get("bloem")!!.widgetReport)
        assertTrue("the registration already carried the report", session.widgetCalls.isEmpty())
    }

    @Test fun aServerWithoutWakesIsNeverSentTheWidgetReport() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"))
        val placed = listOf(widget(1, com.bloemstudios.admin.widget.WidgetKind.Requests, "bloem"))
        val r = registrar(session, placed = { placed })
        r.reconcile()
        val request = session.registered.single().second
        assertNull(request.widgetsPlaced)
        assertNull(request.widgetChannels)
        now += 60_000
        r.reconcile()
        assertTrue(session.widgetCalls.isEmpty())
        assertEquals(PushRegistrar.NOT_REPORTED, registrations.get("bloem")!!.widgetReport)
    }

    @Test fun addingAndRemovingWidgetsIsReportedOnceEachAndAnUnchangedSetIsNot() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"), wakes = setOf("bloem"))
        var placed = listOf<com.bloemstudios.admin.widget.PlacedWidget>()
        val r = registrar(session, placed = { placed })
        r.reconcile() // registers with no widget placed
        assertEquals(false, session.registered.single().second.widgetsPlaced)
        r.reconcile()
        assertTrue("an unchanged report is not sent again", session.widgetCalls.isEmpty())

        placed = listOf(widget(1, com.bloemstudios.admin.widget.WidgetKind.Nodes, "bloem"))
        r.reconcile()
        assertEquals(WidgetReport(true, listOf("nodes")), session.widgetCalls.single().third)
        r.reconcile()
        assertEquals("still one call for the same widgets", 1, session.widgetCalls.size)

        placed = placed + widget(2, com.bloemstudios.admin.widget.WidgetKind.Libraries, null) // an All servers widget shows this server too
        r.reconcile()
        assertEquals(WidgetReport(true, listOf("libraries", "nodes")), session.widgetCalls.last().third)

        placed = emptyList() // the last widget is removed
        r.reconcile()
        assertEquals(WidgetReport.None, session.widgetCalls.last().third)
        assertEquals(3, session.widgetCalls.size)
        assertEquals(1, session.registered.size)
    }

    @Test fun aWidgetForAnotherServerAndAServerStatusWidgetDoNotMakeThisServerSendWakes() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"), wakes = setOf("bloem"))
        val placed = listOf(
            widget(1, com.bloemstudios.admin.widget.WidgetKind.Requests, "someone-else"),
            widget(2, com.bloemstudios.admin.widget.WidgetKind.ServerStatus, "bloem"),
        )
        registrar(session, placed = { placed }).reconcile()
        assertEquals(false, session.registered.single().second.widgetsPlaced)
        assertEquals(emptyList<String>(), session.registered.single().second.widgetChannels)
    }

    @Test fun aReportTheServerCouldNotTakeIsTriedAgainOnTheNextPass() = runBlocking {
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"), wakes = setOf("bloem"))
        var placed = listOf<com.bloemstudios.admin.widget.PlacedWidget>()
        val r = registrar(session, placed = { placed })
        r.reconcile()
        placed = listOf(widget(1, com.bloemstudios.admin.widget.WidgetKind.Requests, "bloem"))
        session.widgetsError = java.io.IOException("offline")
        r.reconcile()
        assertTrue(session.widgetCalls.isEmpty())
        assertEquals("", registrations.get("bloem")!!.widgetReport)
        session.widgetsError = null
        r.reconcile()
        assertEquals(WidgetReport(true, listOf("requests")), session.widgetCalls.single().third)
    }

    @Test fun aRegistrationFromBeforeTheReportExistedIsToldOnceEvenWithNoWidgets() = runBlocking {
        registrations.put("bloem", PushRegistration("dev-bloem", "bad_x", PushRegistrar.sha256(fcm!!), now))
        val session = FakePushSession(capable = mapOf("bloem" to true), signedIn = setOf("bloem"), wakes = setOf("bloem"))
        registrar(session).reconcile()
        assertEquals(WidgetReport.None, session.widgetCalls.single().third)
        assertEquals("", registrations.get("bloem")!!.widgetReport)
    }
}
