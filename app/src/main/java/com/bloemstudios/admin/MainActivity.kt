package com.bloemstudios.admin

import com.bloemstudios.admin.ui.components.TextButton
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bloemstudios.admin.auth.OAuthRedirects
import com.bloemstudios.admin.alerts.AlertSettings
import com.bloemstudios.admin.alerts.AlertNotifier
import com.bloemstudios.admin.alerts.AlertsRuntime
import com.bloemstudios.admin.push.PushPermissionPrompt
import com.bloemstudios.admin.push.PushSettings
import com.bloemstudios.admin.alerts.ServerCheckScheduler
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.links.DeepLinkInbox
import com.bloemstudios.admin.shortcuts.RecentServers
import com.bloemstudios.admin.shortcuts.ShortcutPublisher
import com.bloemstudios.admin.widget.SurfaceRefresher
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bloemstudios.admin.servers.AddServerScreen
import com.bloemstudios.admin.servers.ProviderSignInNotice
import com.bloemstudios.admin.servers.ProviderSignInResumeEffect
import com.bloemstudios.admin.servers.rememberProviderSignInOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bloemstudios.admin.ui.shell.ActivityScopeControl
import com.bloemstudios.admin.ui.shell.LocalAppSettingsRequest
import com.bloemstudios.admin.ui.shell.ActivityScopeOption
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import com.bloemstudios.admin.ui.shell.ScopeOrganization
import com.bloemstudios.admin.organization.discoverAdminContexts
import com.bloemstudios.admin.platform.isBloemServer
import com.bloemstudios.admin.organization.AdminContextScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.bloemstudios.admin.navigation.activityStartRoute
import com.bloemstudios.admin.navigation.ActivityScopePreferences
import com.bloemstudios.admin.lock.AppLockPreferences
import com.bloemstudios.admin.lock.AppSettingsScreen
import com.bloemstudios.admin.lock.AppLockScreen
import com.bloemstudios.admin.organization.OrganizationAdminRoute
import com.bloemstudios.admin.servers.AdminGateScreen
import com.bloemstudios.admin.servers.adminGateRoutingViewModels
import com.bloemstudios.admin.servers.AllServersScreen
import com.bloemstudios.admin.servers.ServerListScreen
import com.bloemstudios.admin.servers.ServerStore
import com.bloemstudios.admin.servers.SensitiveViewModelStoreRegistry
import com.bloemstudios.admin.ui.theme.SystemBarsFollowTheme
import com.bloemstudios.admin.ui.theme.ThemePreferences
import com.bloemstudios.admin.ui.theme.VeilAdminTheme

// FragmentActivity (not plain ComponentActivity) because BiometricPrompt
// (see com.bloemstudios.admin.lock.AppLockScreen) requires a host that is
// either a FragmentActivity or a Fragment.
class MainActivity : FragmentActivity() {
    // A link (alert, widget, tile, shortcut) is only parked here. The shell opens it once the app lock and the admin
    // check have been passed. A rotation re-delivers the same intent, so only a fresh start reads it.
    private fun offerLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        DeepLink.parse(intent.dataString)?.let { DeepLinkInbox.offer(it) }
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerLink(intent)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) offerLink(intent)
        setContent {
            val rootViewModel = viewModel<AdminRootViewModel>()
            val themeMode by rootViewModel.themePreferences.themeMode.collectAsState()
            VeilAdminTheme(themeMode) {
                SystemBarsFollowTheme()
                // A redirect that arrives with no sign-in waiting (process death, stale link) is
                // never redeemed. Its contents are dropped unread; only this notice is shown.
                var signInInterrupted by rememberSaveable { mutableStateOf(false) }
                // The provider sign-in waits here, at activity level, so the app lock (which removes
                // the screens below on stop) cannot orphan the redirect. takeForSignIn comes first.
                val signIn = rememberProviderSignInOwner()
                ProviderSignInResumeEffect(signIn)
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    if (OAuthRedirects.takeOrphan() != null) signInInterrupted = true
                }
                if (signInInterrupted) {
                    AlertDialog(onDismissRequest = { signInInterrupted = false },
                        text = { Text(stringResource(R.string.oauth_sign_in_interrupted)) },
                        confirmButton = { TextButton(onClick = { signInInterrupted = false }) {
                            Text(stringResource(R.string.action_close))
                        } })
                }
                val store by rootViewModel.store.collectAsState()
                val requireDeviceUnlock by rootViewModel.appLockPreferences.requireDeviceUnlock.collectAsState()
                val navController = rememberNavController()
                val adminStateHolder = rememberSaveableStateHolder()

                // Drives the tablet-adaptive layout switch (bottom nav vs.
                // side rail -- see AdminGateScreen/AdminTabScreen). Recomputed
                // on every recomposition of this Activity's content, which
                // includes configuration changes (e.g. rotation, entering
                // multi-window), so the layout tracks the CURRENT window
                // size rather than the size at cold launch. Medium and
                // Expanded both switch to the rail: Compact is a phone in
                // portrait (or a very narrow multi-window pane) where a
                // bottom bar still fits better; anything wider genuinely has
                // room for a persistent side rail instead of stealing a full
                // row at the bottom.
                val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                val useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                // Device unlock is an optional, device-local setting, off by default.
                AppLockScreen(enabled = requireDeviceUnlock, onLocked = rootViewModel::clearSensitiveState) {
                    // A sign-in failure that landed while its screen was behind the lock, shown once here.
                    ProviderSignInNotice(signIn)
                    val currentStore = store
                    if (currentStore == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        adminStateHolder.SaveableStateProvider("admin-navigation") {
                            AdminApp(
                                store = currentStore,
                                appLockPreferences = rootViewModel.appLockPreferences,
                                themePreferences = rootViewModel.themePreferences,
                                activityScopePreferences = rootViewModel.activityScopePreferences,
                                useNavigationRail = useNavigationRail,
                                navController = navController,
                                sensitiveStores = rootViewModel.sensitiveViewModelStores
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdminApp(
    store: ServerStore,
    appLockPreferences: AppLockPreferences,
    themePreferences: ThemePreferences,
    activityScopePreferences: ActivityScopePreferences,
    useNavigationRail: Boolean,
    navController: androidx.navigation.NavHostController,
    sensitiveStores: SensitiveViewModelStoreRegistry
) {
    val servers by store.servers.collectAsState()
    val savedIds = servers.map { it.id }.toSet()
    val allServers by activityScopePreferences.allServers.collectAsState()
    val selectedServerId by activityScopePreferences.selectedServerId.collectAsState()
    // Freeze only after the loaded store exists. Navigation restores its own saved stack.
    val startRoute = remember(store) { activityStartRoute(allServers, selectedServerId, savedIds) }
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val visibleServerId = entry?.arguments?.getString("serverId")
    val previousGateId = navController.previousBackStackEntry?.takeIf {
        it.destination.route == "adminGate/{serverId}"
    }?.arguments?.getString("serverId")

    fun replaceScope(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }
    fun openServers() {
        if (!navController.popBackStack("serverList", inclusive = false)) {
            navController.navigate("serverList") { launchSingleTop = true }
        }
    }
    // "Add server" in the switcher sheet opens Add server over whatever page is showing.
    var addingServer by rememberSaveable { mutableStateOf(false) }
    // Sign out takes the saved sign-in off this device for the servers named, and removes no server. Pushes stop first, by the
    // device's own token and within a bounded wait, so a server that does not answer cannot hang it. The pages notice the
    // cleared sign-in and show the sign-in form (see ServerStore.forgetCredentials).
    val signOutScope = rememberCoroutineScope()
    val signOutContext = LocalContext.current.applicationContext
    fun signOut(serverIds: List<String>) {
        signOutScope.launch {
            serverIds.mapNotNull { id -> servers.firstOrNull { it.id == id } }.forEach { server ->
                com.bloemstudios.admin.push.PushRuntime.unregister(signOutContext, server)
                store.forgetCredentials(server.id)
            }
        }
    }
    // The organizations each saved server lets this account administer (the web's administrative
    // contexts), listed under that server in the context dropdown. Read again after every sign-in
    // change; a server that cannot answer keeps what it listed before.
    val organizationsByServer = remember { mutableStateMapOf<String, List<ScopeOrganization>>() }
    val contextsRevision by store.credentialRevision.collectAsState()
    // Bumped when the server refuses an organization (suspended, or authority lost): the list is read again.
    var contextsRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(servers, contextsRevision, contextsRefresh) {
        organizationsByServer.keys.retainAll(savedIds)
        servers.forEach { server ->
            launch {
                // The store's client is shared by every screen of this server: use it, never close it.
                try {
                    val client = store.client(server)
                    // Organizations are a Bloem extra: a plain Silo server is asked one question (is it Bloem?) and nothing more.
                    if (!client.isBloemServer()) {
                        organizationsByServer.remove(server.id)
                        return@launch
                    }
                    organizationsByServer[server.id] = client.discoverAdminContexts()
                        .filter { it.scope == AdminContextScope.Organization && it.organizationId != null }
                        .map { ScopeOrganization(server.id, it.organizationId!!, it.name) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Signed out, offline or not an administrator here: nothing new to list.
                }
            }
        }
    }
    fun scopeControl(aggregate: Boolean, gateId: String? = null, organizationId: String? = null) = ActivityScopeControl(
        allServers = aggregate,
        selectedServerId = if (aggregate) selectedServerId else gateId,
        servers = servers.map { ActivityScopeOption(it.id, it.displayName) },
        onSelectAllServers = {
            activityScopePreferences.selectAllServers()
            replaceScope("allServers")
        },
        onSelectServer = { id ->
            if (id in savedIds) {
                activityScopePreferences.selectServer(id)
                replaceScope("adminGate/$id")
            }
        },
        organizations = servers.flatMap { organizationsByServer[it.id].orEmpty() },
        selectedOrganizationId = organizationId,
        onSelectOrganization = { organization ->
            if (organization.serverId in savedIds) {
                replaceScope("orgAdmin/${organization.serverId}/${organization.organizationId}")
            }
        },
        onAddServer = { addingServer = true },
        // On All servers the page shows every server, so Sign out leaves every one; elsewhere it leaves the one on show.
        onSignOut = { signOut(if (aggregate) savedIds.toList() else listOfNotNull(gateId)) },
    )

    LaunchedEffect(savedIds, allServers, selectedServerId, currentRoute, visibleServerId, previousGateId) {
        if (!allServers && selectedServerId !in savedIds) activityScopePreferences.selectAllServers()
        if (currentRoute == "adminGate/{serverId}" && visibleServerId !in savedIds) {
            replaceScope("allServers")
        } else if (currentRoute == "serverList" && previousGateId != null && previousGateId !in savedIds) {
            // Preserve management while discarding the removed operational root beneath it.
            replaceScope("allServers")
            navController.navigate("serverList") { launchSingleTop = true }
        }
    }

    // Platform features. A link waiting in the inbox (from an alert, the widget, the tile or a shortcut) opens its server
    // here, inside the app lock, and the gate then applies its page. A link for a server that is gone is dropped.
    val deepLink by DeepLinkInbox.pending.collectAsState()
    LaunchedEffect(deepLink, savedIds) {
        val request = deepLink ?: return@LaunchedEffect
        val serverId = request.target.serverId
        when {
            !DeepLinkInbox.isFresh(request) -> DeepLinkInbox.consume(request)
            serverId == null -> replaceScope("allServers")
            serverId in savedIds -> replaceScope("adminGate/$serverId")
            else -> DeepLinkInbox.consume(request)
        }
    }
    val appContext = LocalContext.current.applicationContext
    val alertPreferences = remember { AlertsRuntime.alertPreferences(appContext) }
    val recentServers = remember { RecentServers(appContext.getSharedPreferences(RecentServers.FILE, android.content.Context.MODE_PRIVATE)) }
    var recentIds by remember { mutableStateOf(recentServers.ids()) }
    LaunchedEffect(visibleServerId, savedIds) {
        visibleServerId?.takeIf { it in savedIds }?.let(recentServers::touch)
        recentServers.retain(savedIds)
        recentIds = recentServers.ids()
    }
    LaunchedEffect(servers, recentIds) {
        withContext(Dispatchers.Default) { ShortcutPublisher.publish(appContext, servers, recentIds) }
    }
    // A removed server stops being watched: its alert switch, open alert and widget numbers go, and the schedule stops
    // if nothing else needs it.
    LaunchedEffect(savedIds) {
        val before = alertPreferences.enabledServerIds.value
        alertPreferences.retainServers(savedIds)
        (before - alertPreferences.enabledServerIds.value).forEach { AlertNotifier(appContext, alertPreferences).clear(it) }
        if (before != alertPreferences.enabledServerIds.value) ServerCheckScheduler.reconcile(appContext)
        // The saved servers changed: draw what is stored, and read the servers again soon.
        SurfaceRefresher.dataChanged(appContext)
    }

    val credentialRevision by store.credentialRevision.collectAsState()
    val requireDeviceUnlock by appLockPreferences.requireDeviceUnlock.collectAsState()
    val themeMode by themePreferences.themeMode.collectAsState()
    // Settings is a page of its own, one step above wherever it was opened from; back returns there.
    val openAppSettings = remember(navController) { { navController.navigate("appSettings") { launchSingleTop = true }; Unit } }
    // Asks for the notification permission once, at the first moment push is useful (a signed-in Bloem server that can push
    // to this app), never at launch.
    PushPermissionPrompt(servers)
    // A server a provider sign-in saved while Add Server was gone (behind the app lock) closes the
    // Add Server the lock's saved state would otherwise bring back.
    val signInOwner = rememberProviderSignInOwner()
    val savedWhileLocked by signInOwner.addServerSucceeded.collectAsState()
    LaunchedEffect(savedWhileLocked) {
        if (savedWhileLocked) {
            signInOwner.consumeAddServerSucceeded()
            addingServer = false
        }
    }
    if (addingServer) AddServerScreen(store = store, onDismiss = { addingServer = false })

    // Management retains its existing notices. Operational startup must expose them too.
    val recoveredCredentials by store.recoveredCredentials.collectAsState()
    val credentialStorageAvailable by store.credentialStorageAvailable.collectAsState()
    var showingRecoveryExplanation by rememberSaveable(recoveredCredentials) { mutableStateOf(recoveredCredentials) }
    if (currentRoute != "serverList") {
        if (!credentialStorageAvailable) {
            AlertDialog(onDismissRequest = {},
                title = { Text(stringResource(R.string.credential_storage_unavailable_title)) },
                text = { Text(stringResource(R.string.credential_storage_unavailable_body)) }, confirmButton = {})
        } else if (showingRecoveryExplanation) {
            AlertDialog(onDismissRequest = { showingRecoveryExplanation = false },
                title = { Text(stringResource(R.string.credentials_recovered_title)) },
                text = { Text(stringResource(R.string.credentials_recovered_body)) },
                confirmButton = { TextButton(onClick = { showingRecoveryExplanation = false }) {
                    Text(stringResource(R.string.action_close))
                } })
        }
    }

    // The drawer's Settings item opens the same page as the Manage servers page.
    CompositionLocalProvider(LocalAppSettingsRequest provides openAppSettings) {
        NavHost(navController = navController, startDestination = startRoute) {
            composable("serverList") {
                ServerListScreen(
                    store = store,
                    onAppSettingsRequested = openAppSettings,
                    onServerSelected = { server ->
                        navController.navigate("adminGate/${server.id}")
                    },
                    onAllServersSelected = {
                        navController.navigate("allServers")
                    },
                    onBack = if (navController.previousBackStackEntry != null) {
                        { navController.popBackStack() }
                    } else null,
                )
            }
            composable("appSettings") {
                AppSettingsScreen(
                    requireDeviceUnlock = requireDeviceUnlock,
                    onRequireDeviceUnlockChanged = appLockPreferences::setRequireDeviceUnlock,
                    onBack = { navController.popBackStack() },
                    themeMode = themeMode,
                    onThemeModeChanged = themePreferences::setThemeMode,
                    alertsContent = {
                        AlertSettings(alertPreferences, servers)
                        PushSettings(alertPreferences, servers)
                    },
                )
            }
            composable(
                "adminGate/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.StringType })
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getString("serverId")
                val server = servers.firstOrNull { it.id == serverId }
                if (server != null) {
                    BackHandler(enabled = navController.previousBackStackEntry == null) { openServers() }
                    val routingViewModels = remember(backStackEntry) {
                        adminGateRoutingViewModels(backStackEntry)
                    }
                    val generation = store.accessTokenSnapshot(server.id).generation
                    val featureScope = remember(server.id, generation) {
                        sensitiveStores.scope("server:${server.id}", generation)
                    }
                    CompositionLocalProvider(LocalViewModelStoreOwner provides featureScope.owner) {
                        AdminGateScreen(
                            server = server,
                            store = store,
                            principalGeneration = featureScope.principalGeneration,
                            viewModel = routingViewModels.gate,
                            sessionViewModel = routingViewModels.session,
                            useNavigationRail = useNavigationRail,
                            activityScope = scopeControl(aggregate = false, gateId = server.id),
                            onBackToServers = ::openServers,
                            onOpenOrganization = { organizationId ->
                                navController.navigate("orgAdmin/${server.id}/$organizationId") {
                                    popUpTo("adminGate/{serverId}") { inclusive = true }
                                }
                            },
                            // The Owner's support access keeps the platform pages behind it: ending it returns there.
                            onOpenSupportOrganization = { organizationId ->
                                navController.navigate("orgAdmin/${server.id}/$organizationId")
                            },
                        )
                    }
                }
            }
            composable(
                "orgAdmin/{serverId}/{organizationId}",
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("organizationId") { type = NavType.StringType },
                )
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getString("serverId")
                val organizationId = backStackEntry.arguments?.getString("organizationId")
                val server = servers.firstOrNull { it.id == serverId }
                if (server != null && organizationId != null) {
                    BackHandler(enabled = navController.previousBackStackEntry == null) { openServers() }
                    val backToContextChoice = {
                        // Opened from the platform pages (the Owner's support access): they are still behind this one, so return to them.
                        if (!navController.popBackStack("adminGate/{serverId}", inclusive = false)) {
                            navController.navigate("adminGate/${server.id}") { popUpTo("orgAdmin/{serverId}/{organizationId}") { inclusive = true } }
                        }
                        Unit
                    }
                    OrganizationAdminRoute(
                        store = store, sensitiveStores = sensitiveStores, server = server, organizationId = organizationId,
                        useNavigationRail = useNavigationRail,
                        onChooseContext = backToContextChoice, onSignInRequired = backToContextChoice,
                        activityScope = scopeControl(aggregate = false, gateId = server.id, organizationId = organizationId),
                        onManageServers = ::openServers,
                        // Out of the dropdown now; the re-read below keeps it out only if the server no longer lists it.
                        onContextUnavailable = { unavailable ->
                            organizationsByServer[server.id] = organizationsByServer[server.id].orEmpty()
                                .filterNot { it.organizationId == unavailable }
                            contextsRefresh += 1
                        },
                    )
                } else LaunchedEffect(Unit) { replaceScope("allServers") }
            }
            composable("allServers") {
                val featureScope = remember(credentialRevision) {
                    sensitiveStores.scope("all-servers", credentialRevision)
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides featureScope.owner) {
                    AllServersScreen(
                        store = store,
                        principalGeneration = featureScope.principalGeneration,
                        useNavigationRail = useNavigationRail,
                        // v1 "tap to sign in" path for a failed aggregate section:
                        // reuse the existing single-server admin gate/re-login flow
                        // outright rather than building an aggregate-specific
                        // re-login UI. Navigating here re-runs AdminGateViewModel's
                        // checkAccess for that one server, which naturally lands on
                        // ReLoginForm if its token is stale.
                        onSignIn = { server ->
                            navController.navigate("adminGate/${server.id}")
                        },
                        onAddServerRequested = { addingServer = true },
                        activityScope = scopeControl(aggregate = true),
                        onBackToServers = ::openServers,
                    )
                }
            }
        }
    }
}
