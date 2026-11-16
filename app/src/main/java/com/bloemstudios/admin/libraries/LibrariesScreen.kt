package com.bloemstudios.admin.libraries

import com.bloemstudios.admin.realtime.LiveRefreshEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

/**
 * Libraries of the open server: each library with its scan status, warning, mount check and refresh, then Recent scans.
 * The page reads when it opens and each time the server's events socket says a scan ran or moved (`scans`) or a
 * refresh job changed (`jobs`). There is no timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(client: ApiClient) {
    val viewModel = viewModel<LibrariesViewModel>()
    val page = viewModel.page
    val state by page.state.collectAsState()
    val pullState = rememberPullToRefreshState()

    LiveRefreshEffect(client, LIBRARY_CHANNELS) { viewModel.load(client) }
    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try { viewModel.load(client) } finally { pullState.endRefresh() }
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection).testTag("libraries-page")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VeilSpacing.CompactHorizontal)) {
            val failure = state.loadFailure
            if (!state.loaded) {
                // Loading until the first read answers; a failed first read says what happened and offers Try again.
                ManagementState(loading = failure == null, empty = failure != null, error = failure?.let { libraryLoadError(it).resolve() }) {
                    pullState.startRefresh()
                }
            } else {
                failure?.let { MessageBanner(libraryLoadError(it).resolve(), isError = true, onDismiss = {}) }
                LibraryServerContent(page, client)
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** All servers: one section per server, each acting on its own server. */
@Composable
fun AggregateLibrariesScreen(servers: List<AdminServer>, store: ServerStore, onSignIn: (AdminServer) -> Unit) {
    val viewModel = viewModel<AggregateLibrariesViewModel>()
    val reads = rememberForegroundReadLauncher()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { server -> store.client(server) } }

    LiveRefreshEffect(servers, clientFor, LIBRARY_CHANNELS) { viewModel.load(servers, clientFor) }

    val pages = servers.map { it to viewModel.pageFor(it) }
    val states = pages.map { (_, page) -> page.state.collectAsState().value }
    AggregateRefreshSurface(
        pending = pages.filter { (_, page) -> !page.state.value.loaded && page.state.value.loadFailure == null }.map { it.first },
        onRefresh = { viewModel.load(servers, clientFor) },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VeilSpacing.CompactHorizontal).testTag("libraries-page"),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            pages.forEachIndexed { index, (server, page) ->
                val state = states[index]
                ServerGroupHeader(server.displayName)
                val failure = state.loadFailure
                when {
                    !state.loaded && failure != null -> {
                        val signInNeeded = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
                        if (signInNeeded) ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(server) })
                        else ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, server.displayName),
                            { reads.launch { viewModel.loadOne(server, clientFor) } })
                    }
                    state.loaded -> {
                        failure?.let { MessageBanner(libraryLoadError(it, server.displayName).resolve(), isError = true, onDismiss = {}) }
                        // Every action in this section uses this server's own client.
                        val client = runCatching { clientFor(server) }.getOrNull()
                        if (client != null) LibraryServerContent(page, client)
                        else ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(server) })
                    }
                }
            }
        }
    }
}

/** One server's libraries and Recent scans, with the dialogs for Stop and Refresh metadata. Every action is bound to [page] and [client]. */
@Composable
internal fun LibraryServerContent(page: LibraryPage, client: ApiClient) {
    val state by page.state.collectAsState()
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf<AdminLibrary?>(null) }
    var refreshing by remember { mutableStateOf<AdminLibrary?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        state.error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = page::clearError) }
        state.notice?.let { MessageBanner(it.resolve(), isError = false, onDismiss = page::clearNotice) }
        if (state.libraries.isEmpty()) {
            Text(stringResource(R.string.libraries_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        }
        state.libraries.forEach { library ->
            LibraryRow(
                library = library,
                scanStatus = page.scanStatus(library.id),
                scanHistoryAvailable = state.scanHistoryAvailable,
                mount = state.mountChecks[library.id],
                refreshJob = page.refreshJobFor(library.id),
                scanControls = state.scanControls,
                busy = state.busy,
                actions = LibraryRowActions(
                    onScan = { scope.launch { page.scan(client, library) } },
                    onStop = { stopping = library },
                    onCheckMount = { scope.launch { page.checkMount(client, library) } },
                    onRefreshMetadata = { refreshing = library },
                    onCancelRefresh = { job -> scope.launch { page.cancelRefresh(client, job) } },
                ),
            )
        }
        if (state.scanHistoryAvailable) {
            RecentScans(state.scanRuns, state.libraries.associate { it.id to it.name })
        } else {
            Text(stringResource(R.string.libraries_recent_scans_unavailable), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        if (!state.scanControls) {
            Text(stringResource(R.string.libraries_scan_unavailable), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
    }

    stopping?.let { library ->
        StopScanDialog(library, onConfirm = { stopping = null; scope.launch { page.stop(client, library) } }, onDismiss = { stopping = null })
    }
    refreshing?.let { library ->
        RefreshMetadataDialog(library, onConfirm = { chosen -> refreshing = null; scope.launch { page.refreshMetadata(client, library, chosen) } }, onDismiss = { refreshing = null })
    }
}

/** The channels of the events socket that carry library scan runs and refresh jobs. */
internal val LIBRARY_CHANNELS: Set<String> = setOf(com.bloemstudios.admin.realtime.LiveChannels.SCANS, com.bloemstudios.admin.realtime.LiveChannels.JOBS)
