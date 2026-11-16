package com.bloemstudios.admin.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.AggregateSections
import com.bloemstudios.admin.automation.SectionFailure
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.deleteBackup
import com.bloemstudios.admin.network.api.startBackup
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
import kotlinx.coroutines.launch

class AggregateBackupsViewModel : ViewModel() {
    val sections = AggregateSections { client -> client.readBackups() }
}

/** All servers: one Backups section per server. Back up now, Delete and the settings editor act on the section's own server. */
@Composable
fun AggregateBackupsScreen(servers: List<AdminServer>, store: ServerStore, onSignIn: (AdminServer) -> Unit) {
    val viewModel = viewModel<AggregateBackupsViewModel>()
    val sections by viewModel.sections.sections.collectAsState()
    val busy by viewModel.sections.busy.collectAsState()
    val error by viewModel.sections.errorMessage.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }
    var editing by remember { mutableStateOf<AdminServer?>(null) }

    ForegroundEffect(servers) { viewModel.sections.load(servers, clientFor) }
    AggregateBackupsContent(
        servers, sections, busy, error?.resolve(), onDismissError = viewModel.sections::dismissError,
        onRefresh = { viewModel.sections.load(servers, clientFor) }, onSignIn = onSignIn,
        onRetry = { server -> reads.launch { viewModel.sections.retry(server, clientFor) } },
        onBackUpNow = { server -> scope.launch { viewModel.sections.act(server, "start", R.string.backups_error_start, clientFor) { it.startBackup() } } },
        onDelete = { server, item -> scope.launch { viewModel.sections.act(server, "delete:${item.id}", R.string.backups_error_delete, clientFor) { it.deleteBackup(item.id) } } },
        onEditSettings = { editing = it },
    )
    editing?.let { server ->
        BackupSettingsDialog(clientFor(server), onDismiss = { editing = null }, onSaved = { scope.launch { viewModel.sections.retry(server, clientFor) } })
    }
}

@Composable
internal fun AggregateBackupsContent(
    servers: List<AdminServer>,
    sections: List<ServerSectionResult<BackupsSnapshot>>,
    busy: Set<String>,
    errorText: String?,
    onDismissError: () -> Unit,
    onRefresh: suspend () -> Unit,
    onSignIn: (AdminServer) -> Unit,
    onRetry: (AdminServer) -> Unit,
    onBackUpNow: (AdminServer) -> Unit,
    onDelete: (AdminServer, BackupItem) -> Unit,
    onEditSettings: (AdminServer) -> Unit,
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
                        failure != null -> SectionFailure(section.server, failure, stringResource(R.string.backups_open_unsupported), { onSignIn(section.server) }, { onRetry(section.server) })
                        snapshot != null -> BackupsContent(
                            snapshot, busy, "${section.server.id}:",
                            onBackUpNow = { onBackUpNow(section.server) }, onEditSettings = { onEditSettings(section.server) },
                            onDelete = { onDelete(section.server, it) },
                        )
                    }
                }
            }
        }
    }
}
