package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagesViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Server {
        var etag = "\"v1\""
        var effective = """{"server.log_level":"info","playback.transcode_enabled":"true","server.public_url":""}"""
        var failRestartKeys = false
        var failStatus = false
        var putStatus = HttpStatusCode.OK
        var putBody = """{"values":{"server.log_level":"debug"},"restart_required":false}"""
        val seen = mutableListOf<String>()
        var putBodyText: String? = null
        var putIfMatch: String? = null
    }

    private fun client(server: Server) = ApiClient("https://example.test", null, MockEngine { request ->
        val path = request.url.encodedPath
        server.seen += "${request.method.value} $path"
        val headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(server.etag))
        when {
            request.method == HttpMethod.Put && path == "/api/v2/admin/settings" -> {
                server.putBodyText = String(request.body.toByteArray())
                server.putIfMatch = request.headers[HttpHeaders.IfMatch]
                respond(server.putBody, server.putStatus, json)
            }
            path == "/api/v2/admin/settings/effective" -> respond(server.effective, HttpStatusCode.OK, headers)
            path == "/api/v2/admin/settings/restart-keys" ->
                if (server.failRestartKeys) respond("{}", HttpStatusCode.NotFound, json)
                else respond("""{"keys":["playback.hw_accel"],"prefixes":["s3."]}""", HttpStatusCode.OK, json)
            path == "/api/v2/admin/settings/sensitive-status" ->
                respond("""{"configured":["email.smtp_password"],"managed_by_env":[]}""", HttpStatusCode.OK, json)
            path == "/api/v2/admin/server/status" && server.failStatus ->
                respond("""{"type":"about:blank","title":"Unavailable","status":503,"detail":"status unavailable"}""", HttpStatusCode.ServiceUnavailable, json)
            path == "/api/v2/admin/server/status" ->
                respond("""{"started_at":"2026-11-14T08:00:00Z","restart_required":false,"restart_requested":false}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    })

    @Test fun `load reads the effective settings with their validator, the restart keys and the secrets that are set`() = runTest {
        val server = Server()
        val vm = SettingsPagesViewModel()
        vm.load(client(server))
        val snapshot = vm.state.value.snapshot!!
        assertEquals("\"v1\"", snapshot.validator)
        assertEquals("info", snapshot.values["server.log_level"])
        assertTrue(snapshot.reports("server.public_url"))
        assertTrue(snapshot.restart.requiresRestart("s3.public_bucket"))
        assertEquals(listOf("email.smtp_password"), snapshot.sensitive.configured)
        assertEquals(listOf("GET /api/v2/admin/settings/effective"), server.seen.filter { it.endsWith("effective") })
    }

    @Test fun `a server without restart keys still loads`() = runTest {
        val server = Server().apply { failRestartKeys = true }
        val vm = SettingsPagesViewModel()
        vm.load(client(server))
        assertNotNull(vm.state.value.snapshot)
        assertNull(vm.state.value.loadError)
        assertTrue(vm.state.value.snapshot!!.restart.keys.isEmpty())
    }

    @Test fun `save sends the edited keys with the validator and clears them`() = runTest {
        val server = Server()
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit("server.log_level", "debug")
        vm.edit("playback.transcode_enabled", "false")
        server.etag = "\"v2\""
        val outcome = vm.save(api, setOf("server.log_level"))
        assertEquals(SettingsSaveOutcome.Saved(restartNeeded = false), outcome)
        assertEquals("\"v1\"", server.putIfMatch)
        assertEquals("""{"values":{"server.log_level":"debug"}}""", server.putBodyText)
        assertEquals(setOf("playback.transcode_enabled"), vm.state.value.draft.dirtyKeys)
        assertEquals("\"v2\"", vm.state.value.snapshot!!.validator)
    }

    @Test fun `a restart-only key saved asks for a restart`() = runTest {
        val server = Server().apply { putBody = """{"values":{"playback.hw_accel":"qsv"},"restart_required":true,"restart_required_keys":["playback.hw_accel"]}""" }
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit("playback.hw_accel", "qsv")
        assertEquals(SettingsSaveOutcome.Saved(restartNeeded = true), vm.save(api, setOf("playback.hw_accel")))
        assertEquals(listOf("playback.hw_accel"), vm.state.value.restartKeysSaved)
    }

    @Test fun `412 reads the settings again, keeps the edits visible and says settings changed`() = runTest {
        val server = Server()
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit("server.log_level", "debug")
        server.putStatus = HttpStatusCode.PreconditionFailed
        server.etag = "\"v3\""
        server.effective = """{"server.log_level":"warn","playback.transcode_enabled":"false","server.public_url":""}"""
        val outcome = vm.save(api, setOf("server.log_level"))
        assertEquals(SettingsSaveOutcome.Conflict, outcome)
        val state = vm.state.value
        assertTrue(state.conflict)
        assertEquals("debug", state.draft.value("server.log_level"))
        assertEquals("false", state.draft.value("playback.transcode_enabled"))
        assertEquals("\"v3\"", state.snapshot!!.validator)
        // The next save goes out on the fresh validator.
        server.putStatus = HttpStatusCode.OK
        vm.save(api, setOf("server.log_level"))
        assertEquals("\"v3\"", server.putIfMatch)
        assertFalse(vm.state.value.conflict)
    }

    @Test fun `a refusal keeps the edits and shows the server's reason`() = runTest {
        val server = Server().apply {
            putStatus = HttpStatusCode.Conflict
            putBody = """{"type":"https://errors.example/break_glass_required","title":"Break-glass account required","detail":"Turn password sign-in back on first, or make another admin break-glass."}"""
        }
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit("server.log_level", "debug")
        val outcome = vm.save(api, setOf("server.log_level"))
        assertTrue(outcome is SettingsSaveOutcome.Rejected)
        assertEquals("debug", vm.state.value.draft.value("server.log_level"))
        val error = vm.state.value.saveError
        assertTrue(error is ErrorText.Raw)
    }

    @Test fun `a later read while there are edits does not replace the validator the edits were made on`() = runTest {
        val server = Server()
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit("server.log_level", "debug")
        server.etag = "\"v9\""
        vm.load(api)
        assertEquals("\"v1\"", vm.state.value.snapshot!!.validator)
        assertEquals("debug", vm.state.value.draft.value("server.log_level"))
    }

    @Test fun `saving with no edits sends nothing`() = runTest {
        val server = Server()
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        assertEquals(SettingsSaveOutcome.Nothing, vm.save(api, setOf("server.log_level")))
        assertTrue(server.seen.none { it.startsWith("PUT") })
    }

    @Test fun `a failed read keeps the last good settings and reports the failure`() = runTest {
        val server = Server()
        val vm = SettingsPagesViewModel()
        val api = client(server)
        vm.load(api)
        val good = vm.state.value.snapshot
        server.failStatus = true
        server.effective = """{"server.log_level":"debug"}"""
        vm.load(api)
        assertTrue(vm.state.value.snapshot === good)
        assertNotNull(vm.state.value.loadError)
    }
}
