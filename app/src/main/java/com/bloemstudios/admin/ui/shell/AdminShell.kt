package com.bloemstudios.admin.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bloemstudios.admin.ui.gestures.horizontalSwipe
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.components.DestructiveTextButton
import com.bloemstudios.admin.ui.components.TextButton
import com.bloemstudios.admin.ui.theme.LocalReducedMotion
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilMotionTokens
import kotlinx.coroutines.launch

private val EmptyInsets = WindowInsets(0, 0, 0, 0)

private val RecentDestinationSaver = Saver<MutableMap<AdminGroup, String>, List<String>>(
    save = { recent -> recent.entries.flatMap { listOf(it.key.name, it.value) } },
    restore = { flat ->
        mutableStateMapOf<AdminGroup, String>().apply {
            flat.chunked(2).forEach { pair ->
                if (pair.size == 2) {
                    runCatching { AdminGroup.valueOf(pair[0]) }
                        .getOrNull()
                        ?.let { group -> put(group, pair[1]) }
                }
            }
        }
    },
)

internal fun resolveEffectiveDestination(
    destinations: List<AdminDestination>,
    selectedDestinationId: String,
    recentDestinationIds: Map<AdminGroup, String>,
): AdminDestination? {
    destinations.firstOrNull { it.id == selectedDestinationId }?.let { return it }

    val previousGroup = recentDestinationIds.entries
        .firstOrNull { it.value == selectedDestinationId }
        ?.key
    return previousGroup?.let { group ->
        selectSupportedDestination(
            group = group,
            destinations = destinations,
            currentDestinationId = selectedDestinationId,
            rememberedDestinationId = recentDestinationIds[group],
        )
    } ?: destinations.firstOrNull { it.id == START_DESTINATION_ID }
        ?: destinations.firstOrNull()
}

/**
 * Lets the app host open its own Settings page from the drawer. The shell has no app-level state, so the
 * activity provides this; where it is absent (previews, tests of a single screen) the drawer leaves
 * the "Settings" item out.
 */
val LocalAppSettingsRequest = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * True while the modal navigation drawer is open. A destination that handles system back itself (the User
 * page) steps aside while this is true, so back closes the drawer first. False where there is no shell.
 */
val LocalShellDrawerOpen = compositionLocalOf { false }

@Composable
fun AdminShell(
    context: AdminShellContext,
    destinations: List<AdminDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (String) -> Unit,
    useNavigationRail: Boolean,
    onContextRequested: () -> Unit,
    activityScope: ActivityScopeControl? = null,
    statusContent: (@Composable () -> Unit)? = null,
    contextEnabled: Boolean = true,
    /** A count at the end of a destination's drawer row, by destination id (the Requests menu item's Needs approval count). */
    destinationBadges: Map<String, Int> = emptyMap(),
    content: @Composable (selectedDestinationId: String) -> Unit,
) {
    AdminShellLayout(
        context = context,
        destinations = destinations,
        selectedDestinationId = selectedDestinationId,
        onDestinationSelected = onDestinationSelected,
        useNavigationRail = useNavigationRail,
        onContextRequested = onContextRequested,
        activityScope = activityScope,
        statusContent = statusContent,
        contextEnabled = contextEnabled,
        shellWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
        destinationBadges = destinationBadges,
        content = content,
    )
}

/**
 * The shell is a top bar (menu, page title, context dropdown) over the destination content, with the
 * navigation drawer beside it. Wide layouts keep the drawer permanently open; narrow ones open it from
 * the menu button. [onContextRequested] backs the drawer's "Manage servers" and a switcher that has no sheet to list servers
 * in. The drawer's foot is Manage servers, Settings and Sign out.
 */
@Composable
internal fun AdminShellLayout(
    context: AdminShellContext,
    destinations: List<AdminDestination>,
    selectedDestinationId: String,
    onDestinationSelected: (String) -> Unit,
    useNavigationRail: Boolean,
    onContextRequested: () -> Unit,
    activityScope: ActivityScopeControl? = null,
    statusContent: (@Composable () -> Unit)?,
    contextEnabled: Boolean,
    shellWindowInsets: WindowInsets,
    destinationBadges: Map<String, Int> = emptyMap(),
    content: @Composable (selectedDestinationId: String) -> Unit,
) {
    val interactionLock = remember { ShellInteractionLockController() }
    val isLocked by interactionLock.isLocked
    val recentDestinations = rememberSaveable(saver = RecentDestinationSaver) {
        mutableStateMapOf<AdminGroup, String>()
    }
    val effectiveDestination = resolveEffectiveDestination(
        destinations = destinations,
        selectedDestinationId = selectedDestinationId,
        recentDestinationIds = recentDestinations,
    )

    LaunchedEffect(effectiveDestination?.id, destinations) {
        effectiveDestination?.let { destination ->
            recentDestinations[destination.group] = destination.id
            if (selectedDestinationId != destination.id) {
                onDestinationSelected(destination.id)
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var searching by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReducedMotion.current
    val appSettings = LocalAppSettingsRequest.current
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
    val signOut = activityScope?.onSignOut
    fun setDrawer(open: Boolean) {
        scope.launch {
            val target = if (open) DrawerValue.Open else DrawerValue.Closed
            if (reducedMotion) drawerState.snapTo(target) else if (open) drawerState.open() else drawerState.close()
        }
    }
    // The permanent drawer has nothing to close; this is for the modal one only.
    BackHandler(enabled = !useNavigationRail && drawerState.isOpen) { setDrawer(open = false) }
    LaunchedEffect(useNavigationRail) { if (useNavigationRail) drawerState.snapTo(DrawerValue.Closed) }

    val drawerContent: @Composable (Modifier) -> Unit = { modifier ->
        ShellDrawerContent(
            destinations = destinations,
            selectedDestinationId = effectiveDestination?.id,
            locked = isLocked,
            manageServersEnabled = contextEnabled,
            onDestination = { destination ->
                recentDestinations[destination.group] = destination.id
                onDestinationSelected(destination.id)
                setDrawer(open = false)
            },
            onManageServers = {
                setDrawer(open = false)
                onContextRequested()
            },
            onSettings = appSettings?.let { request ->
                {
                    setDrawer(open = false)
                    request()
                }
            },
            onSignOut = signOut?.let {
                {
                    setDrawer(open = false)
                    confirmingSignOut = true
                }
            },
            modifier = modifier,
            badges = destinationBadges,
            signOutEnabled = contextEnabled,
        )
    }

    val screen: @Composable () -> Unit = {
        Scaffold(
            contentWindowInsets = EmptyInsets,
            topBar = {
                Column {
                    ShellTopBar(
                        title = effectiveDestination?.let { stringResource(it.labelRes) } ?: context.displayName,
                        context = context,
                        enabled = contextEnabled && !isLocked,
                        activityScope = activityScope,
                        onContextClick = onContextRequested,
                        onMenuClick = if (useNavigationRail) null else ({ setDrawer(open = true) }),
                        onSearchClick = if (destinations.isEmpty()) null else ({ searching = true }),
                        searchEnabled = !isLocked,
                    )
                    statusContent?.invoke()
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                DestinationContent(
                    destinationId = effectiveDestination?.id,
                    supportedDestinationIds = destinations.mapTo(mutableSetOf()) { it.id },
                    content = content,
                    modifier = Modifier.pageSwipe(
                        // Swiping walks the pages in the drawer's order, like the old tabs.
                        order = remember(destinations) { groupedForDrawer(destinations).flatMap { it.second } },
                        currentId = effectiveDestination?.id,
                        enabled = !isLocked,
                    ) { destination ->
                        recentDestinations[destination.group] = destination.id
                        onDestinationSelected(destination.id)
                    },
                )
            }
        }
    }

    val navigationLabel = stringResource(R.string.shell_navigation)
    val drawerOpen = !useNavigationRail && drawerState.isOpen
    CompositionLocalProvider(LocalShellInteractionLock provides interactionLock, LocalShellDrawerOpen provides drawerOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(shellWindowInsets)
                .consumeWindowInsets(shellWindowInsets),
        ) {
            // While the search covers the shell, what is under it is out of reach of accessibility too.
            Box(if (searching) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                PermanentNavigationDrawer(
                    drawerContent = {
                        PermanentDrawerSheet(
                            modifier = Modifier.width(
                                (240.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)),
                            ),
                            drawerContainerColor = VeilColors.Sidebar,
                            drawerContentColor = VeilColors.Text,
                        ) { drawerContent(Modifier) }
                    },
                    content = screen,
                )
            } else {
                val open = drawerState.isOpen || drawerState.isAnimationRunning
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // A horizontal swipe on the page changes pages, so the drawer opens only from the
                    // menu button; once open it can still be swiped closed.
                    gesturesEnabled = open,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = VeilColors.Sidebar,
                            drawerContentColor = VeilColors.Text,
                            // A closed drawer is composed off-screen; keep it out of accessibility
                            // and test trees until it is opened.
                            modifier = if (open) Modifier.semantics { paneTitle = navigationLabel }
                            else Modifier.clearAndSetSemantics { },
                        ) { drawerContent(Modifier) }
                    },
                    content = screen,
                )
            }
            }
            // Over the whole shell, the drawer included. Back closes it before anything else.
            if (searching && destinations.isNotEmpty()) {
                BackHandler { searching = false }
                PageSearchSheet(
                    destinations = destinations,
                    selectedDestinationId = effectiveDestination?.id,
                    locked = isLocked,
                    onPick = { destination ->
                        recentDestinations[destination.group] = destination.id
                        searching = false
                        onDestinationSelected(destination.id)
                    },
                    onDismiss = { searching = false },
                )
            }
        }
    }
    if (confirmingSignOut && signOut != null) {
        SignOutConfirmation(
            allServers = activityScope?.allServers == true,
            serverName = context.displayName,
            onConfirm = { confirmingSignOut = false; signOut() },
            onDismiss = { confirmingSignOut = false },
        )
    }
}

/** Test tag of the confirmation's button. */
internal const val SignOutConfirmTag = "shell-sign-out-confirm"

/**
 * Sign out names what it changes: this server, or every saved server on All servers. It removes the saved sign-in from this
 * device only; no server is removed from the list.
 */
@Composable
private fun SignOutConfirmation(allServers: Boolean, serverName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (allServers) stringResource(R.string.sign_out_all_title) else stringResource(R.string.sign_out_title, serverName))
        },
        text = { Text(stringResource(R.string.sign_out_body)) },
        confirmButton = {
            DestructiveTextButton(onClick = onConfirm, modifier = Modifier.testTag(SignOutConfirmTag)) {
                Text(stringResource(R.string.drawer_sign_out))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Test tag of the page area that answers swipes. */
internal const val ShellPageTag = "shell-page"

/**
 * A horizontal swipe over the page moves to the next page (swipe left) or the previous one (swipe
 * right) in [order]; there is no wrap-around. Anything inside the page that scrolls sideways (tab
 * rows, charts) takes the gesture first, because it consumes the drag before this ancestor sees it.
 */
private fun Modifier.pageSwipe(
    order: List<AdminDestination>,
    currentId: String?,
    enabled: Boolean,
    onSwipe: (AdminDestination) -> Unit,
): Modifier = this
    .testTag(ShellPageTag)
    .horizontalSwipe(order, currentId, enabled = enabled) { step ->
        val index = order.indexOfFirst { it.id == currentId }
        if (index >= 0) order.getOrNull(index + step)?.let(onSwipe)
    }

@Composable
private fun DestinationContent(
    destinationId: String?,
    supportedDestinationIds: Set<String>,
    content: @Composable (selectedDestinationId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current

    if (reducedMotion) {
        Box(modifier = modifier.fillMaxSize()) {
            if (destinationId != null && destinationId in supportedDestinationIds) {
                // Each destination gets its own composition, as AnimatedContent's contentKey gives it below.
                key(destinationId) { content(destinationId) }
            }
        }
        return
    }

    val motion = VeilMotionTokens()
    AnimatedContent(
        targetState = destinationId,
        contentKey = { it },
        transitionSpec = {
            fadeIn(tween(motion.normalMillis, easing = motion.ease)) togetherWith
                fadeOut(tween(motion.fastMillis, easing = motion.ease))
        },
        label = "admin-shell-destination",
        modifier = modifier.fillMaxSize(),
    ) { selected ->
        if (selected != null && selected in supportedDestinationIds) content(selected)
    }
}
