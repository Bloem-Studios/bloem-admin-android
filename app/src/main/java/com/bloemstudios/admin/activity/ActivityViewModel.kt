package com.bloemstudios.admin.activity

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listPlaybackHistory
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ActivityViewModel : ViewModel() {
    private val _entries = MutableStateFlow<List<PlaybackHistoryEntry>>(emptyList())
    val entries: StateFlow<List<PlaybackHistoryEntry>> = _entries
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    // The cursor for the page after the last one loaded; null means there is none.
    private var nextCursor: String? = null
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    // True once a continuation page has been appended: a poll then refreshes only
    // the first page and must leave nextCursor alone.
    private var loadedMore = false

    // The filter the loaded entries belong to. A request for another filter never
    // appends to or merges into them.
    private var activeFilter: HistoryFilter? = null

    // load()/loadMore()/pollRefresh() read-modify-write the cursor and entries across a
    // suspending call, and run from different coroutines (poll loop, Load more tap).
    private val mutex = Mutex()

    /** Loads (or reloads) the first page for [filterBy], dropping any cursor. */
    suspend fun load(client: ApiClient, filterBy: HistoryFilter = HistoryFilter()) = mutex.withLock {
        val filter = filterBy.normalized()
        fetchFirstPageLocked(client, filter, replace = true)
    }

    /** Appends the page after the current cursor. */
    suspend fun loadMore(client: ApiClient, filterBy: HistoryFilter = HistoryFilter()) = mutex.withLock {
        val filter = filterBy.normalized()
        val cursor = nextCursor
        if (filter != activeFilter || cursor == null) return@withLock
        try {
            val page = client.listPlaybackHistory(filter, cursor)
            val known = _entries.value.mapTo(HashSet()) { it.session_id }
            _entries.value = _entries.value + page.items.filter { it.session_id !in known }
            setCursor(page)
            loadedMore = true
            _errorMessage.value = null
        } catch (e: Exception) {
            fail(e, R.string.error_could_not_load_more_activity)
        }
    }

    /**
     * Called from the screen's periodic poll. Refreshes the first page; if later pages are
     * loaded they stay, with their cursor, and first-page rows replace older copies by
     * session id. Decided inside the mutex so an in-flight loadMore is never overtaken.
     */
    suspend fun pollRefresh(client: ApiClient, filterBy: HistoryFilter = HistoryFilter()) = mutex.withLock {
        val filter = filterBy.normalized()
        fetchFirstPageLocked(client, filter, replace = filter != activeFilter || !loadedMore)
    }

    private suspend fun fetchFirstPageLocked(client: ApiClient, filter: HistoryFilter, replace: Boolean) {
        if (filter != activeFilter) {
            // The rows and cursor belong to the old filter. Drop them before the request so a failure
            // cannot leave them standing in for the new filter, with a Load more that does nothing.
            _entries.value = emptyList()
            nextCursor = null
            _hasMore.value = false
            loadedMore = false
            activeFilter = filter
        }
        try {
            val page = client.listPlaybackHistory(filter, cursor = null)
            if (replace) {
                _entries.value = page.items
                setCursor(page)
                loadedMore = false
            } else {
                val fresh = page.items.mapTo(HashSet()) { it.session_id }
                _entries.value = page.items + _entries.value.filter { it.session_id !in fresh }
            }
            _errorMessage.value = null
        } catch (e: Exception) {
            fail(e, R.string.error_could_not_load_activity)
        }
    }

    private fun setCursor(page: com.bloemstudios.admin.network.api.ApiCollection<PlaybackHistoryEntry>) {
        val info = page.page
        nextCursor = info?.takeIf { it.has_more }?.next_cursor
        _hasMore.value = nextCursor != null
    }

    private fun fail(e: Exception, fallback: Int) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _errorMessage.value = if (e is ApiError) e.toErrorText(fallback) else ErrorText.Res(fallback)
    }

}
