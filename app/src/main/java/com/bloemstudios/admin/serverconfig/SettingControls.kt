package com.bloemstudios.admin.serverconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.SegmentedControl
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

/** What a control needs to draw itself, beyond the definition: the live value and how the server treats the key. */
data class SettingControlState(
    val value: String,
    val restartOnly: Boolean = false,
    /** The server's environment owns this key: it is shown but cannot change. */
    val managedByEnv: Boolean = false,
    /** For a secret: whether one is set. */
    val secretConfigured: Boolean = false,
    val enabled: Boolean = true,
)

fun settingControlTag(key: String) = "setting-control:$key"

/**
 * One labelled, typed control on a settings page: the label, the web's help text, and a switch, number with its unit,
 * choice or text field. Secrets show only whether one is set.
 */
@Composable
fun SettingControl(def: SettingDef, state: SettingControlState, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(def.labelRes)
    RowCard(modifier.testTag(settingControlTag(def.key))) {
        when (val kind = def.kind) {
            SettingKind.Toggle -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) { Heading(label, def, state) }
                Switch(
                    checked = state.value.equals("true", ignoreCase = true),
                    onCheckedChange = { onChange(it.toString()) },
                    enabled = state.enabled && !state.managedByEnv,
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
            is SettingKind.Number -> {
                Heading(label, def, state)
                NumberField(kind, label, state, onChange)
            }
            is SettingKind.Choice -> {
                Heading(label, def, state)
                ChoiceField(kind, label, state, onChange)
            }
            is SettingKind.Text -> {
                Heading(label, def, state)
                OutlinedTextField(
                    value = state.value,
                    onValueChange = onChange,
                    enabled = state.enabled && !state.managedByEnv,
                    singleLine = true,
                    placeholder = kind.example?.let { { Text(it, color = VeilColors.Muted) } },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
            SettingKind.Secret -> {
                Heading(label, def, state)
                StatusLabel(
                    when {
                        state.managedByEnv -> stringResource(R.string.settings_secret_env)
                        state.secretConfigured -> stringResource(R.string.settings_secret_set)
                        else -> stringResource(R.string.settings_secret_not_set)
                    },
                )
            }
        }
    }
}

@Composable
private fun Heading(label: String, def: SettingDef, state: SettingControlState) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
    if (def.helpRes != 0) Text(stringResource(def.helpRes), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    if (state.managedByEnv && def.kind != SettingKind.Secret) {
        StatusLabel(stringResource(R.string.settings_managed_by_env), tone = StatusTone.Quiet)
    }
    if (state.restartOnly) StatusLabel(stringResource(R.string.settings_restart_marker), tone = StatusTone.Warning)
}

@Composable
private fun NumberField(kind: SettingKind.Number, label: String, state: SettingControlState, onChange: (String) -> Unit) {
    val problem = if (state.value.isBlank()) null else kind.problem(state.value)
    val bound = kind.min?.toString() ?: ""
    OutlinedTextField(
        value = state.value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        enabled = state.enabled && !state.managedByEnv,
        singleLine = true,
        isError = problem != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyLarge.tabular(),
        suffix = kind.unit?.let { { Text(it, color = VeilColors.Muted) } },
        supportingText = problem?.let {
            {
                Text(
                    when (it) {
                        NumberProblem.TooLow -> stringResource(R.string.settings_number_too_low, kind.min.toString())
                        NumberProblem.TooHigh -> stringResource(R.string.settings_number_too_high, kind.max.toString())
                        NumberProblem.NotANumber -> stringResource(R.string.settings_number_invalid, bound)
                    },
                )
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
}

@Composable
private fun ChoiceField(kind: SettingKind.Choice, label: String, state: SettingControlState, onChange: (String) -> Unit) {
    val labels = kind.options.map { stringResource(it.labelRes) }
    val index = kind.options.indexOfFirst { it.value == state.value }
    val enabled = state.enabled && !state.managedByEnv
    if (kind.options.size <= 4 && labels.all { it.length <= 12 }) {
        SegmentedControl(
            options = labels, selectedIndex = index, onSelect = { onChange(kind.options[it].value) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    } else {
        var open by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(
                onClick = { open = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = label },
            ) { Text(labels.getOrNull(index) ?: state.value.ifBlank { stringResource(R.string.settings_choice_unset) }, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                kind.options.forEachIndexed { i, option ->
                    DropdownMenuItem(text = { Text(labels[i]) }, onClick = { open = false; onChange(option.value) })
                }
            }
        }
    }
}
