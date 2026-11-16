package com.bloemstudios.admin.serverconfig

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.getSettings
import com.bloemstudios.admin.network.api.restartServer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class ServerConfigViewModelTest {
    @Test
    fun `restart posts to v2`() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            seen += request.method to request.url.encodedPath
            respond(V2Fixtures.SERVER_RESTART, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        client.restartServer()
        assertEquals(listOf(HttpMethod.Post to "/api/v2/admin/server/restart"), seen)
    }

    @Test
    fun `restart sends an empty JSON object body`() = runTest {
        var contentType: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            contentType = request.body.contentType?.withoutParameters()?.toString()
            body = String(request.body.toByteArray())
            respond(V2Fixtures.SERVER_RESTART, HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ApiClient("https://example.test", null, engine).restartServer()
        assertEquals("application/json", contentType)
        assertEquals("{}", body)
    }

    @Test
    fun `settings read the v2 map`() = runTest {
        val engine = MockEngine { respond(V2Fixtures.SETTINGS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = ApiClient("https://example.test", null, engine)
        assertEquals(mapOf("s3.public_bucket" to "general-purpose-silo-us-east"), client.getSettings())
    }

    @Test
    fun `status prefers the reasons list and falls back to the single reason`() {
        val both = ServerStatus("t", true, true, restart_required_reason = "old_slug", restart_required_reasons = listOf("jellyfin_compat", "s3_bucket"))
        assertEquals("Jellyfin compat, S3 bucket", both.humanizedRestartRequiredReason)
        val single = ServerStatus("t", true, true, restart_required_reason = "old_slug")
        assertEquals("Old slug", single.humanizedRestartRequiredReason)
        assertNull(ServerStatus("t", false, false).humanizedRestartRequiredReason)
    }

    @Test
    fun `restartServer posts to restart endpoint and reloads settings and status`() = runTest {
        val capturedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            capturedPaths.add(request.url.encodedPath)
            val content = when (request.url.encodedPath) {
                "/api/v2/admin/settings" -> V2Fixtures.SETTINGS
                "/api/v2/admin/server/status" -> V2Fixtures.SERVER_STATUS
                "/api/v2/admin/server/restart" -> V2Fixtures.SERVER_RESTART
                else -> "{}"
            }
            respond(content = content, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = ServerConfigViewModel()

        viewModel.restartServer(client)

        assertEquals(
            listOf(
                "/api/v2/admin/server/restart",
                "/api/v2/admin/settings",
                "/api/v2/admin/server/status"
            ),
            capturedPaths
        )
        assertEquals(1, viewModel.config.value?.settings?.size)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `load fetches settings`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = if (request.url.encodedPath.endsWith("/settings")) {
                    V2Fixtures.SETTINGS
                } else {
                    V2Fixtures.SERVER_STATUS
                },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = ServerConfigViewModel()

        viewModel.load(client)

        assertEquals(1, viewModel.config.value?.settings?.size)
        assertEquals("s3.public_bucket", viewModel.config.value?.settings?.first()?.key)
    }

    @Test
    fun `status failure does not publish settings from a partial refresh`() = runTest {
        var load = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/settings")) load++
            when {
                request.url.encodedPath.endsWith("/settings") -> respond(
                    if (load == 1) """{"mode":"accepted"}""" else """{"mode":"partial"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                load == 1 -> respond(
                    V2Fixtures.SERVER_STATUS.replace("2026-08-28T10:00:00Z", "accepted"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(
                    """{"type":"about:blank","title":"Unavailable","status":503,"detail":"status unavailable"}""",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val viewModel = ServerConfigViewModel()
        val client = ApiClient("https://example.test", null, engine)
        viewModel.load(client)
        val accepted = viewModel.config.value

        viewModel.load(client)

        assertSame(accepted, viewModel.config.value)
        assertEquals("accepted", viewModel.config.value?.settings?.single()?.value)
        assertEquals("accepted", viewModel.config.value?.status?.started_at)
    }
}
