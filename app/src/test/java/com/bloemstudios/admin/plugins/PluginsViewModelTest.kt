package com.bloemstudios.admin.plugins

import com.bloemstudios.admin.auth.V2Fixtures
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsViewModelTest {
    private class Seen(val method: HttpMethod, val path: String, val query: String, val body: String)

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun row(id: String, enabled: Boolean = true, state: String = "running") =
        """{"id":"$id","plugin_id":"silo.plugin-$id","version":"1.0.0","enabled":$enabled,"repository_id":"1","source_kind":"silo","signature":{"state":"verified"},"runtime":{"resident":true,"state":"$state","restart_count":0}}"""

    private fun page(vararg rows: String, next: String? = null) =
        """{"items":[${rows.joinToString(",")}],"page":{"has_more":${next != null}${if (next != null) ",\"next_cursor\":\"$next\"" else ""}}}"""

    private fun client(seen: MutableList<Seen>, handler: (Seen) -> Pair<HttpStatusCode, String>): ApiClient =
        ApiClient("https://server.test", "token", MockEngine { req ->
            val entry = Seen(req.method, req.url.encodedPath, req.url.encodedQuery, String(req.body.toByteArray()))
            seen += entry
            val (status, body) = handler(entry)
            respond(body, status, json)
        })

    @Test fun `load reads every page of the installations`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req -> HttpStatusCode.OK to (if (req.query.contains("cursor=n2")) page(row("2")) else page(row("1"), next = "n2")) }
        val vm = PluginsViewModel()
        vm.load(api)
        assertEquals(listOf("1", "2"), vm.plugins.value!!.map { it.id })
        assertNull(vm.error.value)
        api.close()
    }

    @Test fun `restart posts to the installation's restart route and reloads`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req -> if (req.method == HttpMethod.Post) HttpStatusCode.OK to row("7") else HttpStatusCode.OK to page(row("7")) }
        val vm = PluginsViewModel()
        vm.restart(api, "7")
        assertTrue(seen.any { it.method == HttpMethod.Post && it.path == "/api/v2/admin/plugins/installations/7/restart" })
        assertTrue(seen.any { it.method == HttpMethod.Get && it.path == "/api/v2/admin/plugins/installations" })
        assertNull(vm.actionError.value)
        api.close()
    }

    @Test fun `turning a plugin off puts enabled false`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req -> if (req.method == HttpMethod.Put) HttpStatusCode.OK to row("7", enabled = false) else HttpStatusCode.OK to page(row("7", enabled = false)) }
        val vm = PluginsViewModel()
        vm.setEnabled(api, "7", false)
        val put = seen.single { it.method == HttpMethod.Put }
        assertEquals("/api/v2/admin/plugins/installations/7", put.path)
        assertEquals("""{"enabled":false}""", put.body)
        api.close()
    }

    @Test fun `update posts to the update route`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req -> if (req.method == HttpMethod.Post) HttpStatusCode.OK to row("7") else HttpStatusCode.OK to page(row("7")) }
        val vm = PluginsViewModel()
        vm.update(api, "7")
        assertTrue(seen.any { it.method == HttpMethod.Post && it.path == "/api/v2/admin/plugins/installations/7/update" })
        api.close()
    }

    @Test fun `a refused restart reports the server's reason`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req ->
            if (req.method == HttpMethod.Post) HttpStatusCode.Conflict to V2Fixtures.problem(409, "conflict", "This installation is disabled; enable it before restarting.")
            else HttpStatusCode.OK to page(row("7", enabled = false))
        }
        val vm = PluginsViewModel()
        vm.restart(api, "7")
        assertNotNull(vm.actionError.value)
        api.close()
    }

    @Test fun `the filter is kept while the list reloads`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { HttpStatusCode.OK to page(row("1"), row("2", state = "failed")) }
        val vm = PluginsViewModel()
        vm.load(api)
        vm.setFilter(PluginFilter.NeedsAttention)
        vm.load(api)
        assertEquals(PluginFilter.NeedsAttention, vm.filter.value)
        assertEquals(listOf("2"), vm.visible().map { it.id })
        api.close()
    }
}
