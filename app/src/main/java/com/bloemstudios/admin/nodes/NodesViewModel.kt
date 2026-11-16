package com.bloemstudios.admin.nodes

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.ActionRefused
import com.bloemstudios.admin.automation.ActionRunner
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.drainNode
import com.bloemstudios.admin.network.api.getNodeCapabilities
import com.bloemstudios.admin.network.api.listNodeConfigurations
import com.bloemstudios.admin.network.api.listNodeSummaries
import com.bloemstudios.admin.network.api.resumeNode
import com.bloemstudios.admin.network.api.setNodeEnabled
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the Nodes page shows for one server. */
data class NodesSnapshot(val nodes: List<NodeSummary>, val drainSupported: Boolean)

/** Reads one server's nodes and their drain support. A server that does not state capabilities offers no drain. */
suspend fun ApiClient.readNodes(): NodesSnapshot {
    val capabilities = getNodeCapabilities()
    return NodesSnapshot(listNodeSummaries(), drainSupported = capabilities.drain)
}

/** The writes of the Nodes page, shared by the single-server and All servers pages. */
object NodeWrites {
    /**
     * Enable or disable goes through the node's stored configuration, which is guarded by `config_etag` (the summary does
     * not carry it). The validator is read just before the write, so a node edited meanwhile on the web is refused (412)
     * rather than overwritten.
     */
    suspend fun setEnabled(client: ApiClient, id: String, enabled: Boolean) {
        val validator = client.listNodeConfigurations().firstOrNull { it.id == id }?.config_etag
            ?: throw ActionRefused(ErrorText.Res(R.string.nodes_error_gone))
        try {
            client.setNodeEnabled(id, enabled, validator)
        } catch (e: ApiError) {
            if (e.statusCode == 412 || e.statusCode == 428) throw ActionRefused(ErrorText.Res(R.string.nodes_error_changed))
            throw e
        }
    }
}

class NodesViewModel : ViewModel() {
    private val latestLoad = LatestRequestGate()
    private val _nodes = MutableStateFlow<List<NodeSummary>?>(null)
    val nodes: StateFlow<List<NodeSummary>?> = _nodes
    private val _drainSupported = MutableStateFlow(false)
    val drainSupported: StateFlow<Boolean> = _drainSupported
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val actions = ActionRunner()
    val busy = actions.busy
    val actionError = actions.error
    fun dismissActionError() = actions.dismiss()

    suspend fun load(client: ApiClient) {
        val generation = latestLoad.begin()
        try {
            val snapshot = client.readNodes()
            if (!latestLoad.isCurrent(generation)) return
            _nodes.value = snapshot.nodes
            _drainSupported.value = snapshot.drainSupported
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (latestLoad.isCurrent(generation)) _error.value = e.toErrorText(R.string.nodes_error_load)
        } catch (_: Exception) {
            if (latestLoad.isCurrent(generation)) _error.value = ErrorText.Res(R.string.nodes_error_load)
        }
    }

    suspend fun drain(client: ApiClient, id: String) = act(client, "drain:$id", R.string.nodes_error_drain) { client.drainNode(id) }
    suspend fun resume(client: ApiClient, id: String) = act(client, "resume:$id", R.string.nodes_error_resume) { client.resumeNode(id) }
    suspend fun setEnabled(client: ApiClient, id: String, enabled: Boolean) =
        act(client, "enabled:$id", if (enabled) R.string.nodes_error_enable else R.string.nodes_error_disable) { NodeWrites.setEnabled(client, id, enabled) }

    private suspend fun act(client: ApiClient, key: String, failure: Int, write: suspend () -> Unit) {
        actions.run(key, failure, write = write)
        load(client)
    }
}
