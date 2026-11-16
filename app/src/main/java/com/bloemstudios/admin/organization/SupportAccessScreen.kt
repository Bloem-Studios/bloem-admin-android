package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

/**
 * The server Owner's way into a customer's organization when its administrator cannot help themselves: pick an organization,
 * state a reason, open it. [account] is the signed-in account's client (never closed here); [onOpen] navigates to the
 * organization with the grant already recorded for [serverId].
 */
@Composable
fun SupportAccessScreen(account: ApiClient, serverId: String, onOpen: (organizationId: String) -> Unit) {
    val viewModel = viewModel<SupportOrganizationsViewModel>()
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    ForegroundOnceEffect(account) { viewModel.load(account) }
    SupportAccessContent(
        state = state,
        onSearch = { query -> scope.launch { viewModel.load(account, query) } },
        onRetry = { scope.launch { viewModel.load(account) } },
        onRefresh = { viewModel.load(account) },
        onMore = { scope.launch { viewModel.loadMore(account) } },
        onDismiss = viewModel::dismissError,
        onOpen = { organization, reason ->
            SupportAccessGrants.start(serverId, organization.id, reason)
            onOpen(organization.id)
        },
    )
}

@Composable
internal fun SupportAccessContent(
    state: SupportOrganizationsState,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (OrganizationSummary, reason: String) -> Unit,
    onRefresh: (suspend () -> Unit)? = null,
) {
    var query by remember { mutableStateOf(state.query) }
    var opening by remember { mutableStateOf<OrganizationSummary?>(null) }
    OrganizationPage(
        title = stringResource(R.string.organization_support_open_title),
        subtitle = stringResource(R.string.organization_support_open_body),
        keepSubtitle = true,
        loading = state.loading, empty = state.organizations.isEmpty(),
        emptyMessage = stringResource(R.string.organization_support_none),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
        header = {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.organization_support_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth().testTag("organization-support-search"),
            )
        },
    ) {
        items(state.organizations, key = { it.id }) { organization ->
            val member = organization.id in state.memberOrganizationIds
            val openable = organization.status == "active" && !member
            OrganizationCard(Modifier.testTag("organization-support-org-${organization.id}")) {
                Text(organization.name, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                Text(
                    stringResource(R.string.organization_support_members, organization.active_membership_count.toInt()),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
                if (openable) ActionButton(
                    stringResource(R.string.organization_support_open_action),
                    stringResource(R.string.organization_support_open_action_for, organization.name),
                    { opening = organization },
                ) else Text(
                    if (member) stringResource(R.string.organization_support_member_note)
                    else stringResource(R.string.organization_support_unavailable_status, statusLabel(organization.status)),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning,
                )
            }
        }
        if (state.nextCursor != null) item {
            TextButton(
                onClick = onMore, enabled = !state.loadingMore,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-more"),
            ) { Text(stringResource(if (state.loadingMore) R.string.organization_loading else R.string.organization_support_more)) }
        }
    }
    opening?.let { organization ->
        ReasonDialog(organization.name, onDismiss = { opening = null }) { reason -> opening = null; onOpen(organization, reason) }
    }
}

/** Asks for the reason the server requires (10 to 500 characters). Nothing is sent until it is acceptable. */
@Composable
internal fun ReasonDialog(organizationName: String, onDismiss: () -> Unit, onConfirm: (reason: String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val problem = reasonProblem(reason)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organization_support_reason_title, organizationName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.organization_support_reason_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it.replace('\n', ' '); touched = true }, minLines = 3,
                    label = { Text(stringResource(R.string.organization_support_reason_label)) },
                    isError = touched && problem != null,
                    supportingText = {
                        Text(
                            when {
                                touched && problem == ReasonProblem.Short -> stringResource(R.string.organization_support_reason_short)
                                problem == ReasonProblem.Long -> stringResource(R.string.organization_support_reason_long)
                                problem == ReasonProblem.Control -> stringResource(R.string.organization_support_reason_control)
                                else -> stringResource(R.string.organization_support_reason_count, reason.trim().length)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("organization-support-reason"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { SupportReason.valid(reason)?.let(onConfirm) }, enabled = problem == null,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-reason-open"),
            ) { Text(stringResource(R.string.organization_support_reason_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.organization_cancel)) }
        },
    )
}
