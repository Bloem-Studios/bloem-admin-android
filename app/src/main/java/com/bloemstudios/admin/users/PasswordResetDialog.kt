package com.bloemstudios.admin.users

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.invitations.SensitiveInvitationClipboard
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.requestPasswordReset
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.organization.findActivity
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.AdminUserCapabilities
import com.bloemstudios.admin.users.detail.formatDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class PasswordResetCapabilities(val password_reset_email: Boolean = false)
/** `delivery` is required by the server and has no default here, so it is always sent (a default would be left out of the JSON). */
@Serializable data class PasswordResetRequest(val delivery: String)

/**
 * AdminPasswordReset. [reset_url] is present for `link` delivery only: a one-time bearer credential for the account's
 * password, so [toString] leaves it out and nothing here may log, persist or keep it once it has been shown.
 */
@Serializable
data class PasswordResetReceipt(
    val delivery: String,
    val delivery_status: String,
    val expires_at: String,
    val reset_url: String? = null
) {
    override fun toString(): String =
        "PasswordResetReceipt(delivery=$delivery, delivery_status=$delivery_status, expires_at=$expires_at, reset_url=${if (reset_url == null) "none" else "<redacted>"})"
}

suspend fun sendPasswordResetEmail(client: ApiClient, userId: String): PasswordResetReceipt =
    client.requestPasswordReset(userId)

const val RESET_EMAIL_TAG = "user-reset-email"
const val RESET_LINK_TAG = "user-reset-link"
const val RESET_LINK_VALUE_TAG = "user-reset-link-value"
const val RESET_LINK_COPY_TAG = "user-reset-link-copy"
const val RESET_LINK_SHARE_TAG = "user-reset-link-share"
const val RESET_LINK_DONE_TAG = "user-reset-link-done"

/**
 * Reset password: email the account a one-time link, or create one to hand over. The choices come from what the server
 * says it can do ([capabilities]). A link is shown once, in this dialog only; closing it forgets the link. After either
 * is issued, the dialog must be closed and opened again to issue another, so an uncertain answer is never repeated by a
 * tap.
 */
@Composable
internal fun PasswordResetDialog(client: ApiClient, user: AdminUser, capabilities: AdminUserCapabilities?, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ResetOutcome?>(null) }
    var submitted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val options = remember(user, capabilities) { resetOptions(user, capabilities) }
    ShellInteractionLockEffect(true)

    fun issue(choice: ResetChoice) {
        if (submitted) return
        submitted = true
        busy = true
        scope.launch {
            outcome = issuePasswordReset(client, user, choice)
            busy = false
        }
    }

    (outcome as? ResetOutcome.LinkIssued)?.let { issued ->
        ResetLinkDialog(user.username, issued.link, onDone = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.users_reset_title, user.username)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (val result = outcome) {
                    null -> {
                        Text(stringResource(R.string.user_reset_choose, user.username), color = VeilColors.Muted)
                        ResetChoiceRow(
                            title = stringResource(R.string.user_reset_email_title),
                            hint = stringResource(R.string.user_reset_email_hint, user.email),
                            state = options.email, action = stringResource(R.string.users_reset_send),
                            enabled = !busy, tag = RESET_EMAIL_TAG,
                        ) { issue(ResetChoice.Email) }
                        ResetChoiceRow(
                            title = stringResource(R.string.user_reset_link_title),
                            hint = stringResource(R.string.user_reset_link_hint),
                            state = options.link, action = stringResource(R.string.user_reset_link_title),
                            enabled = !busy, tag = RESET_LINK_TAG,
                        ) { issue(ResetChoice.Link) }
                        if (options.anyAvailable) Text(stringResource(R.string.user_reset_replaces), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    }
                    is ResetOutcome.EmailSent -> {
                        Text(stringResource(R.string.users_reset_sent))
                        Text(stringResource(R.string.users_reset_expires, formatDateTime(result.expiresAt) ?: result.expiresAt))
                    }
                    is ResetOutcome.EmailUnconfirmed -> {
                        Text(stringResource(R.string.users_reset_unknown))
                        Text(stringResource(R.string.users_reset_expires, formatDateTime(result.expiresAt) ?: result.expiresAt))
                    }
                    is ResetOutcome.Failed -> Text(result.message.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                    ResetOutcome.Uncertain -> Text(stringResource(R.string.users_reset_uncertain), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
                    is ResetOutcome.LinkIssued -> Unit
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun ResetChoiceRow(title: String, hint: String, state: ResetChoiceState, action: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    RowCard {
        Text(title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
        when (state) {
            ResetChoiceState.Available -> {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(tag)) { Text(action) }
            }
            is ResetChoiceState.Unavailable ->
                Text(stringResource(state.reason), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
    }
}

/**
 * The one-time reset link. It is copied through the sensitive clipboard and taken off it again when this closes; it is
 * held by this composition alone and never saved, so closing the dialog forgets it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResetLinkDialog(username: String, link: IssuedResetLink, onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { SensitiveInvitationClipboard(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager) }
    var copied by remember(link) { mutableStateOf(false) }
    DisposableEffect(clipboard, link) { onDispose { clipboard.clearIfOwned(link.url) } }
    if (copied) LaunchedEffect(clipboard, link) {
        delay(60_000)
        clipboard.clearIfOwned(link.url)
        copied = false
    }
    val shareTitle = stringResource(R.string.user_reset_link_share_title)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.user_reset_link_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.user_reset_link_dialog_body, username, formatDateTime(link.expiresAt) ?: link.expiresAt))
                OutlinedTextField(
                    value = link.url, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.user_reset_link_field)) },
                    modifier = Modifier.fillMaxWidth().testTag(RESET_LINK_VALUE_TAG),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    OutlinedButton(
                        onClick = { clipboard.copy(link.url); copied = true },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(RESET_LINK_COPY_TAG),
                    ) { Text(stringResource(if (copied) R.string.user_reset_link_copied else R.string.user_reset_link_copy)) }
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url)
                            val chooser = Intent.createChooser(send, shareTitle)
                            context.findActivity()?.startActivity(chooser) ?: context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(RESET_LINK_SHARE_TAG),
                    ) { Text(stringResource(R.string.user_reset_link_share)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(RESET_LINK_DONE_TAG)) {
                Text(stringResource(R.string.user_reset_link_done))
            }
        },
    )
}
