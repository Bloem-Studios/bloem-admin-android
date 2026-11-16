package com.bloemstudios.admin.audit

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AuditViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problemJson = headersOf(HttpHeaders.ContentType, "application/problem+json")

    private fun row(id: String, optional: String = "") = V2Fixtures.adminAuditLog(id, optional = optional)

    @Test
    fun `load decodes entries with string ids and keeps recorded impersonation`() = runTest {
        val engine = MockEngine {
            respond(
                V2Fixtures.adminAuditLogsPage(row("123", """"user_id":"42","impersonator_user_id":"7""""), nextCursor = "page2"),
                HttpStatusCode.OK, json
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()

        viewModel.load(client)

        val entry = viewModel.entries.value.single()
        assertEquals("123", entry.id)
        assertEquals("42", entry.user_id)
        assertEquals("7", entry.impersonator_user_id)
        assertEquals(45L, entry.duration_ms)
        assertTrue(viewModel.hasMore.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `audit pages by v2 cursor with the actor_user_id filter`() = runTest {
        val requests = mutableListOf<Url>()
        val engine = MockEngine { request ->
            requests += request.url
            if (request.url.parameters["cursor"] == null) {
                respond(V2Fixtures.adminAuditLogsPage(row("1"), nextCursor = "page2"), HttpStatusCode.OK, json)
            } else {
                respond(V2Fixtures.adminAuditLogsPage(row("2")), HttpStatusCode.OK, json)
            }
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()

        viewModel.load(client, userId = " 42 ")
        assertTrue(viewModel.hasMore.value)
        viewModel.loadMore(client, userId = "42")

        assertEquals("/api/v2/admin/logs/audit", requests[0].encodedPath)
        assertEquals("42", requests[0].parameters["actor_user_id"])
        assertNull(requests[0].parameters["cursor"])
        assertEquals("page2", requests[1].parameters["cursor"])
        assertEquals("42", requests[1].parameters["actor_user_id"])
        assertEquals(listOf("1", "2"), viewModel.entries.value.map { it.id })
        // The last page has has_more=false and no next_cursor.
        assertFalse(viewModel.hasMore.value)
    }

    @Test
    fun `blank user_id filter is omitted`() = runTest {
        var captured: String? = "unset"
        val engine = MockEngine { request ->
            captured = request.url.parameters["actor_user_id"]
            respond(V2Fixtures.adminAuditLogsPage(), HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()

        viewModel.load(client, userId = "  ")

        assertNull(captured)
    }

    @Test
    fun `poll refreshes the first page, keeps loaded pages and dedupes by id keeping the newer copy`() = runTest {
        var call = 0
        val engine = MockEngine { request ->
            call++
            when (call) {
                1 -> respond(V2Fixtures.adminAuditLogsPage(row("3"), row("2"), nextCursor = "page2"), HttpStatusCode.OK, json)
                2 -> respond(V2Fixtures.adminAuditLogsPage(row("1")), HttpStatusCode.OK, json)
                else -> {
                    assertNull(request.url.parameters["cursor"])
                    respond(V2Fixtures.adminAuditLogsPage(row("4"), row("3", """"user_id":"9""""), nextCursor = "page2"), HttpStatusCode.OK, json)
                }
            }
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()
        viewModel.load(client)
        viewModel.loadMore(client)

        viewModel.pollRefresh(client)

        assertEquals(listOf("4", "3", "2", "1"), viewModel.entries.value.map { it.id })
        assertEquals("9", viewModel.entries.value[1].user_id)
    }

    @Test
    fun `load surfaces a fallback error resource id on decode failure`() = runTest {
        val engine = MockEngine { respond("not json", HttpStatusCode.OK, json) }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()

        viewModel.load(client)

        assertEquals(ErrorText.Res(R.string.error_could_not_load_audit), viewModel.errorMessage.value)
        assertTrue(viewModel.entries.value.isEmpty())
    }

    @Test
    fun `a problem response shows its detail`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.problem(403, "permission_denied", "Admin access required"), HttpStatusCode.Forbidden, problemJson)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()

        viewModel.load(client)

        assertEquals(ErrorText.Raw("Admin access required"), viewModel.errorMessage.value)
    }

    // A failed refresh must not desync hasMore from the cursor it describes.
    @Test
    fun `a failed reload leaves entries, cursor and hasMore consistent with the last success`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond(V2Fixtures.adminAuditLogsPage(row("1"), nextCursor = "page2"), HttpStatusCode.OK, json)
            else respond(V2Fixtures.problem(500, "internal_error", "boom"), HttpStatusCode.InternalServerError, problemJson)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = AuditViewModel()

        viewModel.load(client)
        viewModel.load(client)

        assertNotNull(viewModel.errorMessage.value)
        assertTrue("hasMore must still reflect the last successfully-fetched cursor", viewModel.hasMore.value)
        assertEquals(1, viewModel.entries.value.size)
    }

    private fun seeded(vararg pages: String): Pair<ApiClient, MutableList<Url>> {
        val urls = mutableListOf<Url>()
        var i = 0
        val engine = MockEngine { request ->
            urls += request.url
            val body = pages[minOf(i++, pages.size - 1)]
            if (body.startsWith("{\"type\"")) respond(body, HttpStatusCode.InternalServerError, problemJson)
            else respond(body, HttpStatusCode.OK, json)
        }
        return ApiClient("https://example.test", null, engine) to urls
    }

    @Test
    fun `load more after a filter change sends nothing`() = runTest {
        val (client, urls) = seeded(V2Fixtures.adminAuditLogsPage(row("1"), nextCursor = "page2"))
        val vm = AuditViewModel()
        vm.load(client, userId = "1")

        vm.loadMore(client, userId = "2")

        assertEquals(1, urls.size)
        assertEquals(listOf("1"), vm.entries.value.map { it.id })
    }

    @Test
    fun `a poll under a new filter replaces instead of merging`() = runTest {
        val (client, _) = seeded(
            V2Fixtures.adminAuditLogsPage(row("2"), nextCursor = "p2"),
            V2Fixtures.adminAuditLogsPage(row("1")),
            V2Fixtures.adminAuditLogsPage(row("9"))
        )
        val vm = AuditViewModel()
        vm.load(client, userId = "1")
        vm.loadMore(client, userId = "1")

        vm.pollRefresh(client, userId = "2")

        assertEquals(listOf("9"), vm.entries.value.map { it.id })
        assertFalse(vm.hasMore.value)
    }

    @Test
    fun `a failed poll keeps loaded pages and the cursor`() = runTest {
        val (client, urls) = seeded(
            V2Fixtures.adminAuditLogsPage(row("2"), nextCursor = "p2"),
            V2Fixtures.adminAuditLogsPage(row("1"), nextCursor = "p3"),
            V2Fixtures.problem(500, "internal_error", "boom"),
            V2Fixtures.adminAuditLogsPage(row("0"))
        )
        val vm = AuditViewModel()
        vm.load(client)
        vm.loadMore(client)

        vm.pollRefresh(client)

        assertNotNull(vm.errorMessage.value)
        assertEquals(listOf("2", "1"), vm.entries.value.map { it.id })
        assertTrue(vm.hasMore.value)
        vm.loadMore(client)
        assertEquals("p3", urls.last().parameters["cursor"])
    }

    @Test
    fun `a load more page that overlaps loaded rows adds only new ids`() = runTest {
        val (client, _) = seeded(
            V2Fixtures.adminAuditLogsPage(row("2"), nextCursor = "p2"),
            V2Fixtures.adminAuditLogsPage(row("2"), row("1"))
        )
        val vm = AuditViewModel()
        vm.load(client)

        vm.loadMore(client)

        assertEquals(listOf("2", "1"), vm.entries.value.map { it.id })
    }
}
