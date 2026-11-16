package com.bloemstudios.admin.invitations

import com.bloemstudios.admin.ui.invitationStatusLabelRes
import com.bloemstudios.admin.ui.roleLabelRes
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.users.detail.formatDateTime
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.MessageBanner
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.VeilDialog
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.realtime.ForegroundEffect
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.users.ROLES
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Opened from a toolbar button on UsersScreen -- single-server only,
// deliberately not wired into the "All Servers" aggregate view (that view
// only has read-only cross-server rollups today; inviting a user is
// necessarily scoped to one server's own invite-link/email config).
// Presented full-screen (usePlatformDefaultWidth = false) rather than as
// the compact Surface UserSessionsDialog uses, since this is a whole list
// screen with its own create flow, not a small per-row detail popup.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationsScreen(client: ApiClient, onDismiss: () -> Unit) {
    val viewModel = viewModel<InvitationsViewModel>()
    val invitations by viewModel.invitations.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val pendingActionResult by viewModel.pendingActionResult.collectAsState()
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    // See ServerConfigScreen's identical flag for why this exists: only
    // gates the initial loading spinner (distinguishing "still loading"
    // from "genuinely no invitations"), not every poll tick.
    var hasLoadedOnce by remember { mutableStateOf(false) }

    var showingAddInvitation by remember { mutableStateOf(false) }
    var invitationPendingRevoke by remember { mutableStateOf<Invitation?>(null) }
    // The claim_url/delivery_status result of the most recent send/resend --
    // this is the ONLY time the server ever returns the actual invite link
    // (it only stores a hash after this), so it must be surfaced here
    // rather than relied on to reappear from a later load().
    var resendError by remember { mutableStateOf<ErrorText?>(null) }
    val closeSurface = {
        viewModel.closeSurface()
        onDismiss()
    }

    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModel, activity) {
        onDispose {
            viewModel.onSurfaceHidden(isChangingConfigurations = activity?.isChangingConfigurations == true)
        }
    }

    ForegroundEffect(client) {
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

    Dialog(onDismissRequest = closeSurface, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = VeilColors.Canvas) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullState.nestedScrollConnection)
            ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal),
                verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.invitations_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                        Button(onClick = { showingAddInvitation = true }) { Text(stringResource(R.string.invitations_add_invitation)) }
                        TextButton(onClick = closeSurface) { Text(stringResource(R.string.action_close)) }
                    }
                }
                error?.let { MessageBanner(it.resolve(), isError = true, onDismiss = {}) }
                resendError?.let { MessageBanner(it.resolve(), isError = true, onDismiss = { resendError = null }) }

                if (!hasLoadedOnce) {
                    VeilStateSurface(
                        kind = VeilStateSurfaceKind.Loading, title = "", message = "",
                        stateDescription = stringResource(R.string.management_loading),
                    )
                } else if (invitations.isEmpty()) {
                    VeilStateSurface(
                        kind = VeilStateSurfaceKind.Empty, title = stringResource(R.string.invitations_none), message = "",
                        stateDescription = stringResource(R.string.invitations_none),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                        items(invitations) { invitation ->
                            RowCard {
                                Text(
                                    invitation.email.ifEmpty { stringResource(R.string.invitations_link_invitation) },
                                    style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
                                )
                                Text(
                                    "${codeLabel(invitation.role, ::roleLabelRes)} · " +
                                        "${codeLabel(invitation.status, ::invitationStatusLabelRes)} · " +
                                        stringResource(R.string.invitations_expires, formatDateTime(invitation.expires_at) ?: invitation.expires_at) +
                                        if (invitation.delivery == "email_unconfirmed") " · " + stringResource(R.string.invitations_email_unconfirmed) else "",
                                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            resendError = null
                                            when (val result = viewModel.resend(invitation, client)) {
                                                is InvitationActionResult.Success -> Unit
                                                is InvitationActionResult.Failure -> resendError = result.message
                                            }
                                        }
                                    }) { Text(stringResource(R.string.invitations_resend)) }
                                    TextButton(onClick = { invitationPendingRevoke = invitation }) {
                                        Text(stringResource(R.string.action_revoke))
                                    }
                                }
                            }
                        }
                    }
                }
            }

                PullToRefreshContainer(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }

    if (showingAddInvitation) {
        AddInvitationDialog(
            onDismiss = { showingAddInvitation = false },
            onSend = { email, role, note ->
                viewModel.send(email, role, note, client)
            }
        )
    }

    invitationPendingRevoke?.let { invitation ->
        AlertDialog(
            onDismissRequest = { invitationPendingRevoke = null },
            title = { Text(stringResource(R.string.invitations_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.invitations_revoke_confirm_body, invitation.email.ifEmpty { stringResource(R.string.invitations_link_invitation) })) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    val target = invitation
                    invitationPendingRevoke = null
                    scope.launch {
                        when (val result = viewModel.revoke(target, client)) {
                            is RevokeInvitationResult.Success -> resendError = null
                            is RevokeInvitationResult.Failure -> resendError = result.message
                        }
                    }
                }) { Text(stringResource(R.string.action_revoke)) }
            },
            dismissButton = { TextButton(onClick = { invitationPendingRevoke = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    pendingActionResult?.let { result ->
        InvitationLinkDialog(result = result, onDismiss = viewModel::consumePendingActionResult)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun AddInvitationDialog(
    onDismiss: () -> Unit,
    onSend: suspend (email: String, role: String, note: String?) -> InvitationActionResult
) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ROLES.first()) }
    var note by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<ErrorText?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    VeilDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.invitations_dialog_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.action_email)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        SegmentedControl(
            options = ROLES.map { codeLabel(it, ::roleLabelRes) },
            selectedIndex = ROLES.indexOf(role),
            onSelect = { role = ROLES[it] },
        )
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.invitations_note_label)) },
            modifier = Modifier.fillMaxWidth())
        errorMessage?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            Button(
                enabled = !isSubmitting && email.isNotBlank(),
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        errorMessage = null
                        when (val result = onSend(email, role, note.ifBlank { null })) {
                            is InvitationActionResult.Success -> onDismiss()
                            is InvitationActionResult.Failure -> errorMessage = result.message
                        }
                        isSubmitting = false
                    }
                }
            ) { Text(stringResource(R.string.invitations_send)) }
        }
    }
}

// Surfaces the one-time claim_url/delivery_status payload from a successful send/resend. A delivery
// that failed or could not be confirmed is called out in red (not just implied by the link being
// present): the admin must hand the link over some other way, and the server never shows it again.
@Composable
private fun InvitationLinkDialog(result: InvitationActionResult.Success, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        SensitiveInvitationClipboard(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
    }
    var copied by remember(result) { mutableStateOf(false) }

    result.claimUrl?.let { claimUrl ->
        DisposableEffect(clipboard, claimUrl) {
            onDispose { clipboard.clearIfOwned(claimUrl) }
        }
        if (copied) LaunchedEffect(clipboard, claimUrl) {
            delay(60_000)
            clipboard.clearIfOwned(claimUrl)
            copied = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when {
                        result.emailSent -> R.string.invitations_result_title
                        result.deliveryFailed -> R.string.invitations_failed_title
                        else -> R.string.invitations_link_ready_title
                    }
                )
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        when (result.deliveryStatus) {
                            InvitationDelivery.DELIVERY_SENT -> R.string.invitations_email_sent
                            InvitationDelivery.DELIVERY_NOT_CONFIGURED -> R.string.invitations_email_not_configured
                            InvitationDelivery.DELIVERY_NOT_REQUESTED -> R.string.invitations_link_only
                            else -> R.string.invitations_email_failed
                        }
                    ),
                    color = if (result.deliveryFailed) VeilColors.Danger else VeilColors.Text
                )
                result.claimUrl?.let { claimUrl ->
                    Text(stringResource(R.string.invitations_claim_url_label), modifier = Modifier.padding(top = VeilSpacing.Medium))
                    OutlinedTextField(value = claimUrl, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        clipboard.copy(claimUrl)
                        copied = true
                    }) {
                        Text(stringResource(if (copied) R.string.invitations_link_copied else R.string.invitations_copy_link))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
