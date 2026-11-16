package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * The one row surface of list pages: a 12dp card on the page with a hairline edge and 12dp inside. Rows are 8dp apart
 * (the list's own spacing). When [onClick] is set the whole card is the tap target.
 */
@Composable
fun RowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(VeilShapes.CompactCardCorner)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(VeilColors.Card, shape)
            .border(BorderStroke(1.dp, VeilColors.Hairline), shape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = VeilSpacing.MinimumTouchTarget)
            .padding(VeilSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
        content = content,
    )
}

/** A row card with a title, up to a few supporting lines and a trailing slot (a status label, a menu). */
@Composable
fun ListRowCard(
    title: String,
    modifier: Modifier = Modifier,
    supporting: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    RowCard(modifier, onClick, enabled) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting.filter(String::isNotBlank).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
                }
            }
            trailing?.invoke()
        }
    }
}

/** A full-width outlined search field with a search icon and a clear button. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { LucideIcon(R.drawable.lucide_search, contentDescription = null, tint = VeilColors.Muted) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                    LucideIcon(R.drawable.lucide_x, contentDescription = stringResource(R.string.action_clear), tint = VeilColors.Muted)
                }
            }
        } else null,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The surface of a custom dialog: the theme's 16dp corners on the raised color, with a hairline. Never a square Surface. */
@Composable
fun VeilDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = VeilColors.Raised,
            contentColor = VeilColors.Text,
            border = BorderStroke(1.dp, VeilColors.Hairline),
        ) {
            Column(Modifier.padding(VeilSpacing.Large), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium), content = content)
        }
    }
}

/**
 * One message with a Dismiss: an error in the danger color or a confirmation in the ok color, on a card. Errors speak up
 * to assistive technology at once; confirmations do so politely.
 */
@Composable
fun MessageBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val shape = RoundedCornerShape(VeilShapes.CompactCardCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(VeilColors.Card, shape)
            .border(BorderStroke(1.dp, VeilColors.Hairline), shape)
            .padding(start = VeilSpacing.Medium)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = if (isError) VeilColors.Danger else VeilColors.Ok,
            modifier = Modifier.weight(1f).padding(vertical = VeilSpacing.Small),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

/** The name of a server above its rows, in the All servers pages. */
@Composable
fun ServerGroupHeader(name: String, modifier: Modifier = Modifier) {
    Text(
        name, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text,
        modifier = modifier.fillMaxWidth().padding(top = VeilSpacing.Medium).semantics { heading() },
    )
}

/** A server that cannot be shown: what happened, in the warning color, and the whole row taps to fix it (sign in, try again). */
@Composable
fun ServerProblemRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RowCard(modifier, onClick = onClick) {
        StatusLabel(text, tone = StatusTone.Warning, dot = true)
    }
}
