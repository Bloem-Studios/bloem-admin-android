package com.bloemstudios.admin.ui.components

import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.realtime.ForegroundEffect

@Composable
internal fun ManagementState(loading: Boolean, empty: Boolean, error: String?, emptyTitle: String? = null, onRetry: () -> Unit) {
    val loadingText = stringResource(R.string.management_loading)
    val emptyText = ""
    val emptyHeading = emptyTitle ?: stringResource(R.string.management_empty_title)
    if (loading || empty) VeilStateSurface(
        kind = if (loading) VeilStateSurfaceKind.Loading else if (error != null) VeilStateSurfaceKind.Failure else VeilStateSurfaceKind.Empty,
        title = if (loading) loadingText else if (error != null) stringResource(R.string.management_failed_title) else emptyHeading,
        message = if (loading) loadingText else error ?: emptyText,
        stateDescription = if (loading) loadingText else error ?: emptyHeading,
        modifier = Modifier.padding(VeilSpacing.CompactHorizontal),
        action = if (loading) null else VeilStateSurfaceAction(stringResource(R.string.action_retry), onRetry)
    )
}

/** Keeps unfinished servers visible while completed sections remain interactive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AggregateRefreshSurface(pending: List<AdminServer>, onRefresh: suspend () -> Unit, content: @Composable () -> Unit) {
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) ForegroundEffect(pull) {
        // Cancellation clears the intent before a rapid START can replay it.
        if (!pull.isRefreshing) return@ForegroundEffect
        try { onRefresh() } finally { pull.endRefresh() }
    }
    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        Column(Modifier.fillMaxSize()) {
            if (pending.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.management_pending_servers, pending.joinToString { it.displayName }),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(VeilSpacing.CompactHorizontal))
            }
            // Also gives keyboard/accessibility users an explicit refresh control: a quiet icon and word at the page
            // edge, not a bare word over the content.
            Row(Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Small), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { pull.startRefresh() },
                    enabled = !pull.isRefreshing,
                    colors = ButtonDefaults.textButtonColors(contentColor = VeilColors.Muted),
                ) {
                    LucideIcon(R.drawable.lucide_rotate_cw, contentDescription = null, size = LucideSize.Inline)
                    Spacer(Modifier.width(VeilSpacing.Small))
                    Text(stringResource(R.string.management_refresh))
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
        PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}
