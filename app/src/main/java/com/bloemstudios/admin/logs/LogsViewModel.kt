package com.bloemstudios.admin.logs

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.ApiCollection
import com.bloemstudios.admin.audit.AuditLogEntry
import com.bloemstudios.admin.network.api.listAppLogs
import com.bloemstudios.admin.network.api.listAuditLogs
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LogsViewModel : ViewModel() {
    private val _entries = MutableStateFlow<List<OperationalLogEntry>>(emptyList())
    val entries: StateFlow<List<OperationalLogEntry>> = _entries
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    // The audit entries of the playback session the page is filtered to, for the session summary.
    private val _sessionAudit = MutableStateFlow(SessionAudit())
    val sessionAudit: StateFlow<SessionAudit> = _sessionAudit

    // Opaque cursor from the last page's page.next_cursor, same contract as AuditViewModel.
    private var nextCursor: String? = null
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    // load()/loadMore()/pollRefresh() take this lock for their whole body so a refresh cannot
    // interleave its cursor reset with an append.
    private val mutex = Mutex()
    private var hasLoadedMore = false

    // The filter the loaded entries belong to; a request for another filter never merges into them.
    private var activeFilter: LogFilter? = null

    /**
     * Loads (or reloads) the first page for [filterBy] (blank values clear a filter). Always resets
     * pagination: it backs the initial load, a filter change and pull-to-refresh.
     */
    suspend fun load(client: ApiClient, filterBy: LogFilter = LogFilter()) = mutex.withLock {
        fetchFirstPageLocked(client, filterBy.normalized(), replace = true)
    }

    /**
     * Called from the screen's 30 second poll. Refreshes the first page; when later pages are
     * loaded they stay, with their cursor, and first-page rows replace older copies by id.
     */
    suspend fun pollRefresh(client: ApiClient, filterBy: LogFilter = LogFilter()) = mutex.withLock {
        val filter = filterBy.normalized()
        fetchFirstPageLocked(client, filter, replace = filter != activeFilter || !hasLoadedMore)
    }

    private suspend fun fetchFirstPageLocked(client: ApiClient, filter: LogFilter, replace: Boolean) {
        try {
            val page = client.listAppLogs(filter, cursor = null)
            // cursor/entries/hasMore only change together, and only on success.
            if (replace) {
                _entries.value = page.items
                setCursor(page)
                hasLoadedMore = false
            } else {
                val fresh = page.items.mapTo(HashSet()) { it.id }
                _entries.value = page.items + _entries.value.filter { it.id !in fresh }
            }
            activeFilter = filter
            _errorMessage.value = null
        } catch (e: Exception) {
            fail(e, R.string.error_could_not_load_logs)
        }
    }

    /** Appends the next page using the cursor captured from the last load()/loadMore(). */
    suspend fun loadMore(client: ApiClient, filterBy: LogFilter = LogFilter()) = mutex.withLock {
        val filter = filterBy.normalized()
        val cursor = nextCursor
        if (filter != activeFilter || cursor == null) return@withLock
        try {
            val page = client.listAppLogs(filter, cursor)
            val known = _entries.value.mapTo(HashSet()) { it.id }
            _entries.value = _entries.value + page.items.filter { it.id !in known }
            hasLoadedMore = true
            setCursor(page)
            _errorMessage.value = null
        } catch (e: Exception) {
            fail(e, R.string.error_could_not_load_more_logs)
        }
    }

    /**
     * Reads the audit entries of [playbackSessionId] (the newest page) for the summary; null clears them. A failure only
     * marks them unavailable: the log list does not depend on them.
     */
    suspend fun loadSessionAudit(client: ApiClient, playbackSessionId: String?) {
        val id = playbackSessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) { _sessionAudit.value = SessionAudit(); return }
        try {
            val page = client.listAuditLogs(userId = null, cursor = null, limit = SESSION_AUDIT_LIMIT, playbackSessionId = id)
            _sessionAudit.value = SessionAudit(page.items, page.page?.has_more == true, available = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _sessionAudit.value = SessionAudit(available = false)
        }
    }

    private fun setCursor(page: ApiCollection<OperationalLogEntry>) {
        nextCursor = page.page?.takeIf { it.has_more }?.next_cursor
        _hasMore.value = nextCursor != null
    }

    private fun fail(e: Exception, fallback: Int) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _errorMessage.value = if (e is ApiError) e.toErrorText(fallback) else ErrorText.Res(fallback)
    }

}

/** The audit entries read for a playback session's summary. [hasMore] means older ones exist; [available] false means the read failed. */
data class SessionAudit(val rows: List<AuditLogEntry> = emptyList(), val hasMore: Boolean = false, val available: Boolean = true)

private const val SESSION_AUDIT_LIMIT = 200
