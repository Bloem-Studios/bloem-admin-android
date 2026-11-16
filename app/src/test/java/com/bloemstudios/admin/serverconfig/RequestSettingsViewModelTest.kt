package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.network.ApiClient
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

class RequestSettingsViewModelTest {
    private class Server {
        var etag = "\"r1\""
        var settings = """{"requests_enabled":true,"global_max_requests":5,"global_window_days":7,"global_auto_approval_enabled":false,"force_dual_quality":false,"watchlist_requests":true}"""
        var putStatus = HttpStatusCode.OK
        var putBody: String? = null
        var putIfMatch: String? = null
        var capabilities = """{"available":true,"guarded_configuration":true,"routing":true}"""
    }

    private fun client(server: Server) = ApiClient("https://example.test", null, MockEngine { request ->
        val path = request.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(server.etag))
        when {
            path == "/api/v2/admin/requests/capabilities" -> respond(server.capabilities, HttpStatusCode.OK, json)
            request.method == HttpMethod.Put && path == "/api/v2/admin/request-settings" -> {
                server.putBody = String(request.body.toByteArray())
                server.putIfMatch = request.headers[HttpHeaders.IfMatch]
                if (server.putStatus == HttpStatusCode.OK) server.settings = server.putBody!!
                respond(if (server.putStatus == HttpStatusCode.OK) server.putBody!! else "{}", server.putStatus, json)
            }
            path == "/api/v2/admin/request-settings" -> respond(server.settings, HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    })

    @Test fun `the page is offered only where request configuration is guarded`() = runTest {
        val vm = RequestSettingsViewModel()
        vm.load(client(Server()))
        assertTrue(vm.state.value.available)
        val older = Server().apply { capabilities = """{"available":true,"guarded_configuration":false}""" }
        val vm2 = RequestSettingsViewModel()
        vm2.load(client(older))
        assertFalse(vm2.state.value.available)
        assertNull(vm2.state.value.loaded)
    }

    @Test fun `load reads the settings and their validator`() = runTest {
        val vm = RequestSettingsViewModel()
        vm.load(client(Server()))
        val loaded = vm.state.value.loaded!!
        assertEquals("\"r1\"", loaded.validator)
        assertEquals(5, loaded.settings.global_max_requests)
        assertEquals("5", vm.state.value.draft.maxRequests)
        assertEquals(0, vm.state.value.changeCount)
    }

    @Test fun `editing counts changes and an edit back is not a change`() = runTest {
        val vm = RequestSettingsViewModel()
        vm.load(client(Server()))
        vm.edit { it.copy(autoApprove = true, maxRequests = "10") }
        assertEquals(2, vm.state.value.changeCount)
        vm.edit { it.copy(maxRequests = "5") }
        assertEquals(1, vm.state.value.changeCount)
    }

    @Test fun `numbers that are not valid block the save`() = runTest {
        val vm = RequestSettingsViewModel()
        vm.load(client(Server()))
        vm.edit { it.copy(windowDays = "0") }
        assertFalse(vm.state.value.canSave)
        vm.edit { it.copy(windowDays = "14", maxRequests = "") }
        assertFalse(vm.state.value.canSave)
        vm.edit { it.copy(maxRequests = "0") }
        assertTrue(vm.state.value.canSave)
    }

    @Test fun `save sends the whole settings with If-Match and reads the new validator`() = runTest {
        val server = Server()
        val vm = RequestSettingsViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit { it.copy(autoApprove = true, force4k = true) }
        server.etag = "\"r2\""
        assertEquals(RequestSettingsOutcome.Saved, vm.save(api))
        assertEquals("\"r1\"", server.putIfMatch)
        val body = server.putBody!!
        assertTrue(body, body.contains("\"global_auto_approval_enabled\":true"))
        assertTrue(body, body.contains("\"force_dual_quality\":true"))
        assertTrue(body, body.contains("\"global_max_requests\":5"))
        assertTrue(body, body.contains("\"watchlist_requests\":true"))
        assertEquals("\"r2\"", vm.state.value.loaded!!.validator)
        assertEquals(0, vm.state.value.changeCount)
    }

    @Test fun `412 reads again, keeps the edits and says settings changed`() = runTest {
        val server = Server()
        val vm = RequestSettingsViewModel()
        val api = client(server)
        vm.load(api)
        vm.edit { it.copy(autoApprove = true) }
        server.putStatus = HttpStatusCode.PreconditionFailed
        server.etag = "\"r5\""
        server.settings = """{"requests_enabled":false,"global_max_requests":5,"global_window_days":7,"global_auto_approval_enabled":false,"force_dual_quality":false,"watchlist_requests":true}"""
        assertEquals(RequestSettingsOutcome.Conflict, vm.save(api))
        val state = vm.state.value
        assertTrue(state.conflict)
        assertTrue(state.draft.autoApprove)
        assertFalse(state.draft.enabled)
        assertEquals("\"r5\"", state.loaded!!.validator)
    }

    @Test fun `a server that predates watchlist requests hides the switch and sends none`() = runTest {
        val server = Server().apply { settings = """{"requests_enabled":true,"global_max_requests":5,"global_window_days":7,"global_auto_approval_enabled":false,"force_dual_quality":false}""" }
        val vm = RequestSettingsViewModel()
        val api = client(server)
        vm.load(api)
        assertNull(vm.state.value.draft.watchlist)
        vm.edit { it.copy(enabled = false) }
        vm.save(api)
        assertFalse(server.putBody!!.contains("watchlist_requests"))
        assertNotNull(vm.state.value.loaded)
    }
}
