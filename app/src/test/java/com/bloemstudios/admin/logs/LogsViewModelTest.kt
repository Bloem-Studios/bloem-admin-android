package com.bloemstudios.admin.logs

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LogsViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problemJson = headersOf(HttpHeaders.ContentType, "application/problem+json")

    private fun row(id: String, level: String = "info", optional: String = "") =
        V2Fixtures.adminOperationalLog(id, level, optional = optional)

    @Test
    fun `load decodes entries and captures next_cursor`() = runTest {
        val engine = MockEngine {
            respond(
                V2Fixtures.adminOperationalLogsPage(
                    row("123", optional = """"request_id":"abc","user_id":"5","node_id":"node1","attrs":{"k":1}"""),
                    nextCursor = "page2"
                ),
                HttpStatusCode.OK, json
            )
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(client)

        val entry = viewModel.entries.value.single()
        assertEquals("123", entry.id)
        assertEquals("info", entry.level)
        assertEquals("scanner", entry.component)
        assertEquals("scan completed", entry.message)
        assertEquals("5", entry.user_id)
        assertTrue(viewModel.hasMore.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `app logs send level and q`() = runTest {
        val requests = mutableListOf<Url>()
        val engine = MockEngine { request ->
            requests += request.url
            respond(V2Fixtures.adminOperationalLogsPage(), HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(client, LogFilter(level = "error", q = "boom"))
        assertEquals("/api/v2/admin/logs/app", requests[0].encodedPath)
        assertEquals("error", requests[0].parameters["level"])
        assertEquals("boom", requests[0].parameters["q"])

        viewModel.load(client, LogFilter(level = "  ", q = "  "))
        assertNull(requests[1].parameters["level"])
        assertNull(requests[1].parameters["q"])
    }

    @Test
    fun `loadMore appends the next page using the captured cursor and stops at the last page`() = runTest {
        var capturedCursor: String? = null
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            if (callCount == 1) {
                respond(V2Fixtures.adminOperationalLogsPage(row("1"), nextCursor = "page2"), HttpStatusCode.OK, json)
            } else {
                capturedCursor = request.url.parameters["cursor"]
                respond(V2Fixtures.adminOperationalLogsPage(row("2", "warn")), HttpStatusCode.OK, json)
            }
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(client)
        viewModel.loadMore(client)

        assertEquals("page2", capturedCursor)
        assertEquals(listOf("1", "2"), viewModel.entries.value.map { it.id })
        assertFalse(viewModel.hasMore.value)
    }

    @Test
    fun `poll refreshes the first page, keeps loaded pages and dedupes by id keeping the newer copy`() = runTest {
        var call = 0
        val engine = MockEngine {
            call++
            when (call) {
                1 -> respond(V2Fixtures.adminOperationalLogsPage(row("3"), row("2"), nextCursor = "page2"), HttpStatusCode.OK, json)
                2 -> respond(V2Fixtures.adminOperationalLogsPage(row("1")), HttpStatusCode.OK, json)
                else -> respond(V2Fixtures.adminOperationalLogsPage(row("4"), row("3", "error"), nextCursor = "page2"), HttpStatusCode.OK, json)
            }
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()
        viewModel.load(client)
        viewModel.loadMore(client)

        viewModel.pollRefresh(client)

        assertEquals(listOf("4", "3", "2", "1"), viewModel.entries.value.map { it.id })
        assertEquals("error", viewModel.entries.value[1].level)
    }

    @Test
    fun `load surfaces a fallback error resource id on decode failure`() = runTest {
        val engine = MockEngine { respond("not json", HttpStatusCode.OK, json) }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(client)

        assertEquals(ErrorText.Res(R.string.error_could_not_load_logs), viewModel.errorMessage.value)
        assertTrue(viewModel.entries.value.isEmpty())
    }

    @Test
    fun `a 401 problem surfaces its detail and leaves entries untouched`() = runTest {
        val engine = MockEngine {
            respond(V2Fixtures.problem(401, "authentication_required", "Sign in again"), HttpStatusCode.Unauthorized, problemJson)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(client)

        assertEquals(ErrorText.Raw("Sign in again"), viewModel.errorMessage.value)
        assertTrue(viewModel.entries.value.isEmpty())
        assertFalse(viewModel.hasMore.value)
    }

    @Test
    fun `a failed reload leaves entries, cursor and hasMore consistent with the last success`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond(V2Fixtures.adminOperationalLogsPage(row("1"), nextCursor = "page2"), HttpStatusCode.OK, json)
            else respond(V2Fixtures.problem(500, "internal_error", "boom"), HttpStatusCode.InternalServerError, problemJson)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(client)
        viewModel.load(client)

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.hasMore.value)
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
        val (client, urls) = seeded(V2Fixtures.adminOperationalLogsPage(row("1"), nextCursor = "page2"))
        val vm = LogsViewModel()
        vm.load(client, LogFilter(level = "info", q = "a"))

        vm.loadMore(client, LogFilter(level = "error", q = "a"))
        vm.loadMore(client, LogFilter(level = "info", q = "b"))

        assertEquals(1, urls.size)
        assertEquals(listOf("1"), vm.entries.value.map { it.id })
    }

    @Test
    fun `a poll under a new filter replaces instead of merging`() = runTest {
        val (client, _) = seeded(
            V2Fixtures.adminOperationalLogsPage(row("2"), nextCursor = "p2"),
            V2Fixtures.adminOperationalLogsPage(row("1")),
            V2Fixtures.adminOperationalLogsPage(row("9"))
        )
        val vm = LogsViewModel()
        vm.load(client, LogFilter(level = "info"))
        vm.loadMore(client, LogFilter(level = "info"))

        vm.pollRefresh(client, LogFilter(level = "error"))

        assertEquals(listOf("9"), vm.entries.value.map { it.id })
        assertFalse(vm.hasMore.value)
    }

    @Test
    fun `a failed poll keeps loaded pages and the cursor`() = runTest {
        val (client, urls) = seeded(
            V2Fixtures.adminOperationalLogsPage(row("2"), nextCursor = "p2"),
            V2Fixtures.adminOperationalLogsPage(row("1"), nextCursor = "p3"),
            V2Fixtures.problem(500, "internal_error", "boom"),
            V2Fixtures.adminOperationalLogsPage(row("0"))
        )
        val vm = LogsViewModel()
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
            V2Fixtures.adminOperationalLogsPage(row("2"), nextCursor = "p2"),
            V2Fixtures.adminOperationalLogsPage(row("2"), row("1"))
        )
        val vm = LogsViewModel()
        vm.load(client)

        vm.loadMore(client)

        assertEquals(listOf("2", "1"), vm.entries.value.map { it.id })
    }

    @Test
    fun `component, request id, playback session and combined levels are sent and trimmed`() = runTest {
        val requests = mutableListOf<io.ktor.http.Parameters>()
        val engine = MockEngine { request ->
            requests += request.url.parameters
            respond(V2Fixtures.adminOperationalLogsPage(), HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://example.test", null, engine)
        val viewModel = LogsViewModel()

        viewModel.load(
            client,
            LogFilter(level = LogLevelChoice.WarnError.wire, component = " ffmpeg ", requestId = "req-1", playbackSessionId = "pb-1"),
        )
        viewModel.load(client, LogFilter(component = " ", requestId = "", playbackSessionId = null))

        assertEquals("error,warn", requests[0]["level"])
        assertEquals("ffmpeg", requests[0]["component"])
        assertEquals("req-1", requests[0]["request_id"])
        assertEquals("pb-1", requests[0]["playback_session_id"])
        assertNull(requests[1]["component"])
        assertNull(requests[1]["request_id"])
        assertNull(requests[1]["playback_session_id"])
    }

    @Test
    fun `changing only the playback session drops the old rows and ignores Load more for the old filter`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(V2Fixtures.adminOperationalLogsPage(V2Fixtures.adminOperationalLog("a"), nextCursor = "c1"), HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://example.test", null, engine)
        val vm = LogsViewModel()
        vm.load(client, LogFilter(playbackSessionId = "one"))
        vm.loadMore(client, LogFilter(playbackSessionId = "two"))
        assertEquals(1, calls)
    }

    @Test
    fun `a playback session also reads its audit entries for the summary`() = runTest {
        val seen = mutableListOf<Pair<String, io.ktor.http.Parameters>>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath to request.url.parameters
            respond(V2Fixtures.adminAuditLogsPage(V2Fixtures.adminAuditLog("a1", optional = """"playback_session_id":"pb-1""""), nextCursor = "more"), HttpStatusCode.OK, json)
        }
        val client = ApiClient("https://example.test", null, engine)
        val vm = LogsViewModel()

        vm.loadSessionAudit(client, "pb-1")

        assertEquals("/api/v2/admin/logs/audit", seen.single().first)
        assertEquals("pb-1", seen.single().second["playback_session_id"])
        assertEquals("200", seen.single().second["limit"])
        assertEquals(listOf("a1"), vm.sessionAudit.value.rows.map { it.id })
        assertTrue(vm.sessionAudit.value.hasMore)
    }

    @Test
    fun `clearing the playback session drops its audit entries without a request`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(V2Fixtures.adminAuditLogsPage(V2Fixtures.adminAuditLog("a1")), HttpStatusCode.OK, json) }
        val client = ApiClient("https://example.test", null, engine)
        val vm = LogsViewModel()
        vm.loadSessionAudit(client, "pb-1")
        vm.loadSessionAudit(client, null)
        assertEquals(1, calls)
        assertTrue(vm.sessionAudit.value.rows.isEmpty())
    }

    @Test
    fun `a failed audit read leaves the summary without audit counts instead of failing the page`() = runTest {
        val client = ApiClient("https://example.test", null, MockEngine { respond("{}", HttpStatusCode.InternalServerError, json) })
        val vm = LogsViewModel()
        vm.loadSessionAudit(client, "pb-1")
        assertTrue(vm.sessionAudit.value.rows.isEmpty())
        assertFalse(vm.sessionAudit.value.available)
        assertNull(vm.errorMessage.value)
    }
}
