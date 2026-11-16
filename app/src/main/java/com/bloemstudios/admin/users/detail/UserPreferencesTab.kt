package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ui.components.LucideIcon

import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.AdminUser
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Preferences: the settings the account's people saved in their apps, by level (a profile's settings for every
 * device, then what a device, app family, library or series replaces for it), like the web's Preferences tab.
 * A level shows only the settings it stores. Scalar settings are edited in place with the contract's own
 * controls; each write addresses one row. Anything the contract types as an object is shown, not edited.
 */
@Composable
internal fun UserPreferencesTab(user: AdminUser, admin: UserAdmin, devicesAvailable: Boolean, editable: Boolean) {
    val viewModel = remember(admin, user.id, devicesAvailable) { UserPreferencesViewModel(admin, user.id, devicesAvailable) }
    ForegroundOnceEffect(viewModel) { viewModel.load() }
    val state = viewModel.state.collectAsState().value
    val busy = viewModel.busy.collectAsState().value
    val error = viewModel.error.collectAsState().value
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
        when (state) {
            PreferencesState.Loading -> Text(stringResource(R.string.user_prefs_loading), color = VeilColors.Muted)
            is PreferencesState.Failed -> {
                Text(state.message.resolve(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { viewModel.load() } }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            is PreferencesState.Ready -> {
                if (!editable) Text(stringResource(R.string.user_prefs_read_only_note), color = VeilColors.Muted)
                // Without the contract no value can be edited truthfully.
                val canEdit = editable && state.definitions != null
                error?.let {
                    Text(it.resolve(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::dismissError, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
                if (state.levels.isEmpty()) {
                    Text(stringResource(R.string.user_prefs_no_profiles), color = VeilColors.Muted)
                } else {
                    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                    val selected = state.levels.firstOrNull { it.id == selectedId }
                        ?: state.levels.firstOrNull { it.kind == LevelKind.Profile }
                        ?: state.levels.first()
                    LevelPicker(state.groups, selected) { selectedId = it.id }
                    LevelSettings(viewModel, state, selected, canEdit, busy)
                }
            }
        }
    }
}

@Composable
private fun levelName(level: PreferenceLevel): String = when (level.kind) {
    LevelKind.Account -> stringResource(R.string.user_prefs_level_account_wide)
    LevelKind.Profile -> stringResource(R.string.user_prefs_level_all_devices)
    LevelKind.Device -> level.deviceName
        ?: if (level.deviceId.isNullOrEmpty()) stringResource(R.string.user_prefs_level_unknown_device)
        else stringResource(R.string.user_prefs_level_device_by_id, level.deviceId.take(8))
    LevelKind.Client -> stringResource(R.string.user_prefs_level_client_apps, familyName(level.clientFamily.orEmpty()))
    LevelKind.Library -> level.libraryName?.let { stringResource(R.string.user_prefs_level_library, it) }
        ?: stringResource(R.string.user_prefs_level_library_by_id, level.libraryId.orEmpty())
    LevelKind.Series -> stringResource(R.string.user_prefs_level_series, level.seriesId.orEmpty())
}

private fun familyName(family: String) = if (family == "tv") "TV" else family.replaceFirstChar { it.uppercase() }

@Composable
private fun levelTitle(level: PreferenceLevel): String =
    if (level.kind == LevelKind.Account) levelName(level) else "${levelName(level)} · ${level.profileName}"

@Composable
private fun levelDescription(level: PreferenceLevel): String {
    val profile = level.profileName
    return when (level.kind) {
        LevelKind.Account -> stringResource(R.string.user_prefs_desc_account)
        LevelKind.Profile -> stringResource(R.string.user_prefs_desc_profile, profile)
        LevelKind.Device -> stringResource(R.string.user_prefs_desc_device, profile)
        LevelKind.Client -> stringResource(R.string.user_prefs_desc_client, profile)
        LevelKind.Library -> stringResource(R.string.user_prefs_desc_library, profile)
        LevelKind.Series -> stringResource(R.string.user_prefs_desc_series, profile)
    }
}

/** Picks the level whose settings are shown. Grouped by profile, with how many settings each level stores. */
@Composable
private fun LevelPicker(groups: List<PreferenceLevelGroup>, selected: PreferenceLevel, onSelect: (PreferenceLevel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val picker = stringResource(R.string.user_prefs_level_picker)
    val current = stringResource(R.string.user_prefs_level_option, levelTitle(selected), selected.entries.size)
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget)
                .semantics { contentDescription = "$picker: $current" }
        ) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            groups.forEach { group ->
                Text(
                    if (group.profileName == null) stringResource(R.string.user_prefs_group_account)
                    else stringResource(R.string.user_prefs_group_profile, group.profileName),
                    style = MaterialTheme.typography.labelMedium,
                    color = VeilColors.Muted,
                    modifier = Modifier.padding(horizontal = VeilSpacing.Medium, vertical = VeilSpacing.Small).semantics { heading() }
                )
                group.levels.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.user_prefs_level_option, levelName(level), level.entries.size)) },
                        onClick = { open = false; onSelect(level) },
                        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelSettings(
    viewModel: UserPreferencesViewModel,
    state: PreferencesState.Ready,
    level: PreferenceLevel,
    canEdit: Boolean,
    busy: Boolean
) {
    val scope = rememberCoroutineScope()
    var removing by remember(level.id) { mutableStateOf<UserSettingEntry?>(null) }
    var adding by remember(level.id) { mutableStateOf(false) }
    val profileEntries = state.levels.firstOrNull { it.kind == LevelKind.Profile && it.profileId == level.profileId }?.entries.orEmpty()
    val addable = remember(level, state.definitions) { addableSettings(level, state.definitions.orEmpty()) }

    DetailCard(levelTitle(level)) {
        Text(levelDescription(level), color = VeilColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (level.entries.isEmpty()) {
            Text(stringResource(R.string.user_prefs_empty_title), style = MaterialTheme.typography.titleSmall)
            Text(
                if (level.kind == LevelKind.Profile || level.kind == LevelKind.Account) stringResource(R.string.user_prefs_empty_profile, level.profileName.ifEmpty { levelName(level) })
                else stringResource(R.string.user_prefs_empty_other, level.profileName),
                color = VeilColors.Muted
            )
        }
        level.entries.forEach { entry ->
            SettingRow(
                entry = entry,
                definition = state.definition(entry.key),
                replaces = replacesText(replacedValue(level, entry.key, profileEntries, state.definition(entry.key)), state.definition(entry.key)),
                editable = canEdit,
                busy = busy,
                onSet = { value -> scope.launch { viewModel.set(entry.key, entry.identity(), value) } },
                onRemove = { removing = entry }
            )
        }
        if (canEdit && addable.isNotEmpty()) {
            OutlinedButton(
                onClick = { adding = true },
                enabled = !busy,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)
            ) { Text(stringResource(R.string.user_prefs_add)) }
        }
    }

    removing?.let { entry ->
        val definition = state.definition(entry.key)
        val replaced = replacedValue(level, entry.key, profileEntries, definition)
        val label = definition?.label?.ifBlank { null } ?: entry.key
        val fromProfile = replaced.profileName != null
        AlertDialog(
            onDismissRequest = { removing = null },
            title = {
                Text(
                    if (fromProfile) stringResource(R.string.user_prefs_remove_title_profile, level.profileName)
                    else stringResource(R.string.user_prefs_remove_title_default)
                )
            },
            text = {
                Text(
                    if (fromProfile) stringResource(R.string.user_prefs_remove_body_profile, label, level.profileName)
                    else stringResource(R.string.user_prefs_remove_body_default, label)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = { removing = null; scope.launch { viewModel.remove(entry) } }
                ) {
                    Text(
                        if (fromProfile) stringResource(R.string.user_prefs_remove_confirm_profile, level.profileName)
                        else stringResource(R.string.user_prefs_remove_confirm_default)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (adding) {
        var picked by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.user_prefs_add)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    addable.groupBy { it.category }.forEach { (category, defs) ->
                        Text(categoryTitle(category), style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted, modifier = Modifier.semantics { heading() })
                        defs.forEach { def ->
                            LabeledCheckbox(
                                checked = def.key in picked,
                                label = def.label.ifBlank { def.key },
                                onCheckedChange = { on -> picked = if (on) picked + def.key else picked - def.key },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = picked.isNotEmpty() && !busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                    onClick = {
                        adding = false
                        // In the contract's order, so the rows land the way the level lists them.
                        val keys = addable.map { it.key }.filter { it in picked }
                        scope.launch { viewModel.add(level, keys) }
                    }
                ) { Text(stringResource(R.string.user_prefs_add_set_here)) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun replacesText(replaced: ReplacedValue, definition: SettingDefinition?): String {
    val on = stringResource(R.string.user_prefs_value_on)
    val off = stringResource(R.string.user_prefs_value_off)
    val unset = stringResource(R.string.user_prefs_value_unset)
    val text = valueText(definition, replaced.value, on, off, unset)
    return when {
        replaced.profileName != null -> stringResource(R.string.user_prefs_replaces_profile, replaced.profileName, text)
        replaced.value == null -> stringResource(R.string.user_prefs_replaces_default)
        else -> stringResource(R.string.user_prefs_replaces_default_value, text)
    }
}

/** One stored setting: what it is, what it replaces, and the control for its type. */
@Composable
private fun SettingRow(
    entry: UserSettingEntry,
    definition: SettingDefinition?,
    replaces: String,
    editable: Boolean,
    busy: Boolean,
    onSet: (JsonElement) -> Unit,
    onRemove: () -> Unit
) {
    val label = definition?.label?.ifBlank { null } ?: entry.key
    val editor = editorFor(definition, entry.value)
    val on = stringResource(R.string.user_prefs_value_on)
    val off = stringResource(R.string.user_prefs_value_off)
    val unset = stringResource(R.string.user_prefs_value_unset)
    val removeText = stringResource(R.string.user_prefs_remove, label)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (definition != null) categoryTitle(definition.category) else stringResource(R.string.user_prefs_not_in_version),
                    style = MaterialTheme.typography.labelSmall, color = VeilColors.Muted
                )
                Text(label)
                Text(replaces, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            }
            if (editable) {
                IconButton(
                    onClick = onRemove,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = removeText }
                ) { LucideIcon(R.drawable.lucide_trash_2, contentDescription = null) }
            }
        }
        when {
            !editable || editor is ValueEditor.ViewOnly -> {
                Text(valueText(definition, entry.value, on, off, unset), color = VeilColors.Muted)
                if (editor is ValueEditor.ViewOnly && editable) Text(stringResource(R.string.user_prefs_view_only), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            }
            editor is ValueEditor.Switch -> {
                val checked = (entry.value as? JsonPrimitive)?.content == "true"
                Switch(
                    checked = checked,
                    onCheckedChange = { onSet(JsonPrimitive(it)) },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = label }
                )
            }
            editor is ValueEditor.Choice -> ChoiceControl(label, editor, entry.value, definition, !busy, onSet)
            editor is ValueEditor.Number -> TextControl(label, editText(entry.value), !busy, number = editor, text = null) { onSet(it) }
            editor is ValueEditor.Text -> TextControl(label, editText(entry.value), !busy, number = null, text = editor) { onSet(it) }
        }
    }
}

@Composable
private fun ChoiceControl(label: String, editor: ValueEditor.Choice, current: JsonElement, definition: SettingDefinition?, enabled: Boolean, onSet: (JsonElement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val on = stringResource(R.string.user_prefs_value_on)
    val off = stringResource(R.string.user_prefs_value_off)
    val unset = stringResource(R.string.user_prefs_value_unset)
    val shown = valueText(definition, current, on, off, unset)
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = "$label: $shown" }
        ) { Text(shown) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            editor.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label ?: editText(option.value)) },
                    onClick = { open = false; if (option.value != current) onSet(option.value) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)
                )
            }
        }
    }
}

/** A number or text field with its own Save: nothing is written until the typed value is valid and different. */
@Composable
private fun TextControl(
    label: String,
    stored: String,
    enabled: Boolean,
    number: ValueEditor.Number?,
    text: ValueEditor.Text?,
    onSet: (JsonElement) -> Unit
) {
    var typed by remember(stored) { mutableStateOf(stored) }
    val parsed: ParsedValue? = number?.let { parseNumber(typed, it) }
    val problem = when (parsed) {
        ParsedValue.NotANumber -> stringResource(R.string.user_prefs_invalid_number)
        is ParsedValue.OutOfRange -> stringResource(R.string.user_prefs_out_of_range, parsed.min?.let(::plain) ?: "…", parsed.max?.let(::plain) ?: "…")
        else -> null
    }
    val value: JsonElement? = when {
        number != null -> (parsed as? ParsedValue.Ok)?.value
        text != null -> parseText(typed, text)
        else -> null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
            isError = problem != null,
            supportingText = problem?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (number != null) KeyboardType.Decimal else KeyboardType.Text),
            modifier = Modifier.weight(1f)
        )
        TextButton(
            enabled = enabled && value != null && typed != stored,
            modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).padding(top = VeilSpacing.Tiny),
            onClick = { value?.let(onSet) }
        ) { Text(stringResource(R.string.user_prefs_save)) }
    }
}

private fun plain(number: Double): String = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
