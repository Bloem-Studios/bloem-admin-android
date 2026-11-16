package com.bloemstudios.admin.jobs

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.cancelTask
import com.bloemstudios.admin.network.api.listTasks
import com.bloemstudios.admin.network.api.runTask
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.fanOutToServers
import com.bloemstudios.admin.servers.ServerRequestLocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aggregate ("All Servers") counterpart to JobsViewModel: one task list per
 * server. run()/cancel() target that one server's client and, like Streams'
 * act(), reload only the acted-on server's section afterward. Schedule
 * editing lives in GuardedScheduleDialog, not here.
 */
private enum class TaskAction { RUN, CANCEL }

class AggregateJobsViewModel : ViewModel() {
    private val _sections = MutableStateFlow<List<ServerSectionResult<List<AdminTask>>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<List<AdminTask>>>> = _sections

    private val _errorMessage = MutableStateFlow<ErrorText?>(null)
    val errorMessage: StateFlow<ErrorText?> = _errorMessage

    // Polls and actions share a lock only within their own server.
    private val operations = com.bloemstudios.admin.InFlightOperations()
    val busyTasks = operations.keys
    private val latestLoad = com.bloemstudios.admin.LatestRequestGate()
    private val locks = ServerRequestLocks()

    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        val generation = latestLoad.begin()
        _sections.update { current -> current.filter { row -> servers.any { it.id == row.server.id } } }
        fanOutToServers(servers, clientFor, locks = locks, onResult = { result ->
            if (latestLoad.isCurrent(generation)) _sections.update { current ->
                val byId = current.associateBy { it.server.id } + (result.server.id to result)
                servers.mapNotNull { byId[it.id] }
            }
        }) { client -> client.listTasks() }
    }

    suspend fun run(server: AdminServer, task: AdminTask, clientFor: (AdminServer) -> ApiClient) =
        act(server, task, clientFor, TaskAction.RUN, R.string.error_could_not_start_job)

    suspend fun cancel(server: AdminServer, task: AdminTask, clientFor: (AdminServer) -> ApiClient) =
        act(server, task, clientFor, TaskAction.CANCEL, R.string.error_could_not_cancel_job)

    private suspend fun act(
        server: AdminServer,
        task: AdminTask,
        clientFor: (AdminServer) -> ApiClient,
        action: TaskAction,
        @StringRes fallbackErrorRes: Int
    ) = operations.run("${server.id}:${task.key}") {
      locks.forServer(server).withLock {
        val client = clientFor(server)
        try {
            when (action) {
                TaskAction.RUN -> client.runTask(task.key)
                TaskAction.CANCEL -> client.cancelTask(task.key)
            }
            _errorMessage.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _errorMessage.value = e.toErrorText(server.displayName, fallbackErrorRes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _errorMessage.value = ErrorText.Res(fallbackErrorRes)
        }
        reloadOneLocked(server, client)
    }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /** Retries just [server]'s section without touching any other section. */
    suspend fun retry(server: AdminServer, clientFor: (AdminServer) -> ApiClient) = locks.forServer(server).withLock {
        reloadOneLocked(server, clientFor(server))
    }

    // Callers must already hold the server lock.
    private suspend fun reloadOneLocked(server: AdminServer, client: ApiClient) {
        val result = runCatching { client.listTasks() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        _sections.update { current ->
            current.map { section -> if (section.server.id == server.id) ServerSectionResult(server, result) else section }
        }
    }
}
