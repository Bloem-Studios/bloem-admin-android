package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import com.bloemstudios.admin.dashboard.DashboardLibrary
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.EffectiveLibraryAccess
import com.bloemstudios.admin.users.EffectivePermissionAccess
import com.bloemstudios.admin.users.UpdateUserQuotasRequest
import com.bloemstudios.admin.users.UpdateUserResult
import com.bloemstudios.admin.users.UserAccessEdit
import com.bloemstudios.admin.users.UserPolicyEditorValues
import com.bloemstudios.admin.users.buildUserAccessUpdate
import com.bloemstudios.admin.users.buildUserPolicyUpdate
import com.bloemstudios.admin.users.policyEditorValues
import kotlinx.coroutines.launch

/**
 * Access & limits: the editor that used to be the Edit dialog. Max streams, transcodes and profiles, each
 * with Inherit, the downloads, requests and transcode flags, and a summary of libraries and permissions.
 *
 * The form is keyed on the account and the ETag it was read with. Saving sends that ETag, so a change
 * another admin made since is refused with a conflict; the page then reads the account again, which
 * resets the form to the server's values.
 */
@Composable
internal fun UserAccessTab(
    user: AdminUser,
    validator: String?,
    editable: Boolean,
    policyOwnerOnly: Boolean,
    choices: AccessChoices,
    onRetryChoices: () -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSave: suspend (UpdateUserQuotasRequest) -> UpdateUserResult
) {
    val initial = remember(user, validator) { user.policyEditorValues() }
    var maxStreamsText by remember(user, validator) { mutableStateOf(initial.maxStreamsText) }
    var maxTranscodesText by remember(user, validator) { mutableStateOf(initial.maxTranscodesText) }
    var maxProfilesText by remember(user, validator) { mutableStateOf(initial.maxProfilesText) }
    var maxStreamsInherited by remember(user, validator) { mutableStateOf(initial.maxStreamsInherited) }
    var maxTranscodesInherited by remember(user, validator) { mutableStateOf(initial.maxTranscodesInherited) }
    var maxProfilesInherited by remember(user, validator) { mutableStateOf(initial.maxProfilesInherited) }
    var downloadAllowed by remember(user, validator) { mutableStateOf(initial.downloadAllowed) }
    var downloadAllowedInherited by remember(user, validator) { mutableStateOf(initial.downloadAllowedInherited) }
    var requestsAllowed by remember(user, validator) { mutableStateOf(initial.requestsAllowed) }
    var requestsAllowedInherited by remember(user, validator) { mutableStateOf(initial.requestsAllowedInherited) }
    var transcodeAllowed by remember(user, validator) { mutableStateOf(initial.transcodeAllowed) }
    var transcodeAllowedInherited by remember(user, validator) { mutableStateOf(initial.transcodeAllowedInherited) }
    var audioTranscodeAllowed by remember(user, validator) { mutableStateOf(initial.audioTranscodeAllowed) }
    var audioTranscodeAllowedInherited by remember(user, validator) { mutableStateOf(initial.audioTranscodeAllowedInherited) }
    var groupId by remember(user, validator) { mutableStateOf(user.access_group_id) }
    var libraryIds by remember(user, validator) { mutableStateOf(user.library_ids) }
    var permissions by remember(user, validator) { mutableStateOf(user.permissions.sorted()) }
    // Not keyed on the account: a save or a conflict reads the account again, which resets the form above, and the
    // outcome of that save (the conflict message, "Saved.") has to survive the reset to be seen.
    var error by remember { mutableStateOf<ErrorText?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasInvalidValue = (!maxStreamsInherited && maxStreamsText.toIntOrNull() == null) ||
        (!maxTranscodesInherited && maxTranscodesText.toIntOrNull() == null) ||
        (!maxProfilesInherited && maxProfilesText.toIntOrNull() == null)
    val limits = buildUserPolicyUpdate(
        user,
        UserPolicyEditorValues(
            maxStreamsText, maxStreamsInherited, maxTranscodesText, maxTranscodesInherited,
            maxProfilesText, downloadAllowed, downloadAllowedInherited, requestsAllowed,
            requestsAllowedInherited, transcodeAllowed, transcodeAllowedInherited,
            audioTranscodeAllowed, audioTranscodeAllowedInherited,
            initial.libraryAccess, initial.permissionAccess, maxProfilesInherited
        )
    )
    val request = buildUserAccessUpdate(user, UserAccessEdit(groupId, libraryIds, permissions), limits)
    val dirty = !hasInvalidValue && !request.isEmpty()
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    val canEdit = editable && validator != null

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (!editable) {
            Text(
                stringResource(if (policyOwnerOnly) R.string.user_access_policy_owner_only else R.string.user_access_view_only),
                color = VeilColors.Muted
            )
        }
        Text(
            when (val access = initial.libraryAccess) {
                EffectiveLibraryAccess.Unrestricted -> stringResource(R.string.users_effective_libraries_unrestricted)
                is EffectiveLibraryAccess.Restricted -> stringResource(R.string.users_effective_libraries_restricted, access.libraryIds.size)
            }
        )
        Text(
            when (val access = initial.permissionAccess) {
                EffectivePermissionAccess.None -> stringResource(R.string.users_effective_permissions_none)
                is EffectivePermissionAccess.Explicit -> stringResource(R.string.users_effective_permissions_explicit, access.permissions.size)
            }
        )

        AccessSections(
            user = user,
            choices = choices,
            enabled = canEdit,
            groupId = groupId,
            libraryIds = libraryIds,
            permissions = permissions,
            onGroup = { groupId = it; saved = false },
            onLibraries = { libraryIds = it; saved = false },
            onPermissions = { permissions = it; saved = false },
            onRetry = onRetryChoices
        )

        LimitField(stringResource(R.string.users_max_streams), maxStreamsText, maxStreamsInherited, canEdit,
            onValue = { maxStreamsText = it.filter(Char::isDigit); saved = false }, onInherit = { maxStreamsInherited = it; saved = false })
        LimitField(stringResource(R.string.users_max_transcodes), maxTranscodesText, maxTranscodesInherited, canEdit,
            onValue = { maxTranscodesText = it.filter(Char::isDigit); saved = false }, onInherit = { maxTranscodesInherited = it; saved = false })
        LimitField(stringResource(R.string.users_max_profiles), maxProfilesText, maxProfilesInherited, canEdit,
            onValue = { maxProfilesText = it.filter(Char::isDigit); saved = false }, onInherit = { maxProfilesInherited = it; saved = false })

        FlagRow(stringResource(R.string.users_downloads_allowed), downloadAllowed, downloadAllowedInherited, canEdit,
            onValue = { downloadAllowed = it; saved = false }, onInherit = { downloadAllowedInherited = it; saved = false })
        FlagRow(stringResource(R.string.users_requests_allowed), requestsAllowed, requestsAllowedInherited, canEdit,
            onValue = { requestsAllowed = it; saved = false }, onInherit = { requestsAllowedInherited = it; saved = false })
        FlagRow(stringResource(R.string.users_transcode_allowed), transcodeAllowed, transcodeAllowedInherited, canEdit,
            onValue = { transcodeAllowed = it; saved = false }, onInherit = { transcodeAllowedInherited = it; saved = false })
        FlagRow(stringResource(R.string.users_audio_transcode_allowed), audioTranscodeAllowed, audioTranscodeAllowedInherited, canEdit,
            onValue = { audioTranscodeAllowed = it; saved = false }, onInherit = { audioTranscodeAllowedInherited = it; saved = false })

        if (editable && validator == null) {
            Text(stringResource(R.string.users_missing_version), color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
        if (saved) Text(stringResource(R.string.user_access_saved), color = VeilColors.Ok)
        if (editable) {
            Button(
                enabled = canEdit && !submitting && dirty,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                onClick = {
                    scope.launch {
                        submitting = true
                        error = null
                        when (val result = onSave(request)) {
                            UpdateUserResult.Busy -> error = ErrorText.Res(R.string.action_in_progress)
                            is UpdateUserResult.Success -> saved = true
                            is UpdateUserResult.Failure -> error = result.message
                        }
                        submitting = false
                    }
                }
            ) { Text(stringResource(R.string.user_access_save)) }
        }
    }
}

@Composable
private fun LimitField(
    label: String,
    value: String,
    inherited: Boolean,
    enabled: Boolean,
    onValue: (String) -> Unit,
    onInherit: (Boolean) -> Unit
) {
    val inheritLabel = "${stringResource(R.string.users_inherit)} $label"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(label) },
            enabled = enabled && !inherited,
            isError = !inherited && value.toIntOrNull() == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        LabeledCheckbox(
            checked = inherited,
            label = stringResource(R.string.users_inherit),
            enabled = enabled,
            onCheckedChange = onInherit,
            modifier = Modifier.semantics { contentDescription = inheritLabel }
        )
    }
}

@Composable
private fun FlagRow(
    label: String,
    value: Boolean,
    inherited: Boolean,
    enabled: Boolean,
    onValue: (Boolean) -> Unit,
    onInherit: (Boolean) -> Unit
) {
    val inheritLabel = "${stringResource(R.string.users_inherit)} $label"
    Row(
        Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = onValue,
            enabled = enabled && !inherited,
            modifier = Modifier.semantics { contentDescription = label }
        )
        LabeledCheckbox(
            checked = inherited,
            label = stringResource(R.string.users_inherit),
            enabled = enabled,
            onCheckedChange = onInherit,
            modifier = Modifier.semantics { contentDescription = inheritLabel }
        )
    }
}

/** What the Access & limits form chooses from, each read on its own so one failing does not hide the others. */
internal data class AccessChoices(
    val groups: Section<List<AccessGroup>>,
    val libraries: Section<List<DashboardLibrary>>,
    val defaults: Section<UserPolicyDefaults>
)

private data class Perm(val key: String, val label: Int, val help: Int)

private val PERMISSIONS = listOf(
    Perm(PERMISSION_MARKER_EDIT, R.string.user_permission_marker_edit, R.string.user_permission_marker_edit_help),
    Perm(PERMISSION_METADATA_CURATION, R.string.user_permission_metadata_curation, R.string.user_permission_metadata_curation_help),
    Perm(PERMISSION_WATCH_LIVE_TV, R.string.user_permission_watch_live_tv, R.string.user_permission_watch_live_tv_help)
)

/**
 * The access group, the library allowlist and the permissions assigned to the account, like the web's
 * library card. Every label says where an inherited value comes from under the group picked here.
 */
@Composable
private fun AccessSections(
    user: AdminUser,
    choices: AccessChoices,
    enabled: Boolean,
    groupId: String?,
    libraryIds: List<String>?,
    permissions: List<String>,
    onGroup: (String?) -> Unit,
    onLibraries: (List<String>?) -> Unit,
    onPermissions: (List<String>) -> Unit,
    onRetry: () -> Unit
) {
    val admin = user.role == "admin"
    val groups = (choices.groups as? Section.Loaded)?.value
    val libraries = (choices.libraries as? Section.Loaded)?.value
    val defaults = (choices.defaults as? Section.Loaded)?.value
    val failure = listOf(choices.groups, choices.libraries).firstNotNullOfOrNull { (it as? Section.Failed)?.message }
    val groupList = groups ?: emptyList()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        if (failure != null) {
            Text(failure.resolve(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_retry))
            }
        } else if (groups == null || libraries == null) {
            Text(stringResource(R.string.user_access_choices_loading), color = VeilColors.Muted)
        }

        // Access group: admins are never grouped.
        Text(stringResource(R.string.user_access_group_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (admin) {
            Text(stringResource(R.string.user_access_admin_no_groups), color = VeilColors.Muted)
        } else {
            val pickable = enabled && groups != null
            RadioRow(groupId == null, stringResource(R.string.user_access_no_group), { onGroup(null) }, pickable)
            groupList.forEach { group ->
                RadioRow(groupId == group.id, group.name, { onGroup(group.id) }, pickable)
            }
            // A group the list does not have (deleted since): still shown, so the form says what is saved.
            if (groupId != null && groupList.none { it.id == groupId }) {
                RadioRow(true, stringResource(R.string.user_access_group_unknown, groupId), {}, enabled = false)
            }
            groupList.firstOrNull { it.id == groupId }?.let { picked ->
                Text(groupSummary(picked, libraries), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            }
        }

        // Libraries: inherit (from the group or the server), or an explicit list.
        Text(stringResource(R.string.user_access_libraries_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val inherited = inheritedLibraries(
            role = user.role, draftGroupId = groupId, savedGroupId = user.access_group_id,
            inheritsNow = user.library_ids == null, effectiveLibraryIds = user.effective_policy?.library_ids,
            groups = groups, defaults = defaults
        )
        val choose = libraryIds != null
        RadioRow(
            selected = !choose,
            label = stringResource(
                if (inheritsFromGroup(user.role, groupId)) R.string.user_access_libraries_use_group
                else R.string.user_access_libraries_use_default
            ),
            onSelect = { onLibraries(null) },
            enabled = enabled,
            detail = inheritedLibrariesText(inherited, libraries)
        )
        RadioRow(
            selected = choose,
            label = stringResource(R.string.user_access_libraries_choose),
            onSelect = {
                // Start from what the account can see now, so choosing changes nothing until a library is toggled.
                val seeing = user.effective_policy?.library_ids ?: libraries?.map { it.id } ?: emptyList()
                onLibraries(seeing)
            },
            enabled = enabled && libraries != null
        )
        if (libraryIds != null && libraries != null) {
            val all = libraries.map { it.id }
            libraries.forEach { library ->
                LabeledCheckbox(
                    checked = library.id in libraryIds,
                    label = library.name,
                    enabled = enabled,
                    onCheckedChange = { on -> onLibraries(toggleLibrary(libraryIds, all, library.id, on)) },
                    modifier = Modifier.padding(start = VeilSpacing.Large)
                )
            }
            if (libraryIds.isEmpty()) Text(stringResource(R.string.user_access_libraries_none_warning), color = VeilColors.Warning)
        }

        // Permissions assigned to the account; a group can still block them.
        Text(stringResource(R.string.user_access_permissions_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        PERMISSIONS.forEach { perm ->
            val lock = permissionLock(user.role, groupId, groupList, perm.key)
            val assigned = perm.key in permissions
            val label = stringResource(perm.label)
            val removeText = stringResource(R.string.user_permission_remove)
            val note = when {
                lock.locked && assigned -> stringResource(R.string.user_permission_assigned_blocked, lock.groupName.orEmpty())
                !lock.locked && lock.groupName != null -> stringResource(R.string.user_permission_group_allows, lock.groupName)
                else -> stringResource(perm.help)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    Text(note, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                    if (lock.locked) {
                        Text(stringResource(R.string.user_permission_group_disallows, lock.groupName.orEmpty()), style = MaterialTheme.typography.bodySmall, color = VeilColors.Warning)
                    }
                }
                if (lock.locked && assigned) {
                    TextButton(
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = "$label: $removeText" },
                        onClick = { onPermissions(setAssignedPermission(permissions, perm.key, false)) }
                    ) { Text(stringResource(R.string.user_permission_remove)) }
                }
                Switch(
                    // A locked switch shows what applies: off.
                    checked = assigned && !lock.locked,
                    onCheckedChange = { onPermissions(setAssignedPermission(permissions, perm.key, it)) },
                    enabled = enabled && !lock.locked,
                    modifier = Modifier.semantics { contentDescription = label }
                )
            }
        }
    }
}

@Composable
private fun groupSummary(group: AccessGroup, libraries: List<DashboardLibrary>?): String {
    val parts = listOfNotNull(
        libraries?.let { libraryListText(group.library_ids, it) },
        group.max_playback_quality.takeIf { it.isNotBlank() },
        if (group.max_streams == 0) stringResource(R.string.user_access_group_streams_unlimited)
        else pluralStringResource(R.plurals.user_access_group_streams, group.max_streams, group.max_streams)
    )
    return stringResource(R.string.user_access_group_summary, group.name, parts.joinToString(" · "))
}

@Composable
private fun libraryListText(ids: List<String>?, libraries: List<DashboardLibrary>): String = when {
    ids == null -> stringResource(R.string.user_access_libraries_all)
    ids.isEmpty() -> stringResource(R.string.user_access_libraries_none)
    else -> ids.joinToString(", ") { id -> libraries.firstOrNull { it.id == id }?.name ?: "#$id" }
}

@Composable
private fun inheritedLibrariesText(inherited: InheritedLibraries, libraries: List<DashboardLibrary>?): String? = when (inherited) {
    InheritedLibraries.Unknown -> null
    InheritedLibraries.All -> stringResource(R.string.user_access_libraries_all)
    is InheritedLibraries.Some -> libraries?.let { libraryListText(inherited.ids, it) }
        ?: if (inherited.ids.isEmpty()) stringResource(R.string.user_access_libraries_none) else null
}
