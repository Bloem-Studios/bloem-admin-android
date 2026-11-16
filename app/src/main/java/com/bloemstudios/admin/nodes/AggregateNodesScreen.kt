package com.bloemstudios.admin.nodes

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
import com.bloemstudios.admin.network.api.drainNode
import com.bloemstudios.admin.network.api.resumeNode
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

/** All servers: one section of nodes per server; Drain, Resume and Turn on or off go to the server the row belongs to. */
class AggregateNodesViewModel : ViewModel() {
    val sections = AggregateSections { client -> client.readNodes() }
}

@Composable
fun AggregateNodesScreen(servers: List<AdminServer>, store: ServerStore, onSignIn: (AdminServer) -> Unit) {
    val viewModel = viewModel<AggregateNodesViewModel>()
    val sections by viewModel.sections.sections.collectAsState()
    val busy by viewModel.sections.busy.collectAsState()
    val error by viewModel.sections.errorMessage.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }

    ForegroundEffect(servers) { viewModel.sections.load(servers, clientFor) }
    AggregateNodesContent(
        servers, sections, busy, error?.resolve(), onDismissError = viewModel.sections::dismissError,
        onRefresh = { viewModel.sections.load(servers, clientFor) },
        onSignIn = onSignIn,
        onRetry = { server -> reads.launch { viewModel.sections.retry(server, clientFor) } },
        onDrain = { server, node -> scope.launch { viewModel.sections.act(server, "drain:${node.id}", R.string.nodes_error_drain, clientFor) { it.drainNode(node.id) } } },
        onResume = { server, node -> scope.launch { viewModel.sections.act(server, "resume:${node.id}", R.string.nodes_error_resume, clientFor) { it.resumeNode(node.id) } } },
        onSetEnabled = { server, node, enabled ->
            scope.launch {
                viewModel.sections.act(server, "enabled:${node.id}", if (enabled) R.string.nodes_error_enable else R.string.nodes_error_disable, clientFor) {
                    NodeWrites.setEnabled(it, node.id, enabled)
                }
            }
        },
    )
}

@Composable
internal fun AggregateNodesContent(
    servers: List<AdminServer>,
    sections: List<com.bloemstudios.admin.servers.ServerSectionResult<NodesSnapshot>>,
    busy: Set<String>,
    errorText: String?,
    onDismissError: () -> Unit,
    onRefresh: suspend () -> Unit,
    onSignIn: (AdminServer) -> Unit,
    onRetry: (AdminServer) -> Unit,
    onDrain: (AdminServer, NodeSummary) -> Unit,
    onResume: (AdminServer, NodeSummary) -> Unit,
    onSetEnabled: (AdminServer, NodeSummary, Boolean) -> Unit,
) {
    AggregateRefreshSurface(pending = servers.filter { server -> sections.none { it.server.id == server.id } }, onRefresh = onRefresh) {
        Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            errorText?.let { MessageBanner(it, isError = true, onDismiss = onDismissError) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                items(sections, key = { it.server.id }) { section ->
                    ServerGroupHeader(section.server.displayName)
                    val snapshot = section.result.getOrNull()
                    val failure = section.result.exceptionOrNull()
                    when {
                        failure != null -> SectionFailure(section.server, failure, stringResource(R.string.nodes_unsupported), { onSignIn(section.server) }, { onRetry(section.server) })
                        snapshot != null && snapshot.nodes.isEmpty() -> QuietNote(stringResource(R.string.nodes_none))
                        snapshot != null -> NodeList(
                            snapshot.nodes, snapshot.drainSupported, busy, "${section.server.id}:",
                            onDrain = { onDrain(section.server, it) }, onResume = { onResume(section.server, it) },
                            onSetEnabled = { node, enabled -> onSetEnabled(section.server, node, enabled) },
                        )
                    }
                }
            }
        }
    }
}
