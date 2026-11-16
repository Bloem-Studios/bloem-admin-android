package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * The mock server here follows the real contract: GET /settings/{key} sends no ETag (and answers 404 for a key
 * still at its default), while GET /settings/effective sends the ETag that covers the whole settings snapshot.
 * That ETag is the only validator PUT /settings/{key} accepts as If-Match.
 */
class SettingEditorTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun contractClient(
        effective: String = """{"server.name":"Original","server.log_level":"info"}""",
        etag: String? = "\"snapshot-1\"",
        onPut: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("{}", HttpStatusCode.OK, json) },
        paths: MutableList<String> = mutableListOf()
    ) = ApiClient("https://server.test", "token", MockEngine { req ->
        paths += "${req.method.value} ${req.url.encodedPath}"
        when {
            req.method == HttpMethod.Get && req.url.encodedPath == "/api/v2/admin/settings/effective" ->
                respond(effective, HttpStatusCode.OK, if (etag == null) json else headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag)))
            req.method == HttpMethod.Get && req.url.encodedPath.startsWith("/api/v2/admin/settings/") ->
                respond("""{"key":"x","value":"y"}""", HttpStatusCode.OK, json) // the real server sends no ETag here
            req.method == HttpMethod.Put -> onPut(req)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    })

    @Test fun `the edit validator comes from the effective settings ETag and the value from its map`() = runTest {
        val paths = mutableListOf<String>()
        val client = contractClient(paths = paths)
        val editor = loadSettingForEdit(client, "server.name")
        assertEquals("\"snapshot-1\"", editor.validator)
        assertEquals(AdminSetting("server.name", "Original"), editor.setting)
        assertEquals(listOf("GET /api/v2/admin/settings/effective"), paths)
        client.close()
    }

    @Test fun `a key still at its default can be edited`() = runTest {
        val client = contractClient(effective = """{"server.log_level":"info"}""")
        assertEquals("info", loadSettingForEdit(client, "server.log_level").setting.value)
        client.close()
    }

    @Test fun `a key the server does not list opens with an empty value`() = runTest {
        val client = contractClient(effective = """{"server.log_level":"info"}""")
        assertEquals("", loadSettingForEdit(client, "server.name").setting.value)
        client.close()
    }

    @Test fun `save sends the snapshot ETag as If-Match and reports a stale snapshot as a conflict`() = runTest {
        var sentIfMatch: String? = null
        var putPath: String? = null
        val client = contractClient(onPut = { req ->
            sentIfMatch = req.headers[HttpHeaders.IfMatch]; putPath = req.url.encodedPath
            respond("""{"detail":"Changed by another administrator"}""", HttpStatusCode.PreconditionFailed, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        })
        val editor = loadSettingForEdit(client, "server.name")
        val result = saveSetting(client, editor, "Draft")
        assertEquals("\"snapshot-1\"", sentIfMatch)
        assertEquals("/api/v2/admin/settings/server.name", putPath)
        assertTrue(result is SettingSaveResult.Conflict)
        assertEquals("Original", editor.setting.value)
        client.close()
    }

    @Test fun `a missing If-Match answer is a conflict too`() = runTest {
        val client = contractClient(onPut = { respond("""{"detail":"If-Match required"}""", HttpStatusCode(428, "Precondition Required"), headersOf(HttpHeaders.ContentType, "application/problem+json")) })
        assertTrue(saveSetting(client, loadSettingForEdit(client, "server.name"), "Draft") is SettingSaveResult.Conflict)
        client.close()
    }

    @Test fun `successful save returns the receipt including restart_required`() = runTest {
        val client = contractClient(onPut = { respond("""{"key":"server.name","value":"Saved","restart_required":true}""", HttpStatusCode.OK, json) })
        val result = saveSetting(client, loadSettingForEdit(client, "server.name"), "Saved")
        assertTrue(result is SettingSaveResult.Saved)
        assertEquals(AdminSetting("server.name", "Saved", restart_required = true), (result as SettingSaveResult.Saved).setting)
        client.close()
    }

    @Test fun `a receipt without restart_required means no restart`() = runTest {
        val client = contractClient(onPut = { respond("""{"key":"server.name","value":"Saved"}""", HttpStatusCode.OK, json) })
        val result = saveSetting(client, loadSettingForEdit(client, "server.name"), "Saved") as SettingSaveResult.Saved
        assertFalse(result.setting.restart_required)
        client.close()
    }

    @Test fun `a server that sends no ETag refuses an unguarded write`() = runTest {
        val client = contractClient(etag = null)
        assertTrue(runCatching { loadSettingForEdit(client, "server.name") }.isFailure)
        client.close()
    }

    @Test fun `after a conflict the re-read offers the new value and a fresh validator`() = runTest {
        var snapshot = 1
        val client = ApiClient("https://server.test", "token", MockEngine { req ->
            if (req.method == HttpMethod.Get) {
                val body = if (snapshot == 1) """{"server.name":"Original"}""" else """{"server.name":"Changed elsewhere"}"""
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"snapshot-$snapshot\"")))
            } else respond("{}", HttpStatusCode.PreconditionFailed, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        })
        val first = loadSettingForEdit(client, "server.name")
        assertTrue(saveSetting(client, first, "Draft") is SettingSaveResult.Conflict)
        snapshot = 2
        val reread = loadSettingForEdit(client, "server.name")
        assertEquals("Changed elsewhere", reread.setting.value)
        assertEquals("\"snapshot-2\"", reread.validator)
        client.close()
    }
}
