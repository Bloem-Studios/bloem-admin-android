package com.bloemstudios.admin.organization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Wire shapes of the organization routes under /api/bloem/v2/admin/organization, as the server's handlers write them
 * (internal/api/handlers/bloem_admin_organization.go, bloem_admin_people.go and the OpenAPI
 * document). Field names stay snake_case like the rest of the app's wire models. Unknown fields are
 * ignored. Strings the server may extend (status, outcome, action) are never matched against a closed
 * set for decoding.
 */

// ---- Administrative context pass -----------------------------------------------------------------

@Serializable
data class AdminSessionRequest(val scope: String, val organization_id: String? = null, val support: AdminSessionSupport? = null)

@Serializable
data class AdminSessionContext(
    val key: String,
    val scope: String,
    val organization_id: String? = null,
    val membership_id: String? = null,
    val name: String,
    val status: String,
    val authority: String,
    val policy_revision: Long = 0,
    val security_revision: Long = 0,
    /** `membership`, or `support` for the Owner's break-glass access; an older server sends neither. */
    val access_basis: String? = null,
    val support_access_id: String? = null,
    val support_expires_at: String? = null,
)

/** The pass is a bearer credential: it is held in memory only and never printed. */
@Serializable
data class AdminSessionResponse(
    val access_token: String,
    val expires_at: String,
    val context: AdminSessionContext,
) {
    override fun toString(): String = "AdminSessionResponse(access_token=<redacted>, context=$context)"
}

// ---- Overview ---------------------------------------------------------------------------------------

@Serializable
data class OrganizationSummary(
    val id: String,
    val slug: String = "",
    val name: String,
    val status: String,
    val owner_account_id: Long? = null,
    val policy_revision: Long = 0,
    val is_default: Boolean = false,
    val membership_count: Long = 0,
    val active_membership_count: Long = 0,
    val profile_count: Long = 0,
    val library_count: Long = 0,
    val entitlement_count: Long = 0,
)

@Serializable
data class OrganizationOverviewResponse(val organization: OrganizationSummary)

// ---- People -----------------------------------------------------------------------------------------

@Serializable
data class ProfileSummary(
    val id: String,
    val name: String,
    val group_id: Long = 0,
    val group_name: String = "",
    val updated_at: String = "",
    /** The profile's own revision: rename, clearing the PIN and removal quote it. Zero from a server without support routes. */
    val revision: Long = 0,
    /** Whether a PIN is set. Support can clear it, never set it. */
    val has_pin: Boolean = false,
    /** The account's first profile, which cannot be removed. */
    val is_primary: Boolean = false,
)

@Serializable
data class PersonSummary(
    val organization_id: String = "",
    val account_id: Long,
    val email: String = "",
    val display_name: String = "",
    val membership_id: String = "",
    /** `invited`, `active` or `suspended`. */
    val membership_status: String,
    val legacy_role: String = "user",
    /** The revision a membership or profile write must quote. */
    val security_revision: Long,
    val last_activity: String? = null,
    val profiles: List<ProfileSummary> = emptyList(),
) {
    val isSuspended: Boolean get() = membership_status == MEMBERSHIP_SUSPENDED
    val isActive: Boolean get() = membership_status == MEMBERSHIP_ACTIVE
    val isAdministrator: Boolean get() = legacy_role == "admin"
    /** What a list row calls the person: the display name, else the email. */
    val label: String get() = display_name.ifBlank { email }

    companion object {
        const val MEMBERSHIP_ACTIVE = "active"
        const val MEMBERSHIP_SUSPENDED = "suspended"
        const val MEMBERSHIP_INVITED = "invited"
    }
}

@Serializable
data class PeoplePage(
    val items: List<PersonSummary> = emptyList(),
    val next_cursor: String? = null,
    val approximate_total: Long = 0,
)

@Serializable
data class PersonResponse(val person: PersonSummary)

@Serializable
data class MembershipStatusUpdate(val expected_revision: Long, val status: String)

@Serializable
data class ProfileGroupUpdate(val expected_revision: Long, val group_id: Long)

/** What the People screen asks for. Blank or empty values are left out of the request. */
data class PeopleFilter(
    val query: String = "",
    val statuses: Set<String> = emptySet(),
    val groupId: Long? = null,
    /** `name` (the server's default), `email` or `recent_activity`. */
    val sort: String? = null,
    /** An RFC 3339 instant: only people active since then. The view model fills it in from [activeWithinDays]. */
    val activeSince: String? = null,
    /** "Active in the last N days", the way a phone picks it; turned into [activeSince] when the request is made. */
    val activeWithinDays: Int? = null,
) {
    /** True when anything narrows the list, so "everyone" is not the answer. */
    val isNarrowed: Boolean get() = query.isNotBlank() || statuses.isNotEmpty() || groupId != null || activeSince != null || activeWithinDays != null
}

object PeopleSort {
    const val NAME = "name"
    const val EMAIL = "email"
    const val RECENT_ACTIVITY = "recent_activity"
    val all = listOf(NAME, EMAIL, RECENT_ACTIVITY)
}

// ---- Access groups ----------------------------------------------------------------------------------

@Serializable
data class AccessGroup(
    val id: String,
    val name: String,
    val description: String = "",
    val library_ids: List<String>? = null,
    val max_playback_quality: String = "",
    val download_allowed: Boolean = false,
    val download_transcode_allowed: Boolean = false,
    val transcode_allowed: Boolean = false,
    val audio_transcode_allowed: Boolean = false,
    val max_streams: Long = 0,
    val max_transcodes: Long = 0,
    val max_remote_stream_bitrate_kbps: Long = 0,
    val max_local_stream_bitrate_kbps: Long = 0,
    val max_profiles: Long = 0,
    val allowed_permissions: List<String>? = null,
    val requests_allowed: Boolean = false,
    val is_default: Boolean = false,
    /** Only the list route sends it. */
    val member_count: Long = 0,
    val created_at: String = "",
    val updated_at: String = "",
)

@Serializable
data class AccessGroupsResponse(val groups: List<AccessGroup> = emptyList())

// ---- Libraries and entitlements ---------------------------------------------------------------------

@Serializable
data class LibraryEntitlement(
    val id: String,
    val organization_id: String = "",
    /** `active`, `suspended` or `revoked`. */
    val status: String,
    /** The revision an entitlement write must quote. */
    val security_revision: Long,
)

@Serializable
data class OrganizationLibrary(
    val folder_id: Long,
    val name: String,
    val type: String = "",
    /** `owned` by the organization, or `entitled` (shared by the server). */
    val access_kind: String,
    val entitlement: LibraryEntitlement? = null,
) {
    val isOwned: Boolean get() = access_kind == "owned"

    /**
     * Whether members can use it right now: the organization's own libraries always, a shared one only while its
     * entitlement is active. This is the ceiling an access group's library choice lives under.
     */
    val countsTowardCeiling: Boolean get() = isOwned || entitlement?.status == "active"
}

@Serializable
data class OrganizationLibrariesResponse(val libraries: List<OrganizationLibrary> = emptyList())

@Serializable
data class OrganizationEntitlementResponse(val entitlement: LibraryEntitlement)

@Serializable
data class EntitlementStatusUpdate(val expected_revision: Long, val status: String)

// ---- Invitations ------------------------------------------------------------------------------------

@Serializable
data class OrganizationInvitation(
    val id: String,
    val email: String = "",
    val delivery: String = "",
    val role: String = "user",
    val access_group_id: String? = null,
    val library_ids: List<String>? = null,
    val create_profile: Boolean = true,
    val show_tour: Boolean = true,
    val note: String = "",
    val invited_by: String = "",
    val invited_by_name: String = "",
    /** `pending`, `accepted`, `revoked` or `expired`. */
    val status: String,
    val expires_at: String = "",
    val accepted_at: String? = null,
    val accepted_user_id: String? = null,
    val created_at: String = "",
) {
    val isPending: Boolean get() = status == "pending"
}

@Serializable
data class OrganizationInvitationsResponse(val invitations: List<OrganizationInvitation> = emptyList())

@Serializable
data class CreateOrganizationInvitation(
    val email: String,
    val expected_revision: Long,
    val access_group_id: Long? = null,
    val note: String? = null,
)

@Serializable
data class RevisionBody(val expected_revision: Long)

/**
 * The answer to create and resend: the claim token is shown once and never again. It is a secret, so
 * [toString] hides it and it must not be logged or saved.
 */
@Serializable
data class InvitationHandoff(
    val invitation: OrganizationInvitation,
    val claim_token: String,
) {
    override fun toString(): String = "InvitationHandoff(invitation=${invitation.id}, claim_token=<redacted>)"
}

// ---- Activity & Audit -------------------------------------------------------------------------------

@Serializable
data class OrganizationAuditEvent(
    val id: Long,
    val source: String = "",
    val created_at: String = "",
    val actor_account_id: Long? = null,
    val action: String,
    val target_id: String = "",
    val outcome: String = "",
    /** `organization_admin`, or `platform_support` for the server operator under support access. Null from older servers. */
    val actor_authority: String? = null,
    /** The reason a support access was opened with, as the operator wrote it (only on `support_access.opened`). */
    val reason: String? = null,
    val support_access_id: String? = null,
) {
    val isPlatformSupport: Boolean get() = actor_authority == PLATFORM_SUPPORT_AUTHORITY
    val reasonToShow: String? get() = reason?.trim()?.takeIf { it.isNotEmpty() }

    /** Who acted. The operator under support access is "Platform support", never an account. */
    fun attribution(): AuditAttribution = when {
        isPlatformSupport -> AuditAttribution.PlatformSupport
        actor_account_id != null -> AuditAttribution.Account(actor_account_id)
        else -> AuditAttribution.Bloem
    }

    companion object { const val PLATFORM_SUPPORT_AUTHORITY = "platform_support" }
}

sealed interface AuditAttribution {
    data object PlatformSupport : AuditAttribution
    data class Account(val id: Long) : AuditAttribution
    data object Bloem : AuditAttribution
}

@Serializable
data class OrganizationAuditPage(
    val events: List<OrganizationAuditEvent> = emptyList(),
    val next_cursor: String? = null,
)

// ---- Policy cohorts and policy decisions (read-only) -------------------------------------------------

@Serializable
data class PolicyView(
    val library_ids: List<Long>? = null,
    val playback_allowed: Boolean = false,
    val max_streams: Long = 0,
    val max_profiles: Long = 0,
    val transcode_allowed: Boolean = false,
    /** Not every server sends it; the web shows the row only when present. */
    val audio_transcode_allowed: Boolean? = null,
    val max_transcodes: Long = 0,
    val download_allowed: Boolean = false,
    val download_transcode_allowed: Boolean = false,
    val max_playback_quality: String = "",
    val allowed_permissions: List<String>? = null,
    val requests_allowed: Boolean = false,
)

@Serializable
data class EntitlementCohort(
    val cohort_id: String,
    val organization_id: String = "",
    val name: String,
    val revision: Long = 0,
    val access_group_id: Long = 0,
    val source_template_key: String? = null,
    val source_template_revision: Long? = null,
    val parent_cohort_id: String? = null,
    val derivation_kind: String = "",
    val policy: PolicyView = PolicyView(),
    /** A fingerprint of the settings, shown with the details. */
    val policy_digest: String = "",
    val archived: Boolean = false,
    val member_count: Long = 0,
    val created_by_account_id: Long? = null,
    val created_at: String = "",
)

@Serializable
data class EntitlementCohortsResponse(val cohorts: List<EntitlementCohort> = emptyList())

@Serializable
data class PolicyDecisionGroupRef(val id: Long = 0, val name: String = "")

@Serializable
data class PolicyDecisionSubject(val account_id: Long = 0, val profile_id: String = "")

@Serializable
data class PolicyDecision(
    val id: Long,
    val timestamp: String = "",
    val subject: PolicyDecisionSubject = PolicyDecisionSubject(),
    val group: PolicyDecisionGroupRef = PolicyDecisionGroupRef(),
    val action: String,
    val allowed: Boolean,
    val reason_code: String = "",
    /** Sanitised by the server; shown only as a count of its keys, never rendered raw. */
    val resource: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PolicyDecisionOrganizationRef(val id: String = "", val membership_id: String? = null)

@Serializable
data class PolicyVersionRef(val kind: String = "", val name: String? = null, val version: Long = 0)

/** `GET /organization/policy-decisions/{id}`: one check with everything the server records about it. */
@Serializable
data class PolicyDecisionDetail(
    val id: Long,
    val timestamp: String = "",
    val organization: PolicyDecisionOrganizationRef = PolicyDecisionOrganizationRef(),
    val subject: PolicyDecisionSubject = PolicyDecisionSubject(),
    val group: PolicyDecisionGroupRef = PolicyDecisionGroupRef(),
    /** The libraries the organization allowed this check to reach. */
    val library_ceiling: List<Long>? = null,
    val action: String = "",
    /** Sanitised by the server (sensitive values arrive as `[redacted]`); shown as label and value pairs. */
    val resource: JsonObject = JsonObject(emptyMap()),
    val allowed: Boolean = false,
    val reason_code: String = "",
    val policy_versions: List<PolicyVersionRef>? = null,
)

@Serializable
data class PolicyDecisionDetailResponse(val decision: PolicyDecisionDetail)

@Serializable
data class PolicyDecisionsPage(
    val decisions: List<PolicyDecision> = emptyList(),
    val next_cursor: String? = null,
)

/** True when this row is the signed-in administrator's own membership. Suspending oneself would lock them out. */
fun PersonSummary.isSelf(context: AdminContext?): Boolean =
    context?.membershipId?.let { it.isNotEmpty() && it == membership_id } ?: false
