package com.bloemstudios.admin.libraries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.ConfirmButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.detail.RadioRow

/** Stopping scans is not undone by the app, so the dialog names the library and the confirm button is red. */
@Composable
internal fun StopScanDialog(library: AdminLibrary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("stop-scan-dialog"),
        title = { Text(stringResource(R.string.libraries_stop_title, library.name)) },
        text = { Text(stringResource(R.string.libraries_stop_message, library.name)) },
        confirmButton = {
            ConfirmButton(onClick = onConfirm, destructive = true, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.libraries_stop_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.libraries_stop_keep))
            }
        },
    )
}

/**
 * The choice the web's refresh dialog offers: missing metadata only (the default) or all of it. Refreshing everything
 * can take hours on a large library, so that choice has the red confirm button.
 */
@Composable
internal fun RefreshMetadataDialog(library: AdminLibrary, onConfirm: (RefreshScope) -> Unit, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(RefreshScope.MissingOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("refresh-metadata-dialog"),
        title = { Text(stringResource(R.string.libraries_refresh_title, library.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
                RadioRow(
                    selected = scope == RefreshScope.MissingOnly,
                    label = stringResource(R.string.libraries_refresh_missing_title),
                    detail = stringResource(R.string.libraries_refresh_missing_detail),
                    onSelect = { scope = RefreshScope.MissingOnly },
                )
                RadioRow(
                    selected = scope == RefreshScope.All,
                    label = stringResource(R.string.libraries_refresh_all_title),
                    detail = stringResource(R.string.libraries_refresh_all_detail),
                    onSelect = { scope = RefreshScope.All },
                )
            }
        },
        confirmButton = {
            ConfirmButton(
                onClick = { onConfirm(scope) }, destructive = scope == RefreshScope.All,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
            ) {
                Text(stringResource(if (scope == RefreshScope.All) R.string.libraries_refresh_confirm_all else R.string.libraries_refresh_confirm_missing))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
