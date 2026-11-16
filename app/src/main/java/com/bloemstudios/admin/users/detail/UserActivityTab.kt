package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.listPlaybackHistory
import com.bloemstudios.admin.network.api.listUserIps
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import kotlinx.coroutines.launch

private const val IP_DAYS = 30

/** Activity: this user's playback history (the Activity screen's endpoint, filtered by user id) and where they signed in from. */
@Composable
internal fun UserActivityTab(user: AdminUser, client: ApiClient, serverName: String?) {
    val history = remember(client, user.id) { PagedFeed(serverName) { cursor -> client.listPlaybackHistory(user.id, cursor) } }
    val ips = remember(client, user.id) { SectionLoader(serverName) { client.listUserIps(user.id, IP_DAYS) } }
    val reads = rememberForegroundReadLauncher()
    ForegroundOnceEffect(history, ips) {
        launch { history.reload() }
        launch { ips.load() }
    }
    val items by history.items.collectAsState()
    val loaded by history.loaded.collectAsState()
    val loading by history.loading.collectAsState()
    val error by history.error.collectAsState()
    val hasMore by history.hasMore.collectAsState()
    val ipState by ips.state.collectAsState()
    val completed = stringResource(R.string.activity_completed)
    val partial = stringResource(R.string.activity_partial)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Large)) {
        DetailCard(stringResource(R.string.user_activity_history)) {
            if (!loaded && error == null) Text(stringResource(R.string.management_loading), color = VeilColors.Muted)
            error?.let {
                Text(it.resolve(), color = MaterialTheme.colorScheme.error)
                TextButton(
                    enabled = !loading,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = { reads.launch { if (loaded) history.loadMore() else history.reload() } }
                ) { Text(stringResource(R.string.action_retry)) }
            }
            if (loaded && items.isEmpty() && error == null) Text(stringResource(R.string.user_activity_history_none), color = VeilColors.Muted)
            items.forEach { entry ->
                Column {
                    Text(entry.media_title)
                    Text(
                        stringResource(
                            R.string.user_activity_history_row,
                            formatDateTime(entry.started_at) ?: entry.started_at,
                            if (entry.completed) completed else partial
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = VeilColors.Muted
                    )
                }
            }
            if (hasMore) TextButton(
                enabled = !loading,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                onClick = { reads.launch { history.loadMore() } }
            ) { Text(stringResource(R.string.user_load_more)) }
        }
        DetailCard(stringResource(R.string.user_activity_ips)) {
            SectionBody(ipState, onRetry = { reads.launch { ips.load() } }) { list ->
                if (list.isEmpty()) Text(stringResource(R.string.user_activity_ips_none), color = VeilColors.Muted)
                list.forEach { ip ->
                    Column {
                        Text(listOf(ip.client_ip, ip.location).filter { it.isNotBlank() }.joinToString(" · "))
                        Text(
                            stringResource(R.string.user_activity_ip_row, ip.request_count, formatDateTime(ip.last_seen) ?: ip.last_seen),
                            style = MaterialTheme.typography.bodySmall,
                            color = VeilColors.Muted
                        )
                    }
                }
            }
        }
    }
}
