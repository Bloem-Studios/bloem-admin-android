package com.bloemstudios.admin.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.automation.ConfirmActionDialog
import com.bloemstudios.admin.automation.LabelValueRow
import com.bloemstudios.admin.automation.MenuEntry
import com.bloemstudios.admin.automation.RowActionMenu
import com.bloemstudios.admin.jobs.Elapsed
import com.bloemstudios.admin.jobs.rememberClock
import com.bloemstudios.admin.jobs.textBehind
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch
import java.time.Instant

/** Nodes: every stream node's state, load and capacity, with Drain, Resume and Turn on or off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(client: ApiClient) {
    val viewModel = viewModel<NodesViewModel>()
    val nodes by viewModel.nodes.collectAsState()
    val drainSupported by viewModel.drainSupported.collectAsState()
    val error by viewModel.error.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val pull = rememberPullToRefreshState()
    var loadedOnce by remember { mutableStateOf(false) }

    ForegroundEffect(client) {
        viewModel.load(client)
        loadedOnce = true
    }
    if (pull.isRefreshing) ForegroundEffect(pull) {
        if (pull.isRefreshing) try { viewModel.load(client) } finally { pull.endRefresh() }
    }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        Column(Modifier.fillMaxSize().padding(horizontal = VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            actionError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = viewModel::dismissActionError) }
            val list = nodes
            ManagementState(!loadedOnce, loadedOnce && list.isNullOrEmpty(), error?.resolve(), stringResource(R.string.nodes_none)) { pull.startRefresh() }
            if (!list.isNullOrEmpty()) {
                error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
                NodeList(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = VeilSpacing.Medium),
                    nodes = list, drainSupported = drainSupported, busy = busy, busyPrefix = "",
                    onDrain = { scope.launch { viewModel.drain(client, it.id) } },
                    onResume = { scope.launch { viewModel.resume(client, it.id) } },
                    onSetEnabled = { node, enabled -> scope.launch { viewModel.setEnabled(client, node.id, enabled) } },
                )
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * The node rows of one server. [busy] holds the keys of writes still running (`drain:7`, prefixed with [busyPrefix] in
 * All servers); a row whose write is running offers no second one.
 */
@Composable
fun NodeList(
    nodes: List<NodeSummary>,
    drainSupported: Boolean,
    busy: Set<String>,
    busyPrefix: String,
    onDrain: (NodeSummary) -> Unit,
    onResume: (NodeSummary) -> Unit,
    onSetEnabled: (NodeSummary, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = rememberClock(),
) {
    var pending by remember { mutableStateOf<PendingNodeAction?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        nodes.forEach { node ->
            androidx.compose.runtime.key(node.id) {
                val working = busy.any { it == "${busyPrefix}drain:${node.id}" || it == "${busyPrefix}resume:${node.id}" || it == "${busyPrefix}enabled:${node.id}" }
                NodeCard(node, drainSupported, working, now) { pending = PendingNodeAction(node, it) }
            }
        }
    }
    pending?.let { request ->
        val name = request.node.name
        val (title, message, confirm) = when (request.action) {
            NodeMenuAction.Drain -> Triple(R.string.nodes_drain_title, R.string.nodes_drain_message, R.string.nodes_action_drain)
            NodeMenuAction.Resume -> Triple(R.string.nodes_resume_title, R.string.nodes_resume_message, R.string.nodes_action_resume)
            NodeMenuAction.Disable -> Triple(R.string.nodes_disable_title, R.string.nodes_disable_message, R.string.nodes_action_disable)
            NodeMenuAction.Enable -> Triple(R.string.nodes_enable_title, R.string.nodes_enable_message, R.string.nodes_action_enable)
        }
        ConfirmActionDialog(
            title = stringResource(title, name), message = stringResource(message), confirmLabel = stringResource(confirm),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                when (request.action) {
                    NodeMenuAction.Drain -> onDrain(request.node)
                    NodeMenuAction.Resume -> onResume(request.node)
                    NodeMenuAction.Disable -> onSetEnabled(request.node, false)
                    NodeMenuAction.Enable -> onSetEnabled(request.node, true)
                }
            },
        )
    }
}

private enum class NodeMenuAction { Drain, Resume, Disable, Enable }
private class PendingNodeAction(val node: NodeSummary, val action: NodeMenuAction)

@Composable
private fun NodeCard(node: NodeSummary, drainSupported: Boolean, working: Boolean, now: Instant, onAction: (NodeMenuAction) -> Unit) {
    val state = nodeState(node)
    val entries = buildList {
        when (drainAction(node, drainSupported)) {
            DrainAction.Drain -> add(MenuEntry(stringResource(R.string.nodes_action_drain), "node-${node.id}-drain") { onAction(NodeMenuAction.Drain) })
            DrainAction.Resume -> add(MenuEntry(stringResource(R.string.nodes_action_resume), "node-${node.id}-resume") { onAction(NodeMenuAction.Resume) })
            null -> Unit
        }
        if (node.enabled) add(MenuEntry(stringResource(R.string.nodes_action_disable), "node-${node.id}-disable") { onAction(NodeMenuAction.Disable) })
        else add(MenuEntry(stringResource(R.string.nodes_action_enable), "node-${node.id}-enable") { onAction(NodeMenuAction.Enable) })
    }
    RowCard(Modifier.testTag("node-${node.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(node.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                StatusLabel(
                    when (state) {
                        NodeState.Healthy -> stringResource(R.string.nodes_state_healthy)
                        NodeState.Draining -> stringResource(R.string.nodes_state_draining)
                        NodeState.Drained -> stringResource(R.string.nodes_state_drained)
                        NodeState.Unhealthy -> stringResource(R.string.nodes_state_unhealthy)
                        NodeState.Disabled -> stringResource(R.string.nodes_state_disabled)
                    },
                    tone = when (state) {
                        NodeState.Unhealthy -> StatusTone.Danger
                        NodeState.Draining -> StatusTone.Warning
                        else -> StatusTone.Quiet
                    },
                    dot = true,
                )
                Text(
                    listOfNotNull(
                        stringResource(if (node.type == "proxy") R.string.nodes_type_proxy else R.string.nodes_type_transcode),
                        node.group?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
            }
            RowActionMenu(stringResource(R.string.nodes_actions_for, node.name), entries, enabled = !working, tag = "node-${node.id}-menu")
        }
        when (state) {
            NodeState.Drained -> Hint(stringResource(R.string.nodes_drained_hint))
            NodeState.Draining -> Hint(androidx.compose.ui.res.pluralStringResource(R.plurals.nodes_draining_hint, node.active_jobs.coerceAtLeast(0), node.active_jobs.coerceAtLeast(0)))
            NodeState.Unhealthy -> Hint(stringResource(R.string.nodes_unhealthy_hint), danger = true)
            else -> Unit
        }
        NodeLoadBlock(node, now)
        NodeCapacityBlock(node, now)
    }
}

@Composable
private fun Hint(text: String, danger: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (danger) VeilColors.Danger else VeilColors.Muted)
}

@Composable
private fun NodeLoadBlock(node: NodeSummary, now: Instant) {
    Text(stringResource(R.string.nodes_load_title), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted, modifier = Modifier.padding(top = VeilSpacing.Tiny))
    val load = nodeLoad(node, now)
    if (load == null) {
        Text(stringResource(R.string.nodes_no_reading), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        return
    }
    LabelValueRow(stringResource(R.string.nodes_cpu), stringResource(R.string.nodes_percent, load.cpuPercent), valueColor = warnAbove(load.cpuPercent))
    LabelValueRow(
        stringResource(R.string.nodes_memory),
        stringResource(R.string.nodes_memory_value, load.memoryPercent, megabytesText(load.memoryUsedTotalMb.first), megabytesText(load.memoryUsedTotalMb.second)),
        valueColor = warnAbove(load.memoryPercent),
    )
    load.diskPercent?.let { LabelValueRow(stringResource(R.string.nodes_disk), stringResource(R.string.nodes_percent, it), valueColor = warnAbove(it)) }
    LabelValueRow(
        stringResource(R.string.nodes_network),
        stringResource(R.string.nodes_network_value, megabitsPerSecond(load.rxBytesPerSecond), megabitsPerSecond(load.txBytesPerSecond)),
    )
}

/** A reading near its limit is set in the warning color; everything else stays plain. */
@Composable
private fun warnAbove(percent: Int) = if (percent >= WARN_PERCENT) VeilColors.Warning else VeilColors.Text

private const val WARN_PERCENT = 90

@Composable
private fun megabytesText(mb: Long): String = if (mb >= 1_024) stringResource(R.string.nodes_gb, "%.1f".format(mb / 1_024.0)) else "$mb MB"

@Composable
private fun NodeCapacityBlock(node: NodeSummary, now: Instant) {
    Text(stringResource(R.string.nodes_capacity_title), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted, modifier = Modifier.padding(top = VeilSpacing.Tiny))
    val capacity = nodeCapacity(node)
    LabelValueRow(
        stringResource(if (node.type == "proxy") R.string.nodes_capacity_streams else R.string.nodes_capacity_transcodes),
        if (capacity.cap != null) stringResource(R.string.nodes_capacity_value, capacity.active, capacity.cap) else stringResource(R.string.nodes_capacity_value_unlimited, capacity.active),
        valueColor = if (capacity.atCap) VeilColors.Warning else VeilColors.Text,
    )
    if (node.type == "proxy") {
        val cap = node.max_bandwidth_kbps?.takeIf { it > 0 }
        val used = megabitsPerSecond(node.egress_kbps.toLong() * 125)
        LabelValueRow(
            stringResource(R.string.nodes_capacity_egress),
            if (cap != null) stringResource(R.string.nodes_capacity_egress_value, used, megabitsPerSecond(cap.toLong() * 125)) else stringResource(R.string.nodes_capacity_egress_value_unlimited, used),
        )
    }
    val age: Elapsed? = lastCheckAge(node, now)
    LabelValueRow(stringResource(R.string.nodes_last_check), age?.textBehind() ?: stringResource(R.string.nodes_never_checked))
}
