package com.bloemstudios.admin.fixtures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bloemstudios.admin.activity.ActivityScreen
import com.bloemstudios.admin.audit.AuditScreen
import com.bloemstudios.admin.automation.AutomationPages
import com.bloemstudios.admin.backups.BackupsScreen
import com.bloemstudios.admin.apikeys.ApiKeysScreen
import com.bloemstudios.admin.dashboard.DashboardSections
import com.bloemstudios.admin.dashboard.DashboardSectionsData
import com.bloemstudios.admin.jobs.JobsScreen
import com.bloemstudios.admin.libraries.LibrariesScreen
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.logs.LogsScreen
import com.bloemstudios.admin.nodes.NodesScreen
import com.bloemstudios.admin.alerts.AlertDeliveryStatus
import com.bloemstudios.admin.alerts.AlertSettingsSection
import com.bloemstudios.admin.lock.AppSettingsScreen
import com.bloemstudios.admin.organization.AdminAuthority
import com.bloemstudios.admin.organization.AdminContext
import com.bloemstudios.admin.organization.AdminContextScope
import com.bloemstudios.admin.organization.OrganizationAdminShell
import com.bloemstudios.admin.organization.OverviewContent
import com.bloemstudios.admin.organization.OverviewState
import com.bloemstudios.admin.organization.PeopleContent
import com.bloemstudios.admin.organization.PeopleState
import com.bloemstudios.admin.plugins.PluginsScreen
import com.bloemstudios.admin.serverconfig.ServerSettingsHost
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.platform.AggregatePlatformOrganizationsContent
import com.bloemstudios.admin.platform.OpenAccess
import com.bloemstudios.admin.platform.OrganizationPlanState
import com.bloemstudios.admin.platform.PlanPreview
import com.bloemstudios.admin.platform.PlanWords
import com.bloemstudios.admin.platform.PlatformOrganizationContent
import com.bloemstudios.admin.platform.PlatformOrganizationState
import com.bloemstudios.admin.platform.PlatformOrganizationsContent
import com.bloemstudios.admin.platform.PlatformOrganizationsState
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.requests.BulkFailure
import com.bloemstudios.admin.requests.BulkProgress
import com.bloemstudios.admin.requests.BulkRequestAction
import com.bloemstudios.admin.requests.RequestBulkCallbacks
import com.bloemstudios.admin.requests.RequestQueueState
import com.bloemstudios.admin.requests.RequestsContent
import com.bloemstudios.admin.streams.SendMessageResult
import com.bloemstudios.admin.streams.StreamActions
import com.bloemstudios.admin.streams.StreamsVeilContent
import androidx.compose.runtime.CompositionLocalProvider
import com.bloemstudios.admin.ui.shell.ActivityScopeControl
import com.bloemstudios.admin.ui.shell.ActivityScopeOption
import com.bloemstudios.admin.ui.shell.LocalAppSettingsRequest
import com.bloemstudios.admin.ui.shell.AdminShell
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.START_DESTINATION_ID
import com.bloemstudios.admin.ui.shell.aggregateDestinations
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.users.UsersScreen

/**
 * The real shell and screens fed from [FixtureData]: used by the debug-only [FixtureActivity] and the screenshot tour.
 * [variant] picks a state of the page: on `work.requests`, `select`, `progress`, `failed`, `unavailable` or `stale`.
 */
@Composable
fun FixtureServer(
    startPage: String,
    settingsRoute: String = "home",
    logsFocus: LogsFocus? = null,
    variant: String? = null,
    /** False draws a plain Silo server: no Platform pages (organizations, support access) in the menu. */
    bloemServer: Boolean = true,
) {
    // The organization page and the plan preview sit below Platform > Organizations: the menu still shows that page.
    var selected by rememberSaveable { mutableStateOf(if (startPage in PLATFORM_ORGANIZATION_PAGES) "platform.organizations" else startPage) }
    CompositionLocalProvider(LocalAppSettingsRequest provides {}) {
    AdminShell(
        context = AdminShellContext(if (bloemServer) "Bloem Home" else "Silo Home", isAggregate = false),
        activityScope = fixtureScope(aggregate = false),
        destinations = singleServerDestinations(
            tenantsVisible = false, supportAccessVisible = bloemServer, organizationsVisible = bloemServer,
            automation = AutomationPages(nodes = true, backups = true, plugins = true), apiKeysVisible = true,
        ),
        destinationBadges = mapOf("work.requests" to FixtureData.requestCounts.needs_approval),
        selectedDestinationId = selected,
        onDestinationSelected = { selected = it },
        useNavigationRail = false,
        onContextRequested = {},
    ) { id ->
        when (id) {
            "live.streams" -> StreamsVeilContent(
                hasLoadedOnce = true, sessions = FixtureData.sessions, errorMessage = null, onRetry = {},
                actions = StreamActions({}, {}, {}, {}, { _, _, _ -> SendMessageResult.Success }, onOpenLogs = { _, _ -> }),
                sourceState = RealtimeState.Live, sessionDataAvailable = true, showClientIp = true,
            )
            "overview.dashboard" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) {
                item {
                    DashboardSections(
                        DashboardSectionsData(
                            stats = FixtureData.stats, hostStats = FixtureData.host, hostErrorMessage = null, hostUnavailable = false,
                            sessions = FixtureData.sessions, sessionsErrorMessage = null, sourceState = RealtimeState.Live, sourceError = null,
                            insights = FixtureData.insights, onStreamsRequested = { selected = "live.streams" },
                            onUsersRequested = { selected = "people.users" },
                        ),
                    )
                }
            }
            "people.users" -> UsersScreen(FixtureData.usersClient)
            "system.server" -> ServerSettingsHost(FixtureSettings.client, scopeKey = "fixture", initialRoute = settingsRoute)
            "live.activity" -> ActivityScreen(FixtureIncidentData.client, onOpenLogs = { _, _ -> })
            "system.logs" -> LogsScreen(FixtureIncidentData.client, focus = logsFocus)
            "system.audit" -> AuditScreen(FixtureIncidentData.client, onOpenLogs = {})
            "work.jobs" -> JobsScreen(FixtureAutomation.client, onOpenBackups = { selected = "system.backups" })
            "playback.nodes" -> NodesScreen(FixtureAutomation.client)
            "system.backups" -> BackupsScreen(FixtureAutomation.client)
            "system.plugins" -> PluginsScreen(FixtureAutomation.client)
            "content.libraries" -> LibrariesScreen(remember { LibraryFixtures().client })
            "work.requests" -> FixtureRequests(variant)
            "system.api-keys" -> ApiKeysScreen(FixtureData.apiKeysClient)
            "platform.organizations" -> when (startPage) {
                "platform.organization" -> FixturePlatformOrganization(plan = false)
                "platform.organization-plan" -> FixturePlatformOrganization(plan = true)
                else -> PlatformOrganizationsContent(
                    PlatformOrganizationsState(loading = false, organizations = FixtureData.platformOrganizations, nextCursor = "next"),
                    onSearch = {}, onStatus = {}, onRetry = {}, onMore = {}, onOpen = {}, onDismiss = {},
                )
            }
            else -> Box(Modifier.fillMaxSize())
        }
    }
    }
}

/** The switcher and drawer foot the real app builds: two invented servers, Add server, and Sign out. Nothing here talks to a server. */
private fun fixtureScope(aggregate: Boolean) = ActivityScopeControl(
    allServers = aggregate,
    selectedServerId = if (aggregate) null else "home",
    servers = listOf(ActivityScopeOption("home", "Bloem Home"), ActivityScopeOption("garden", "Garden")),
    onSelectAllServers = {}, onSelectServer = {},
    onAddServer = {}, onSignOut = {},
)

private val PLATFORM_ORGANIZATION_PAGES = setOf("platform.organization", "platform.organization-plan")

/** One organization of Platform › Organizations as an administrator who can open it sees it; [plan] shows a previewed plan change. */
@Composable
private fun FixturePlatformOrganization(plan: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val words = remember(context) { PlanWords(context.resources) }
    val organization = FixtureData.platformOrganizations.first()
    PlatformOrganizationContent(
        state = PlatformOrganizationState(
            loading = false, organization = organization, members = FixtureData.platformMembers, membersNext = "next",
            ownerName = "pat", ownerResolved = true, openAccess = OpenAccess.Membership,
        ),
        plan = OrganizationPlanState(
            loading = false, entitlement = FixtureData.platformEntitlement, templates = FixtureData.platformTemplates,
            selectedKey = if (plan) "premium" else null,
            preview = if (plan) PlanPreview(FixtureData.platformTemplates[1], FixtureData.platformDryRun) else null,
        ),
        words = words, onBack = {}, onOpen = {}, onSetSuspended = {}, onMoreMembers = {}, onSelectPlan = {}, onPreviewPlan = {},
        onApplyPlan = {}, onRetry = {}, onDismiss = {}, onDismissPlan = {},
    )
}

/** All servers, with Organizations in the menu and each saved server's organizations in its own section. */
@Composable
fun FixtureAllServers(startPage: String, bloemServer: Boolean = true) {
    val destinations = aggregateDestinations(organizationsVisible = bloemServer)
    // A plain Silo server has no Organizations page: a link to it opens the start page instead.
    var selected by rememberSaveable { mutableStateOf(startPage.takeIf { page -> destinations.any { it.id == page } } ?: START_DESTINATION_ID) }
    AdminShell(
        context = AdminShellContext("All servers", isAggregate = true),
        destinations = destinations,
        selectedDestinationId = selected,
        onDestinationSelected = { selected = it },
        useNavigationRail = false,
        onContextRequested = {},
    ) { id ->
        when (id) {
            "platform.organizations" -> AggregatePlatformOrganizationsContent(FixtureData.platformAggregate, onSignIn = {}, onRetry = {}, onMore = {})
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

/** The Requests page's Needs approval view in the state [variant] names. Nothing here talks to a server. */
@Composable
private fun FixtureRequests(variant: String?) {
    val pending = FixtureData.pendingRequests
    val failureRow = pending[2]
    val state = when (variant) {
        "select" -> RequestQueueState(
            counts = FixtureData.requestCounts, requests = pending, hasLoaded = true, requesterNames = FixtureData.requesterNames,
            selecting = true, selected = setOf("r1", "r2", "r4"),
        )
        "progress" -> RequestQueueState(
            counts = FixtureData.requestCounts, requests = pending, hasLoaded = true, requesterNames = FixtureData.requesterNames,
            selecting = true, selected = pending.mapTo(HashSet()) { it.id }, bulk = BulkProgress(BulkRequestAction.Approve, total = 4, done = 3),
        )
        "failed" -> RequestQueueState(
            counts = FixtureData.requestCounts.copy(needs_approval = 1), requests = listOf(failureRow), hasLoaded = true,
            requesterNames = FixtureData.requesterNames, selecting = true, selected = setOf(failureRow.id),
            bulk = BulkProgress(
                BulkRequestAction.Approve, total = 4, done = 4,
                failures = listOf(BulkFailure(failureRow.id, failureRow.title, ErrorText.Raw("Another admin already approved this request"))),
            ),
        )
        "unavailable" -> RequestQueueState(hasLoaded = true, unavailable = true)
        "stale" -> RequestQueueState(
            counts = FixtureData.requestCounts, requests = pending, hasLoaded = true, requesterNames = FixtureData.requesterNames,
            refreshFailed = true, lastUpdated = java.time.Instant.now().minusSeconds(8 * 60),
        )
        else -> RequestQueueState(counts = FixtureData.requestCounts, requests = pending, hasLoaded = true, requesterNames = FixtureData.requesterNames)
    }
    RequestsContent(
        state = state, loadError = null, actionError = null, pendingKeys = emptySet(),
        onSelectView = {}, onSearch = {}, onMedia = {}, onFilterUser = {}, onClearUserFilter = {},
        onAction = { _, _, _ -> }, onOpen = { _, _ -> }, onLoadMore = {}, onRetry = {}, onDismissError = {},
        bulk = RequestBulkCallbacks({}, {}, {}, {}, {}, {}, {}, {}),
    )
}

@Composable
fun FixtureOrganization(startPage: String) {
    var selected by rememberSaveable { mutableStateOf(startPage) }
    val context = AdminContext(
        "organization:o", "Acme Media", AdminContextScope.Organization, "o", "active", AdminAuthority.OrganizationAdmin, membershipId = "m1",
    )
    OrganizationAdminShell(
        organizationName = "Acme Media",
        selectedDestinationId = selected,
        onDestinationSelected = { selected = it },
        useNavigationRail = false,
        onContextRequested = {},
        streamsVisible = true,
    ) { id ->
        when (id) {
            "organization.people" -> PeopleContent(
                PeopleState(loading = false, people = FixtureData.people, groups = FixtureData.accessGroups, approximateTotal = 48),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
            )
            else -> OverviewContent(OverviewState(loading = false, summary = FixtureData.organization, ownerName = "Pat Owner", ownerResolved = true)) {}
        }
    }
}

/** How the alerts section of App settings looks in each state the admin can meet. */
enum class FixtureAlerts { Off, On, Blocked }

/**
 * App settings with its "Server alerts" section, drawn as the page draws it, from invented servers.
 * [FixtureAlerts.Off] is the state of a new install; [On] has alerts on for two of three servers; [Blocked] is alerts
 * on in the app while Android's notification setting is off.
 */
@Composable
fun FixtureAppSettings(alerts: FixtureAlerts) {
    AppSettingsScreen(
        requireDeviceUnlock = false, onRequireDeviceUnlockChanged = {}, onBack = {}, themeMode = ThemeMode.System, onThemeModeChanged = {},
        alertsContent = {
            AlertSettingsSection(
                enabled = alerts != FixtureAlerts.Off,
                showDetailsOnLockScreen = false,
                servers = FixtureData.savedServers,
                enabledServerIds = if (alerts == FixtureAlerts.Off) emptySet() else setOf("home", "garden"),
                status = when (alerts) {
                    FixtureAlerts.Off -> AlertDeliveryStatus.Off
                    FixtureAlerts.On -> AlertDeliveryStatus.On
                    FixtureAlerts.Blocked -> AlertDeliveryStatus.BlockedBySystem
                },
                permissionRefused = false,
                onEnabledChange = {}, onServerChange = { _, _ -> }, onShowDetailsChange = {}, onOpenSystemSettings = {},
            )
        },
    )
}
