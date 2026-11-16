package com.bloemstudios.admin.dashboard

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Only sampled metrics are retained here. Session rows remain in each client's repository. */
class AggregateDashboardViewModel : ViewModel() {
    private val _samples = MutableStateFlow<Map<String, DashboardViewModel>>(emptyMap())
    val samples = _samples.asStateFlow()

    fun reconcile(servers: List<AdminServer>) {
        val ids = servers.map { it.id }.toSet()
        val previous = _samples.value
        previous.filterKeys { it !in ids }.values.forEach { it.close() }
        _samples.value = servers.associate { it.id to (previous[it.id] ?: DashboardViewModel()) }
    }

    suspend fun load(servers: List<AdminServer>, forceRefresh: Boolean = false, clientFor: (AdminServer) -> ApiClient) = coroutineScope {
        reconcile(servers)
        servers.forEach { server ->
            val model = _samples.value.getValue(server.id)
            launch {
                try { model.load(clientFor(server), forceRefresh) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { model.reportUnavailable() }
            }
        }
    }

    override fun onCleared() { _samples.value.values.forEach { it.close() } }
}
