package com.bloemstudios.admin.servers

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class ServerBrandingTest {
    @Test
    fun `uses server_name from health when present and non-blank`() = runTest {
        var capturedAuthHeader: String? = null
        val engine = MockEngine { request ->
            capturedAuthHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"status":"ok","server_name":"Home Media Server","server_id":"abc123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val displayName = fetchServerDisplayName(client, "example.test")

        assertEquals("Home Media Server", displayName)
        assertNull(capturedAuthHeader)
    }

    @Test
    fun `trims whitespace around server_name`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok","server_name":"  Padded Name  "}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val displayName = fetchServerDisplayName(client, "example.test")

        assertEquals("Padded Name", displayName)
    }

    @Test
    fun `falls back to host when server_name is absent`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val displayName = fetchServerDisplayName(client, "example.test")

        assertEquals("example.test", displayName)
    }

    @Test
    fun `falls back to host when server_name is blank`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok","server_name":"   "}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val displayName = fetchServerDisplayName(client, "example.test")

        assertEquals("example.test", displayName)
    }

    @Test
    fun `propagates a health failure instead of treating it as a display-name fallback`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"not_found","message":"no such route"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val error = assertThrows(ApiError::class.java) {
            kotlinx.coroutines.runBlocking { fetchServerDisplayName(client, "example.test") }
        }

        assertEquals(404, error.statusCode)
    }
}
