package com.bloemstudios.admin.automation

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationPagesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(answers: Map<String, Pair<HttpStatusCode, String>>) =
        ApiClient("https://server.test", "token", MockEngine { req ->
            val (status, body) = answers[req.url.encodedPath] ?: (HttpStatusCode.NotFound to """{"status":404,"code":"not_found","title":"Not found","detail":"No such route"}""")
            respond(body, status, json)
        })

    @Test fun `a current server shows all three pages`() = runTest {
        val api = client(mapOf(
            "/api/v2/admin/nodes/capabilities" to (HttpStatusCode.OK to """{"state":"available","allowed":true,"drain":true,"summary":true}"""),
            "/api/v2/admin/backups/capabilities" to (HttpStatusCode.OK to """{"state":"available","allowed":true}"""),
            "/api/v2/admin/plugins/installations" to (HttpStatusCode.OK to """{"items":[]}"""),
        ))
        assertEquals(AutomationPages(nodes = true, backups = true, plugins = true), api.readAutomationPages())
        api.close()
    }

    @Test fun `an older server that has none of the routes shows none`() = runTest {
        val api = client(emptyMap())
        assertEquals(AutomationPages.None, api.readAutomationPages())
        api.close()
    }

    @Test fun `nodes need the summary read and a server without it hides the page`() = runTest {
        val api = client(mapOf("/api/v2/admin/nodes/capabilities" to (HttpStatusCode.OK to """{"state":"available","allowed":true,"drain":true,"summary":false}""")))
        assertEquals(false, api.readAutomationPages().nodes)
        api.close()
    }

    @Test fun `backups without storage still show a page that says so, and an unsupported state hides it`() = runTest {
        val notConfigured = client(mapOf("/api/v2/admin/backups/capabilities" to (HttpStatusCode.OK to """{"state":"not_configured","allowed":true}""")))
        assertEquals(true, notConfigured.readAutomationPages().backups)
        val unsupported = client(mapOf("/api/v2/admin/backups/capabilities" to (HttpStatusCode.OK to """{"state":"unsupported","allowed":true}""")))
        assertEquals(false, unsupported.readAutomationPages().backups)
        notConfigured.close(); unsupported.close()
    }

    @Test fun `a server that refuses the principal hides the page`() = runTest {
        val api = client(mapOf("/api/v2/admin/plugins/installations" to (HttpStatusCode.Forbidden to """{"status":403,"code":"forbidden","title":"Forbidden","detail":"No access"}""")))
        assertEquals(false, api.readAutomationPages().plugins)
        api.close()
    }
}
