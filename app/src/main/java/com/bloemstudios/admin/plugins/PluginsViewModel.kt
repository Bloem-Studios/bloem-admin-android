package com.bloemstudios.admin.plugins

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.ActionRunner
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listPluginInstallations
import com.bloemstudios.admin.network.api.restartPlugin
import com.bloemstudios.admin.network.api.setPluginEnabled
import com.bloemstudios.admin.network.api.updatePlugin
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PluginsViewModel : ViewModel() {
    private val latestLoad = LatestRequestGate()
    private val _plugins = MutableStateFlow<List<PluginInstallation>?>(null)
    val plugins: StateFlow<List<PluginInstallation>?> = _plugins
    private val _filter = MutableStateFlow(PluginFilter.All)
    val filter: StateFlow<PluginFilter> = _filter
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val actions = ActionRunner()
    val busy = actions.busy
    val actionError = actions.error
    fun dismissActionError() = actions.dismiss()

    fun setFilter(filter: PluginFilter) { _filter.value = filter }

    /** The plugins the current filter shows, in list order. */
    fun visible(): List<PluginInstallation> = sortedForList(filtered(_plugins.value.orEmpty(), _filter.value))

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val installed = client.listPluginInstallations()
            if (!latestLoad.isCurrent(generation)) return
            _plugins.value = installed
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (latestLoad.isCurrent(generation)) _error.value = e.toErrorText(R.string.plugins_error_load)
        } catch (_: Exception) {
            if (latestLoad.isCurrent(generation)) _error.value = ErrorText.Res(R.string.plugins_error_load)
        }
    }

    suspend fun restart(client: ApiClient, id: String) = act(client, "restart:$id", R.string.plugins_error_restart) { client.restartPlugin(id) }
    suspend fun update(client: ApiClient, id: String) = act(client, "update:$id", R.string.plugins_error_update) { client.updatePlugin(id) }
    suspend fun setEnabled(client: ApiClient, id: String, enabled: Boolean) =
        act(client, "enabled:$id", if (enabled) R.string.plugins_error_turn_on else R.string.plugins_error_turn_off) { client.setPluginEnabled(id, enabled) }

    private suspend fun act(client: ApiClient, key: String, failure: Int, write: suspend () -> Unit) {
        actions.run(key, failure, write = write)
        load(client)
    }
}
