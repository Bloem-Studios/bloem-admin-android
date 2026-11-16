package com.bloemstudios.admin.organization

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import com.bloemstudios.admin.streams.PlaybackCommandSequencer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationStreamsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = "/api/bloem/v2/admin/organization"
    private val sent = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private var caps = F.supportCapabilities()
    private var streams: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to F.supportStreams(F.supportStream("st1"), F.supportStream("st2", person = "Ana", canStop = false)) }
    private var stop: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to F.stopped() }
    private var message: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.Accepted to F.messageReceipt() }
    private var passes = 0

    private suspend fun opened(): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine {
            passes += 1
            respond(F.session(token = "pass-$passes"), HttpStatusCode.OK, json)
        })
        val session = OrganizationSession(
            account, F.ORG_ID, clock = { Instant.parse("2026-11-13T12:00:00Z") },
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    sent += request
                    bodies += String(request.body.toByteArray())
                    val path = request.url.encodedPath
                    val (status, body) = when {
                        path == "$base/support-capabilities" -> HttpStatusCode.OK to caps
                        path == "$base/streams" -> streams(request)
                        path.endsWith("/stop") -> stop(request)
                        path.endsWith("/message") -> message(request)
                        else -> HttpStatusCode.NotFound to F.error("not_found")
                    }
                    respond(body, status, json)
                }, recovery)
            },
        )
        session.open()
        return session
    }

    private fun bodyOf(index: Int) = Json.parseToJsonElement(bodies[index]).jsonObject
    private fun lastMessageBody() = bodyOf(sent.indexOfLast { it.url.encodedPath.endsWith("/message") })

    // ---- The list ----------------------------------------------------------------------------------------

    @Test
    fun `the streams of the organization load with who can be stopped`() = runTest {
        val vm = OrganizationStreamsViewModel()
        vm.load(opened())
        val state = vm.state.value
        assertEquals(listOf("st1", "st2"), state.streams.map { it.session_id })
        assertTrue(state.streams[0].can_stop)
        assertFalse(state.streams[1].can_stop)
        assertFalse(state.loading)
        assertNull(state.error)
        assertFalse(state.truncated)
    }

    @Test
    fun `more pages are followed up to a limit`() = runTest {
        var page = 0
        streams = { request ->
            page += 1
            val cursor = request.url.parameters["cursor"]
            HttpStatusCode.OK to F.supportStreams(F.supportStream("st$page"), next = if (page < 9) "c$page" else null)
        }
        val vm = OrganizationStreamsViewModel()
        vm.load(opened())
        assertEquals(OrganizationStreamsViewModel.MAX_PAGES, vm.state.value.streams.size)
        assertTrue("there were more", vm.state.value.truncated)
        assertEquals("c1", sent.filter { it.url.encodedPath == "$base/streams" }[1].url.parameters["cursor"])
        assertEquals("100", sent.first { it.url.encodedPath == "$base/streams" }.url.parameters["limit"])
    }

    @Test
    fun `a server that cannot list streams is not asked`() = runTest {
        caps = F.supportCapabilities(list = false)
        val vm = OrganizationStreamsViewModel()
        vm.load(opened())
        assertTrue(vm.state.value.unavailable)
        assertTrue(sent.none { it.url.encodedPath == "$base/streams" })
    }

    @Test
    fun `an older server shows an empty unavailable page`() = runTest {
        val vm = OrganizationStreamsViewModel()
        // No capabilities route: the session treats that as nothing offered.
        caps = F.error("not_found")
        val session = opened()
        vm.load(session)
        assertTrue(vm.state.value.unavailable)
    }

    @Test
    fun `a failed read keeps the streams on screen and says so`() = runTest {
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        streams = { HttpStatusCode.ServiceUnavailable to F.error("tenant_unavailable", "Tenant administration is unavailable") }
        vm.load(session)
        assertEquals(2, vm.state.value.streams.size)
        assertEquals(ErrorText.Raw("Tenant administration is unavailable"), vm.state.value.error)
    }

    // ---- The row never carries an address, node or routing -------------------------------------------------------

    @Test
    fun `a stream maps to the Now Playing row without any address or routing`() {
        val episode = Json { ignoreUnknownKeys = true }.decodeFromString<SupportStream>(F.supportStream(title = "Pilot", series = "Show", season = 1, episode = 2))
        val row = episode.toAdminSession()
        assertEquals("st1", row.session_id)
        assertEquals("Sam", row.username)
        assertEquals("5", row.user_id)
        assertEquals("Show", row.series_name)
        assertEquals("Pilot", row.episode_name)
        assertEquals(1, row.season_number)
        assertEquals(2, row.episode_number)
        assertEquals("transcode", row.effective_play_method)
        assertEquals(3600.0, row.file_duration!!, 0.0)
        assertEquals("tvos", row.device_platform)
        assertEquals("Bloem tvOS", row.client_label)
        assertEquals("Bloem tvOS · Sam's TV", row.client_label_full)
        assertNull(row.client_ip)
        assertNull(row.reporting_node)
        assertNull(row.node_display_name)
        assertNull(row.routing_workload)
        assertNull(row.routing_execution)
        assertNull(row.routing_egress)
        assertNull(row.stream_location)
    }

    @Test
    fun `a film maps without an episode name`() {
        val film = Json { ignoreUnknownKeys = true }.decodeFromString<SupportStream>(F.supportStream(title = "Heat", series = null, season = null, episode = null)).toAdminSession()
        assertNull(film.series_name)
        assertNull(film.episode_name)
        assertEquals("Heat", film.media_title)
    }

    @Test
    fun `no field of a stream names an address`() {
        val names = SupportStream.serializer().descriptor.elementNames.toList() + SupportStreamDevice.serializer().descriptor.elementNames.toList()
        assertTrue(names.none { it.contains("ip") && !it.contains("pip") || it.contains("address") || it.contains("node") || it.contains("rout") })
    }

    // ---- Stop ---------------------------------------------------------------------------------------------

    @Test
    fun `stopping sends one stop and reads the list again`() = runTest {
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        var listed = 0
        streams = { listed += 1; HttpStatusCode.OK to F.supportStreams(F.supportStream("st2", person = "Ana", canStop = false)) }

        assertTrue(vm.stop(session, vm.state.value.streams.first()))

        val stops = sent.filter { it.url.encodedPath == "$base/streams/st1/stop" }
        assertEquals(1, stops.size)
        assertEquals(HttpMethod.Post, stops.single().method)
        assertEquals(1, listed)
        assertEquals(listOf("st2"), vm.state.value.streams.map { it.session_id })
        assertEquals(ErrorText.Res(R.string.organization_streams_notice_stopped), vm.state.value.notice)
        assertTrue(vm.state.value.pendingIds.isEmpty())
    }

    @Test
    fun `a stream the server marks as not stoppable is never stopped`() = runTest {
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertFalse(vm.stop(session, vm.state.value.streams.first { !it.can_stop }))
        assertTrue(sent.none { it.url.encodedPath.endsWith("/stop") })
    }

    @Test
    fun `stopping is not offered when the server cannot do it`() = runTest {
        caps = F.supportCapabilities(stop = false)
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertFalse(vm.stop(session, vm.state.value.streams.first()))
        assertTrue(sent.none { it.url.encodedPath.endsWith("/stop") })
    }

    @Test
    fun `a stop the server refuses says so and the list is read again`() = runTest {
        stop = { HttpStatusCode.Conflict to F.protectedTarget("platform_account") }
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertFalse(vm.stop(session, vm.state.value.streams.first()))
        assertEquals(ErrorText.Res(R.string.organization_support_protected_platform), vm.state.value.error)
        assertEquals(2, sent.count { it.url.encodedPath == "$base/streams" })
    }

    // ---- Message ------------------------------------------------------------------------------------------

    private suspend fun sendTo(vm: OrganizationStreamsViewModel, session: OrganizationSession, title: String? = null, text: String = "Please turn it down") =
        vm.sendMessage(session, vm.state.value.streams.first(), title, text)

    @Test
    fun `a message goes out once with a fresh identity above the last`() = runTest {
        val vm = OrganizationStreamsViewModel(PlaybackCommandSequencer { 1_000L })
        val session = opened()
        vm.load(session)
        assertEquals(StreamMessageResult.Sent(queued = false), sendTo(vm, session, "Hello"))
        assertEquals(StreamMessageResult.Sent(queued = false), sendTo(vm, session, "Hello", "Another"))
        val bodies = sent.indices.filter { sent[it].url.encodedPath.endsWith("/message") }.map { bodyOf(it) }
        assertEquals(2, bodies.size)
        assertNotEquals(bodies[0].getValue("command_id").jsonPrimitive.content, bodies[1].getValue("command_id").jsonPrimitive.content)
        assertEquals("1000", bodies[0].getValue("sequence").jsonPrimitive.content)
        assertEquals("1001", bodies[1].getValue("sequence").jsonPrimitive.content)
        assertEquals("Hello", bodies[0].getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `a message accepted for later says it was queued`() = runTest {
        message = { HttpStatusCode.Accepted to F.messageReceipt(delivery = "fallback_scheduled") }
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertEquals(StreamMessageResult.Sent(queued = true), sendTo(vm, session))
    }

    @Test
    fun `a player that is not connected is not a failure of the stream`() = runTest {
        message = { HttpStatusCode.Conflict to F.error("realtime_unavailable", "The player is not connected") }
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertEquals(StreamMessageResult.ClientNotConnected, sendTo(vm, session))
    }

    @Test
    fun `a stale sequence moves the next one above the latest applied`() = runTest {
        var first = true
        message = {
            if (first) { first = false; HttpStatusCode.Conflict to F.error("stale_sequence") } else HttpStatusCode.OK to F.messageReceipt()
        }
        val vm = OrganizationStreamsViewModel(PlaybackCommandSequencer { 5L })
        val session = opened()
        vm.load(session)
        // The server's latest sequence header is read by the client; without it the refusal still reads as stale.
        val result = sendTo(vm, session)
        assertEquals(StreamMessageResult.Failed(ErrorText.Res(R.string.error_stream_command_stale)), result)
        assertEquals(StreamMessageResult.Sent(queued = false), sendTo(vm, session))
    }

    @Test
    fun `after the server could not confirm, the same text is sent under the same identity`() = runTest {
        var calls = 0
        message = {
            calls += 1
            if (calls == 1) HttpStatusCode.ServiceUnavailable to F.error("dependency_unavailable", "The server holding the session did not answer")
            else HttpStatusCode.OK to F.messageReceipt(outcome = "replayed")
        }
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertTrue(sendTo(vm, session) is StreamMessageResult.Failed)
        assertEquals(StreamMessageResult.Sent(queued = false), sendTo(vm, session))
        val identities = sent.indices.filter { sent[it].url.encodedPath.endsWith("/message") }.map { bodyOf(it) }
        assertEquals(identities[0].getValue("command_id"), identities[1].getValue("command_id"))
        assertEquals(identities[0].getValue("sequence"), identities[1].getValue("sequence"))
    }

    @Test
    fun `different text after an unconfirmed send is a new command`() = runTest {
        var calls = 0
        message = {
            calls += 1
            if (calls == 1) HttpStatusCode.ServiceUnavailable to F.error("dependency_unavailable") else HttpStatusCode.OK to F.messageReceipt()
        }
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        sendTo(vm, session, text = "One")
        sendTo(vm, session, text = "Two")
        val ids = sent.indices.filter { sent[it].url.encodedPath.endsWith("/message") }.map { bodyOf(it).getValue("command_id") }
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `a message is checked before anything is sent`() = runTest {
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertEquals(StreamMessageResult.Invalid(ErrorText.Res(R.string.organization_streams_message_empty)), sendTo(vm, session, text = "   "))
        assertEquals(StreamMessageResult.Invalid(ErrorText.Res(R.string.organization_streams_message_too_long)), sendTo(vm, session, text = "x".repeat(2049)))
        assertEquals(StreamMessageResult.Invalid(ErrorText.Res(R.string.organization_streams_title_too_long)), sendTo(vm, session, title = "t".repeat(257)))
        assertTrue(sent.none { it.url.encodedPath.endsWith("/message") })
        assertEquals(StreamMessageResult.Sent(queued = false), sendTo(vm, session, text = "x".repeat(2048)))
    }

    @Test
    fun `messages are not offered when the server cannot send them or the stream cannot take one`() = runTest {
        caps = F.supportCapabilities(message = false)
        val vm = OrganizationStreamsViewModel()
        val session = opened()
        vm.load(session)
        assertTrue(sendTo(vm, session) is StreamMessageResult.Failed)
        assertTrue(sent.none { it.url.encodedPath.endsWith("/message") })
    }
}
