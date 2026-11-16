package com.bloemstudios.admin.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.Choice
import com.bloemstudios.admin.ui.components.ChoiceSheet
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.PickerField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A request to open Logs narrowed to one playback session (from a Now playing stream, a Playback history play or an
 * audit entry) or to one request. [ffmpegOnly] also narrows to the FFmpeg component, as the web's "FFmpeg logs" link does.
 */
data class LogsFocus(val playbackSessionId: String = "", val ffmpegOnly: Boolean = false, val requestId: String = "")

/**
 * The application logs, newest first, with level chips, message search, and the narrowing filters the web offers:
 * component, request ID and playback session. A playback session shows its summary on top. A tap opens the line's
 * details. Cursor-paginated (see LogsViewModel); a pull, a filter change or the first load starts again from page one.
 * [focus] applies a [LogsFocus] once, then [onFocusConsumed] clears it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(client: ApiClient, focus: LogsFocus? = null, onFocusConsumed: () -> Unit = {}, onOpenLogSettings: (() -> Unit)? = null) {
    val viewModel = viewModel<LogsViewModel>()
    val entries by viewModel.entries.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val sessionAudit by viewModel.sessionAudit.collectAsState()
    val scope = rememberForegroundReadLauncher()
    val pullState = rememberPullToRefreshState()
    var hasLoadedOnce by remember(client) { mutableStateOf(false) }

    var level by rememberSaveable { mutableStateOf(LogLevelChoice.All) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var component by rememberSaveable { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf("") }
    var playbackSessionId by rememberSaveable { mutableStateOf("") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OperationalLogEntry?>(null) }

    LaunchedEffect(focus) {
        if (focus != null) {
            level = LogLevelChoice.All
            searchText = ""
            requestId = focus.requestId
            playbackSessionId = focus.playbackSessionId
            component = if (focus.ffmpegOnly) FFMPEG_COMPONENT else ""
            // A playback session shows its summary, so the fields stay folded away to leave room for the lines.
            filtersOpen = focus.requestId.isNotEmpty()
            onFocusConsumed()
        }
    }

    // Re-keying on the raw text would fire one request per keystroke.
    val typed = LogFilter(level.wire, searchText, component, requestId, playbackSessionId).normalized()
    var filter by remember { mutableStateOf(typed) }
    LaunchedEffect(typed) {
        if (typed != filter) delay(FILTER_DEBOUNCE_MILLIS)
        filter = typed
    }

    var initialized by remember(client, filter) { mutableStateOf(false) }
    ForegroundEffect(client, filter) {
        if (initialized) viewModel.pollRefresh(client, filter) else {
            viewModel.load(client, filter)
            initialized = true
        }
        viewModel.loadSessionAudit(client, filter.playbackSessionId)
        hasLoadedOnce = true
    }

    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try { viewModel.load(client, filter) }
                finally { pullState.endRefresh() }
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            if (onOpenLogSettings != null) item(key = "settings-link") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenLogSettings, modifier = Modifier.testTag("logs-open-settings")) { Text(stringResource(R.string.logs_open_settings)) }
                }
            }
            item(key = "filters") {
                LogFilters(
                    level = level, onLevel = { level = it },
                    searchText = searchText, onSearch = { searchText = it },
                    component = component, onComponent = { component = it },
                    requestId = requestId, onRequestId = { requestId = it },
                    playbackSessionId = playbackSessionId, onPlaybackSessionId = { playbackSessionId = it },
                    open = filtersOpen, onToggle = { filtersOpen = !filtersOpen },
                    activeCount = typed.extraFilterCount,
                )
            }
            filter.playbackSessionId?.let { session ->
                item(key = "summary") {
                    PlaybackSessionSummaryCard(
                        playbackSessionId = session,
                        summary = summarizePlaybackSession(session, entries, sessionAudit.rows),
                        appHasMore = hasMore,
                        auditHasMore = sessionAudit.hasMore,
                        auditAvailable = sessionAudit.available,
                        ffmpegOnly = filter.component == FFMPEG_COMPONENT,
                        onToggleFfmpeg = { component = if (component == FFMPEG_COMPONENT) "" else FFMPEG_COMPONENT },
                        onClear = { playbackSessionId = ""; component = "" },
                    )
                }
            }
            if (!hasLoadedOnce || entries.isEmpty()) item(key = "state") {
                ManagementState(
                    !hasLoadedOnce, entries.isEmpty(), error?.resolve(),
                    stringResource(if (filter.extraFilterCount > 0 || filter.q != null || filter.level != null) R.string.logs_filtered_none else R.string.logs_none),
                ) { pullState.startRefresh() }
            }
            if (entries.isNotEmpty()) error?.let { message -> item(key = "error") { MessageBanner(message.resolve(), isError = true, onDismiss = {}) } }
            items(entries, key = { it.id }) { entry -> LogRow(entry, onClick = { selected = entry }) }
            if (hasMore) item(key = "more") {
                TextButton(
                    onClick = { scope.launch { viewModel.loadMore(client, filter) } },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(R.string.logs_load_more)) }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }

    selected?.let { entry ->
        LogDetailSheet(
            entry = entry,
            onDismiss = { selected = null },
            onFilterPlaybackSession = { id -> playbackSessionId = id; selected = null },
            onFilterRequest = { id -> requestId = id; filtersOpen = true; selected = null },
        )
    }
}

private const val FILTER_DEBOUNCE_MILLIS = 300L

@Composable
private fun LogFilters(
    level: LogLevelChoice, onLevel: (LogLevelChoice) -> Unit,
    searchText: String, onSearch: (String) -> Unit,
    component: String, onComponent: (String) -> Unit,
    requestId: String, onRequestId: (String) -> Unit,
    playbackSessionId: String, onPlaybackSessionId: (String) -> Unit,
    open: Boolean, onToggle: () -> Unit, activeCount: Int,
) {
    var pickingComponent by remember { mutableStateOf(false) }
    val allComponents = stringResource(R.string.logs_all_components)
    Column(Modifier.fillMaxWidth().padding(top = VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        // One control for a choice of one, the same as Plugins and Playback history: a radius-10 segmented bar, not pills.
        val levels = LogLevelChoice.entries
        val levelLabels = levels.map { stringResource(it.labelRes()) }
        SegmentedControl(
            options = levelLabels,
            selectedIndex = levels.indexOf(level),
            onSelect = { onLevel(levels[it]) },
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            optionModifier = { Modifier.testTag("logs-level-${levels[it].name}") },
        )
        SearchField(searchText, onSearch, stringResource(R.string.logs_filter_search))
        TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("logs-filters-toggle")) {
            Text(if (activeCount > 0) stringResource(R.string.logs_more_filters_active, activeCount) else stringResource(R.string.logs_more_filters))
        }
        if (open) {
            PickerField(
                label = stringResource(R.string.logs_filter_component),
                value = component.ifBlank { allComponents },
                onClick = { pickingComponent = true },
                modifier = Modifier.testTag("logs-filter-component"),
            )
            OutlinedTextField(
                value = requestId, onValueChange = onRequestId, singleLine = true,
                label = { Text(stringResource(R.string.logs_filter_request_id)) },
                textStyle = MaterialTheme.typography.bodyMedium.tabular(),
                modifier = Modifier.fillMaxWidth().testTag("logs-filter-request-id"),
            )
            OutlinedTextField(
                value = playbackSessionId, onValueChange = onPlaybackSessionId, singleLine = true,
                label = { Text(stringResource(R.string.logs_filter_playback_session)) },
                textStyle = MaterialTheme.typography.bodyMedium.tabular(),
                modifier = Modifier.fillMaxWidth().testTag("logs-filter-playback-session"),
            )
        }
    }
    if (pickingComponent) ChoiceSheet(
        title = stringResource(R.string.logs_pick_component_title),
        choices = listOf(Choice(null, allComponents)) + componentChoices(component).map { Choice(it, it) },
        selectedId = component.ifBlank { null },
        onSelect = { onComponent(it.id ?: ""); pickingComponent = false },
        onDismiss = { pickingComponent = false },
        searchPlaceholder = stringResource(R.string.logs_search_components),
    )
}

private fun LogLevelChoice.labelRes(): Int = when (this) {
    LogLevelChoice.All -> R.string.logs_level_all
    LogLevelChoice.Debug -> R.string.logs_level_debug
    LogLevelChoice.Info -> R.string.logs_level_info
    LogLevelChoice.Warn -> R.string.logs_level_warn
    LogLevelChoice.Error -> R.string.logs_level_error
    LogLevelChoice.WarnError -> R.string.logs_level_warn_error
}

/**
 * A log entry: its level in a fixed 56dp column (so every message starts at one x), then the message and where it came
 * from: time, component and, for a request, its status and duration. The whole card opens the details.
 */
@Composable
private fun LogRow(entry: OperationalLogEntry, onClick: () -> Unit) {
    val duration = entry.durationMs()?.let { stringResource(R.string.logs_duration_ms, it) }
    val meta = listOfNotNull(formatDateTime(entry.timestamp) ?: entry.timestamp, entry.component, entry.attrText("status"), duration).joinToString(" · ")
    RowCard(onClick = onClick, modifier = Modifier.testTag("log-row-${entry.id}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            LevelText(entry.level, Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted)
            }
        }
    }
}

/** The level as a sentence-case word; only Error (danger) and Warn (warning) take a color. */
@Composable
private fun LevelText(level: String, modifier: Modifier = Modifier) {
    val color = when (level.lowercase()) {
        "error" -> VeilColors.Danger
        "warn", "warning" -> VeilColors.Warning
        else -> VeilColors.Muted
    }
    Text(levelLabel(level), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier, maxLines = 1)
}
