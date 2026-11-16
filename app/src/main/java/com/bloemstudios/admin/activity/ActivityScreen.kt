package com.bloemstudios.admin.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.Choice
import com.bloemstudios.admin.ui.components.ChoiceSheet
import com.bloemstudios.admin.ui.components.PickerField
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.UserChoice
import com.bloemstudios.admin.users.UserDirectoryViewModel
import com.bloemstudios.admin.users.detail.UserProfile
import kotlinx.coroutines.launch

/**
 * Playback history: every finished play, newest first, with when it ended, how much was watched, the profile, the play
 * method and the episode code. Filter by user (picked by name), profile and completed or partial; a tap opens the details.
 * [onOpenLogs] opens Logs for a play's playback session; leave it null where Logs is not offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    client: ApiClient,
    onOpenLogs: ((playbackSessionId: String, ffmpegOnly: Boolean) -> Unit)? = null,
) {
    val viewModel = viewModel<ActivityViewModel>()
    val directory = viewModel<UserDirectoryViewModel>()
    val entries by viewModel.entries.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val users by directory.users.collectAsState()
    val usersError by directory.usersError.collectAsState()
    val profiles by directory.profiles.collectAsState()
    val profilesError by directory.profilesError.collectAsState()
    val scope = rememberForegroundReadLauncher()
    val pullState = rememberPullToRefreshState()
    var userId by rememberSaveable { mutableStateOf<String?>(null) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var completion by rememberSaveable { mutableStateOf(CompletionFilter.All) }
    var hasLoadedOnce by remember(client) { mutableStateOf(false) }
    val filter = HistoryFilter(userId, profileId, completion)

    ForegroundEffect(client) { directory.loadUsers(client) }
    ForegroundEffect(client, userId) { directory.loadProfiles(client, userId) }

    var initialized by remember(client, filter) { mutableStateOf(false) }
    ForegroundEffect(client, filter) {
        if (initialized) viewModel.pollRefresh(client, filter) else {
            viewModel.load(client, filter)
            initialized = true
        }
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
        ActivityVeilContent(
            hasLoadedOnce = hasLoadedOnce,
            entries = entries,
            errorMessage = error?.resolve(),
            hasMore = hasMore,
            filters = HistoryFilterUi(
                filter = filter,
                users = users,
                usersError = usersError?.resolve(),
                profiles = profiles,
                profilesError = profilesError?.resolve(),
                onUserChange = { userId = it; profileId = null },
                onProfileChange = { profileId = it },
                onCompletionChange = { completion = it },
            ),
            onRetry = { pullState.startRefresh() },
            onLoadMore = { scope.launch { viewModel.loadMore(client, filter) } },
            onOpenLogs = onOpenLogs,
        )
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** Everything the filter bar draws and can change. Profiles belong to the chosen user, so none is offered before one is. */
internal data class HistoryFilterUi(
    val filter: HistoryFilter,
    val users: List<UserChoice>,
    val usersError: String?,
    val profiles: List<UserProfile>,
    val profilesError: String?,
    val onUserChange: (String?) -> Unit,
    val onProfileChange: (String?) -> Unit,
    val onCompletionChange: (CompletionFilter) -> Unit,
)

@Composable
internal fun ActivityVeilContent(
    hasLoadedOnce: Boolean,
    entries: List<PlaybackHistoryEntry>,
    errorMessage: String?,
    hasMore: Boolean,
    filters: HistoryFilterUi,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenLogs: ((playbackSessionId: String, ffmpegOnly: Boolean) -> Unit)? = null,
) {
    var selected by remember { mutableStateOf<PlaybackHistoryEntry?>(null) }
    val filtered = !filters.filter.isEmpty
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        item(key = "filters") { HistoryFilterBar(filters) }
        when {
            !hasLoadedOnce -> item(key = "state") {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Loading, title = "", message = "",
                    stateDescription = stringResource(R.string.activity_state_loading_description),
                )
            }
            entries.isEmpty() && errorMessage != null -> item(key = "state") {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Failure,
                    title = stringResource(R.string.activity_state_failure_title),
                    message = errorMessage,
                    stateDescription = stringResource(R.string.activity_state_failure_description),
                    action = VeilStateSurfaceAction(stringResource(R.string.action_retry), onRetry),
                )
            }
            entries.isEmpty() -> item(key = "state") {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Empty,
                    title = stringResource(if (filtered) R.string.history_empty_filtered_title else R.string.activity_state_empty_title),
                    message = stringResource(if (filtered) R.string.history_empty_filtered_message else R.string.activity_state_empty_message),
                    stateDescription = stringResource(if (filtered) R.string.history_empty_filtered_description else R.string.activity_state_empty_description),
                )
            }
            else -> {
                if (errorMessage != null) item(key = "partial") {
                    VeilStateSurface(
                        kind = VeilStateSurfaceKind.PartialFailure,
                        title = stringResource(R.string.activity_state_partial_title),
                        message = errorMessage,
                        stateDescription = stringResource(R.string.activity_state_partial_description),
                        action = VeilStateSurfaceAction(stringResource(R.string.action_retry), onRetry),
                    )
                }
                items(entries, key = { it.session_id }) { entry -> HistoryRow(entry, onClick = { selected = entry }) }
                if (hasMore) item(key = "more") {
                    TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.activity_load_more))
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        HistoryDetailSheet(
            entry = entry,
            onDismiss = { selected = null },
            onOpenLogs = onOpenLogs?.let { open -> { id, ffmpeg -> selected = null; open(id, ffmpeg) } },
        )
    }
}

/** User by name, then one of that user's profiles, then All, Completed or Partial. */
@Composable
private fun HistoryFilterBar(ui: HistoryFilterUi) {
    var picking by remember { mutableStateOf<Picker?>(null) }
    val allUsers = stringResource(R.string.history_all_users)
    val allProfiles = stringResource(R.string.history_all_profiles)
    val userName = ui.users.firstOrNull { it.id == ui.filter.userId }?.username
        ?: ui.filter.userId?.let { stringResource(R.string.history_user_fallback, it) }
        ?: allUsers
    val profileName = when {
        ui.filter.userId == null -> stringResource(R.string.history_choose_user_first)
        else -> ui.profiles.firstOrNull { it.id == ui.filter.profileId }?.name ?: ui.filter.profileId ?: allProfiles
    }
    Column(Modifier.fillMaxWidth().padding(top = VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        PickerField(
            label = stringResource(R.string.history_filter_user), value = userName, onClick = { picking = Picker.User },
            supportingText = ui.usersError?.let { stringResource(R.string.history_users_unavailable, it) },
            modifier = Modifier.testTag("history-filter-user"),
        )
        PickerField(
            label = stringResource(R.string.history_filter_profile), value = profileName, onClick = { picking = Picker.Profile },
            enabled = ui.filter.userId != null,
            supportingText = ui.profilesError?.takeIf { ui.filter.userId != null }?.let { stringResource(R.string.history_profiles_unavailable, it) },
            modifier = Modifier.testTag("history-filter-profile"),
        )
        val statuses = CompletionFilter.entries
        SegmentedControl(
            options = listOf(R.string.history_status_all, R.string.history_status_completed, R.string.history_status_partial).map { stringResource(it) },
            selectedIndex = statuses.indexOf(ui.filter.completion),
            onSelect = { ui.onCompletionChange(statuses[it]) },
            modifier = Modifier.testTag("history-filter-status"),
            optionModifier = { Modifier.testTag("history-filter-status-$it") },
        )
    }
    when (picking) {
        Picker.User -> ChoiceSheet(
            title = stringResource(R.string.history_pick_user_title),
            choices = listOf(Choice(null, allUsers)) + ui.users.map { Choice(it.id, it.username, it.email.takeIf(String::isNotBlank)) },
            selectedId = ui.filter.userId,
            onSelect = { ui.onUserChange(it.id); picking = null },
            onDismiss = { picking = null },
            searchPlaceholder = stringResource(R.string.history_search_users),
            emptyText = stringResource(R.string.history_no_users),
        )
        Picker.Profile -> ChoiceSheet(
            title = stringResource(R.string.history_pick_profile_title),
            choices = listOf(Choice(null, allProfiles)) + ui.profiles.map { Choice(it.id, it.name) },
            selectedId = ui.filter.profileId,
            onSelect = { ui.onProfileChange(it.id); picking = null },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

private enum class Picker { User, Profile }
