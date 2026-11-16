package com.bloemstudios.admin.serverconfig

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The named settings pages on a device, against a stand-in server that records what the app sends. */
@RunWith(AndroidJUnit4::class)
class SettingsPagesUiTest {
    @get:Rule val compose = createComposeRule()

    private class Wire {
        data class Call(val method: HttpMethod, val path: String, val body: String, val ifMatch: String?)
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var effective = """{"server.log_level":"info","server.public_url":"https://bloem.example.com","playback.hw_accel":"auto","playback.transcode_enabled":"true","email.enabled":"true","scanner.workers":"8"}"""
        @Volatile var putStatus = HttpStatusCode.OK
        val etag = AtomicInteger(1)
        @Volatile var restartReasons = """["setting:playback.hw_accel","setting:server.public_url"]"""
        @Volatile var requestsAvailable = true
    }

    private fun client(wire: Wire) = ApiClient("https://ui-test.invalid", "token", MockEngine { request ->
        val path = request.url.encodedPath
        val body = String(request.body.toByteArray())
        wire.calls += Wire.Call(request.method, path, body, request.headers[HttpHeaders.IfMatch])
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.method == HttpMethod.Put && path == "/api/v2/admin/settings" -> {
                if (wire.putStatus == HttpStatusCode.PreconditionFailed) {
                    wire.putStatus = HttpStatusCode.OK
                    wire.etag.incrementAndGet()
                    respond("{}", HttpStatusCode.PreconditionFailed, json)
                } else respond("""{"values":{"server.log_level":"debug"},"restart_required":false}""", HttpStatusCode.OK, json)
            }
            path == "/api/v2/admin/settings/effective" ->
                respond(wire.effective, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"fix-${wire.etag.get()}\"")))
            path == "/api/v2/admin/settings/restart-keys" -> respond("""{"keys":["playback.hw_accel"],"prefixes":[]}""", HttpStatusCode.OK, json)
            path == "/api/v2/admin/settings/sensitive-status" -> respond("""{"configured":[],"managed_by_env":[]}""", HttpStatusCode.OK, json)
            path == "/api/v2/admin/server/status" ->
                respond("""{"started_at":"2026-11-14T08:00:00Z","restart_required":true,"restart_requested":false,"restart_required_reasons":${wire.restartReasons}}""", HttpStatusCode.OK, json)
            path == "/api/v2/admin/requests/capabilities" ->
                if (wire.requestsAvailable) respond("""{"available":true,"guarded_configuration":true}""", HttpStatusCode.OK, json) else respond("{}", HttpStatusCode.NotFound, json)
            path == "/api/v2/network-access/capabilities" -> respond("{}", HttpStatusCode.NotFound, json)
            request.method == HttpMethod.Post && path == "/api/v2/admin/email/test" ->
                respond("""{"ok":false,"duration_ms":2000,"message":"535 Authentication failed"}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    })

    private fun show(wire: Wire, route: String = "home") {
        val api = client(wire)
        compose.setContent { VeilAdminTheme(ThemeMode.Dark) { ServerSettingsHost(api, scopeKey = "ui-test", initialRoute = route) } }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = compose.waitUntil(20_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun savingSendsOnlyTheChangedKeyOnTheSnapshotValidatorAndSaysSaved() {
        val wire = Wire()
        show(wire)
        waitForTag("settings-row:logging")
        compose.onNodeWithTag("settings-row:logging").performClick()
        waitForTag("setting-control:server.log_level")
        compose.onNodeWithText("Debug").performClick()
        compose.onNodeWithTag(SettingsSaveButtonTag).performClick()
        waitForText("Saved")
        val put = wire.calls.single { it.method == HttpMethod.Put }
        assertEquals("/api/v2/admin/settings", put.path)
        assertEquals("\"fix-1\"", put.ifMatch)
        assertEquals("""{"values":{"server.log_level":"debug"}}""", put.body)
    }

    @Test fun aStaleValidatorSaysSettingsChangedAndKeepsTheEdit() {
        val wire = Wire().apply { putStatus = HttpStatusCode.PreconditionFailed }
        show(wire, route = "page:logging")
        waitForTag("setting-control:server.log_level")
        compose.onNodeWithText("Debug").performClick()
        compose.onNodeWithTag(SettingsSaveButtonTag).performClick()
        waitForTag("settings-conflict")
        compose.onNodeWithText("Settings changed on the server").assertIsDisplayed()
        // The edit is still there to save again, now on the new validator.
        compose.onNodeWithTag(SettingsSaveBarTag).assertIsDisplayed()
        compose.onNodeWithTag(SettingsSaveButtonTag).performClick()
        waitForText("Saved")
        assertEquals(listOf("\"fix-1\"", "\"fix-2\""), wire.calls.filter { it.method == HttpMethod.Put }.map { it.ifMatch })
    }

    @Test fun searchFindsOneSettingAndOpensItsPage() {
        show(Wire())
        waitForTag("settings-search")
        compose.onNodeWithTag("settings-search").performTextInput("public url")
        waitForTag("settings-hit:server.public_url")
        compose.onNodeWithTag("settings-hit:server.public_url").performClick()
        waitForTag("setting-control:server.public_url")
    }

    @Test fun restartStatusNamesTheSettingsAndTheConfirmationSaysWhatItCosts() {
        show(Wire())
        waitForText("Restart needed: Hardware acceleration, Public URL")
        compose.onNodeWithTag(RestartButtonTag).performClick()
        waitForText("Active streams are interrupted")
        compose.onNodeWithText("Restart this server?").assertIsDisplayed()
    }

    @Test fun anOlderServerShowsOnlyThePagesItReports() {
        val wire = Wire().apply { effective = """{"server.log_level":"info","scanner.workers":"8"}"""; requestsAvailable = false }
        show(wire)
        waitForTag("settings-row:logging")
        assertTrue(compose.onAllNodes(hasTestTag("settings-row:email")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("settings-row:requests")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("settings-row:network-access")).fetchSemanticsNodes().isEmpty())
        // Anything the pages do not cover is still reachable.
        compose.onNodeWithTag("settings-row:other").performClick()
        waitForText("scanner.workers")
    }

    @Test fun aFailedTestEmailShowsFailedWithTheMailServersReason() {
        show(Wire(), route = "page:email")
        waitForTag("settings-email-test")
        compose.onNodeWithTag("settings-email-test").performScrollTo()
        compose.onNodeWithText("Test email recipient").performTextInput("me@example.test")
        compose.onNodeWithText("Send test email").performClick()
        waitForText("535 Authentication failed")
        compose.onNodeWithText("Failed").assertIsDisplayed()
    }
}
