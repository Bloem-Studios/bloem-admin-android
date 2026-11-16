package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

class CollectionsTest {
    @Serializable
    data class Row(val id: String)

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun items(prefix: String) = """[{"id":"$prefix-1"},{"id":"$prefix-2"}]"""

    @Test
    fun `reads items and cursor from a v2 envelope`() = runTest {
        val client = ApiClient("https://example.test", null, MockEngine {
            json("""{"items":[{"id":"a"}],"page":{"has_more":true,"next_cursor":"c1"}}""")
        })
        val result = client.getCollection<Row>("/api/v2/things")
        assertEquals("a", result.items.single().id)
        assertEquals("c1", result.page!!.next_cursor)
        assertTrue(result.page!!.has_more)
        client.close()
    }

    @Test
    fun `missing page means one page`() = runTest {
        val client = ApiClient("https://example.test", null, MockEngine { json("""{"items":[]}""") })
        val result = client.getCollection<Row>("/api/v2/things")
        assertTrue(result.items.isEmpty())
        assertNull(result.page)
        client.close()
    }

    @Test
    fun `readAllPages follows cursors and sends limit`() = runTest {
        val urls = mutableListOf<String>()
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            urls += request.url.fullPath
            when (request.url.parameters["cursor"]) {
                null -> json("""{"items":${items("p1")},"page":{"has_more":true,"next_cursor":"c1"}}""")
                "c1" -> json("""{"items":${items("p2")},"page":{"has_more":true,"next_cursor":"c2"}}""")
                else -> json("""{"items":${items("p3")},"page":{"has_more":false}}""")
            }
        })
        val all = client.readAllPages<Row>("/api/v2/things")
        assertEquals(3, urls.size)
        assertEquals("/api/v2/things?cursor=c1&limit=200", urls[1])
        assertEquals(6, all.size)
        client.close()
    }

    @Test
    fun `readAllPages keeps caller params`() = runTest {
        val urls = mutableListOf<String>()
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            urls += request.url.fullPath
            json("""{"items":[]}""")
        })
        client.readAllPages<Row>("/api/v2/things", mapOf("status" to "open"), pageSize = 50)
        assertEquals(listOf("/api/v2/things?status=open&limit=50"), urls)
        client.close()
    }

    @Test
    fun `readAllPages stops when the cursor repeats`() = runTest {
        var requests = 0
        val client = ApiClient("https://example.test", null, MockEngine {
            requests++
            json("""{"items":${items("p$requests")},"page":{"has_more":true,"next_cursor":"c1"}}""")
        })
        val all = client.readAllPages<Row>("/api/v2/things")
        assertEquals(2, requests)
        assertEquals(4, all.size)
        client.close()
    }

    @Test
    fun `readAllPages stops at maxPages`() = runTest {
        var requests = 0
        val client = ApiClient("https://example.test", null, MockEngine {
            requests++
            json("""{"items":[],"page":{"has_more":true,"next_cursor":"c$requests"}}""")
        })
        client.readAllPages<Row>("/api/v2/things", maxPages = 3)
        assertEquals(3, requests)
        client.close()
    }
}
