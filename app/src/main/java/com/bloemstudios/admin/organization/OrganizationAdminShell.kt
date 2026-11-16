package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.components.Button
import com.bloemstudios.admin.ui.components.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.resolve
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.ui.shell.ActivityScopeControl
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.organizationDestinations
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilSpacing
import kotlinx.coroutines.launch

/**
 * The shell for an organization context, on the existing [AdminShell]: its own destinations, with the
 * organization's name where the server name goes. Nothing platform-level is reachable from here; the
 * destination list is the web's organization menu and [content] receives only those ids.
 */
@Composable
fun OrganizationAdminShell(
    organizationName: String,
    selectedDestinationId: String,
    onDestinationSelected: (String) -> Unit,
    useNavigationRail: Boolean,
    onContextRequested: () -> Unit,
    activityScope: ActivityScopeControl? = null,
    /** Show Now Playing: the server can list this organization's streams. */
    streamsVisible: Boolean = false,
    content: @Composable (destinationId: String) -> Unit,
) {
    val destinations = remember(streamsVisible) { organizationDestinations(streamsVisible) }
    AdminShell(
        context = AdminShellContext(organizationName, isAggregate = false),
        destinations = destinations,
        selectedDestinationId = selectedDestinationId.takeIf { id -> destinations.any { it.id == id } } ?: destinations.first().id,
        onDestinationSelected = onDestinationSelected,
        useNavigationRail = useNavigationRail,
        onContextRequested = onContextRequested,
        activityScope = activityScope,
        content = content,
    )
}

/** The page for an organization destination id. Unknown ids show nothing. */
@Composable
fun OrganizationDestinationContent(destinationId: String, session: OrganizationSession) {
    when (destinationId) {
        "organization.overview" -> OrganizationOverviewScreen(session)
        "organization.streams" -> OrganizationStreamsScreen(session)
        "organization.people" -> OrganizationPeopleScreen(session)
        "organization.cohorts" -> OrganizationCohortsScreen(session)
        "organization.groups" -> OrganizationAccessGroupsScreen(session)
        "organization.libraries" -> OrganizationLibrariesScreen(session)
        "organization.invitations" -> OrganizationInvitationsScreen(session)
        "organization.decisions" -> OrganizationDecisionsScreen(session)
        "organization.activity" -> OrganizationActivityScreen(session)
    }
}

private sealed interface HostState {
    data object Opening : HostState
    data class Open(val context: AdminContext) : HostState
    data class Denied(val reason: OrganizationDenial) : HostState
    data object SignInRequired : HostState
    data class Failed(val message: ErrorText) : HostState
}

/**
 * Opens [session] and shows the organization shell. The server has the last word:
 *  - 403 or 404 on the pass, or a 403 on any later call, shows why (the organization is suspended, or the
 *    account no longer administers it) with a way back to context choice ([onChooseContext]), and tells
 *    [onContextUnavailable] once so the context list drops the organization until the server lists it again;
 *  - a rejected sign-in goes to [onSignInRequired] (the gate, which shows today's re-login form).
 * The session is closed when this leaves composition, which drops the pass.
 */
@Composable
fun OrganizationAdminHost(
    session: OrganizationSession,
    useNavigationRail: Boolean,
    onChooseContext: () -> Unit,
    onSignInRequired: () -> Unit,
    activityScope: ActivityScopeControl? = null,
    /** The drawer's "Manage servers"; without it the drawer item returns to context choice. */
    onManageServers: (() -> Unit)? = null,
    /** Called once with the organization id when the server refuses it (suspended, authority lost, or hidden). */
    onContextUnavailable: (organizationId: String) -> Unit = {},
) {
    val capabilities by session.supportCapabilities.collectAsState()
    val openContext by session.context.collectAsState()
    val scope = rememberCoroutineScope()
    var endingAccess by remember(session) { mutableStateOf(false) }
    var endError by remember(session) { mutableStateOf<ErrorText?>(null) }
    var state by remember(session) { mutableStateOf<HostState>(HostState.Opening) }
    var attempt by remember(session) { mutableIntStateOf(0) }
    var selectedDestinationId by rememberSaveable { mutableStateOf("organization.overview") }
    val denial by session.denial.collectAsState()

    LaunchedEffect(session, attempt) {
        state = HostState.Opening
        state = try {
            HostState.Open(session.open())
        } catch (denied: OrganizationAccessDenied) {
            session.markDenied(denied.reason)
            HostState.Denied(denied.reason)
        } catch (error: ApiError) {
            if (error.statusCode == 401) HostState.SignInRequired else HostState.Failed(error.toErrorText(R.string.organization_error_overview))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HostState.Failed(ErrorText.Res(R.string.organization_error_overview))
        }
    }
    // Ask once what the server can do for support; a server that has none shows nothing new.
    LaunchedEffect(session, state is HostState.Open) { if (state is HostState.Open) session.loadSupportCapabilities() }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(state) { if (state is HostState.SignInRequired) onSignInRequired() }
    // The organization is no longer one this account can open: leave it out of the context list.
    LaunchedEffect(denial) { if (denial != null) onContextUnavailable(session.organizationId) }

    when (val current = state) {
        HostState.Opening, HostState.SignInRequired -> OpeningSurface(onChooseContext)
        is HostState.Failed -> FailureSurface(
            current.message, onRetry = { attempt += 1 }, onChooseContext = onChooseContext,
        )
        is HostState.Denied -> DeniedSurface(current.reason, onChooseContext)
        is HostState.Open -> if (denial != null) DeniedSurface(denial ?: OrganizationDenial.LostAuthority, onChooseContext) else OrganizationAdminShell(
            organizationName = current.context.name,
            selectedDestinationId = selectedDestinationId,
            onDestinationSelected = { selectedDestinationId = it },
            useNavigationRail = useNavigationRail,
            onContextRequested = onManageServers ?: onChooseContext,
            activityScope = activityScope,
            streamsVisible = capabilities.streams.list,
        ) { destinationId ->
            Column(Modifier.fillMaxSize()) {
                // The Owner's audited access is never out of sight: what it is, how long is left, and a way to end it.
                if (openContext?.isSupport == true) SupportAccessBanner(
                    endsAt = openContext?.supportExpiresAt, ending = endingAccess, error = endError,
                    onEnd = {
                        scope.launch {
                            endingAccess = true
                            endError = null
                            try {
                                session.endSupportAccess()
                                onChooseContext()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (denied: OrganizationAccessDenied) {
                                // Already over: nothing is left to end.
                                onChooseContext()
                            } catch (_: Exception) {
                                endError = ErrorText.Res(R.string.organization_support_end_error)
                            } finally {
                                endingAccess = false
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
                )
                Box(Modifier.weight(1f)) { OrganizationDestinationContent(destinationId, session) }
            }
        }
    }
}

@Composable
private fun OpeningSurface(onChooseContext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal).testTag("organization-opening")) {
        Text(stringResource(R.string.organization_opening), style = MaterialTheme.typography.titleLarge, color = VeilColors.Text)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = VeilSpacing.Medium))
        TextButton(onClick = onChooseContext, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
            Text(stringResource(R.string.organization_choose_another))
        }
    }
}

@Composable
private fun DeniedSurface(reason: OrganizationDenial, onChooseContext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal).testTag("organization-denied"),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium),
    ) {
        Text(
            stringResource(reason.messageRes()), style = MaterialTheme.typography.titleLarge, color = VeilColors.Text,
            modifier = Modifier.semantics { heading() },
        )
        Button(onClick = onChooseContext, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
            Text(stringResource(R.string.organization_choose_another))
        }
    }
}

@Composable
private fun FailureSurface(message: ErrorText, onRetry: () -> Unit, onChooseContext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(VeilSpacing.CompactHorizontal), verticalArrangement = Arrangement.spacedBy(VeilSpacing.Medium)) {
        Text(message.resolve(), style = MaterialTheme.typography.bodyLarge, color = VeilColors.Danger)
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
            Text(stringResource(R.string.organization_try_again))
        }
        TextButton(onClick = onChooseContext, modifier = Modifier.heightIn(min = VeilSpacing.MinimumTouchTarget)) {
            Text(stringResource(R.string.organization_choose_another))
        }
    }
}

/**
 * Context choice for an organization-only administrator who has several organizations. Picking one
 * opens it; the callback owns navigation.
 */
@Composable
fun OrganizationContextChoice(
    serverName: String,
    contexts: List<AdminContext>,
    onOpen: (AdminContext) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("organization-choice"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(VeilSpacing.CompactHorizontal),
        verticalArrangement = Arrangement.spacedBy(VeilSpacing.Small),
    ) {
        item {
            Text(
                stringResource(R.string.organization_choice_title), style = MaterialTheme.typography.headlineMedium,
                color = VeilColors.Text, modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.organization_choice_body, serverName),
                style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted,
            )
        }
        items(contexts, key = { it.key }) { context ->
            Button(
                onClick = { onOpen(context) },
                modifier = Modifier.fillMaxWidth().heightIn(min = VeilSpacing.MinimumTouchTarget),
            ) { Text(context.name) }
        }
    }
}

/**
 * What the gate shows an organization-only administrator: straight into the organization when there
 * is exactly one, otherwise a choice. [onOpen] owns navigation.
 */
@Composable
fun OrganizationAdmission(serverName: String, contexts: List<AdminContext>, onOpen: (AdminContext) -> Unit) {
    val only = contexts.singleOrNull()
    if (only != null) {
        LaunchedEffect(only.key) { onOpen(only) }
        OpeningSurface(onChooseContext = {})
    } else {
        OrganizationContextChoice(serverName, contexts, onOpen)
    }
}
