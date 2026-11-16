package com.bloemstudios.admin.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Aggregate ("All Servers") counterpart to AuditScreen: one audit log
 * section per server, each with its own cursor-based "Load more" (see
 * AggregateAuditViewModel). No poll timer -- same as single-server Audit,
 * loads once per filter change plus an explicit Refresh.
 */
@Composable
fun AggregateAuditScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    onSignIn: (AdminServer) -> Unit
) {
    val viewModel = viewModel<AggregateAuditViewModel>()
    val sections by viewModel.sections.collectAsState()
    val hasMoreByServer by viewModel.hasMoreByServer.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val scope = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }

    // The details open here too; Logs is not offered in All servers, so the sheet has no log actions.
    var selected by remember { mutableStateOf<AuditLogEntry?>(null) }
    selected?.let { AuditDetailSheet(it, names = emptyMap(), onDismiss = { selected = null }) }

    var userIdFilter by remember { mutableStateOf("") }
    var debouncedFilter by remember { mutableStateOf(userIdFilter) }
    LaunchedEffect(userIdFilter) {
        delay(300)
        debouncedFilter = userIdFilter
    }

    var initialized by remember(servers, debouncedFilter) { mutableStateOf(false) }
    ForegroundEffect(servers, debouncedFilter) {
        if (initialized) viewModel.pollRefresh(servers, clientFor, debouncedFilter) else {
            viewModel.load(servers, clientFor, debouncedFilter)
            initialized = true
        }
    }

    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.load(servers, clientFor, debouncedFilter) }
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        OutlinedTextField(
            value = userIdFilter,
            onValueChange = { userIdFilter = it },
            label = { Text(stringResource(R.string.audit_filter_user_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        errorMessage?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(sections, key = { it.server.id }) { section ->
                ServerGroupHeader(section.server.displayName)
                val entries = section.result.getOrNull()
                val failure = section.result.exceptionOrNull()
                if (failure != null) {
                    val isAuthFailure = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
                    if (isAuthFailure) {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(section.server) })
                    } else {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, section.server.displayName),
                            { scope.launch { viewModel.retry(section.server, clientFor, debouncedFilter) } })
                    }
                } else {
                    if (entries?.isEmpty() == true) ManagementState(false, true, null, stringResource(R.string.audit_none)) { scope.launch { viewModel.retry(section.server, clientFor, debouncedFilter) } }
                    entries?.forEach { entry -> AuditEntryRow(entry, aggregateUserLine(entry), onClick = { selected = entry }) }
                    if (hasMoreByServer[section.server.id] == true) {
                        TextButton(onClick = { scope.launch { viewModel.loadMore(section.server, clientFor, debouncedFilter) } }) {
                            Text(stringResource(R.string.audit_load_more))
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun aggregateUserLine(entry: AuditLogEntry): String? {
    val impersonator = entry.impersonator_user_id
    val user = entry.user_id
    return when {
        impersonator != null -> stringResource(R.string.audit_impersonation_line, impersonator, user ?: "?")
        user != null -> stringResource(R.string.audit_user_line, user)
        else -> null
    }
}
