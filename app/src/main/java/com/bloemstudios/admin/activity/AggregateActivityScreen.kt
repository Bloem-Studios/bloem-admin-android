package com.bloemstudios.admin.activity

import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AggregateActivityScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    onSignIn: (AdminServer) -> Unit,
) {
    val viewModel = viewModel<AggregateActivityViewModel>()
    val sections by viewModel.sections.collectAsState()
    val hasMoreByServer by viewModel.hasMoreByServer.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val scope = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }
    var userIdFilter by remember { mutableStateOf("") }
    var debouncedFilter by remember { mutableStateOf(userIdFilter) }
    var hasLoadedOnce by remember { mutableStateOf(false) }

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
        hasLoadedOnce = true
    }

    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.load(servers, clientFor, debouncedFilter) }
    ) {
    AggregateActivityVeilContent(
        hasLoadedOnce = hasLoadedOnce || sections.isNotEmpty(),
        sections = sections,
        hasMoreByServer = hasMoreByServer,
        errorMessage = errorMessage?.resolve(),
        userIdFilter = userIdFilter,
        onFilterChange = { userIdFilter = it },
        onSignIn = onSignIn,
        onRetry = { server -> scope.launch { viewModel.retry(server, clientFor, debouncedFilter) } },
        onLoadMore = { server -> scope.launch { viewModel.loadMore(server, clientFor, debouncedFilter) } },
    )
    }
}

@Composable
internal fun AggregateActivityVeilContent(
    hasLoadedOnce: Boolean,
    sections: List<ServerSectionResult<List<PlaybackHistoryEntry>>>,
    hasMoreByServer: Map<String, Boolean>,
    errorMessage: String?,
    userIdFilter: String,
    onFilterChange: (String) -> Unit,
    onSignIn: (AdminServer) -> Unit,
    onRetry: (AdminServer) -> Unit,
    onLoadMore: (AdminServer) -> Unit,
) {
    // The details open here too; Logs is not offered in All servers, so the sheet has no log actions.
    var selected by remember { mutableStateOf<PlaybackHistoryEntry?>(null) }
    selected?.let { HistoryDetailSheet(it, onDismiss = { selected = null }) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        OutlinedTextField(
            value = userIdFilter,
            onValueChange = onFilterChange,
            label = { Text(stringResource(R.string.activity_filter_user_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            !hasLoadedOnce -> VeilStateSurface(
                kind = VeilStateSurfaceKind.Loading,
                title = "",
                message = "",
                stateDescription = stringResource(R.string.activity_aggregate_state_loading_description),
            )
            sections.isEmpty() -> VeilStateSurface(
                kind = VeilStateSurfaceKind.Empty,
                title = stringResource(if (userIdFilter.isBlank()) R.string.activity_state_empty_title else R.string.activity_state_filtered_empty_title),
                message = stringResource(if (userIdFilter.isBlank()) R.string.activity_state_empty_message else R.string.activity_state_filtered_empty_message),
                stateDescription = stringResource(R.string.activity_aggregate_state_empty_description),
            )
            else -> {
                if (errorMessage != null) {
                    VeilStateSurface(
                        kind = VeilStateSurfaceKind.PartialFailure,
                        title = stringResource(R.string.activity_state_partial_title),
                        message = errorMessage,
                        stateDescription = stringResource(R.string.activity_aggregate_state_partial_description),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
                ) {
                    items(sections, key = { it.server.id }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                            ServerGroupHeader(section.server.displayName)
                            val entries = section.result.getOrNull()
                            val failure = section.result.exceptionOrNull()
                            when {
                                failure != null -> ServerActivityFailure(section.server, failure, onSignIn, onRetry)
                                entries.isNullOrEmpty() -> VeilStateSurface(
                                    kind = VeilStateSurfaceKind.Empty,
                                    title = stringResource(if (userIdFilter.isBlank()) R.string.activity_state_empty_title else R.string.activity_state_filtered_empty_title),
                                    message = stringResource(if (userIdFilter.isBlank()) R.string.activity_state_empty_message else R.string.activity_state_filtered_empty_message),
                                    stateDescription = stringResource(R.string.activity_server_empty_description, section.server.displayName),
                                )
                                else -> entries.forEach { entry -> HistoryRow(entry, onClick = { selected = entry }) }
                            }
                            if (failure == null && hasMoreByServer[section.server.id] == true) {
                                TextButton(
                                    onClick = { onLoadMore(section.server) },
                                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                                ) { Text(stringResource(R.string.activity_load_more)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerActivityFailure(
    server: AdminServer,
    failure: Throwable,
    onSignIn: (AdminServer) -> Unit,
    onRetry: (AdminServer) -> Unit,
) {
    val isAuthFailure = failure is ApiError && failure.statusCode in setOf(401, 403)
    VeilStateSurface(
        kind = VeilStateSurfaceKind.PartialFailure,
        title = if (isAuthFailure) stringResource(R.string.streams_server_sign_in_title) else stringResource(R.string.streams_server_unavailable_title),
        message = if (isAuthFailure) stringResource(R.string.streams_server_sign_in_message, server.displayName) else stringResource(R.string.streams_aggregate_unreachable, server.displayName),
        stateDescription = stringResource(R.string.activity_server_failure_description, server.displayName),
        action = VeilStateSurfaceAction(
            label = if (isAuthFailure) stringResource(R.string.relogin_sign_in) else stringResource(R.string.action_retry),
            onClick = { if (isAuthFailure) onSignIn(server) else onRetry(server) },
        ),
    )
}
