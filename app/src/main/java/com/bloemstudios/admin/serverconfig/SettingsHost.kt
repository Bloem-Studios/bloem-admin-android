package com.bloemstudios.admin.serverconfig

import com.bloemstudios.admin.realtime.LiveRefreshEffect
import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getNetworkAccessCapabilities
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val ROUTE_HOME = "home"
internal const val ROUTE_REQUESTS = "requests"
internal const val ROUTE_NETWORK = "network"
internal const val ROUTE_OTHER = "other"
internal const val ROUTE_CHECKS = "checks"
internal fun pageRoute(id: String) = "page:$id"

const val SettingsHomeTag = "settings-home"
fun settingsPageRowTag(id: String) = "settings-row:$id"

/** One server's Settings: a list of named pages, and each page. All servers opens one of these per server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsHost(
    client: ApiClient,
    scopeKey: String = "server",
    /** In All servers: leaves this server's pages. Null on a single server, where the pages list is the top. */
    onClose: (() -> Unit)? = null,
    initialRoute: String = ROUTE_HOME,
    /** The page that opened Settings on a given route has been used: forget it, so a later visit starts at the list. */
    onInitialRouteConsumed: (() -> Unit)? = null,
) {
    val vm = viewModel<SettingsPagesViewModel>(key = "settings:$scopeKey")
    val requestsVm = viewModel<RequestSettingsViewModel>(key = "request-settings:$scopeKey")
    val tests = viewModel<SettingsTestsViewModel>(key = "settings-tests:$scopeKey")
    // The guarded single-key save and the restart keep their one-at-a-time protection and error banner here.
    val actions = viewModel<ServerConfigViewModel>(key = "settings-actions:$scopeKey")
    val actionError by actions.actionError.collectAsState()
    val pending by actions.pendingKeys.collectAsState()
    val restartAnswer by actions.restartAnswer.collectAsState()
    val state by vm.state.collectAsState()
    val requests by requestsVm.state.collectAsState()
    val testResults by tests.results.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var savedNotice by remember { mutableStateOf<Int?>(null) }
    var requestsSaved by remember { mutableStateOf(false) }
    var networkOffered by remember(client) { mutableStateOf(false) }

    LaunchedEffect(Unit) { onInitialRouteConsumed?.invoke() }
    LiveRefreshEffect(client, SETTINGS_CHANNELS) {
        vm.load(client)
        requestsVm.load(client)
    }
    LaunchedEffect(client) {
        networkOffered = try { client.getNetworkAccessCapabilities(); true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) ForegroundEffect(pull) {
        if (pull.isRefreshing) {
            try { vm.load(client); requestsVm.load(client) } finally { pull.endRefresh() }
        }
    }

    val snapshot = state.snapshot
    val currentPage = if (route.startsWith("page:")) SETTINGS_PAGES.firstOrNull { it.id == route.removePrefix("page:") } else null
    val pageDirty = currentPage != null && state.draft.changes(currentPage.keys).isNotEmpty()
    val requestsDirty = route == ROUTE_REQUESTS && requests.changeCount > 0
    val dirty = pageDirty || requestsDirty
    // While there are unsaved edits the shell stays put: leaving takes a Save or a Discard.
    ShellInteractionLockEffect(dirty)

    fun open(next: String, focus: String? = null) { route = next; focusKey = focus; savedNotice = null; requestsSaved = false }
    fun leaveNow() {
        vm.discard(); requestsVm.discard()
        if (route == ROUTE_HOME) onClose?.invoke() else route = ROUTE_HOME
        savedNotice = null; requestsSaved = false
    }
    fun back() { if (dirty) confirmLeave = true else leaveNow() }
    BackHandler(enabled = route != ROUTE_HOME || onClose != null, onBack = ::back)

    Column(Modifier.fillMaxSize()) {
        if (route != ROUTE_HOME || onClose != null) {
            val title = when {
                currentPage != null -> stringResource(currentPage.titleRes)
                route == ROUTE_REQUESTS -> stringResource(R.string.requests_page_title)
                route == ROUTE_NETWORK -> stringResource(R.string.network_page_title)
                route == ROUTE_OTHER -> stringResource(R.string.settings_other_title)
                route == ROUTE_CHECKS -> stringResource(R.string.settings_checks_title)
                else -> stringResource(R.string.tab_server)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Tiny), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back, modifier = Modifier.testTag("settings-back")) {
                    LucideIcon(R.drawable.lucide_chevron_left, size = LucideSize.Bar, contentDescription = stringResource(if (route == ROUTE_HOME) R.string.action_cancel else R.string.settings_back_to_pages))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.semantics { heading() })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().nestedScroll(pull.nestedScrollConnection)) {
            when {
                snapshot == null -> {
                    if (state.loadError != null) VeilStateSurface(
                        kind = VeilStateSurfaceKind.Failure, title = stringResource(R.string.management_failed_title), message = state.loadError!!.resolve(),
                        stateDescription = state.loadError!!.resolve(),
                        action = com.bloemstudios.admin.ui.components.VeilStateSurfaceAction(stringResource(R.string.action_retry)) { scope.launch { vm.load(client) } },
                    ) else VeilStateSurface(kind = VeilStateSurfaceKind.Loading, title = "", message = "", stateDescription = stringResource(R.string.settings_page_loading))
                }
                route == ROUTE_HOME -> SettingsHomeContent(
                    snapshot = snapshot, state = state, query = query, onQuery = { query = it },
                    requestsOffered = requests.available, networkOffered = networkOffered,
                    onOpen = ::open, onRestart = { confirmRestart = true },
                    restarting = pending.isNotEmpty(),
                    restartNoticeRes = restartAnswer?.let { if (it.alreadyRequested) R.string.settings_restart_already else R.string.settings_restart_requested },
                    restartError = actionError,
                    onDismissRestartMessages = { actions.dismissActionError(); actions.dismissRestartAnswer() },
                )
                currentPage != null -> SettingsPageContent(
                    page = currentPage, state = state, client = client, tests = tests, testResults = testResults,
                    focusKey = focusKey, savedNotice = savedNotice,
                    actions = SettingsPageActions(
                        onEdit = { key, value -> savedNotice = null; vm.edit(key, value) },
                        onSave = {
                            scope.launch {
                                val outcome = vm.save(client, currentPage.keys)
                                if (outcome is SettingsSaveOutcome.Saved) {
                                    savedNotice = if (outcome.restartNeeded) R.string.settings_page_saved_restart else R.string.settings_page_saved
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        },
                        onDiscard = vm::discard,
                        onDismissError = vm::dismissSaveError,
                    ),
                )
                route == ROUTE_REQUESTS -> RequestSettingsContent(
                    state = requests, onEdit = { requestsSaved = false; requestsVm.edit(it) },
                    onSave = { scope.launch { if (requestsVm.save(client) == RequestSettingsOutcome.Saved) requestsSaved = true } },
                    onDiscard = requestsVm::discard, onDismissError = { requestsVm.discard() }, savedNotice = requestsSaved,
                )
                route == ROUTE_NETWORK -> NetworkAccessContent(client)
                route == ROUTE_OTHER -> OtherSettingsContent(snapshot, onEdit = { editingKey = it }, editEnabled = !state.saving)
                route == ROUTE_CHECKS -> ConnectionChecksContent(client, tests, testResults)
                else -> route = ROUTE_HOME
            }
            PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    editingKey?.let { key ->
        SettingEditorDialog(client, key, onDismiss = { editingKey = null }, onSaved = {
            editingKey = null
            scope.launch { vm.load(client, replaceEdits = true) }
        }, onSave = { original, value -> actions.save(original, value, client) })
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.server_config_restart_confirm_title)) },
            text = { Text(stringResource(R.string.settings_restart_confirm_body)) },
            confirmButton = {
                DestructiveTextButton(enabled = pending.isEmpty(), onClick = {
                    confirmRestart = false
                    scope.launch {
                        if (actions.restartServer(client) == com.bloemstudios.admin.ActionResult.Success) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.load(client, replaceEdits = true)
                    }
                }) { Text(stringResource(R.string.server_config_restart_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.settings_leave_title)) },
            text = { Text(stringResource(R.string.settings_leave_body)) },
            confirmButton = { DestructiveTextButton(onClick = { confirmLeave = false; leaveNow() }) { Text(stringResource(R.string.settings_leave_discard)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.settings_leave_keep)) } },
        )
    }
}

/** The list of named pages (or the results of a search), above the server's status and restart. */
@Composable
fun SettingsHomeContent(
    snapshot: SettingsSnapshot,
    state: SettingsPagesState,
    query: String,
    onQuery: (String) -> Unit,
    requestsOffered: Boolean,
    networkOffered: Boolean,
    onOpen: (route: String, focusKey: String?) -> Unit,
    onRestart: () -> Unit,
    restarting: Boolean,
    restartNoticeRes: Int?,
    restartError: ErrorText?,
    onDismissRestartMessages: () -> Unit,
) {
    val context = LocalContext.current
    val reported = snapshot.values.keys
    val pages = remember(reported) { SETTINGS_PAGES.filter { it.isAvailable(reported) } }
    val otherCount = remember(reported) { otherSettingKeys(reported).size }
    val results = remember(query, reported) {
        searchSettings(query, SETTINGS_PAGES, { context.getString(it.titleRes) }, { context.getString(it.labelRes) }, reported)
    }
    val searching = query.isNotBlank()
    val restartText = rememberRestartText(snapshot.status)
    // A page the server does not serve is never listed; Requests and Network access are listed once their routes answer.
    val orderedPages = remember(pages) {
        val order = listOf("general", "sign-in", "playback", "logging", "email", "notifications", "downloads")
        order.mapNotNull { id -> pages.firstOrNull { it.id == id } }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(SettingsHomeTag),
        contentPadding = PaddingValues(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        item(key = "messages") {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                restartNoticeRes?.let { MessageBanner(stringResource(it), isError = false, onDismiss = onDismissRestartMessages) }
                restartError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = onDismissRestartMessages) }
                state.loadError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
            }
        }
        // While searching, the results get the screen: the status card is back when the search is cleared.
        if (!searching) item(key = "status") { ServerStatusCard(snapshot.status, restartText, restartEnabled = !restarting, onRestart = onRestart) }
        item(key = "search") { SearchField(query, onQuery, stringResource(R.string.settings_home_search), Modifier.testTag("settings-search")) }
        if (searching) {
            if (results.pages.isEmpty() && results.settings.isEmpty()) item(key = "none") {
                Text(stringResource(R.string.settings_home_no_results), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted, modifier = Modifier.testTag("settings-no-results"))
            }
            if (results.settings.isNotEmpty()) {
                item(key = "h-settings") { SectionLabel(stringResource(R.string.settings_home_results_settings)) }
                items(results.settings, key = { "hit:${it.def.key}" }) { hit ->
                    ListRowCard(
                        title = stringResource(hit.def.labelRes),
                        supporting = listOf(stringResource(R.string.settings_result_in_page, hit.def.key, stringResource(hit.page.titleRes))),
                        onClick = { onOpen(pageRoute(hit.page.id), hit.def.key) },
                        modifier = Modifier.testTag("settings-hit:${hit.def.key}"),
                    )
                }
            }
            if (results.pages.isNotEmpty()) {
                item(key = "h-pages") { SectionLabel(stringResource(R.string.settings_home_results_pages)) }
                items(results.pages, key = { "page-hit:${it.id}" }) { page ->
                    ListRowCard(stringResource(page.titleRes), supporting = listOf(stringResource(page.descriptionRes)), onClick = { onOpen(pageRoute(page.id), null) })
                }
            }
        } else {
            val first = orderedPages.firstOrNull()
            orderedPages.forEach { page ->
                item(key = "row:${page.id}") {
                    ListRowCard(stringResource(page.titleRes), supporting = listOf(stringResource(page.descriptionRes)),
                        onClick = { onOpen(pageRoute(page.id), null) }, modifier = Modifier.testTag(settingsPageRowTag(page.id)))
                }
                // The web puts Network access beside General: a server's address and how to reach it.
                if (page === first && networkOffered) item(key = "row:network") {
                    ListRowCard(stringResource(R.string.network_page_title), supporting = listOf(stringResource(R.string.network_page_desc)),
                        onClick = { onOpen(ROUTE_NETWORK, null) }, modifier = Modifier.testTag(settingsPageRowTag("network-access")))
                }
            }
            if (requestsOffered) item(key = "row:requests") {
                ListRowCard(stringResource(R.string.requests_page_title), supporting = listOf(stringResource(R.string.requests_page_desc)),
                    onClick = { onOpen(ROUTE_REQUESTS, null) }, modifier = Modifier.testTag(settingsPageRowTag("requests")))
            }
            item(key = "row:checks") {
                ListRowCard(stringResource(R.string.settings_checks_title), supporting = listOf(stringResource(R.string.settings_checks_desc)),
                    onClick = { onOpen(ROUTE_CHECKS, null) }, modifier = Modifier.testTag(settingsPageRowTag("checks")))
            }
            item(key = "row:other") {
                ListRowCard(stringResource(R.string.settings_other_title),
                    supporting = listOf(stringResource(R.string.settings_other_desc), stringResource(R.string.settings_other_count, otherCount)),
                    onClick = { onOpen(ROUTE_OTHER, null) }, modifier = Modifier.testTag(settingsPageRowTag("other")))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
        modifier = Modifier.padding(top = VeilSpacing.Small).heightIn(min = VeilSpacing.Large).semantics { heading() })
}

/** A setting changed on the server (the `settings` channel). */
internal val SETTINGS_CHANNELS: Set<String> = setOf(com.bloemstudios.admin.realtime.LiveChannels.SETTINGS)
