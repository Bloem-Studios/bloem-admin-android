package com.bloemstudios.admin.libraries

import com.bloemstudios.admin.jobs.JobFailure
import com.bloemstudios.admin.jobs.JobProgress
import kotlinx.serialization.Serializable

/** One row of GET /api/v2/libraries (the server's v2 Library). Absent warning members mean no warning. */
@Serializable
data class AdminLibrary(
    val id: String,
    val name: String,
    val type: String = "",
    val paths: List<String> = emptyList(),
    val enabled: Boolean = true,
    val last_scanned_at: String? = null,
    val scan_warning_code: String? = null,
    val scan_warning_message: String? = null,
    val scan_warning_at: String? = null,
)

/** GET /api/v2/scan/capabilities: `available`, `disabled`, `not_configured` or `unsupported`. */
@Serializable
data class ScanCapability(val state: String = "")

/** POST /api/v2/scan answers 202 with only this. Whether the scan ran is read from the scan runs. */
@Serializable
data class ScanAccepted(val status: String = "", val mode: String = "", val library_id: String = "")

/** POST /api/v2/scan/cancel. The server spells the member `cancelled`. */
@Serializable
data class ScanCancelled(val cancelled: Int = 0, val library_id: String = "")

/** The counters of a finished scan run (admin_autoscan_scans.go AdminAutoscanScanResult). */
@Serializable
data class ScanResult(
    val new: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val missing: Int = 0,
    val errors: Int = 0,
    /** Non-zero when the run did not scan because an overlapping scan of the same scope was already going. */
    val skipped: Int = 0,
)

/** One row of GET /api/v2/admin/autoscan/scans: every scan, whether a person, a task or an automation asked for it. */
@Serializable
data class ScanRun(
    val id: String,
    val library_id: String,
    val mode: String = "library",
    val path: String? = null,
    val trigger: String = "",
    /** `accepted` (queued), `running`, `completed`, `failed` or `cancelled`. */
    val status: String,
    val error_message: String? = null,
    val requested_at: String? = null,
    val started_at: String? = null,
    val completed_at: String? = null,
    val result: ScanResult? = null,
)

@Serializable
data class MountRootCheck(
    val path: String,
    val reachable: Boolean,
    val error_code: String? = null,
    val error_message: String? = null,
    /** Reachable, but every known file under it is missing: a lost mount showing an empty folder. */
    val suspect_empty: Boolean = false,
)

/** POST /api/v2/libraries/{id}/check-mount. A healthy answer also clears an empty-root or dead-root warning on the server. */
@Serializable
data class MountCheck(
    val status: String = "",
    val library_id: String = "",
    val library_name: String = "",
    val healthy: Boolean,
    val checked_at: String = "",
    val summary: String = "",
    val roots: List<MountRootCheck> = emptyList(),
)

@Serializable
data class RefreshResult(val refreshed: Int = 0, val failed: Int = 0)

/**
 * A library metadata refresh job: the answer of POST /api/v2/libraries/{id}/refresh-metadata, and a row of
 * GET /api/v2/admin/jobs?kind=library_refresh. The refresh answer carries no library id (the page knows it from the
 * request); the list rows do.
 */
@Serializable
data class LibraryJob(
    val id: String,
    val kind: String = "library_refresh",
    /** `queued`, `running`, `canceling`, `succeeded`, `failed` or `canceled`. */
    val state: String,
    val terminal: Boolean = false,
    val cancelable: Boolean = false,
    val created_at: String = "",
    val started_at: String? = null,
    val finished_at: String? = null,
    val library_id: String? = null,
    val library_name: String? = null,
    val progress: JobProgress? = null,
    val refresh_result: RefreshResult? = null,
    val failure: JobFailure? = null,
)

/** Which items a metadata refresh covers. The web calls these "Refresh missing metadata" and "Refresh all metadata". */
enum class RefreshScope(val wireMode: String) {
    /** The server's `quick` mode: matched items never refreshed, missing an overview or artwork, or that failed before. */
    MissingOnly("quick"),

    /** The server's `full` mode: every item, from its providers again. Can take hours on a large library. */
    All("full"),
}
