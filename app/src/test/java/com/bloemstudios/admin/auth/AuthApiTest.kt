package com.bloemstudios.admin.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Covers the /api/v2/auth refresh request/response shape used by the store's
 * injected refresh exchange.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuthApiTest {
    @Test
    fun `refresh posts to v2 and returns the rotated refresh token`() = runTest {
        var capturedBody: String? = null
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                content = V2Fixtures.REFRESHED_TOKENS,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val response = refresh(client, "old-refresh")

        assertEquals("/api/v2/auth/refresh", capturedPath)
        assertEquals("""{"refresh_token":"old-refresh"}""", capturedBody)
        assertEquals("new-access", response.access_token)
        assertEquals("new-refresh", response.refresh_token)
        assertEquals(3600L, response.expires_in)
    }

    @Test
    fun `login posts to v2 and decodes a token pair`() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                content = V2Fixtures.TOKEN_PAIR,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val response = login(client, "admin", "secret")

        assertEquals("/api/v2/auth/login", capturedPath)
        assertEquals("""{"username":"admin","password":"secret"}""", capturedBody)
        assertEquals("a", response.access_token)
        assertEquals("r", response.refresh_token)
        assertEquals(3600L, response.expires_in)
    }
}
