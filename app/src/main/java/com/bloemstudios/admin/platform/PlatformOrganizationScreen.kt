package com.bloemstudios.admin.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.organization.Fact
import com.bloemstudios.admin.organization.OrganizationCard
import com.bloemstudios.admin.organization.OrganizationPage
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.organization.ReasonDialog
import com.bloemstudios.admin.organization.SupportAccessGrants
import com.bloemstudios.admin.organization.formatWhen
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.ConfirmButton
import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.Stat
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * One organization of Platform › Organizations: status, owner, counts, members, suspend and reactivate, the plan, and, when
 * the account may, "Open as administrator". [onOpenOrganization] enters the organization (it is told the organization's id); an
 * organization the Owner is not a member of is opened under support access, which first asks for a reason and records it for
 * [serverId].
 */
@Composable
fun PlatformOrganizationScreen(
    session: PlatformSession,
    account: ApiClient,
    serverId: String,
    organizationId: String,
    supportAvailable: Boolean,
    onClose: () -> Unit,
    onOpenOrganization: (organizationId: String) -> Unit,
) {
    val viewModel = viewModel<PlatformOrganizationViewModel>(key = "platform-organization-$organizationId")
    val planModel = viewModel<OrganizationPlanViewModel>(key = "platform-plan-$organizationId")
    val state by viewModel.state.collectAsState()
    val plan by planModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val words = remember(context) { PlanWords(context.resources) }
    ForegroundEffect(session, organizationId) {
        viewModel.load(session, account, organizationId, supportAvailable)
        planModel.load(session, organizationId)
    }
    PlatformOrganizationContent(
        state = state, plan = plan, words = words,
        onBack = onClose,
        onOpen = { reason ->
            // Support access states its reason first; a membership needs none.
            if (reason != null) SupportAccessGrants.start(serverId, organizationId, reason)
            onOpenOrganization(organizationId)
        },
        onSetSuspended = { suspended -> scope.launch { viewModel.setSuspended(session, suspended) } },
        onMoreMembers = { scope.launch { viewModel.loadMoreMembers(session) } },
        onSelectPlan = planModel::select,
        onPreviewPlan = { scope.launch { planModel.preview(session, organizationId) } },
        onApplyPlan = { name -> scope.launch { planModel.apply(session, organizationId, name) } },
        onRetry = { scope.launch { viewModel.load(session, account, organizationId, supportAvailable); planModel.load(session, organizationId) } },
        onDismiss = viewModel::dismissMessages,
        onDismissPlan = planModel::dismissMessages,
        onRefresh = {
            viewModel.load(session, account, organizationId, supportAvailable)
            planModel.load(session, organizationId)
        },
    )
}

private enum class Confirm { Suspend, Reactivate }

@Composable
internal fun PlatformOrganizationContent(
    state: PlatformOrganizationState,
    plan: OrganizationPlanState,
    words: PlanWords,
    onBack: () -> Unit,
    onOpen: (supportReason: String?) -> Unit,
    onSetSuspended: (Boolean) -> Unit,
    onMoreMembers: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onPreviewPlan: () -> Unit,
    onApplyPlan: (organizationName: String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDismissPlan: () -> Unit,
    onRefresh: (suspend () -> Unit)? = null,
) {
    val organization = state.organization
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var askingReason by remember { mutableStateOf(false) }
    OrganizationPage(
        title = organization?.name.orEmpty(), subtitle = null, showTitle = false,
        loading = state.loading, empty = organization == null,
        emptyMessage = stringResource(if (state.notFound) R.string.platform_org_not_found else R.string.platform_org_load_error),
        error = state.error, notice = state.notice?.let { ErrorText.Raw(it.text()) },
        onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
        header = {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-org-back")) {
                Text(stringResource(R.string.platform_org_back))
            }
            if (organization != null) {
                Text(
                    organization.name, style = MaterialTheme.typography.titleLarge, color = VeilColors.Text,
                    modifier = Modifier.semantics { heading() },
                )
                if (organization.slug.isNotBlank()) Text(organization.slug, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            }
        },
    ) {
        if (organization == null) return@OrganizationPage
        item { Counts(organization) }
        item { About(organization, state) }
        item {
            Actions(
                organization = organization, state = state,
                onOpen = { if (state.openAccess == OpenAccess.Support) askingReason = true else onOpen(null) },
                onSuspend = { confirm = Confirm.Suspend },
                onReactivate = { confirm = Confirm.Reactivate },
            )
        }
        if (plan.available) item {
            OrganizationPlanSection(
                plan = plan, organizationName = organization.name, words = words,
                onSelect = onSelectPlan, onPreview = onPreviewPlan, onApply = { onApplyPlan(organization.name) }, onDismiss = onDismissPlan,
            )
        }
        item {
            Text(
                stringResource(R.string.platform_org_members), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.membersError) item {
            Text(stringResource(R.string.platform_org_members_error), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Danger)
        }
        if (state.members.isEmpty() && !state.membersError) item {
            Text(stringResource(R.string.platform_org_members_empty), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        }
        items(state.members, key = { it.id }) { member -> MemberRow(member, isOwner = member.account_id == organization.owner_account_id) }
        if (state.membersNext != null) item {
            TextButton(
                onClick = onMoreMembers, enabled = !state.loadingMoreMembers,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-org-members-more"),
            ) { Text(stringResource(if (state.loadingMoreMembers) R.string.organization_loading else R.string.platform_org_members_more)) }
        }
    }
    val name = organization?.name.orEmpty()
    when (confirm) {
        Confirm.Suspend -> ConfirmDialog(
            title = stringResource(R.string.platform_org_suspend_title, name),
            body = organization?.let { suspendBody(it) }.orEmpty(),
            confirm = stringResource(R.string.platform_org_suspend_confirm), destructive = true, tag = "platform-org-suspend-confirm",
            onDismiss = { confirm = null }, onConfirm = { confirm = null; onSetSuspended(true) },
        )
        Confirm.Reactivate -> ConfirmDialog(
            title = stringResource(R.string.platform_org_reactivate_title, name),
            body = stringResource(R.string.platform_org_reactivate_body, name),
            confirm = stringResource(R.string.platform_org_reactivate_confirm), destructive = false, tag = "platform-org-reactivate-confirm",
            onDismiss = { confirm = null }, onConfirm = { confirm = null; onSetSuspended(false) },
        )
        null -> Unit
    }
    if (askingReason) ReasonDialog(name, onDismiss = { askingReason = false }) { reason -> askingReason = false; onOpen(reason) }
}

/** What suspending does to the organization's people, stated before anything is sent. */
@Composable
private fun suspendBody(organization: OrganizationSummary): String {
    val active = organization.active_membership_count.toInt()
    return if (active > 0) pluralStringResource(R.plurals.platform_org_suspend_body, active, active, organization.name)
    else stringResource(R.string.platform_org_suspend_body_none, organization.name)
}

@Composable
private fun PlatformNotice.text(): String = when (this) {
    is PlatformNotice.Suspended -> stringResource(R.string.platform_org_suspended_notice, organization)
    is PlatformNotice.Reactivated -> stringResource(R.string.platform_org_reactivated_notice, organization)
    PlatformNotice.Changed -> stringResource(R.string.platform_org_changed)
    is PlatformNotice.PlanApplied -> stringResource(R.string.platform_plan_applied, organization, plan)
    is PlatformNotice.PlanUnchanged -> stringResource(R.string.platform_plan_applied_nothing, organization, plan)
}

@Composable
private fun ConfirmDialog(
    title: String, body: String, confirm: String, destructive: Boolean, tag: String, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            ConfirmButton(onClick = onConfirm, destructive = destructive, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(tag)) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.platform_org_cancel)) }
        },
    )
}

@Composable
private fun Counts(organization: OrganizationSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatCard(
                stringResource(R.string.platform_org_people), organization.membership_count,
                stringResource(R.string.platform_org_active_people, organization.active_membership_count.toInt()), Modifier.weight(1f).fillMaxHeight(),
            )
            StatCard(stringResource(R.string.platform_org_profiles), organization.profile_count, null, Modifier.weight(1f).fillMaxHeight())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatCard(stringResource(R.string.platform_org_own_libraries), organization.library_count, null, Modifier.weight(1f).fillMaxHeight())
            StatCard(stringResource(R.string.platform_org_shared_libraries), organization.entitlement_count, null, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun StatCard(label: String, value: Long, detail: String?, modifier: Modifier = Modifier) {
    RowCard(modifier.semantics(mergeDescendants = true) {}) {
        Stat(label, NumberFormat.getIntegerInstance().format(value), detail = detail, large = true)
    }
}

@Composable
private fun About(organization: OrganizationSummary, state: PlatformOrganizationState) {
    OrganizationCard(Modifier.testTag("platform-org-about")) {
        Text(stringResource(R.string.platform_org_about), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
        Fact(stringResource(R.string.platform_org_status), statusText(organization.status))
        Fact(
            stringResource(R.string.platform_org_owner),
            when {
                organization.owner_account_id == null -> stringResource(R.string.platform_org_no_owner)
                state.ownerName != null -> state.ownerName
                state.ownerResolved -> stringResource(R.string.platform_org_owner_unknown)
                else -> stringResource(R.string.organization_loading)
            },
        )
        if (organization.slug.isNotBlank()) Fact(stringResource(R.string.platform_org_short_name), organization.slug)
    }
}

@Composable
private fun statusText(status: String): String = when (status) {
    "active" -> stringResource(R.string.platform_orgs_status_active)
    "suspended" -> stringResource(R.string.platform_orgs_status_suspended)
    "initializing" -> stringResource(R.string.platform_orgs_status_initializing)
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun Actions(
    organization: OrganizationSummary,
    state: PlatformOrganizationState,
    onOpen: () -> Unit,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (state.openAccess != OpenAccess.None) Button(
            onClick = onOpen, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-org-open"),
        ) { Text(stringResource(R.string.platform_org_open)) }
        if (organization.status == "suspended") OutlinedButton(
            onClick = onReactivate, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-org-reactivate"),
        ) { Text(stringResource(R.string.platform_org_reactivate)) }
        else if (organization.status == "active") OutlinedButton(
            onClick = onSuspend, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-org-suspend"),
        ) { Text(stringResource(R.string.platform_org_suspend), color = VeilColors.Danger) }
    }
}

@Composable
private fun MemberRow(member: PlatformMembership, isOwner: Boolean) {
    val role = stringResource(
        when {
            isOwner -> R.string.platform_org_role_owner
            member.isAdministrator -> R.string.platform_org_role_admin
            else -> R.string.platform_org_role_member
        },
    )
    val statusText = stringResource(
        when (member.status) {
            "active" -> R.string.platform_org_member_active
            "invited" -> R.string.platform_org_member_invited
            "suspended" -> R.string.platform_org_member_suspended
            else -> R.string.platform_org_member_invited
        },
    )
    ListRowCard(
        title = member.label,
        supporting = listOf(member.email.takeIf { member.username.isNotBlank() }.orEmpty(), role),
        modifier = Modifier.testTag("platform-member-${member.id}"),
        trailing = {
            StatusLabel(
                if (member.status == "active" || member.status == "invited" || member.status == "suspended") statusText else member.status,
                tone = if (member.status == "suspended") StatusTone.Warning else StatusTone.Quiet, dot = member.status == "suspended",
            )
        },
    )
}

// ---- Plan -------------------------------------------------------------------------------------------

@Composable
internal fun OrganizationPlanSection(
    plan: OrganizationPlanState,
    organizationName: String,
    words: PlanWords,
    onSelect: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    OrganizationCard(Modifier.testTag("platform-plan")) {
        Text(
            stringResource(R.string.platform_plan_title), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
            modifier = Modifier.semantics { heading() },
        )
        plan.notice?.let { MessageBanner(it.text(), isError = false, onDismiss = onDismiss) }
        plan.error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = onDismiss) }
        val entitlement = plan.entitlement
        if (entitlement == null) {
            if (plan.loading) Text(stringResource(R.string.organization_loading), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
        } else {
            Fact(
                stringResource(R.string.platform_plan_current),
                if (entitlement.hasPlan) words.planName(entitlement.template_key, entitlement.template_revision, plan.templates)
                else stringResource(R.string.platform_plan_none),
            )
            Fact(
                stringResource(R.string.platform_plan_limits),
                stringResource(
                    R.string.platform_plan_limits_value,
                    pluralStringResource(R.plurals.platform_policy_screens, entitlement.tenant_limits.slots, entitlement.tenant_limits.slots),
                    pluralStringResource(R.plurals.platform_policy_conversions, entitlement.tenant_limits.transcodes, entitlement.tenant_limits.transcodes),
                ),
            )
            Fact(stringResource(R.string.platform_plan_libraries), words.libraries(entitlement.library_ids))
            Fact(
                stringResource(R.string.platform_plan_last_applied),
                entitlement.last_reconciled_at?.let(::formatWhen)?.takeIf(String::isNotBlank) ?: stringResource(R.string.platform_plan_not_yet),
            )
            Text(
                stringResource(R.string.platform_plan_member_defaults), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
            )
            val group = entitlement.managed_default_group
            if (group == null) {
                Text(stringResource(R.string.platform_plan_no_member_defaults), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
            } else {
                Text(group.name, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
                words.policyRows(group.policy).forEach { (label, value) -> Fact(label, value) }
            }
            Text(stringResource(R.string.platform_plan_intro), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            when {
                plan.templatesError -> Text(stringResource(R.string.platform_plan_plans_error), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Danger)
                plan.templates.isEmpty() -> Text(stringResource(R.string.platform_plan_no_plans), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                else -> {
                    Text(stringResource(R.string.platform_plan_choose), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
                    plan.templates.forEach { template ->
                        PlanChoice(
                            label = words.planName(template.key, template.revision, plan.templates),
                            selected = plan.selectedKey == template.key, onSelect = { onSelect(template.key) },
                            tag = "platform-plan-choice-${template.key}",
                        )
                    }
                    if (entitlement.hasPlan && plan.templates.none { it.key == entitlement.template_key }) {
                        Text(
                            stringResource(R.string.platform_plan_current_unavailable, entitlement.template_key),
                            style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning,
                        )
                    }
                    OutlinedButton(
                        onClick = onPreview, enabled = plan.selectedKey != null && !plan.previewing && !plan.applying,
                        modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-plan-preview"),
                    ) { Text(stringResource(if (plan.previewing) R.string.platform_plan_checking else R.string.platform_plan_preview)) }
                }
            }
            plan.preview?.let { preview ->
                PreviewCard(preview, words, plan.templates, plan.applying, onApply = { confirming = true })
            }
        }
    }
    val preview = plan.preview
    if (confirming && preview != null) {
        val lines = words.changeLines(preview.dryRun.changes, plan.templates)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.platform_plan_confirm_title, organizationName, preview.template.label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text) }
                    Text(stringResource(R.string.platform_plan_confirm_body), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirming = false; onApply() },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-plan-apply-confirm"),
                ) { Text(stringResource(R.string.platform_plan_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.platform_org_cancel))
                }
            },
        )
    }
}

@Composable
private fun PlanChoice(label: String, selected: Boolean, onSelect: () -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
    }
}

/** What the preview found: the changes in plain words, or that there are none. Only a preview with changes can be applied. */
@Composable
private fun PreviewCard(preview: PlanPreview, words: PlanWords, templates: List<EntitlementTemplate>, applying: Boolean, onApply: () -> Unit) {
    Column(
        Modifier.testTag("platform-plan-changes"), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        Text(
            stringResource(if (preview.dryRun.changed) R.string.platform_plan_changes else R.string.platform_plan_no_changes),
            style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
        )
        words.changeLines(preview.dryRun.changes, templates).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
        }
        preview.dryRun.warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Warning) }
        if (preview.dryRun.changed) Button(
            onClick = onApply, enabled = !applying,
            modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-plan-apply"),
        ) { Text(stringResource(R.string.platform_plan_apply)) }
    }
}
