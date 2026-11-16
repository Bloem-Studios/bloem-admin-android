package com.bloemstudios.admin.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.realtime.rememberForegroundReadLauncher
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.ui.components.AggregateRefreshSurface
import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.components.ServerProblemRow
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing

/**
 * All servers › Organizations: each saved server's organizations in its own section, with their status and counts. The page only
 * reads: to change an organization, open that server on its own.
 */
@Composable
fun AggregatePlatformOrganizationsScreen(
    servers: List<AdminServer>,
    clientFor: (AdminServer) -> ApiClient,
    onSignIn: (AdminServer) -> Unit,
) {
    val viewModel = viewModel<AggregatePlatformOrganizationsViewModel>()
    val sections by viewModel.sections.collectAsState()
    val reads = rememberForegroundReadLauncher()
    ForegroundEffect(servers, clientFor) { viewModel.load(servers, clientFor) }
    AggregateRefreshSurface(
        pending = servers.filter { server -> sections.none { it.server.id == server.id } },
        onRefresh = { viewModel.load(servers, clientFor) },
    ) {
        AggregatePlatformOrganizationsContent(
            sections = sections,
            onSignIn = onSignIn,
            onRetry = { reads.launch { viewModel.load(servers, clientFor) } },
            onMore = { server -> reads.launch { viewModel.loadMore(server, clientFor) } },
        )
    }
}

@Composable
internal fun AggregatePlatformOrganizationsContent(
    sections: List<ServerSectionResult<ServerOrganizations>>,
    onSignIn: (AdminServer) -> Unit,
    onRetry: () -> Unit,
    onMore: (AdminServer) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().testTag("platform-orgs-aggregate"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        item {
            SearchField(
                value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.platform_orgs_search_hint),
                modifier = Modifier.testTag("platform-orgs-search"),
            )
        }
        sections.forEach { section ->
            val server = section.server
            val failure = section.result.exceptionOrNull()
            val organizations = section.result.getOrNull()
            // A plain Silo server has no organizations and is not part of this Bloem page: no section, no note.
            if (organizations?.supported == false) return@forEach
            item(key = "header-${server.id}") { ServerGroupHeader(server.displayName) }
            when {
                failure is ApiError && failure.statusCode == 401 ->
                    item(key = "problem-${server.id}") { ServerProblemRow(stringResource(R.string.streams_aggregate_sign_in_required), { onSignIn(server) }) }
                failure is ApiError && failure.statusCode == 403 ->
                    item(key = "problem-${server.id}") { Caption(stringResource(R.string.platform_orgs_no_access), warning = true) }
                failure != null ->
                    item(key = "problem-${server.id}") { ServerProblemRow(stringResource(R.string.streams_aggregate_unreachable, server.displayName), onRetry) }
                organizations == null -> Unit
                organizations.organizations.isEmpty() -> item(key = "none-${server.id}") { Caption(stringResource(R.string.platform_orgs_aggregate_none)) }
                else -> {
                    val rows = filterOrganizations(organizations.organizations, query)
                    if (rows.isEmpty()) item(key = "none-${server.id}") { Caption(stringResource(R.string.platform_orgs_aggregate_filtered)) }
                    items(rows, key = { "${server.id}:${it.id}" }) { organization ->
                        ListRowCard(
                            title = organization.name, supporting = listOf(organization.slug, countsLine(organization)),
                            modifier = Modifier.testTag("platform-org-${server.id}-${organization.id}"),
                            trailing = { OrganizationStatusLabel(organization.status) },
                        )
                    }
                    if (organizations.nextCursor != null) item(key = "more-${server.id}") {
                        TextButton(onClick = { onMore(server) }, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-orgs-more-${server.id}")) {
                            Text(stringResource(R.string.platform_orgs_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, warning: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = if (warning) VeilColors.Warning else VeilColors.Muted,
        modifier = Modifier.padding(vertical = VeilSpacing.Small),
    )
}
