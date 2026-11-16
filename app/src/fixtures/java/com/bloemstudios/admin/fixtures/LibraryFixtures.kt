package com.bloemstudios.admin.fixtures

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Duration
import java.time.Instant

/**
 * A pretend Bloem server for the Libraries page, in the debug fixture screen and the screenshot tour. Invented data:
 * no real library, path or server. It answers the same routes as the server (libraries, scan capabilities, scan runs,
 * admin jobs, scan, scan cancel, check-mount, refresh-metadata) and keeps just enough state that Scan shows Running,
 * Stop clears it and Refresh metadata starts a job, so the page can be driven by hand.
 */
internal class LibraryFixtures {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun ago(minutes: Long): String = Instant.now().minus(Duration.ofMinutes(minutes)).toString()
    private fun agoSeconds(seconds: Long): String = Instant.now().minusSeconds(seconds).toString()

    private val libraries = """{"items":[
        {"id":"1","name":"Movies","type":"movies","paths":["/mnt/nas/movies","/mnt/nas/movies-4k"],"enabled":true,"last_scanned_at":"${ago(180)}"},
        {"id":"2","name":"TV shows","type":"series","paths":["/mnt/nas/tv","/mnt/nas/tv-kids","/mnt/nas/tv-archive"],"enabled":true,"last_scanned_at":"${ago(2880)}",
         "scan_warning_code":"dead_root","scan_warning_message":"Root /mnt/nas/tv is not reachable: stale file handle","scan_warning_at":"${ago(300)}"},
        {"id":"3","name":"Music","type":"music","paths":["/mnt/nas/music"],"enabled":true,"last_scanned_at":"${ago(1440)}"},
        {"id":"4","name":"Audiobooks","type":"audiobooks","paths":["/mnt/nas/audiobooks"],"enabled":false}]}"""

    // Scans started from the page, newest first.
    private val startedScans = mutableListOf<Pair<String, String>>() // library id to run id
    private val stoppedLibraries = mutableSetOf<String>()
    private var refreshStarted: String? = null
    private val cancelledJobs = mutableSetOf<String>()

    private fun activeRuns(): String {
        val runs = mutableListOf<String>()
        // Music is always mid-scan in the sample.
        if ("3" !in stoppedLibraries) runs += """{"id":"run-music","library_id":"3","mode":"library","trigger":"manual","status":"running","requested_at":"${agoSeconds(150)}","started_at":"${agoSeconds(140)}"}"""
        startedScans.filter { it.first !in stoppedLibraries }.forEach { (library, id) ->
            runs += """{"id":"$id","library_id":"$library","mode":"library","trigger":"manual","status":"running","requested_at":"${agoSeconds(5)}","started_at":"${agoSeconds(3)}"}"""
        }
        return runs.joinToString(",")
    }

    private fun recentRuns(): String = listOf(
        """{"id":"run-1","library_id":"1","mode":"library","trigger":"manual","status":"completed","requested_at":"${ago(181)}","started_at":"${ago(181)}","completed_at":"${ago(180)}",
           "result":{"new":4,"updated":2,"unchanged":1893,"missing":0,"errors":0,"skipped":0}}""",
        """{"id":"run-2","library_id":"2","mode":"library","trigger":"manual","status":"failed","requested_at":"${ago(301)}","started_at":"${ago(300)}","completed_at":"${ago(299)}",
           "error_message":"Root /mnt/nas/tv is not reachable: stale file handle"}""",
        """{"id":"run-3","library_id":"1","mode":"subtree","path":"/mnt/nas/movies/Example Film (2026)","trigger":"autoscan","status":"completed","requested_at":"${ago(600)}","started_at":"${ago(600)}","completed_at":"${ago(599)}",
           "result":{"new":1,"updated":0,"unchanged":0,"missing":0,"errors":0,"skipped":0}}""",
        """{"id":"run-4","library_id":"3","mode":"library","trigger":"manual","status":"completed","requested_at":"${ago(1445)}","started_at":"${ago(1445)}","completed_at":"${ago(1440)}",
           "result":{"new":0,"updated":12,"unchanged":3010,"missing":3,"errors":1,"skipped":0}}""",
    ).joinToString(",")

    private fun job(id: String, library: String, name: String, createdAt: String, current: Int, total: Int): String {
        val canceling = id in cancelledJobs
        return """{"id":"$id","kind":"library_refresh","state":"${if (canceling) "canceling" else "running"}","terminal":false,"cancelable":${!canceling},
            "created_at":"$createdAt","library_id":"$library","library_name":"$name","progress":{"current":$current,"total":$total,"unit":"items"},"library_ids":["$library"]}"""
    }

    private fun jobs(): String = listOfNotNull(
        refreshStarted?.let { job("job-new", it, "Movies", agoSeconds(20), 120, 1900) },
        job("job-music", "3", "Music", ago(12), 340, 1200),
    ).joinToString(",")

    val client: ApiClient by lazy {
        ApiClient("https://fixture.invalid", "fixture", MockEngine { request ->
            val path = request.url.encodedPath
            val libraryId = Regex("^/api/v2/libraries/(\\d+)/").find(path)?.groupValues?.get(1)
            val body = if (request.method == HttpMethod.Post) (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty() else ""
            val bodyLibrary = Regex("\"library_id\":\"(\\d+)\"").find(body)?.groupValues?.get(1)
            when {
                path == "/api/v2/libraries" -> respond(libraries, HttpStatusCode.OK, json)
                path == "/api/v2/scan/capabilities" -> respond("""{"revision":"fixture","state":"available"}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/autoscan/scans" -> {
                    val active = request.url.parameters["status"]
                    val rows = when (active) {
                        "running" -> activeRuns()
                        "accepted" -> ""
                        else -> listOf(activeRuns(), recentRuns()).filter { it.isNotEmpty() }.joinToString(",")
                    }
                    respond("""{"items":[$rows],"page":{"has_more":false}}""", HttpStatusCode.OK, json)
                }
                path == "/api/v2/admin/jobs" -> respond("""{"items":[${jobs()}],"page":{"has_more":false}}""", HttpStatusCode.OK, json)
                path == "/api/v2/scan" && request.method == HttpMethod.Post -> {
                    stoppedLibraries -= bodyLibrary.orEmpty()
                    startedScans += bodyLibrary.orEmpty() to "run-${startedScans.size + 10}"
                    respond("""{"status":"accepted","mode":"library","library_id":"$bodyLibrary"}""", HttpStatusCode.Accepted, json)
                }
                path == "/api/v2/scan/cancel" -> {
                    stoppedLibraries += bodyLibrary.orEmpty()
                    respond("""{"cancelled":1,"library_id":"$bodyLibrary"}""", HttpStatusCode.OK, json)
                }
                path.endsWith("/check-mount") && libraryId == "2" -> respond(
                    """{"status":"unhealthy","library_id":"2","library_name":"TV shows","healthy":false,"checked_at":"${Instant.now()}","summary":"1 of 3 roots not reachable",
                       "roots":[{"path":"/mnt/nas/tv","reachable":false,"error_code":"stale_handle","error_message":"stale file handle","suspect_empty":false},
                                {"path":"/mnt/nas/tv-kids","reachable":true,"error_code":null,"error_message":null,"suspect_empty":false},
                                {"path":"/mnt/nas/tv-archive","reachable":true,"error_code":null,"error_message":null,"suspect_empty":false}]}""",
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/check-mount") -> respond(
                    """{"status":"healthy","library_id":"$libraryId","library_name":"Library","healthy":true,"checked_at":"${Instant.now()}","summary":"All roots reachable","roots":[]}""",
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/refresh-metadata") -> {
                    refreshStarted = libraryId
                    respond(
                        """{"id":"job-new","kind":"library_refresh","state":"queued","terminal":false,"cancelable":true,"created_at":"${Instant.now()}"}""",
                        HttpStatusCode.Accepted, json,
                    )
                }
                path.endsWith("/cancel") && path.contains("/admin/jobs/") -> {
                    val jobId = path.removePrefix("/api/v2/admin/jobs/").removeSuffix("/cancel")
                    cancelledJobs += jobId
                    respond("""{"id":"$jobId","kind":"library_refresh","state":"canceling","terminal":false,"cancelable":false,"created_at":"${Instant.now()}"}""", HttpStatusCode.Accepted, json)
                }
                else -> respond("""{"items":[]}""", HttpStatusCode.OK, json)
            }
        })
    }
}
