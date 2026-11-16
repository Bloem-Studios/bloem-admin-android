package com.bloemstudios.admin.servers

import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.links.DeepLinkPageEffect
import com.bloemstudios.admin.ui.shell.START_DESTINATION_ID
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bloemstudios.admin.R
import com.bloemstudios.admin.activity.ActivityScreen
import com.bloemstudios.admin.apikeys.ApiKeysScreen
import com.bloemstudios.admin.apikeys.canOpenApiKeys
import com.bloemstudios.admin.audit.AuditScreen
import com.bloemstudios.admin.dashboard.DashboardScreen
import com.bloemstudios.admin.automation.AutomationPages
import com.bloemstudios.admin.automation.readAutomationPages
import com.bloemstudios.admin.backups.BackupsScreen
import com.bloemstudios.admin.jobs.JobsScreen
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.nodes.NodesScreen
import com.bloemstudios.admin.plugins.PluginsScreen
import com.bloemstudios.admin.libraries.LibrariesScreen
import com.bloemstudios.admin.logs.LogsScreen
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.getServerStatus
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.organization.AdminContext
import com.bloemstudios.admin.organization.OrganizationAdmission
import com.bloemstudios.admin.organization.SupportAccessScreen
import com.bloemstudios.admin.organization.canOpenSupportAccess
import com.bloemstudios.admin.realtime.ForegroundEffect
import com.bloemstudios.admin.platform.PlatformOrganizationsScreen
import com.bloemstudios.admin.platform.PlatformSession
import com.bloemstudios.admin.platform.supportsPlatformOrganizations
import com.bloemstudios.admin.realtime.ForegroundOnceEffect
import com.bloemstudios.admin.requests.CountsTarget
import com.bloemstudios.admin.requests.LocalRequestCountsNudge
import com.bloemstudios.admin.requests.RequestCountsPoller
import com.bloemstudios.admin.organization.listAdminContexts
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.requests.RequestsScreen
import com.bloemstudios.admin.serverconfig.ServerSettingsHost
import com.bloemstudios.admin.streams.StreamsScreen
import com.bloemstudios.admin.tenants.TenantsScreen
import com.bloemstudios.admin.users.UsersScreen
import com.bloemstudios.admin.ui.shell.ActivityScopeControl
import com.bloemstudios.admin.ui.shell.ServerContextHeader
import com.bloemstudios.admin.ui.shell.AdminDestination
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.selectSupportedDestination
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class AdminGateState {
    object Checking : AdminGateState()
    object Authorized : AdminGateState()
    object Unauthorized : AdminGateState()
    /** Not a platform administrator, but administers these organizations: admitted to organization mode. */
    data class OrganizationOnly(val contexts: List<AdminContext>) : AdminGateState()
    data class NeedsLogin(val reason: String? = null) : AdminGateState()
    data class Failed(val message: ErrorText) : AdminGateState()
}

internal data class AdminGateRoutingViewModels(
    val gate: AdminGateViewModel,
    val session: AdminSessionViewModel
)

internal fun adminGateRoutingViewModels(owner: ViewModelStoreOwner): AdminGateRoutingViewModels {
    val provider = ViewModelProvider.create(owner)
    return AdminGateRoutingViewModels(
        gate = provider[AdminGateViewModel::class.java],
        session = provider[AdminSessionViewModel::class.java]
    )
}

class AdminGateViewModel : ViewModel() {
    private val _state = MutableStateFlow<AdminGateState>(AdminGateState.Checking)
    val state: StateFlow<AdminGateState> = _state

    // Suspend so production (LaunchedEffect, which already provides a
    // coroutine scope) and tests (runTest) can both await completion
    // directly. checkAccessAsync below is the thin viewModelScope wrapper
    // for any caller that isn't already inside a coroutine.
    suspend fun checkAccess(client: ApiClient) {
        if (client.accessToken == null) {
            _state.value = AdminGateState.NeedsLogin()
            return
        }
        _state.value = AdminGateState.Checking
        try {
            client.getServerStatus()
            _state.value = AdminGateState.Authorized
        } catch (e: ApiError) {
            _state.value = when (e.statusCode) {
                401 -> AdminGateState.NeedsLogin()
                403 -> organizationAdmission(client)
                else -> AdminGateState.Failed(e.toErrorText(R.string.error_admin_gate_unreachable))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            _state.value = AdminGateState.Failed(ErrorText.Res(R.string.error_admin_gate_unreachable))
        }
    }

    /** Refused as platform administrator: admitted when the account administers an organization. */
    private suspend fun organizationAdmission(client: ApiClient): AdminGateState = try {
        val contexts = client.listAdminContexts(platformAdmin = false)
        if (contexts.isEmpty()) AdminGateState.Unauthorized else AdminGateState.OrganizationOnly(contexts)
    } catch (e: ApiError) {
        when (e.statusCode) {
            401 -> AdminGateState.NeedsLogin()
            // An account the server won't even list organizations for administers none.
            403 -> AdminGateState.Unauthorized
            else -> AdminGateState.Failed(e.toErrorText(R.string.error_admin_gate_unreachable))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        AdminGateState.Failed(ErrorText.Res(R.string.error_admin_gate_unreachable))
    }

    fun checkAccessAsync(client: ApiClient) {
        viewModelScope.launch { checkAccess(client) }
    }

    /**
     * Forces the gate back to NeedsLogin outside of the normal checkAccess
     * probe -- used when the ADMIN's own token is rejected mid-session on
     * an authenticated feature screen (see
     * AdminSessionViewModel.sessionExpiredReason), which is a different
     * trigger than the initial gate check but should land on exactly the
     * same re-login form/state, now carrying [reason] so the admin knows
     * why they're being asked to sign in again.
     */
    fun forceNeedsLogin(reason: String) {
        _state.value = AdminGateState.NeedsLogin(reason)
    }
}

@Composable
fun AdminGateScreen(
    server: AdminServer,
    store: ServerStore,
    principalGeneration: Long,
    viewModel: AdminGateViewModel,
    sessionViewModel: AdminSessionViewModel,
    useNavigationRail: Boolean = false,
    activityScope: ActivityScopeControl? = null,
    onBackToServers: () -> Unit = {},
    onOpenOrganization: (organizationId: String) -> Unit = {},
    /** The server Owner opened an organization under support access: navigate to it, keeping this page behind. */
    onOpenSupportOrganization: (organizationId: String) -> Unit = {},
) {
    // These routing-only ViewModels are owned by the nav back-stack entry,
    // outside the credential-scoped feature owner. That lets an expiry reason
    // reach the replacement gate while the displaced feature ViewModels are
    // still cleared immediately.
    val state by viewModel.state.collectAsState()
    // Leaving the gate by choice ends a re-login in the browser; the app lock removing it does not.
    val signInOwner = rememberProviderSignInOwner()
    val onBackToServers = {
        signInOwner.cancel(SignInTarget.ReLogin(server.id))
        onBackToServers()
    }
    val sessionExpiredReasonByServer by sessionViewModel.sessionExpiredReasonByServer.collectAsState()
    val sessionExpiredReason = sessionExpiredReasonByServer[server.id]
    // Keep navigation selection outside the credential-bound content below:
    // a refresh/re-login must replace sensitive feature state without
    // bouncing the administrator back to Dashboard.
    var selectedDestinationId by rememberSaveable { mutableStateOf(START_DESTINATION_ID) }
    // A link from an alert, widget, tile or shortcut may name the page to open on this server.
    DeepLinkPageEffect(server.id) { selectedDestinationId = it }

    LaunchedEffect(server.id, principalGeneration) {
        viewModel.checkAccess(store.client(server))
    }

    // The ADMIN's own token being rejected mid-session (bubbled up via
    // ApiClient.onUnauthorized -> AdminSessionViewModel.reportAdminUnauthorized,
    // wired in the authorized shell below) routes back to the same re-login form
    // NeedsLogin already renders for the initial gate probe.
    LaunchedEffect(sessionExpiredReason) {
        sessionExpiredReason?.let { reason ->
            viewModel.forceNeedsLogin(reason)
        }
    }

    CompositionLocalProvider(LocalAdminGateServerDisplayName provides server.displayName) {
        // The signed-in shell pads its own insets; every screen before it gets the same top bar and padding.
        Column(
            if (state is AdminGateState.Authorized) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AdminGateHeader(state, server.displayName, activityScope, onBackToServers)
            Box(Modifier.weight(1f)) {
                AdminGateContent(
                    state = state,
                    authorizedContent = {
                        AdminAuthorizedShell(
                            server = server,
                            store = store,
                            principalGeneration = principalGeneration,
                            sessionViewModel = sessionViewModel,
                            selectedDestinationId = selectedDestinationId,
                            onSelectedDestinationChanged = { selectedDestinationId = it },
                            useNavigationRail = useNavigationRail,
                            activityScope = activityScope,
                            onBackToServers = onBackToServers,
                            onOpenSupportOrganization = onOpenSupportOrganization,
                        )
                    },
                    loginContent = {
                        val loginState = state as AdminGateState.NeedsLogin
                        ReLoginForm(
                            server = server,
                            reason = loginState.reason,
                            onLoggedIn = { accessToken, refreshToken, signInProviderId ->
                                sessionViewModel.clearSessionExpired(server.id)
                                store.completeReLogin(server.id, accessToken, refreshToken, signInProviderId)
                                // Updating credentials replaces the sensitive owner. Its
                                // generation-keyed LaunchedEffect performs the new probe;
                                // probing this displaced ViewModel would race that work.
                            },
                        )
                    },
                    onBackToServers = onBackToServers,
                    onRetry = { viewModel.checkAccessAsync(store.client(server)) },
                    organizationContent = { admitted ->
                        OrganizationAdmission(server.displayName, admitted.contexts) { context ->
                            context.organizationId?.let(onOpenOrganization)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The top bar of every screen before a server's pages: title and context dropdown, like the shell's.
 * The signed-in shell brings its own bar, so nothing is drawn once access is granted.
 */
@Composable
internal fun AdminGateHeader(
    state: AdminGateState,
    serverName: String,
    activityScope: ActivityScopeControl?,
    onBackToServers: () -> Unit,
) {
    if (state is AdminGateState.Authorized) return
    ServerContextHeader(
        context = AdminShellContext(serverName, false),
        activityScope = activityScope,
        title = stringResource(adminGateTitleRes(state)),
        onClick = onBackToServers,
    )
}

/** The top bar's title for the screens before a server's pages, in the web's one-title-per-page style. */
internal fun adminGateTitleRes(state: AdminGateState): Int = when (state) {
    is AdminGateState.Checking, is AdminGateState.Authorized -> R.string.shell_title_checking_access
    is AdminGateState.NeedsLogin -> R.string.shell_title_sign_in
    is AdminGateState.OrganizationOnly -> R.string.shell_title_organizations
    is AdminGateState.Unauthorized -> R.string.shell_title_no_access
    is AdminGateState.Failed -> R.string.shell_title_unreachable
}

private val LocalAdminGateServerDisplayName = staticCompositionLocalOf { "" }

@Composable
internal fun AdminGateContent(
    state: AdminGateState,
    authorizedContent: @Composable () -> Unit,
    loginContent: @Composable () -> Unit,
    onBackToServers: () -> Unit,
    onRetry: () -> Unit = {},
    organizationContent: @Composable (AdminGateState.OrganizationOnly) -> Unit = {},
) {
    when (state) {
        is AdminGateState.Checking -> Column(Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal)) {
            Text(stringResource(R.string.login_checking_access), style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = VeilSpacing.Medium))
            TextButton(onClick = onBackToServers, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.veil_back_to_servers))
            }
        }
        is AdminGateState.Authorized -> authorizedContent()
        is AdminGateState.OrganizationOnly -> organizationContent(state)
        is AdminGateState.Unauthorized -> GateMessage(
            stringResource(R.string.admin_gate_unauthorized, LocalAdminGateServerDisplayName.current),
            onRetry, onBackToServers,
        )
        is AdminGateState.NeedsLogin -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { loginContent() }
            TextButton(
                onClick = onBackToServers,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.veil_back_to_servers))
            }
        }
        is AdminGateState.Failed -> GateMessage(state.message.resolve(), onRetry, onBackToServers)
    }
}

/** The No access and Can't reach pages: what happened, then the two ways out. */
@Composable
private fun GateMessage(message: String, onRetry: () -> Unit, onBackToServers: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal)) {
        Text(message)
        Row(Modifier.padding(top = VeilSpacing.Medium), horizontalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = onBackToServers, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.veil_back_to_servers))
            }
        }
    }
}

/**
 * Signs back in to a saved server on the same sign-in options as Add Server's second step, without
 * "Change server". Tokens are stored by [onLoggedIn] exactly as password re-login always did; the
 * admin check is left to the gate's re-probe, so neither sign-in probes here.
 */
@Composable
internal fun ReLoginForm(
    server: AdminServer,
    reason: String?,
    connector: AddServerConnector = remember { AddServerConnector() },
    onLoggedIn: suspend (accessToken: String, refreshToken: String, signInProviderId: String?) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf<AddServerResult.Rejected?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<DiscoveryResult.Discovered?>(null) }
    var discoveryFailed by remember { mutableStateOf(false) }
    var providerName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The provider sign-in lives in the activity: the app lock removing this form does not cancel it.
    val providerSignIn = rememberProviderSignIn(
        target = SignInTarget.ReLogin(server.id),
        onSucceeded = {},
        onRejected = { failure, name -> rejection = failure; providerName = name },
    )

    LaunchedEffect(server.id, server.baseUrl) {
        discoveryFailed = false
        when (val result = connector.discoverForReLogin(server)) {
            is DiscoveryResult.Discovered -> discovered = result
            is DiscoveryResult.Rejected -> {
                rejection = AddServerResult.Rejected(result.reason)
                discoveryFailed = true
            }
        }
    }

    suspend fun finish(result: AddServerResult) {
        when (result) {
            is AddServerResult.Connected -> {
                val persisted = persistConnectedServer(result) { signedIn, accessToken, refreshToken ->
                    onLoggedIn(accessToken, refreshToken, signedIn.signInProviderId)
                }
                if (persisted is AddServerResult.Rejected) rejection = persisted
            }
            is AddServerResult.Rejected -> rejection = result
        }
    }

    val current = discovered
    val title = stringResource(R.string.relogin_sign_in)
    val guidance = reason ?: stringResource(R.string.relogin_expired_message, server.displayName)
    val message = rejection?.let { addServerFailureMessage(it.reason, it.detail, providerName) }
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(guidance, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted,
                modifier = Modifier.padding(top = VeilSpacing.Small))
            if (discoveryFailed) {
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Danger,
                        modifier = Modifier.padding(top = VeilSpacing.Medium))
                }
            } else {
                Text(stringResource(R.string.add_server_checking), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = VeilSpacing.Medium))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = VeilSpacing.Medium))
            }
        }
        return
    }
    SignInOptionsStep(
        serverName = server.displayName,
        providers = current.providers,
        username = username,
        onUsernameChange = { username = it },
        password = password,
        onPasswordChange = { password = it },
        errorMessage = message,
        isConnecting = isSubmitting || providerSignIn.isConnecting,
        connectingProviderId = providerSignIn.providerId,
        compact = true,
        title = title,
        guidance = guidance,
        rememberedProviderId = server.signInProviderId,
        passwordActionLabel = title,
        onPasswordSignIn = {
            scope.launch {
                isSubmitting = true
                rejection = null
                try {
                    finish(connector.signInWithPassword(current, username, password, probeAdmin = false))
                } finally {
                    isSubmitting = false
                }
            }
        },
        onProviderSignIn = { provider ->
            rejection = null
            providerName = provider.display_name
            providerSignIn.start(
                connector = connector,
                discovered = current,
                provider = provider,
                probeAdmin = false,
                persist = { signedIn, accessToken, refreshToken ->
                    onLoggedIn(accessToken, refreshToken, signedIn.signInProviderId)
                },
            )
        },
    )
}

internal enum class SingleServerFeature(val destinationId: String) {
    Dashboard("overview.dashboard"),
    Streams("live.streams"),
    Activity("live.activity"),
    Jobs("work.jobs"),
    Nodes("playback.nodes"),
    Backups("system.backups"),
    Plugins("system.plugins"),
    Libraries("content.libraries"),
    Requests("work.requests"),
    Users("people.users"),
    Tenants("people.tenants"),
    Server("system.server"),
    Audit("system.audit"),
    Logs("system.logs"),
    SupportAccess("platform.support-access"),
    ApiKeys("system.api-keys"),
    PlatformOrganizations("platform.organizations"),
}

internal fun singleServerFeatureFor(destinationId: String): SingleServerFeature =
    SingleServerFeature.entries.firstOrNull { it.destinationId == destinationId }
        ?: error("Unsupported single-server destination: $destinationId")

/**
 * Tenants stays out of navigation until Phase 3 adds the platform pass. Its code under `tenants/`
 * is kept; flipping this on is not enough on its own, because the routes need that pass.
 */
internal const val TENANTS_DESTINATION_VISIBLE = false

internal fun normalizeSingleServerDestinationId(
    selectedDestinationId: String,
    supportedDestinations: List<AdminDestination>,
): String {
    supportedDestinations.firstOrNull { it.id == selectedDestinationId }?.let { return it.id }

    val previousGroup = singleServerDestinations(tenantsVisible = true)
        .firstOrNull { it.id == selectedDestinationId }
        ?.group
    return previousGroup?.let { group ->
        selectSupportedDestination(
            group = group,
            destinations = supportedDestinations,
            currentDestinationId = selectedDestinationId,
            rememberedDestinationId = null,
        )?.id
    } ?: supportedDestinations.firstOrNull { it.id == START_DESTINATION_ID }?.id
        ?: supportedDestinations.firstOrNull()?.id
        ?: error("Single-server destinations must not be empty")
}

@Composable
private fun AdminAuthorizedShell(
    server: AdminServer,
    store: ServerStore,
    principalGeneration: Long,
    sessionViewModel: AdminSessionViewModel,
    selectedDestinationId: String,
    onSelectedDestinationChanged: (String) -> Unit,
    useNavigationRail: Boolean,
    activityScope: ActivityScopeControl?,
    onBackToServers: () -> Unit,
    onOpenSupportOrganization: (organizationId: String) -> Unit,
) {
    val adminClient = remember(server.id, principalGeneration) { store.client(server) }
    // The Owner's support access is offered only to the Owner of a server that advertises it; any doubt is "no".
    var supportAccessVisible by remember(server.id, principalGeneration) { mutableStateOf(false) }
    // The Logs page links to the Logging settings page: Settings opens there once, then forgets it.
    var pendingSettingsRoute by rememberSaveable(server.id) { mutableStateOf<String?>(null) }
    var logsFocus by remember(server.id) { mutableStateOf<LogsFocus?>(null) }
    ForegroundOnceEffect(adminClient) { supportAccessVisible = adminClient.canOpenSupportAccess() }
    // Nodes, Backups and Plugins appear only where the server says it has them.
    var automation by remember(server.id, principalGeneration) { mutableStateOf(AutomationPages.None) }
    ForegroundOnceEffect(adminClient) { automation = adminClient.readAutomationPages() }
    // The API keys page is listed only where the server offers key administration; any doubt is "no".
    var apiKeysVisible by remember(server.id, principalGeneration) { mutableStateOf(false) }
    ForegroundOnceEffect(adminClient) { apiKeysVisible = adminClient.canOpenApiKeys() }
    // Platform › Organizations is offered to a platform administrator of a server that has organizations.
    var organizationsVisible by remember(server.id, principalGeneration) { mutableStateOf(false) }
    ForegroundOnceEffect(adminClient) { organizationsVisible = adminClient.supportsPlatformOrganizations() }
    // The platform pass for the Organizations pages: minted when they first read, kept in memory, dropped with this shell.
    val platformSession = remember(adminClient) { PlatformSession(adminClient) }
    DisposableEffect(platformSession) { onDispose { platformSession.close() } }
    DisposableEffect(adminClient, server.id, store, sessionViewModel) {
        val binding = AdminUnauthorizedBinding(
            client = adminClient,
            currentCredential = { store.accessTokenSnapshot(server.id) },
            onCurrentUnauthorized = { sessionViewModel.reportAdminUnauthorized(server.id) }
        )
        onDispose { binding.close() }
    }

    // The Needs approval count on the Requests menu item: read every minute while the app is in front.
    val countsPoller = remember(adminClient) { RequestCountsPoller() }
    ForegroundEffect(adminClient) { countsPoller.run(listOf(CountsTarget(server.id) { adminClient })) }
    val waiting by countsPoller.needsApproval.collectAsState()

    SingleServerAdminShell(
        context = AdminShellContext(server.displayName, isAggregate = false),
        principalGeneration = principalGeneration,
        tenantsVisible = TENANTS_DESTINATION_VISIBLE,
        supportAccessVisible = supportAccessVisible,
        automation = automation,
        apiKeysVisible = apiKeysVisible,
        organizationsVisible = organizationsVisible,
        selectedDestinationId = selectedDestinationId,
        onSelectedDestinationChanged = onSelectedDestinationChanged,
        useNavigationRail = useNavigationRail,
        activityScope = activityScope,
        onContextRequested = onBackToServers,
        destinationBadges = mapOf(SingleServerFeature.Requests.destinationId to (waiting ?: 0)),
    ) { destinationId ->
        // Logs opened from a stream, a play or an audit entry, narrowed to that playback session or request.
        val openLogs = { focus: LogsFocus -> logsFocus = focus; onSelectedDestinationChanged(SingleServerFeature.Logs.destinationId) }
      CompositionLocalProvider(LocalRequestCountsNudge provides countsPoller::nudge) {
        when (singleServerFeatureFor(destinationId)) {
            SingleServerFeature.Dashboard -> DashboardScreen(
                client = adminClient,
                onStreamsRequested = { onSelectedDestinationChanged("live.streams") },
                onUsersRequested = { onSelectedDestinationChanged("people.users") },
                onNodesRequested = if (automation.nodes) ({ onSelectedDestinationChanged("playback.nodes") }) else null,
            )
            SingleServerFeature.Streams -> StreamsScreen(
                client = adminClient, showClientIp = true,
                onOpenLogs = { id, ffmpeg -> openLogs(LogsFocus(id, ffmpeg)) },
            )
            SingleServerFeature.Activity -> ActivityScreen(
                client = adminClient,
                onOpenLogs = { id, ffmpeg -> openLogs(LogsFocus(id, ffmpeg)) },
            )
            SingleServerFeature.Jobs -> JobsScreen(client = adminClient, onOpenBackups = if (automation.backups) ({ onSelectedDestinationChanged("system.backups") }) else null)
            SingleServerFeature.Nodes -> NodesScreen(client = adminClient)
            SingleServerFeature.Backups -> BackupsScreen(client = adminClient)
            SingleServerFeature.Plugins -> PluginsScreen(client = adminClient)
            SingleServerFeature.Libraries -> LibrariesScreen(client = adminClient)
            SingleServerFeature.Requests -> RequestsScreen(client = adminClient)
            SingleServerFeature.Users -> UsersScreen(client = adminClient)
            SingleServerFeature.Tenants -> TenantsScreen(client = adminClient)
            SingleServerFeature.Server -> ServerSettingsHost(
                client = adminClient, scopeKey = server.id,
                initialRoute = pendingSettingsRoute ?: com.bloemstudios.admin.serverconfig.ROUTE_HOME,
                onInitialRouteConsumed = { pendingSettingsRoute = null },
            )
            SingleServerFeature.Audit -> AuditScreen(client = adminClient, onOpenLogs = openLogs)
            SingleServerFeature.Logs -> LogsScreen(
                client = adminClient, focus = logsFocus, onFocusConsumed = { logsFocus = null },
                onOpenLogSettings = {
                    pendingSettingsRoute = com.bloemstudios.admin.serverconfig.pageRoute("logging")
                    onSelectedDestinationChanged("system.server")
                },
            )
            SingleServerFeature.ApiKeys -> ApiKeysScreen(client = adminClient)
            SingleServerFeature.SupportAccess -> SupportAccessScreen(account = adminClient, serverId = server.id, onOpen = onOpenSupportOrganization)
            SingleServerFeature.PlatformOrganizations -> PlatformOrganizationsScreen(
                session = platformSession, account = adminClient, serverId = server.id,
                supportAvailable = supportAccessVisible, onOpenOrganization = onOpenSupportOrganization,
            )
        }
      }
    }
}

@Composable
internal fun SingleServerAdminShell(
    context: AdminShellContext,
    principalGeneration: Long,
    tenantsVisible: Boolean,
    supportAccessVisible: Boolean = false,
    automation: AutomationPages = AutomationPages.None,
    apiKeysVisible: Boolean = false,
    organizationsVisible: Boolean = false,
    selectedDestinationId: String,
    onSelectedDestinationChanged: (String) -> Unit,
    useNavigationRail: Boolean,
    onContextRequested: () -> Unit,
    activityScope: ActivityScopeControl? = null,
    destinationBadges: Map<String, Int> = emptyMap(),
    featureContent: @Composable (destinationId: String) -> Unit,
) {
    val destinations = singleServerDestinations(
        tenantsVisible, supportAccessVisible, organizationsVisible = organizationsVisible, automation = automation, apiKeysVisible = apiKeysVisible,
    )
    val supportedDestinationId = normalizeSingleServerDestinationId(
        selectedDestinationId = selectedDestinationId,
        supportedDestinations = destinations,
    )
    LaunchedEffect(supportedDestinationId) {
        if (selectedDestinationId != supportedDestinationId) {
            onSelectedDestinationChanged(supportedDestinationId)
        }
    }

    AdminShell(
        context = context,
        destinations = destinations,
        selectedDestinationId = supportedDestinationId,
        onDestinationSelected = onSelectedDestinationChanged,
        useNavigationRail = useNavigationRail,
        activityScope = activityScope,
        onContextRequested = onContextRequested,
        destinationBadges = destinationBadges,
    ) { destinationId ->
        key(principalGeneration) {
            featureContent(destinationId)
        }
    }
}
