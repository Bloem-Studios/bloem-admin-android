package com.bloemstudios.admin.jobs

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.listJobHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class JobProgress(val current: Long, val total: Long, val unit: String)
@Serializable data class JobFailure(val title: String, val detail: String)
@Serializable data class RetainedAdminJob(
    val id: String, val kind: String, val state: String, val terminal: Boolean,
    val cancelable: Boolean, val created_at: String, val started_at: String? = null,
    val finished_at: String? = null, val library_name: String? = null,
    val progress: JobProgress? = null, val failure: JobFailure? = null
)

/** One persisted run of a task. [steps] is sent for tasks that run several steps; [error_message] for a failed or stopped run. */
@Serializable data class TaskExecution(
    val id: String, val task_key: String, val status: String, val started_at: String,
    val completed_at: String, val duration_ms: Long, val error_message: String? = null,
    val steps: List<TaskStep> = emptyList()
)
data class HistoryRow(val id: String, val title: String, val state: String, val startedAt: String, val detail: String?)

/** The retained background jobs (GET /api/v2/admin/jobs): restores, imports and cleanups, newest first. */
class JobHistoryViewModel : ViewModel() {
    private val _rows = MutableStateFlow<List<HistoryRow>>(emptyList())
    val rows: StateFlow<List<HistoryRow>> = _rows
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore
    private val mutex = Mutex()
    private var cursor: String? = null

    suspend fun load(client: ApiClient) = mutex.withLock { fetch(client, false) }
    suspend fun loadMore(client: ApiClient) = mutex.withLock {
        if (cursor != null) fetch(client, true)
    }
    private suspend fun fetch(client: ApiClient, append: Boolean) {
        try {
            val from = if (append) cursor else null
            val response = client.listJobHistory(from)
            val items = response.items.map { job -> HistoryRow(job.id, job.library_name ?: job.kind.replace('_', ' '), job.state, job.created_at, job.failure?.detail) }
            _rows.value = if (append) (_rows.value + items).distinctBy { it.id } else items
            cursor = response.page?.next_cursor?.takeIf { response.page.has_more }
            _hasMore.value = cursor != null
            _error.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: ApiError) { _error.value = e.toErrorText(R.string.error_could_not_load_jobs) }
        catch (_: Exception) { _error.value = ErrorText.Res(R.string.error_could_not_load_jobs) }
    }
}
