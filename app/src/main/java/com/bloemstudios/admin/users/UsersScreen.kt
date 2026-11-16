package com.bloemstudios.admin.users

import com.bloemstudios.admin.ui.roleLabelRes
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.VeilDialog
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.R
import com.bloemstudios.admin.invitations.InvitationsScreen
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.users.detail.SingleServerUserAdmin
import com.bloemstudios.admin.users.detail.UserDetailScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(client: ApiClient) {
    val viewModel = viewModel<UsersViewModel>()
    val users by viewModel.users.collectAsState()
    val error by viewModel.loadError.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val pendingKeys by viewModel.pendingKeys.collectAsState()
    var showingAddUser by remember { mutableStateOf(false) }
    // The open User page. Saved, so rotating keeps it open; the page reads the account again. Keyed on the
    // client, so a page opened on one server is never shown for another.
    var openUserId by rememberSaveable(client) { mutableStateOf<String?>(null) }
    var showingInvitations by rememberSaveable { mutableStateOf(false) }
    // Pure client-side substring filter (see filterUsers in UsersViewModel.kt)
    // -- the user list has no server-side search, so
    // this never triggers a new network call, just narrows the already-
    // loaded `users` list held above.
    // Saved, with the list's scroll position, so returning from a User page finds the list as it was left.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val filteredUsers = remember(users, searchQuery) { filterUsers(users, searchQuery) }
    val pullState = rememberPullToRefreshState()
    // See ServerConfigScreen's identical flag for why this exists: only
    // gates the initial blank-list spinner, not every poll tick.
    var hasLoadedOnce by remember { mutableStateOf(false) }

    ForegroundEffect(client) {
        viewModel.load(client)
        hasLoadedOnce = true
    }

    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try { viewModel.load(client) }
                finally { pullState.endRefresh() }
            }
        }
    }

    // The Users destination shows the list or one User page, never both. The search text and the scroll position
    // live above this branch, so the list is as it was left when the page closes.
    val openId = openUserId
    if (openId != null) {
        val admin = remember(viewModel, client) { SingleServerUserAdmin(viewModel, client) }
        UserDetailScreen(admin, openId, onClose = { openUserId = null })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection)
    ) {

    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        actionError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = viewModel::dismissActionError) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
            OutlinedButton(onClick = { showingInvitations = true }) { Text(stringResource(R.string.users_invitations)) }
            Button(enabled = "create" !in pendingKeys, onClick = { showingAddUser = true }) { Text(stringResource(R.string.users_add_user)) }
        }
        SearchField(searchQuery, { searchQuery = it }, stringResource(R.string.users_search_hint))
        ManagementState(!hasLoadedOnce, filteredUsers.isEmpty(), error?.resolve(), stringResource(R.string.users_none)) { pullState.startRefresh() }
        if (filteredUsers.isNotEmpty()) error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(filteredUsers, key = { it.id }) { user ->
                val pending = user.id in pendingKeys
                UserListRow(user, enabled = !pending, pending = pending) { openUserId = user.id }
            }
        }
    }

        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    if (showingAddUser) {
        AddUserDialog(
            onDismiss = { showingAddUser = false },
            onCreate = { username, email, password, role ->
                viewModel.createUser(username, email, password, role, client)
            }
        )
    }

    if (showingInvitations) {
        InvitationsScreen(client = client, onDismiss = { showingInvitations = false })
    }
}

// Not private: reused by InvitationsScreen's create-invitation role picker
// so the invite flow offers the exact same role vocabulary as direct user
// creation, rather than a second hardcoded list that could drift.
internal val ROLES = listOf("user", "admin")

@Composable
internal fun AddUserDialog(
    onDismiss: () -> Unit,
    onCreate: suspend (username: String, email: String, password: String, role: String) -> CreateUserResult
) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ROLES.first()) }
    var errorMessage by remember { mutableStateOf<ErrorText?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    VeilDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.users_add_dialog_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.action_username)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.action_email)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.action_password)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedControl(
            options = ROLES.map { codeLabel(it, ::roleLabelRes) },
            selectedIndex = ROLES.indexOf(role),
            onSelect = { role = ROLES[it] },
        )
        errorMessage?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Button(
                enabled = !isSubmitting && username.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        errorMessage = null
                        when (val result = onCreate(username, email, password, role)) {
                            CreateUserResult.Busy -> errorMessage = ErrorText.Res(R.string.action_in_progress)
                            is CreateUserResult.Success -> onDismiss()
                            is CreateUserResult.Failure -> errorMessage = result.message
                        }
                        isSubmitting = false
                    }
                }
            ) { Text(stringResource(R.string.users_create)) }
        }
    }
}
