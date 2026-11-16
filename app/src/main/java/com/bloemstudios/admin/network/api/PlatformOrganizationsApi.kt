package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.platform.EntitlementApplyBody
import com.bloemstudios.admin.platform.EntitlementDryRun
import com.bloemstudios.admin.platform.EntitlementResult
import com.bloemstudios.admin.platform.EntitlementSelection
import com.bloemstudios.admin.platform.EntitlementTemplate
import com.bloemstudios.admin.platform.EntitlementTemplatesResponse
import com.bloemstudios.admin.platform.ExpectedRevisionBody
import com.bloemstudios.admin.platform.OrganizationEntitlement
import com.bloemstudios.admin.platform.PlatformCapabilities
import com.bloemstudios.admin.platform.PlatformMembershipsPage
import com.bloemstudios.admin.platform.PlatformOrganizationResponse
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart

/*
 * The platform organization routes (server: internal/api/router_bloem.go, handlers/bloem_admin_platform.go and
 * handlers/entitlement_templates.go). Every call here needs the platform context pass (POST /admin/session with
 * scope "platform"), so they are used only through PlatformSession, never with the account's own token and never
 * from organization mode. Writes are sent once (replayUnauthorized = false): a pass the server refused is renewed by
 * PlatformSession and the administrator decides whether to ask again.
 *
 * `GET /admin/platform/organizations/` (the list) is [listPlatformOrganizations] in OrganizationSupportApi.kt.
 */

private const val PLATFORM = "/api/bloem/v2/admin/platform"

/** The public capability document of a Bloem server. A plain Silo server has no such route, so this is how a server is told apart. */
const val BLOEM_CAPABILITIES_PATH = "/api/bloem/v2/capabilities"

/** Every route only a Bloem server has starts with this. */
const val BLOEM_ROUTE_PREFIX = "/api/bloem/"

private fun organization(id: String) = "$PLATFORM/organizations/${id.encodeURLPathPart()}"

/** Platform pass. One organization with its member, profile and library counts. */
suspend fun ApiClient.getPlatformOrganization(id: String): OrganizationSummary =
    get<PlatformOrganizationResponse>(organization(id)).organization

/** Platform pass. One page of an organization's memberships, with each account's username and email. */
suspend fun ApiClient.listPlatformOrganizationMemberships(id: String, cursor: String?, limit: Int = 50): PlatformMembershipsPage =
    get("${organization(id)}/memberships", mapOf("cursor" to cursor, "limit" to limit.toString()))

/** Platform pass. Suspends the organization; [expectedRevision] is its `policy_revision`. The answer carries the organization without counts. */
suspend fun ApiClient.suspendPlatformOrganization(id: String, expectedRevision: Long): OrganizationSummary =
    request<PlatformOrganizationResponse, ExpectedRevisionBody>(
        "${organization(id)}/suspend", HttpMethod.Post, ExpectedRevisionBody(expectedRevision), replayUnauthorized = false,
    ).organization

/** Platform pass. Reactivates the organization; [expectedRevision] is its `policy_revision`. */
suspend fun ApiClient.reactivatePlatformOrganization(id: String, expectedRevision: Long): OrganizationSummary =
    request<PlatformOrganizationResponse, ExpectedRevisionBody>(
        "${organization(id)}/reactivate", HttpMethod.Post, ExpectedRevisionBody(expectedRevision), replayUnauthorized = false,
    ).organization

/** Platform pass. The plan the organization is on, its limits and its default group for members. */
suspend fun ApiClient.getPlatformOrganizationEntitlement(id: String): OrganizationEntitlement =
    get("${organization(id)}/entitlement")

/** Platform pass. The plans on offer (enabled, not archived), the same list the web offers for an organization. */
suspend fun ApiClient.listOfferedEntitlementTemplates(): List<EntitlementTemplate> =
    get<EntitlementTemplatesResponse>("$PLATFORM/entitlement-templates", mapOf("status" to "enabled")).templates

/** Platform pass. Shows what putting the organization on a plan would change; nothing is applied. */
suspend fun ApiClient.previewOrganizationEntitlement(id: String, templateKey: String, templateRevision: Long): EntitlementDryRun =
    request<EntitlementDryRun, EntitlementSelection>(
        "${organization(id)}/entitlement/dry-run", HttpMethod.Post, EntitlementSelection(templateKey, templateRevision),
    )

/**
 * Platform pass. Puts the organization on the plan. [dryRunToken] is the one the preview of this same plan gave and
 * [idempotencyKey] makes a repeat of this exact request harmless. Sent once.
 */
suspend fun ApiClient.applyOrganizationEntitlement(
    id: String, templateKey: String, templateRevision: Long, dryRunToken: String, idempotencyKey: String,
): EntitlementResult =
    request<EntitlementResult, EntitlementApplyBody>(
        "${organization(id)}/entitlement/apply", HttpMethod.Post,
        EntitlementApplyBody(templateKey, templateRevision, dryRunToken, idempotencyKey), replayUnauthorized = false,
    )

/** No account token or pass: the public capability document, with its feature tokens and the fixed object of booleans. */
suspend fun ApiClient.getPlatformCapabilities(): PlatformCapabilities = get(BLOEM_CAPABILITIES_PATH)
