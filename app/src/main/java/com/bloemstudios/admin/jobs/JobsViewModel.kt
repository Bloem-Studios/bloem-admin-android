package com.bloemstudios.admin.jobs

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.cancelTask
import com.bloemstudios.admin.network.api.listTasks
import com.bloemstudios.admin.network.api.runTask
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class UpdateTriggersResult {
    object Success : UpdateTriggersResult()
    data object Conflict : UpdateTriggersResult()
    data class Failure(val message: ErrorText) : UpdateTriggersResult()
}

class JobsViewModel : ViewModel() {
    private val operations = com.bloemstudios.admin.InFlightOperations()
    val busyTasks = operations.keys
    private val latestLoad = LatestRequestGate()
    private val _tasks = MutableStateFlow<List<AdminTask>>(emptyList())
    val tasks: StateFlow<List<AdminTask>> = _tasks
    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val tasks = client.listTasks()
            if (!latestLoad.isCurrent(generation)) return
            _tasks.value = tasks
            _errorMessage.value = null
        } catch (e: ApiError) {
            if (!latestLoad.isCurrent(generation)) return
            _errorMessage.value = e.toErrorText(R.string.error_could_not_load_jobs)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!latestLoad.isCurrent(generation)) return
            _errorMessage.value = ErrorText.Res(R.string.error_could_not_load_jobs)
        }
    }

    suspend fun run(task: AdminTask, client: ApiClient) = operations.run(task.key) {
        try {
            client.runTask(task.key)
            load(client)
        } catch (e: ApiError) {
            _errorMessage.value = e.toErrorText(R.string.error_could_not_start_job)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _errorMessage.value = ErrorText.Res(R.string.error_could_not_start_job)
        }
    }

    suspend fun cancel(task: AdminTask, client: ApiClient) = operations.run(task.key) {
        try {
            client.cancelTask(task.key)
            load(client)
        } catch (e: ApiError) {
            _errorMessage.value = e.toErrorText(R.string.error_could_not_cancel_job)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _errorMessage.value = ErrorText.Res(R.string.error_could_not_cancel_job)
        }
    }
}
