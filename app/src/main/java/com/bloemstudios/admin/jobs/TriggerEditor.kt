package com.bloemstudios.admin.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.OutlinedButton
import com.bloemstudios.admin.ui.components.OutlinedTextField
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.components.VeilDialog
import com.bloemstudios.admin.ui.label
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.weekdayName
import kotlinx.coroutines.launch
import java.time.DayOfWeek

/**
 * The web's trigger form on a phone: a list of triggers, each an interval, a daily time, a weekly day and time, or
 * "at startup", with an optional maximum runtime. PUT .../triggers replaces the whole set, so a trigger of a type the
 * app does not know is kept as the server sent it. A save that finds the schedule changed (412 or 428) keeps the draft
 * and offers a review of the current schedule before another save.
 */
@Composable
internal fun EditTriggersDialog(
    taskName: String,
    initialTriggers: List<TaskTrigger>,
    onDismiss: () -> Unit,
    onSave: suspend (List<TaskTrigger>) -> UpdateTriggersResult,
    onReview: suspend () -> List<TaskTrigger> = { initialTriggers },
    onAdopt: () -> Unit = {},
) {
    val drafts = remember { mutableStateListOf<TriggerDraft>().apply { addAll(initialTriggers.map { it.toDraft() }) } }
    var errorMessage by remember { mutableStateOf<ErrorText?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf<List<TaskTrigger>?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showInvalid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reads = rememberForegroundReadLauncher()
    val result = triggersOf(drafts)

    VeilDialog(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Text(stringResource(R.string.jobs_editor_title, taskName), style = MaterialTheme.typography.titleMedium)
            drafts.forEachIndexed { index, draft ->
                TriggerCard(
                    number = index + 1,
                    draft = draft,
                    showInvalid = showInvalid,
                    enabled = !isSubmitting,
                    onChange = { drafts[index] = it },
                    onRemove = { drafts.removeAt(index) },
                )
            }
            if (drafts.isEmpty()) Text(stringResource(R.string.jobs_schedule_none), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            OutlinedButton(enabled = !isSubmitting, onClick = { drafts.add(TriggerDraft.new()) }, modifier = Modifier.testTag("trigger-add")) {
                Text(stringResource(R.string.jobs_editor_add))
            }
            Text(stringResource(R.string.jobs_page_schedule_server_time), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            Text(stringResource(R.string.jobs_editor_scope), style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
            if (showInvalid && result == null) Text(stringResource(R.string.jobs_editor_invalid), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall)
            if (conflict) {
                Text(stringResource(R.string.settings_conflict), color = VeilColors.Warning)
                TextButton(enabled = !isSubmitting, onClick = { reads.launch {
                    isSubmitting = true
                    try { reviewed = onReview(); errorMessage = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { errorMessage = ErrorText.Res(R.string.error_could_not_load_job_schedule) }
                    finally { isSubmitting = false }
                } }) { Text(stringResource(R.string.settings_review_current)) }
                reviewed?.let { triggers ->
                    Text(stringResource(R.string.jobs_schedule_current))
                    if (triggers.isEmpty()) Text(stringResource(R.string.jobs_schedule_none))
                    triggers.forEach { trigger -> Text(trigger.label()) }
                    TextButton(onClick = { onAdopt(); reviewed = null; conflict = false }) { Text(stringResource(R.string.settings_adopt_revision)) }
                }
            }
            errorMessage?.let { Text(it.resolve(), color = VeilColors.Danger, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    enabled = !conflict && !isSubmitting,
                    modifier = Modifier.testTag("trigger-save"),
                    onClick = {
                        val triggers = triggersOf(drafts)
                        if (triggers == null) { showInvalid = true; return@Button }
                        scope.launch {
                            isSubmitting = true
                            errorMessage = null
                            when (val saved = onSave(triggers)) {
                                is UpdateTriggersResult.Success -> onDismiss()
                                UpdateTriggersResult.Conflict -> { conflict = true; reviewed = null }
                                is UpdateTriggersResult.Failure -> errorMessage = saved.message
                            }
                            isSubmitting = false
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun TriggerCard(number: Int, draft: TriggerDraft, showInvalid: Boolean, enabled: Boolean, onChange: (TriggerDraft) -> Unit, onRemove: () -> Unit) {
    val invalid = showInvalid && draft.toTrigger() == null
    RowCard(Modifier.testTag("trigger-$number")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.jobs_editor_trigger_number, number), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            DestructiveTextButton(enabled = enabled, onClick = onRemove) { Text(stringResource(R.string.jobs_editor_remove, number)) }
        }
        if (draft.isUnknown) {
            Text(stringResource(R.string.jobs_editor_unknown, draft.type), style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
            return@RowCard
        }
        Choice(
            label = stringResource(R.string.jobs_editor_type),
            options = listOf("interval", "daily", "weekly", "startup"),
            selected = draft.type,
            text = { typeLabel(it) },
            enabled = enabled,
            tag = "trigger-$number-type",
        ) { onChange(draft.copy(type = it)) }
        when (draft.type) {
            "interval" -> Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = draft.intervalValue,
                    onValueChange = { onChange(draft.copy(intervalValue = it.filter(Char::isDigit))) },
                    label = { Text(stringResource(R.string.jobs_editor_interval_value)) },
                    singleLine = true, enabled = enabled,
                    isError = invalid && draft.intervalValue.toLongOrNull()?.let { it > 0 } != true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("trigger-$number-interval"),
                )
                Choice(
                    label = stringResource(R.string.jobs_editor_unit),
                    options = IntervalUnit.entries,
                    selected = draft.intervalUnit,
                    text = { unitLabel(it) },
                    enabled = enabled,
                    tag = "trigger-$number-unit",
                    modifier = Modifier.weight(1f),
                ) { onChange(draft.copy(intervalUnit = it)) }
            }
            "daily", "weekly" -> {
                if (draft.type == "weekly") Choice(
                    label = stringResource(R.string.jobs_editor_day),
                    options = (0..6).toList(),
                    selected = draft.day,
                    text = { weekdayName(weekday(it)) },
                    enabled = enabled,
                    tag = "trigger-$number-day",
                ) { onChange(draft.copy(day = it)) }
                OutlinedTextField(
                    value = draft.time,
                    onValueChange = { onChange(draft.copy(time = it.take(5))) },
                    label = { Text(stringResource(R.string.jobs_editor_time)) },
                    placeholder = { Text(stringResource(R.string.jobs_editor_time_hint)) },
                    singleLine = true, enabled = enabled,
                    isError = invalid && !isValidTimeOfDay(draft.time),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("trigger-$number-time"),
                )
            }
        }
        OutlinedTextField(
            value = draft.maxRuntimeMinutes,
            onValueChange = { onChange(draft.copy(maxRuntimeMinutes = it.filter { c -> c.isDigit() || c == '.' })) },
            label = { Text(stringResource(R.string.jobs_editor_max_runtime)) },
            placeholder = { Text(stringResource(R.string.jobs_editor_max_runtime_hint)) },
            singleLine = true, enabled = enabled,
            isError = invalid && draft.maxRuntimeMinutes.isNotBlank() && draft.maxRuntimeMinutes.toBigDecimalOrNull() == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("trigger-$number-max"),
        )
    }
}

/** The server numbers weekdays from Sunday = 0; java.time from Monday = 1. */
private fun weekday(serverDay: Int): DayOfWeek = DayOfWeek.of(if (serverDay == 0) 7 else serverDay)

@Composable
private fun typeLabel(type: String): String = when (type) {
    "interval" -> stringResource(R.string.jobs_editor_type_interval)
    "daily" -> stringResource(R.string.jobs_editor_type_daily)
    "weekly" -> stringResource(R.string.jobs_editor_type_weekly)
    else -> stringResource(R.string.jobs_editor_type_startup)
}

@Composable
private fun unitLabel(unit: IntervalUnit): String = when (unit) {
    IntervalUnit.Seconds -> stringResource(R.string.jobs_editor_unit_seconds)
    IntervalUnit.Minutes -> stringResource(R.string.jobs_editor_unit_minutes)
    IntervalUnit.Hours -> stringResource(R.string.jobs_editor_unit_hours)
}

/** A button that opens a short menu of choices and shows the chosen one. */
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    text: @Composable (T) -> String,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(text(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(text(option)) }, onClick = { open = false; onSelect(option) }, modifier = Modifier.testTag("$tag-${option.toString().lowercase()}"))
                }
            }
        }
    }
}
