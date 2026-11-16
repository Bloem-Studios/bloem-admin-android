package com.bloemstudios.admin.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.Choice
import com.bloemstudios.admin.ui.components.ChoiceSheet
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.PickerField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.users.UserDirectoryViewModel
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.launch

/**
 * The audit trail, newest first: what each request did and changed (the action with its before and after lines leads,
 * then METHOD and path), who made it, and a tap for the details. Filter by the acting user, picked by name; the list
 * follows the web's actor filter, which includes requests made while impersonating that user. Cursor-paginated.
 *
 * Reads when it opens (and again when the app comes back to the front) and on pull; no channel carries the audit trail, so
 * no timer does either. A re-read after coming back refreshes only the first page: pages brought
 * in with "Load more" stay with their cursor, and a first-page row replaces its older copy by id. A filter change or a pull
 * down starts again from page one. [onOpenLogs] opens Logs on an entry's playback session or request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(client: ApiClient, onOpenLogs: ((LogsFocus) -> Unit)? = null) {
    val viewModel = viewModel<AuditViewModel>()
    val directory = viewModel<UserDirectoryViewModel>()
    val entries by viewModel.entries.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val users by directory.users.collectAsState()
    val usersError by directory.usersError.collectAsState()
    val scope = rememberForegroundReadLauncher()
    val pullState = rememberPullToRefreshState()
    var hasLoadedOnce by remember(client) { mutableStateOf(false) }
    var userId by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AuditLogEntry?>(null) }
    val names = remember(users) { users.associate { it.id to it.username } }

    ForegroundEffect(client) { directory.loadUsers(client) }

    var initialized by remember(client, userId) { mutableStateOf(false) }
    ForegroundEffect(client, userId) {
        if (initialized) viewModel.pollRefresh(client, userId) else {
            viewModel.load(client, userId)
            initialized = true
        }
        hasLoadedOnce = true
    }

    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try { viewModel.load(client, userId) }
                finally { pullState.endRefresh() }
            }
        }
    }

    val allUsers = stringResource(R.string.audit_all_users)
    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            item(key = "filter") {
                PickerField(
                    label = stringResource(R.string.audit_filter_user),
                    value = names[userId] ?: userId?.let { stringResource(R.string.audit_user_line, it) } ?: allUsers,
                    onClick = { picking = true },
                    supportingText = usersError?.let { stringResource(R.string.history_users_unavailable, it.resolve()) },
                    modifier = Modifier.padding(top = VeilSpacing.Small).testTag("audit-filter-user"),
                )
            }
            if (!hasLoadedOnce || entries.isEmpty()) item(key = "state") {
                ManagementState(!hasLoadedOnce, entries.isEmpty(), error?.resolve(), stringResource(if (userId != null) R.string.audit_filtered_none else R.string.audit_none)) { pullState.startRefresh() }
            }
            if (entries.isNotEmpty()) error?.let { message -> item(key = "error") { MessageBanner(message.resolve(), isError = true, onDismiss = {}) } }
            items(entries, key = { it.id }) { entry -> AuditEntryRow(entry, auditActor(entry, names), onClick = { selected = entry }) }
            if (hasMore) item(key = "more") {
                TextButton(
                    onClick = { scope.launch { viewModel.loadMore(client, userId) } },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(R.string.audit_load_more)) }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }

    if (picking) ChoiceSheet(
        title = stringResource(R.string.audit_pick_user_title),
        choices = listOf(Choice(null, allUsers)) + users.map { Choice(it.id, it.username, it.email.takeIf(String::isNotBlank)) },
        selectedId = userId,
        onSelect = { userId = it.id; picking = false },
        onDismiss = { picking = false },
        searchPlaceholder = stringResource(R.string.audit_search_users),
        emptyText = stringResource(R.string.history_no_users),
    )
    selected?.let { entry ->
        AuditDetailSheet(
            entry = entry, names = names, onDismiss = { selected = null },
            onOpenLogs = onOpenLogs?.let { open -> { focus -> selected = null; open(focus) } },
        )
    }
}

/**
 * One audited request. The action leads, with its before and after lines under it; METHOD and path come second, then
 * the time, status (a server error is red), who did it and what it acted on. A tap opens the details when [onClick] is set.
 */
@Composable
internal fun AuditEntryRow(entry: AuditLogEntry, user: String?, onClick: (() -> Unit)? = null) {
    val words = rememberAuditWords()
    val headline = entry.action?.takeIf(String::isNotBlank) ?: stringResource(R.string.audit_http_request)
    val changes = auditChangeLines(entry, words)
    val target = auditTarget(entry)
    RowCard(Modifier.testTag("audit-row-${entry.id}"), onClick = onClick) {
        Text(headline, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        Text(
            stringResource(R.string.audit_method_path, entry.method, entry.path),
            style = MaterialTheme.typography.bodySmall, color = VeilColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.audit_status_line, formatDateTime(entry.timestamp) ?: entry.timestamp, entry.status_code),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = if (entry.status_code >= 500) VeilColors.Danger else VeilColors.Muted,
        )
        listOfNotNull(user, target).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
