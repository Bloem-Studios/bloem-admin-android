package com.bloemstudios.admin.organization

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.api.createOrganizationGroup
import com.bloemstudios.admin.network.api.createOrganizationSelection
import com.bloemstudios.admin.network.api.deleteOrganizationEntitlement
import com.bloemstudios.admin.network.api.deleteOrganizationGroup
import com.bloemstudios.admin.network.api.getOrganizationBulkJob
import com.bloemstudios.admin.network.api.getOrganizationPerson
import com.bloemstudios.admin.network.api.getOrganizationPolicyDecision
import com.bloemstudios.admin.network.api.listOrganizationCohorts
import com.bloemstudios.admin.network.api.listOrganizationPeople
import com.bloemstudios.admin.network.api.startOrganizationBulkJob
import com.bloemstudios.admin.network.api.updateOrganizationGroup
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Decoding and request shapes for the routes behind bulk people actions, group editing and giving a library back. */
class OrganizationBulkApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String): ApiClient =
        ApiClient("https://example.test", F.PASS, MockEngine { request ->
            seen += request
            respond(body, status, json)
        })

    private suspend fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject

    // ---- People: sort, active since, one person -----------------------------------------------------

    @Test
    fun `people send the sort and the active-since instant`() = runTest {
        client(body = F.peoplePage()).listOrganizationPeople(
            PeopleFilter(sort = PeopleSort.RECENT_ACTIVITY, activeSince = "2026-11-06T09:00:00Z"), cursor = null,
        )
        val params = seen.single().url.parameters
        assertEquals("recent_activity", params["sort"])
        assertEquals("2026-11-06T09:00:00Z", params["active_since"])
    }

    @Test
    fun `one person is read from the person route and decodes profiles and revision`() = runTest {
        val person = client(body = F.personResponse(F.person(5, revision = 8))).getOrganizationPerson(5)
        assertEquals("/api/bloem/v2/admin/organization/people/5", seen.single().url.encodedPath)
        assertEquals(HttpMethod.Get, seen.single().method)
        assertEquals(8L, person.security_revision)
        assertEquals("Family", person.profiles.single().group_name)
        assertEquals("person-membership-5", person.membership_id)
    }

    // ---- Selections and bulk jobs ---------------------------------------------------------------------

    @Test
    fun `a selection posts the filter the way the web does and hides its token`() = runTest {
        val selection = client(HttpStatusCode.Created, F.selection(token = "secret-selection", matched = 9, excluded = 2))
            .createOrganizationSelection(PeopleFilter(query = " sam ", statuses = setOf("suspended", "active"), groupId = 3, sort = null, activeSince = "2026-11-06T00:00:00Z"))
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/bloem/v2/admin/organization/people/selections", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals(setOf("query", "status", "group_ids", "sort", "active_since"), body.keys)
        assertEquals("sam", body.getValue("query").jsonPrimitive.content)
        assertEquals(listOf("active", "suspended"), body.getValue("status").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("3"), body.getValue("group_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("name", body.getValue("sort").jsonPrimitive.content)
        assertEquals(9L, selection.matched)
        assertEquals(2L, selection.excluded)
        assertEquals("secret-selection", selection.token)
        assertFalse(selection.toString().contains("secret-selection"))
    }

    @Test
    fun `an empty filter still posts empty lists and no active-since`() = runTest {
        client(HttpStatusCode.Created, F.selection()).createOrganizationSelection(PeopleFilter())
        val body = bodyOf(seen.single())
        assertEquals(JsonArray(emptyList()), body.getValue("status"))
        assertEquals(JsonArray(emptyList()), body.getValue("group_ids"))
        assertFalse(body.containsKey("active_since"))
    }

    @Test
    fun `a bulk job is started once with the selection token, the kind and the group`() = runTest {
        val job = client(HttpStatusCode.Created, F.bulkJob(status = "queued", total = 9))
            .startOrganizationBulkJob("sel-token", BulkKind.ASSIGN_GROUP, groupId = 4)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/bloem/v2/admin/organization/people/bulk-jobs", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals("sel-token", body.getValue("selection_token").jsonPrimitive.content)
        assertEquals("assign_group", body.getValue("kind").jsonPrimitive.content)
        assertEquals("4", body.getValue("group_id").jsonPrimitive.content)
        assertEquals("job-1", job.job_id)
        assertFalse(job.isFinished)
        assertEquals(9L, job.progress_total)
    }

    @Test
    fun `suspend and reactivate jobs carry no group`() = runTest {
        client(HttpStatusCode.Created, F.bulkJob()).startOrganizationBulkJob("sel-token", BulkKind.SUSPEND)
        assertEquals(setOf("selection_token", "kind"), bodyOf(seen.single()).keys)
    }

    @Test
    fun `a finished job lists who was skipped and who failed with the server's reasons`() = runTest {
        val job = client(
            body = F.bulkJob(
                status = "completed", current = 9, total = 9, succeeded = 6,
                skipped = F.record(7, "already_applied") + "," + F.record(8, "protected_owner"), failed = F.record(9, "mutation_failed"),
            )
        ).getOrganizationBulkJob("job-1")
        assertEquals("/api/bloem/v2/admin/organization/people/bulk-jobs/job-1", seen.single().url.encodedPath)
        assertTrue(job.isFinished)
        assertEquals(6L, job.succeeded)
        assertEquals(listOf(7L to "already_applied", 8L to "protected_owner"), job.skippedRecords.map { it.account_id to it.reason })
        assertEquals("mutation_failed", job.failedRecords.single().reason)
    }

    @Test
    fun `a job whose lists are null still decodes`() = runTest {
        val job = client(body = """{"job":{"job_id":"j","status":"running","progress_current":1,"progress_total":3,"succeeded":1,"skipped":null,"failed":null}}""")
            .getOrganizationBulkJob("j")
        assertTrue(job.skippedRecords.isEmpty())
        assertTrue(job.failedRecords.isEmpty())
        assertFalse(job.isFinished)
    }

    @Test
    fun `an expired selection is a 409 the caller can read`() = runTest {
        val failure = try {
            client(HttpStatusCode.Conflict, F.error("selection_expired", "The immutable selection expired; create a new selection"))
                .startOrganizationBulkJob("old", BulkKind.SUSPEND)
            fail("expected a failure")
            return@runTest
        } catch (error: ApiError) {
            error
        }
        assertEquals(409, failure.statusCode)
        assertEquals("selection_expired", failure.code)
    }

    // ---- Access groups --------------------------------------------------------------------------------

    @Test
    fun `a new group is a POST with a name and the policy revision only`() = runTest {
        val group = client(HttpStatusCode.Created, F.groupMutation(F.group("9", "Guests", members = 0)))
            .createOrganizationGroup("  Guests ", revision = 7)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/bloem/v2/admin/organization/groups", request.url.encodedPath.trimEnd('/'))
        assertEquals(setOf("name", "expected_revision"), bodyOf(request).keys)
        assertEquals("Guests", bodyOf(request).getValue("name").jsonPrimitive.content)
        assertEquals("7", bodyOf(request).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("9", group.id)
    }

    private fun draft(group: AccessGroup = AccessGroup(
        id = "3", name = "Family", description = "Home", library_ids = listOf("2", "10", "1"), max_playback_quality = "1080p",
        download_allowed = true, download_transcode_allowed = true, transcode_allowed = true, audio_transcode_allowed = false,
        max_streams = 2, max_transcodes = 1, max_remote_stream_bitrate_kbps = 8000, max_local_stream_bitrate_kbps = 0,
        max_profiles = 5, allowed_permissions = null, requests_allowed = true, is_default = false,
    )) = AccessGroupDraft.of(group)

    @Test
    fun `saving a group writes every field, library ids as strings, and a null for all libraries`() = runTest {
        val edited = draft().copy(libraryIds = null, name = " Family ")
        client(body = F.groupMutation()).updateOrganizationGroup(3, edited.toBody(revision = 7))
        val request = seen.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/bloem/v2/admin/organization/groups/3", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals(JsonNull, body.getValue("library_ids"))
        assertEquals(JsonNull, body.getValue("allowed_permissions"))
        assertEquals("Family", body.getValue("name").jsonPrimitive.content)
        assertEquals("7", body.getValue("expected_revision").jsonPrimitive.content)
        // What the editor does not show is written back as loaded.
        assertEquals("8000", body.getValue("max_remote_stream_bitrate_kbps").jsonPrimitive.content)
        assertEquals("5", body.getValue("max_profiles").jsonPrimitive.content)
    }

    @Test
    fun `selected libraries and permissions are sorted and keep their string ids`() = runTest {
        val edited = draft().copy(allowedPermissions = setOf(GroupPermissions.MARKERS, GroupPermissions.LIVE_TV))
        client(body = F.groupMutation()).updateOrganizationGroup(3, edited.toBody(revision = 7))
        val body = bodyOf(seen.single())
        assertEquals(listOf("1", "2", "10"), body.getValue("library_ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("marker_edit", "watch_live_tv"), body.getValue("allowed_permissions").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `converted downloads are never saved without downloads`() {
        val body = draft().copy(downloadAllowed = false, downloadTranscodeAllowed = true).toBody(7)
        assertFalse(body.download_allowed)
        assertFalse(body.download_transcode_allowed)
    }

    @Test
    fun `deleting a group sends the revision in the body and reads the impact`() = runTest {
        val impact = client(body = F.deletionImpact(reassigned = 4, defaultGroup = 3)).deleteOrganizationGroup(9, revision = 7)
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/api/bloem/v2/admin/organization/groups/9", request.url.encodedPath)
        assertEquals(setOf("expected_revision"), bodyOf(request).keys)
        assertEquals(4L, impact.profiles_reassigned)
        assertEquals(3L, impact.default_group_id)
    }

    @Test
    fun `a managed or default group refusal is a 409 conflict`() = runTest {
        val failure = try {
            client(HttpStatusCode.Conflict, F.error("conflict", "This group is managed by entitlement policy and can only be changed there"))
                .deleteOrganizationGroup(9, 7)
            fail("expected a failure")
            return@runTest
        } catch (error: ApiError) {
            error
        }
        assertEquals(409, failure.statusCode)
        assertEquals("conflict", failure.code)
    }

    // ---- Giving a library back --------------------------------------------------------------------------

    @Test
    fun `giving a library back is a DELETE with the entitlement revision and a 204`() = runTest {
        client(HttpStatusCode.NoContent, "").deleteOrganizationEntitlement(folderId = 2, revision = 9)
        val request = seen.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/api/bloem/v2/admin/organization/entitlements/2", request.url.encodedPath)
        assertEquals("9", bodyOf(request).getValue("expected_revision").jsonPrimitive.content)
    }

    // ---- Cohorts and decisions at the web's detail level ---------------------------------------------------

    @Test
    fun `a cohort decodes how it was made, its parent, its maker and its fingerprint`() = runTest {
        val cohort = client(body = F.cohorts(F.cohort())).listOrganizationCohorts(includeArchived = false).single()
        assertEquals("exact_template", cohort.derivation_kind)
        assertEquals("c0", cohort.parent_cohort_id)
        assertEquals(4L, cohort.created_by_account_id)
        assertEquals("d", cohort.policy_digest)
        assertNull(cohort.policy.audio_transcode_allowed)
    }

    @Test
    fun `a decision detail decodes the ceiling, the redacted resource and the rules checked`() = runTest {
        val decision = client(body = F.decisionDetail(allowed = false)).getOrganizationPolicyDecision(4)
        assertEquals("/api/bloem/v2/admin/organization/policy-decisions/4", seen.single().url.encodedPath)
        assertFalse(decision.allowed)
        assertEquals("library_not_in_ceiling", decision.reason_code)
        assertEquals(listOf(1L, 2L), decision.library_ceiling)
        assertEquals("[redacted]", decision.resource.getValue("token").jsonPrimitive.content)
        assertEquals(F.MEMBERSHIP_ID, decision.organization.membership_id)
        assertEquals(listOf("vendor" to 4L, "custom" to 2L), decision.policy_versions?.map { it.kind to it.version })
        assertEquals("Kids rule", decision.policy_versions?.last()?.name)
    }
}
