package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.ConfirmButton
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.OrganizationLiveChannels
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

// ---- People -------------------------------------------------------------------------------------------

/** "Active in the last N days" choices a phone offers instead of a date field. */
internal val ActiveWithinChoices: List<Int?> = listOf(null, 1, 7, 30, 90)

@Composable
fun OrganizationPeopleScreen(session: OrganizationSession) {
    val viewModel = viewModel<PeopleViewModel>()
    val state by viewModel.state.collectAsState()
    val context by session.context.collectAsState()
    var openAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    OrganizationLiveEffect(session, OrganizationLiveChannels.PEOPLE) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    val opened = openAccountId
    if (opened != null) {
        PersonDetailScreen(
            session = session, accountId = opened,
            onBack = {
                openAccountId = null
                // What changed in the detail shows in the list.
                scope.launch { viewModel.load(session) }
            },
        )
        return
    }
    PeopleContent(
        state = state, context = context,
        onFilter = { scope.launch { viewModel.load(session, it) } },
        onLoadMore = { scope.launch { viewModel.loadMore(session) } },
        onRetry = { scope.launch { viewModel.load(session) } },
        onRefresh = { viewModel.load(session) },
        onDismiss = viewModel::dismissMessages,
        onSetSuspended = { person, suspend -> scope.launch { viewModel.setSuspended(session, person, suspend) } },
        onAssignGroup = { person, profile, group -> scope.launch { viewModel.assignGroup(session, person, profile, group) } },
        onOpen = { openAccountId = it.account_id },
        onStartSelecting = viewModel::startSelecting,
        onStopSelecting = viewModel::stopSelecting,
        onToggle = viewModel::toggle,
        onSelectAll = { scope.launch { viewModel.selectAllMatching(session) } },
        onApply = { action -> scope.launch { viewModel.apply(session, action) } },
        onDismissReport = viewModel::dismissReport,
    )
}

@Composable
internal fun PeopleContent(
    state: PeopleState,
    context: AdminContext?,
    onFilter: (PeopleFilter) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSetSuspended: (PersonSummary, Boolean) -> Unit,
    onAssignGroup: (PersonSummary, ProfileSummary, AccessGroup) -> Unit,
    onOpen: (PersonSummary) -> Unit = {},
    onStartSelecting: () -> Unit = {},
    onStopSelecting: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onApply: (BulkAction) -> Unit = {},
    onDismissReport: () -> Unit = {},
    onRefresh: (suspend () -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf(state.filter.query) }
    var confirming by remember { mutableStateOf<Pair<PersonSummary, Boolean>?>(null) }
    var moving by remember { mutableStateOf<Pair<PersonSummary, ProfileSummary>?>(null) }
    var pickingBulkGroup by remember { mutableStateOf(false) }
    var bulkConfirm by remember { mutableStateOf<BulkAction?>(null) }
    val submit = { onFilter(state.filter.copy(query = query)) }
    val anyStatus = stringResource(R.string.organization_people_any_status)
    val anyGroup = stringResource(R.string.organization_people_any_group)
    val anyTime = stringResource(R.string.organization_people_any_time)
    val organizationName = context?.name ?: stringResource(R.string.organization_this_organization)

    // The bulk bar sits below the list, never over it, so no row is ever hidden behind it.
    Column {
        OrganizationPage(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.organization_people_title),
            subtitle = null,
            loading = state.loading, empty = state.people.isEmpty(),
            emptyMessage = stringResource(if (state.filter.isNarrowed) R.string.organization_people_no_matches else R.string.organization_people_none),
            error = state.error, notice = state.notice, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
            header = {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.organization_people_search)) },
                    placeholder = { Text(stringResource(R.string.organization_people_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("organization-people-search"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.fillMaxWidth()) {
                    FilterMenu(
                        label = stringResource(R.string.organization_people_status),
                        selected = state.filter.statuses.singleOrNull()?.let { statusLabel(it) } ?: anyStatus,
                        options = listOf<Pair<String?, String>>(null to anyStatus) +
                            listOf("active", "suspended", "invited").map { it to statusLabel(it) },
                        onSelect = { onFilter(state.filter.copy(query = query, statuses = setOfNotNull(it))) },
                        modifier = Modifier.weight(1f),
                    )
                    if (state.groups.isNotEmpty()) FilterMenu(
                        label = stringResource(R.string.organization_people_access_group),
                        selected = state.groups.firstOrNull { it.id.toLongOrNull() == state.filter.groupId }?.name ?: anyGroup,
                        options = listOf<Pair<String?, String>>(null to anyGroup) + state.groups.map { it.id to it.name },
                        onSelect = { onFilter(state.filter.copy(query = query, groupId = it?.toLongOrNull())) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.fillMaxWidth()) {
                    FilterMenu(
                        label = stringResource(R.string.organization_people_sort),
                        selected = sortLabel(state.filter.sort ?: PeopleSort.NAME),
                        options = PeopleSort.all.map { it to sortLabel(it) },
                        onSelect = { onFilter(state.filter.copy(query = query, sort = it?.takeIf { sort -> sort != PeopleSort.NAME })) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterMenu(
                        label = stringResource(R.string.organization_people_active_since),
                        selected = state.filter.activeWithinDays?.let { activeWithinLabel(it) } ?: anyTime,
                        options = ActiveWithinChoices.map { (it?.toString()) to (it?.let { days -> activeWithinLabel(days) } ?: anyTime) },
                        onSelect = { onFilter(state.filter.copy(query = query, activeWithinDays = it?.toIntOrNull(), activeSince = null)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.approximateTotal > 0) {
                    Text(
                        pluralStringResource(R.plurals.organization_people_about, state.approximateTotal.toInt(), state.approximateTotal.toInt()),
                        style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                    )
                }
                if (state.people.isNotEmpty() || state.selecting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                        if (state.selecting) {
                            TextButton(
                                onClick = onStopSelecting, enabled = !state.bulkRunning,
                                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-done-selecting"),
                            ) { Text(stringResource(R.string.organization_people_done_selecting)) }
                            if (state.approximateTotal > 0) TextButton(
                                onClick = onSelectAll, enabled = !state.makingSelection && !state.bulkRunning && state.matching == null,
                                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-select-all"),
                            ) {
                                Text(
                                    if (state.makingSelection) stringResource(R.string.organization_people_selecting)
                                    else stringResource(R.string.organization_people_select_all, state.approximateTotal.toInt()),
                                )
                            }
                        } else TextButton(
                            onClick = onStartSelecting,
                            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-select"),
                        ) { Text(stringResource(R.string.organization_people_select)) }
                    }
                }
            },
        ) {
            state.report?.let { report ->
                item(key = "bulk-report") { BulkReportCard(report, onDismissReport) }
            }
            items(state.people, key = { it.account_id }) { person ->
                PersonCard(
                    person = person, self = person.isSelf(context),
                    pending = person.account_id in state.pendingAccountIds || state.bulkRunning,
                    selecting = state.selecting,
                    checked = state.matching != null || person.account_id in state.pickedIds,
                    onToggle = { onToggle(person.account_id) },
                    onOpen = { onOpen(person) },
                    onSuspend = { confirming = person to true },
                    onReactivate = { confirming = person to false },
                    onMove = { profile -> moving = person to profile },
                )
            }
            if (state.nextCursor != null) item {
                TextButton(
                    onClick = onLoadMore, enabled = !state.loadingMore,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-more"),
                ) { Text(stringResource(if (state.loadingMore) R.string.organization_loading else R.string.organization_people_show_more)) }
            }
        }
        if (state.selecting && state.hasSelection && !state.bulkRunning) {
            BulkBar(
                state = state, groupsAvailable = state.groups.isNotEmpty(),
                onMove = { pickingBulkGroup = true },
                onReactivate = { bulkConfirm = BulkAction.Reactivate },
                onSuspend = { bulkConfirm = BulkAction.Suspend },
            )
        }
    }

    confirming?.let { (person, suspend) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (suspend) R.string.organization_people_suspend_title else R.string.organization_people_reactivate_title, person.label)) },
            text = { Text(stringResource(if (suspend) R.string.organization_people_suspend_body else R.string.organization_people_reactivate_body)) },
            confirmButton = {
                ConfirmButton(destructive = suspend, 
                    onClick = { confirming = null; onSetSuspended(person, suspend) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(if (suspend) R.string.organization_people_suspend else R.string.organization_people_reactivate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }

    moving?.let { (person, profile) ->
        GroupPickerDialog(
            title = stringResource(R.string.organization_people_move_title, profile.name),
            groups = state.groups, currentGroupId = profile.group_id,
            onPick = { moving = null; onAssignGroup(person, profile, it) },
            onDismiss = { moving = null },
        )
    }

    if (pickingBulkGroup) {
        GroupPickerDialog(
            title = stringResource(R.string.organization_people_bulk_move_title),
            groups = state.groups, currentGroupId = -1,
            onPick = { pickingBulkGroup = false; bulkConfirm = BulkAction.AssignGroup(it) },
            onDismiss = { pickingBulkGroup = false },
        )
    }

    bulkConfirm?.let { action ->
        val groupName = (action as? BulkAction.AssignGroup)?.group?.name.orEmpty()
        val count = state.selectedCount.toInt()
        val people = pluralStringResource(R.plurals.organization_people_count, count, count)
        val body = state.matching?.let { selection ->
            val filters = filterSummary(state.filter, state.groups)
            val base = stringResource(R.string.organization_people_confirm_matching, people, organizationName, filters)
            if (selection.excluded > 0) base + " " + stringResource(R.string.organization_people_confirm_left_out, selection.excluded.toInt()) else base
        } ?: stringResource(R.string.organization_people_confirm_picked, people, organizationName)
        AlertDialog(
            onDismissRequest = { bulkConfirm = null },
            title = {
                Text(
                    when (action) {
                        is BulkAction.AssignGroup -> stringResource(R.string.organization_people_confirm_move, groupName)
                        BulkAction.Reactivate -> stringResource(R.string.organization_people_confirm_reactivate)
                        BulkAction.Suspend -> stringResource(R.string.organization_people_confirm_suspend)
                    }
                )
            },
            text = { Text(body) },
            confirmButton = {
                ConfirmButton(destructive = action == BulkAction.Suspend, 
                    onClick = { bulkConfirm = null; onApply(action) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-bulk-confirm"),
                ) { Text(stringResource(if (action == BulkAction.Suspend) R.string.organization_people_suspend else R.string.organization_people_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { bulkConfirm = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}

@Composable
private fun sortLabel(sort: String): String = stringResource(
    when (sort) {
        PeopleSort.EMAIL -> R.string.organization_people_sort_email
        PeopleSort.RECENT_ACTIVITY -> R.string.organization_people_sort_recent
        else -> R.string.organization_people_sort_name
    }
)

@Composable
private fun activeWithinLabel(days: Int): String =
    if (days == 1) stringResource(R.string.organization_people_active_last_day) else stringResource(R.string.organization_people_active_last_days, days)

/** The web's sentence for what a filter matches: "matching “sam”, active, in Family, active in the last 7 days". */
@Composable
internal fun filterSummary(filter: PeopleFilter, groups: List<AccessGroup>): String {
    val parts = buildList {
        if (filter.query.isNotBlank()) add(stringResource(R.string.organization_people_filter_matching, filter.query.trim()))
        if (filter.statuses.isNotEmpty()) add(filter.statuses.sorted().map { statusLabel(it).lowercase() }.joinToString(" / "))
        filter.groupId?.let { id ->
            val name = groups.firstOrNull { it.id.toLongOrNull() == id }?.name ?: stringResource(R.string.organization_people_group_hash, id.toInt())
            add(stringResource(R.string.organization_people_filter_in_group, name))
        }
        filter.activeWithinDays?.let { add(stringResource(R.string.organization_people_filter_active, it)) }
    }
    return if (parts.isEmpty()) stringResource(R.string.organization_people_filter_everyone) else parts.joinToString(", ")
}

@Composable
private fun PersonCard(
    person: PersonSummary,
    self: Boolean,
    pending: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
    onMove: (ProfileSummary) -> Unit,
) {
    OrganizationCard(Modifier.testTag("organization-person-${person.account_id}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                val selectLabel = stringResource(R.string.organization_people_select_person, person.label)
                // The box is the 48dp target; the checkbox inside is what a screen reader toggles.
                Box(
                    Modifier.size(VeilSpacing.MinimumTouchTarget).semantics(mergeDescendants = true) { contentDescription = selectLabel }
                        .testTag("organization-person-check-${person.account_id}"),
                    contentAlignment = Alignment.Center,
                ) { Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = !pending) }
            }
            Column(Modifier.weight(1f)) {
                Text(person.label, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                if (person.email.isNotBlank() && person.email != person.label) {
                    Text(person.email, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
            }
            StatusLabel(
                statusLabel(person.membership_status),
                tone = if (person.isSuspended) StatusTone.Warning else StatusTone.Quiet,
            )
        }
        val tags = buildList {
            if (person.isAdministrator) add(stringResource(R.string.organization_people_administrator))
            if (self) add(stringResource(R.string.organization_people_you))
            if (!person.last_activity.isNullOrBlank()) add(stringResource(R.string.organization_people_last_active, formatWhen(person.last_activity)))
        }
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        person.profiles.forEach { profile ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.organization_people_profile_group, profile.name, profile.group_name.ifBlank { "#${profile.group_id}" }),
                    style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text, modifier = Modifier.weight(1f),
                )
                val moveLabel = stringResource(R.string.organization_people_move_profile, profile.name)
                TextButton(
                    onClick = { onMove(profile) }, enabled = !pending,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = moveLabel },
                ) { Text(stringResource(R.string.organization_people_access_group)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
            when {
                pending -> Text(stringResource(R.string.organization_people_working), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                person.isSuspended -> ActionButton(
                    stringResource(R.string.organization_people_reactivate),
                    stringResource(R.string.organization_people_actions, person.label) + ": " + stringResource(R.string.organization_people_reactivate),
                    onReactivate,
                )
                person.isActive && !self -> ActionButton(
                    stringResource(R.string.organization_people_suspend),
                    stringResource(R.string.organization_people_actions, person.label) + ": " + stringResource(R.string.organization_people_suspend),
                    onSuspend,
                )
            }
            ActionButton(
                stringResource(R.string.organization_people_details),
                stringResource(R.string.organization_people_details_for, person.label),
                onOpen,
            )
        }
    }
}

/** The bar that follows the selection: how many, and the web's three bulk changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkBar(
    state: PeopleState,
    groupsAvailable: Boolean,
    onMove: () -> Unit,
    onReactivate: () -> Unit,
    onSuspend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.organization_people_bulk_actions)
    val count = state.selectedCount.toInt()
    Column(
        modifier
            .fillMaxWidth()
            .padding(VeilSpacing.Small)
            .background(VeilColors.Card, RoundedCornerShape(VeilShapes.CompactCardCorner))
            .padding(VeilSpacing.Small)
            .semantics { contentDescription = label }
            .testTag("organization-people-bulk-bar"),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
    ) {
        Text(
            pluralStringResource(R.plurals.organization_people_selected, count, count),
            style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
        )
        // The actions wrap onto a second line on a narrow phone rather than running off the edge.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            if (groupsAvailable) OutlinedButton(
                onClick = onMove, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-bulk-move"),
            ) { Text(stringResource(R.string.organization_people_bulk_move)) }
            OutlinedButton(
                onClick = onReactivate, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-bulk-reactivate"),
            ) { Text(stringResource(R.string.organization_people_reactivate)) }
            OutlinedButton(
                onClick = onSuspend, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-people-bulk-suspend"),
            ) { Text(stringResource(R.string.organization_people_suspend)) }
        }
    }
}

/** What a bulk change did so far, then what it did. Matches the web's result card. */
@Composable
internal fun BulkReportCard(report: BulkReport, onDismiss: () -> Unit) {
    val job = report.job
    OrganizationCard(Modifier.testTag("organization-bulk-report").semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(bulkHeadingRes(job)), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
        Text(
            stringResource(R.string.organization_bulk_progress, job.progress_current.toInt(), job.progress_total.toInt()),
            style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
        )
        Text(
            listOf(
                stringResource(R.string.organization_bulk_changed, job.succeeded.toInt()),
                stringResource(R.string.organization_bulk_skipped, job.skippedRecords.size),
                stringResource(R.string.organization_bulk_failed_count, job.failedRecords.size),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
        )
        report.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Danger) }
        if (job.skippedRecords.isNotEmpty()) {
            Text(stringResource(R.string.organization_bulk_skipped_heading), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
            job.skippedRecords.forEach { BulkRecordRow(it, report.names, VeilColors.Muted) }
        }
        if (job.failedRecords.isNotEmpty()) {
            Text(stringResource(R.string.organization_bulk_failed_heading), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
            job.failedRecords.forEach { BulkRecordRow(it, report.names, VeilColors.Danger) }
        }
        if (!report.running) TextButton(
            onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-bulk-dismiss"),
        ) { Text(stringResource(R.string.organization_dismiss)) }
    }
}

@Composable
private fun BulkRecordRow(record: BulkRecordResult, names: Map<Long, String>, color: androidx.compose.ui.graphics.Color) {
    val who = names[record.account_id] ?: stringResource(R.string.organization_bulk_account, record.account_id.toInt())
    val why = resultReasonRes(record.reason)?.let { stringResource(it) } ?: sentenceCase(record.reason).lowercase()
    Text(stringResource(R.string.organization_bulk_record, who, why), style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
internal fun GroupPickerDialog(
    title: String,
    groups: List<AccessGroup>,
    currentGroupId: Long,
    onPick: (AccessGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { group ->
                    val current = group.id.toLongOrNull() == currentGroupId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current, onClick = { if (!current) onPick(group) })
                        TextButton(onClick = { if (!current) onPick(group) }, enabled = !current) { Text(group.name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.organization_cancel))
            }
        },
    )
}

// ---- One person -----------------------------------------------------------------------------------------

@Composable
fun PersonDetailScreen(session: OrganizationSession, accountId: Long, onBack: () -> Unit) {
    val viewModel = viewModel<PersonDetailViewModel>(key = "organization-person-$accountId")
    val state by viewModel.state.collectAsState()
    val context by session.context.collectAsState()
    ForegroundEffect(session, accountId) { viewModel.load(session, accountId) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    PersonDetailContent(
        state = state, context = context, onBack = onBack,
        onRetry = { scope.launch { viewModel.load(session, accountId) } },
        onRefresh = { viewModel.load(session, accountId) },
        onDismiss = viewModel::dismissMessages,
        onSetSuspended = { suspend -> scope.launch { viewModel.setSuspended(session, suspend) } },
        onAssignGroup = { profile, group -> scope.launch { viewModel.assignGroup(session, profile, group) } },
        supportActions = PersonSupportActions(
            onReset = { delivery -> scope.launch { viewModel.resetPassword(session, delivery) } },
            onDismissLink = viewModel::dismissResetLink,
            onSignOutEverywhere = { scope.launch { viewModel.signOutEverywhere(session) } },
            onSignOutSession = { target -> scope.launch { viewModel.signOutSession(session, target) } },
            onSignOutDevice = { target -> scope.launch { viewModel.signOutDevice(session, target) } },
            onAddProfile = { name, group -> scope.launch { viewModel.addProfile(session, name, group) } },
            onRenameProfile = { profile, name -> scope.launch { viewModel.renameProfile(session, profile, name) } },
            onClearPin = { profile -> scope.launch { viewModel.clearPin(session, profile) } },
            onRemoveProfile = { profile -> scope.launch { viewModel.removeProfile(session, profile) } },
        ),
    )
}

@Composable
internal fun PersonDetailContent(
    state: PersonDetailState,
    context: AdminContext?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSetSuspended: (Boolean) -> Unit,
    onAssignGroup: (ProfileSummary, AccessGroup) -> Unit,
    supportActions: PersonSupportActions = PersonSupportActions(),
    onRefresh: (suspend () -> Unit)? = null,
) {
    val person = state.person
    var confirming by remember { mutableStateOf<Boolean?>(null) }
    var moving by remember { mutableStateOf<ProfileSummary?>(null) }
    var supportDialog by remember { mutableStateOf<SupportDialog?>(null) }
    OrganizationPage(
        title = person?.label ?: stringResource(R.string.organization_person_title),
        subtitle = person?.email?.takeIf { it.isNotBlank() && it != person.label },
        showTitle = true,
        loading = state.loading, empty = person == null,
        emptyMessage = stringResource(if (state.gone) R.string.organization_person_gone else R.string.organization_error_person),
        error = state.error, notice = state.notice, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
        header = {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-person-back")) {
                Text(stringResource(R.string.organization_person_back))
            }
        },
    ) {
        if (person == null) return@OrganizationPage
        val self = person.isSelf(context)
        val helpable = canHelpPerson(state, person, context)
        item {
            OrganizationCard(Modifier.testTag("organization-person-detail")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.organization_person_membership), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                    StatusLabel(
                statusLabel(person.membership_status),
                tone = if (person.isSuspended) StatusTone.Warning else StatusTone.Quiet,
            )
                }
                val tags = buildList {
                    if (person.isAdministrator) add(stringResource(R.string.organization_people_administrator))
                    if (self) add(stringResource(R.string.organization_people_you))
                }
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                Text(
                    if (person.last_activity.isNullOrBlank()) stringResource(R.string.organization_person_no_activity)
                    else stringResource(R.string.organization_people_last_active, formatWhen(person.last_activity)),
                    style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
                )
                Text(
                    stringResource(R.string.organization_person_account, person.account_id.toInt(), person.security_revision.toInt()),
                    style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted,
                )
                when {
                    state.pending -> Text(stringResource(R.string.organization_people_working), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    person.isSuspended -> ActionButton(
                        stringResource(R.string.organization_people_reactivate),
                        stringResource(R.string.organization_people_actions, person.label) + ": " + stringResource(R.string.organization_people_reactivate),
                        { confirming = false },
                    )
                    person.isActive && !self -> ActionButton(
                        stringResource(R.string.organization_people_suspend),
                        stringResource(R.string.organization_people_actions, person.label) + ": " + stringResource(R.string.organization_people_suspend),
                        { confirming = true },
                    )
                }
            }
        }
        item {
            OrganizationCard(Modifier.testTag("organization-person-profiles")) {
                Text(stringResource(R.string.organization_person_profiles), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                if (person.profiles.isEmpty()) Text(stringResource(R.string.organization_person_no_profiles), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                person.profiles.forEach { profile ->
                    val group = state.groups.firstOrNull { it.id.toLongOrNull() == profile.group_id }
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                        Text(
                            stringResource(R.string.organization_person_in_group, profile.group_name.ifBlank { "#${profile.group_id}" }),
                            style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
                        )
                        group?.let {
                            Text(groupGist(it), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                        }
                        if (profile.updated_at.isNotBlank()) Text(
                            stringResource(R.string.organization_person_profile_updated, formatWhen(profile.updated_at)),
                            style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted,
                        )
                    }
                    val moveLabel = stringResource(R.string.organization_people_move_profile, profile.name)
                    TextButton(
                        onClick = { moving = profile }, enabled = !state.pending && state.groups.isNotEmpty(),
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = moveLabel },
                    ) { Text(stringResource(R.string.organization_people_access_group)) }
                    if (helpable && state.capabilities.canEditProfiles) ProfileSupportControls(profile, state.capabilities, !state.pending) { supportDialog = it }
                }
                if (helpable && state.capabilities.profiles.add) ActionButton(
                    stringResource(R.string.organization_support_profile_add),
                    stringResource(R.string.organization_support_profile_add_for, person.label),
                    { supportDialog = SupportDialog.AddProfile }, enabled = !state.pending,
                )
            }
        }
        personSupportItems(state, person, context) { supportDialog = it }
        item {
            OrganizationCard(Modifier.testTag("organization-person-changes")) {
                Text(stringResource(R.string.organization_person_changes), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                when {
                    state.changesUnavailable -> Text(stringResource(R.string.organization_person_changes_unavailable), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                    state.changes.isEmpty() -> Text(
                        stringResource(R.string.organization_person_changes_none, state.changesLooked),
                        style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted,
                    )
                    else -> {
                        Text(
                            stringResource(R.string.organization_person_changes_among, state.changesLooked),
                            style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                        )
                        state.changes.forEach { event ->
                            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                                Text(
                                    auditActionLabelRes(event.action)?.let { stringResource(it) } ?: auditActionFallback(event.action),
                                    style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
                                )
                                val whenText = formatWhen(event.created_at)
                                Text(
                                    event.actor_account_id?.let { stringResource(R.string.organization_activity_by_account, whenText, it.toInt()) }
                                        ?: stringResource(R.string.organization_activity_by_bloem, whenText),
                                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            // Said plainly, so an administrator doesn't hunt for buttons the server never gave them.
            OrganizationCard(Modifier.testTag("organization-person-limits")) {
                Text(stringResource(R.string.organization_person_limits_title), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                Text(
                    stringResource(if (state.capabilities.hasPersonSupport) R.string.organization_person_limits_support_body else R.string.organization_person_limits_body),
                    style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted,
                )
            }
        }
    }

    confirming?.let { suspend ->
        val label = person?.label.orEmpty()
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (suspend) R.string.organization_people_suspend_title else R.string.organization_people_reactivate_title, label)) },
            text = { Text(stringResource(if (suspend) R.string.organization_people_suspend_body else R.string.organization_people_reactivate_body)) },
            confirmButton = {
                ConfirmButton(destructive = suspend, 
                    onClick = { confirming = null; onSetSuspended(suspend) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(if (suspend) R.string.organization_people_suspend else R.string.organization_people_reactivate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
    PersonSupportDialogs(supportDialog, state, person, supportActions) { supportDialog = null }
    moving?.let { profile ->
        GroupPickerDialog(
            title = stringResource(R.string.organization_people_move_title, profile.name),
            groups = state.groups, currentGroupId = profile.group_id,
            onPick = { moving = null; onAssignGroup(profile, it) },
            onDismiss = { moving = null },
        )
    }
}

/** An access group in a line: screens, profiles, picture quality. */
@Composable
internal fun groupGist(group: AccessGroup): String = buildList {
    add(stringResource(R.string.organization_groups_streams, group.max_streams.toInt()))
    add(stringResource(R.string.organization_groups_profiles, group.max_profiles.toInt()))
    add(qualityRes(PlaybackQuality.canonical(group.max_playback_quality))?.let { stringResource(it) } ?: group.max_playback_quality)
}.joinToString(" · ")
