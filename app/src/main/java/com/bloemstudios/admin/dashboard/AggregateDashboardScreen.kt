package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize
import androidx.compose.ui.text.style.TextAlign
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.*
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TallyDot
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch

@Composable
fun AggregateDashboardScreen(
    servers: List<AdminServer>, store: ServerStore, expiredServerIds: Set<String>,
    onSignIn: (AdminServer) -> Unit, onAddServer: (() -> Unit)?, onStreamsRequested: () -> Unit,
    onUsersRequested: (() -> Unit)? = null,
) {
    val model = viewModel<AggregateDashboardViewModel>()
    val samples by model.samples.collectAsState()
    val clientFor: (AdminServer) -> ApiClient = remember(store) { { store.client(it) } }
    val latestExpired by rememberUpdatedState(expiredServerIds)
    ForegroundEffect(servers, model) {
        model.reconcile(servers)
        servers.forEach { server ->
            val client = runCatching { clientFor(server) }.getOrNull()
            if (client == null) {
                model.samples.value[server.id]?.reportUnavailable()
                return@forEach
            }
            launch { client.liveSessions.observe() }
            launch {
                val sample = model.samples.value.getValue(server.id)
                if (server.id !in latestExpired && client.liveSessions.state.value != RealtimeState.SignInRequired) sample.load(client)
            }
        }
    }
    AggregateRefreshSurface(emptyList(), onRefresh = {
        kotlinx.coroutines.coroutineScope {
            servers.forEach { server ->
                val client = runCatching { clientFor(server) }.getOrNull() ?: return@forEach
                launch { client.liveSessions.refresh() }
                if (server.id !in expiredServerIds) launch { model.samples.value[server.id]?.load(client, true) }
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(VeilSpacing.CompactHorizontal),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            if (servers.isEmpty()) item { EmptyActivitySetup(onAddServer) }
            items(servers, key = { it.id }) { server ->
                val client = remember(server, store) { runCatching { clientFor(server) }.getOrNull() }
                // With several servers each starts as one row; a tap opens that server's full dashboard under it.
                var expanded by rememberSaveable(server.id) { mutableStateOf(servers.size == 1) }
                if (client == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                        ServerRollupRow(server.displayName, expanded, { expanded = !expanded }, sessions = null, egressKbps = null,
                            errors = null, problem = stringResource(R.string.dashboard_rollup_unreachable))
                        if (expanded) AggregateStatusSection(server, null, RealtimeState.Unavailable, SessionFailure.Connection,
                            null, stringResource(R.string.status_health_unavailable), null,
                            stringResource(R.string.status_sample_not_available), null, false, { onSignIn(server) }, onStreamsRequested,
                            showName = false)
                    }
                    return@items
                }
                val sessions by client.liveSessions.sessions.collectAsState()
                val state by client.liveSessions.state.collectAsState()
                val error by client.liveSessions.error.collectAsState()
                val sample = samples[server.id]
                val host = sample?.hostStats?.collectAsState()?.value
                val hostError = sample?.hostError?.collectAsState()?.value
                val hostUnavailable = sample?.hostUnavailable?.collectAsState()?.value ?: false
                val stats = sample?.stats?.collectAsState()?.value
                val statsError = sample?.errorMessage?.collectAsState()?.value
                val sampledAt = sample?.sampledAt?.collectAsState()?.value
                val insights = sample?.insights?.collectAsState()?.value ?: DashboardInsights()
                val ranges = sample?.ranges?.collectAsState()?.value
                val signedOut = server.id in expiredServerIds || state == RealtimeState.SignInRequired
                // A chart's new window is read as soon as it is chosen; a server that needs sign-in is not asked.
                if (sample != null && !signedOut) ForegroundEffect(client, sample, ranges) { sample.loadCharts(client) }
                Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    ServerRollupRow(
                        server.displayName, expanded, { expanded = !expanded },
                        sessions = sessions?.size, egressKbps = insights.egressNow.value?.freshEgressKbps(java.time.Instant.now()),
                        errors = insights.status.value?.health?.errors_24h,
                        problem = if (signedOut) stringResource(R.string.dashboard_rollup_signed_out) else null,
                    )
                    if (expanded) AggregateStatusSection(server, sessions, state, error, host, hostError?.resolve(), stats,
                        statsError?.resolve(), sampledAt?.toString(), server.id in expiredServerIds,
                        { onSignIn(server) }, onStreamsRequested, hostUnavailable, insights, onUsersRequested,
                        chartRanges = ranges, onChartRange = sample?.let { model -> { range: ChartRange -> model.selectRange(range) } },
                        showName = false)
                }
            }
        }
    }
}

/**
 * The empty state of the app: what goes here, and the one action that fills it. Centred, with nothing else on the
 * page; the screen that shows it draws no shell around it.
 */
@Composable
internal fun EmptyActivitySetup(onAddServer: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(VeilSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
    ) {
        Text(
            stringResource(R.string.activity_scope_welcome_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.activity_scope_welcome_message),
            style = MaterialTheme.typography.bodyMedium,
            color = VeilColors.Muted,
            textAlign = TextAlign.Center,
        )
        onAddServer?.let { add ->
            Button(onClick = add, modifier = Modifier.padding(top = VeilSpacing.Small).heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                LucideIcon(R.drawable.lucide_plus, contentDescription = null, size = LucideSize.Inline)
                Spacer(Modifier.width(VeilSpacing.Small))
                Text(stringResource(R.string.activity_scope_add_first))
            }
        }
    }
}

/**
 * A flat section inside the aggregate list, never a nested full-screen destination. Below the server's name it
 * follows the single-server dashboard's order: number tiles, server health, now playing, then the trends, lists and feeds.
 */
@Composable
internal fun AggregateStatusSection(
    server: AdminServer, sessions: List<AdminSession>?, state: RealtimeState, error: SessionFailure?,
    host: SystemResources?, hostError: String?, stats: AdminStats?, statsError: String?, sampledAt: String?,
    expired: Boolean, onSignIn: () -> Unit, onStreamsRequested: () -> Unit,
    hostUnavailable: Boolean = false,
    insights: DashboardInsights = DashboardInsights(),
    onUsersRequested: (() -> Unit)? = null,
    chartRanges: ChartRanges? = null,
    onChartRange: ((ChartRange) -> Unit)? = null,
    showName: Boolean = true,
) {
    val signInRequired = expired || state == RealtimeState.SignInRequired
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (showName) Text(server.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (signInRequired) {
            SessionSourceStatus(RealtimeState.SignInRequired, SessionFailure.SignInRequired, false)
            TextButton(onClick = onSignIn, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.relogin_sign_in))
            }
        } else {
            statsError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            DashboardSections(DashboardSectionsData(
                stats = stats, hostStats = host, hostErrorMessage = hostError, hostUnavailable = hostUnavailable,
                sessions = sessions, sessionsErrorMessage = null, sourceState = state, sourceError = error,
                insights = insights, onStreamsRequested = onStreamsRequested, onUsersRequested = onUsersRequested,
                sampledAt = sampledAt, chartRanges = chartRanges, onChartRange = onChartRange,
            ))
        }
    }
}

/**
 * One server in the roll-up: its name, then streams now, egress now and errors in the last day on one line, so the
 * servers can be compared at a glance. Tapping opens or closes the server's own dashboard below it.
 */
@Composable
private fun ServerRollupRow(
    name: String, expanded: Boolean, onToggle: () -> Unit,
    sessions: Int?, egressKbps: Double?, errors: Int?, problem: String? = null,
) {
    val shape = RoundedCornerShape(VeilShapes.CompactCardCorner)
    val dash = "—"
    val numbers = NumberFormat.getIntegerInstance()
    val streams = sessions?.let { plural(R.plurals.dashboard_rollup_streams, it, numbers.format(it)) } ?: dash
    val egress = egressKbps?.let { formatMbps(it / 1000.0) } ?: dash
    val errorText = errors?.let { plural(R.plurals.dashboard_rollup_errors, it, numbers.format(it)) } ?: dash
    val state = stringResource(if (expanded) R.string.streams_row_state_expanded else R.string.streams_row_state_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(VeilColors.Card, shape)
            .border(BorderStroke(1.dp, VeilColors.Hairline), shape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(horizontal = VeilSpacing.Medium, vertical = VeilSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            if (problem != null) {
                StatusLabel(problem, tone = StatusTone.Warning, dot = true)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    if ((sessions ?: 0) > 0) TallyDot()
                    Text(
                        listOf(streams, egress, errorText).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = if ((errors ?: 0) > 0) VeilColors.Danger else VeilColors.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LucideIcon(
            if (expanded) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down,
            contentDescription = null, tint = VeilColors.Muted,
        )
    }
}
