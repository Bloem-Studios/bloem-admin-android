package com.bloemstudios.admin.nodes

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodesViewModelTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun summary(id: String, name: String = "node-$id", type: String = "transcode", enabled: Boolean = true, drainRequested: Boolean = false) =
        """{"id":"$id","name":"$name","type":"$type","enabled":$enabled,"healthy":true,"active_jobs":1,"group":null,"max_jobs":null,"max_bandwidth_kbps":null,"egress_kbps":0,"last_health_check":"2026-11-14T09:59:40.000Z","created_at":"2026-01-01T00:00:00.000Z","drain_requested":$drainRequested,"drain_state":"none"}"""

    private fun page(vararg rows: String, next: String? = null) =
        """{"items":[${rows.joinToString(",")}],"page":{"has_more":${next != null}${if (next != null) ",\"next_cursor\":\"$next\"" else ""}}}"""

    private class Seen(val method: HttpMethod, val path: String, val query: String, val ifMatch: String?, val body: String)

    private fun client(seen: MutableList<Seen> = mutableListOf(), handler: (Seen) -> Pair<HttpStatusCode, String>): ApiClient =
        ApiClient("https://server.test", "token", MockEngine { req ->
            val entry = Seen(req.method, req.url.encodedPath, req.url.encodedQuery, req.headers[HttpHeaders.IfMatch], String(req.body.toByteArray()))
            seen += entry
            val (status, body) = handler(entry)
            respond(body, status, jsonHeaders)
        })

    private val capabilities = """{"state":"available","allowed":true,"drain":true,"summary":true,"revision":"r1"}"""

    @Test fun `load reads the capabilities and every page of the summary`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req ->
            when {
                req.path.endsWith("/capabilities") -> HttpStatusCode.OK to capabilities
                req.query.contains("cursor=n2") -> HttpStatusCode.OK to page(summary("2"))
                else -> HttpStatusCode.OK to page(summary("1"), next = "n2")
            }
        }
        val vm = NodesViewModel()
        vm.load(api)
        assertEquals(listOf("1", "2"), vm.nodes.value!!.map { it.id })
        assertTrue(vm.drainSupported.value)
        assertTrue(seen.any { it.path == "/api/v2/admin/nodes/summary" })
        assertNull(vm.error.value)
        api.close()
    }

    @Test fun `a server without drain support offers no drain`() = runTest {
        val api = client { req ->
            if (req.path.endsWith("/capabilities")) HttpStatusCode.OK to """{"state":"available","allowed":true,"drain":false,"summary":true,"revision":"r"}"""
            else HttpStatusCode.OK to page(summary("1"))
        }
        val vm = NodesViewModel()
        vm.load(api)
        assertFalse(vm.drainSupported.value)
        api.close()
    }

    @Test fun `a failed load keeps the nodes already shown and reports the failure`() = runTest {
        var fail = false
        val api = client { req ->
            if (fail) HttpStatusCode.InternalServerError to V2Fixtures.problem(500, "internal_error", "Nodes could not be listed")
            else if (req.path.endsWith("/capabilities")) HttpStatusCode.OK to capabilities
            else HttpStatusCode.OK to page(summary("1"))
        }
        val vm = NodesViewModel()
        vm.load(api)
        fail = true
        vm.load(api)
        assertEquals(1, vm.nodes.value!!.size)
        assertNotNull(vm.error.value)
        api.close()
    }

    @Test fun `drain and resume post to the node and reload`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req ->
            when {
                req.method == HttpMethod.Post -> HttpStatusCode.OK to """{"node_id":"7","drain_requested":true,"drain_state":"none","active_jobs":1}"""
                req.path.endsWith("/capabilities") -> HttpStatusCode.OK to capabilities
                else -> HttpStatusCode.OK to page(summary("7"))
            }
        }
        val vm = NodesViewModel()
        vm.drain(api, "7")
        vm.resume(api, "7")
        val posts = seen.filter { it.method == HttpMethod.Post }
        assertEquals(listOf("/api/v2/admin/nodes/7/drain", "/api/v2/admin/nodes/7/resume"), posts.map { it.path })
        assertNotNull(vm.nodes.value)
        api.close()
    }

    @Test fun `a refused drain says so and leaves the list as it was`() = runTest {
        val api = client { req ->
            when {
                req.method == HttpMethod.Post -> HttpStatusCode.Conflict to V2Fixtures.problem(409, "conflict", "Only transcode nodes can drain.")
                req.path.endsWith("/capabilities") -> HttpStatusCode.OK to capabilities
                else -> HttpStatusCode.OK to page(summary("7", type = "proxy"))
            }
        }
        val vm = NodesViewModel()
        vm.load(api)
        vm.drain(api, "7")
        assertNotNull(vm.actionError.value)
        api.close()
    }

    @Test fun `disable reads the node's configuration validator and writes it back with If-Match`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req ->
            when {
                req.method == HttpMethod.Put -> HttpStatusCode.OK to """{"id":"7","enabled":false,"config_etag":"\"rev-3\""}"""
                req.path == "/api/v2/admin/nodes" -> HttpStatusCode.OK to page("""{"id":"6","config_etag":"\"rev-1\""}""", """{"id":"7","config_etag":"\"rev-2\""}""")
                req.path.endsWith("/capabilities") -> HttpStatusCode.OK to capabilities
                else -> HttpStatusCode.OK to page(summary("7", enabled = false))
            }
        }
        val vm = NodesViewModel()
        vm.setEnabled(api, "7", enabled = false)
        val put = seen.single { it.method == HttpMethod.Put }
        assertEquals("/api/v2/admin/nodes/7", put.path)
        assertEquals("\"rev-2\"", put.ifMatch)
        assertEquals("""{"enabled":false}""", put.body)
        assertNull(vm.actionError.value)
        api.close()
    }

    @Test fun `a node that is no longer configured is reported and nothing is written`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req ->
            if (req.path == "/api/v2/admin/nodes") HttpStatusCode.OK to page("""{"id":"6","config_etag":"\"rev-1\""}""")
            else if (req.path.endsWith("/capabilities")) HttpStatusCode.OK to capabilities
            else HttpStatusCode.OK to page(summary("6"))
        }
        val vm = NodesViewModel()
        vm.setEnabled(api, "7", enabled = true)
        assertTrue(seen.none { it.method == HttpMethod.Put })
        assertNotNull(vm.actionError.value)
        api.close()
    }

    @Test fun `a configuration that changed since it was read is refused and reloaded`() = runTest {
        val seen = mutableListOf<Seen>()
        val api = client(seen) { req ->
            when {
                req.method == HttpMethod.Put -> HttpStatusCode.PreconditionFailed to V2Fixtures.problem(412, "precondition_failed", "Node changed")
                req.path == "/api/v2/admin/nodes" -> HttpStatusCode.OK to page("""{"id":"7","config_etag":"\"rev-2\""}""")
                req.path.endsWith("/capabilities") -> HttpStatusCode.OK to capabilities
                else -> HttpStatusCode.OK to page(summary("7"))
            }
        }
        val vm = NodesViewModel()
        vm.setEnabled(api, "7", enabled = false)
        assertNotNull(vm.actionError.value)
        assertTrue(seen.any { it.path == "/api/v2/admin/nodes/summary" })
        api.close()
    }
}
