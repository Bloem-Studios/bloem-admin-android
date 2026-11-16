package com.bloemstudios.admin.audit

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.ApiCollection
import com.bloemstudios.admin.network.api.listAuditLogs
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuditViewModel : ViewModel() {
    private val _entries = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val entries: StateFlow<List<AuditLogEntry>> = _entries
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    // Opaque cursor from the last page's page.next_cursor -- "load more" replays the same query
    // with this cursor, it is not an offset.
    private var nextCursor: String? = null
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    // Poll, manual refresh and pagination share this lock so resetting the cursor cannot
    // interleave with an append.
    private val mutex = Mutex()

    // True once a continuation page has been appended: a poll then refreshes only the first page
    // and leaves the cursor alone.
    private var hasLoadedMore = false

    // The filter the loaded entries belong to; a request for another filter never merges into them.
    private var activeFilter: String? = null

    /**
     * Loads (or reloads) the first page for [userId] (blank/null clears the filter). Always resets
     * pagination -- it backs the initial load, a filter change and an explicit Refresh.
     */
    suspend fun load(client: ApiClient, userId: String? = null) = mutex.withLock {
        fetchFirstPageLocked(client, normalize(userId), replace = true)
    }

    /**
     * Called from the screen's 30 second poll. Refreshes the first page; when later pages are
     * loaded they stay, with their cursor, and first-page rows replace older copies by id.
     */
    suspend fun pollRefresh(client: ApiClient, userId: String? = null) = mutex.withLock {
        val filter = normalize(userId)
        fetchFirstPageLocked(client, filter, replace = filter != activeFilter || !hasLoadedMore)
    }

    private suspend fun fetchFirstPageLocked(client: ApiClient, filter: String?, replace: Boolean) {
        try {
            val page = client.listAuditLogs(userId = null, cursor = null, actorUserId = filter)
            // cursor/entries/hasMore only change together, and only on success, so a failed
            // refresh leaves what was loaded (and its cursor) exactly as it was.
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
            fail(e, R.string.error_could_not_load_audit)
        }
    }

    /** Appends the next page using the cursor captured from the last load()/loadMore(). */
    suspend fun loadMore(client: ApiClient, userId: String? = null) = mutex.withLock {
        val filter = normalize(userId)
        val cursor = nextCursor
        if (filter != activeFilter || cursor == null) return@withLock
        try {
            val page = client.listAuditLogs(userId = null, cursor = cursor, actorUserId = filter)
            val known = _entries.value.mapTo(HashSet()) { it.id }
            _entries.value = _entries.value + page.items.filter { it.id !in known }
            hasLoadedMore = true
            setCursor(page)
            _errorMessage.value = null
        } catch (e: Exception) {
            fail(e, R.string.error_could_not_load_more_audit)
        }
    }

    private fun setCursor(page: ApiCollection<AuditLogEntry>) {
        nextCursor = page.page?.takeIf { it.has_more }?.next_cursor
        _hasMore.value = nextCursor != null
    }

    private fun fail(e: Exception, fallback: Int) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _errorMessage.value = if (e is ApiError) e.toErrorText(fallback) else ErrorText.Res(fallback)
    }

    private fun normalize(userId: String?) = userId?.trim()?.takeIf { it.isNotEmpty() }
}
