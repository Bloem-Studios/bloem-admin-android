package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.invitations.SensitiveInvitationClipboard
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.OrganizationLiveChannels
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Invitations --------------------------------------------------------------------------------------

@Composable
fun OrganizationInvitationsScreen(session: OrganizationSession) {
    val viewModel = viewModel<OrganizationInvitationsViewModel>()
    val state by viewModel.state.collectAsState()
    ForegroundEffect(session) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    // Rotation keeps the link; leaving the screen releases it.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModel, activity) {
        onDispose { viewModel.onSurfaceHidden(isChangingConfigurations = activity?.isChangingConfigurations == true) }
    }
    InvitationsContent(
        state = state,
        onRetry = { scope.launch { viewModel.load(session) } },
        onRefresh = { viewModel.load(session) },
        onDismissError = viewModel::dismissError,
        onSend = { email, group, note -> viewModel.send(session, email, group, note) },
        onResend = { invitation -> scope.launch { viewModel.resend(session, invitation) } },
        onRevoke = { invitation -> scope.launch { viewModel.revoke(session, invitation) } },
        onDismissLink = viewModel::dismissClaimLink,
    )
}

@Composable
internal fun InvitationsContent(
    state: InvitationsState,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onSend: suspend (email: String, accessGroupId: Long?, note: String?) -> InvitationResult,
    onResend: (OrganizationInvitation) -> Unit,
    onRevoke: (OrganizationInvitation) -> Unit,
    onDismissLink: () -> Unit,
    onRefresh: (suspend () -> Unit)? = null,
) {
    var inviting by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<OrganizationInvitation?>(null) }
    OrganizationPage(
        title = stringResource(R.string.organization_invitations_title),
        subtitle = null,
        loading = state.loading, empty = state.invitations.isEmpty(),
        emptyMessage = stringResource(R.string.organization_invitations_none),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = onDismissError, onRefresh = onRefresh,
        header = {
            Button(
                onClick = { inviting = true }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-invite"),
            ) { Text(stringResource(R.string.organization_invitations_invite)) }
        },
    ) {
        items(state.invitations, key = { it.id }) { invitation ->
            OrganizationCard(Modifier.testTag("organization-invitation-${invitation.id}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        invitation.email.ifBlank { stringResource(R.string.organization_invitations_title) },
                        style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.weight(1f),
                    )
                    StatusLabel(invitationStatus(invitation.status))
                }
                val details = buildList {
                    if (invitation.invited_by_name.isNotBlank()) add(stringResource(R.string.organization_invitations_invited_by, invitation.invited_by_name))
                    if (invitation.isPending && invitation.expires_at.isNotBlank()) {
                        add(stringResource(R.string.organization_invitations_expires, formatWhen(invitation.expires_at)))
                    }
                    state.groups.firstOrNull { it.id == invitation.access_group_id }?.let { add(it.name) }
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                if (invitation.isPending) {
                    val who = invitation.email.ifBlank { stringResource(R.string.organization_invitations_title) }
                    Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                        ActionButton(
                            stringResource(R.string.organization_invitations_resend),
                            stringResource(R.string.organization_invitations_resend) + ": " + who,
                            { onResend(invitation) }, enabled = !state.busy,
                        )
                        ActionButton(
                            stringResource(R.string.organization_invitations_revoke),
                            stringResource(R.string.organization_invitations_revoke) + ": " + who,
                            { revoking = invitation }, enabled = !state.busy,
                        )
                    }
                }
            }
        }
    }

    if (inviting) InviteDialog(state.groups, onSend, onDismiss = { inviting = false })

    revoking?.let { invitation ->
        val who = invitation.email.ifBlank { stringResource(R.string.organization_invitations_title) }
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.organization_invitations_revoke_title)) },
            text = { Text(stringResource(R.string.organization_invitations_revoke_body, who)) },
            confirmButton = {
                DestructiveTextButton(onClick = { revoking = null; onRevoke(invitation) }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_invitations_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }

    state.claimLink?.let { ClaimLinkDialog(it, onDismissLink) }
}

@Composable
private fun invitationStatus(status: String): String = when (status) {
    "pending" -> stringResource(R.string.organization_invitations_status_pending)
    "accepted" -> stringResource(R.string.organization_invitations_status_accepted)
    "revoked" -> stringResource(R.string.organization_invitations_status_revoked)
    "expired" -> stringResource(R.string.organization_invitations_status_expired)
    else -> status.replaceFirstChar { it.uppercase() }
}

@Composable
private fun InviteDialog(
    groups: List<AccessGroup>,
    onSend: suspend (email: String, accessGroupId: Long?, note: String?) -> InvitationResult,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<ErrorText?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val defaultGroup = stringResource(R.string.organization_invitations_group_default)
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.organization_invitations_invite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true,
                    label = { Text(stringResource(R.string.organization_invitations_email)) },
                    placeholder = { Text(stringResource(R.string.organization_invitations_email_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("organization-invite-email"),
                )
                if (groups.isNotEmpty()) FilterMenu(
                    label = stringResource(R.string.organization_invitations_group),
                    selected = groups.firstOrNull { it.id == groupId }?.name ?: defaultGroup,
                    options = listOf<Pair<String?, String>>(null to defaultGroup) + groups.map { it.id to it.name },
                    onSelect = { groupId = it },
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(stringResource(R.string.organization_invitations_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && email.isNotBlank(),
                onClick = {
                    scope.launch {
                        submitting = true
                        error = null
                        when (val result = onSend(email, groupId?.toLongOrNull(), note)) {
                            is InvitationResult.Sent -> onDismiss()
                            is InvitationResult.Failed -> error = result.message
                        }
                        submitting = false
                    }
                },
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-invite-create"),
            ) { Text(stringResource(if (submitting) R.string.organization_invitations_creating else R.string.organization_invitations_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.organization_cancel))
            }
        },
    )
}

/** The one-time link. It is copied through the sensitive clipboard and cleared again when this closes. */
@Composable
private fun ClaimLinkDialog(link: ClaimLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { SensitiveInvitationClipboard(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager) }
    var copied by remember(link) { mutableStateOf(false) }
    DisposableEffect(clipboard, link) { onDispose { clipboard.clearIfOwned(link.url) } }
    if (copied) LaunchedEffect(clipboard, link) {
        delay(60_000)
        clipboard.clearIfOwned(link.url)
        copied = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organization_invitations_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.organization_invitations_link_body))
                OutlinedTextField(
                    value = link.url, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.organization_invitations_link_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("organization-claim-link"),
                )
                TextButton(
                    onClick = { clipboard.copy(link.url); copied = true },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                ) { Text(stringResource(if (copied) R.string.organization_invitations_copied else R.string.organization_invitations_copy)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.organization_invitations_done))
            }
        },
    )
}

internal tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---- Activity & Audit ---------------------------------------------------------------------------------

@Composable
fun OrganizationActivityScreen(session: OrganizationSession) {
    val viewModel = viewModel<OrganizationActivityViewModel>()
    val state by viewModel.state.collectAsState()
    OrganizationLiveEffect(session, OrganizationLiveChannels.ACTIVITY) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    ActivityContent(state, onRetry = { scope.launch { viewModel.load(session) } }, onRefresh = { viewModel.load(session) }, onOlder = { scope.launch { viewModel.loadOlder(session) } })
}

@Composable
internal fun ActivityContent(state: OrganizationActivityState, onRetry: () -> Unit, onRefresh: (suspend () -> Unit)? = null, onOlder: () -> Unit) {
    OrganizationPage(
        title = stringResource(R.string.organization_activity_title),
        subtitle = null,
        loading = state.loading, empty = state.events.isEmpty(),
        emptyMessage = stringResource(R.string.organization_activity_none),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = {}, onRefresh = onRefresh,
    ) {
        items(state.events, key = { "${it.source}:${it.id}" }) { event ->
            OrganizationCard(Modifier.testTag("organization-event-${event.id}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        auditActionLabelRes(event.action)?.let { stringResource(it) } ?: auditActionFallback(event.action),
                        style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.weight(1f),
                    )
                    if (!event.isSuccess && event.outcome.isNotBlank()) {
                        StatusLabel(
                            auditOutcomeLabelRes(event.outcome)?.let { stringResource(it) } ?: event.outcome.replace('_', ' '),
                            tone = StatusTone.Danger,
                        )
                    }
                }
                val whenText = formatWhen(event.created_at)
                Text(
                    when (val who = event.attribution()) {
                        AuditAttribution.PlatformSupport -> stringResource(R.string.organization_activity_by_platform_support, whenText)
                        is AuditAttribution.Account -> stringResource(R.string.organization_activity_by_account, whenText, who.id.toInt())
                        AuditAttribution.Bloem -> stringResource(R.string.organization_activity_by_bloem, whenText)
                    },
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
                event.reasonToShow?.let { reason ->
                    // The reason a support access was opened with, as the operator wrote it.
                    Text(stringResource(R.string.organization_activity_reason, reason), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.organization_activity_technical, event.action, event.target_id),
                    style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted,
                )
            }
        }
        if (state.nextCursor != null) item {
            TextButton(
                onClick = onOlder, enabled = !state.loadingOlder,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-activity-older"),
            ) { Text(stringResource(if (state.loadingOlder) R.string.organization_loading else R.string.organization_activity_show_older)) }
        }
    }
}
