package com.bloemstudios.admin.automation

import com.bloemstudios.admin.ui.components.LucideIcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.components.ConfirmButton
import com.bloemstudios.admin.ui.components.RowCard
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.shell.ShellInteractionLockEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

/** The confirmation before a node, backup or plugin action. [destructive] colors the confirm button in the danger color. */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelLabel: String = stringResource(R.string.action_cancel),
) {
    ShellInteractionLockEffect(true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { ConfirmButton(onClick = onConfirm, destructive = destructive, modifier = Modifier.testTag("confirm-action")) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}

class MenuEntry(val label: String, val tag: String, val onClick: () -> Unit)

/** The three-dot menu of a row. It is left out when it has nothing to offer. */
@Composable
fun RowActionMenu(description: String, entries: List<MenuEntry>, enabled: Boolean = true, tag: String = "row-menu") {
    if (entries.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    ShellInteractionLockEffect(open)
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(tag)) {
            LucideIcon(R.drawable.lucide_ellipsis, contentDescription = description)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.label) }, onClick = { open = false; entry.onClick() }, modifier = Modifier.testTag(entry.tag))
            }
        }
    }
}

/** A section title inside a page, as the page's headings read to a screen reader. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = VeilColors.Text, modifier = modifier.fillMaxWidth().padding(top = VeilSpacing.Small).semantics { heading() })
}

/** Whether a failure means the server does not have this feature (an older server), as opposed to being down. */
fun isUnsupported(failure: Throwable?): Boolean = failure is ApiError && failure.statusCode in setOf(404, 405, 501)

/**
 * What an All servers section says when its server could not be read: sign in again, this server lacks the page, or it
 * could not be reached, each with the one thing to do next.
 */
@Composable
fun SectionFailure(server: AdminServer, failure: Throwable, unsupportedText: String, onSignIn: () -> Unit, onRetry: () -> Unit) {
    val auth = failure is ApiError && (failure.statusCode == 401 || failure.statusCode == 403)
    when {
        auth -> ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), onSignIn)
        isUnsupported(failure) -> RowCard { StatusLabel(unsupportedText, tone = StatusTone.Quiet) }
        else -> ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, server.displayName), onRetry)
    }
}

/** A label and its value on one line, the value in tabular figures. */
@Composable
fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color = VeilColors.Text) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), color = valueColor)
    }
}

/** A plain statement under a title: loading, empty, or what is unavailable. */
@Composable
fun QuietNote(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = VeilSpacing.Small)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted)
    }
}
