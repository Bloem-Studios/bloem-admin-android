package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.libraries.AdminLibrary
import com.bloemstudios.admin.libraries.LibraryJob
import com.bloemstudios.admin.libraries.MountCheck
import com.bloemstudios.admin.libraries.RefreshScope
import com.bloemstudios.admin.libraries.ScanAccepted
import com.bloemstudios.admin.libraries.ScanCancelled
import com.bloemstudios.admin.libraries.ScanCapability
import com.bloemstudios.admin.libraries.ScanRun
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.AppDataSignal
import com.bloemstudios.admin.network.NoBody
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

// Every route here is registered in the server's v2 API for an acting admin, and none needs a profile.

private fun libraryPath(id: String, suffix: String = "") = "/api/v2/libraries/${id.encodeURLPathPart()}$suffix"

@Serializable private data class ScanTarget(val library_id: String)
@Serializable private data class RefreshBody(val mode: String)

/** The whole library list. The route declares no paging parameters, so it is read unpaged. */
suspend fun ApiClient.listAdminLibraries(): List<AdminLibrary> = getCollection<AdminLibrary>("/api/v2/libraries").items

/** Whether this server can start scans at all. Older servers answer 404, which callers treat as "no scan controls". */
suspend fun ApiClient.getScanCapability(): ScanCapability = get("/api/v2/scan/capabilities")

/** Queues a scan of one library. The server answers 202 `accepted` and nothing else; [listScanRuns] shows what became of it. Never replayed after a 401. */
suspend fun ApiClient.startLibraryScan(libraryId: String): ScanAccepted =
    postOnce<ScanAccepted, ScanTarget>("/api/v2/scan", ScanTarget(libraryId)).also {
        AppDataSignal.emit(AppDataSignal.ScanStarted(origin.issuer, libraryId))
    }

/** Stops the library's queued and local scans. Never replayed after a 401. */
suspend fun ApiClient.cancelLibraryScans(libraryId: String): ScanCancelled = postOnce("/api/v2/scan/cancel", ScanTarget(libraryId))

/** Probes every root of the library. A healthy answer clears an outstanding empty-root or dead-root warning. */
suspend fun ApiClient.checkLibraryMount(libraryId: String): MountCheck =
    request<MountCheck, NoBody>(libraryPath(libraryId, "/check-mount"), HttpMethod.Post, NoBody)

/** Queues a metadata refresh and answers the job, 202. Never replayed after a 401. */
suspend fun ApiClient.refreshLibraryMetadata(libraryId: String, scope: RefreshScope): LibraryJob =
    postOnce(libraryPath(libraryId, "/refresh-metadata"), RefreshBody(scope.wireMode))

/**
 * Scan runs, newest first, covering manual, task and automation scans. [status] is `accepted`, `running`, `completed`,
 * `failed` or `cancelled`; null reads every status.
 */
suspend fun ApiClient.listScanRuns(status: String?, limit: Int = 50): List<ScanRun> =
    getCollection<ScanRun>("/api/v2/admin/autoscan/scans", mapOf("status" to status, "limit" to limit.toString())).items

/** The newest library metadata refresh jobs, queued and running ones included. */
suspend fun ApiClient.listLibraryRefreshJobs(limit: Int = 30): List<LibraryJob> =
    getCollection<LibraryJob>("/api/v2/admin/jobs", mapOf("kind" to "library_refresh", "limit" to limit.toString())).items

/** Asks a cancelable job (a library refresh) to stop. Work already done stays. The server answers 202, or 200 when it was already canceled. */
suspend fun ApiClient.cancelAdminJob(jobId: String): LibraryJob =
    request<LibraryJob, NoBody>("/api/v2/admin/jobs/${jobId.encodeURLPathPart()}/cancel", HttpMethod.Post, NoBody, replayUnauthorized = false)
