package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.realtime.LiveRefreshEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(client: ApiClient, onOpenBackups: (() -> Unit)? = null) {
    val viewModel = viewModel<JobsViewModel>()
    val tasks by viewModel.tasks.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    var showRetainedHistory by remember { mutableStateOf(false) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val busyTasks by viewModel.busyTasks.collectAsState()
    val scope = rememberCoroutineScope()
    var scheduling by remember { mutableStateOf<AdminTask?>(null) }

    val pullState = rememberPullToRefreshState()
    // See ServerConfigScreen's identical flag for why this exists: only
    // gates the initial blank-screen spinner, not every poll tick.
    var hasLoadedOnce by remember { mutableStateOf(false) }

    // The server says when a task starts, moves or ends (tasks and jobs channels); there is no timer.
    LiveRefreshEffect(client, TASK_CHANNELS) {
        viewModel.load(client)
        hasLoadedOnce = true
    }

    if (pullState.isRefreshing) {
        ForegroundEffect(pullState) {
            if (pullState.isRefreshing) {
                try { viewModel.load(client) }
                finally { pullState.endRefresh() }
            }
        }
    }

    val open = openKey
    if (open != null) {
        TaskDetailScreen(client, open, onClose = { openKey = null; scope.launch { viewModel.load(client) } }, onOpenBackups = onOpenBackups)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection)
    ) {

    Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { showRetainedHistory = true }) { Text(stringResource(R.string.jobs_retained_history)) }
        }
        ManagementState(!hasLoadedOnce, tasks.isEmpty(), error?.resolve(), stringResource(R.string.jobs_none)) { pullState.startRefresh() }
        if (tasks.isNotEmpty()) error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            items(tasks, key = { it.key }) { task ->
                TaskRow(task, busy = task.key in busyTasks,
                    onRun = { scope.launch { viewModel.run(task, client) } },
                    onCancel = { scope.launch { viewModel.cancel(task, client) } },
                    onSchedule = { scheduling = task },
                    onHistory = { openKey = task.key },
                    onOpen = { openKey = task.key })
            }
        }
    }

        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    if (showRetainedHistory) JobHistoryDialog(client, onDismiss = { showRetainedHistory = false })

    scheduling?.let { task -> GuardedScheduleDialog(client, task) { scheduling = null; scope.launch { viewModel.load(client) } } }
}
