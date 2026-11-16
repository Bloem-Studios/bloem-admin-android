package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.ui.runStatusLabelRes
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.users.detail.formatDateTime
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import kotlinx.coroutines.launch

@Composable
internal fun JobHistoryDialog(client: ApiClient, onDismiss: () -> Unit) {
    val vm = remember(client) { JobHistoryViewModel() }
    val rows by vm.rows.collectAsState(); val error by vm.error.collectAsState(); val more by vm.hasMore.collectAsState()
    var loading by remember { mutableStateOf(true) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberForegroundReadLauncher()
    ShellInteractionLockEffect(true)
    ForegroundOnceEffect(client) {
        loading = true
        try { vm.load(client) } finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jobs_retained_history)) },
        text = {
            Column {
                Text(stringResource(R.string.jobs_history_notice), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                ManagementState(loading, rows.isEmpty(), error?.resolve(), stringResource(R.string.jobs_history_none)) { scope.launch { loading = true; try { vm.load(client) } finally { loading = false } } }
                if (rows.isNotEmpty()) error?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
                    items(rows, key = { it.id }) { row ->
                        Column {
                            Text(row.title, style = MaterialTheme.typography.titleSmall)
                            StatusLabel(codeLabel(row.state, ::runStatusLabelRes), tone = if (row.detail != null) StatusTone.Danger else StatusTone.Quiet, dot = true)
                            Text(formatDateTime(row.startedAt) ?: row.startedAt, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted)
                            row.detail?.let { Text(it, color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (more) item {
                        TextButton(enabled = !busy, onClick = { scope.launch { busy = true; try { vm.loadMore(client) } finally { busy = false } } }) {
                            Text(stringResource(R.string.activity_load_more))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { scope.launch { busy = true; try { vm.load(client) } finally { busy = false } } }) { Text(stringResource(R.string.management_refresh)) } }
    )
}
