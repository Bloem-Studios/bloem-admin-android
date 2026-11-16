package com.bloemstudios.admin.servers

import com.bloemstudios.admin.links.DeepLinkPageEffect
import com.bloemstudios.admin.ui.shell.START_DESTINATION_ID
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.bloemstudios.admin.dashboard.EmptyActivitySetup
import androidx.compose.ui.Modifier
import com.bloemstudios.admin.ui.theme.VeilColors
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import com.bloemstudios.admin.ui.shell.ActivityScopeControl
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.dashboard.AggregateDashboardScreen
import com.bloemstudios.admin.activity.AggregateActivityScreen
import com.bloemstudios.admin.audit.AggregateAuditScreen
import com.bloemstudios.admin.backups.AggregateBackupsScreen
import com.bloemstudios.admin.jobs.AggregateJobsScreen
import com.bloemstudios.admin.nodes.AggregateNodesScreen
import com.bloemstudios.admin.plugins.AggregatePluginsScreen
import com.bloemstudios.admin.libraries.AggregateLibrariesScreen
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.requests.AggregateRequestsScreen
import com.bloemstudios.admin.requests.CountsTarget
import com.bloemstudios.admin.requests.LocalRequestCountsNudge
import com.bloemstudios.admin.requests.RequestCountsPoller
import com.bloemstudios.admin.serverconfig.AggregateServerConfigScreen
import com.bloemstudios.admin.streams.AggregateStreamsScreen
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.aggregateDestinations
import com.bloemstudios.admin.users.AggregateUsersScreen
import com.bloemstudios.admin.platform.AggregatePlatformOrganizationsScreen
import com.bloemstudios.admin.platform.supportsPlatformOrganizations
import com.bloemstudios.admin.realtime.ForegroundOnceEffect

/**
 * The "All Servers" aggregate shell fans each supported section out across
 * every added server instead of scoping to one.
 *
 * Each server retains its own authority gate instead of using one decision for a
 * single server. Independent section states keep healthy servers usable.
 * Each server's admin-check happens per section
 * (see AggregateStreamsScreen/ServerSectionResult): a server that isn't
 * reachable or whose token is stale/non-admin just renders its own section
 * as an error/"tap to sign in" row instead of a session list.
 */
@Composable
fun AllServersScreen(
    store: ServerStore,
    principalGeneration: Long,
    useNavigationRail: Boolean = false,
    onSignIn: (AdminServer) -> Unit,
    onAddServerRequested: (() -> Unit)? = null,
    activityScope: ActivityScopeControl? = null,
    onBackToServers: () -> Unit = {},
) {
    val servers by store.servers.collectAsState()
    var selectedDestinationId by rememberSaveable { mutableStateOf(START_DESTINATION_ID) }
    // A link from the widget, tile or a shortcut may name the page to open across all servers.
    DeepLinkPageEffect(null) { selectedDestinationId = it }
    val adminClientFor: (AdminServer) -> com.bloemstudios.admin.network.ApiClient = remember(store) {
        { server -> store.client(server) }
    }

    // Bind every saved server, including those outside the current lazy viewport.
    val sessions = remember(principalGeneration) { AdminSessionViewModel() }
    val expired by sessions.sessionExpiredReasonByServer.collectAsState()
    DisposableEffect(servers, principalGeneration, store, sessions) {
        val bindings = servers.mapNotNull { server ->
            runCatching { store.client(server) }.getOrNull()?.let { client ->
                AdminUnauthorizedBinding(client, { store.accessTokenSnapshot(server.id) },
                    { sessions.reportAdminUnauthorized(server.id) })
            }
        }
        onDispose { bindings.forEach { it.close() } }
    }

    // The Needs approval count on the Requests menu item, summed over every saved server.
    val countsPoller = remember(principalGeneration) { RequestCountsPoller() }
    ForegroundEffect(servers, principalGeneration) {
        countsPoller.run(servers.map { server -> CountsTarget(server.id) { adminClientFor(server) } })
    }
    val waiting by countsPoller.needsApproval.collectAsState()
    // Organizations is offered when at least one saved server has organizations; a server that does not says so in its own section.
    var organizationsVisible by remember(servers, principalGeneration) { mutableStateOf(false) }
    ForegroundOnceEffect(servers, principalGeneration) {
        organizationsVisible = servers.isNotEmpty() &&
            fanOutToServers(servers, clientFor = adminClientFor) { it.supportsPlatformOrganizations() }.any { it.result.getOrNull() == true }
    }
    val destinations = aggregateDestinations(organizationsVisible)
    val shownDestinationId = if (destinations.any { it.id == selectedDestinationId }) selectedDestinationId else START_DESTINATION_ID

    // Nothing saved yet: a welcome with the one action, and none of the shell (menu, search, scope switcher) around it.
    if (servers.isEmpty()) {
        Box(
            Modifier.fillMaxSize().background(VeilColors.Canvas).windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) { EmptyActivitySetup(onAddServerRequested) }
        return
    }

    AdminShell(
        context = AdminShellContext(
            displayName = stringResource(R.string.veil_context_all_servers),
            isAggregate = true,
        ),
        destinations = destinations,
        destinationBadges = mapOf("work.requests" to (waiting ?: 0)),
        selectedDestinationId = shownDestinationId,
        onDestinationSelected = { selectedDestinationId = it },
        useNavigationRail = useNavigationRail,
        activityScope = activityScope,
        onContextRequested = onBackToServers,
    ) { id ->
        key(principalGeneration) {
          CompositionLocalProvider(LocalRequestCountsNudge provides countsPoller::nudge) {
            when (id) {
                "overview.dashboard" -> AggregateDashboardScreen(servers, store, expired.keys,
                    onSignIn, onAddServerRequested, { selectedDestinationId = "live.streams" },
                    onUsersRequested = { selectedDestinationId = "people.users" })
                "live.streams" -> AggregateStreamsScreen(servers = servers, store = store, onSignIn = onSignIn, expiredServerIds = expired.keys, showClientIp = true)
                "live.activity" -> AggregateActivityScreen(servers = servers, store = store, onSignIn = onSignIn)
                "work.jobs" -> AggregateJobsScreen(servers = servers, store = store, onSignIn = onSignIn)
                "content.libraries" -> AggregateLibrariesScreen(servers = servers, store = store, onSignIn = onSignIn)
                "playback.nodes" -> AggregateNodesScreen(servers = servers, store = store, onSignIn = onSignIn)
                "system.backups" -> AggregateBackupsScreen(servers = servers, store = store, onSignIn = onSignIn)
                "system.plugins" -> AggregatePluginsScreen(servers = servers, store = store, onSignIn = onSignIn)
                "work.requests" -> AggregateRequestsScreen(servers = servers, store = store, onSignIn = onSignIn)
                "people.users" -> AggregateUsersScreen(
                    servers = servers,
                    store = store,
                    clientFor = adminClientFor,
                    onSignIn = onSignIn,
                )
                "system.server" -> AggregateServerConfigScreen(servers = servers, store = store, onSignIn = onSignIn)
                "system.audit" -> AggregateAuditScreen(servers = servers, store = store, onSignIn = onSignIn)
                "platform.organizations" -> AggregatePlatformOrganizationsScreen(servers = servers, clientFor = adminClientFor, onSignIn = onSignIn)
                else -> error("Unsupported aggregate destination: $id")
            }
          }
        }
    }
}
