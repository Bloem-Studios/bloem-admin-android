package com.bloemstudios.admin.tenants

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.network.api.listTenantMembers
import com.bloemstudios.admin.network.api.listTenants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Hidden in the app until Phase 3 adds the platform pass; nothing creates this view model today.
// Tenants is a Bloem-specific feature that does not exist on a vanilla
// Silo-Server install. [Checking] is the "haven't probed yet" state (the
// entry point stays hidden while in this state, to avoid a flash of a tab
// that then disappears). [NotSupported] means the initial GET /tenants
// probe came back 404 specifically -- the server genuinely doesn't have
// this feature, so the caller should hide the Tenants entry point entirely
// rather than show a broken/errored screen. [Error] means the probe failed
// for any OTHER reason (network failure, 5xx, decode failure, etc) -- that
// is a genuine transient problem, not "doesn't exist", so the caller should
// still show the entry point with a normal error/retry state.
sealed class TenantsProbeState {
    object Checking : TenantsProbeState()
    object Idle : TenantsProbeState()
    object NotSupported : TenantsProbeState()
    data class Loaded(val tenants: List<Tenant>) : TenantsProbeState()
    data class Error(val message: ErrorText) : TenantsProbeState()
}

sealed class TenantMembersState {
    object Loading : TenantMembersState()
    object Idle : TenantMembersState()
    data class Loaded(val members: List<TenantMember>) : TenantMembersState()
    data class Error(val message: ErrorText) : TenantMembersState()
}

class TenantsViewModel : ViewModel() {
    private val latestTenantsLoad = LatestRequestGate()
    private val latestMembersLoad = LatestRequestGate()
    private val _probeState = MutableStateFlow<TenantsProbeState>(TenantsProbeState.Checking)
    val probeState: StateFlow<TenantsProbeState> = _probeState

    private val _membersState = MutableStateFlow<TenantMembersState?>(null)
    val membersState: StateFlow<TenantMembersState?> = _membersState

    // Probes (and, once past the "does this server even have tenants"
    // question, loads) the tenant list. Also the Tenants screen's own
    // load/retry; there is no separate "probe" vs "load" call.
    suspend fun load(client: ApiClient) {
        val generation = latestTenantsLoad.begin()
        val previous = _probeState.value.takeUnless { it is TenantsProbeState.Checking } ?: TenantsProbeState.Idle
        _probeState.value = TenantsProbeState.Checking
        try {
            val tenants = client.listTenants()
            if (!latestTenantsLoad.isCurrent(generation)) return
            _probeState.value = TenantsProbeState.Loaded(tenants)
        } catch (e: ApiError) {
            if (!latestTenantsLoad.isCurrent(generation)) return
            _probeState.value = if (e.statusCode == 404) {
                TenantsProbeState.NotSupported
            } else {
                TenantsProbeState.Error(e.toErrorText(R.string.error_could_not_load_tenants))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!latestTenantsLoad.isCurrent(generation)) return
            _probeState.value = TenantsProbeState.Error(ErrorText.Res(R.string.error_could_not_load_tenants))
        } finally {
            if (latestTenantsLoad.isCurrent(generation) && _probeState.value is TenantsProbeState.Checking) {
                _probeState.value = previous
            }
        }
    }

    suspend fun loadMembers(tenantId: String, client: ApiClient) {
        val generation = latestMembersLoad.begin()
        val previous = _membersState.value.takeUnless { it is TenantMembersState.Loading } ?: TenantMembersState.Idle
        _membersState.value = TenantMembersState.Loading
        try {
            val members = client.listTenantMembers(tenantId)
            if (!latestMembersLoad.isCurrent(generation)) return
            _membersState.value = TenantMembersState.Loaded(members)
        } catch (e: ApiError) {
            if (!latestMembersLoad.isCurrent(generation)) return
            _membersState.value = TenantMembersState.Error(e.toErrorText(R.string.error_could_not_load_tenant_members))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!latestMembersLoad.isCurrent(generation)) return
            _membersState.value = TenantMembersState.Error(ErrorText.Res(R.string.error_could_not_load_tenant_members))
        } finally {
            if (latestMembersLoad.isCurrent(generation) && _membersState.value is TenantMembersState.Loading) {
                _membersState.value = previous
            }
        }
    }

    fun clearMembers() {
        latestMembersLoad.begin()
        _membersState.value = null
    }
}
