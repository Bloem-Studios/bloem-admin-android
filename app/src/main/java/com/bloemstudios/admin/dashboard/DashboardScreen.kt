package com.bloemstudios.admin.dashboard

import com.bloemstudios.admin.realtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import com.bloemstudios.admin.ui.theme.tabular
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.streams.playbackProgressPercent
import com.bloemstudios.admin.streams.streamViewerLabel
import com.bloemstudios.admin.streams.streamMethodOf
import com.bloemstudios.admin.streams.methodLabel
import com.bloemstudios.admin.streams.markColor
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(client: ApiClient, onStreamsRequested: () -> Unit = {}, onUsersRequested: (() -> Unit)? = null, onNodesRequested: (() -> Unit)? = null) {
    val viewModel = viewModel<DashboardViewModel>()
    val stats by viewModel.stats.collectAsState()
    val hostStats by viewModel.hostStats.collectAsState()
    val hostUnavailable by viewModel.hostUnavailable.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val sessions by client.liveSessions.sessions.collectAsState()
    val sourceState by client.liveSessions.state.collectAsState()
    val sourceError by client.liveSessions.error.collectAsState()
    val hostError by viewModel.hostError.collectAsState()
    val insights by viewModel.insights.collectAsState()
    val chartRanges by viewModel.ranges.collectAsState()
    val pullState = rememberPullToRefreshState()
    var hasLoadedOnce by remember { mutableStateOf(false) }

    ForegroundEffect(client) { client.liveSessions.observe() }
    ForegroundEffect(client, viewModel) {
        viewModel.load(client)
        hasLoadedOnce = true
    }

    // A chart's new window is read as soon as it is chosen, without waiting for the next poll.
    ForegroundEffect(client, viewModel, chartRanges) { viewModel.loadCharts(client) }

    if (pullState.isRefreshing) {
        ForegroundEffect(client, viewModel, pullState) {
            if (!pullState.isRefreshing) return@ForegroundEffect
            try {
                kotlinx.coroutines.coroutineScope {
                    launch { client.liveSessions.refresh() }
                    viewModel.load(client, forceRefresh = true)
                }
            } finally { pullState.endRefresh() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection),
    ) {
        DashboardVeilContent(
            hasLoadedOnce = hasLoadedOnce || stats != null || error != null || sessions != null || sourceError != null || hostStats != null || hostUnavailable || insights.status.hasContent,
            stats = stats.takeUnless { sourceState == RealtimeState.SignInRequired },
            hostStats = hostStats.takeUnless { sourceState == RealtimeState.SignInRequired },
            errorMessage = error?.resolve(),
            onRetry = { pullState.startRefresh() },
            sessions = sessions,
            sessionsErrorMessage = sourceError?.let { stringResource(it.messageResource()) },
            sourceState = sourceState,
            sourceError = sourceError,
            hostErrorMessage = hostError?.resolve(),
            hostUnavailable = hostUnavailable,
            onStreamsRequested = onStreamsRequested,
            insights = insights,
            onUsersRequested = onUsersRequested,
            onNodesRequested = onNodesRequested,
            chartRanges = chartRanges,
            onChartRange = { viewModel.selectRange(it) },
        )
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
internal fun DashboardVeilContent(
    hasLoadedOnce: Boolean,
    stats: AdminStats?,
    hostStats: SystemResources?,
    errorMessage: String?,
    onRetry: () -> Unit,
    sessions: List<AdminSession>? = null,
    sessionsErrorMessage: String? = null,
    hostErrorMessage: String? = null,
    hostUnavailable: Boolean = false,
    onStreamsRequested: (() -> Unit)? = null,
    sourceState: RealtimeState = RealtimeState.Unavailable,
    sourceError: SessionFailure? = null,
    insights: DashboardInsights = DashboardInsights(),
    onUsersRequested: (() -> Unit)? = null,
    onNodesRequested: (() -> Unit)? = null,
    chartRanges: ChartRanges? = null,
    onChartRange: ((ChartRange) -> Unit)? = null,
) {
    when {
        !hasLoadedOnce -> Column(Modifier.padding(VeilSpacing.CompactHorizontal)) {
            SessionSourceStatus(sourceState, sourceError, false)
            VeilStateSurface(kind = VeilStateSurfaceKind.Loading, title = "", message = "",
                stateDescription = stringResource(R.string.dashboard_state_loading_description))
        }
        else -> DashboardMetrics(
            stats = stats,
            hostStats = hostStats,
            errorMessage = errorMessage,
            onRetry = onRetry,
            sessions = sessions,
            sessionsErrorMessage = sessionsErrorMessage,
            hostErrorMessage = hostErrorMessage,
            hostUnavailable = hostUnavailable,
            onStreamsRequested = onStreamsRequested,
            sourceState = sourceState, sourceError = sourceError,
            insights = insights, onUsersRequested = onUsersRequested, onNodesRequested = onNodesRequested,
            chartRanges = chartRanges, onChartRange = onChartRange,
        )
    }
}

@Composable
private fun DashboardMetrics(
    stats: AdminStats?, hostStats: SystemResources?, errorMessage: String?, onRetry: () -> Unit,
    sessions: List<AdminSession>?, sessionsErrorMessage: String?,
    hostErrorMessage: String?, hostUnavailable: Boolean, onStreamsRequested: (() -> Unit)?,
    sourceState: RealtimeState, sourceError: SessionFailure?,
    insights: DashboardInsights, onUsersRequested: (() -> Unit)?, onNodesRequested: (() -> Unit)?,
    chartRanges: ChartRanges?, onChartRange: ((ChartRange) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
    ) {
        if (errorMessage != null) item {
            VeilStateSurface(
                kind = VeilStateSurfaceKind.PartialFailure,
                title = stringResource(R.string.dashboard_state_partial_title), message = errorMessage,
                stateDescription = stringResource(R.string.dashboard_state_partial_description),
                action = VeilStateSurfaceAction(stringResource(R.string.action_retry), onRetry),
            )
        }
        // One item: the sections are a fixed, short list, and keeping them together keeps their order obvious.
        item {
            DashboardSections(DashboardSectionsData(
                stats = stats, hostStats = hostStats, hostErrorMessage = hostErrorMessage, hostUnavailable = hostUnavailable,
                sessions = sessions, sessionsErrorMessage = sessionsErrorMessage, sourceState = sourceState, sourceError = sourceError,
                insights = insights, onStreamsRequested = onStreamsRequested, onUsersRequested = onUsersRequested, onNodesRequested = onNodesRequested,
                chartRanges = chartRanges, onChartRange = onChartRange,
            ))
        }
    }
}

@Composable
internal fun StatusPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier, color = VeilColors.Card, shape = RoundedCornerShape(VeilShapes.CompactCardCorner),
        border = androidx.compose.foundation.BorderStroke(1.dp, VeilColors.Hairline),
    ) {
        Column(Modifier.fillMaxWidth().padding(VeilSpacing.Medium), content = content)
    }
}

@Composable
internal fun MetricRow(label: String, value: String) {
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) {
        Column(Modifier.fillMaxWidth().padding(top = VeilSpacing.Small)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            Text(value, style = MaterialTheme.typography.bodyMedium.tabular())
        }
    } else Row(Modifier.fillMaxWidth().padding(top = VeilSpacing.Small), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular())
    }
}

@Composable
internal fun SessionPreview(session: AdminSession, onStreamsRequested: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().then(if (onStreamsRequested != null)
        Modifier.clickable(role = Role.Button, onClick = onStreamsRequested) else Modifier)
        .heightIn(min = VeilSpacing.MinimumTouchTarget)) {
        Text(session.media_title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(streamViewerLabel(session), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // The play method the way the rows and charts name it, with its chart color as a key.
            val method = streamMethodOf(session)
            StatusLabel(methodLabel(method), dot = true, dotColor = method.markColor())
            if (session.is_paused) StatusLabel(stringResource(R.string.status_paused), tone = StatusTone.Warning, dot = true)
            else StatusLabel(stringResource(R.string.status_playing), tone = StatusTone.Live)
        }
        playbackProgressPercent(session)?.let { percent ->
            LinearProgressIndicator(progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = VeilSpacing.Tiny),
                color = if (session.is_paused) VeilColors.Warning else VeilColors.Text, trackColor = VeilColors.Raised)
        }
    }
}

@Composable
internal fun formatRelativeTime(timestamp: String): String {
    val instant = remember(timestamp) { runCatching { Instant.parse(timestamp) }.getOrNull() } ?: return timestamp
    var now by remember { mutableStateOf(Instant.now()) }
    ForegroundEffect(timestamp) {
        while (isActive) { now = Instant.now(); delay(30_000) }
    }
    val duration = Duration.between(instant, now)
    val minutes = duration.toMinutes()
    val hours = duration.toHours()
    return when {
        minutes < 1 -> stringResource(R.string.dashboard_relative_just_now)
        minutes < 60 -> stringResource(R.string.dashboard_relative_minutes_ago, minutes.toInt())
        hours < 24 -> stringResource(R.string.dashboard_relative_hours_ago, hours.toInt())
        else -> stringResource(R.string.dashboard_relative_days_ago, duration.toDays().toInt())
    }
}
