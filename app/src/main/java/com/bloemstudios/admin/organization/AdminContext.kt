package com.bloemstudios.admin.organization

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.getServerStatus
import com.bloemstudios.admin.network.api.listOrganizationMemberships
import com.bloemstudios.admin.platform.isBloemServer
import kotlinx.serialization.Serializable

enum class AdminContextScope { Platform, Organization }

/** What lets the account in: the platform role, or an administrator membership of one organization. */
enum class AdminAuthority { PlatformAdmin, OrganizationAdmin }

/** How the pass was opened: the account's own membership, or the Owner's audited support access. */
enum class AccessBasis { Membership, Support }

/**
 * One place an account can administer. [key] is the web's own key: `platform` or
 * `organization:<organization id>`. [status] is `active` for every context offered, because the
 * server lists only active memberships of active organizations.
 */
data class AdminContext(
    val key: String,
    val name: String,
    val scope: AdminContextScope,
    val organizationId: String?,
    val status: String,
    val authority: AdminAuthority,
    val membershipId: String? = null,
    val policyRevision: Long = 0,
    val securityRevision: Long = 0,
    val accessBasis: AccessBasis = AccessBasis.Membership,
    /** Set with [AccessBasis.Support]: the access record, and when the server ends it (at most four hours after opening). */
    val supportAccessId: String? = null,
    val supportExpiresAt: java.time.Instant? = null,
) {
    val isSupport: Boolean get() = accessBasis == AccessBasis.Support

    companion object {
        const val PLATFORM_KEY = "platform"
        fun organizationKey(organizationId: String) = "organization:$organizationId"
    }
}

/** One row of `GET /api/bloem/v2/organizations`: the account's own membership. */
@Serializable
data class OrganizationMembership(
    val id: String,
    val slug: String = "",
    val name: String,
    val default: Boolean = false,
    val membership_id: String,
    val membership_role: String,
    val policy_revision: Long = 0,
    val security_revision: Long = 0,
)

@Serializable
data class OrganizationMembershipList(val organizations: List<OrganizationMembership> = emptyList())

/**
 * The web's rule (AdminContextProvider): a platform administrator gets Platform first, then every
 * organization they belong to; anyone else gets only the organizations where their role is `admin`.
 */
fun adminContextsFor(memberships: List<OrganizationMembership>, platformAdmin: Boolean): List<AdminContext> = buildList {
    if (platformAdmin) {
        add(
            AdminContext(
                key = AdminContext.PLATFORM_KEY, name = "Platform", scope = AdminContextScope.Platform,
                organizationId = null, status = "active", authority = AdminAuthority.PlatformAdmin,
            )
        )
    }
    for (membership in memberships) {
        if (!platformAdmin && membership.membership_role != "admin") continue
        add(
            AdminContext(
                key = AdminContext.organizationKey(membership.id), name = membership.name,
                scope = AdminContextScope.Organization, organizationId = membership.id, status = "active",
                authority = if (platformAdmin) AdminAuthority.PlatformAdmin else AdminAuthority.OrganizationAdmin,
                membershipId = membership.membership_id, policyRevision = membership.policy_revision,
                securityRevision = membership.security_revision,
            )
        )
    }
}

/**
 * The contexts [platformAdmin] and the account's memberships add up to. A server without
 * organizations (`404`, a plain Silo server) has none beyond Platform.
 */
suspend fun ApiClient.listAdminContexts(platformAdmin: Boolean): List<AdminContext> {
    // Organizations exist only on a Bloem server: a plain Silo server is never asked for them.
    val memberships = if (!isBloemServer()) emptyList() else try {
        listOrganizationMemberships()
    } catch (error: ApiError) {
        if (error.statusCode == 404) emptyList() else throw error
    }
    return adminContextsFor(memberships, platformAdmin)
}

/**
 * Probes platform authority with the admin status route (200 platform administrator, 403 not), then
 * lists contexts. A 401 or any other failure propagates.
 */
suspend fun ApiClient.discoverAdminContexts(): List<AdminContext> {
    val platformAdmin = try {
        getServerStatus()
        true
    } catch (error: ApiError) {
        if (error.statusCode == 403) false else throw error
    }
    return listAdminContexts(platformAdmin)
}
