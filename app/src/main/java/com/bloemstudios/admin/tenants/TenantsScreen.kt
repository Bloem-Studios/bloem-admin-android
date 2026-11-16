package com.bloemstudios.admin.tenants

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
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
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import kotlinx.coroutines.launch

// Read-only tenant visibility -- no freeze/thaw/create/delete/limits-editing
// actions anywhere in this screen. That's a deliberate product decision:
// tenant lifecycle is an automated billing-system contract with a separate
// system, not something a human admin should be able to hand-edit from this
// app. See AdminGateScreen for the tab-visibility probe that hides this
// screen's entry point entirely on servers without the feature (404) while
// still surfacing a normal error/retry state for any other failure.
@Composable
fun TenantsScreen(client: ApiClient) {
    val viewModel = viewModel<TenantsViewModel>()
    val probeState by viewModel.probeState.collectAsState()
    val scope = rememberForegroundReadLauncher()
    var selectedTenant by remember { mutableStateOf<Tenant?>(null) }

    ForegroundEffect(client) {
        viewModel.load(client)
    }

    AggregateRefreshSurface(emptyList(), { viewModel.load(client) }) {
    when (val state = probeState) {
        is TenantsProbeState.Checking -> ManagementState(true, true, null) {}
        is TenantsProbeState.Idle -> Column(Modifier.padding(VeilSpacing.CompactHorizontal)) {
            TextButton(onClick = { scope.launch { viewModel.load(client) } }) { Text(stringResource(R.string.tenants_retry)) }
        }
        // NotSupported is handled by the caller (AdminGateScreen hides the
        // tab entirely) -- rendering nothing here is just a safety net in
        // case this screen is ever reached directly with an unsupported
        // server.
        is TenantsProbeState.NotSupported -> Unit
        is TenantsProbeState.Error -> VeilStateSurface(
            kind = VeilStateSurfaceKind.Failure,
            title = stringResource(R.string.tenants_failed_title), message = state.message.resolve(),
            stateDescription = state.message.resolve(),
            modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
            action = VeilStateSurfaceAction(stringResource(R.string.tenants_retry)) { scope.launch { viewModel.load(client) } },
        )
        is TenantsProbeState.Loaded -> TenantsList(
            tenants = state.tenants,
            onTenantSelected = { tenant ->
                selectedTenant = tenant
                scope.launch { viewModel.loadMembers(tenant.tenant_id, client) }
            }
        )
    }

    }

    selectedTenant?.let { tenant ->
        TenantMembersDialog(
            tenant = tenant,
            client = client,
            viewModel = viewModel,
            onDismiss = {
                selectedTenant = null
                viewModel.clearMembers()
            }
        )
    }
}

@Composable
private fun TenantsList(tenants: List<Tenant>, onTenantSelected: (Tenant) -> Unit) {
    Column(Modifier.padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (tenants.isEmpty()) {
            VeilStateSurface(
                kind = VeilStateSurfaceKind.Empty, title = stringResource(R.string.tenants_none), message = "",
                stateDescription = stringResource(R.string.tenants_none),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(tenants) { tenant ->
                val slots = tenant.limits?.slots
                val slotsUsed = tenant.usage?.slots_used
                val slotsLabel = if (slots != null && slotsUsed != null) {
                    stringResource(R.string.tenants_slots_usage, slotsUsed, slots)
                } else {
                    null
                }
                val transcodesLabel = tenant.limits?.transcodes?.let { stringResource(R.string.tenants_transcode_limit, it) }
                RowCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                        Column(Modifier.weight(1f)) {
                            Text(tenant.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                            slotsLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted) }
                            transcodesLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted) }
                            if (tenant.frozen) {
                                val reason = tenant.frozen_reason
                                StatusLabel(
                                    if (reason.isNullOrBlank()) {
                                        stringResource(R.string.tenants_frozen)
                                    } else {
                                        stringResource(R.string.tenants_frozen_with_reason, reason)
                                    },
                                    tone = StatusTone.Warning, dot = true,
                                )
                            }
                        }
                        TextButton(onClick = { onTenantSelected(tenant) }) {
                            Text(stringResource(R.string.tenants_view_members))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TenantMembersDialog(tenant: Tenant, client: ApiClient, viewModel: TenantsViewModel, onDismiss: () -> Unit) {
    val membersState by viewModel.membersState.collectAsState()
    val scope = rememberForegroundReadLauncher()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tenants_members_dialog_title, tenant.name)) },
        text = {
            when (val state = membersState) {
                is TenantMembersState.Loading, null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = VeilColors.Text, trackColor = VeilColors.Raised)
                is TenantMembersState.Idle -> TextButton(onClick = { scope.launch { viewModel.loadMembers(tenant.tenant_id, client) } }) {
                    Text(stringResource(R.string.tenants_retry))
                }
                is TenantMembersState.Error -> Column {
                    Text(state.message.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { viewModel.loadMembers(tenant.tenant_id, client) } }) {
                        Text(stringResource(R.string.tenants_retry))
                    }
                }
                is TenantMembersState.Loaded -> if (state.members.isEmpty()) {
                    Text(stringResource(R.string.tenants_no_members))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                        items(state.members) { member ->
                            Column {
                                Text(member.username, style = MaterialTheme.typography.titleSmall)
                                Text("${member.email} · ${member.status}", style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}
