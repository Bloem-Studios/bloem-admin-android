package com.bloemstudios.admin.users.detail

import androidx.compose.foundation.border
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing

/** A titled block of the User page. */
@Composable
internal fun DetailCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Column(
            Modifier
                .fillMaxWidth()
                .background(VeilColors.Card, RoundedCornerShape(VeilShapes.CompactCardCorner))
                .border(androidx.compose.foundation.BorderStroke(1.dp, VeilColors.Hairline), RoundedCornerShape(VeilShapes.CompactCardCorner))
                .padding(VeilSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)
        ) { content() }
    }
}

/** A checkbox whose whole row, label included, toggles it, and is at least 48dp tall. */
@Composable
internal fun LabeledCheckbox(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .heightIn(min = VeilSpacing.MinimumTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = VeilSpacing.Small))
        Text(label)
    }
}

/** The body of a [Section]: loading, the server's own failure with Retry, or the loaded value. */
@Composable
internal fun <T> SectionBody(section: Section<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (section) {
        Section.Loading -> Text(stringResource(R.string.management_loading), color = VeilColors.Muted)
        is Section.Failed -> {
            Text(section.message.resolve(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_retry))
            }
        }
        is Section.Loaded -> content(section.value)
    }
}

@Composable
internal fun FieldLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
        Text(label, color = VeilColors.Muted, modifier = Modifier.width(96.dp))
        Text(value)
    }
}

/** A radio choice whose whole row selects it, at least 48dp tall. [detail] explains the choice under its label. */
@Composable
internal fun RadioRow(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
    enabled: Boolean = true,
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = VeilSpacing.MinimumTouchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(end = VeilSpacing.Small))
        Column(Modifier.padding(vertical = VeilSpacing.Tiny)) {
            Text(label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
        }
    }
}
