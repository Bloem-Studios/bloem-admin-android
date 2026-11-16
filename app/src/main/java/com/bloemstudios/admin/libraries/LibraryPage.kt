package com.bloemstudios.admin.libraries

import androidx.annotation.StringRes
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.cancelAdminJob
import com.bloemstudios.admin.network.api.cancelLibraryScans
import com.bloemstudios.admin.network.api.checkLibraryMount
import com.bloemstudios.admin.network.api.getScanCapability
import com.bloemstudios.admin.network.api.listAdminLibraries
import com.bloemstudios.admin.network.api.listLibraryRefreshJobs
import com.bloemstudios.admin.network.api.listScanRuns
import com.bloemstudios.admin.network.api.refreshLibraryMetadata
import com.bloemstudios.admin.network.api.startLibraryScan
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a mount check found, kept for the library's row. A check that could not run is not a finding about the mount. */
sealed interface MountOutcome {
    data class Checked(val check: MountCheck) : MountOutcome
    data class Failed(val error: ErrorText) : MountOutcome
}

data class LibraryPageState(
    /** True once the libraries have been read at least once. */
    val loaded: Boolean = false,
    val libraries: List<AdminLibrary> = emptyList(),
    /** The server can start and stop scans (GET /scan/capabilities says `available`). Without it the page offers neither. */
    val scanControls: Boolean = false,
    /** The server lists scan runs. Without it the page shows no scan status and no Recent scans. */
    val scanHistoryAvailable: Boolean = true,
    val scanRuns: List<ScanRun> = emptyList(),
    val refreshJobs: List<LibraryJob> = emptyList(),
    /** Scans this page asked for, by library, until the runs list them. */
    val pendingScans: Map<String, PendingScan> = emptyMap(),
    /** The refresh job this page started, by library. It stays visible after it finishes. */
    val trackedJobs: Map<String, String> = emptyMap(),
    val mountChecks: Map<String, MountOutcome> = emptyMap(),
    /** Actions in flight, as `scan:<libraryId>`, `stop:`, `mount:`, `refresh:` and `cancel:`. */
    val busy: Set<String> = emptySet(),
    /** The last failed read. The page keeps showing what it had. */
    val loadFailure: Throwable? = null,
    /** The last failed action, for a banner. */
    val error: ErrorText? = null,
    /** A plain result that is not an error, for a banner. */
    val notice: ErrorText? = null,
)

/**
 * One server's Libraries page: what it read, what the person asked for, and the actions. A plain class so the single
 * server page and each section of All servers share it; the screens' view models only hold one or several.
 * [serverName] prefixes the errors in All servers.
 */
class LibraryPage(
    private val serverName: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LibraryPageState())
    val state: StateFlow<LibraryPageState> = _state
    private val latestRead = LatestRequestGate()

    // ---- Reading ----

    /** Reads everything the page shows. A failed libraries read is kept in [LibraryPageState.loadFailure]; the rest degrades quietly. */
    suspend fun refresh(client: ApiClient) {
        val generation = latestRead.begin()
        val before = _state.value
        try {
            val snapshot = readSnapshot(client, before)
            if (!latestRead.isCurrent(generation)) return
            _state.update { current -> current.withSnapshot(snapshot, clock()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (latestRead.isCurrent(generation)) _state.update { it.copy(loadFailure = e) }
        }
    }

    private class Snapshot(
        val libraries: List<AdminLibrary>,
        val scanControls: Boolean,
        val scanRuns: List<ScanRun>?,
        val scanHistoryAvailable: Boolean,
        val refreshJobs: List<LibraryJob>?,
    )

    private suspend fun readSnapshot(client: ApiClient, before: LibraryPageState): Snapshot = coroutineScope {
        val libraries = async { client.listAdminLibraries() }
        val capability = async { runCatchingKeepingCancel { client.getScanCapability() } }
        val active = async { runCatchingKeepingCancel { client.listScanRuns("accepted") + client.listScanRuns("running") } }
        val recent = async { runCatchingKeepingCancel { client.listScanRuns(null, RECENT_SCAN_COUNT) } }
        val jobs = async { runCatchingKeepingCancel { client.listLibraryRefreshJobs() } }

        val capabilityResult = capability.await()
        val scanControls = when {
            capabilityResult.isSuccess -> capabilityResult.getOrThrow().state == "available"
            capabilityResult.exceptionOrNull().isUnsupported() -> false
            else -> before.scanControls
        }
        val activeResult = active.await()
        val recentResult = recent.await()
        val historyGone = activeResult.exceptionOrNull().isUnsupported() || recentResult.exceptionOrNull().isUnsupported()
        val runs = if (activeResult.isSuccess && recentResult.isSuccess) {
            mergeScanRuns(activeResult.getOrThrow(), recentResult.getOrThrow())
        } else if (historyGone) emptyList() else null
        Snapshot(libraries.await(), scanControls, runs, scanHistoryAvailable = !historyGone, refreshJobs = jobs.await().getOrNull())
    }

    private fun LibraryPageState.withSnapshot(snapshot: Snapshot, now: Long): LibraryPageState {
        val runs = snapshot.scanRuns ?: scanRuns
        val fetchedJobs = snapshot.refreshJobs
        val jobs = if (fetchedJobs == null) refreshJobs else {
            // A job this page started stays listed even if the newest-jobs read no longer reaches it.
            fetchedJobs + refreshJobs.filter { old -> old.id in trackedJobs.values && fetchedJobs.none { it.id == old.id } }
        }
        return copy(
            loaded = true,
            loadFailure = null,
            libraries = snapshot.libraries,
            scanControls = snapshot.scanControls,
            scanHistoryAvailable = snapshot.scanHistoryAvailable,
            scanRuns = runs,
            refreshJobs = jobs,
            pendingScans = pendingScans.filter { (libraryId, request) -> !isPendingResolved(request, libraryId, runs, now) },
        )
    }

    /** A server that could not be reached at all (no client, a timeout): kept like a failed read so its section can say so. */
    fun reportLoadFailure(failure: Throwable) = _state.update { it.copy(loadFailure = failure) }

    // ---- What a row shows ----

    fun scanStatus(libraryId: String): LibraryScanStatus? {
        val s = _state.value
        return libraryScanStatus(libraryId, s.scanRuns, s.pendingScans[libraryId], clock())
    }

    /** The refresh to show for a library: a running one (whoever started it), else the one this page started. */
    fun refreshJobFor(libraryId: String): LibraryJob? {
        val s = _state.value
        val mine = s.refreshJobs.filter { it.library_id == libraryId }
        mine.filterNot { it.terminal }.maxByOrNull { it.created_at }?.let { return it }
        val tracked = s.trackedJobs[libraryId] ?: return null
        return mine.firstOrNull { it.id == tracked }
    }

    /** True while a scan or a refresh is under way, so the page reads faster. */
    fun isActive(): Boolean {
        val s = _state.value
        return scanIsActive(s.scanRuns, s.pendingScans, clock()) || s.refreshJobs.any { !it.terminal }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---- Acting ----

    suspend fun scan(client: ApiClient, library: AdminLibrary) = act("scan:${library.id}", R.string.error_could_not_start_scan) {
        if (!_state.value.scanControls) return@act
        val known = _state.value.scanRuns.mapTo(mutableSetOf()) { it.id }
        client.startLibraryScan(library.id)
        _state.update { it.copy(pendingScans = it.pendingScans + (library.id to PendingScan(known, clock())), error = null, notice = null) }
        refresh(client)
    }

    suspend fun stop(client: ApiClient, library: AdminLibrary) = act("stop:${library.id}", R.string.error_could_not_stop_scan) {
        if (!_state.value.scanControls) return@act
        val stopped = client.cancelLibraryScans(library.id)
        // Whatever was waiting to be listed is no longer coming.
        _state.update {
            it.copy(
                pendingScans = it.pendingScans - library.id, error = null,
                notice = if (stopped.cancelled == 0) ErrorText.Res(R.string.libraries_nothing_to_stop) else null,
            )
        }
        refresh(client)
    }

    /** The answer lands on the row. A healthy answer clears a dead-root or empty-root warning on the server, so the list is read again. */
    suspend fun checkMount(client: ApiClient, library: AdminLibrary) = act("mount:${library.id}", R.string.error_could_not_check_mount, onFailure = { error ->
        _state.update { it.copy(mountChecks = it.mountChecks + (library.id to MountOutcome.Failed(error)), error = null) }
    }) {
        val check = client.checkLibraryMount(library.id)
        _state.update { it.copy(mountChecks = it.mountChecks + (library.id to MountOutcome.Checked(check)), error = null) }
        refresh(client)
    }

    suspend fun refreshMetadata(client: ApiClient, library: AdminLibrary, scope: RefreshScope) =
        act("refresh:${library.id}", R.string.error_could_not_start_refresh) {
            val job = client.refreshLibraryMetadata(library.id, scope).let { it.copy(library_id = it.library_id ?: library.id, library_name = it.library_name ?: library.name) }
            _state.update {
                it.copy(
                    refreshJobs = listOf(job) + it.refreshJobs.filterNot { old -> old.id == job.id },
                    trackedJobs = it.trackedJobs + (library.id to job.id), error = null, notice = null,
                )
            }
            refresh(client)
        }

    suspend fun cancelRefresh(client: ApiClient, job: LibraryJob) = act("cancel:${job.id}", R.string.error_could_not_cancel_refresh) {
        val answer = client.cancelAdminJob(job.id)
        _state.update {
            it.copy(
                refreshJobs = it.refreshJobs.map { old -> if (old.id == job.id) answer.copy(library_id = old.library_id, library_name = old.library_name) else old },
                error = null,
            )
        }
        refresh(client)
    }

    private suspend fun act(
        key: String,
        @StringRes fallback: Int,
        onFailure: ((ErrorText) -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        var acquired = false
        _state.update { current ->
            acquired = key !in current.busy
            if (acquired) current.copy(busy = current.busy + key) else current
        }
        if (!acquired) return
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = when (e) {
                is ApiError -> serverName?.let { e.toErrorText(it, fallback) } ?: e.toErrorText(fallback)
                else -> ErrorText.Res(fallback).let { plain -> serverName?.let { ErrorText.Prefixed(it, plain) } ?: plain }
            }
            if (onFailure != null) onFailure(text) else _state.update { it.copy(error = text) }
        } finally {
            _state.update { it.copy(busy = it.busy - key) }
        }
    }

    private companion object {
        const val RECENT_SCAN_COUNT = 30
    }
}

/** The error to show for a failed read of the page, in the page's own words. */
fun libraryLoadError(failure: Throwable, serverName: String? = null): ErrorText = when (failure) {
    is ApiError -> serverName?.let { failure.toErrorText(it, R.string.error_could_not_load_libraries) } ?: failure.toErrorText(R.string.error_could_not_load_libraries)
    else -> ErrorText.Res(R.string.error_could_not_load_libraries).let { plain -> serverName?.let { ErrorText.Prefixed(it, plain) } ?: plain }
}

/** An older server answers 404 or 501 for a route it does not have, and 503 when the part behind it is off. */
private fun Throwable?.isUnsupported(): Boolean = this is ApiError && statusCode in setOf(404, 501, 503)

private inline fun <T> runCatchingKeepingCancel(block: () -> T): Result<T> =
    try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
