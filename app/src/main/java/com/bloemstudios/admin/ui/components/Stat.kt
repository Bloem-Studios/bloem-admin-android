package com.bloemstudios.admin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.tabular

/**
 * A label, a figure and an optional detail: the one number block the dashboard and the organization overview share.
 * [large] leads a screen (two of them side by side); a compact one sits in a line of catalog counts. [live] puts the
 * tally dot beside the label, for a count of things happening right now.
 */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    large: Boolean = false,
    live: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) {
            if (live) TallyDot()
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            value,
            style = (if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium).tabular(),
            color = VeilColors.Text,
        )
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted)
    }
}
