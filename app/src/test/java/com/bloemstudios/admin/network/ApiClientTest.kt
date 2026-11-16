package com.bloemstudios.admin.network

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientTest {
    @Serializable
    data class TestStatusProbe(val restart_required: Boolean)

    @Serializable
    data class TestBody(val value: String)

    @Serializable
    data class TestListBody(val values: List<String>)

    @Test
    fun `nested server error preserves code message and retry after`() = runTest {
        val engine = MockEngine {
            respondJson(
                """{"error":{"code":"rate_limited","message":"Request is pending"}}""",
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.RetryAfter, "1")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val error = assertFailsWith<ApiError> { client.get<TestBody>("/value") }

        assertEquals(503, error.statusCode)
        assertEquals("rate_limited", error.code)
        assertEquals("Request is pending", error.message)
        assertEquals("1", error.retryAfter)
    }

    @Test
    fun `top-level server error preserves code and message`() = runTest {
        val engine = MockEngine {
            respondJson(
                """{"error":"rate_limited","message":"Try later"}""",
                HttpStatusCode.TooManyRequests
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val error = assertFailsWith<ApiError> { client.get<TestBody>("/value") }

        assertEquals("rate_limited", error.code)
        assertEquals("Try later", error.message)
    }

    @Test
    fun `problem json fills code from type`() = runTest {
        val engine = MockEngine {
            respondJson(
                """{"type":"https://siloserver.org/docs/api/v2/problems/not_found","title":"Not found","status":404,"detail":"No operation is registered at this path."}""",
                HttpStatusCode.NotFound
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val error = assertFailsWith<ApiError> { client.get<TestBody>("/value") }

        assertEquals("not_found", error.code)
        assertEquals("No operation is registered at this path.", error.message)
        assertFalse(error.isClientUpgradeRequired)
    }

    @Test
    fun `tombstone is an upgrade error`() = runTest {
        val engine = MockEngine {
            respondJson(
                """{"error":"client_upgrade_required","message":"This server no longer serves /api/v1. Update the app to continue."}""",
                HttpStatusCode.Gone
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val error = assertFailsWith<ApiError> { client.get<TestBody>("/api/v1/admin/users") }

        assertTrue(error.isClientUpgradeRequired)
        assertEquals("This server no longer serves /api/v1. Update the app to continue.", error.message)
    }

    @Test
    fun `deleteConditional sends If-Match and does not replay after 401`() = runTest {
        val ifMatch = mutableListOf<String?>()
        var recoveries = 0
        val engine = MockEngine { request ->
            ifMatch += request.headers[HttpHeaders.IfMatch]
            respond("denied", HttpStatusCode.Unauthorized)
        }
        val client = ApiClient(
            ServerOrigin.parse("https://example.test"), "old", engine,
            UnauthorizedRecovery { recoveries++; RecoveryResult.Retry(AccessTokenSnapshot("new", 1L)) }
        )

        assertFailsWith<ApiError> { client.deleteConditional("/api/v2/things/1", "\"r1\"") }

        assertEquals(listOf<String?>("\"r1\""), ifMatch)
        assertEquals(0, recoveries)
    }

    @Test
    fun unauthorizedRecoveryRetriesOriginalRequestOnceWithNewToken() = runTest {
        val authorizations = mutableListOf<String?>()
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            authorizations += request.headers[HttpHeaders.Authorization]
            if (calls == 1) respond("denied", HttpStatusCode.Unauthorized)
            else respondJson("""{"value":"ok"}""")
        }
        val recovery = UnauthorizedRecovery { rejected ->
            assertEquals(AccessTokenSnapshot("old", 0L), rejected)
            RecoveryResult.Retry(AccessTokenSnapshot("new", 1L))
        }
        val client = ApiClient(ServerOrigin.parse("https://example.test"), "old", engine, recovery)

        assertEquals("ok", client.get<TestBody>("/value").value)
        assertEquals(listOf("Bearer old", "Bearer new"), authorizations)
    }

    @Test
    fun secondUnauthorizedResponseIsNotRefreshedRecursively() = runTest {
        var recoveryCalls = 0
        var callbackSnapshot: AccessTokenSnapshot? = null
        val client = clientReturning401(UnauthorizedRecovery {
            recoveryCalls++
            RecoveryResult.Retry(AccessTokenSnapshot("new", 1L))
        })
        client.onUnauthorized = { callbackSnapshot = it }

        assertFailsWith<ApiError> { client.get<TestBody>("/value") }
        assertEquals(1, recoveryCalls)
        assertEquals(AccessTokenSnapshot("new", 1L), callbackSnapshot)
    }

    @Test
    fun transientRefreshFailureDoesNotFireUnauthorizedCallback() = runTest {
        var unauthorized = false
        val failure = IOException("offline")
        val client = clientReturning401(
            UnauthorizedRecovery { RecoveryResult.Unavailable(failure) }
        )
        client.onUnauthorized = { unauthorized = true }

        assertSame(failure, assertFailsWith<IOException> { client.get<TestBody>("/value") })
        assertFalse(unauthorized)
    }

    @Test
    fun clientNeverInstallsAnOlderRecoverySnapshot() = runTest {
        val authorizations = mutableListOf<String?>()
        val engine = MockEngine { request ->
            authorizations += request.headers[HttpHeaders.Authorization]
            if (authorizations.size == 1) respond("denied", HttpStatusCode.Unauthorized)
            else respondJson("""{"value":"ok"}""")
        }
        lateinit var client: ApiClient
        client = ApiClient(
            origin = ServerOrigin.parse("https://example.test"),
            initialAccessToken = AccessTokenSnapshot("old", 0L),
            engine = engine,
            unauthorizedRecovery = UnauthorizedRecovery {
                client.replaceAuthenticatedPrincipal(AccessTokenSnapshot("new-login", 2L))
                RecoveryResult.Retry(AccessTokenSnapshot("stale-refresh", 1L))
            }
        )

        client.get<TestBody>("/value")

        assertEquals(AccessTokenSnapshot("new-login", 2L), client.accessTokenSnapshotForTest())
        assertEquals(listOf("Bearer old", "Bearer new-login"), authorizations)
    }

    @Test
    fun secondUnauthorizedCallbackReceivesAtomicWinnerActuallyRetried() = runTest {
        val authorizations = mutableListOf<String?>()
        val engine = MockEngine { request ->
            authorizations += request.headers[HttpHeaders.Authorization]
            respond("denied", HttpStatusCode.Unauthorized)
        }
        lateinit var client: ApiClient
        client = ApiClient(
            origin = ServerOrigin.parse("https://example.test"),
            initialAccessToken = AccessTokenSnapshot("old", 0L),
            engine = engine,
            unauthorizedRecovery = UnauthorizedRecovery {
                client.replaceAuthenticatedPrincipal(AccessTokenSnapshot("new-login", 2L))
                RecoveryResult.Retry(AccessTokenSnapshot("stale-refresh", 1L))
            }
        )
        var callbackSnapshot: AccessTokenSnapshot? = null
        client.onUnauthorized = { callbackSnapshot = it }

        assertFailsWith<ApiError> { client.get<TestBody>("/value") }

        assertEquals(listOf("Bearer old", "Bearer new-login"), authorizations)
        assertEquals(AccessTokenSnapshot("new-login", 2L), callbackSnapshot)
    }

    @Test
    fun `get decodes success response and sends bearer token`() = runTest {
        var capturedAuthHeader: String? = null
        val engine = MockEngine { request ->
            capturedAuthHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"restart_required":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", "tok123", engine)

        val status: TestStatusProbe = client.get("/api/v1/admin/server/status")

        assertEquals(false, status.restart_required)
        assertEquals("Bearer tok123", capturedAuthHeader)
    }

    @Test
    fun `get resolves a trailing root slash to one api path slash`() = runTest {
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                content = """{"restart_required":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test/", null, engine)

        client.get<TestStatusProbe>("/api/v1/admin/server/status")

        assertEquals("/api/v1/admin/server/status", capturedPath)
    }

    @Test
    fun `get preserves reserved query characters as one parameter value`() = runTest {
        var capturedValues: List<String>? = null
        val engine = MockEngine { request ->
            capturedValues = request.url.parameters.getAll("filter")
            respond(
                content = """{"restart_required":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        client.get<TestStatusProbe>("/api/v1/admin/server/status", mapOf("filter" to "a&b+c%25?"))

        assertEquals(listOf("a&b+c%25?"), capturedValues)
    }

    @Test
    fun `get throws ApiError on non-2xx`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"invalid_credentials","message":"Invalid username or password"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)

        val error = assertThrows(ApiError::class.java) {
            kotlinx.coroutines.runBlocking { client.get<TestStatusProbe>("/api/v1/admin/server/status") }
        }
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `onUnauthorized fires on a 401 response but not on other error statuses`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"Token expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", "expired-token", engine)
        var unauthorizedCalls = 0
        client.onUnauthorized = { unauthorizedCalls++ }

        runCatching { client.get<TestStatusProbe>("/api/v1/admin/server/status") }

        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun `onUnauthorized does not fire on a 403 response`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"forbidden","message":"Admin access required"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", "tok", engine)
        var unauthorizedCalls = 0
        client.onUnauthorized = { unauthorizedCalls++ }

        runCatching { client.get<TestStatusProbe>("/api/v1/admin/server/status") }

        assertEquals(0, unauthorizedCalls)
    }

    private fun clientReturning401(recovery: UnauthorizedRecovery): ApiClient {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"Token expired"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return ApiClient(ServerOrigin.parse("https://example.test"), "old", engine, recovery)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        additionalHeaders: Headers = Headers.Empty
    ) = respond(
        body,
        status,
        Headers.build {
            append(HttpHeaders.ContentType, "application/json")
            appendAll(additionalHeaders)
        }
    )

    private suspend inline fun <reified T : Throwable> assertFailsWith(
        noinline block: suspend () -> Unit
    ): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }
}
