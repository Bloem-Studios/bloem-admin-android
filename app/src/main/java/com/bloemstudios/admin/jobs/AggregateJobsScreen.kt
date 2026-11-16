package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.realtime.LiveRefreshEffect
import androidx.compose.ui.Alignment
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
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

/** Aggregate ("All Servers") counterpart to JobsScreen: one task list per server, each action scoped to its own server's client. */
@Composable
fun AggregateJobsScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    onSignIn: (AdminServer) -> Unit
) {
    val viewModel = viewModel<AggregateJobsViewModel>()
    val sections by viewModel.sections.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val busyTasks by viewModel.busyTasks.collectAsState()
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }
    var scheduling by remember { mutableStateOf<Pair<AdminServer, AdminTask>?>(null) }
    var history by remember { mutableStateOf<AdminServer?>(null) }
    var openTask by remember { mutableStateOf<Pair<AdminServer, String>?>(null) }


    LiveRefreshEffect(servers, clientFor, TASK_CHANNELS) { viewModel.load(servers, clientFor) }

    openTask?.let { (server, key) ->
        TaskDetailScreen(clientFor(server), key, onClose = { openTask = null; scope.launch { viewModel.retry(server, clientFor) } }, serverName = server.displayName)
        return
    }

    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.load(servers, clientFor) }
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        errorMessage?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(sections, key = { it.server.id }) { section ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ServerGroupHeader(section.server.displayName, Modifier.weight(1f))
                    OutlinedButton(onClick = { history = section.server }) { Text(stringResource(R.string.jobs_retained_history)) }
                }
                val tasks = section.result.getOrNull()
                val failure = section.result.exceptionOrNull()
                if (failure != null) {
                    val isAuthFailure = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
                    if (isAuthFailure) {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(section.server) })
                    } else {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, section.server.displayName),
                            { reads.launch { viewModel.retry(section.server, clientFor) } })
                    }
                } else {
                    if (tasks?.isEmpty() == true) ManagementState(false, true, null, stringResource(R.string.jobs_none)) { reads.launch { viewModel.load(servers, clientFor) } }
                    tasks?.forEach { task ->
                        TaskRow(
                            task = task,
                            busy = "${section.server.id}:${task.key}" in busyTasks,
                            onRun = { scope.launch { viewModel.run(section.server, task, clientFor) } },
                            onCancel = { scope.launch { viewModel.cancel(section.server, task, clientFor) } },
                            onHistory = { openTask = section.server to task.key },
                            onOpen = { openTask = section.server to task.key },
                            onSchedule = { scheduling = section.server to task },
                        )
                    }
                }
            }
        }
    }
    }

    history?.let { server -> JobHistoryDialog(clientFor(server)) { history = null } }
    scheduling?.let { (server, task) ->
        GuardedScheduleDialog(clientFor(server), task) { scheduling = null }
    }
}
