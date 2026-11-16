package com.bloemstudios.admin.organization

import androidx.compose.foundation.border
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.VeilStateSurface
import com.bloemstudios.admin.ui.components.VeilStateSurfaceAction
import com.bloemstudios.admin.ui.components.VeilStateSurfaceKind
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * The frame every organization page shares: the last message, and either a loading, empty or failed surface or the
 * page's own rows. Rows are lazy. The top bar already names a page the menu opens, so only a page below one shows a title.
 *
 * [onRefresh] is the page's own load. When it is given, pulling the list down runs it and the indicator ends when it
 * returns, as on the platform pages; a page with nothing to reload leaves it null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrganizationPage(
    title: String,
    subtitle: String?,
    loading: Boolean,
    empty: Boolean,
    emptyMessage: String,
    error: ErrorText?,
    notice: ErrorText?,
    onRetry: () -> Unit,
    onDismissMessages: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    onRefresh: (suspend () -> Unit)? = null,
    /** True on a page below a page (a person, a group editor): it names its object. A page the menu opens is named by the top bar. */
    showTitle: Boolean = false,
    /** True where the line carries a fact the person needs (a refresh interval, the terms of support access), not an explanation. */
    keepSubtitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val pull = rememberPullToRefreshState()
    if (onRefresh != null && pull.isRefreshing) {
        ForegroundEffect(pull) {
            // Leaving the foreground cancels this, and the finally clears the indicator so a rapid return cannot replay it.
            if (!pull.isRefreshing) return@ForegroundEffect
            try { onRefresh() } finally { pull.endRefresh() }
        }
    }
    Box(if (onRefresh != null) modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection) else modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("organization-page"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        if (showTitle || (keepSubtitle && subtitle != null)) item {
            Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny)) {
                if (showTitle) Text(
                    title, style = MaterialTheme.typography.titleLarge, color = VeilColors.Text,
                    modifier = Modifier.semantics { heading() },
                )
                if (showTitle || keepSubtitle) subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VeilColors.Muted) }
            }
        }
        if (header != null) item { Column(verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small)) { header() } }
        notice?.let { item { MessageBanner(it.resolve(), isError = false, onDismiss = onDismissMessages) } }
        // A failure that leaves rows on screen is a banner; with nothing to show it becomes the page.
        if (error != null && !(loading || empty)) item { MessageBanner(error.resolve(), isError = true, onDismiss = onDismissMessages) }
        when {
            loading && empty -> item {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Loading, title = stringResource(R.string.organization_loading),
                    message = stringResource(R.string.organization_loading), stateDescription = stringResource(R.string.organization_loading),
                )
            }
            empty && error != null -> item {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Failure, title = stringResource(R.string.management_failed_title),
                    message = error.resolve(), stateDescription = error.resolve(),
                    action = VeilStateSurfaceAction(stringResource(R.string.organization_try_again), onRetry),
                )
            }
            empty -> item {
                VeilStateSurface(
                    kind = VeilStateSurfaceKind.Empty, title = stringResource(R.string.management_empty_title),
                    message = emptyMessage, stateDescription = emptyMessage,
                )
            }
            else -> content()
        }
    }
    if (onRefresh != null) PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** One message with a Dismiss. Errors speak up to assistive technology; confirmations do too, politely. */
@Composable
internal fun MessageBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    com.bloemstudios.admin.ui.components.MessageBanner(
        text, isError, onDismiss, tag = if (isError) "organization-error" else "organization-notice",
    )
}

/** A raised row for one record. */
@Composable
internal fun OrganizationCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VeilColors.Card, RoundedCornerShape(VeilShapes.CompactCardCorner))
            .border(androidx.compose.foundation.BorderStroke(1.dp, VeilColors.Hairline), RoundedCornerShape(VeilShapes.CompactCardCorner))
            .padding(VeilSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
        content = content,
    )
}

/** `2026-11-12T09:30:00Z` as the device would write it; anything that doesn't parse is shown as sent. */
internal fun formatWhen(iso: String?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withZone(zone).format(java.time.Instant.parse(iso))
    }.getOrDefault(iso)
}
