package com.bloemstudios.admin.automation

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregateSectionsTest {
    private fun server(id: String, name: String) = AdminServer(id = id, displayName = name, baseUrl = "https://$id.example.test")

    private val a = server("a", "Home")
    private val b = server("b", "Studio")

    /** One client per server; [reads] counts the reads each saw and [fail] lists servers that answer 500. */
    private class Fleet(fail: Set<String> = emptySet()) {
        val reads = mutableMapOf<String, Int>()
        val writes = mutableListOf<String>()
        val failing = fail.toMutableSet()
        private val clients = mutableMapOf<String, ApiClient>()
        fun client(server: AdminServer): ApiClient = clients.getOrPut(server.id) {
            ApiClient("https://${server.id}.example.test", "token", MockEngine { req ->
                if (req.method.value == "POST") { writes += "${server.id} ${req.url.encodedPath}"; respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                else {
                    reads[server.id] = (reads[server.id] ?: 0) + 1
                    if (server.id in failing) respond("""{"type":"x","title":"t","status":500,"code":"internal_error","detail":"Down"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                    else respond("""{"n":${reads[server.id]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            })
        }
    }

    private suspend fun read(client: ApiClient): Int = client.get<kotlinx.serialization.json.JsonObject>("/read")["n"].toString().toInt()

    @Test fun `each server is a section of its own and one failing server does not take the others down`() = runTest {
        val fleet = Fleet(fail = setOf("b"))
        val sections = AggregateSections<Int>(::read)
        sections.load(listOf(a, b), fleet::client)
        val byId = sections.sections.value.associateBy { it.server.id }
        assertEquals(1, byId.getValue("a").result.getOrThrow())
        assertTrue(byId.getValue("b").result.exceptionOrNull() is ApiError)
        assertEquals(listOf("a", "b"), sections.sections.value.map { it.server.id })
    }

    @Test fun `an action goes to the row's own server and only that server is read again`() = runTest {
        val fleet = Fleet()
        val sections = AggregateSections<Int>(::read)
        sections.load(listOf(a, b), fleet::client)
        val ok = sections.act(b, "drain:1", R.string.nodes_error_drain, fleet::client) { it.post("/nodes/1/drain") }
        assertTrue(ok)
        assertEquals(listOf("b /nodes/1/drain"), fleet.writes)
        assertEquals(1, fleet.reads["a"])
        assertEquals(2, fleet.reads["b"])
        assertEquals(2, sections.sections.value.first { it.server.id == "b" }.result.getOrThrow())
    }

    @Test fun `a failed action names the server and still re-reads it`() = runTest {
        val fleet = Fleet()
        val sections = AggregateSections<Int>(::read)
        sections.load(listOf(a, b), fleet::client)
        val ok = sections.act(a, "x", R.string.nodes_error_drain, fleet::client) { throw ApiError(409, "Only transcode nodes can drain.") }
        assertEquals(false, ok)
        val error = sections.errorMessage.value
        assertTrue(error is ErrorText.Prefixed && error.prefix == "Home")
        assertEquals(2, fleet.reads["a"])
    }

    @Test fun `servers that left the list drop out of the sections`() = runTest {
        val fleet = Fleet()
        val sections = AggregateSections<Int>(::read)
        sections.load(listOf(a, b), fleet::client)
        sections.load(listOf(b), fleet::client)
        assertEquals(listOf("b"), sections.sections.value.map { it.server.id })
        assertNull(sections.errorMessage.value)
        assertNotNull(sections.busy)
    }
}
