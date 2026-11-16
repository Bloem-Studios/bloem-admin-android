package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.AccessGroup
import com.bloemstudios.admin.organization.AccessGroupCreateBody
import com.bloemstudios.admin.organization.AccessGroupWrite
import com.bloemstudios.admin.organization.BulkJob
import com.bloemstudios.admin.organization.BulkJobRequest
import com.bloemstudios.admin.organization.BulkJobResponse
import com.bloemstudios.admin.organization.GroupDeletionImpact
import com.bloemstudios.admin.organization.OrganizationGroupMutationResponse
import com.bloemstudios.admin.organization.PeopleFilter
import com.bloemstudios.admin.organization.PeopleSelection
import com.bloemstudios.admin.organization.PeopleSelectionRequest
import com.bloemstudios.admin.organization.PeopleSelectionResponse
import com.bloemstudios.admin.organization.PeopleSort
import com.bloemstudios.admin.organization.PersonResponse
import com.bloemstudios.admin.organization.PersonSummary
import com.bloemstudios.admin.organization.PolicyDecisionDetail
import com.bloemstudios.admin.organization.PolicyDecisionDetailResponse
import com.bloemstudios.admin.organization.RevisionBody
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart

/*
 * The organization routes that read one person, change several people, edit access groups and give a
 * library back. Same rules as OrganizationApi.kt: the bearer is the context pass, the organization is
 * never named in a path, and every write is sent once (replayUnauthorized = false): a pass the server
 * refused is renewed by OrganizationSession, and the administrator decides whether to ask again.
 */

private const val ORGANIZATION = "/api/bloem/v2/admin/organization"

/** Context pass. One person, with the profiles and the revision a write must quote. */
suspend fun ApiClient.getOrganizationPerson(accountId: Long): PersonSummary =
    get<PersonResponse>("$ORGANIZATION/people/$accountId").person

/**
 * Context pass. Freezes everyone the filter matches into a selection the server remembers for a while.
 * It changes nothing, so a renewed pass may ask again. The server takes the filter without a limit or cursor.
 */
suspend fun ApiClient.createOrganizationSelection(filter: PeopleFilter): PeopleSelection =
    post<PeopleSelectionResponse, PeopleSelectionRequest>(
        "$ORGANIZATION/people/selections",
        PeopleSelectionRequest(
            query = filter.query.trim(),
            status = filter.statuses.sorted(),
            group_ids = listOfNotNull(filter.groupId),
            sort = filter.sort ?: PeopleSort.NAME,
            active_since = filter.activeSince,
        )
    ).selection

/**
 * Context pass. Starts a bulk change over a selection: `assign_group`, `suspend_memberships` or
 * `reactivate_memberships`. Sent once: a lost answer is not retried, because the job may exist.
 */
suspend fun ApiClient.startOrganizationBulkJob(selectionToken: String, kind: String, groupId: Long? = null): BulkJob =
    postOnce<BulkJobResponse, BulkJobRequest>(
        "$ORGANIZATION/people/bulk-jobs", BulkJobRequest(selectionToken, kind, groupId)
    ).job

/** Context pass. A job's progress and what it skipped or could not change. */
suspend fun ApiClient.getOrganizationBulkJob(jobId: String): BulkJob =
    get<BulkJobResponse>("$ORGANIZATION/people/bulk-jobs/${jobId.encodeURLPathPart()}").job

/** Context pass. A new group with the server's defaults; [revision] is the organization policy revision. */
suspend fun ApiClient.createOrganizationGroup(name: String, revision: Long): AccessGroup =
    postOnce<OrganizationGroupMutationResponse, AccessGroupCreateBody>(
        "$ORGANIZATION/groups", AccessGroupCreateBody(revision, name.trim())
    ).group

/** Context pass. Saves a group; [body] carries the organization policy revision. */
suspend fun ApiClient.updateOrganizationGroup(id: Long, body: AccessGroupWrite): AccessGroup =
    request<OrganizationGroupMutationResponse, AccessGroupWrite>(
        "$ORGANIZATION/groups/$id", HttpMethod.Put, body, replayUnauthorized = false
    ).group

/** Context pass. Deletes a group; its profiles move to the default group. DELETE carries the revision in a body. */
suspend fun ApiClient.deleteOrganizationGroup(id: Long, revision: Long): GroupDeletionImpact =
    request<GroupDeletionImpact, RevisionBody>(
        "$ORGANIZATION/groups/$id", HttpMethod.Delete, RevisionBody(revision), replayUnauthorized = false
    )

/** Context pass. Gives a shared library back for good; [revision] is the entitlement's `security_revision`. The answer is 204. */
suspend fun ApiClient.deleteOrganizationEntitlement(folderId: Long, revision: Long) {
    request<Unit, RevisionBody>(
        "$ORGANIZATION/entitlements/$folderId", HttpMethod.Delete, RevisionBody(revision), replayUnauthorized = false
    )
}

/** Context pass. One policy check with the rules the server applied. */
suspend fun ApiClient.getOrganizationPolicyDecision(id: Long): PolicyDecisionDetail =
    get<PolicyDecisionDetailResponse>("$ORGANIZATION/policy-decisions/$id").decision
