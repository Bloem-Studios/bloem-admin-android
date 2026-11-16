package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.AccessGroup
import com.bloemstudios.admin.organization.AccessGroupsResponse
import com.bloemstudios.admin.organization.AdminSessionRequest
import com.bloemstudios.admin.organization.AdminSessionResponse
import com.bloemstudios.admin.organization.CreateOrganizationInvitation
import com.bloemstudios.admin.organization.EntitlementCohort
import com.bloemstudios.admin.organization.EntitlementCohortsResponse
import com.bloemstudios.admin.organization.EntitlementStatusUpdate
import com.bloemstudios.admin.organization.InvitationHandoff
import com.bloemstudios.admin.organization.LibraryEntitlement
import com.bloemstudios.admin.organization.MembershipStatusUpdate
import com.bloemstudios.admin.organization.OrganizationAuditPage
import com.bloemstudios.admin.organization.OrganizationEntitlementResponse
import com.bloemstudios.admin.organization.OrganizationInvitation
import com.bloemstudios.admin.organization.OrganizationInvitationsResponse
import com.bloemstudios.admin.organization.OrganizationLibrariesResponse
import com.bloemstudios.admin.organization.OrganizationLibrary
import com.bloemstudios.admin.organization.OrganizationMembership
import com.bloemstudios.admin.organization.OrganizationMembershipList
import com.bloemstudios.admin.organization.OrganizationOverviewResponse
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.organization.PeopleFilter
import com.bloemstudios.admin.organization.PeoplePage
import com.bloemstudios.admin.organization.PersonResponse
import com.bloemstudios.admin.organization.PersonSummary
import com.bloemstudios.admin.organization.PolicyDecisionsPage
import com.bloemstudios.admin.organization.ProfileGroupUpdate
import com.bloemstudios.admin.organization.RevisionBody
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart

/*
 * Administrative context and organization routes (Bloem v2).
 *
 * Two kinds of bearer, never mixed:
 *  - the ACCOUNT token (the normal sign-in) is for [listOrganizationMemberships] and [mintAdminSession] only;
 *  - the CONTEXT PASS that mintAdminSession returns is the bearer for every /admin/organization route.
 * OrganizationSession (organization package) owns both clients; screens receive only the pass client.
 *
 * Organization id never appears in these paths: the server reads it from the pass.
 */

private const val ORGANIZATION = "/api/bloem/v2/admin/organization"

/** Account token. The account's own active memberships, with their roles. */
suspend fun ApiClient.listOrganizationMemberships(): List<OrganizationMembership> =
    get<OrganizationMembershipList>("/api/bloem/v2/organizations").organizations

/** Account token. Exchanges the login session for a short-lived context pass. */
suspend fun ApiClient.mintAdminSession(body: AdminSessionRequest): AdminSessionResponse =
    post("/api/bloem/v2/admin/session", body)

/** Context pass. Counts, owner and the policy revision. */
suspend fun ApiClient.getOrganizationOverview(): OrganizationSummary =
    get<OrganizationOverviewResponse>("$ORGANIZATION/overview").organization

/** Context pass. One page of people; [cursor] is the previous page's `next_cursor`. */
suspend fun ApiClient.listOrganizationPeople(filter: PeopleFilter, cursor: String?, limit: Int = 50): PeoplePage =
    get(
        "$ORGANIZATION/people",
        mapOf(
            "query" to filter.query.trim().ifEmpty { null },
            "status" to filter.statuses.sorted().joinToString(",").ifEmpty { null },
            "group_id" to filter.groupId?.toString(),
            "sort" to filter.sort,
            "active_since" to filter.activeSince,
            "cursor" to cursor,
            "limit" to limit.toString(),
        )
    )

/** Context pass. Suspends or reactivates a membership; [revision] is the person's `security_revision`. */
suspend fun ApiClient.setOrganizationMembershipStatus(accountId: Long, status: String, revision: Long): PersonSummary =
    request<PersonResponse, MembershipStatusUpdate>(
        "$ORGANIZATION/people/$accountId/memberships/current", HttpMethod.Patch,
        MembershipStatusUpdate(revision, status), replayUnauthorized = false
    ).person

/** Context pass. Moves one profile to an access group; [revision] is the person's `security_revision`. */
suspend fun ApiClient.setOrganizationProfileGroup(accountId: Long, profileId: String, groupId: Long, revision: Long): PersonSummary =
    request<PersonResponse, ProfileGroupUpdate>(
        "$ORGANIZATION/people/$accountId/profiles/${profileId.encodeURLPathPart()}", HttpMethod.Patch,
        ProfileGroupUpdate(revision, groupId), replayUnauthorized = false
    ).person

/** Context pass. Every access group with its member count. */
suspend fun ApiClient.listOrganizationGroups(): List<AccessGroup> =
    get<AccessGroupsResponse>("$ORGANIZATION/groups").groups

/** Context pass. Libraries the organization owns or is entitled to. */
suspend fun ApiClient.listOrganizationLibraries(): List<OrganizationLibrary> =
    get<OrganizationLibrariesResponse>("$ORGANIZATION/libraries").libraries

/** Context pass. Pauses (`suspended`) or restores (`active`); [revision] is the entitlement's `security_revision`. */
suspend fun ApiClient.setOrganizationEntitlementStatus(folderId: Long, status: String, revision: Long): LibraryEntitlement =
    request<OrganizationEntitlementResponse, EntitlementStatusUpdate>(
        "$ORGANIZATION/entitlements/$folderId", HttpMethod.Put,
        EntitlementStatusUpdate(revision, status), replayUnauthorized = false
    ).entitlement

/** Context pass. Newest first. */
suspend fun ApiClient.listOrganizationInvitations(): List<OrganizationInvitation> =
    get<OrganizationInvitationsResponse>("$ORGANIZATION/invitations").invitations

/** Context pass. One submission: the answer holds the only copy of the claim token. */
suspend fun ApiClient.createOrganizationInvitation(body: CreateOrganizationInvitation): InvitationHandoff =
    postOnce("$ORGANIZATION/invitations", body)

/** Context pass. Replaces the claim token. One submission, for the same reason as create. */
suspend fun ApiClient.resendOrganizationInvitation(id: String, revision: Long): InvitationHandoff =
    postOnce("$ORGANIZATION/invitations/${id.encodeURLPathPart()}/resend", RevisionBody(revision))

/** Context pass. DELETE carries the policy revision in a JSON body; the answer is 204. */
suspend fun ApiClient.revokeOrganizationInvitation(id: String, revision: Long) {
    request<Unit, RevisionBody>(
        "$ORGANIZATION/invitations/${id.encodeURLPathPart()}", HttpMethod.Delete,
        RevisionBody(revision), replayUnauthorized = false
    )
}

/** Context pass. One page of changes to the organization, newest first. */
suspend fun ApiClient.listOrganizationActivity(cursor: String?): OrganizationAuditPage =
    get("$ORGANIZATION/activity", mapOf("cursor" to cursor))

/** Context pass. Read-only. */
suspend fun ApiClient.listOrganizationCohorts(includeArchived: Boolean): List<EntitlementCohort> =
    get<EntitlementCohortsResponse>(
        "$ORGANIZATION/entitlement-cohorts",
        if (includeArchived) mapOf("include_archived" to "true") else emptyMap()
    ).cohorts

/** Context pass. Read-only; newest first. */
suspend fun ApiClient.listOrganizationPolicyDecisions(cursor: String?, limit: Int = 50): PolicyDecisionsPage =
    get("$ORGANIZATION/policy-decisions", mapOf("cursor" to cursor, "limit" to limit.toString()))
