package com.bloemstudios.admin.users

import androidx.compose.foundation.layout.fillMaxSize
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.users.detail.AggregateUserAdmin
import com.bloemstudios.admin.users.detail.UserDetailScreen
import kotlinx.coroutines.launch

/**
 * Aggregate ("All Servers") counterpart to UsersScreen: one user list per
 * server, each with its own client-side search (the user list has
 * no server-side search, same as single-server mode -- narrowing is purely
 * local per section).
 *
 * Aggregate mode deliberately omits impersonation because its sibling tabs
 * are administrator-only. This keeps the visible identity aligned with the
 * authority used by every aggregate action.
 */
@Composable
fun AggregateUsersScreen(
    servers: List<AdminServer>,
    store: ServerStore,
    clientFor: (AdminServer) -> ApiClient,
    onSignIn: (AdminServer) -> Unit
) {
    val viewModel = viewModel<AggregateUsersViewModel>()
    val sections by viewModel.sections.collectAsState()
    val errorMessage by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    val reads = rememberForegroundReadLauncher()
    var showingAddUserFor by remember { mutableStateOf<AdminServer?>(null) }
    // The open User page: the server it lives on and the user's id. Saved so rotating keeps it open.
    var openUser by rememberSaveable(stateSaver = OpenUserSaver) { mutableStateOf<Pair<String, String>?>(null) }
    // Saved, with the list's scroll position, so returning from a User page finds the list as it was left.
    var searchQueryByServer by rememberSaveable { mutableStateOf(HashMap<String, String>()) }
    val listState = rememberLazyListState()

    // Keyed on both inputs so replacing the store-owned admin client factory
    // cannot leave a read using a stale closure. Read on open and on pull; no channel carries accounts.
    ForegroundEffect(servers, clientFor) { viewModel.load(servers, clientFor) }

    // The Users destination shows the lists or one User page, never both. The search texts and the scroll position
    // live above this branch, so the lists are as they were left when the page closes.
    openUser?.let { (serverId, userId) ->
        // The server may have been removed while the page was open; then there is nothing to show.
        val server = servers.firstOrNull { it.id == serverId }
        if (server == null) openUser = null
        else {
            // The page is keyed on the server's id only: a new lambda or server list on a poll must not restart it.
            val latestClientFor by rememberUpdatedState(clientFor)
            val admin = remember(viewModel, server.id) { AggregateUserAdmin(viewModel, server) { latestClientFor(it) } }
            UserDetailScreen(admin, userId, onClose = { openUser = null })
            return
        }
    }

    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.load(servers, clientFor) }
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        errorMessage?.let { MessageBanner(it.resolve(), isError = true, onDismiss = viewModel::dismissActionError) }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(sections, key = { it.server.id }) { section ->
                val server = section.server
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ServerGroupHeader(server.displayName, Modifier.weight(1f))
                    Button(enabled = "${server.id}:create" !in pendingKeys, onClick = { showingAddUserFor = server }) { Text(stringResource(R.string.users_add_user)) }
                }

                val users = section.result.getOrNull()
                val failure = section.result.exceptionOrNull()
                if (failure != null) {
                    val isAuthFailure = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
                    if (isAuthFailure) {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(server) })
                    } else {
                        ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, server.displayName),
                            { reads.launch { viewModel.retry(server, clientFor) } })
                    }
                } else if (users != null) {
                    val query = searchQueryByServer[server.id].orEmpty()
                    SearchField(
                        value = query,
                        onValueChange = { searchQueryByServer = HashMap(searchQueryByServer).apply { put(server.id, it) } },
                        placeholder = stringResource(R.string.users_search_hint),
                    )
                    val filtered = remember(users, query) { filterUsers(users, query) }
                    if (filtered.isEmpty()) ManagementState(false, true, null, stringResource(R.string.users_none)) { reads.launch { viewModel.load(servers, clientFor) } }
                    filtered.forEach { user ->
                        val pending = viewModel.pendingKey(server, user) in pendingKeys
                        UserListRow(user, enabled = !pending, pending = pending) { openUser = server.id to user.id }
                    }
                }
            }
        }
    }
    }

    showingAddUserFor?.let { server ->
        AddUserDialog(
            onDismiss = { showingAddUserFor = null },
            onCreate = { username, email, password, role -> viewModel.createUser(server, username, email, password, role, clientFor) }
        )
    }
}

private val OpenUserSaver = androidx.compose.runtime.saveable.Saver<Pair<String, String>?, List<String>>(
    save = { pair -> pair?.let { listOf(it.first, it.second) } ?: emptyList() },
    restore = { list -> if (list.size == 2) list[0] to list[1] else null }
)
