package com.bloemstudios.admin.activity

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActivityViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun row(id: String, title: String = "m $id") = V2Fixtures.adminPlaybackHistoryEntry(id, title)
    private fun page(vararg ids: String, next: String? = null) =
        V2Fixtures.adminPlaybackHistoryPage(*ids.map { row(it) }.toTypedArray(), nextCursor = next)

    private fun client(engine: MockEngine) = ApiClient("https://example.test", null, engine)

    @Test
    fun `load decodes history entries`() = runTest {
        val viewModel = ActivityViewModel()
        viewModel.load(client(MockEngine { respond(page("h1"), HttpStatusCode.OK, json) }))

        assertEquals(1, viewModel.entries.value.size)
        assertEquals("m h1", viewModel.entries.value.first().media_title)
        assertEquals("user-1", viewModel.entries.value.first().user_id)
        assertTrue(viewModel.entries.value.first().completed)
    }

    @Test
    fun `first page has no cursor and Load more sends next_cursor`() = runTest {
        val seen = mutableListOf<Map<String, String?>>()
        val engine = MockEngine { request ->
            seen += listOf("cursor", "limit", "offset").associateWith { request.url.parameters[it] }
            if (seen.size == 1) respond(page("a", next = "c1"), HttpStatusCode.OK, json)
            else respond(page("b"), HttpStatusCode.OK, json)
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)

        viewModel.load(c)
        assertNull(seen[0]["cursor"])
        assertEquals("50", seen[0]["limit"])
        assertNull(seen[0]["offset"])
        assertTrue(viewModel.hasMore.value)

        viewModel.loadMore(c)
        assertEquals("c1", seen[1]["cursor"])
        assertEquals(listOf("a", "b"), viewModel.entries.value.map { it.session_id })
    }

    @Test
    fun `Load more is hidden when has_more is false`() = runTest {
        val viewModel = ActivityViewModel()
        viewModel.load(client(MockEngine { respond(page("a", "b"), HttpStatusCode.OK, json) }))
        assertFalse(viewModel.hasMore.value)
    }

    @Test
    fun `Load more is hidden after the last page arrives`() = runTest {
        var n = 0
        val engine = MockEngine { if (++n == 1) respond(page("a", next = "c1"), HttpStatusCode.OK, json) else respond(page("b"), HttpStatusCode.OK, json) }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c)
        viewModel.loadMore(c)
        assertFalse(viewModel.hasMore.value)
    }

    @Test
    fun `changing the user filter restarts from the first page`() = runTest {
        val cursors = mutableListOf<String?>()
        val users = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cursors += request.url.parameters["cursor"]
            users += request.url.parameters["user_id"]
            respond(page("a", next = "c1"), HttpStatusCode.OK, json)
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)

        viewModel.load(c, HistoryFilter(userId = "u1"))
        viewModel.loadMore(c, HistoryFilter(userId = "u1"))
        viewModel.load(c, HistoryFilter(userId = "u2"))
        viewModel.loadMore(c, HistoryFilter(userId = "u2"))

        assertEquals(listOf(null, "c1", null, "c1"), cursors)
        assertEquals(listOf("u1", "u1", "u2", "u2"), users)
    }

    @Test
    fun `Load more with a stale filter is rejected without a request`() = runTest {
        var n = 0
        val engine = MockEngine { n++; respond(page("a", next = "c1"), HttpStatusCode.OK, json) }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c, HistoryFilter(userId = "u1"))
        viewModel.loadMore(c, HistoryFilter(userId = "u2"))
        assertEquals(1, n)
    }

    @Test
    fun `load sends the user_id filter as a query param and omits it when blank`() = runTest {
        var captured: String? = "unset"
        val engine = MockEngine { request ->
            captured = request.url.parameters["user_id"]
            respond(page(), HttpStatusCode.OK, json)
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)

        viewModel.load(c, HistoryFilter(userId = "7"))
        assertEquals("7", captured)
        viewModel.load(c, HistoryFilter(userId = ""))
        assertNull(captured)
    }

    @Test
    fun `load sends the profile and completion filters and omits them when unset`() = runTest {
        val seen = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { request ->
            seen += request.url.parameters["profile_id"] to request.url.parameters["completed"]
            respond(page(), HttpStatusCode.OK, json)
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)

        viewModel.load(c, HistoryFilter(userId = "7", profileId = "p2", completion = CompletionFilter.Partial))
        viewModel.load(c, HistoryFilter(userId = "7", completion = CompletionFilter.Completed))
        viewModel.load(c, HistoryFilter())

        assertEquals(listOf("p2" to "false", null to "true", null to null), seen)
    }

    @Test
    fun `changing only the profile restarts from the first page and drops the old rows`() = runTest {
        val cursors = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cursors += request.url.parameters["cursor"]
            respond(page("a", next = "c1"), HttpStatusCode.OK, json)
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)

        viewModel.load(c, HistoryFilter(userId = "7", profileId = "p1"))
        viewModel.loadMore(c, HistoryFilter(userId = "7", profileId = "p2"))

        assertEquals(listOf<String?>(null), cursors)
    }

    @Test
    fun `a filter with a blank profile is the same filter as none`() {
        assertEquals(HistoryFilter(userId = "7"), HistoryFilter(userId = " 7 ", profileId = " ").normalized())
        assertTrue(HistoryFilter().isEmpty)
        assertFalse(HistoryFilter(completion = CompletionFilter.Partial).isEmpty)
    }

    @Test
    fun `load surfaces a fallback error resource id on decode failure`() = runTest {
        val viewModel = ActivityViewModel()
        viewModel.load(client(MockEngine { respond("not json", HttpStatusCode.OK, json) }))
        assertEquals(ErrorText.Res(R.string.error_could_not_load_activity), viewModel.errorMessage.value)
    }

    @Test
    fun `a failed reload leaves entries and cursor untouched so Load more continues`() = runTest {
        var n = 0
        var lastCursor: String? = null
        val engine = MockEngine { request ->
            when (++n) {
                1 -> respond(page("a", next = "c1"), HttpStatusCode.OK, json)
                2 -> respond(V2Fixtures.problem(500, "internal_error", "Internal error"), HttpStatusCode.InternalServerError, json)
                else -> { lastCursor = request.url.parameters["cursor"]; respond(page("b"), HttpStatusCode.OK, json) }
            }
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c)
        viewModel.load(c)
        assertEquals(1, viewModel.entries.value.size)
        assertNotNull(viewModel.errorMessage.value)

        viewModel.loadMore(c)
        assertEquals("c1", lastCursor)
        assertEquals(listOf("a", "b"), viewModel.entries.value.map { it.session_id })
    }

    @Test
    fun `a failed load under a new filter drops the old filter's rows and Load more does nothing`() = runTest {
        var n = 0
        val engine = MockEngine {
            when (++n) {
                1 -> respond(page("a", next = "c1"), HttpStatusCode.OK, json)
                2 -> respond(V2Fixtures.problem(500, "internal_error", "Internal error"), HttpStatusCode.InternalServerError, json)
                else -> respond(page("late"), HttpStatusCode.OK, json)
            }
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c, HistoryFilter(userId = "u1"))
        assertTrue(viewModel.hasMore.value)

        viewModel.load(c, HistoryFilter(userId = "u2"))

        assertEquals(emptyList<String>(), viewModel.entries.value.map { it.session_id })
        assertFalse(viewModel.hasMore.value)
        assertNotNull(viewModel.errorMessage.value)
        viewModel.loadMore(c, HistoryFilter(userId = "u2"))
        assertEquals(2, n)
    }

    @Test
    fun `pollRefresh keeps loaded later pages and their cursor, deduping by session id with the newer copy`() = runTest {
        var n = 0
        val cursors = mutableListOf<String?>()
        val engine = MockEngine { request ->
            cursors += request.url.parameters["cursor"]
            when (++n) {
                1 -> respond(page("a", "b", next = "c1"), HttpStatusCode.OK, json)
                2 -> respond(page("c", next = "c2"), HttpStatusCode.OK, json)
                3 -> respond(
                    V2Fixtures.adminPlaybackHistoryPage(row("new"), row("a", "a refreshed"), row("b"), nextCursor = "cX"),
                    HttpStatusCode.OK, json
                )
                else -> respond(page("d"), HttpStatusCode.OK, json)
            }
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c)
        viewModel.loadMore(c)

        viewModel.pollRefresh(c)

        assertNull(cursors[2])
        assertEquals(listOf("new", "a", "b", "c"), viewModel.entries.value.map { it.session_id })
        assertEquals("a refreshed", viewModel.entries.value[1].media_title)
        assertTrue(viewModel.hasMore.value)

        viewModel.loadMore(c)
        assertEquals("c2", cursors[3]) // the later-page cursor survived the poll
    }

    @Test
    fun `pollRefresh replaces the list while only the first page is loaded`() = runTest {
        var n = 0
        val engine = MockEngine {
            if (++n == 1) respond(page("a", next = "c1"), HttpStatusCode.OK, json) else respond(page("z", next = "c9"), HttpStatusCode.OK, json)
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c)
        viewModel.pollRefresh(c)
        assertEquals(listOf("z"), viewModel.entries.value.map { it.session_id })
        assertEquals(2, n)
    }

    @Test
    fun `a failed poll keeps loaded pages`() = runTest {
        var n = 0
        val engine = MockEngine {
            when (++n) {
                1 -> respond(page("a", next = "c1"), HttpStatusCode.OK, json)
                2 -> respond(page("b"), HttpStatusCode.OK, json)
                else -> respond(V2Fixtures.problem(500, "internal_error", "Internal error"), HttpStatusCode.InternalServerError, json)
            }
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c); viewModel.loadMore(c); viewModel.pollRefresh(c)
        assertEquals(listOf("a", "b"), viewModel.entries.value.map { it.session_id })
    }

    // A poll tick landing while loadMore is in flight must not reset to page 1.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `pollRefresh waits for an in-flight loadMore and keeps the appended page`() = runTest {
        var n = 0
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine {
            when (++n) {
                1 -> respond(page("a", next = "c1"), HttpStatusCode.OK, json)
                2 -> { gate.await(); respond(page("b"), HttpStatusCode.OK, json) }
                else -> respond(page("a", next = "c1"), HttpStatusCode.OK, json)
            }
        }
        val viewModel = ActivityViewModel()
        val c = client(engine)
        viewModel.load(c)

        val more = launch { viewModel.loadMore(c) }
        runCurrent()
        val poll = launch { viewModel.pollRefresh(c) }
        runCurrent()
        gate.complete(Unit)
        more.join(); poll.join()

        assertEquals(listOf("a", "b"), viewModel.entries.value.map { it.session_id })
    }
}
