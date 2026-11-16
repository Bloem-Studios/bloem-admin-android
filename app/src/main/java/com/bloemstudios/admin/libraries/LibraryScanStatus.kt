package com.bloemstudios.admin.libraries

import java.time.Duration
import java.time.Instant

/** What a library's scans are doing, in the words the page shows. */
enum class ScanState { Queued, Running, Completed, Failed, Cancelled, Skipped }

/** [run] is null while a scan has been asked for but the server's scan runs do not list it yet. */
data class LibraryScanStatus(val state: ScanState, val run: ScanRun?)

/**
 * A scan this page asked for. POST /api/v2/scan answers only `accepted`, so the page remembers which runs it already
 * knew ([knownRunIds]) and reads the first run it did not know as the answer. It never compares the device clock with
 * the server's timestamps.
 */
data class PendingScan(val knownRunIds: Set<String>, val requestedAtMillis: Long)

/** How long a request may go unseen before the page stops saying Queued for it. */
const val PENDING_SCAN_TIMEOUT_MILLIS = 60_000L

private const val STATUS_ACCEPTED = "accepted"
private const val STATUS_RUNNING = "running"

private fun ScanRun.activityInstant(): Instant =
    listOf(completed_at, started_at, requested_at).firstNotNullOfOrNull { value -> value?.let { runCatching { Instant.parse(it) }.getOrNull() } }
        ?: Instant.EPOCH

fun isPendingResolved(pending: PendingScan, libraryId: String, runs: List<ScanRun>, nowMillis: Long): Boolean =
    nowMillis - pending.requestedAtMillis >= PENDING_SCAN_TIMEOUT_MILLIS ||
        runs.any { it.library_id == libraryId && it.id !in pending.knownRunIds }

/**
 * The one status a library row shows: a running scan, else a queued one (or a request not yet listed), else the newest
 * finished one. Null when there is nothing to say (no run, or a status this app does not know).
 */
fun libraryScanStatus(libraryId: String, runs: List<ScanRun>, pending: PendingScan?, nowMillis: Long): LibraryScanStatus? {
    val mine = runs.filter { it.library_id == libraryId }
    mine.filter { it.status == STATUS_RUNNING }.maxByOrNull { it.activityInstant() }
        ?.let { return LibraryScanStatus(ScanState.Running, it) }
    mine.filter { it.status == STATUS_ACCEPTED }.maxByOrNull { it.activityInstant() }
        ?.let { return LibraryScanStatus(ScanState.Queued, it) }
    if (pending != null && !isPendingResolved(pending, libraryId, runs, nowMillis)) return LibraryScanStatus(ScanState.Queued, null)
    val latest = mine.maxByOrNull { it.activityInstant() } ?: return null
    val state = scanRunState(latest) ?: return null
    return LibraryScanStatus(state, latest)
}

/** The state of one run on its own; null for a status this app does not know. A completed run that did nothing because another was running is Skipped. */
fun scanRunState(run: ScanRun): ScanState? = when (run.status) {
    STATUS_ACCEPTED -> ScanState.Queued
    STATUS_RUNNING -> ScanState.Running
    "completed" -> if ((run.result?.skipped ?: 0) > 0) ScanState.Skipped else ScanState.Completed
    "failed" -> ScanState.Failed
    "cancelled" -> ScanState.Cancelled
    else -> null
}

/** True while any scan is queued, running or asked for and not yet listed: the page reads faster then. */
fun scanIsActive(runs: List<ScanRun>, pending: Map<String, PendingScan>, nowMillis: Long): Boolean =
    runs.any { it.status == STATUS_ACCEPTED || it.status == STATUS_RUNNING } ||
        pending.any { (libraryId, request) -> !isPendingResolved(request, libraryId, runs, nowMillis) }

/**
 * Active runs and recent runs read in separate calls become one list: one copy of each run (the active call's copy is
 * fresher), newest first.
 */
fun mergeScanRuns(active: List<ScanRun>, recent: List<ScanRun>): List<ScanRun> =
    (active + recent).distinctBy { it.id }.sortedByDescending { it.activityInstant() }

/** A scan's length, split for the page to put into words. */
data class ScanDuration(val hours: Int, val minutes: Int, val seconds: Int)

fun scanDuration(run: ScanRun): ScanDuration? {
    val start = run.started_at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val end = run.completed_at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val total = Duration.between(start, end)
    if (total.isNegative) return null
    // Plain arithmetic: Duration.toMinutesPart and toSecondsPart need API 31 and the app supports 26.
    val seconds = total.seconds
    return ScanDuration((seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt(), (seconds % 60).toInt())
}
