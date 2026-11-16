package com.bloemstudios.admin.libraries

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted server: each route answers from a mutable table, and every request is recorded. */
private class FakeLibraryServer {
    // Read concurrently: the page asks five questions at once.
    val requests: MutableList<HttpRequestData> = java.util.concurrent.CopyOnWriteArrayList()
    var libraries = """{"items":[
        {"id":"1","name":"Movies","type":"movies","paths":["/mnt/a"],"enabled":true},
        {"id":"2","name":"Shows","type":"series","paths":["/mnt/b","/mnt/c"],"enabled":true,
         "scan_warning_code":"dead_root","scan_warning_message":"Root is unreachable","scan_warning_at":"2026-11-14T07:00:00.000Z"}]}"""
    var capability: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"state":"available"}"""
    var activeRuns = """{"items":[]}"""
    var recentRuns: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"items":[]}"""
    var jobs: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"items":[]}"""
    val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    fun client() = ApiClient("https://example.test", null, MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        val key = "${request.method.value} $path"
        val (status, body) = answers[key] ?: when {
            path == "/api/v2/libraries" -> HttpStatusCode.OK to libraries
            path == "/api/v2/scan/capabilities" -> capability
            path == "/api/v2/admin/autoscan/scans" ->
                if (request.url.parameters["status"] != null) HttpStatusCode.OK to activeRuns else recentRuns
            path == "/api/v2/admin/jobs" -> jobs
            else -> HttpStatusCode.NotFound to """{"detail":"no route"}"""
        }
        respond(body, status, json)
    })

    fun posts() = requests.filter { it.method == HttpMethod.Post }
    fun bodyOf(request: HttpRequestData) = (request.body as TextContent).text
    fun clearRequests() = requests.clear()
}

class LibraryPageTest {
    private val movies = AdminLibrary("1", "Movies", "movies", listOf("/mnt/a"))
    private val shows = AdminLibrary("2", "Shows", "series", listOf("/mnt/b", "/mnt/c"))

    @Test
    fun `refresh reads the libraries, the scan controls, the scan runs and the refresh jobs`() = runTest {
        val server = FakeLibraryServer()
        val page = LibraryPage()

        page.refresh(server.client())

        val state = page.state.value
        assertTrue(state.loaded)
        assertEquals(listOf("1", "2"), state.libraries.map { it.id })
        assertTrue(state.scanControls)
        assertTrue(state.scanHistoryAvailable)
        assertNull(state.loadFailure)
        val paths = server.requests.map { it.url.encodedPath }
        assertTrue("/api/v2/scan/capabilities" in paths)
        assertTrue("/api/v2/admin/jobs" in paths)
        val statusesAsked = server.requests.filter { it.url.encodedPath == "/api/v2/admin/autoscan/scans" }.map { it.url.parameters["status"] }
        assertEquals(setOf("accepted", "running", null), statusesAsked.toSet())
    }

    @Test
    fun `the scan warning of a library is kept`() = runTest {
        val page = LibraryPage()
        page.refresh(FakeLibraryServer().client())
        val warning = page.state.value.libraries.first { it.id == "2" }
        assertEquals("dead_root", warning.scan_warning_code)
        assertEquals("Root is unreachable", warning.scan_warning_message)
    }

    @Test
    fun `a failed library read is reported and keeps what was loaded before`() = runTest {
        val server = FakeLibraryServer()
        val page = LibraryPage()
        page.refresh(server.client())
        server.answers["GET /api/v2/libraries"] = HttpStatusCode.InternalServerError to """{"detail":"database is down"}"""

        page.refresh(server.client())

        assertNotNull(page.state.value.loadFailure)
        assertEquals(2, page.state.value.libraries.size)
        assertTrue(page.state.value.loaded)
    }

    @Test
    fun `a server without scan controls hides scanning and a 404 on the runs hides the history`() = runTest {
        val server = FakeLibraryServer()
        server.capability = HttpStatusCode.NotFound to """{"detail":"not found"}"""
        server.recentRuns = HttpStatusCode.NotFound to """{"detail":"not found"}"""
        val page = LibraryPage()

        page.refresh(server.client())

        assertFalse(page.state.value.scanControls)
        assertFalse(page.state.value.scanHistoryAvailable)
        assertEquals(2, page.state.value.libraries.size)
    }

    @Test
    fun `scan controls that are not configured are hidden`() = runTest {
        val server = FakeLibraryServer()
        server.capability = HttpStatusCode.OK to """{"state":"not_configured"}"""
        val page = LibraryPage()
        page.refresh(server.client())
        assertFalse(page.state.value.scanControls)
    }

    @Test
    fun `scan posts once, then shows queued until the runs list it`() = runTest {
        var now = 1_000L
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/scan"] = HttpStatusCode.Accepted to """{"status":"accepted","mode":"library","library_id":"1"}"""
        val page = LibraryPage(clock = { now })
        val client = server.client()
        page.refresh(client)
        server.clearRequests()

        page.scan(client, movies)

        assertEquals(1, server.posts().size)
        assertEquals("""{"library_id":"1"}""", server.bodyOf(server.posts().single()))
        assertEquals(ScanState.Queued, page.scanStatus("1")?.state)
        assertNull(page.scanStatus("2"))

        // The server now lists the run.
        server.activeRuns = """{"items":[{"id":"r1","library_id":"1","status":"running","started_at":"2026-11-14T09:00:01.000Z"}]}"""
        now += 5_000
        page.refresh(client)
        assertEquals(ScanState.Running, page.scanStatus("1")?.state)
        assertEquals("r1", page.scanStatus("1")?.run?.id)

        server.activeRuns = """{"items":[]}"""
        server.recentRuns = HttpStatusCode.OK to """{"items":[{"id":"r1","library_id":"1","status":"completed","trigger":"manual",
            "started_at":"2026-11-14T09:00:01.000Z","completed_at":"2026-11-14T09:00:40.000Z","result":{"new":3,"updated":1,"missing":0,"errors":0}}]}"""
        now += 5_000
        page.refresh(client)
        assertEquals(ScanState.Completed, page.scanStatus("1")?.state)
        assertEquals(3, page.scanStatus("1")?.run?.result?.new)
        assertTrue("the request is forgotten once its run is listed", page.state.value.pendingScans.isEmpty())
    }

    @Test
    fun `a failed scan shows failed with the server's words`() = runTest {
        val server = FakeLibraryServer()
        server.recentRuns = HttpStatusCode.OK to """{"items":[{"id":"r9","library_id":"1","status":"failed","error_message":"root is unreachable","completed_at":"2026-11-14T09:00:40.000Z"}]}"""
        val page = LibraryPage()
        page.refresh(server.client())
        assertEquals(ScanState.Failed, page.scanStatus("1")?.state)
        assertEquals("root is unreachable", page.scanStatus("1")?.run?.error_message)
    }

    @Test
    fun `scan does nothing when the server has no scan controls`() = runTest {
        val server = FakeLibraryServer()
        server.capability = HttpStatusCode.NotFound to "{}"
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        server.clearRequests()

        page.scan(client, movies)

        assertTrue(server.posts().isEmpty())
    }

    @Test
    fun `a rejected scan says what happened and leaves no queued status`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/scan"] = HttpStatusCode.Conflict to """{"detail":"Library is disabled"}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)

        page.scan(client, movies)

        assertEquals(ErrorText.Raw("Library is disabled"), page.state.value.error)
        assertNull(page.scanStatus("1"))
    }

    @Test
    fun `an error with no sentence falls back to the page's own words and the status`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/scan"] = HttpStatusCode.InternalServerError to "{}"
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        page.scan(client, movies)
        assertEquals(ErrorText.Http(R.string.error_could_not_start_scan, 500), page.state.value.error)
    }

    @Test
    fun `stop posts the library and says so when nothing was running`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/scan/cancel"] = HttpStatusCode.OK to """{"cancelled":0,"library_id":"1"}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        server.clearRequests()

        page.stop(client, movies)

        assertEquals("""{"library_id":"1"}""", server.bodyOf(server.posts().single()))
        assertEquals(ErrorText.Res(R.string.libraries_nothing_to_stop), page.state.value.notice)
    }

    @Test
    fun `stop that cancelled something raises no notice and reads the runs again`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/scan/cancel"] = HttpStatusCode.OK to """{"cancelled":1,"library_id":"1"}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        server.clearRequests()

        page.stop(client, movies)

        assertNull(page.state.value.notice)
        assertTrue(server.requests.any { it.url.encodedPath == "/api/v2/admin/autoscan/scans" })
    }

    @Test
    fun `a healthy mount check is kept for the row and reads the libraries again`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/libraries/2/check-mount"] = HttpStatusCode.OK to
            """{"status":"healthy","library_id":"2","library_name":"Shows","healthy":true,"checked_at":"2026-11-14T09:00:00.000Z","summary":"All 2 roots reachable","roots":[]}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        server.clearRequests()

        page.checkMount(client, shows)

        val outcome = page.state.value.mountChecks["2"]
        assertTrue(outcome is MountOutcome.Checked && outcome.check.healthy)
        assertTrue("the server clears a dead-root warning on a healthy check, so the list is read again",
            server.requests.any { it.method == HttpMethod.Get && it.url.encodedPath == "/api/v2/libraries" })
    }

    @Test
    fun `a failed mount check shows on the row, not as a page error`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/libraries/2/check-mount"] = HttpStatusCode.BadGateway to "{}"
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)

        page.checkMount(client, shows)

        val outcome = page.state.value.mountChecks["2"]
        assertEquals(ErrorText.Http(R.string.error_could_not_check_mount, 502), (outcome as MountOutcome.Failed).error)
        assertNull(page.state.value.error)
    }

    @Test
    fun `refresh metadata sends the chosen scope and follows the returned job`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/libraries/1/refresh-metadata"] = HttpStatusCode.Accepted to
            """{"id":"job-1","kind":"library_refresh","state":"queued","terminal":false,"cancelable":true,"created_at":"2026-11-14T09:00:00.000Z"}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        server.clearRequests()

        page.refreshMetadata(client, movies, RefreshScope.All)

        assertEquals("""{"mode":"full"}""", server.bodyOf(server.posts().single()))
        assertEquals("job-1", page.refreshJobFor("1")?.id)
        assertEquals("queued", page.refreshJobFor("1")?.state)

        server.jobs = HttpStatusCode.OK to """{"items":[{"id":"job-1","kind":"library_refresh","state":"running","terminal":false,"cancelable":true,
            "created_at":"2026-11-14T09:00:00.000Z","library_id":"1","library_name":"Movies","progress":{"current":10,"total":40,"unit":"items"}}]}"""
        page.refresh(client)
        assertEquals("running", page.refreshJobFor("1")?.state)
        assertEquals(10L, page.refreshJobFor("1")?.progress?.current)
        assertNull(page.refreshJobFor("2"))
    }

    @Test
    fun `a finished refresh this page started stays visible, one it did not start does not`() = runTest {
        val server = FakeLibraryServer()
        server.answers["POST /api/v2/libraries/1/refresh-metadata"] = HttpStatusCode.Accepted to
            """{"id":"job-1","kind":"library_refresh","state":"queued","terminal":false,"cancelable":true,"created_at":"2026-11-14T09:00:00.000Z"}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        page.refreshMetadata(client, movies, RefreshScope.MissingOnly)
        server.jobs = HttpStatusCode.OK to """{"items":[
            {"id":"job-1","kind":"library_refresh","state":"succeeded","terminal":true,"cancelable":false,"created_at":"2026-11-14T09:00:00.000Z","library_id":"1","refresh_result":{"library_id":"1","refreshed":38,"failed":2}},
            {"id":"old","kind":"library_refresh","state":"failed","terminal":true,"cancelable":false,"created_at":"2026-11-10T09:00:00.000Z","library_id":"2"}]}"""

        page.refresh(client)

        assertEquals("succeeded", page.refreshJobFor("1")?.state)
        assertEquals(38, page.refreshJobFor("1")?.refresh_result?.refreshed)
        assertNull("an old finished job nobody here started is history, not status", page.refreshJobFor("2"))
    }

    @Test
    fun `a refresh started elsewhere shows while it is running`() = runTest {
        val server = FakeLibraryServer()
        server.jobs = HttpStatusCode.OK to """{"items":[{"id":"x","kind":"library_refresh","state":"running","terminal":false,"cancelable":true,
            "created_at":"2026-11-14T09:00:00.000Z","library_id":"2","library_name":"Shows"}]}"""
        val page = LibraryPage()
        page.refresh(server.client())
        assertEquals("x", page.refreshJobFor("2")?.id)
    }

    @Test
    fun `cancel posts to the job and shows canceling`() = runTest {
        val server = FakeLibraryServer()
        server.jobs = HttpStatusCode.OK to """{"items":[{"id":"x","kind":"library_refresh","state":"running","terminal":false,"cancelable":true,
            "created_at":"2026-11-14T09:00:00.000Z","library_id":"2"}]}"""
        server.answers["POST /api/v2/admin/jobs/x/cancel"] = HttpStatusCode.Accepted to
            """{"id":"x","kind":"library_refresh","state":"canceling","terminal":false,"cancelable":false,"created_at":"2026-11-14T09:00:00.000Z"}"""
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        val job = page.refreshJobFor("2")!!

        server.jobs = HttpStatusCode.OK to """{"items":[{"id":"x","kind":"library_refresh","state":"canceling","terminal":false,"cancelable":false,
            "created_at":"2026-11-14T09:00:00.000Z","library_id":"2"}]}"""
        page.cancelRefresh(client, job)

        assertEquals("/api/v2/admin/jobs/x/cancel", server.posts().single().url.encodedPath)
        assertEquals("canceling", page.refreshJobFor("2")?.state)
    }

    @Test
    fun `each scan request is sent and the busy mark is released afterwards`() = runTest {
        val server = FakeLibraryServer()
        val page = LibraryPage()
        val client = server.client()
        page.refresh(client)
        server.answers["POST /api/v2/scan"] = HttpStatusCode.Accepted to """{"status":"accepted","mode":"library","library_id":"1"}"""
        server.clearRequests()

        page.scan(client, movies)
        page.scan(client, movies)

        assertEquals(2, server.posts().size)
        assertTrue(page.state.value.busy.isEmpty())
    }

    @Test
    fun `reading faster is asked for while a scan or a refresh is active`() = runTest {
        val server = FakeLibraryServer()
        val page = LibraryPage()
        page.refresh(server.client())
        assertFalse(page.isActive())

        server.activeRuns = """{"items":[{"id":"r1","library_id":"1","status":"accepted"}]}"""
        page.refresh(server.client())
        assertTrue(page.isActive())
    }
}
