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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTestsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private class Seen { val calls = mutableListOf<Triple<HttpMethod, String, String>>() }

    private fun client(seen: Seen, status: HttpStatusCode = HttpStatusCode.OK, body: String) =
        ApiClient("https://example.test", null, MockEngine { request ->
            seen.calls += Triple(request.method, request.url.encodedPath, String(request.body.toByteArray()))
            respond(body, status, json)
        })

    @Test fun `a delivered test email passes with the time it took`() = runTest {
        val seen = Seen()
        val vm = SettingsTestsViewModel()
        vm.sendTestEmail(client(seen, body = """{"ok":true,"duration_ms":412}"""), " me@example.test ")
        val state = vm.results.value[TestKeys.EMAIL] as TestState.Finished
        assertTrue(state.passed)
        assertEquals(412L, state.durationMs)
        assertEquals(Triple(HttpMethod.Post, "/api/v2/admin/email/test", """{"to":"me@example.test"}"""), seen.calls.single())
    }

    @Test fun `a failed test email fails with the mail server's reason`() = runTest {
        val vm = SettingsTestsViewModel()
        vm.sendTestEmail(client(Seen(), body = """{"ok":false,"duration_ms":3000,"message":"535 Authentication failed"}"""), "me@example.test")
        val state = vm.results.value[TestKeys.EMAIL] as TestState.Finished
        assertFalse(state.passed)
        assertEquals("535 Authentication failed", state.reason)
        assertFalse(state.couldNotRun)
    }

    @Test fun `a connection check posts the kind with no changed values and shows pass or fail`() = runTest {
        val seen = Seen()
        val vm = SettingsTestsViewModel()
        vm.checkConnection(client(seen, body = """{"success":false,"message":"Connection check failed. Verify the submitted settings and provider availability."}"""), "redis")
        val state = vm.results.value[TestKeys.check("redis")] as TestState.Finished
        assertFalse(state.passed)
        assertTrue(state.reason!!.startsWith("Connection check failed"))
        assertEquals(Triple(HttpMethod.Post, "/api/v2/admin/settings/check/redis", """{"values":{},"dirty_keys":[]}"""), seen.calls.single())
    }

    @Test fun `a channel test passes with its HTTP status and time`() = runTest {
        val seen = Seen()
        val vm = SettingsTestsViewModel()
        vm.testChannel(client(seen, body = """{"ok":true,"http_status":204,"duration_ms":180}"""), "ch1")
        val state = vm.results.value[TestKeys.channel("ch1")] as TestState.Finished
        assertTrue(state.passed)
        assertEquals(204, state.httpStatus)
        assertEquals(Triple(HttpMethod.Post, "/api/v2/admin/notifications/server-channels/ch1/test", ""), seen.calls.single())
    }

    @Test fun `a sign-in provider test lists each step and fails on the first failed one`() = runTest {
        val seen = Seen()
        val vm = SettingsTestsViewModel()
        vm.testSignIn(
            client(seen, body = """{"ok":false,"steps":[{"id":"discovery","label":"Discovery document reachable","ok":true,"message":"Loaded"},{"id":"token","label":"Token endpoint","ok":false,"message":"401 from the provider"}],"callback_url":""}"""),
            "7", "oidc",
        )
        val state = vm.results.value[TestKeys.signIn("7")] as TestState.Finished
        assertFalse(state.passed)
        assertEquals("401 from the provider", state.reason)
        assertEquals(2, state.steps.size)
        assertEquals(Triple(HttpMethod.Post, "/api/v2/admin/plugins/installations/7/auth-binding/test", """{"capability_id":"oidc"}"""), seen.calls.single())
    }

    @Test fun `a refusal means the test could not run, and keeps the server's sentence`() = runTest {
        val vm = SettingsTestsViewModel()
        vm.sendTestEmail(
            client(Seen(), HttpStatusCode.ServiceUnavailable, """{"type":"https://errors.example/unavailable","title":"Unavailable","detail":"Email is not set up on this server."}"""),
            "me@example.test",
        )
        val state = vm.results.value[TestKeys.EMAIL] as TestState.Finished
        assertFalse(state.passed)
        assertTrue(state.couldNotRun)
        assertEquals("Email is not set up on this server.", state.reason)
        assertEquals(503, state.httpStatus)
    }

    @Test fun `a refusal with no sentence leaves the reason empty for the screen's own words`() = runTest {
        val vm = SettingsTestsViewModel()
        vm.checkConnection(client(Seen(), HttpStatusCode.InternalServerError, "{}"), "redis")
        val state = vm.results.value[TestKeys.check("redis")] as TestState.Finished
        assertNull(state.reason)
        assertTrue(state.couldNotRun)
    }

    @Test fun `email addresses are checked before anything is sent`() {
        assertTrue(isPlausibleEmail("me@example.test"))
        assertFalse(isPlausibleEmail("me@example"))
        assertFalse(isPlausibleEmail("me example@test.com"))
        assertFalse(isPlausibleEmail(""))
    }
}
