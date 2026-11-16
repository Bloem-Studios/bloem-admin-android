package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

/**
 * A sheet that rises from the bottom with one record's details: a title, a line under it, the actions for the whole record
 * (Copy, Share, open its logs) so they are in reach without scrolling, then the fields. The content scrolls; the sheet
 * opens fully so a long attribute list is readable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VeilColors.Raised,
        contentColor = VeilColors.Text,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = VeilSpacing.CompactHorizontal)
                .padding(bottom = VeilSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                SelectionContainer {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.semantics { heading() })
                }
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = VeilColors.Muted) }
            }
            actions?.let { actionRow ->
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                    actionRow()
                }
            }
            content()
        }
    }
}

/** One labelled value in a detail sheet, in tabular figures so identifiers and addresses line up; the value can be selected. */
@Composable
fun DetailField(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
        SelectionContainer {
            Text(
                value?.takeIf(String::isNotBlank) ?: stringResource(R.string.detail_no_value),
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = VeilColors.Text,
            )
        }
    }
}

/** Two short fields side by side; at a large font scale (see [stacked]) they stack so neither wraps mid-word. */
@Composable
fun DetailPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    stacked: Boolean = false,
) {
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) { first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth()) }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
        first(Modifier.weight(1f)); second(Modifier.weight(1f))
    }
}

/** One choice in a [ChoiceSheet]. [id] null is the "all" entry that clears the filter. */
data class Choice(val id: String?, val label: String, val detail: String? = null)

/**
 * A field that shows the current choice and opens a [ChoiceSheet] when tapped. It looks like the app's outlined text
 * field, so a filter bar reads as one set of controls.
 */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { LucideIcon(R.drawable.lucide_chevron_down, contentDescription = null, tint = VeilColors.Muted) },
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            colors = veilFieldColors().copy(
                disabledTextColor = VeilColors.Muted,
                disabledLabelColor = VeilColors.Muted,
                disabledTrailingIconColor = VeilColors.Muted,
            ),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled, onClickLabel = label, role = Role.DropdownList, onClick = onClick),
        )
    }
}

/**
 * A sheet of choices with an optional search box, for a list that is too long for a menu (every account on a server).
 * The chosen entry carries a check mark; tapping any entry picks it and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(
    title: String,
    choices: List<Choice>,
    selectedId: String?,
    onSelect: (Choice) -> Unit,
    onDismiss: () -> Unit,
    searchPlaceholder: String? = null,
    emptyText: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(choices, query) {
        val needle = query.trim()
        if (needle.isEmpty()) choices
        else choices.filter { it.id == null || it.label.contains(needle, ignoreCase = true) || it.detail?.contains(needle, ignoreCase = true) == true }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VeilColors.Raised,
        contentColor = VeilColors.Text,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = VeilSpacing.CompactHorizontal),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = VeilColors.Text, modifier = Modifier.semantics { heading() })
            if (searchPlaceholder != null) SearchField(query, { query = it }, searchPlaceholder)
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = VeilSpacing.Large)) {
                items(shown, key = { it.id ?: "" }) { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = VeilSpacing.MinimumTouchTarget)
                            .clickable(role = Role.RadioButton) { onSelect(choice) }
                            .padding(vertical = VeilSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = VeilColors.Text)
                            choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
                        }
                        if (choice.id == selectedId) LucideIcon(R.drawable.lucide_check, contentDescription = stringResource(R.string.choice_selected), tint = VeilColors.Accent)
                    }
                }
                if (shown.none { it.id != null } && emptyText != null) {
                    item { Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted, modifier = Modifier.padding(vertical = VeilSpacing.Medium)) }
                }
            }
        }
    }
}
