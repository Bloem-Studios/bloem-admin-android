package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ui.components.LucideIcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import kotlinx.coroutines.launch

const val PROFILES_ADD_TAG = "user-profiles-add"
const val PROFILE_ROW_TAG_PREFIX = "user-profile-row:"
const val PROFILE_MENU_TAG_PREFIX = "user-profile-menu:"
const val PROFILE_NAME_FIELD_TAG = "user-profile-name"
const val PROFILE_PIN_FIELD_TAG = "user-profile-pin"
const val PROFILE_CONFIRM_TAG = "user-profile-confirm"

/** What a profile's menu or a dialog is working on. */
private sealed interface ProfileDialog {
    data object Add : ProfileDialog
    data class Rename(val profile: UserProfile) : ProfileDialog
    data class Pin(val profile: UserProfile) : ProfileDialog
    data class RemovePin(val profile: UserProfile) : ProfileDialog
    data class Delete(val profile: UserProfile) : ProfileDialog
}

/**
 * Profiles (platform mode): the account's profiles with add, rename, set or remove a PIN and delete, as the web's
 * Profiles tab. Writes go through `/api/v2/admin/users/{id}/profiles`, which the server offers when its
 * `profile_management` capability is on. [manageable] is the owner rule: only the server owner changes the owner's or
 * another admin's profiles, so anyone else sees them read-only.
 */
@Composable
internal fun UserProfilesTab(user: AdminUser, admin: UserAdmin, manageable: Boolean) {
    val client = admin.client
    val model = remember(client, user.id) { UserProfilesViewModel(admin.serverName) }
    ForegroundOnceEffect(model) { model.load(client, user.id) }
    val reads = rememberForegroundReadLauncher()
    val scope = rememberCoroutineScope()
    val section by model.profiles.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val notice by model.notice.collectAsState()
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(stringResource(R.string.user_tab_profiles), style = MaterialTheme.typography.titleSmall)
        if (manageable) Button(
            onClick = { model.dismissMessages(); dialog = ProfileDialog.Add },
            enabled = !busy,
            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PROFILES_ADD_TAG),
        ) { Text(stringResource(R.string.user_profiles_add)) }
        notice?.let { Text(stringResource(it.messageRes()), color = VeilColors.Ok, modifier = Modifier.testTag("user-profiles-notice")) }
        error?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
        SectionBody(section, onRetry = { reads.launch { model.load(client, user.id) } }) { profiles ->
            if (profiles.isEmpty()) Text(stringResource(R.string.user_profiles_none), color = VeilColors.Muted)
            profiles.forEach { profile ->
                ProfileRow(profile, manageable = manageable, busy = busy) { dialog = it }
            }
        }
        if (!manageable) Text(stringResource(R.string.user_profiles_read_only_owner), color = VeilColors.Muted, style = MaterialTheme.typography.bodySmall)
    }

    ProfileDialogs(dialog, busy, onDismiss = { dialog = null }) { action ->
        scope.launch {
            val done = when (action) {
                is ProfileAction.Add -> model.add(client, user.id, action.name)
                is ProfileAction.Rename -> model.rename(client, user.id, action.profile.id, action.name)
                is ProfileAction.SetPin -> model.setPin(client, user.id, action.profile.id, action.pin)
                is ProfileAction.RemovePin -> model.removePin(client, user.id, action.profile.id)
                is ProfileAction.Delete -> model.delete(client, user.id, action.profile.id)
            }
            if (done || model.error.value != null) dialog = null
        }
    }
}

private fun ProfileNotice.messageRes(): Int = when (this) {
    ProfileNotice.Added -> R.string.user_profiles_notice_added
    ProfileNotice.Renamed -> R.string.user_profiles_notice_renamed
    ProfileNotice.PinSet -> R.string.user_profiles_notice_pin_set
    ProfileNotice.PinRemoved -> R.string.user_profiles_notice_pin_removed
    ProfileNotice.Deleted -> R.string.user_profiles_notice_deleted
}

private sealed interface ProfileAction {
    data class Add(val name: String) : ProfileAction
    data class Rename(val profile: UserProfile, val name: String) : ProfileAction
    data class SetPin(val profile: UserProfile, val pin: String) : ProfileAction
    data class RemovePin(val profile: UserProfile) : ProfileAction
    data class Delete(val profile: UserProfile) : ProfileAction
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileRow(profile: UserProfile, manageable: Boolean, busy: Boolean, onChoose: (ProfileDialog) -> Unit) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }
    RowCard(Modifier.testTag(PROFILE_ROW_TAG_PREFIX + profile.id)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    if (profile.is_primary) StatusLabel(stringResource(R.string.user_profiles_badge_primary))
                    if (profile.is_child) StatusLabel(stringResource(R.string.user_profiles_badge_kids))
                    if (profile.has_pin) StatusLabel(stringResource(R.string.user_profiles_badge_pin))
                }
                Text(
                    formatDate(profile.last_seen_at)?.let { stringResource(R.string.user_profiles_last_seen, it) }
                        ?: stringResource(R.string.user_profiles_never_seen),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
            }
            if (manageable) Box {
                IconButton(
                    onClick = { menuOpen = true }, enabled = !busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PROFILE_MENU_TAG_PREFIX + profile.id),
                ) { LucideIcon(R.drawable.lucide_ellipsis, contentDescription = stringResource(R.string.user_profiles_actions_for, profile.name)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    @Composable
                    fun item(label: Int, dialog: ProfileDialog, color: androidx.compose.ui.graphics.Color = VeilColors.Text) =
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = color) },
                            onClick = { menuOpen = false; onChoose(dialog) },
                            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                        )
                    item(R.string.user_profiles_rename, ProfileDialog.Rename(profile))
                    item(if (profile.has_pin) R.string.user_profiles_change_pin else R.string.user_profiles_set_pin, ProfileDialog.Pin(profile))
                    if (profile.has_pin) item(R.string.user_profiles_remove_pin, ProfileDialog.RemovePin(profile))
                    if (!profile.is_primary) item(R.string.user_profiles_delete, ProfileDialog.Delete(profile), VeilColors.Danger)
                }
            }
        }
    }
}

@Composable
private fun ProfileDialogs(dialog: ProfileDialog?, busy: Boolean, onDismiss: () -> Unit, onAct: (ProfileAction) -> Unit) {
    if (dialog == null) return
    ShellInteractionLockEffect(true)
    when (dialog) {
        ProfileDialog.Add -> NameDialog(
            title = stringResource(R.string.user_profiles_add_title), initial = "", confirm = stringResource(R.string.user_profiles_add_confirm),
            busy = busy, onDismiss = onDismiss, onConfirm = { onAct(ProfileAction.Add(it)) },
        )
        is ProfileDialog.Rename -> NameDialog(
            title = stringResource(R.string.user_profiles_rename_title, dialog.profile.name), initial = dialog.profile.name,
            confirm = stringResource(R.string.user_profiles_save), busy = busy, onDismiss = onDismiss,
            onConfirm = { onAct(ProfileAction.Rename(dialog.profile, it)) },
        )
        is ProfileDialog.Pin -> PinDialog(dialog.profile, busy, onDismiss) { onAct(ProfileAction.SetPin(dialog.profile, it)) }
        is ProfileDialog.RemovePin -> ConfirmDialog(
            title = stringResource(R.string.user_profiles_pin_remove_title, dialog.profile.name),
            body = stringResource(if (dialog.profile.is_primary) R.string.user_profiles_pin_remove_primary_body else R.string.user_profiles_pin_remove_body),
            confirm = stringResource(R.string.user_profiles_remove_pin), destructive = false, busy = busy,
            onDismiss = onDismiss, onConfirm = { onAct(ProfileAction.RemovePin(dialog.profile)) },
        )
        is ProfileDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.user_profiles_delete_title, dialog.profile.name),
            body = stringResource(R.string.user_profiles_delete_body),
            confirm = stringResource(R.string.user_profiles_delete), destructive = true, busy = busy,
            onDismiss = onDismiss, onConfirm = { onAct(ProfileAction.Delete(dialog.profile)) },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(PROFILE_NAME_MAX).filterNot(Char::isISOControl) }, singleLine = true,
                label = { Text(stringResource(R.string.user_profiles_name_label)) },
                supportingText = { Text(stringResource(R.string.user_profiles_name_count, name.length)) },
                modifier = Modifier.fillMaxWidth().testTag(PROFILE_NAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) }, enabled = !busy && cleanProfileName(name) != null,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PROFILE_CONFIRM_TAG),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PinDialog(profile: UserProfile, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (profile.has_pin) R.string.user_profiles_pin_change_title else R.string.user_profiles_pin_set_title, profile.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                Text(stringResource(if (profile.has_pin) R.string.user_profiles_pin_change_body else R.string.user_profiles_pin_set_body))
                if (profile.is_primary) Text(stringResource(R.string.user_profiles_pin_primary_note), color = VeilColors.Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c in '0'..'9' }.take(4) }, singleLine = true,
                    label = { Text(stringResource(R.string.user_profiles_pin_label)) },
                    placeholder = { Text(stringResource(R.string.user_profiles_pin_hint)) },
                    // Shown as typed: the admin chooses it with the account holder and must see a slip. It is not kept once this closes.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = pin.isNotEmpty() && !isValidProfilePin(pin),
                    supportingText = { if (pin.isNotEmpty() && !isValidProfilePin(pin)) Text(stringResource(R.string.user_profiles_pin_invalid)) },
                    modifier = Modifier.fillMaxWidth().testTag(PROFILE_PIN_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) }, enabled = !busy && isValidProfilePin(pin),
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PROFILE_CONFIRM_TAG),
            ) { Text(stringResource(R.string.user_profiles_pin_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            if (destructive) DestructiveTextButton(
                onClick = onConfirm, enabled = !busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PROFILE_CONFIRM_TAG),
            ) { Text(confirm) }
            else TextButton(
                onClick = onConfirm, enabled = !busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag(PROFILE_CONFIRM_TAG),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
