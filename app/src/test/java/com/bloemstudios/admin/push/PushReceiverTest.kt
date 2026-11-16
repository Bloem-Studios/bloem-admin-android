package com.bloemstudios.admin.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.alerts.AlertSink
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class RecordingPoster : PushPoster {
    val posted = mutableListOf<Pair<AdminServer, PushContent>>()
    val generic = mutableListOf<Pair<AdminServer, String>>()
    val results = mutableListOf<List<Any?>>()
    var canShow = true

    override fun post(server: AdminServer, content: PushContent): Boolean { if (canShow) posted += server to content; return canShow }
    override fun postGeneric(server: AdminServer, deliveryId: String): Boolean { if (canShow) generic += server to deliveryId; return canShow }
    override fun showActionResult(server: AdminServer?, tag: String, requestId: String, text: String, failed: Boolean) {
        results += listOf(server?.id, tag, requestId, text, failed)
    }
}

private const val CONTENT_JSON = """{"delivery_id":"0b9e","kind":"admin.request_waiting","channel":"requests","title":"sam requested Dune","body":"3 waiting","lock_screen":"New request waiting","page":"requests","item_id":"req-1","subject_type":"request","actions":["approve","decline"],"badge":3,"created_at":"2026-11-15T22:00:00Z"}"""

@RunWith(RobolectricTestRunner::class)
class PushReceiverTest {
    private lateinit var preferences: PushPreferences
    private lateinit var registrations: PushRegistrationStore
    private val server = AdminServer("srv-1", "https://bloem.example.test", "Home")
    private val poster = RecordingPoster()
    private val wire = mapOf("bloem_delivery_id" to "dev1.0b9e", "bloem_mode" to "private_alert")

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("push_recv_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("push_recv_regs", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = PushPreferences(context.getSharedPreferences("push_recv_prefs", Context.MODE_PRIVATE))
        registrations = PushRegistrationStore(context.getSharedPreferences("push_recv_regs", Context.MODE_PRIVATE))
        registrations.put("srv-1", PushRegistration("dev1", "bad_secret"))
    }

    private fun receiver(engine: () -> io.ktor.client.engine.HttpClientEngine, saved: List<AdminServer> = listOf(server)) =
        PushReceiver({ saved }, registrations, preferences, PushContentClient(engineFactory = engine), poster)

    private fun json(body: String = CONTENT_JSON, status: HttpStatusCode = HttpStatusCode.OK): () -> io.ktor.client.engine.HttpClientEngine =
        { MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) } }

    @Test fun aPushIsFetchedFromTheServerItNamesWithTheDisplayTokenAndPosted() = runBlocking {
        var seen: Triple<HttpMethod, String, String?>? = null
        val engine = {
            MockEngine { request ->
                seen = Triple(request.method, request.url.toString(), request.headers[HttpHeaders.Authorization])
                respond(CONTENT_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        assertEquals(PushOutcome.Posted, receiver(engine).receive(wire))
        assertEquals(Triple(HttpMethod.Get, "https://bloem.example.test/api/bloem/v2/admin/push/content/dev1.0b9e", "Bearer bad_secret"), seen)
        val (to, content) = poster.posted.single()
        assertEquals("srv-1", to.id)
        assertEquals("sam requested Dune", content.title)
        assertEquals(listOf("approve", "decline"), content.actions)
    }

    @Test fun theServerIsFoundFromTheDeviceIdWhenSeveralAreSaved() = runBlocking {
        val other = AdminServer("srv-2", "https://other.example.test", "Other")
        registrations.put("srv-2", PushRegistration("dev2", "bad_other"))
        var url = ""
        val engine = { MockEngine { request -> url = request.url.toString(); respond(CONTENT_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) } }
        receiver(engine, saved = listOf(server, other)).receive(mapOf("bloem_delivery_id" to "dev2.0b9e"))
        assertTrue(url.startsWith("https://other.example.test/"))
        assertEquals("srv-2", poster.posted.single().first.id)
    }

    @Test fun aDeviceIdThisAppNeverRegisteredIsIgnoredAndNothingIsFetched() = runBlocking {
        var called = false
        val engine = { MockEngine { called = true; respond(CONTENT_JSON, HttpStatusCode.OK) } }
        assertEquals(PushOutcome.Ignored, receiver(engine).receive(mapOf("bloem_delivery_id" to "stranger.0b9e")))
        assertFalse(called)
        assertTrue(poster.posted.isEmpty() && poster.generic.isEmpty())
    }

    @Test fun aRegistrationForAServerNoLongerSavedIsIgnored() = runBlocking {
        assertEquals(PushOutcome.Ignored, receiver(json(), saved = emptyList()).receive(wire))
        assertTrue(poster.posted.isEmpty() && poster.generic.isEmpty())
    }

    @Test fun aBackgroundWakeAndAMalformedMessageShowNothing() = runBlocking {
        assertEquals(PushOutcome.Ignored, receiver(json()).receive(mapOf("bloem_delivery_id" to "dev1.0b9e", "bloem_mode" to "background_wake")))
        assertEquals(PushOutcome.Ignored, receiver(json()).receive(mapOf("title" to "Free gift", "body" to "tap")))
        assertTrue(poster.posted.isEmpty() && poster.generic.isEmpty())
    }

    @Test fun aServerThatSaysNoOrDoesNotAnswerLeavesOnlyTheGenericNotice() = runBlocking {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)) {
            assertEquals(PushOutcome.PostedGeneric, receiver(json("", status)).receive(wire))
        }
        assertEquals(PushOutcome.PostedGeneric, receiver(json("not json at all")).receive(wire))
        assertEquals(PushOutcome.PostedGeneric, receiver({ MockEngine { throw java.io.IOException("offline") } }).receive(wire))
        // A server that accepts the connection and then says nothing is cut off by the client's own timeout.
        val slow = PushReceiver({ listOf(server) }, registrations, preferences, PushContentClient(timeoutMillis = 100, engineFactory = { MockEngine { kotlinx.coroutines.delay(10_000); respond("", HttpStatusCode.OK) } }), poster)
        val started = System.currentTimeMillis()
        assertEquals(PushOutcome.PostedGeneric, slow.receive(wire))
        assertTrue("waited ${System.currentTimeMillis() - started} ms for a silent server", System.currentTimeMillis() - started < 4_000)
        assertTrue(poster.posted.isEmpty())
        assertEquals(6, poster.generic.size)
        assertEquals("0b9e", poster.generic.first().second)
    }

    @Test fun contentTheAppCannotShowFallsBackToTheGenericNotice() = runBlocking {
        val unknownKind = CONTENT_JSON.replace("admin.request_waiting", "admin.something_new")
        assertEquals(PushOutcome.PostedGeneric, receiver(json(unknownKind)).receive(wire))
        assertTrue(poster.posted.isEmpty())
    }

    @Test fun aKindSwitchedOffHereIsDroppedEvenIfTheServerStillSentIt() = runBlocking {
        preferences.setKindEnabled("srv-1", PushKind.REQUEST_WAITING, false)
        assertEquals(PushOutcome.Ignored, receiver(json()).receive(wire))
        assertTrue(poster.posted.isEmpty() && poster.generic.isEmpty())
    }

    @Test fun whenAndroidWillNotShowItTheOutcomeSaysSo() = runBlocking {
        poster.canShow = false
        assertEquals(PushOutcome.Suppressed, receiver(json()).receive(wire))
    }

    @Test fun serverTextIsCleanedBeforeItIsShown() = runBlocking {
        val noisy = CONTENT_JSON.replace("sam requested Dune", "sam\\nrequested\\u0000 Dune ").replace("\"approve\",\"decline\"", "\"approve\",\"decline\",\"delete\"")
        receiver(json(noisy)).receive(wire)
        val content = poster.posted.single().second
        assertEquals("sam requested Dune", content.title)
        assertEquals(listOf("approve", "decline"), content.actions)
    }
}

@RunWith(RobolectricTestRunner::class)
class PushActionRunnerTest {
    private val server = AdminServer("srv-1", "https://bloem.example.test", "Home")
    private val poster = RecordingPoster()
    private val labels = object : PushActionLabels {
        override fun done(approve: Boolean) = if (approve) "Approved" else "Declined"
        override fun alreadyDone() = "Already handled"
        override fun failed(approve: Boolean) = "Couldn't ${if (approve) "approve" else "decline"}, open the app"
    }

    private class Actions(private val error: Exception? = null) : PushRequestActions {
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        override suspend fun decide(server: AdminServer, requestId: String, approve: Boolean) {
            calls += Triple(server.id, requestId, approve)
            error?.let { throw it }
        }
    }

    private fun runner(actions: PushRequestActions, saved: List<AdminServer> = listOf(server)) =
        PushActionRunner({ saved }, actions, poster, labels)

    @Test fun approveAndDeclineCallTheSameRouteTheWidgetsDo() = runBlocking {
        val actions = Actions()
        assertTrue(runner(actions).run("srv-1", "req-1", approve = true, tag = "t1"))
        assertTrue(runner(actions).run("srv-1", "req-2", approve = false, tag = "t2"))
        assertEquals(listOf(Triple("srv-1", "req-1", true), Triple("srv-1", "req-2", false)), actions.calls)
        assertEquals(listOf("srv-1", "t1", "req-1", "Approved", false), poster.results[0])
        assertEquals(listOf("srv-1", "t2", "req-2", "Declined", false), poster.results[1])
    }

    @Test fun someoneElseHavingDecidedFirstCountsAsDone() = runBlocking {
        for (status in listOf(404, 409)) {
            poster.results.clear()
            assertTrue(runner(Actions(ApiError(status, "conflict", "already"))).run("srv-1", "req-1", true, "t"))
            assertEquals("Already handled", poster.results.single()[3])
            assertEquals(false, poster.results.single()[4])
        }
    }

    @Test fun aRealFailureSaysCouldntAndAsksToOpenTheApp() = runBlocking {
        for (error in listOf(ApiError(500, "boom", "x"), ApiError(403, "forbidden", "x"), java.io.IOException("offline"), IllegalStateException("not signed in"))) {
            poster.results.clear()
            assertFalse(runner(Actions(error)).run("srv-1", "req-1", true, "t"))
            assertEquals("Couldn't approve, open the app", poster.results.single()[3])
            assertEquals(true, poster.results.single()[4])
        }
        poster.results.clear()
        assertFalse(runner(Actions(java.io.IOException("offline"))).run("srv-1", "req-1", false, "t"))
        assertEquals("Couldn't decline, open the app", poster.results.single()[3])
    }

    @Test fun aServerRemovedSinceTheNoticeFailsWithoutAnyCall() = runBlocking {
        val actions = Actions()
        assertFalse(runner(actions, saved = emptyList()).run("srv-1", "req-1", true, "t"))
        assertTrue(actions.calls.isEmpty())
        assertNull(poster.results.single()[0])
        assertEquals(true, poster.results.single()[4])
    }
}

@RunWith(RobolectricTestRunner::class)
class KindGatedAlertSinkTest {
    private class Sink : AlertSink {
        val events = mutableListOf<String>()
        override fun down(server: AdminServer, failingSince: Long?, failures: Int) { events += "down:${server.id}" }
        override fun back(server: AdminServer) { events += "back:${server.id}" }
        override fun clear(serverId: String) { events += "clear:$serverId" }
    }

    @Test fun theBackNoteFollowsTheServerUpSwitchAndTheRestIsNeverGated() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("push_gate", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PushPreferences(context.getSharedPreferences("push_gate", Context.MODE_PRIVATE))
        val inner = Sink()
        val gated = KindGatedAlertSink(inner, preferences)
        val s = AdminServer("a", "https://a.example.test", "A")
        gated.down(s, 1L, 2)
        gated.back(s)
        preferences.setKindEnabled("a", PushKind.SERVER_UP, false)
        gated.back(s)
        gated.clear("a")
        // A different server is not affected by this one's switch.
        gated.back(AdminServer("b", "https://b.example.test", "B"))
        assertEquals(listOf("down:a", "back:a", "clear:a", "back:b"), inner.events)
    }
}
