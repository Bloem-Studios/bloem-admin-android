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
import com.bloemstudios.admin.network.api.getUserDownloadSummary
import com.bloemstudios.admin.network.api.listUserDownloads
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import kotlinx.coroutines.launch

/** Downloads: what this user has on their devices, read-only. Shown only when the server offers account downloads. */
@Composable
internal fun UserDownloadsTab(user: AdminUser, client: ApiClient, serverName: String?) {
    val summary = remember(client, user.id) { SectionLoader(serverName) { client.getUserDownloadSummary(user.id) } }
    val feed = remember(client, user.id) { PagedFeed(serverName) { cursor -> client.listUserDownloads(user.id, cursor) } }
    val reads = rememberForegroundReadLauncher()
    ForegroundOnceEffect(summary, feed) {
        launch { summary.load() }
        launch { feed.reload() }
    }
    val summaryState by summary.state.collectAsState()
    val items by feed.items.collectAsState()
    val loaded by feed.loaded.collectAsState()
    val loading by feed.loading.collectAsState()
    val error by feed.error.collectAsState()
    val hasMore by feed.hasMore.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Large)) {
        DetailCard(stringResource(R.string.user_downloads_title)) {
            SectionBody(summaryState, onRetry = { reads.launch { summary.load() } }) { s ->
                Text(stringResource(R.string.user_downloads_summary, s.total, s.devices, s.completed, s.in_progress, s.failed))
                if (s.total_bytes > 0) Text(formatBytes(s.total_bytes), color = VeilColors.Muted)
            }
            if (!loaded && error == null) Text(stringResource(R.string.management_loading), color = VeilColors.Muted)
            error?.let {
                Text(it.resolve(), color = MaterialTheme.colorScheme.error)
                TextButton(
                    enabled = !loading,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = { reads.launch { if (loaded) feed.loadMore() else feed.reload() } }
                ) { Text(stringResource(R.string.action_retry)) }
            }
            if (loaded && items.isEmpty() && error == null) Text(stringResource(R.string.user_downloads_none), color = VeilColors.Muted)
            items.forEach { download ->
                Column {
                    Text(download.title)
                    Text(
                        stringResource(R.string.user_downloads_row, download.status, formatBytes(download.file_size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = VeilColors.Muted
                    )
                }
            }
            if (hasMore) TextButton(
                enabled = !loading,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                onClick = { reads.launch { feed.loadMore() } }
            ) { Text(stringResource(R.string.user_load_more)) }
        }
    }
}
