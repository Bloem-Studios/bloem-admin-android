package com.bloemstudios.admin.organization

import com.bloemstudios.admin.ui.shell.ActivityScopeControl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.AdminUnauthorizedBinding
import com.bloemstudios.admin.servers.SensitiveViewModelStoreRegistry
import com.bloemstudios.admin.servers.ServerStore

/**
 * The destination behind `orgAdmin/{serverId}/{organizationId}`: one saved server, one organization.
 * Organization screens' state lives in a credential-scoped ViewModel store, so a changed sign-in or the
 * app lock clears it, exactly as for the platform screens. The session is opened here and closed when
 * the destination leaves.
 */
@Composable
fun OrganizationAdminRoute(
    store: ServerStore,
    sensitiveStores: SensitiveViewModelStoreRegistry,
    server: AdminServer,
    organizationId: String,
    useNavigationRail: Boolean,
    onChooseContext: () -> Unit,
    onSignInRequired: () -> Unit,
    activityScope: ActivityScopeControl? = null,
    onManageServers: (() -> Unit)? = null,
    onContextUnavailable: (organizationId: String) -> Unit = {},
) {
    val generation = store.accessTokenSnapshot(server.id).generation
    val featureScope = remember(server.id, organizationId, generation) {
        sensitiveStores.scope("organization:${server.id}:$organizationId", generation)
    }
    val account = remember(server.id, generation) { store.client(server) }
    // The server Owner's support access for this organization, if he opened it in this run: the pass then comes from that
    // access (and its renewals continue it) instead of a membership he does not have.
    val support = remember(server.id, organizationId) { SupportAccessGrants.current(server.id, organizationId) }
    val session = remember(account, organizationId, support) { OrganizationSession(account, organizationId, support = support) }
    // The account client reports a sign-in it can no longer refresh: back to the gate, which shows re-login.
    DisposableEffect(account, server.id, store) {
        val binding = AdminUnauthorizedBinding(
            client = account,
            currentCredential = { store.accessTokenSnapshot(server.id) },
            onCurrentUnauthorized = onSignInRequired,
        )
        onDispose { binding.close() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides featureScope.owner) {
        OrganizationAdminHost(
            session = session,
            useNavigationRail = useNavigationRail,
            onChooseContext = onChooseContext,
            onSignInRequired = onSignInRequired,
            activityScope = activityScope,
            onManageServers = onManageServers,
            onContextUnavailable = onContextUnavailable,
        )
    }
}
