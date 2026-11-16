package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.ActionFeedback
import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.InFlightOperations
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.getServerStatus
import com.bloemstudios.admin.network.api.getSettings
import com.bloemstudios.admin.network.api.restartServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServerConfigSnapshot(
    val settings: List<AdminSetting>,
    val status: ServerStatus
)

class ServerConfigViewModel : ViewModel() {
    private val latestLoad = LatestRequestGate()
    private val _config = MutableStateFlow<ServerConfigSnapshot?>(null)
    val config: StateFlow<ServerConfigSnapshot?> = _config
    private val feedback = ActionFeedback()
    val actionError = feedback.actionError
    val errorMessage = feedback.errorMessage
    val loadError = feedback.loadError
    private val operations = InFlightOperations()
    val pendingKeys = operations.keys
    fun dismissActionError() = feedback.dismiss()

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val settings = adminSettingsFromWire(client.getSettings())
            val status = client.getServerStatus()
            if (!latestLoad.isCurrent(generation)) return
            _config.value = ServerConfigSnapshot(settings, status)
            feedback.load(null)
        } catch (e: ApiError) {
            if (!latestLoad.isCurrent(generation)) return
            feedback.load(e.toErrorText(R.string.error_could_not_load_server_config))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!latestLoad.isCurrent(generation)) return
            feedback.load(ErrorText.Res(R.string.error_could_not_load_server_config))
        }
    }

    fun settingKey(key: String) = "setting:$key"
    private fun conflicts(key: String, active: String) = key == "restart" || active == "restart" || active == key
    private val _restartAnswer = MutableStateFlow<RestartAnswer?>(null)
    /** What the server said to the last accepted restart request (first request, or already under way). */
    val restartAnswer: StateFlow<RestartAnswer?> = _restartAnswer
    fun dismissRestartAnswer() { _restartAnswer.value = null }
    suspend fun restartServer(client: ApiClient) = act("restart", R.string.error_could_not_restart_server, client) {
        _restartAnswer.value = client.restartServer()
    }
    suspend fun save(original: SettingEditSnapshot, value: String, client: ApiClient): SettingSaveResult =
        operations.runResult(settingKey(original.setting.key), SettingSaveResult.Busy,
            conflicts = { conflicts(settingKey(original.setting.key), it) }) {
            val result = saveSetting(client, original, value)
            if (result is SettingSaveResult.Saved) load(client)
            result
        }
    private suspend fun act(key: String, errorResource: Int, client: ApiClient, write: suspend () -> Unit): ActionResult =
        operations.runResult(key, ActionResult.Busy, conflicts = { conflicts(key, it) }) {
            val generation = feedback.begin()
            val result = try {
                write()
                feedback.publish(generation, null)
                ActionResult.Success
            } catch (e: ApiError) {
                feedback.publish(generation, e.toErrorText(errorResource))
                ActionResult.Failure
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                feedback.publish(generation, ErrorText.Res(errorResource))
                ActionResult.Failure
            }
            if (result == ActionResult.Success) load(client)
            result
        }


}
