package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.realtime.LiveRefreshEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import com.bloemstudios.admin.ui.components.ListRowCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import kotlinx.coroutines.launch

/** Aggregate ("All Servers") counterpart to ServerConfigScreen: one settings/status section per server, restart confirmed and scoped per server. */
@Composable
fun AggregateServerConfigScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    onSignIn: (AdminServer) -> Unit
) {
    val viewModel = viewModel<AggregateServerConfigViewModel>()
    val sections by viewModel.sections.collectAsState()
    val errorMessage by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }
    var pendingRestart by remember { mutableStateOf<AdminServer?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    LiveRefreshEffect(servers, clientFor, SETTINGS_CHANNELS) { viewModel.load(servers, clientFor) }

    // Settings pages are per server: pick one, and its pages open in place of this list.
    servers.firstOrNull { it.id == opened }?.let { server ->
        ServerSettingsHost(clientFor(server), scopeKey = server.id, onClose = { opened = null })
        return
    }

    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.load(servers, clientFor) }
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        errorMessage?.let { MessageBanner(it.resolve(), isError = true, onDismiss = viewModel::dismissActionError) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(sections, key = { it.server.id }) { section ->
                ServerGroupHeader(section.server.displayName)
                if (pendingKeys.any { it.startsWith("${section.server.id}:") }) {
                    Text(stringResource(R.string.action_in_progress), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
                val data = section.result.getOrNull()
                val failure = section.result.exceptionOrNull()
                if (failure != null) {
                    val isAuthFailure = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
                    if (isAuthFailure) {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(section.server) })
                    } else {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, section.server.displayName),
                            { reads.launch { viewModel.retry(section.server, clientFor) } })
                    }
                } else if (data != null) {
                    ServerStatusCard(data.status, rememberRestartText(data.status), restartEnabled = !(pendingKeys.any { it.startsWith("${section.server.id}:") })) {
                        pendingRestart = section.server
                    }
                    ListRowCard(
                        title = stringResource(R.string.settings_aggregate_open),
                        supporting = listOf(stringResource(R.string.settings_aggregate_open_desc)),
                        onClick = { opened = section.server.id },
                        modifier = Modifier.testTag("settings-open:${section.server.id}"),
                    )
                }
            }
        }
    }
    }

    pendingRestart?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingRestart = null },
            title = { Text(stringResource(R.string.server_config_restart_confirm_title)) },
            text = { Text(stringResource(R.string.settings_restart_confirm_body)) },
            confirmButton = {
                DestructiveTextButton(enabled = !pendingKeys.any { it.startsWith("${server.id}:") }, onClick = {
                    scope.launch { viewModel.restartServer(server, clientFor) }
                    pendingRestart = null
                }) { Text(stringResource(R.string.server_config_restart_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { pendingRestart = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
