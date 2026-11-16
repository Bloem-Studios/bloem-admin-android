package com.bloemstudios.admin.apikeys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.launch

const val API_KEYS_LIST_TAG = "api-keys-list"
const val API_KEY_ROW_TAG_PREFIX = "api-key-row:"
const val API_KEY_REVOKE_TAG_PREFIX = "api-key-revoke:"
const val API_KEY_REVOKE_CONFIRM_TAG = "api-key-revoke-confirm"
const val API_KEYS_LOAD_MORE_TAG = "api-keys-load-more"
const val API_KEYS_UNAVAILABLE_TAG = "api-keys-unavailable"

/** API keys: who owns each key, what it may reach and when it was last used, with Revoke. Keys are created on the web admin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(client: ApiClient) {
    val model = remember(client) { ApiKeysViewModel() }
    val availability by model.availability.collectAsState()
    val state by model.state.collectAsState()
    val accounts by model.accounts.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val notice by model.notice.collectAsState()
    val reads = rememberForegroundReadLauncher()
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    var asking by remember { mutableStateOf<AdminApiKey?>(null) }

    ForegroundOnceEffect(model) {
        model.load(client)
        model.loadAccounts(client)
    }
    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try { if (availability == ApiKeysAvailability.Available) model.reload(client) else model.load(client) } finally { pullState.endRefresh() }
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(API_KEYS_LIST_TAG),
            contentPadding = PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            when (val now = availability) {
                ApiKeysAvailability.Checking -> item(key = "checking") {
                    VeilStateSurface(VeilStateSurfaceKind.Loading, "", "", stringResource(R.string.management_loading))
                }
                ApiKeysAvailability.Unavailable -> item(key = "unavailable") {
                    RowCard(Modifier.testTag(API_KEYS_UNAVAILABLE_TAG)) {
                        Text(stringResource(R.string.api_keys_unavailable_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                        Text(stringResource(R.string.api_keys_unavailable_detail), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    }
                }
                is ApiKeysAvailability.Failed -> item(key = "failed") {
                    VeilStateSurface(
                        kind = VeilStateSurfaceKind.Failure,
                        title = stringResource(R.string.api_keys_load_failed), message = now.message.resolve(),
                        stateDescription = stringResource(R.string.api_keys_load_failed),
                        action = VeilStateSurfaceAction(stringResource(R.string.action_retry)) { reads.launch { model.load(client) } },
                    )
                }
                ApiKeysAvailability.Available -> {
                    item(key = "hint") {
                        Text(stringResource(R.string.api_keys_create_on_web), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    }
                    notice?.let { shown ->
                        item(key = "notice") {
                            Text(
                                when (shown) {
                                    is RevokeNotice.Revoked -> stringResource(R.string.api_keys_notice_revoked, shown.label)
                                    is RevokeNotice.AlreadyGone -> stringResource(R.string.api_keys_notice_gone, shown.label)
                                },
                                color = VeilColors.Ok,
                            )
                        }
                    }
                    error?.let { shown ->
                        item(key = "error") {
                            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                                Text(shown.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = model::dismissMessages, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                                    Text(stringResource(R.string.action_dismiss))
                                }
                            }
                        }
                    }
                    when {
                        !state.loaded -> item(key = "loading") {
                            VeilStateSurface(VeilStateSurfaceKind.Loading, "", "", stringResource(R.string.management_loading))
                        }
                        state.keys.isEmpty() && state.listError != null -> item(key = "list-failed") {
                            VeilStateSurface(
                                kind = VeilStateSurfaceKind.Failure,
                                title = stringResource(R.string.api_keys_load_failed), message = state.listError!!.resolve(),
                                stateDescription = stringResource(R.string.api_keys_load_failed),
                                action = VeilStateSurfaceAction(stringResource(R.string.action_retry)) { reads.launch { model.reload(client) } },
                            )
                        }
                        state.keys.isEmpty() -> item(key = "empty") {
                            Text(stringResource(R.string.api_keys_none), color = VeilColors.Muted)
                        }
                        else -> {
                            items(state.keys, key = { it.id }) { key ->
                                ApiKeyRow(
                                    key = key,
                                    canRevoke = accounts?.let { canRevokeKey(key, it.accounts, it.viewerId, it.viewerIsOwner) } == true,
                                    ownerKnown = accounts != null,
                                    busy = key.id in busy,
                                    onRevoke = { model.dismissMessages(); asking = key },
                                )
                            }
                            if (state.moreFailed) item(key = "more-failed") {
                                Text(stringResource(R.string.api_keys_more_failed), style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger)
                            }
                            if (state.hasMore) item(key = "more") {
                                TextButton(
                                    onClick = { reads.launch { model.loadMore(client) } }, enabled = !state.loadingMore,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(API_KEYS_LOAD_MORE_TAG),
                                ) { Text(stringResource(if (state.loadingMore) R.string.management_loading else R.string.api_keys_load_more)) }
                            }
                        }
                    }
                }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }

    asking?.let { key ->
        ShellInteractionLockEffect(true)
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.api_keys_revoke_title, key.label)) },
            text = { Text(stringResource(R.string.api_keys_revoke_body)) },
            confirmButton = {
                DestructiveTextButton(
                    onClick = { asking = null; scope.launch { model.revoke(client, key) } },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(API_KEY_REVOKE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.api_keys_revoke_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ApiKeyRow(key: AdminApiKey, canRevoke: Boolean, ownerKnown: Boolean, busy: Boolean, onRevoke: () -> Unit) {
    RowCard(Modifier.testTag(API_KEY_ROW_TAG_PREFIX + key.id)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(key.label, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                Text(key.key_prefix, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                val owner = key.username.ifBlank { key.user_id }
                Text(stringResource(R.string.api_keys_owner, owner), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                Text(
                    stringResource(R.string.api_keys_access, if (key.hasFullAccess) stringResource(R.string.api_keys_full_access) else key.scopes.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
                Text(
                    formatDateTime(key.last_used_at)?.let { stringResource(R.string.api_keys_last_used, it) } ?: stringResource(R.string.api_keys_never_used),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
                if (key.rate_tier == "elevated") StatusLabel(stringResource(R.string.api_keys_rate_elevated), tone = StatusTone.Quiet)
            }
            if (canRevoke) {
                val description = stringResource(R.string.api_keys_revoke_for, key.label)
                DestructiveTextButton(
                    onClick = onRevoke, enabled = !busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(API_KEY_REVOKE_TAG_PREFIX + key.id)
                        .semantics { contentDescription = description },
                ) { Text(stringResource(R.string.api_keys_revoke)) }
            }
        }
        if (!canRevoke && ownerKnown) Text(stringResource(R.string.api_keys_revoke_owner_only), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
}
