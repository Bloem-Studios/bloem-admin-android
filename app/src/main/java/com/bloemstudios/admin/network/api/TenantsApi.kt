package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.tenants.Tenant
import com.bloemstudios.admin.tenants.TenantMember

/*
 * Platform routes. They need the platform pass (POST /api/bloem/v2/admin/session), which the app
 * does not obtain yet, so nothing calls them while the Tenants destination is hidden. Phase 3
 * wires the pass and brings Tenants back.
 */

/** Needs the Phase 3 platform pass. Lists tenants on the platform. */
suspend fun ApiClient.listTenants(): List<Tenant> = get("/api/bloem/v2/admin/platform/tenants")

/** Needs the Phase 3 platform pass. Lists the members of one tenant. */
suspend fun ApiClient.listTenantMembers(tenantId: String): List<TenantMember> =
    get("/api/bloem/v2/admin/platform/tenants/$tenantId/members")
