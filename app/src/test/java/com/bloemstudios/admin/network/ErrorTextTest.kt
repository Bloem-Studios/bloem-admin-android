package com.bloemstudios.admin.network

import com.bloemstudios.admin.R
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTextTest {
    private val fallback = R.string.error_could_not_load_jobs

    @Test
    fun `upgrade tombstone maps to the string resource`() {
        val error = ApiError(410, "client_upgrade_required", "This server no longer serves /api/v1.")
        assertEquals(ErrorText.Res(R.string.error_client_upgrade_required), error.toErrorText(fallback))
    }

    @Test
    fun `a real sentence from the server is shown as it is`() {
        assertEquals(ErrorText.Raw("The task is already running."), ApiError(409, "conflict", "The task is already running.").toErrorText(fallback))
        assertEquals(ErrorText.Raw("Nothing found here"), ApiError(404, "not_found", "Nothing found here").toErrorText(fallback))
    }

    @Test
    fun `a bare server code is replaced by the screen fallback and the status`() {
        assertEquals(ErrorText.Http(fallback, 403), ApiError(403, "forbidden", "forbidden").toErrorText(fallback))
        assertEquals(ErrorText.Http(fallback, 500), ApiError(500, "internal_error", "internal_error").toErrorText(fallback))
    }

    @Test
    fun `a single word with no code is not a sentence`() {
        assertEquals(ErrorText.Http(fallback, 404), ApiError(404, "Gone").toErrorText(fallback))
    }

    @Test
    fun `the client's own placeholder is replaced by the screen fallback and the status`() {
        assertEquals(ErrorText.Http(fallback, 502), ApiError(502, null, DEFAULT_ERROR_MESSAGE).toErrorText(fallback))
        assertEquals(ErrorText.Http(fallback, 502), ApiError(502, null, "  ").toErrorText(fallback))
    }

    @Test
    fun `an aggregate error keeps the server name in front of the fallback`() {
        assertEquals(
            ErrorText.Prefixed("Bloem West", ErrorText.Http(fallback, 502)),
            ApiError(502, null, DEFAULT_ERROR_MESSAGE).toErrorText("Bloem West", fallback)
        )
    }

    @Test
    fun `the fallback loses its closing period before the status is added`() {
        assertEquals("Could not load jobs", "Could not load jobs.".withoutClosingPeriod())
        assertEquals("Couldn't load tasks", "Couldn't load tasks".withoutClosingPeriod())
    }

    @Test
    fun `a proxy error page without a JSON body reaches the screen as a fallback with the status`() = runTest {
        val client = ApiClient("https://example.test", "t", MockEngine {
            respond("<html>Bad Gateway</html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val error = runCatching { client.get<String>("/api/v2/admin/tasks") }.exceptionOrNull() as ApiError
        assertEquals(502, error.statusCode)
        assertNull(error.code)
        assertEquals(ErrorText.Http(fallback, 502), error.toErrorText(fallback))
        client.close()
    }

    @Test
    fun `a problem body with a detail sentence still shows that sentence`() = runTest {
        val client = ApiClient("https://example.test", "t", MockEngine {
            respond("""{"type":"https://x/problems/forbidden","title":"Forbidden","status":403,"detail":"You need the owner role to do this."}""",
                HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        })
        val error = runCatching { client.get<String>("/api/v2/admin/tasks") }.exceptionOrNull() as ApiError
        assertEquals(ErrorText.Raw("You need the owner role to do this."), error.toErrorText(fallback))
        assertTrue(error.statusCode == 403)
        client.close()
    }

    @Test
    fun `a problem body with only a type slug falls back with the status`() = runTest {
        val client = ApiClient("https://example.test", "t", MockEngine {
            respond("""{"type":"https://x/problems/forbidden","status":403}""",
                HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        })
        val error = runCatching { client.get<String>("/api/v2/admin/tasks") }.exceptionOrNull() as ApiError
        assertEquals(ErrorText.Http(fallback, 403), error.toErrorText(fallback))
        client.close()
    }
}
