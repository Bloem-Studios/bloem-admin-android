package com.bloemstudios.admin.organization

import androidx.compose.foundation.layout.height
import com.bloemstudios.admin.ui.components.RowCard
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import com.bloemstudios.admin.ui.components.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

// ---- Overview -----------------------------------------------------------------------------------------

@Composable
fun OrganizationOverviewScreen(session: OrganizationSession) {
    val viewModel = viewModel<OverviewViewModel>()
    val state by viewModel.state.collectAsState()
    ForegroundEffect(session) { viewModel.load(session) }
    val scope = rememberCoroutineScope()
    OverviewContent(state, onRefresh = { viewModel.load(session) }) { scope.launch { viewModel.load(session) } }
}

@Composable
internal fun OverviewContent(state: OverviewState, onRefresh: (suspend () -> Unit)? = null, onRetry: () -> Unit) {
    val summary = state.summary
    OrganizationPage(
        title = stringResource(R.string.organization_overview_title),
        subtitle = null,
        loading = state.loading, empty = summary == null,
        emptyMessage = stringResource(R.string.organization_error_overview),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = {}, onRefresh = onRefresh,
    ) {
        if (summary == null) return@OrganizationPage
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                StatCard(stringResource(R.string.organization_overview_members), summary.membership_count,
                    pluralStringResource(R.plurals.organization_overview_active_members, summary.active_membership_count.toInt(), summary.active_membership_count.toInt()),
                    Modifier.weight(1f).fillMaxHeight())
                StatCard(stringResource(R.string.organization_overview_profiles), summary.profile_count, null, Modifier.weight(1f).fillMaxHeight())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Small), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                StatCard(stringResource(R.string.organization_overview_owned_libraries), summary.library_count, null, Modifier.weight(1f).fillMaxHeight())
                StatCard(stringResource(R.string.organization_overview_shared_libraries), summary.entitlement_count, null, Modifier.weight(1f).fillMaxHeight())
            }
        }
        item {
            OrganizationCard {
                Text(stringResource(R.string.organization_overview_about), style = MaterialTheme.typography.titleSmall, color = VeilColors.Text)
                Fact(stringResource(R.string.organization_overview_status), statusLabel(summary.status))
                Fact(
                    stringResource(R.string.organization_overview_owner),
                    when {
                        summary.owner_account_id == null -> stringResource(R.string.organization_overview_no_owner)
                        state.ownerName != null -> state.ownerName
                        state.ownerResolved -> stringResource(R.string.organization_overview_owner_unknown)
                        else -> stringResource(R.string.organization_loading)
                    },
                )
                if (summary.slug.isNotBlank()) Fact(stringResource(R.string.organization_overview_short_name), summary.slug)
                Fact(stringResource(R.string.organization_overview_revision), summary.policy_revision.toString())
            }
        }
    }
}

/** One figure of the overview, in the same Stat the dashboard uses, on a row card. */
@Composable
private fun StatCard(label: String, value: Long, detail: String?, modifier: Modifier = Modifier) {
    RowCard(modifier.semantics(mergeDescendants = true) {}) {
        com.bloemstudios.admin.ui.components.Stat(label, java.text.NumberFormat.getIntegerInstance().format(value), detail = detail, large = true)
    }
}

@Composable
internal fun statusLabel(status: String): String = when (status) {
    "active" -> stringResource(R.string.organization_status_active)
    "suspended" -> stringResource(R.string.organization_status_suspended)
    "invited" -> stringResource(R.string.organization_status_invited)
    "initializing" -> stringResource(R.string.organization_status_initializing)
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
internal fun ActionButton(text: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = description },
    ) { Text(text) }
}

/** A labelled dropdown. [options] are (value, label); the null value is "any". */
@Composable
internal fun FilterMenu(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = VeilColors.Muted)
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget).semantics { contentDescription = "$label: $selected" },
        ) { Text(selected, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onSelect(value) },
                    modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget),
                )
            }
        }
    }
}
