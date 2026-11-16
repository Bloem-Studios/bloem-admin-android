package com.bloemstudios.admin.platform

import com.bloemstudios.admin.organization.OrganizationSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * What the platform organization routes send and take (server: internal/api/handlers/bloem_admin_platform.go and
 * entitlement_templates.go). These belong to the platform context pass; none of them is used inside an organization.
 */

/** `GET /admin/platform/organizations/{id}` and the answer of suspend and reactivate: the organization under `organization`. */
@Serializable
data class PlatformOrganizationResponse(val organization: OrganizationSummary)

/** The body of suspend and reactivate: the organization's `policy_revision` as the admin last saw it. */
@Serializable
data class ExpectedRevisionBody(val expected_revision: Long)

/**
 * One row of `GET /admin/platform/organizations/{id}/memberships`. The server sends the membership with the account's
 * `email` and `username`; it never sends a display name here.
 */
@Serializable
data class PlatformMembership(
    val id: String,
    val organization_id: String = "",
    val account_id: Long,
    /** `invited`, `active` or `suspended`. */
    val status: String,
    /** `admin` or `user`. */
    val legacy_role: String = "user",
    val security_revision: Long = 0,
    val email: String = "",
    val username: String = "",
) {
    val isAdministrator: Boolean get() = legacy_role == "admin"

    /** What a row calls the person: the username, else the email. Never the account number. */
    val label: String get() = username.ifBlank { email }
}

@Serializable
data class PlatformMembershipsPage(val memberships: List<PlatformMembership> = emptyList(), val next_cursor: String? = null)

// ---- Plan ---------------------------------------------------------------------------------------------

/** What a plan or a default group allows (`entitlementPolicyJSON`). A null list means everything. */
@Serializable
data class EntitlementPolicy(
    val all_libraries: Boolean = false,
    val library_ids: List<Int>? = null,
    val playback_allowed: Boolean = false,
    val max_streams: Int = 0,
    val max_profiles: Int = 0,
    val transcode_allowed: Boolean = false,
    val max_transcodes: Int = 0,
    val download_allowed: Boolean = false,
    val download_transcode_allowed: Boolean = false,
    val max_playback_quality: String = "",
    val allowed_permissions: List<String>? = null,
    val requests_allowed: Boolean = false,
)

@Serializable
data class ManagedDefaultGroup(val id: Long = 0, val name: String = "", val policy: EntitlementPolicy = EntitlementPolicy())

@Serializable
data class TenantLimits(val slots: Int = 0, val transcodes: Int = 0)

/** `GET /admin/platform/organizations/{id}/entitlement`: the plan an organization is on. A blank key means none yet. */
@Serializable
data class OrganizationEntitlement(
    val template_key: String = "",
    val template_revision: Long = 0,
    val managed_default_group: ManagedDefaultGroup? = null,
    val tenant_limits: TenantLimits = TenantLimits(),
    val library_ids: List<Int>? = null,
    val last_reconciled_at: String? = null,
) {
    val hasPlan: Boolean get() = template_key.isNotBlank() && template_revision > 0
}

/** One plan of `GET /admin/platform/entitlement-templates`. */
@Serializable
data class EntitlementTemplate(
    val key: String,
    val name: String = "",
    val revision: Long = 0,
    val enabled: Boolean = false,
    val archived: Boolean = false,
    val status: String = "",
    val policy: EntitlementPolicy = EntitlementPolicy(),
) {
    val label: String get() = name.ifBlank { key }
}

@Serializable
data class EntitlementTemplatesResponse(val templates: List<EntitlementTemplate> = emptyList())

/** The body of dry-run: which plan, at which revision. The server rejects any other field. */
@Serializable
data class EntitlementSelection(val template_key: String, val template_revision: Long)

/** The body of apply: the same selection, the token the dry-run gave, and a key that makes a repeat harmless. */
@Serializable
data class EntitlementApplyBody(
    val template_key: String,
    val template_revision: Long,
    val dry_run_token: String,
    val idempotency_key: String,
)

/** One line of a dry-run's diff. For a plan change the field is `template_revision` and each side is `{template_key, template_revision}`. */
@Serializable
data class EntitlementChange(val field: String, val before: JsonElement? = null, val after: JsonElement? = null)

@Serializable
data class EntitlementResult(
    val template_key: String = "",
    val template_revision: Long = 0,
    val changed: Boolean = false,
    val profiles_moved: Int = 0,
    val previous_template_key: String = "",
    val previous_template_revision: Long = 0,
)

/** `POST .../entitlement/dry-run`: what applying the plan would change, and the token that lets apply go ahead. */
@Serializable
data class EntitlementDryRun(
    val template_key: String = "",
    val template_revision: Long = 0,
    val changed: Boolean = false,
    val dry_run_token: String = "",
    val expires_at: String = "",
    val changes: List<EntitlementChange> = emptyList(),
    val warnings: List<String> = emptyList(),
    val result: EntitlementResult = EntitlementResult(),
) {
    override fun toString(): String = "EntitlementDryRun(template_key=$template_key, template_revision=$template_revision, changed=$changed, dry_run_token=<redacted>)"
}

// ---- Capabilities -------------------------------------------------------------------------------------

/** `GET /api/bloem/v2/capabilities`: the public feature list, tokens and the fixed object of booleans. */
@Serializable
data class PlatformCapabilities(
    val feature_tokens: List<String> = emptyList(),
    val features: PlatformCapabilityFeatures = PlatformCapabilityFeatures(),
)

@Serializable
data class PlatformCapabilityFeatures(val organization_memberships: Boolean = false)
