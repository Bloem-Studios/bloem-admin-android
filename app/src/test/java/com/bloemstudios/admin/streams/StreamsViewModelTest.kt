package com.bloemstudios.admin.streams

import com.bloemstudios.admin.auth.V2Fixtures
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.network.ApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.*

class StreamsViewModelTest {
    @Test fun `refresh without foreground owner performs no session read`() = runTest {
        var reads = 0
        val client = ApiClient("https://example.test", null, MockEngine { reads++; respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try { StreamsViewModel().load(client); assertEquals(0, reads) } finally { client.close() }
    }

    @Test
    fun `load decodes sessions`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession(optional = """"client_name":"Silo Android"""")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()

        val owner = backgroundScope.launch { client.liveSessions.observe() }
        val rows = withContext(Dispatchers.Default) { withTimeout(5_000) { client.liveSessions.sessions.first { it != null } } }!!
        owner.cancel()
        client.close()
        assertEquals(1, rows.size)
        assertEquals("mchan969", rows.first().username)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load decodes new nullable fields when present`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession(title = "Pilot", playMethod = "transcode", optional = """"profile_name":"Living room","device_platform":"android","client_name":"Silo Android","video_decision":"transcode","audio_decision":"transcode","effective_play_method":"transcode","transcode_hw_accel":"nvenc","source_video_resolution":"1920x1080","source_video_codec":"h264","target_resolution":"1280x720","target_video_codec":"h264","source_audio_codec":"aac","target_audio_codec":"aac","series_name":"Some Show","episode_name":"Pilot","season_number":1,"episode_number":1""").replace("\"stream_bitrate_kbps\":null", "\"stream_bitrate_kbps\":5000")),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()

        val owner = backgroundScope.launch { client.liveSessions.observe() }
        val session = withContext(Dispatchers.Default) { withTimeout(5_000) { client.liveSessions.sessions.first { it != null } } }!!.first()
        owner.cancel()
        client.close()
        assertEquals("user-1", session.user_id)
        assertEquals("profile-1", session.profile_id)
        assertEquals("Living room", session.profile_name)
        assertEquals("android", session.device_platform)
        assertEquals(5000, session.stream_bitrate_kbps)
        assertEquals("transcode", session.video_decision)
        assertEquals("transcode", session.audio_decision)
        assertEquals("transcode", session.effective_play_method)
        assertEquals("nvenc", session.transcode_hw_accel)
        assertEquals("1920x1080", session.source_video_resolution)
        assertEquals("h264", session.source_video_codec)
        assertEquals("1280x720", session.target_resolution)
        assertEquals("h264", session.target_video_codec)
        assertEquals("aac", session.source_audio_codec)
        assertEquals("aac", session.target_audio_codec)
        assertEquals("Some Show", session.series_name)
        assertEquals("Pilot", session.episode_name)
        assertEquals(1, session.season_number)
        assertEquals(1, session.episode_number)
    }

    @Test
    fun `load decodes sessions missing the new fields with nulls, not a decode failure`() = runTest {
        // A schema-complete row carries none of the optional members; they decode as null.
        val engine = MockEngine {
            respond(
                content = V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()

        val owner = backgroundScope.launch { client.liveSessions.observe() }
        val session = withContext(Dispatchers.Default) { withTimeout(5_000) { client.liveSessions.sessions.first { it != null } } }!!.first()
        owner.cancel()
        client.close()
        assertNull(session.stream_bitrate_kbps)
        assertNull(session.profile_name)
        assertNull(session.device_platform)
        assertNull(session.client_name)
        assertNull(session.video_decision)
        assertNull(session.audio_decision)
        assertNull(session.effective_play_method)
        assertNull(session.transcode_hw_accel)
        assertNull(session.source_video_resolution)
        assertNull(session.source_video_codec)
        assertNull(session.target_resolution)
        assertNull(session.target_video_codec)
        assertNull(session.source_audio_codec)
        assertNull(session.target_audio_codec)
        assertNull(session.series_name)
        assertNull(session.episode_name)
        assertNull(session.season_number)
        assertNull(session.episode_number)
    }

    @Test
    fun `sendMessage posts title and message and returns Success on 202`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            capturedBodies.add((request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(
                content = V2Fixtures.PLAYBACK_COMMAND_RECEIPT,
                status = HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()
        val target = AdminSession("s1", "user-1", "mchan969", "profile-1", "X", "series", "direct", 0.0, 100.0, false, "Silo Android")

        val result = viewModel.sendMessage(target, "Heads up", "Server restarting soon", client)

        assertTrue(result is SendMessageResult.Success)
        assertTrue(capturedBodies.first().contains("\"message\":\"Server restarting soon\""))
        assertTrue(capturedBodies.first().contains("\"title\":\"Heads up\""))
        assertTrue(capturedBodies.first().contains("\"command_id\""))
        assertTrue(capturedBodies.first().contains("\"sequence\""))
    }

    @Test
    fun `sendMessage returns ClientNotConnected on 409`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.PROBLEM_CONFLICT,
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()
        val target = AdminSession("s1", "user-1", "mchan969", "profile-1", "X", "series", "direct", 0.0, 100.0, false, "Silo Android")

        val result = viewModel.sendMessage(target, null, "Hello", client)

        assertTrue(result is SendMessageResult.ClientNotConnected)
    }

    @Test
    fun `sendMessage returns a generic Failure on other errors`() = runTest {
        val engine = MockEngine {
            respond(
                content = V2Fixtures.PROBLEM_CONFLICT.replace("409", "404").replace("Conflict", "Not Found"),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()
        val target = AdminSession("s1", "user-1", "mchan969", "profile-1", "X", "series", "direct", 0.0, 100.0, false, "Silo Android")

        val result = viewModel.sendMessage(target, null, "Hello", client)

        assertTrue(result is SendMessageResult.Failure)
    }

    @Test
    fun `stop posts to the v2 stop endpoint with a command identity`() = runTest {
        // act() reloads sessions on success (quality bar: reload only on
        // success), so a second GET request follows the mutating POST.
        // Capture every request path and assert on the first one — the
        // mutating call this test is about — rather than a single var
        // that the reload's GET would silently overwrite.
        val capturedPaths = mutableListOf<String>()
        val capturedBodies = mutableListOf<String?>()
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath)
            capturedBodies.add((request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString())
            respond(content = V2Fixtures.PLAYBACK_COMMAND_RECEIPT, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()
        val target = AdminSession("s1", "user-1", "mchan969", "profile-1", "X", "series", "direct", 0.0, 100.0, false, "Silo Android")

        viewModel.stop(target, client)

        assertEquals("/api/v2/admin/sessions/s1/stop", capturedPaths.first())
        assertTrue(capturedBodies.first()!!.contains("\"command_id\""))
    }

    @Test
    fun `consecutive commands on one session get rising sequences and fresh command ids`() = runTest {
        val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val engine = MockEngine { request ->
            (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.let {
                bodies += kotlinx.serialization.json.Json.parseToJsonElement(it.bytes().decodeToString()) as kotlinx.serialization.json.JsonObject
            }
            respond(content = V2Fixtures.PLAYBACK_COMMAND_RECEIPT, status = HttpStatusCode.Accepted, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel()
        val target = AdminSession("s1", "user-1", "mchan969", "profile-1", "X", "series", "direct", 0.0, 100.0, false, "Silo Android")
        viewModel.pause(target, client); viewModel.resume(target, client)
        val sequences = bodies.map { (it.getValue("sequence") as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
        assertEquals(2, sequences.size)
        assert(sequences[1] > sequences[0])
        assertNotEquals(bodies[0]["command_id"], bodies[1]["command_id"])
    }

    @Test
    fun `stale message refusal shows stale text and the next pause uses a sequence above the header`() = runTest {
        val seqs = mutableListOf<Long>(); var stale = true
        val engine = MockEngine { request ->
            val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            seqs += (kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject).getValue("sequence").toString().toLong()
            if (stale) { stale = false; respond(V2Fixtures.PROBLEM_CONFLICT, HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType to listOf("application/problem+json"), "X-Silo-Latest-Sequence" to listOf("9000"))) }
            else respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = StreamsViewModel(PlaybackCommandSequencer { 1000L })
        val target = AdminSession("s1", "user-1", "mchan969", "profile-1", "X", "series", "direct", 0.0, 100.0, false, "Silo Android")
        val result = viewModel.sendMessage(target, null, "Hello", client)
        assertEquals(com.bloemstudios.admin.network.ErrorText.Res(com.bloemstudios.admin.R.string.error_stream_command_stale), (result as SendMessageResult.Failure).message)
        viewModel.pause(target, client)
        assertEquals(listOf(1000L, 9001L), seqs)
    }
}
