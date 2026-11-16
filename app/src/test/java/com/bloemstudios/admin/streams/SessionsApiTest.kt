package com.bloemstudios.admin.streams

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.SessionCommand
import com.bloemstudios.admin.network.api.listSessions
import com.bloemstudios.admin.network.api.messageSession
import com.bloemstudios.admin.network.api.sendSessionCommand
import com.bloemstudios.admin.network.api.terminateSession
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionsApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private class Seen(val method: HttpMethod, val path: String, val query: String, val body: String?)

    private fun client(seen: MutableList<Seen>, status: HttpStatusCode = HttpStatusCode.Accepted, reply: (Int) -> String) =
        ApiClient("https://example.test", null, MockEngine { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            seen += Seen(request.method, request.url.encodedPath, request.url.encodedQuery, body)
            respond(reply(seen.size), status, json)
        })

    @Test fun `pause sends command id and sequence to v2`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { V2Fixtures.PLAYBACK_COMMAND_RECEIPT }
        try {
            api.sendSessionCommand("s1", SessionCommand.PAUSE, CommandIdentity("3fa85f64-5717-4562-b3fc-2c963f66afa6", 7))
        } finally { api.close() }
        val call = seen.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("/api/v2/admin/sessions/s1/pause", call.path)
        assertEquals(setOf("command_id", "sequence"), Json.parseToJsonElement(call.body!!).jsonObject.keys)
        assertEquals(7L, Json.parseToJsonElement(call.body).jsonObject.getValue("sequence").jsonPrimitive.long)
    }

    @Test fun `resume and stop use their own paths`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { V2Fixtures.PLAYBACK_COMMAND_RECEIPT }
        try {
            api.sendSessionCommand("s1", SessionCommand.RESUME, CommandIdentity("a", 1))
            api.sendSessionCommand("s1", SessionCommand.STOP, CommandIdentity("b", 2))
        } finally { api.close() }
        assertEquals(listOf("/api/v2/admin/sessions/s1/resume", "/api/v2/admin/sessions/s1/stop"), seen.map { it.path })
    }

    @Test fun `message sends title, message, command id and sequence`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { V2Fixtures.PLAYBACK_COMMAND_RECEIPT }
        try { api.messageSession("s1", "Heads up", "Restarting soon", CommandIdentity("id-1", 9)) } finally { api.close() }
        val body = Json.parseToJsonElement(seen.single().body!!).jsonObject
        assertEquals("/api/v2/admin/sessions/s1/message", seen.single().path)
        assertEquals(setOf("title", "message", "command_id", "sequence"), body.keys)
        assertEquals("Heads up", body.getValue("title").jsonPrimitive.content)
        assertEquals("Restarting soon", body.getValue("message").jsonPrimitive.content)
        assertEquals("id-1", body.getValue("command_id").jsonPrimitive.content)
    }

    @Test fun `message without a title omits it`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { V2Fixtures.PLAYBACK_COMMAND_RECEIPT }
        try { api.messageSession("s1", null, "Hello", CommandIdentity("id-1", 9)) } finally { api.close() }
        assertEquals(setOf("message", "command_id", "sequence"), Json.parseToJsonElement(seen.single().body!!).jsonObject.keys)
    }

    @Test fun `terminate sends reason only`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen, HttpStatusCode.OK) { V2Fixtures.PLAYBACK_TERMINATE_RECEIPT }
        try { api.terminateSession("s1", "Maintenance") } finally { api.close() }
        assertEquals("/api/v2/admin/sessions/s1/terminate", seen.single().path)
        assertEquals(setOf("reason"), Json.parseToJsonElement(seen.single().body!!).jsonObject.keys)
    }

    @Test fun `terminate without a reason sends an empty object`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen, HttpStatusCode.OK) { V2Fixtures.PLAYBACK_TERMINATE_RECEIPT }
        try { api.terminateSession("s1", null) } finally { api.close() }
        assertEquals("{}", seen.single().body)
    }

    @Test fun `snapshot read pages through v2 sessions`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen, HttpStatusCode.OK) { n ->
            if (n == 1) V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession("one"), nextCursor = "c2")
            else V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession("two"))
        }
        val rows = try { api.listSessions() } finally { api.close() }
        assertEquals(listOf("one", "two"), rows.map { it.session_id })
        assertTrue(seen.all { it.path == "/api/v2/admin/sessions" && it.method == HttpMethod.Get })
        assertTrue(seen[0].query.contains("limit=200"))
        assertTrue(seen[1].query.contains("cursor=c2"))
    }

    @Test fun `v2 row maps profile, device and client fields`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen, HttpStatusCode.OK) {
            V2Fixtures.adminPlaybackSessionsPage(V2Fixtures.adminPlaybackSession(optional =
                """"profile_name":"Living room","device_platform":"android","client_name":"Silo Android","series_name":"Show","season_number":1,"episode_number":2"""))
        }
        val row = try { api.listSessions().single() } finally { api.close() }
        assertEquals("user-1", row.user_id); assertEquals("profile-1", row.profile_id)
        assertEquals("Living room", row.profile_name); assertEquals("android", row.device_platform)
        assertEquals("Silo Android", row.client_name); assertEquals(2600.0, row.file_duration!!, 0.0)
    }
}
