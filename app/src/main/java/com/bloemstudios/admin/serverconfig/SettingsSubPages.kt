package com.bloemstudios.admin.serverconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.NetworkAccessStatus
import com.bloemstudios.admin.network.api.NetworkHostStatus
import com.bloemstudios.admin.network.api.NetworkProvider
import com.bloemstudios.admin.network.api.connectNetworkAccess
import com.bloemstudios.admin.network.api.disconnectNetworkAccess
import com.bloemstudios.admin.network.api.getNetworkAccessCapabilities
import com.bloemstudios.admin.network.api.getNetworkAccessStatus
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val ListPadding = PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small)

/** Requests settings: the guarded request-settings route, edited with the same save bar as the stored pages. */
@Composable
fun RequestSettingsContent(state: RequestSettingsState, onEdit: ((RequestSettingsDraft) -> RequestSettingsDraft) -> Unit,
                           onSave: () -> Unit, onDiscard: () -> Unit, onDismissError: () -> Unit, savedNotice: Boolean = false) {
    val draft = state.draft
    val unitRequests = stringResource(R.string.requests_limit_unit)
    val unitDays = stringResource(R.string.requests_window_unit)
    fun toggle(label: Int, help: Int) = SettingDef("requests", label, SettingKind.Toggle, help)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("settings-page:requests"), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    state.saveError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = onDismissError) }
                    state.loadError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
                    if (state.conflict) {
                        Text(stringResource(R.string.settings_changed_on_server), style = MaterialTheme.typography.titleSmall, color = VeilColors.Warning, modifier = Modifier.testTag("settings-conflict"))
                        Text(stringResource(R.string.settings_page_conflict_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    }
                    if (savedNotice) MessageBanner(stringResource(R.string.settings_page_saved), isError = false, onDismiss = {})
                }
            }
            if (state.loaded != null) {
                item { SettingControl(toggle(R.string.requests_allow_label, R.string.requests_allow_help), SettingControlState(draft.enabled.toString()), { v -> onEdit { it.copy(enabled = v == "true") } }) }
                item {
                    SettingControl(
                        SettingDef("requests.approval", R.string.requests_approval_label,
                            SettingKind.Choice(listOf(ChoiceOption("admin", R.string.requests_approval_admin), ChoiceOption("auto", R.string.requests_approval_auto))), R.string.requests_approval_help),
                        SettingControlState(if (draft.autoApprove) "auto" else "admin"), { v -> onEdit { it.copy(autoApprove = v == "auto") } },
                    )
                }
                item {
                    SettingControl(
                        SettingDef("requests.limit", R.string.requests_limit_label, SettingKind.Number(unit = unitRequests, min = 0), R.string.requests_limit_help),
                        SettingControlState(draft.maxRequests), { v -> onEdit { it.copy(maxRequests = v) } },
                    )
                }
                item {
                    SettingControl(
                        SettingDef("requests.window", R.string.requests_window_label, SettingKind.Number(unit = unitDays, min = 1)),
                        SettingControlState(draft.windowDays), { v -> onEdit { it.copy(windowDays = v) } },
                    )
                }
                draft.watchlist?.let { watchlist ->
                    item { SettingControl(toggle(R.string.requests_watchlist_label, R.string.requests_watchlist_help), SettingControlState(watchlist.toString()), { v -> onEdit { it.copy(watchlist = v == "true") } }) }
                }
                item { SettingControl(toggle(R.string.requests_4k_label, R.string.requests_4k_help), SettingControlState(draft.force4k.toString()), { v -> onEdit { it.copy(force4k = v == "true") } }) }
            }
        }
        SettingsSaveBar(state.changeCount, state.saving, canSave = state.canSave, onSave = onSave, onDiscard = onDiscard)
    }
}

/** Every setting the server reports that no named page covers, by its stored name, edited with the single-key editor. */
@Composable
fun OtherSettingsContent(snapshot: SettingsSnapshot, onEdit: (String) -> Unit, editEnabled: Boolean) {
    var query by remember { mutableStateOf("") }
    val keys = remember(snapshot.values.keys, query) {
        otherSettingKeys(snapshot.values.keys).filter { query.isBlank() || com.bloemstudios.admin.ui.shell.matchesTokens(
            com.bloemstudios.admin.ui.shell.normalizeSearchText(it), com.bloemstudios.admin.ui.shell.searchTokens(query)) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("settings-page:other"), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        item { SearchField(query, { query = it }, stringResource(R.string.settings_home_search)) }
        if (keys.isEmpty()) item {
            Text(stringResource(if (query.isBlank()) R.string.settings_other_empty else R.string.settings_home_no_results), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        }
        items(keys, key = { it }) { key ->
            SettingRow(
                AdminSetting(key, snapshot.values[key].orEmpty()), editEnabled = editEnabled,
                restartOnly = snapshot.restart.requiresRestart(key), onEdit = { onEdit(key) },
            )
        }
    }
}

/** The connection checks, one row each. */
@Composable
fun ConnectionChecksContent(client: ApiClient, tests: SettingsTestsViewModel, results: Map<String, TestState>) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings-page:checks"), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        item { Text(stringResource(R.string.settings_checks_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        items(CONNECTION_CHECKS, key = { it.first }) { (kind, name) -> ConnectionCheckRow(kind, name, client, tests, results) }
    }
}

/** The name of a host state, and whether it needs a look. */
internal fun networkStateRes(state: String): Int? = when (state) {
    "disconnected" -> R.string.network_state_disconnected
    "awaiting_authorization" -> R.string.network_state_awaiting_authorization
    "connecting" -> R.string.network_state_connecting
    "connected" -> R.string.network_state_connected
    "error" -> R.string.network_state_error
    "unavailable" -> R.string.network_state_unavailable
    else -> null
}

internal fun isHttpUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

/** Network access: each provider's hosts with their state, Connect, Disconnect and the authorization link. */
@Composable
fun NetworkAccessContent(client: ApiClient, preloaded: List<Pair<NetworkProvider, NetworkAccessStatus>>? = null) {
    var loaded by remember(client) { mutableStateOf(preloaded) }
    var loadFailed by remember(client) { mutableStateOf(false) }
    var actionFailed by remember(client) { mutableStateOf(false) }
    var confirmDisconnect by remember(client) { mutableStateOf<Pair<String, NetworkHostStatus>?>(null) }
    var busy by remember(client) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    suspend fun read() {
        try {
            val providers = client.getNetworkAccessCapabilities().providers
            loaded = providers.map { it to client.getNetworkAccessStatus(it.provider) }
            loadFailed = false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadFailed = true }
    }
    // Read on open and on pull. Only while a host is connecting or waiting for its owner to authorize it (something the
    // administrator just started, and no channel reports) is it read again every few seconds, for at most two minutes.
    if (preloaded == null) ForegroundEffect(client) {
        read()
        var looks = 0
        while (isActive && looks++ < SETTLING_LOOKS && loaded.orEmpty().any { (_, status) -> status.hosts.any { it.state == "connecting" || it.state == "awaiting_authorization" } }) {
            delay(SETTLING_LOOK_MILLIS)
            read()
        }
    }
    fun command(provider: String, host: NetworkHostStatus, connect: Boolean) {
        scope.launch {
            busy = true; actionFailed = false
            try {
                if (connect) client.connectNetworkAccess(provider, host.host.id) else client.disconnectNetworkAccess(provider, host.host.id)
                read()
            } catch (e: CancellationException) { throw e }
            catch (_: ApiError) { actionFailed = true }
            catch (_: Exception) { actionFailed = true }
            finally { busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("settings-page:network-access"), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (loadFailed && loaded == null) item { MessageBanner(stringResource(R.string.network_load_failed), isError = true, onDismiss = { loadFailed = false }) }
        if (actionFailed) item { MessageBanner(stringResource(R.string.network_action_failed), isError = true, onDismiss = { actionFailed = false }) }
        val list = loaded
        if (list != null && list.isEmpty()) item { Text(stringResource(R.string.network_no_providers), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted) }
        list?.forEach { (provider, status) ->
            item(key = "provider:${provider.provider}") {
                Text(provider.display_name.ifBlank { provider.provider }, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, modifier = Modifier.padding(top = VeilSpacing.Small))
            }
            if (status.hosts.isEmpty()) item(key = "nohosts:${provider.provider}") { Text(stringResource(R.string.network_no_hosts), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
            items(status.hosts, key = { "host:${provider.provider}:${it.host.id}" }) { host ->
                HostRow(host, busy, onConnect = { command(provider.provider, host, true) }, onDisconnect = { confirmDisconnect = provider.provider to host },
                    onAuthorize = { host.auth_url?.takeIf(::isHttpUrl)?.let(uri::openUri) })
            }
        }
    }
    confirmDisconnect?.let { (provider, host) ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text(stringResource(R.string.network_disconnect_title)) },
            text = { Text(stringResource(R.string.network_disconnect_body)) },
            confirmButton = { DestructiveTextButton(onClick = { confirmDisconnect = null; command(provider, host, false) }) { Text(stringResource(R.string.network_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HostRow(host: NetworkHostStatus, busy: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit, onAuthorize: () -> Unit) {
    val role = stringResource(if (host.host.role == "api") R.string.network_host_api else R.string.network_host_proxy)
    val title = if (host.host.name.isNotBlank()) stringResource(R.string.network_host_named, host.host.name, role) else role
    val connected = host.state == "connected"
    val pending = host.state == "connecting" || host.state == "awaiting_authorization"
    RowCard(Modifier.testTag("network-host:${host.host.id}")) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
        StatusLabel(
            networkStateRes(host.state)?.let { stringResource(it) } ?: host.state,
            tone = when (host.state) { "error" -> StatusTone.Danger; "awaiting_authorization", "connecting" -> StatusTone.Warning; else -> StatusTone.Quiet },
            dot = host.state == "error" || pending,
        )
        host.origin?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.network_reach_at, it), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        host.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Danger) }
        host.updated_at?.let { formatDateTime(it) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        if (host.state == "unavailable") Text(stringResource(R.string.network_start_plugin), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            if (host.state == "awaiting_authorization" && isHttpUrl(host.auth_url)) Button(onClick = onAuthorize, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.network_authorize)) }
            if (connected || pending) OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.network_disconnect)) }
            else if (host.state != "unavailable") OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.network_connect)) }
        }
    }
}

private const val SETTLING_LOOK_MILLIS = 4_000L
private const val SETTLING_LOOKS = 30
