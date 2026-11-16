package com.bloemstudios.admin.libraries

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.cancelAdminJob
import com.bloemstudios.admin.network.api.cancelLibraryScans
import com.bloemstudios.admin.network.api.checkLibraryMount
import com.bloemstudios.admin.network.api.getScanCapability
import com.bloemstudios.admin.network.api.listAdminLibraries
import com.bloemstudios.admin.network.api.listLibraryRefreshJobs
import com.bloemstudios.admin.network.api.listScanRuns
import com.bloemstudios.admin.network.api.refreshLibraryMetadata
import com.bloemstudios.admin.network.api.startLibraryScan
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routes, bodies and field names are the ones in the server's v2 API. */
class LibrariesApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(requests: MutableList<HttpRequestData>, status: HttpStatusCode = HttpStatusCode.OK, body: String): ApiClient =
        ApiClient("https://example.test", null, MockEngine { request ->
            requests += request
            respond(body, status, json)
        })

    private fun HttpRequestData.bodyText(): String = (body as TextContent).text

    @Test
    fun `libraries decode type paths enabled last scan and the scan warning`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"items":[
            {"id":"1","name":"Movies","type":"movies","paths":["/mnt/a","/mnt/b"],"enabled":true,
             "last_scanned_at":"2026-11-14T08:00:00.000Z","poster_url":"/x.jpg","sort_order":0},
            {"id":"2","name":"Shows","type":"series","paths":["/mnt/tv"],"enabled":false,
             "scan_warning_code":"dead_root","scan_warning_message":"Root /mnt/tv is not reachable",
             "scan_warning_at":"2026-11-14T07:00:00.000Z"}]}""")

        val libraries = api.listAdminLibraries()

        assertEquals("/api/v2/libraries", requests.single().url.encodedPath)
        assertEquals(listOf("1", "2"), libraries.map { it.id })
        assertEquals(2, libraries[0].paths.size)
        assertNull(libraries[0].scan_warning_code)
        assertEquals("2026-11-14T08:00:00.000Z", libraries[0].last_scanned_at)
        assertFalse(libraries[1].enabled)
        assertEquals("dead_root", libraries[1].scan_warning_code)
        assertEquals("Root /mnt/tv is not reachable", libraries[1].scan_warning_message)
        assertEquals("2026-11-14T07:00:00.000Z", libraries[1].scan_warning_at)
    }

    @Test
    fun `scan capability reads the state of GET scan capabilities`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"revision":"r1","state":"not_configured"}""")

        assertEquals("not_configured", api.getScanCapability().state)
        assertEquals("/api/v2/scan/capabilities", requests.single().url.encodedPath)
    }

    @Test
    fun `scan posts the library id to POST scan and reads accepted`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, HttpStatusCode.Accepted, """{"status":"accepted","mode":"library","library_id":"7"}""")

        val accepted = api.startLibraryScan("7")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v2/scan", request.url.encodedPath)
        assertEquals("""{"library_id":"7"}""", request.bodyText())
        assertEquals("accepted", accepted.status)
    }

    @Test
    fun `stop posts the library id to POST scan cancel and reads the cancelled count`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"cancelled":2,"library_id":"7"}""")

        val stopped = api.cancelLibraryScans("7")

        assertEquals("/api/v2/scan/cancel", requests.single().url.encodedPath)
        assertEquals("""{"library_id":"7"}""", requests.single().bodyText())
        assertEquals(2, stopped.cancelled)
    }

    @Test
    fun `mount check posts without a body and reads each root`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"status":"unhealthy","library_id":"7","library_name":"Movies","healthy":false,
            "checked_at":"2026-11-14T09:00:00.000Z","summary":"1 of 2 roots unreachable",
            "roots":[{"path":"/mnt/a","reachable":true,"error_code":null,"error_message":null,"suspect_empty":false},
                     {"path":"/mnt/b","reachable":false,"error_code":"not_found","error_message":"no such file or directory","suspect_empty":false}]}""")

        val check = api.checkLibraryMount("7")

        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/v2/libraries/7/check-mount", requests.single().url.encodedPath)
        assertFalse(check.healthy)
        assertEquals("1 of 2 roots unreachable", check.summary)
        assertTrue(check.roots[0].reachable)
        assertEquals("not_found", check.roots[1].error_code)
        assertEquals("no such file or directory", check.roots[1].error_message)
    }

    @Test
    fun `refresh sends quick for missing only and full for all`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, HttpStatusCode.Accepted, """{"id":"job-1","kind":"library_refresh","state":"queued","terminal":false,"cancelable":true,"created_at":"2026-11-14T09:00:00.000Z"}""")

        val job = api.refreshLibraryMetadata("7", RefreshScope.MissingOnly)
        api.refreshLibraryMetadata("7", RefreshScope.All)

        assertEquals("/api/v2/libraries/7/refresh-metadata", requests[0].url.encodedPath)
        assertEquals("""{"mode":"quick"}""", requests[0].bodyText())
        assertEquals("""{"mode":"full"}""", requests[1].bodyText())
        assertEquals("job-1", job.id)
        assertTrue(job.cancelable)
    }

    @Test
    fun `scan runs read status filter and the counters`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"items":[
            {"id":"s1","library_id":"7","mode":"library","trigger":"manual","status":"completed",
             "requested_at":"2026-11-14T09:00:00.000Z","started_at":"2026-11-14T09:00:01.000Z","completed_at":"2026-11-14T09:01:05.000Z",
             "result":{"new":4,"updated":2,"unchanged":900,"missing":1,"errors":0,"skipped":0,"files_deleted":0}},
            {"id":"s2","library_id":"7","mode":"subtree","path":"/mnt/a/Show","trigger":"autoscan","status":"failed","error_message":"root unreachable"}],
            "page":{"has_more":false},"total":2}""")

        val runs = api.listScanRuns(status = "running", limit = 30)

        assertEquals("/api/v2/admin/autoscan/scans", requests.single().url.encodedPath)
        assertEquals("running", requests.single().url.parameters["status"])
        assertEquals("30", requests.single().url.parameters["limit"])
        assertEquals(4, runs[0].result?.new)
        assertEquals(1, runs[0].result?.missing)
        assertEquals("root unreachable", runs[1].error_message)
        assertNull(runs[1].result)
    }

    @Test
    fun `scan runs omit the status filter when asked for all`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"items":[]}""")

        api.listScanRuns(status = null, limit = 20)

        assertNull(requests.single().url.parameters["status"])
    }

    @Test
    fun `library jobs list only library refresh jobs`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, body = """{"items":[
            {"id":"j1","kind":"library_refresh","state":"running","terminal":false,"cancelable":true,"created_at":"2026-11-14T09:00:00.000Z",
             "library_id":"7","library_name":"Movies","progress":{"current":30,"total":120,"unit":"items"},"library_ids":["7"]},
            {"id":"j2","kind":"library_refresh","state":"succeeded","terminal":true,"cancelable":false,"created_at":"2026-11-14T08:00:00.000Z",
             "library_id":"7","library_name":"Movies","refresh_result":{"library_id":"7","refreshed":118,"failed":2}}]}""")

        val jobs = api.listLibraryRefreshJobs()

        assertEquals("/api/v2/admin/jobs", requests.single().url.encodedPath)
        assertEquals("library_refresh", requests.single().url.parameters["kind"])
        assertEquals("7", jobs[0].library_id)
        assertEquals(30L, jobs[0].progress?.current)
        assertEquals(118, jobs[1].refresh_result?.refreshed)
        assertEquals(2, jobs[1].refresh_result?.failed)
    }

    @Test
    fun `cancel posts to the admin job cancel route`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = client(requests, HttpStatusCode.Accepted, """{"id":"j1","kind":"library_refresh","state":"canceling","terminal":false,"cancelable":false,"created_at":"2026-11-14T09:00:00.000Z"}""")

        val job = api.cancelAdminJob("j1")

        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/v2/admin/jobs/j1/cancel", requests.single().url.encodedPath)
        assertEquals("canceling", job.state)
    }
}
