package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class JobHistoryTest {
    @Test fun `continuation appends the next history page with the opaque cursor`() = runTest {
        val paths = mutableListOf<String>()
        val client = ApiClient("https://server.test", "token", MockEngine { req ->
            paths += req.url.toString()
            val next = req.url.parameters["cursor"] == "next-page"
            respond("""{"items":[{"id":"${if (next) "second" else "first"}","kind":"library_refresh","state":"completed","terminal":true,"cancelable":false,"created_at":"2026-10-12T00:00:00Z"}],"page":{"has_more":${!next}${if (next) "" else ",\"next_cursor\":\"next-page\""}}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = JobHistoryViewModel()
        vm.load(client)
        vm.loadMore(client)
        assertEquals(listOf("first", "second"), vm.rows.value.map { it.id })
        assertTrue(paths[1].contains("cursor=next-page"))
        assertFalse(vm.hasMore.value)
        client.close()
    }
}
