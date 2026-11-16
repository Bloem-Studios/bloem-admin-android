package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.ActionFeedback
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.InFlightOperations
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.getServerStatus
import com.bloemstudios.admin.network.api.getSettings
import com.bloemstudios.admin.network.api.restartServer
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

/** One server's settings list plus its status probe -- bundled into one section value, same shape as the two GETs single-server ServerConfigViewModel.load() makes. */
data class ServerConfigData(val settings: List<AdminSetting>, val status: ServerStatus)

/**
 * Aggregate ("All Servers") counterpart to ServerConfigViewModel: one
 * settings/status section per server. save()/restartServer() reload
 * only the acted-on server's section afterward, same as every other
 * aggregate view model here. Restart keeps its confirmation dialog in the
 * screen, scoped to the specific server being restarted.
 */
class AggregateServerConfigViewModel : ViewModel() {
    private val _sections = MutableStateFlow<List<ServerSectionResult<ServerConfigData>>>(emptyList())
    val sections: StateFlow<List<ServerSectionResult<ServerConfigData>>> = _sections

    private val feedback = ActionFeedback()
    val actionError = feedback.actionError
    val errorMessage = feedback.errorMessage
    val loadError = feedback.loadError
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    fun dismissActionError() = feedback.dismiss()

    private val latestLoad = com.bloemstudios.admin.LatestRequestGate()
    private val locks = ServerRequestLocks()

    @Synchronized
    private fun publishSections(sections: List<ServerSectionResult<ServerConfigData>>) {
        _sections.value = sections
        feedback.load(sections.firstOrNull { it.result.isFailure }?.let { ErrorText.Res(R.string.error_could_not_load_server_config) })
    }
    @Synchronized
    private fun publishResult(result: ServerSectionResult<ServerConfigData>, servers: List<AdminServer>? = null) {
        val current = _sections.value
        if (servers == null) publishSections(current.map { if (it.server.id == result.server.id) result else it })
        else {
            val byId = current.associateBy { it.server.id } + (result.server.id to result)
            publishSections(servers.mapNotNull { byId[it.id] })
        }
    }

    suspend fun load(servers: List<AdminServer>, clientFor: (AdminServer) -> ApiClient) {
        val generation = latestLoad.begin()
        publishSections(_sections.value.filter { row -> servers.any { it.id == row.server.id } })
        fanOutToServers(servers, clientFor, locks = locks, onResult = { result ->
            if (latestLoad.isCurrent(generation)) publishResult(result, servers)
        }) { client ->
            ServerConfigData(
                adminSettingsFromWire(client.getSettings()),
                client.getServerStatus()
            )
                }
    }

    fun settingKey(server: AdminServer, key: String) = "${server.id}:setting:$key"
    private fun conflicts(server: AdminServer, key: String, active: String) = active.startsWith("${server.id}:") &&
        (key == "${server.id}:restart" || active == "${server.id}:restart" || active == key)
    suspend fun restartServer(server: AdminServer, clientFor: (AdminServer) -> ApiClient) =
        act(server, "${server.id}:restart", R.string.error_could_not_restart_server, clientFor) { it.restartServer() }
    suspend fun save(server: AdminServer, original: SettingEditSnapshot, value: String, clientFor: (AdminServer) -> ApiClient): SettingSaveResult =
        operations.runResult(settingKey(server, original.setting.key), SettingSaveResult.Busy,
            conflicts = { conflicts(server, settingKey(server, original.setting.key), it) }) {
            locks.forServer(server).withLock {
                val client = try { clientFor(server) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@withLock SettingSaveResult.Failed(ErrorText.Res(R.string.error_could_not_update_setting)) }
                val result = saveSetting(client, original, value)
                if (result is SettingSaveResult.Saved) reloadOneLocked(server, client)
                result
            }
        }
    private suspend fun act(server: AdminServer, key: String, errorResource: Int, clientFor: (AdminServer) -> ApiClient, write: suspend (ApiClient) -> Unit): ActionResult =
        operations.runResult(key, ActionResult.Busy, conflicts = { conflicts(server, key, it) }) {
            val generation = feedback.begin()
            locks.forServer(server).withLock {
                var client: ApiClient? = null
                val result = try {
                    client = clientFor(server)
                    write(client)
                    feedback.publish(generation, null)
                    ActionResult.Success
                } catch (e: CancellationException) { throw e }
                catch (e: ApiError) {
                    feedback.publish(generation, e.toErrorText(server.displayName, errorResource))
                    ActionResult.Failure
                } catch (_: Exception) {
                    feedback.publish(generation, ErrorText.Res(errorResource))
                    ActionResult.Failure
                }
                client?.let { reloadOneLocked(server, it) }
                result
            }
        }

    fun clearErrorMessage() = dismissActionError()

    /** Retries just [server]'s section without touching any other section. */
    suspend fun retry(server: AdminServer, clientFor: (AdminServer) -> ApiClient) = locks.forServer(server).withLock {
        reloadOneLocked(server, clientFor(server))
    }

    // Callers must already hold the server lock.
    private suspend fun reloadOneLocked(server: AdminServer, client: ApiClient) {
        val result = runCatching {
            ServerConfigData(
                adminSettingsFromWire(client.getSettings()),
                client.getServerStatus()
            )
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        publishResult(ServerSectionResult(server, result))
    }
}
