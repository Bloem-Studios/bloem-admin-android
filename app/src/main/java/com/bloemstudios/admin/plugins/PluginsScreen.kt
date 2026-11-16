package com.bloemstudios.admin.plugins

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.bloemstudios.admin.automation.MenuEntry
import com.bloemstudios.admin.automation.RowActionMenu
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.ManagementState
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

/** Plugins: the installed plugins with a Needs attention filter, and Restart, Turn on or off and Update. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(client: ApiClient) {
    val viewModel = viewModel<PluginsViewModel>()
    val plugins by viewModel.plugins.collectAsState()
    val filter by viewModel.filter.collectAsState()
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
            val list = plugins
            ManagementState(!loadedOnce, loadedOnce && list.isNullOrEmpty(), error?.resolve(), stringResource(R.string.plugins_none)) { pull.startRefresh() }
            if (!list.isNullOrEmpty()) {
                error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
                PluginsContent(
                    plugins = list, filter = filter, onFilter = viewModel::setFilter, busy = busy, busyPrefix = "",
                    onRestart = { scope.launch { viewModel.restart(client, it.id) } },
                    onSetEnabled = { plugin, enabled -> scope.launch { viewModel.setEnabled(client, plugin.id, enabled) } },
                    onUpdate = { scope.launch { viewModel.update(client, it.id) } },
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = VeilSpacing.Medium),
                )
            }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** The filter and the plugin rows of one server; the single and All servers pages share it. */
@Composable
fun PluginsContent(
    plugins: List<PluginInstallation>,
    filter: PluginFilter,
    onFilter: (PluginFilter) -> Unit,
    busy: Set<String>,
    busyPrefix: String,
    onRestart: (PluginInstallation) -> Unit,
    onSetEnabled: (PluginInstallation, Boolean) -> Unit,
    onUpdate: (PluginInstallation) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<PendingPluginAction?>(null) }
    val counts = filterCounts(plugins)
    val shown = sortedForList(filtered(plugins, filter))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        val filterNames = listOf(
            stringResource(R.string.plugins_filter_all, counts.getValue(PluginFilter.All)),
            stringResource(R.string.plugins_filter_attention, counts.getValue(PluginFilter.NeedsAttention)),
            stringResource(R.string.plugins_filter_update, counts.getValue(PluginFilter.UpdateAvailable)),
            stringResource(R.string.plugins_filter_off, counts.getValue(PluginFilter.Off)),
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            SegmentedControl(filterNames, PluginFilter.entries.indexOf(filter), { onFilter(PluginFilter.entries[it]) }, optionModifier = { Modifier.testTag("plugin-filter-${PluginFilter.entries[it].name}") })
        }
        if (shown.isEmpty()) Text(stringResource(R.string.plugins_none_filtered), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        shown.forEach { plugin ->
            androidx.compose.runtime.key(plugin.id) {
                val working = busy.any { it == "${busyPrefix}restart:${plugin.id}" || it == "${busyPrefix}update:${plugin.id}" || it == "${busyPrefix}enabled:${plugin.id}" }
                PluginCard(plugin, working) { pending = PendingPluginAction(plugin, it) }
            }
        }
        Text(stringResource(R.string.plugins_web_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
    pending?.let { request ->
        val plugin = request.plugin
        val name = pluginName(plugin)
        val (title, message, confirm) = when (request.action) {
            PluginMenuAction.Restart -> Triple(stringResource(R.string.plugins_restart_title, name), stringResource(R.string.plugins_restart_message), stringResource(R.string.plugins_action_restart))
            PluginMenuAction.TurnOff -> Triple(stringResource(R.string.plugins_turn_off_title, name), stringResource(R.string.plugins_turn_off_message), stringResource(R.string.plugins_action_turn_off))
            PluginMenuAction.TurnOn -> Triple(stringResource(R.string.plugins_turn_on_title, name), stringResource(R.string.plugins_turn_on_message), stringResource(R.string.plugins_action_turn_on))
            PluginMenuAction.Update -> Triple(
                stringResource(R.string.plugins_update_title, name),
                stringResource(R.string.plugins_update_message, pendingUpdate(plugin).orEmpty(), plugin.version),
                stringResource(R.string.plugins_action_update, pendingUpdate(plugin).orEmpty()),
            )
        }
        ConfirmActionDialog(
            title = title, message = message, confirmLabel = confirm, destructive = request.action == PluginMenuAction.TurnOff,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                when (request.action) {
                    PluginMenuAction.Restart -> onRestart(plugin)
                    PluginMenuAction.TurnOff -> onSetEnabled(plugin, false)
                    PluginMenuAction.TurnOn -> onSetEnabled(plugin, true)
                    PluginMenuAction.Update -> onUpdate(plugin)
                }
            },
        )
    }
}

private enum class PluginMenuAction { Restart, TurnOff, TurnOn, Update }
private class PendingPluginAction(val plugin: PluginInstallation, val action: PluginMenuAction)

@Composable
private fun PluginCard(plugin: PluginInstallation, working: Boolean, onAction: (PluginMenuAction) -> Unit) {
    val state = pluginState(plugin)
    val update = pendingUpdate(plugin)
    val entries = buildList {
        if (canRestart(plugin)) add(MenuEntry(stringResource(R.string.plugins_action_restart), "plugin-${plugin.id}-restart") { onAction(PluginMenuAction.Restart) })
        if (update != null && plugin.enabled) add(MenuEntry(stringResource(R.string.plugins_action_update, update), "plugin-${plugin.id}-update") { onAction(PluginMenuAction.Update) })
        if (plugin.enabled) add(MenuEntry(stringResource(R.string.plugins_action_turn_off), "plugin-${plugin.id}-off") { onAction(PluginMenuAction.TurnOff) })
        else add(MenuEntry(stringResource(R.string.plugins_action_turn_on), "plugin-${plugin.id}-on") { onAction(PluginMenuAction.TurnOn) })
    }
    RowCard(Modifier.testTag("plugin-${plugin.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(pluginName(plugin), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                StatusLabel(
                    when (state) {
                        PluginState.Running -> stringResource(R.string.plugins_state_running)
                        PluginState.Starting -> stringResource(R.string.plugins_state_starting)
                        PluginState.Stopped -> stringResource(R.string.plugins_state_stopped)
                        PluginState.Restarting -> stringResource(R.string.plugins_state_restarting)
                        PluginState.Failed -> stringResource(R.string.plugins_state_failed)
                        PluginState.Off -> stringResource(R.string.plugins_state_off)
                    },
                    tone = when (state) {
                        PluginState.Failed -> StatusTone.Danger
                        PluginState.Restarting -> StatusTone.Warning
                        else -> StatusTone.Quiet
                    },
                    dot = true,
                )
                Text(stringResource(R.string.plugins_version, plugin.version), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            }
            RowActionMenu(stringResource(R.string.plugins_actions_for, pluginName(plugin)), entries, enabled = !working, tag = "plugin-${plugin.id}-menu")
        }
        if (plugin.enabled && state in setOf(PluginState.Failed, PluginState.Restarting)) {
            plugin.runtime.last_error?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.plugins_last_error, it), style = MaterialTheme.typography.bodySmall, color = if (state == PluginState.Failed) VeilColors.Danger else VeilColors.Warning)
            }
        }
        if (plugin.enabled && plugin.signature.state == "invalid") Text(stringResource(R.string.plugins_signature_invalid), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
        plugin.update_failure?.takeIf { plugin.enabled }?.let {
            Text(stringResource(R.string.plugins_update_failed, it.version, it.reason), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
        }
        if (update != null) Text(stringResource(R.string.plugins_update_available, update), style = MaterialTheme.typography.bodySmall, color = VeilColors.Text)
        else if (plugin.updates_paused && !plugin.available_version.isNullOrBlank()) Text(stringResource(R.string.plugins_updates_paused), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
}
