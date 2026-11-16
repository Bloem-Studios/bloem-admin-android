package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.bloemstudios.admin.R
import com.bloemstudios.admin.invitations.SensitiveInvitationClipboard
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.delay

/** What the person page asks of its view model for support; the defaults do nothing, so a page without them still draws. */
internal data class PersonSupportActions(
    val onReset: (delivery: String) -> Unit = {},
    val onDismissLink: () -> Unit = {},
    val onSignOutEverywhere: () -> Unit = {},
    val onSignOutSession: (SupportSession) -> Unit = {},
    val onSignOutDevice: (SupportDevice) -> Unit = {},
    val onAddProfile: (name: String, group: AccessGroup?) -> Unit = { _, _ -> },
    val onRenameProfile: (ProfileSummary, name: String) -> Unit = { _, _ -> },
    val onClearPin: (ProfileSummary) -> Unit = {},
    val onRemoveProfile: (ProfileSummary) -> Unit = {},
)

/** The one dialog open on the person page at a time. */
internal sealed interface SupportDialog {
    data object Reset : SupportDialog
    data object SignOutAll : SupportDialog
    data class SignOutSession(val target: SupportSession) : SupportDialog
    data class SignOutDevice(val target: SupportDevice) : SupportDialog
    data object AddProfile : SupportDialog
    data class RenameProfile(val profile: ProfileSummary) : SupportDialog
    data class ClearPin(val profile: ProfileSummary) : SupportDialog
    data class RemoveProfile(val profile: ProfileSummary) : SupportDialog
}

/** Whether the page offers [person] any support action: the person is active, is not the viewer, and the server did not protect them. */
internal fun canHelpPerson(state: PersonDetailState, person: PersonSummary, context: AdminContext?): Boolean =
    person.isActive && !person.isSelf(context) && state.protectedMessage == null

/** The sign-in help, sign-ins and devices cards, and the "can't help" note. Only what the server offers is drawn. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.personSupportItems(
    state: PersonDetailState,
    person: PersonSummary,
    context: AdminContext?,
    onDialog: (SupportDialog) -> Unit,
) {
    val caps = state.capabilities
    if (!caps.hasPersonSupport) return
    state.protectedMessage?.let { message ->
        item {
            OrganizationCard(Modifier.testTag("organization-support-protected")) {
                Text(stringResource(R.string.organization_support_protected_title), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                Text(message.resolve(), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            }
        }
    }
    if (!canHelpPerson(state, person, context)) return
    val label = person.label
    if (caps.canReset) item {
        OrganizationCard(Modifier.testTag("organization-support-reset-card")) {
            Text(stringResource(R.string.organization_support_reset_title), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
            Text(stringResource(R.string.organization_support_reset_body, label), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            ActionButton(
                stringResource(R.string.organization_support_reset_action),
                stringResource(R.string.organization_support_reset_action_for, label),
                { onDialog(SupportDialog.Reset) }, enabled = !state.pending,
            )
        }
    }
    if (caps.sessions) item {
        OrganizationCard(Modifier.testTag("organization-support-sessions")) {
            Text(stringResource(R.string.organization_support_sessions_title), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
            when {
                state.sessions.unavailable -> Text(stringResource(R.string.organization_support_sessions_unavailable), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                !state.sessions.loaded -> Text(stringResource(R.string.organization_loading), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                state.sessions.items.isEmpty() -> Text(stringResource(R.string.organization_support_sessions_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                else -> state.sessions.items.forEach { row ->
                    SessionRow(row, label, !state.pending) { onDialog(SupportDialog.SignOutSession(row)) }
                }
            }
            if (state.sessions.loaded && !state.sessions.unavailable && state.sessions.items.isNotEmpty()) ActionButton(
                stringResource(R.string.organization_support_sign_out_everywhere),
                stringResource(R.string.organization_support_sign_out_everywhere_for, label),
                { onDialog(SupportDialog.SignOutAll) }, enabled = !state.pending,
            )
        }
    }
    if (caps.devices) item {
        OrganizationCard(Modifier.testTag("organization-support-devices")) {
            Text(stringResource(R.string.organization_support_devices_title), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
            when {
                state.devices.unavailable -> Text(stringResource(R.string.organization_support_devices_unavailable), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                !state.devices.loaded -> Text(stringResource(R.string.organization_loading), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                state.devices.items.isEmpty() -> Text(stringResource(R.string.organization_support_devices_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
                else -> state.devices.items.forEach { row ->
                    DeviceRow(row, !state.pending) { onDialog(SupportDialog.SignOutDevice(row)) }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(row: SupportSession, personLabel: String, enabled: Boolean, onSignOut: () -> Unit) {
    val device = row.device_name.ifBlank { row.device_platform }
    Column(Modifier.fillMaxWidth().testTag("organization-support-session-${row.id}")) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                listOf(row.device_name, row.device_platform).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { row.id },
                style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
            )
            if (!row.profile_name.isNullOrBlank()) Text(
                stringResource(R.string.organization_support_session_profile, row.profile_name),
                style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
            )
            val seen = row.last_seen_at?.let(::formatWhen).orEmpty()
            if (seen.isNotBlank()) Text(stringResource(R.string.organization_support_session_seen, seen), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            val since = formatWhen(row.created_at)
            if (since.isNotBlank()) Text(stringResource(R.string.organization_support_session_since, since), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        ActionButton(
            stringResource(R.string.organization_support_sign_out),
            stringResource(R.string.organization_support_sign_out_of, personLabel, device.ifBlank { row.id }),
            onSignOut, enabled = enabled,
        )
    }
}

@Composable
private fun DeviceRow(row: SupportDevice, enabled: Boolean, onSignOut: () -> Unit) {
    val name = row.name.ifBlank { row.platform.ifBlank { row.device_id } }
    Column(Modifier.fillMaxWidth().testTag("organization-support-device-${row.device_id}")) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                listOf(row.name, row.platform).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { row.device_id },
                style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
            )
            if (row.profiles.isNotEmpty()) Text(
                stringResource(R.string.organization_support_device_profiles, row.profiles.joinToString(", ") { it.profile_name }),
                style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
            )
            val seen = formatWhen(row.last_seen_at)
            if (seen.isNotBlank()) Text(stringResource(R.string.organization_support_device_seen, seen), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
        ActionButton(
            stringResource(R.string.organization_support_sign_out_device),
            stringResource(R.string.organization_support_sign_out_device_of, name),
            onSignOut, enabled = enabled,
        )
    }
}

/** What a profile shows of its support state, and the buttons the server allows for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileSupportControls(
    profile: ProfileSummary,
    caps: SupportCapabilities,
    enabled: Boolean,
    onDialog: (SupportDialog) -> Unit,
) {
    val facts = buildList {
        if (profile.is_primary) add(stringResource(R.string.organization_support_profile_main))
        add(stringResource(if (profile.has_pin) R.string.organization_support_profile_pin_set else R.string.organization_support_profile_pin_none))
    }
    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        if (caps.profiles.rename) ActionButton(
            stringResource(R.string.organization_support_profile_rename),
            stringResource(R.string.organization_support_profile_rename_of, profile.name),
            { onDialog(SupportDialog.RenameProfile(profile)) }, enabled = enabled,
        )
        if (caps.profiles.clear_pin && profile.has_pin) ActionButton(
            stringResource(R.string.organization_support_profile_clear_pin),
            stringResource(R.string.organization_support_profile_clear_pin_of, profile.name),
            { onDialog(SupportDialog.ClearPin(profile)) }, enabled = enabled,
        )
        if (caps.profiles.remove && !profile.is_primary) ActionButton(
            stringResource(R.string.organization_support_profile_remove),
            stringResource(R.string.organization_support_profile_remove_of, profile.name),
            { onDialog(SupportDialog.RemoveProfile(profile)) }, enabled = enabled,
        )
    }
}

/** The dialog that is open, if any; [onClose] closes it. Confirming calls the matching action and closes it. */
@Composable
internal fun PersonSupportDialogs(
    dialog: SupportDialog?,
    state: PersonDetailState,
    person: PersonSummary?,
    actions: PersonSupportActions,
    onClose: () -> Unit,
) {
    val label = person?.label.orEmpty()
    when (dialog) {
        null -> Unit
        SupportDialog.Reset -> ResetPasswordDialog(label, state.capabilities.password_reset, onClose) { delivery -> onClose(); actions.onReset(delivery) }
        SupportDialog.SignOutAll -> ConfirmDialog(
            stringResource(R.string.organization_support_sign_out_all_title, label), stringResource(R.string.organization_support_sign_out_all_body),
            stringResource(R.string.organization_support_sign_out_everywhere), "organization-support-confirm", onClose,
        ) { onClose(); actions.onSignOutEverywhere() }
        is SupportDialog.SignOutSession -> ConfirmDialog(
            stringResource(R.string.organization_support_sign_out_title, label), stringResource(R.string.organization_support_sign_out_body),
            stringResource(R.string.organization_support_sign_out), "organization-support-confirm", onClose,
        ) { onClose(); actions.onSignOutSession(dialog.target) }
        is SupportDialog.SignOutDevice -> ConfirmDialog(
            stringResource(R.string.organization_support_sign_out_device_title, label, dialog.target.name.ifBlank { dialog.target.platform }),
            stringResource(R.string.organization_support_sign_out_device_body),
            stringResource(R.string.organization_support_sign_out_device), "organization-support-confirm", onClose,
        ) { onClose(); actions.onSignOutDevice(dialog.target) }
        SupportDialog.AddProfile -> ProfileNameDialog(
            title = stringResource(R.string.organization_support_profile_add_title, label), initial = "", confirm = stringResource(R.string.organization_support_profile_add_confirm),
            groups = state.groups, onDismiss = onClose,
        ) { name, group -> onClose(); actions.onAddProfile(name, group) }
        is SupportDialog.RenameProfile -> ProfileNameDialog(
            title = stringResource(R.string.organization_support_profile_rename_title, dialog.profile.name), initial = dialog.profile.name,
            confirm = stringResource(R.string.organization_support_profile_save), groups = emptyList(), onDismiss = onClose,
        ) { name, _ -> onClose(); actions.onRenameProfile(dialog.profile, name) }
        is SupportDialog.ClearPin -> ConfirmDialog(
            stringResource(R.string.organization_support_profile_clear_pin_title, dialog.profile.name), stringResource(R.string.organization_support_profile_clear_pin_body),
            stringResource(R.string.organization_support_profile_clear_pin), "organization-support-confirm", onClose,
        ) { onClose(); actions.onClearPin(dialog.profile) }
        is SupportDialog.RemoveProfile -> ConfirmDialog(
            stringResource(R.string.organization_support_profile_remove_title, dialog.profile.name), stringResource(R.string.organization_support_profile_remove_body),
            stringResource(R.string.organization_support_profile_remove), "organization-support-confirm", onClose,
        ) { onClose(); actions.onRemoveProfile(dialog.profile) }
    }
    state.resetLink?.let { ResetLinkDialog(it, actions.onDismissLink) }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, confirmTag: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(confirmTag)) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.organization_cancel)) }
        },
    )
}

/** Email or a one-time link, whichever the server can do. Nothing is sent until the administrator confirms. */
@Composable
private fun ResetPasswordDialog(
    label: String,
    caps: SupportResetCapabilities,
    onDismiss: () -> Unit,
    onConfirm: (delivery: String) -> Unit,
) {
    var delivery by remember { mutableStateOf(if (caps.email) ResetDelivery.EMAIL else ResetDelivery.LINK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organization_support_reset_dialog_title, label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.organization_support_reset_body, label), style = MaterialTheme.typography.bodyMedium)
                if (caps.email) DeliveryChoice(
                    ResetDelivery.EMAIL, delivery, stringResource(R.string.organization_support_reset_email),
                    stringResource(R.string.organization_support_reset_email_hint),
                ) { delivery = it }
                if (caps.link) DeliveryChoice(
                    ResetDelivery.LINK, delivery, stringResource(R.string.organization_support_reset_link),
                    stringResource(R.string.organization_support_reset_link_hint),
                ) { delivery = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(delivery) }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-reset-confirm")) {
                Text(stringResource(if (delivery == ResetDelivery.EMAIL) R.string.organization_support_reset_send else R.string.organization_support_reset_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.organization_cancel)) }
        },
    )
}

@Composable
private fun DeliveryChoice(value: String, selected: String, title: String, hint: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget)
            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .testTag("organization-support-delivery-$value"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row is the control; the radio button inside only draws its state.
        RadioButton(selected = value == selected, onClick = null)
        Column(Modifier.padding(start = VeilSpacing.Small)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
    }
}

/** The one-time reset link. It is copied through the sensitive clipboard and cleared again when this closes; never saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResetLinkDialog(link: ResetLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { SensitiveInvitationClipboard(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager) }
    var copied by remember(link) { mutableStateOf(false) }
    DisposableEffect(clipboard, link) { onDispose { clipboard.clearIfOwned(link.url) } }
    if (copied) LaunchedEffect(clipboard, link) {
        delay(60_000)
        clipboard.clearIfOwned(link.url)
        copied = false
    }
    val shareTitle = stringResource(R.string.organization_support_link_share_title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organization_support_link_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(R.string.organization_support_link_body, link.personLabel))
                OutlinedTextField(
                    value = link.url, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.organization_support_link_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("organization-support-reset-link"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    OutlinedButton(
                        onClick = { clipboard.copy(link.url); copied = true },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-link-copy"),
                    ) { Text(stringResource(if (copied) R.string.organization_support_link_copied else R.string.organization_support_link_copy)) }
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url)
                            val chooser = Intent.createChooser(send, shareTitle)
                            context.findActivity()?.startActivity(chooser) ?: context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-link-share"),
                    ) { Text(stringResource(R.string.organization_support_link_share)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-link-done")) {
                Text(stringResource(R.string.organization_support_link_done))
            }
        },
    )
}

/** A profile's name (1 to 100 characters), and for a new profile the access group it starts in. */
@Composable
private fun ProfileNameDialog(
    title: String,
    initial: String,
    confirm: String,
    groups: List<AccessGroup>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, group: AccessGroup?) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var group by remember { mutableStateOf<AccessGroup?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(PROFILE_NAME_LIMIT).filterNot(Char::isISOControl) }, singleLine = true,
                    label = { Text(stringResource(R.string.organization_support_profile_name)) },
                    supportingText = { Text(stringResource(R.string.organization_support_profile_name_count, name.length)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().testTag("organization-support-profile-name"),
                )
                if (groups.isNotEmpty()) {
                    Text(stringResource(R.string.organization_support_profile_group), style = MaterialTheme.typography.labelLarge)
                    GroupChoice(null, group == null, stringResource(R.string.organization_support_profile_group_default)) { group = null }
                    groups.forEach { option -> GroupChoice(option, group?.id == option.id, option.name) { group = option } }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, group) }, enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-support-profile-confirm"),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.organization_cancel)) }
        },
    )
}

@Composable
private fun GroupChoice(group: AccessGroup?, selected: Boolean, text: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("organization-support-group-${group?.id ?: "default"}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = VeilSpacing.Small), style = MaterialTheme.typography.bodyMedium)
    }
}

private const val PROFILE_NAME_LIMIT = 100
