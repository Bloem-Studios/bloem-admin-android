package com.bloemstudios.admin.tenants

import kotlinx.serialization.Serializable

// GET /api/v1/admin/tenants. Bloem-specific multi-tenant reseller/hosting
// feature -- does NOT exist on vanilla Silo-Server installs (see
// TenantsViewModel's 404-vs-genuine-error probe logic). `external_ref` from
// the live response (an arbitrary object, e.g. {"operator_id":...,
// "service_id":...}) is intentionally not modeled here: nothing in this
// read-only screen displays it, and ignoreUnknownKeys=true (see ApiClient)
// means simply omitting the field from this data class is enough to decode
// past it safely regardless of its shape.
@Serializable
data class Tenant(
    val tenant_id: String,
    val name: String,
    val limits: TenantLimits? = null,
    val usage: TenantUsage? = null,
    val frozen: Boolean = false,
    val frozen_reason: String? = null,
    val applied_entitlement_revision: Long? = null
)

@Serializable
data class TenantLimits(
    val slots: Int? = null,
    val transcodes: Int? = null
)

@Serializable
data class TenantUsage(
    val slots_used: Int? = null
)

// GET /api/v1/admin/tenants/{tenant_id}/members
@Serializable
data class TenantMember(
    val user_id: Int,
    val username: String,
    val email: String,
    val status: String
)
