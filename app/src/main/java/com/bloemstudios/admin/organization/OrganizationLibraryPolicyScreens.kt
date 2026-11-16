package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.libraryTypeLabelRes
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

// ---- Libraries & Entitlements -------------------------------------------------------------------------

/** What an organization's members can reach at most, counted from the libraries below. */
internal data class LibraryCeiling(val usable: Int, val owned: Int, val shared: Int, val paused: Int, val withdrawn: Int)

internal fun libraryCeiling(libraries: List<OrganizationLibrary>): LibraryCeiling = LibraryCeiling(
    usable = libraries.count { it.countsTowardCeiling },
    owned = libraries.count { it.isOwned },
    shared = libraries.count { !it.isOwned && it.entitlement?.status == "active" },
    paused = libraries.count { !it.isOwned && it.entitlement?.status == "suspended" },
    withdrawn = libraries.count { !it.isOwned && it.entitlement?.status == "revoked" },
)

@Composable
fun OrganizationLibrariesScreen(session: OrganizationSession) {
    val viewModel = viewModel<LibrariesViewModel>()
    val state by viewModel.state.collectAsState()
    ForegroundEffect(session) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    LibrariesContent(
        state = state,
        onRetry = { scope.launch { viewModel.load(session) } },
        onRefresh = { viewModel.load(session) },
        onSetStatus = { library, status -> scope.launch { viewModel.setStatus(session, library, status) } },
        onGiveBack = { library -> scope.launch { viewModel.giveBack(session, library) } },
        onDismiss = viewModel::dismissMessages,
    )
}

@Composable
internal fun LibrariesContent(
    state: LibrariesState,
    onRetry: () -> Unit,
    onSetStatus: (OrganizationLibrary, String) -> Unit,
    onGiveBack: (OrganizationLibrary) -> Unit = {},
    onDismiss: () -> Unit = {},
    onRefresh: (suspend () -> Unit)? = null,
) {
    var confirming by remember { mutableStateOf<Pair<OrganizationLibrary, Boolean>?>(null) }
    var givingBack by remember { mutableStateOf<OrganizationLibrary?>(null) }
    OrganizationPage(
        title = stringResource(R.string.organization_libraries_title),
        subtitle = null,
        loading = state.loading, empty = state.libraries.isEmpty(),
        emptyMessage = stringResource(R.string.organization_libraries_none),
        error = state.error, notice = state.notice, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
        header = {
            Text(stringResource(R.string.organization_libraries_ceiling), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        },
    ) {
        item {
            val ceiling = libraryCeiling(state.libraries)
            OrganizationCard(Modifier.testTag("organization-library-ceiling").semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.organization_libraries_ceiling_title), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                Text(
                    pluralStringResource(R.plurals.organization_libraries_usable, ceiling.usable, ceiling.usable),
                    style = MaterialTheme.typography.headlineSmall, color = VeilColors.Text,
                )
                Text(
                    buildList {
                        add(stringResource(R.string.organization_libraries_ceiling_owned, ceiling.owned))
                        add(stringResource(R.string.organization_libraries_ceiling_shared, ceiling.shared))
                        if (ceiling.paused > 0) add(stringResource(R.string.organization_libraries_ceiling_paused, ceiling.paused))
                        if (ceiling.withdrawn > 0) add(stringResource(R.string.organization_libraries_ceiling_withdrawn, ceiling.withdrawn))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
            }
        }
        items(state.libraries, key = { it.folder_id }) { library ->
            val pending = library.folder_id in state.pendingFolderIds
            OrganizationCard(Modifier.testTag("organization-library-${library.folder_id}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(library.name, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.weight(1f))
                    StatusLabel(stringResource(if (library.isOwned) R.string.organization_libraries_owned else R.string.organization_libraries_shared))
                }
                if (library.type.isNotBlank()) {
                    Text(codeLabel(library.type, ::libraryTypeLabelRes), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
                val entitlement = library.entitlement
                if (entitlement != null) {
                    Text(
                        stringResource(
                            when (entitlement.status) {
                                "active" -> R.string.organization_libraries_active
                                "suspended" -> R.string.organization_libraries_paused
                                else -> R.string.organization_libraries_withdrawn
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                    )
                    // Pause, restore and give back are for libraries the server shares, as on the web; a withdrawn one
                    // has nothing left to change here.
                    if (!library.isOwned && (entitlement.status == "active" || entitlement.status == "suspended")) {
                        val pause = entitlement.status == "active"
                        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                            ActionButton(
                                text = stringResource(if (pause) R.string.organization_libraries_pause else R.string.organization_libraries_restore),
                                description = stringResource(
                                    R.string.organization_libraries_actions,
                                    stringResource(if (pause) R.string.organization_libraries_pause else R.string.organization_libraries_restore),
                                    library.name,
                                ),
                                onClick = { confirming = library to pause },
                                enabled = !pending,
                            )
                            ActionButton(
                                text = stringResource(R.string.organization_libraries_give_back),
                                description = stringResource(R.string.organization_libraries_actions, stringResource(R.string.organization_libraries_give_back), library.name),
                                onClick = { givingBack = library },
                                enabled = !pending,
                            )
                        }
                    }
                } else if (library.isOwned) {
                    Text(stringResource(R.string.organization_libraries_owned_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
            }
        }
    }

    confirming?.let { (library, pause) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (pause) R.string.organization_libraries_pause_title else R.string.organization_libraries_restore_title, library.name)) },
            text = { Text(stringResource(if (pause) R.string.organization_libraries_pause_body else R.string.organization_libraries_restore_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirming = null; onSetStatus(library, if (pause) "suspended" else "active") },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(if (pause) R.string.organization_libraries_pause else R.string.organization_libraries_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }

    givingBack?.let { library ->
        AlertDialog(
            onDismissRequest = { givingBack = null },
            title = { Text(stringResource(R.string.organization_libraries_give_back_title, library.name)) },
            text = { Text(stringResource(R.string.organization_libraries_give_back_body)) },
            confirmButton = {
                TextButton(
                    onClick = { givingBack = null; onGiveBack(library) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-library-give-back-confirm"),
                ) { Text(stringResource(R.string.organization_libraries_give_back), color = VeilColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { givingBack = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}

// ---- Policy Cohorts (read-only) -----------------------------------------------------------------------

@Composable
fun OrganizationCohortsScreen(session: OrganizationSession) {
    val viewModel = viewModel<CohortsViewModel>()
    val state by viewModel.state.collectAsState()
    ForegroundEffect(session) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    CohortsContent(state, onRetry = { scope.launch { viewModel.load(session) } }, onRefresh = { viewModel.load(session) })
}

@Composable
internal fun CohortsContent(state: CohortsState, onRetry: () -> Unit, onShowArchived: (Boolean) -> Unit = {}, onRefresh: (suspend () -> Unit)? = null) {
    val active = state.cohorts.filter { !it.archived }
    val archived = state.cohorts.filter { it.archived }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    OrganizationPage(
        title = stringResource(R.string.organization_cohorts_title),
        subtitle = null,
        loading = state.loading, empty = state.cohorts.isEmpty(),
        emptyMessage = stringResource(R.string.organization_cohorts_none),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = {}, onRefresh = onRefresh,
    ) {
        items(active, key = { it.cohort_id }) { cohort -> CohortCard(cohort, state.libraries) }
        if (archived.isNotEmpty()) {
            item(key = "archived-toggle") {
                val label = stringResource(R.string.organization_cohorts_archived_count, archived.size)
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = showArchived, onCheckedChange = { showArchived = it; onShowArchived(it) },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = label }.testTag("organization-cohorts-archived-toggle"),
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
                }
            }
            if (showArchived) {
                item(key = "archived-note") {
                    Text(stringResource(R.string.organization_cohorts_archived_note), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
                items(archived, key = { it.cohort_id }) { cohort -> CohortCard(cohort, state.libraries) }
            }
        }
    }
}

@Composable
private fun CohortCard(cohort: EntitlementCohort, libraries: List<OrganizationLibrary>) {
    var expanded by rememberSaveable(cohort.cohort_id) { mutableStateOf(false) }
    val origin = cohort.source_template_key?.takeIf { it.isNotBlank() }?.let { plan ->
        if ((cohort.source_template_revision ?: 0L) > 0L) stringResource(R.string.organization_cohorts_from_plan_revision, plan, cohort.source_template_revision!!.toInt())
        else stringResource(R.string.organization_cohorts_from_plan, plan)
    } ?: (derivationRes(cohort.derivation_kind)?.let { stringResource(it) } ?: sentenceCase(cohort.derivation_kind))
    val cardLabel = stringResource(R.string.organization_cohorts_card_label, cohort.name, cohort.revision.toInt())
    OrganizationCard(Modifier.testTag("organization-cohort-${cohort.cohort_id}").semantics { contentDescription = cardLabel }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(cohort.name, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.weight(1f))
            if (cohort.archived) StatusLabel(stringResource(R.string.organization_cohorts_archived))
        }
        Text(origin, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        Text(
            pluralStringResource(R.plurals.organization_cohorts_accounts, cohort.member_count.toInt(), cohort.member_count.toInt()),
            style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
        )
        Text(stringResource(R.string.organization_cohorts_settings), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
        PolicySummary(cohort.policy, libraries)
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-cohort-details-${cohort.cohort_id}"),
        ) { Text(stringResource(if (expanded) R.string.organization_cohorts_hide_details else R.string.organization_cohorts_details)) }
        if (expanded) {
            Fact(stringResource(R.string.organization_cohorts_how_made), derivationRes(cohort.derivation_kind)?.let { stringResource(it) } ?: sentenceCase(cohort.derivation_kind))
            Fact(stringResource(R.string.organization_cohorts_cohort_revision), cohort.revision.toString())
            cohort.parent_cohort_id?.takeIf { it.isNotBlank() }?.let { Fact(stringResource(R.string.organization_cohorts_started_from), it) }
            Fact(stringResource(R.string.organization_cohorts_created), formatWhen(cohort.created_at).ifBlank { cohort.created_at })
            cohort.created_by_account_id?.takeIf { it > 0 }?.let {
                Fact(stringResource(R.string.organization_cohorts_created_by), stringResource(R.string.organization_decisions_account, it.toInt()))
            }
            Fact(stringResource(R.string.organization_cohorts_access_group), cohort.access_group_id.toString())
            Fact(stringResource(R.string.organization_cohorts_cohort_id), cohort.cohort_id)
            if (cohort.policy_digest.isNotBlank()) Fact(stringResource(R.string.organization_cohorts_fingerprint), cohort.policy_digest)
        }
    }
}

// ---- Policy Decisions (read-only) ---------------------------------------------------------------------

@Composable
fun OrganizationDecisionsScreen(session: OrganizationSession) {
    val viewModel = viewModel<DecisionsViewModel>()
    val state by viewModel.state.collectAsState()
    ForegroundEffect(session) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.selectedId != null) { viewModel.close() }
    DecisionsContent(
        state, onRetry = { scope.launch { viewModel.load(session) } },
        onRefresh = { viewModel.load(session) },
        onMore = { scope.launch { viewModel.loadMore(session) } },
        onOpen = { id -> scope.launch { viewModel.open(session, id) } },
        onClose = viewModel::close,
    )
}

@Composable
internal fun DecisionsContent(
    state: DecisionsState,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Long) -> Unit = {},
    onClose: () -> Unit = {},
    onRefresh: (suspend () -> Unit)? = null,
) {
    if (state.selectedId != null) {
        DecisionDetailContent(state, onClose)
        return
    }
    OrganizationPage(
        title = stringResource(R.string.organization_decisions_title),
        subtitle = null,
        loading = state.loading, empty = state.decisions.isEmpty(),
        emptyMessage = stringResource(R.string.organization_decisions_none),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = {}, onRefresh = onRefresh,
    ) {
        items(state.decisions, key = { it.id }) { decision ->
            val action = accessActionRes(decision.action)?.let { stringResource(it) } ?: sentenceCase(decision.action)
            val title = stringResource(if (decision.allowed) R.string.organization_decisions_allowed else R.string.organization_decisions_refused, action)
            val openLabel = stringResource(R.string.organization_decisions_open, title)
            OrganizationCard(Modifier.testTag("organization-decision-${decision.id}")) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    color = if (decision.allowed) VeilColors.Ok else VeilColors.Danger,
                )
                val who = buildList {
                    add(
                        if (decision.subject.account_id > 0) stringResource(R.string.organization_decisions_account, decision.subject.account_id.toInt())
                        else stringResource(R.string.organization_decisions_account_unknown)
                    )
                    if (decision.subject.profile_id.isNotBlank()) add(stringResource(R.string.organization_decisions_profile, decision.subject.profile_id))
                    if (decision.group.name.isNotBlank()) add(stringResource(R.string.organization_decisions_group, decision.group.name))
                }
                Text(who.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                val reason = if (decision.allowed) "" else accessReasonRes(decision.reason_code)?.let { stringResource(it) } ?: sentenceCase(decision.reason_code)
                Text(
                    listOf(reason, formatWhen(decision.timestamp)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
                ActionButton(stringResource(R.string.organization_decisions_see_why), openLabel, { onOpen(decision.id) })
            }
        }
        if (state.nextCursor != null) item {
            TextButton(
                onClick = onMore, enabled = !state.loadingMore,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-decisions-more"),
            ) { Text(stringResource(if (state.loadingMore) R.string.organization_loading else R.string.organization_decisions_show_more)) }
        }
    }
}

@Composable
private fun DecisionDetailContent(state: DecisionsState, onClose: () -> Unit) {
    val decision = state.detail
    OrganizationPage(
        title = stringResource(R.string.organization_decisions_detail_title),
        subtitle = null,
        showTitle = true,
        loading = state.detailLoading, empty = decision == null,
        emptyMessage = stringResource(R.string.organization_error_decision_detail),
        error = state.detailError, notice = null, onRetry = {}, onDismissMessages = {},
        header = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-decision-back")) {
                Text(stringResource(R.string.organization_decisions_back))
            }
        },
    ) {
        if (decision == null) return@OrganizationPage
        item {
            val action = accessActionRes(decision.action)?.let { stringResource(it) } ?: sentenceCase(decision.action)
            val none = stringResource(R.string.organization_policy_none)
            OrganizationCard(Modifier.testTag("organization-decision-detail")) {
                Text(
                    stringResource(if (decision.allowed) R.string.organization_decisions_allowed else R.string.organization_decisions_refused, action),
                    style = MaterialTheme.typography.titleLarge, color = if (decision.allowed) VeilColors.Ok else VeilColors.Danger,
                )
                Text(formatWhen(decision.timestamp), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                if (!decision.allowed) Text(
                    accessReasonRes(decision.reason_code)?.let { stringResource(it) } ?: sentenceCase(decision.reason_code),
                    style = MaterialTheme.typography.bodyMedium, color = VeilColors.Danger,
                )
                val account = if (decision.subject.account_id > 0) stringResource(R.string.organization_decisions_account, decision.subject.account_id.toInt())
                else stringResource(R.string.organization_decisions_account_unknown)
                Fact(
                    stringResource(R.string.organization_decisions_who),
                    if (decision.subject.profile_id.isNotBlank()) stringResource(R.string.organization_decisions_with_profile, account, decision.subject.profile_id) else account,
                )
                Fact(
                    stringResource(R.string.organization_decisions_access_group),
                    decision.group.name.ifBlank { if (decision.group.id > 0) stringResource(R.string.organization_decisions_group_number, decision.group.id.toInt()) else none },
                )
                Fact(stringResource(R.string.organization_decisions_libraries), libraryListText(decision.library_ceiling.orEmpty(), state.libraries))
            }
        }
        if (decision.resource.isNotEmpty()) item {
            OrganizationCard(Modifier.testTag("organization-decision-resource")) {
                Text(stringResource(R.string.organization_decisions_requested), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                val hidden = stringResource(R.string.organization_decisions_hidden)
                decision.resource.entries.forEach { (key, value) ->
                    val text = resourceValueText(value)
                    // The server already replaced anything sensitive; say so for a screen reader too.
                    Fact(sentenceCase(key), if (text == "[redacted]") hidden else text)
                }
            }
        }
        item {
            var open by rememberSaveable(decision.id) { mutableStateOf(false) }
            OrganizationCard(Modifier.testTag("organization-decision-technical")) {
                TextButton(
                    onClick = { open = !open }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(if (open) R.string.organization_cohorts_hide_details else R.string.organization_cohorts_details)) }
                if (open) {
                    val none = stringResource(R.string.organization_policy_none)
                    Fact(stringResource(R.string.organization_decisions_check), decision.id.toString())
                    Fact(stringResource(R.string.organization_decisions_reason_code), decision.reason_code.ifBlank { none })
                    Fact(stringResource(R.string.organization_decisions_organization_id), decision.organization.id)
                    Fact(stringResource(R.string.organization_decisions_membership_id), decision.organization.membership_id ?: none)
                    if (decision.group.id > 0) Fact(stringResource(R.string.organization_decisions_access_group_id), decision.group.id.toString())
                    val rules = decision.policy_versions.orEmpty().map { version ->
                        if (!version.name.isNullOrBlank()) stringResource(R.string.organization_decisions_rule_named, version.kind, version.name, version.version.toInt())
                        else stringResource(R.string.organization_decisions_rule, version.kind, version.version.toInt())
                    }.joinToString(", ")
                    Fact(stringResource(R.string.organization_decisions_rules_checked), rules.ifBlank { stringResource(R.string.organization_decisions_none_recorded) })
                }
            }
        }
    }
}
