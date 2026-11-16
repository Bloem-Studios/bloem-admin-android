package com.bloemstudios.admin.jobs

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.cancelTask
import com.bloemstudios.admin.network.api.getTask
import com.bloemstudios.admin.network.api.listTaskHistory
import com.bloemstudios.admin.network.api.runTask
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One task's page: the task itself (GET /api/v2/admin/tasks/{key}) and its persisted runs, newest first
 * (GET /api/v2/admin/tasks/{key}/history). A refresh re-reads the task only, so live progress does not reset the runs.
 */
class TaskDetailViewModel : ViewModel() {
    private val _task = MutableStateFlow<AdminTask?>(null)
    val task: StateFlow<AdminTask?> = _task
    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val _runs = MutableStateFlow<List<TaskExecution>>(emptyList())
    val runs: StateFlow<List<TaskExecution>> = _runs
    private val _runsError = MutableStateFlow<ErrorText?>(null)
    val runsError: StateFlow<ErrorText?> = _runsError
    private val _hasMoreRuns = MutableStateFlow(false)
    val hasMoreRuns: StateFlow<Boolean> = _hasMoreRuns
    private val _actionError = MutableStateFlow<ErrorText?>(null)
    val actionError: StateFlow<ErrorText?> = _actionError
    private val runsLock = Mutex()
    private var cursor: String? = null

    /** Reads the task, then its first page of runs. */
    suspend fun open(client: ApiClient, key: String) {
        refreshTask(client, key)
        if (_task.value != null) loadRuns(client, key, append = false)
    }

    suspend fun refreshTask(client: ApiClient, key: String) {
        try {
            _task.value = client.getTask(key)
            _notFound.value = false
            _error.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: ApiError) {
            if (e.statusCode == 404) { _notFound.value = true; _task.value = null }
            else _error.value = e.toErrorText(R.string.error_could_not_load_jobs)
        } catch (_: Exception) { _error.value = ErrorText.Res(R.string.error_could_not_load_jobs) }
    }

    suspend fun reloadRuns(client: ApiClient, key: String) = loadRuns(client, key, append = false)

    suspend fun loadMoreRuns(client: ApiClient) {
        val key = _task.value?.key ?: return
        if (cursor != null) loadRuns(client, key, append = true)
    }

    private suspend fun loadRuns(client: ApiClient, key: String, append: Boolean) = runsLock.withLock {
        try {
            val page = client.listTaskHistory(key, if (append) cursor else null)
            _runs.value = if (append) (_runs.value + page.items).distinctBy { it.id } else page.items
            cursor = page.page?.next_cursor?.takeIf { page.page.has_more }
            _hasMoreRuns.value = cursor != null
            _runsError.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: ApiError) { _runsError.value = e.toErrorText(R.string.jobs_page_runs_load_failed) }
        catch (_: Exception) { _runsError.value = ErrorText.Res(R.string.jobs_page_runs_load_failed) }
    }

    suspend fun run(client: ApiClient, key: String) = act(client, key, R.string.error_could_not_start_job) { client.runTask(key) }
    suspend fun cancel(client: ApiClient, key: String) = act(client, key, R.string.error_could_not_cancel_job) { client.cancelTask(key) }

    fun dismissActionError() { _actionError.value = null }

    private suspend fun act(client: ApiClient, key: String, failure: Int, write: suspend () -> Unit) {
        try {
            write()
            _actionError.value = null
        } catch (e: CancellationException) { throw e }
        catch (e: ApiError) { _actionError.value = e.toErrorText(failure) }
        catch (_: Exception) { _actionError.value = ErrorText.Res(failure) }
        refreshTask(client, key)
    }
}
