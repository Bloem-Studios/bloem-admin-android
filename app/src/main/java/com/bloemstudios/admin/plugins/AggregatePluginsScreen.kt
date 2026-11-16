package com.bloemstudios.admin.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.AggregateSections
import com.bloemstudios.admin.automation.QuietNote
import com.bloemstudios.admin.automation.SectionFailure
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.listPluginInstallations
import com.bloemstudios.admin.network.api.restartPlugin
import com.bloemstudios.admin.network.api.setPluginEnabled
import com.bloemstudios.admin.network.api.updatePlugin
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AggregatePluginsViewModel : ViewModel() {
    val sections = AggregateSections { client -> client.listPluginInstallations() }
    private val _filter = MutableStateFlow(PluginFilter.All)
    val filter: StateFlow<PluginFilter> = _filter
    fun setFilter(filter: PluginFilter) { _filter.value = filter }
}

/** All servers: one Plugins section per server; the filter applies to every section, and each action goes to its own server. */
@Composable
fun AggregatePluginsScreen(servers: List<AdminServer>, store: ServerStore, onSignIn: (AdminServer) -> Unit) {
    val viewModel = viewModel<AggregatePluginsViewModel>()
    val sections by viewModel.sections.sections.collectAsState()
    val busy by viewModel.sections.busy.collectAsState()
    val error by viewModel.sections.errorMessage.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }

    ForegroundEffect(servers) { viewModel.sections.load(servers, clientFor) }
    AggregatePluginsContent(
        servers, sections, filter, viewModel::setFilter, busy, error?.resolve(), onDismissError = viewModel.sections::dismissError,
        onRefresh = { viewModel.sections.load(servers, clientFor) }, onSignIn = onSignIn,
        onRetry = { server -> reads.launch { viewModel.sections.retry(server, clientFor) } },
        onRestart = { server, plugin -> scope.launch { viewModel.sections.act(server, "restart:${plugin.id}", R.string.plugins_error_restart, clientFor) { it.restartPlugin(plugin.id) } } },
        onUpdate = { server, plugin -> scope.launch { viewModel.sections.act(server, "update:${plugin.id}", R.string.plugins_error_update, clientFor) { it.updatePlugin(plugin.id) } } },
        onSetEnabled = { server, plugin, enabled ->
            scope.launch {
                viewModel.sections.act(server, "enabled:${plugin.id}", if (enabled) R.string.plugins_error_turn_on else R.string.plugins_error_turn_off, clientFor) { it.setPluginEnabled(plugin.id, enabled) }
            }
        },
    )
}

@Composable
internal fun AggregatePluginsContent(
    servers: List<AdminServer>,
    sections: List<ServerSectionResult<List<PluginInstallation>>>,
    filter: PluginFilter,
    onFilter: (PluginFilter) -> Unit,
    busy: Set<String>,
    errorText: String?,
    onDismissError: () -> Unit,
    onRefresh: suspend () -> Unit,
    onSignIn: (AdminServer) -> Unit,
    onRetry: (AdminServer) -> Unit,
    onRestart: (AdminServer, PluginInstallation) -> Unit,
    onUpdate: (AdminServer, PluginInstallation) -> Unit,
    onSetEnabled: (AdminServer, PluginInstallation, Boolean) -> Unit,
) {
    AggregateRefreshSurface(pending = servers.filter { server -> sections.none { it.server.id == server.id } }, onRefresh = onRefresh) {
        Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            errorText?.let { MessageBanner(it, isError = true, onDismiss = onDismissError) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                items(sections, key = { it.server.id }) { section ->
                    ServerGroupHeader(section.server.displayName)
                    val installed = section.result.getOrNull()
                    val failure = section.result.exceptionOrNull()
                    when {
                        failure != null -> SectionFailure(section.server, failure, stringResource(R.string.plugins_unsupported), { onSignIn(section.server) }, { onRetry(section.server) })
                        installed != null && installed.isEmpty() -> QuietNote(stringResource(R.string.plugins_none))
                        installed != null -> PluginsContent(
                            installed, filter, onFilter, busy, "${section.server.id}:",
                            onRestart = { onRestart(section.server, it) }, onUpdate = { onUpdate(section.server, it) },
                            onSetEnabled = { plugin, enabled -> onSetEnabled(section.server, plugin, enabled) },
                        )
                    }
                }
            }
        }
    }
}
