package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.realtime.LiveRefreshEffect
import com.bloemstudios.admin.ui.roleLabelRes
import com.bloemstudios.admin.ui.codeLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.getUserWatchSummary
import com.bloemstudios.admin.network.api.listUserLiveSessions
import com.bloemstudios.admin.network.api.listUserProfiles
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import kotlinx.coroutines.launch

private const val STATS_DAYS = 30

/** Who this is, what they can reach, their profiles and a month of watching, as the web's Overview. */
@Composable
internal fun UserOverviewTab(
    user: AdminUser,
    client: ApiClient,
    serverName: String?,
    capabilities: AdminUserCapabilities?
) {
    val profiles = remember(client, user.id) { SectionLoader(serverName) { client.listUserProfiles(user.id) } }
    val stats = remember(client, user.id) { SectionLoader(serverName) { client.getUserWatchSummary(user.id, STATS_DAYS) } }
    val showStats = capabilities != null && capabilities.watch_summary
    val watching = remember(client, user.id) { SectionLoader(serverName) { client.listUserLiveSessions(user.id) } }
    ForegroundOnceEffect(profiles, stats, showStats) {
        launch { profiles.load() }
        if (showStats) launch { stats.load() }
    }
    // What the account is playing changes while the page is open: the server's `sessions` channel says when.
    LiveRefreshEffect(client, setOf(com.bloemstudios.admin.realtime.LiveChannels.SESSIONS), watching) { watching.load() }
    val reads = rememberForegroundReadLauncher()
    val profileState by profiles.state.collectAsState()
    val statsState by stats.state.collectAsState()
    val watchingState by watching.state.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Large)) {
        when (val now = watchingState) {
            is Section.Loaded -> UserWatchingNowCard(now.value)
            is Section.Failed -> DetailCard(stringResource(R.string.user_watching_now)) {
                Text(now.message.resolve(), color = VeilColors.Danger)
            }
            Section.Loading -> Unit
        }
        DetailCard(stringResource(R.string.user_overview_details)) {
            FieldLine(stringResource(R.string.user_overview_email), user.email)
            FieldLine(stringResource(R.string.user_overview_role), codeLabel(user.roleLabel(), ::roleLabelRes))
        }
        DetailCard(stringResource(R.string.user_overview_access)) {
            AccessSummaryLines(user)
        }
        DetailCard(stringResource(R.string.user_overview_profiles)) {
            SectionBody(profileState, onRetry = { reads.launch { profiles.load() } }) { list ->
                if (list.isEmpty()) Text(stringResource(R.string.user_overview_no_profiles), color = VeilColors.Muted)
                list.forEach { profile ->
                    val seen = formatDate(profile.last_seen_at)
                    Text(
                        if (seen == null) profile.name
                        else stringResource(R.string.user_overview_profile_seen, profile.name, seen)
                    )
                }
            }
        }
        if (showStats) DetailCard(stringResource(R.string.user_overview_stats, STATS_DAYS)) {
            SectionBody(statsState, onRetry = { reads.launch { stats.load() } }) { summary ->
                if (summary.plays == 0) {
                    Text(stringResource(R.string.user_overview_never_played), color = VeilColors.Muted)
                } else {
                    Text(stringResource(R.string.user_overview_plays, summary.plays, summary.completed_plays))
                    Text(stringResource(R.string.user_overview_watch_time, formatWatchTime(summary.watched_seconds)))
                    formatDate(summary.last_played_at)?.let { Text(stringResource(R.string.user_overview_last_played, it)) }
                }
            }
        }
    }
}

/** The account's effective limits, as the server resolved them. */
@Composable
internal fun AccessSummaryLines(user: AdminUser) {
    val policy = user.effective_policy
    if (policy == null) {
        Text(stringResource(R.string.user_unknown), color = VeilColors.Muted)
        return
    }
    val libraries = policy.library_ids
    Text(
        if (libraries == null) stringResource(R.string.user_policy_libraries_all)
        else stringResource(R.string.user_policy_libraries_some, libraries.size)
    )
    Text(stringResource(R.string.user_policy_streams, policy.max_streams))
    Text(stringResource(R.string.user_policy_transcodes, policy.max_transcodes))
    Text(stringResource(R.string.user_policy_profiles, policy.max_profiles))
    Text(stringResource(if (policy.download_allowed) R.string.user_policy_downloads_on else R.string.user_policy_downloads_off))
    Text(stringResource(if (policy.requests_allowed) R.string.user_policy_requests_on else R.string.user_policy_requests_off))
}
