package com.bloemstudios.admin.platform

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.FilterMenu
import com.bloemstudios.admin.organization.OrganizationPage
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.ui.components.ListRowCard
import com.bloemstudios.admin.ui.components.SearchField
import com.bloemstudios.admin.ui.components.StatusLabel
import com.bloemstudios.admin.ui.components.StatusTone
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.VeilSpacing
import androidx.compose.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Platform › Organizations: every organization on the server, searched by name and filtered by status, with its people,
 * profile and library counts. Opening a row shows that organization. [supportAvailable] says the account may use the
 * Owner's support access; [onOpenOrganization] enters an organization as its administrator.
 */
@Composable
fun PlatformOrganizationsScreen(
    session: PlatformSession,
    account: ApiClient,
    serverId: String,
    supportAvailable: Boolean,
    onOpenOrganization: (organizationId: String) -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openId != null) { openId = null }
    openId?.let { id ->
        PlatformOrganizationScreen(
            session = session, account = account, serverId = serverId, organizationId = id,
            supportAvailable = supportAvailable, onClose = { openId = null }, onOpenOrganization = onOpenOrganization,
        )
        return
    }

    val viewModel = viewModel<PlatformOrganizationsViewModel>()
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // Loads on open, and again when the administrator comes back from one organization or from another app: its status may have changed.
    ForegroundEffect(session, openId) { viewModel.load(session) }
    PlatformOrganizationsContent(
        state = state,
        onSearch = { query ->
            searchJob?.cancel()
            searchJob = scope.launch {
                delay(SEARCH_DELAY_MILLIS)
                viewModel.load(session, query = query)
            }
        },
        onStatus = { status -> scope.launch { viewModel.load(session, status = status) } },
        onRetry = { scope.launch { viewModel.load(session) } },
        onMore = { scope.launch { viewModel.loadMore(session) } },
        onOpen = { openId = it.id },
        onDismiss = viewModel::dismissError,
        onRefresh = { viewModel.load(session) },
    )
}

private const val SEARCH_DELAY_MILLIS = 300L

@Composable
internal fun PlatformOrganizationsContent(
    state: PlatformOrganizationsState,
    onSearch: (String) -> Unit,
    onStatus: (String?) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (OrganizationSummary) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (suspend () -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val statusOptions = listOf<Pair<String?, String>>(
        null to stringResource(R.string.platform_orgs_status_any),
        "active" to stringResource(R.string.platform_orgs_status_active),
        "suspended" to stringResource(R.string.platform_orgs_status_suspended),
        "initializing" to stringResource(R.string.platform_orgs_status_initializing),
    )
    OrganizationPage(
        title = stringResource(R.string.platform_orgs_nav), subtitle = null,
        loading = state.loading, empty = state.organizations.isEmpty(),
        emptyMessage = stringResource(R.string.platform_orgs_empty),
        error = state.error, notice = null, onRetry = onRetry, onDismissMessages = onDismiss, onRefresh = onRefresh,
        header = {
            SearchField(
                value = query, onValueChange = { query = it; onSearch(it) },
                placeholder = stringResource(R.string.platform_orgs_search_hint),
                modifier = Modifier.testTag("platform-orgs-search"),
            )
            FilterMenu(
                label = stringResource(R.string.platform_orgs_status_label),
                selected = statusOptions.first { it.first == state.status }.second,
                options = statusOptions, onSelect = onStatus,
                modifier = Modifier.testTag("platform-orgs-status"),
            )
        },
    ) {
        items(state.organizations, key = { it.id }) { organization ->
            ListRowCard(
                title = organization.name,
                supporting = listOf(organization.slug, countsLine(organization)),
                onClick = { onOpen(organization) },
                modifier = Modifier.testTag("platform-org-${organization.id}"),
                trailing = { OrganizationStatusLabel(organization.status) },
            )
        }
        if (state.nextCursor != null) item {
            TextButton(
                onClick = onMore, enabled = !state.loadingMore,
                modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget).testTag("platform-orgs-more"),
            ) { Text(stringResource(if (state.loadingMore) R.string.organization_loading else R.string.platform_orgs_more)) }
        }
    }
}

/** "12 people · 25 profiles · 3 libraries". */
@Composable
internal fun countsLine(organization: OrganizationSummary): String = stringResource(
    R.string.platform_orgs_counts,
    pluralStringResource(R.plurals.platform_orgs_people, organization.membership_count.toInt(), organization.membership_count.toInt()),
    pluralStringResource(R.plurals.platform_orgs_profiles, organization.profile_count.toInt(), organization.profile_count.toInt()),
    pluralStringResource(R.plurals.platform_orgs_libraries, organization.library_count.toInt(), organization.library_count.toInt()),
)

/** An organization's status in the web's words; anything but active needs a look, so it is in the warning color. */
@Composable
internal fun OrganizationStatusLabel(status: String, modifier: Modifier = Modifier) {
    val text = when (status) {
        "active" -> stringResource(R.string.platform_orgs_status_active)
        "suspended" -> stringResource(R.string.platform_orgs_status_suspended)
        "initializing" -> stringResource(R.string.platform_orgs_status_initializing)
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    StatusLabel(text, modifier, tone = if (status == "active") StatusTone.Quiet else StatusTone.Warning, dot = status != "active")
}
