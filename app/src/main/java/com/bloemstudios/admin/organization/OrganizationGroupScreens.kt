package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

// ---- Access groups: list, new group, editor --------------------------------------------------------------

@Composable
fun OrganizationAccessGroupsScreen(session: OrganizationSession) {
    val viewModel = viewModel<AccessGroupsViewModel>()
    val state by viewModel.state.collectAsState()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    ForegroundEffect(session) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    // A group that was just made opens in the editor, as on the web.
    LaunchedEffect(state.createdId) {
        state.createdId?.let { editingId = it; viewModel.consumeCreated() }
    }
    val editing = state.groups.firstOrNull { it.id == editingId }
    BackHandler(enabled = editing != null) { editingId = null }
    AccessGroupsContent(
        state = state, editing = editing,
        onRetry = { scope.launch { viewModel.load(session) } },
        onRefresh = { viewModel.load(session) },
        onDismiss = viewModel::dismissMessages,
        onOpen = { editingId = it.id },
        onCloseEditor = { editingId = null },
        onCreate = { name -> scope.launch { viewModel.create(session, name) } },
        onSave = { group, draft -> scope.launch { viewModel.save(session, group, draft) } },
        onDelete = { group -> scope.launch { if (viewModel.delete(session, group)) editingId = null } },
    )
}

@Composable
internal fun AccessGroupsContent(
    state: AccessGroupsState,
    onRetry: () -> Unit,
    editing: AccessGroup? = null,
    onDismiss: () -> Unit = {},
    onOpen: (AccessGroup) -> Unit = {},
    onCloseEditor: () -> Unit = {},
    onCreate: (String) -> Unit = {},
    onSave: (AccessGroup, AccessGroupDraft) -> Unit = { _, _ -> },
    onDelete: (AccessGroup) -> Unit = {},
    onRefresh: (suspend () -> Unit)? = null,
) {
    var naming by remember { mutableStateOf(false) }
    if (editing != null) {
        AccessGroupEditor(state, editing, onDismiss, onCloseEditor, onSave, onDelete)
        return
    }
    OrganizationPage(
        title = stringResource(R.string.organization_groups_title),
        subtitle = null,
        loading = state.loading, empty = state.groups.isEmpty(),
        emptyMessage = stringResource(R.string.organization_groups_none),
        error = state.error, notice = state.notice, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
        header = {
            Button(
                onClick = { naming = true }, enabled = !state.saving,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-group-new"),
            ) { Text(stringResource(R.string.organization_groups_new)) }
            state.lastDeletion?.takeIf { it.profiles_reassigned > 0 }?.let {
                Text(
                    pluralStringResource(R.plurals.organization_groups_reassigned, it.profiles_reassigned.toInt(), it.profiles_reassigned.toInt()),
                    style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                )
            }
        },
    ) {
        items(state.groups, key = { it.id }) { group ->
            OrganizationCard(Modifier.testTag("organization-group-${group.id}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.weight(1f))
                    if (group.is_default) StatusLabel(stringResource(R.string.organization_groups_default))
                }
                if (group.description.isNotBlank()) Text(group.description, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                Text(
                    pluralStringResource(R.plurals.organization_groups_members, group.member_count.toInt(), group.member_count.toInt()),
                    style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text,
                )
                val limits = buildList {
                    add(stringResource(R.string.organization_groups_streams, group.max_streams.toInt()))
                    add(stringResource(R.string.organization_groups_profiles, group.max_profiles.toInt()))
                    if (group.max_playback_quality.isNotBlank()) add(stringResource(R.string.organization_groups_quality, group.max_playback_quality))
                    add(
                        group.library_ids?.let { pluralStringResource(R.plurals.organization_groups_libraries_count, it.size, it.size) }
                            ?: stringResource(R.string.organization_policy_all_libraries)
                    )
                }
                Text(limits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                val allowed = buildList {
                    if (group.download_allowed) add(stringResource(R.string.organization_groups_downloads))
                    if (group.transcode_allowed) add(stringResource(R.string.organization_groups_transcoding))
                    if (group.requests_allowed) add(stringResource(R.string.organization_groups_requests))
                }
                if (allowed.isNotEmpty()) Text(allowed.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                ActionButton(
                    stringResource(R.string.organization_groups_edit),
                    stringResource(R.string.organization_groups_edit_group, group.name),
                    { onOpen(group) },
                )
            }
        }
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.organization_groups_new)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.organization_groups_new_name)) },
                    placeholder = { Text(stringResource(R.string.organization_groups_new_placeholder)) },
                    modifier = Modifier.fillMaxWidth().testTag("organization-group-new-name"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { naming = false; onCreate(name) }, enabled = name.isNotBlank(),
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-group-create"),
                ) { Text(stringResource(R.string.organization_groups_create)) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}

/** The web's editor for one group, in one scrolling page. Nothing is sent until Save. */
@Composable
private fun AccessGroupEditor(
    state: AccessGroupsState,
    group: AccessGroup,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    onSave: (AccessGroup, AccessGroupDraft) -> Unit,
    onDelete: (AccessGroup) -> Unit,
) {
    var draft by remember(group.id, group.updated_at) { mutableStateOf(AccessGroupDraft.of(group)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val defaultGroupName = state.groups.firstOrNull { it.is_default }?.name ?: stringResource(R.string.organization_groups_the_default_group)
    OrganizationPage(
        title = group.name,
        subtitle = null,
        showTitle = true,
        loading = false, empty = false, emptyMessage = "",
        error = state.error, notice = state.notice, onRetry = {}, onDismissMessages = onDismiss,
        header = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-group-back")) {
                Text(stringResource(R.string.organization_groups_all_groups))
            }
        },
    ) {
        item {
            OrganizationCard(Modifier.testTag("organization-group-editor")) {
                OutlinedTextField(
                    value = draft.name, onValueChange = { draft = draft.copy(name = it) }, singleLine = true,
                    label = { Text(stringResource(R.string.organization_groups_field_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("organization-group-name"),
                )
                OutlinedTextField(
                    value = draft.description, onValueChange = { draft = draft.copy(description = it) },
                    label = { Text(stringResource(R.string.organization_groups_field_description)) },
                    placeholder = { Text(stringResource(R.string.organization_groups_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    stringResource(R.string.organization_groups_default_for_new),
                    stringResource(if (group.is_default) R.string.organization_groups_default_for_new_is else R.string.organization_groups_default_for_new_help),
                    draft.isDefault, { draft = draft.copy(isDefault = it) }, enabled = !group.is_default,
                )
            }
        }
        item {
            OrganizationCard(Modifier.testTag("organization-group-libraries")) {
                Text(stringResource(R.string.organization_groups_libraries_playback), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                Text(stringResource(R.string.organization_groups_libraries_scope), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                ChoiceRow(
                    stringResource(R.string.organization_groups_all_libraries), draft.libraryIds == null,
                    { draft = draft.copy(libraryIds = null) }, Modifier.testTag("organization-group-all-libraries"),
                )
                ChoiceRow(
                    stringResource(R.string.organization_groups_chosen_libraries), draft.libraryIds != null,
                    { if (draft.libraryIds == null) draft = draft.copy(libraryIds = emptySet()) }, Modifier.testTag("organization-group-chosen-libraries"),
                )
                val chosen = draft.libraryIds
                if (chosen != null) {
                    state.libraries.forEach { library ->
                        val id = library.folder_id.toString()
                        val usable = library.countsTowardCeiling
                        val label = if (usable) library.name else stringResource(R.string.organization_groups_library_unavailable, library.name)
                        CheckRow(
                            label, id in chosen, { checked -> draft = draft.copy(libraryIds = if (checked) chosen + id else chosen - id) },
                            Modifier.testTag("organization-group-library-$id"),
                        )
                    }
                    // Libraries the group names that the organization can no longer list stay visible so saving doesn't drop them silently.
                    chosen.filter { id -> state.libraries.none { it.folder_id.toString() == id } }.forEach { id ->
                        CheckRow(
                            stringResource(R.string.organization_policy_library_number, id.toIntOrNull() ?: 0), true,
                            { checked -> if (!checked) draft = draft.copy(libraryIds = chosen - id) },
                        )
                    }
                }
                Text(stringResource(R.string.organization_policy_best_quality), style = MaterialTheme.typography.labelLarge, color = VeilColors.Text)
                val quality = PlaybackQuality.canonical(draft.maxPlaybackQuality)
                PlaybackQuality.choices.forEach { choice ->
                    ChoiceRow(
                        qualityText(choice), quality == choice && PlaybackQuality.canonical(draft.maxPlaybackQuality) == choice,
                        { draft = draft.copy(maxPlaybackQuality = choice) }, Modifier.testTag("organization-group-quality-${choice.ifEmpty { "any" }}"),
                    )
                }
            }
        }
        item {
            OrganizationCard {
                Text(stringResource(R.string.organization_groups_downloads_requests), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                ToggleRow(
                    stringResource(R.string.organization_policy_download_originals), stringResource(R.string.organization_groups_download_originals_help),
                    draft.downloadAllowed, { draft = draft.copy(downloadAllowed = it) },
                )
                ToggleRow(
                    stringResource(R.string.organization_policy_download_converted), stringResource(R.string.organization_groups_download_converted_help),
                    draft.downloadTranscodeAllowed && draft.downloadAllowed, { draft = draft.copy(downloadTranscodeAllowed = it) }, enabled = draft.downloadAllowed,
                )
                ToggleRow(
                    stringResource(R.string.organization_policy_media_requests), stringResource(R.string.organization_groups_requests_help),
                    draft.requestsAllowed, { draft = draft.copy(requestsAllowed = it) },
                )
            }
        }
        item {
            OrganizationCard(Modifier.testTag("organization-group-streams")) {
                Text(stringResource(R.string.organization_groups_concurrent), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                LimitField(
                    stringResource(R.string.organization_policy_screens_at_once), draft.maxStreams, { draft = draft.copy(maxStreams = it) },
                    Modifier.testTag("organization-group-max-streams"),
                )
                LimitField(
                    stringResource(R.string.organization_policy_conversions_at_once), draft.maxTranscodes, { draft = draft.copy(maxTranscodes = it) },
                    Modifier.testTag("organization-group-max-transcodes"),
                )
                ToggleRow(
                    stringResource(R.string.organization_policy_video_conversion), stringResource(R.string.organization_groups_video_conversion_help),
                    draft.transcodeAllowed, { draft = draft.copy(transcodeAllowed = it) },
                )
                ToggleRow(
                    stringResource(R.string.organization_policy_audio_conversion), stringResource(R.string.organization_groups_audio_conversion_help),
                    draft.audioTranscodeAllowed, { draft = draft.copy(audioTranscodeAllowed = it) },
                )
            }
        }
        item {
            OrganizationCard(Modifier.testTag("organization-group-permissions")) {
                Text(stringResource(R.string.organization_groups_permissions), style = MaterialTheme.typography.titleMedium, color = VeilColors.Text)
                Text(stringResource(R.string.organization_groups_permissions_help), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                val all = draft.allowedPermissions == null
                ToggleRow(
                    stringResource(R.string.organization_groups_allow_every_permission), null, all,
                    { draft = draft.copy(allowedPermissions = if (it) null else GroupPermissionChoices.toSet()) },
                    Modifier.testTag("organization-group-all-permissions"),
                )
                if (!all) GroupPermissionChoices.forEach { permission ->
                    ToggleRow(
                        permissionText(permission), null, permission in draft.allowedPermissions.orEmpty(),
                        { checked ->
                            val current = draft.allowedPermissions.orEmpty()
                            draft = draft.copy(allowedPermissions = if (checked) current + permission else current - permission)
                        },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TextButton(
                        onClick = { confirmDelete = true }, enabled = !group.is_default && !state.saving,
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-group-delete"),
                    ) { Text(stringResource(R.string.organization_groups_delete), color = if (group.is_default) VeilColors.Muted else VeilColors.Danger) }
                    if (group.is_default) Text(
                        stringResource(R.string.organization_groups_default_cannot_delete), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted,
                    )
                }
                Button(
                    onClick = { onSave(group, draft) }, enabled = !state.saving && draft.name.isNotBlank(),
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-group-save"),
                ) { Text(stringResource(if (state.saving) R.string.organization_groups_saving else R.string.organization_groups_save)) }
            }
        }
    }

    if (confirmDelete) {
        val organization = stringResource(R.string.organization_this_organization)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.organization_groups_delete_title, group.name)) },
            text = {
                Text(
                    if (group.member_count > 0) pluralStringResource(
                        R.plurals.organization_groups_delete_reassign, group.member_count.toInt(), group.member_count.toInt(), organization, defaultGroupName,
                    ) else stringResource(R.string.organization_groups_delete_no_profiles, organization)
                )
            },
            confirmButton = {
                DestructiveTextButton(
                    onClick = { confirmDelete = false; onDelete(group) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("organization-group-delete-confirm"),
                ) { Text(stringResource(R.string.organization_groups_delete_confirm), color = VeilColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}

private val GroupPermissionChoices = listOf(GroupPermissions.LIVE_TV, GroupPermissions.METADATA, GroupPermissions.MARKERS)

@Composable
private fun permissionText(permission: String): String = permissionRes(permission)?.let { stringResource(it) } ?: sentenceCase(permission)

@Composable
private fun ToggleRow(
    label: String,
    help: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = label },
        )
    }
}

/** One choice of several, a single 48dp target that reads as "label, radio button, selected". */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text, modifier = Modifier.padding(start = VeilSpacing.Small))
    }
}

/** One yes-or-no row, a single 48dp target that reads as "label, checkbox, checked". */
@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text, modifier = Modifier.padding(start = VeilSpacing.Small))
    }
}

/** A whole number, 0 for no limit. The field keeps what is typed (so it can be emptied and retyped) and reports the number. */
@Composable
private fun LimitField(label: String, value: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter(Char::isDigit).take(6)
            onChange(text.toLongOrNull() ?: 0L)
        },
        singleLine = true,
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.organization_groups_zero_unlimited)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}
