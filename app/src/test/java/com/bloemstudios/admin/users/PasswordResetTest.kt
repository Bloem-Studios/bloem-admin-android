package com.bloemstudios.admin.users

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.passwordResetCapabilities
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PasswordResetTest {
    @Test fun `email reset accepts the receipt without retrieving a password or link`() = runTest {
        var path = ""
        val client = ApiClient("https://server.test", "token", MockEngine { req ->
            path = req.url.encodedPath
            respond("""{"delivery":"email","delivery_status":"failed_or_unknown","expires_at":"2026-10-13T00:00:00Z"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = sendPasswordResetEmail(client, "7")
        assertEquals("/api/v2/admin/users/7/password-reset", path)
        assertEquals("failed_or_unknown", receipt.delivery_status)
        client.close()
    }
    @Test fun `capabilities report whether reset email is available`() = runTest {
        var path = ""
        val client = ApiClient("https://server.test", "token", MockEngine { req ->
            path = req.url.encodedPath
            respond(com.bloemstudios.admin.auth.V2Fixtures.adminAccountCapabilities(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertTrue(client.passwordResetCapabilities().password_reset_email)
        assertEquals("/api/v2/admin/users/capabilities", path)
        client.close()
    }
    @Test fun `unauthorized reset is submitted only once`() = runTest {
        var calls = 0
        val client = ApiClient("https://server.test", "token", MockEngine {
            calls++
            respond(com.bloemstudios.admin.auth.V2Fixtures.problem(401, "session_expired", "Sign in again"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }, unauthorizedRecovery = UnauthorizedRecovery {
            fail("A password reset must not invoke authentication replay")
            RecoveryResult.Retry(AccessTokenSnapshot("new-token", 1L))
        })
        try { sendPasswordResetEmail(client, "7"); fail("Expected rejection") } catch (_: ApiError) { }
        assertEquals(1, calls)
        client.close()
    }
}
