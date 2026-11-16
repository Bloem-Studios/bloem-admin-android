package com.bloemstudios.admin.streams

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AggregateStreamsViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")
    private val row = AdminSession("one", "user-1", "Alex", "profile-1", "River", "movie", "direct", 0.0, null, false)

    @Test fun `aggregate refresh without owners performs no session reads`() = runTest {
        var reads = 0
        val client = ApiClient("https://example.test", null, MockEngine { reads++; respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try { AggregateStreamsViewModel().load(listOf(serverA, serverB)) { client }; assertEquals(0, reads) } finally { client.close() }
    }

    @Test fun `successful action reconciles only the acted on foreground repository`() = runTest {
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun api(server: AdminServer) = ApiClient(server.baseUrl, null, MockEngine { request ->
            if (request.url.encodedPath.endsWith("/admin/sessions")) counts.merge(server.id, 1, Int::plus)
            respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val a = api(serverA); val b = api(serverB)
        val ownerA = backgroundScope.launch { a.liveSessions.observe() }
        val ownerB = backgroundScope.launch { b.liveSessions.observe() }
        try {
            withContext(Dispatchers.Default) { withTimeout(5_000) { a.liveSessions.sessions.first { it != null }; b.liveSessions.sessions.first { it != null } } }
            val beforeB = counts[serverB.id]
            AggregateStreamsViewModel().stop(serverA, row) { if (it.id == serverA.id) a else b }
            assertEquals(2, counts[serverA.id]); assertEquals(beforeB, counts[serverB.id])
        } finally { ownerA.cancel(); ownerB.cancel(); a.close(); b.close() }
    }

    @Test fun `two servers with the same session id get separate sequences`() = runTest {
        val sequences = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()
        fun api(server: AdminServer) = ApiClient(server.baseUrl, null, MockEngine { request ->
            val body = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            if (body != null) sequences.getOrPut(server.id) { java.util.Collections.synchronizedList(mutableListOf()) } += kotlinx.serialization.json.Json.parseToJsonElement(body)
                .let { (it as kotlinx.serialization.json.JsonObject).getValue("sequence") as kotlinx.serialization.json.JsonPrimitive }.content.toLong()
            respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val a = api(serverA); val b = api(serverB)
        try {
            val model = AggregateStreamsViewModel(PlaybackCommandSequencer { 1000L })
            val pick = { s: AdminServer -> if (s.id == serverA.id) a else b }
            model.pause(serverA, row, pick); model.pause(serverA, row, pick); model.pause(serverB, row, pick)
            assertEquals(listOf(1000L, 1001L), sequences.getValue(serverA.id))
            assertEquals(listOf(1000L), sequences.getValue(serverB.id))
        } finally { a.close(); b.close() }
    }

    @Test fun `stale refusal shows stale text and advances that servers sequence`() = runTest {
        val seqs = mutableListOf<Long>(); var stale = true
        val client = ApiClient(serverA.baseUrl, null, MockEngine { request ->
            val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            seqs += (kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject).getValue("sequence").toString().toLong()
            if (stale) { stale = false; respond(V2Fixtures.PROBLEM_CONFLICT, HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType to listOf("application/problem+json"), "X-Silo-Latest-Sequence" to listOf("5000"))) }
            else respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val model = AggregateStreamsViewModel(PlaybackCommandSequencer { 1000L })
            assertEquals(com.bloemstudios.admin.ActionResult.Failure, model.pause(serverA, row) { client })
            assertEquals(com.bloemstudios.admin.network.ErrorText.Prefixed("Server A", com.bloemstudios.admin.network.ErrorText.Res(com.bloemstudios.admin.R.string.error_stream_command_stale)), model.errorMessage.value)
            model.pause(serverA, row) { client }
            assertEquals(listOf(1000L, 5001L), seqs)
        } finally { client.close() }
    }

    @Test fun `message goes to the chosen server and session with its own sequence and a blank title is dropped`() = runTest {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        fun api(server: AdminServer) = ApiClient(server.baseUrl, null, MockEngine { request ->
            requests += Triple(server.id, request.url.encodedPath, (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val a = api(serverA); val b = api(serverB)
        try {
            val model = AggregateStreamsViewModel(PlaybackCommandSequencer { 1000L })
            val pick = { s: AdminServer -> if (s.id == serverA.id) a else b }
            assertEquals(SendMessageResult.Success, model.sendMessage(serverB, row, "  ", "Back in five", pick))
            assertEquals(SendMessageResult.Success, model.sendMessage(serverB, row, "Heads up", "Back in five", pick))
            assertEquals(listOf("srv-b", "srv-b"), requests.map { it.first })
            assertEquals(listOf("/api/v2/admin/sessions/one/message", "/api/v2/admin/sessions/one/message"), requests.map { it.second })
            val first = kotlinx.serialization.json.Json.parseToJsonElement(requests[0].third) as kotlinx.serialization.json.JsonObject
            val second = kotlinx.serialization.json.Json.parseToJsonElement(requests[1].third) as kotlinx.serialization.json.JsonObject
            assertFalse(first.containsKey("title"))
            assertEquals("\"Heads up\"", second.getValue("title").toString())
            assertEquals(listOf("1000", "1001"), listOf(first, second).map { it.getValue("sequence").toString() })
        } finally { a.close(); b.close() }
    }

    @Test fun `message does not read the session list again`() = runTest {
        var reads = 0
        val client = ApiClient(serverA.baseUrl, null, MockEngine { request ->
            if (request.url.encodedPath.endsWith("/admin/sessions")) reads++
            respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { AggregateStreamsViewModel().sendMessage(serverA, row, null, "Hello") { client }; assertEquals(0, reads) } finally { client.close() }
    }

    @Test fun `message answers a client that is not connected and a stale command separately and names the server`() = runTest {
        var latest: String? = null
        val client = ApiClient(serverA.baseUrl, null, MockEngine {
            respond(V2Fixtures.PROBLEM_CONFLICT, HttpStatusCode.Conflict,
                if (latest == null) headersOf(HttpHeaders.ContentType, "application/problem+json")
                else headersOf(HttpHeaders.ContentType to listOf("application/problem+json"), "X-Silo-Latest-Sequence" to listOf(latest!!)))
        })
        try {
            val model = AggregateStreamsViewModel(PlaybackCommandSequencer { 1000L })
            assertEquals(SendMessageResult.ClientNotConnected, model.sendMessage(serverA, row, null, "Hello") { client })
            latest = "5000"
            assertEquals(
                SendMessageResult.Failure(com.bloemstudios.admin.network.ErrorText.Prefixed("Server A", com.bloemstudios.admin.network.ErrorText.Res(com.bloemstudios.admin.R.string.error_stream_command_stale))),
                model.sendMessage(serverA, row, null, "Hello") { client })
        } finally { client.close() }
    }

    @Test fun `message failure without a sentence names the server and the screen fallback with the status`() = runTest {
        val client = ApiClient(serverA.baseUrl, null, MockEngine { respond("<html>no</html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html")) })
        try {
            val result = AggregateStreamsViewModel().sendMessage(serverA, row, null, "Hello") { client }
            assertEquals(SendMessageResult.Failure(com.bloemstudios.admin.network.ErrorText.Prefixed("Server A",
                com.bloemstudios.admin.network.ErrorText.Http(com.bloemstudios.admin.R.string.error_action_failed, 502))), result)
        } finally { client.close() }
    }

    @Test fun `source refresh cannot erase an action failure`() = runTest {
        val client = ApiClient(serverA.baseUrl, null, MockEngine { request ->
            if (request.url.encodedPath.endsWith("/stop")) respond("""{"message":"session ended"}""", HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val model = AggregateStreamsViewModel()
        try {
            model.stop(serverA, row) { client }
            val failure = model.errorMessage.value
            assertNotNull(failure)
            model.load(listOf(serverA)) { client }
            assertEquals(failure, model.errorMessage.value)
        } finally { client.close() }
    }

    @Test fun `confirmed mutation followed by failed reconciliation retains successful action outcome`() = runTest {
        var fail = false; var posts = 0
        val client = ApiClient(serverA.baseUrl, null, MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/stop") -> { posts++; fail = true; respond(V2Fixtures.PLAYBACK_COMMAND_RECEIPT, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json")) }
                request.url.encodedPath.endsWith("/admin/sessions") && fail -> respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("""{"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val owner = backgroundScope.launch { client.liveSessions.observe() }
        try {
            withContext(Dispatchers.Default) { withTimeout(5_000) { client.liveSessions.sessions.first { it != null } } }
            val model = AggregateStreamsViewModel()
            model.stop(serverA, row) { client }
            assertEquals(1, posts); assertNull(model.errorMessage.value)
            assertNotNull(client.liveSessions.error.value)
        } finally { owner.cancel(); client.close() }
    }
}
