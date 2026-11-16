package com.bloemstudios.admin.organization

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.organization.OrganizationFixtures as F
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bulk people actions, person detail, access group editing, giving a library back, cohorts and decisions. */
class OrganizationBulkViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = "/api/bloem/v2/admin/organization"

    private class Route(val method: HttpMethod, val path: String, val answers: ArrayDeque<Pair<HttpStatusCode, String>>)

    private val routes = mutableListOf<Route>()
    private val sent = mutableListOf<HttpRequestData>()
    private val sentBodies = mutableListOf<String>()
    private var mints = 0
    private val now = Instant.parse("2026-11-13T12:00:00Z")

    /** Answers [method] [path] with [answers] in order; the last one repeats. */
    private fun on(method: HttpMethod, path: String, vararg answers: Pair<HttpStatusCode, String>) {
        routes.removeAll { it.method == method && it.path == base + path }
        routes += Route(method, base + path, ArrayDeque(answers.toList()))
    }

    private fun ok(body: String) = HttpStatusCode.OK to body
    private fun get(path: String, body: String) = on(HttpMethod.Get, path, ok(body))
    private fun patch(path: String, vararg answers: Pair<HttpStatusCode, String>) = on(HttpMethod.Patch, path, *answers)

    private fun sentTo(method: HttpMethod, path: String) =
        sent.withIndex().filter { it.value.method == method && it.value.url.encodedPath == base + path }

    private suspend fun bodyJson(index: Int) = Json.parseToJsonElement(sentBodies[index]).jsonObject

    private suspend fun opened(): OrganizationSession {
        val account = ApiClient("https://example.test", "account-token", MockEngine {
            mints += 1
            respond(F.session(token = "pass-$mints", policyRevision = 6L + mints, name = "Acme Media"), HttpStatusCode.OK, json)
        })
        val session = OrganizationSession(
            account, F.ORG_ID, clock = { now },
            clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
                ApiClient(origin, snapshot, MockEngine { request ->
                    sent += request
                    sentBodies += String(request.body.toByteArray())
                    val route = routes.lastOrNull { it.method == request.method && it.path == request.url.encodedPath }
                    if (route == null) respond(F.error("not_found"), HttpStatusCode.NotFound, json)
                    else {
                        val (status, body) = if (route.answers.size > 1) route.answers.removeFirst() else route.answers.first()
                        respond(body, status, json)
                    }
                }, recovery)
            },
        )
        session.open()
        return session
    }

    private fun resId(text: ErrorText?) = (text as? ErrorText.Res)?.id

    // Two people with profiles, one suspended, plus the signed-in administrator's own row.
    private val sam get() = F.person(5, "sam@example.test", "Sam", "active", 4)
    private val ana get() = F.person(6, "ana@example.test", "Ana", "suspended", 2, profiles = "")
    private val me get() = F.person(1, "owner@example.test", "Pat Owner", "active", 3, "admin", profiles = "").replace("person-membership-1", F.MEMBERSHIP_ID)

    private fun peopleSetup(vararg people: String) {
        val rows = if (people.isEmpty()) arrayOf(sam, ana, me) else arrayOf(*people)
        get("/groups", F.groups(F.group("3", "Family"), F.group("4", "Kids")))
        get("/people", F.peoplePage(*rows))
    }

    private fun viewModel() = PeopleViewModel().apply { pollDelayMillis = 0; clock = { now } }

    // ---- Sort and recent activity ---------------------------------------------------------------------------

    @Test
    fun `sort and active within days reach the server, and the same instant pages the list`() = runTest {
        get("/groups", F.groups(F.group()))
        get("/people", F.peoplePage(sam, next = "n2", total = 80))
        val session = opened()
        val vm = viewModel()
        vm.load(session, PeopleFilter(sort = PeopleSort.RECENT_ACTIVITY, activeWithinDays = 7))
        val first = sentTo(HttpMethod.Get, "/people").last().value.url.parameters
        assertEquals("recent_activity", first["sort"])
        assertEquals("2026-11-06T12:00:00Z", first["active_since"])

        vm.loadMore(session)
        val second = sentTo(HttpMethod.Get, "/people").last().value.url.parameters
        assertEquals("a cursor remembers its filter, so the instant must not move", first["active_since"], second["active_since"])
        assertEquals("n2", second["cursor"])
    }

    @Test
    fun `changing the filter drops the selection`() = runTest {
        peopleSetup()
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.toggle(5)
        assertEquals(setOf(5L), vm.state.value.pickedIds)
        vm.load(session, PeopleFilter(query = "sam"))
        assertTrue(vm.state.value.pickedIds.isEmpty())
        assertNull(vm.state.value.matching)
    }

    @Test
    fun `reloading the same filter keeps what was ticked`() = runTest {
        peopleSetup()
        val session = opened()
        val vm = viewModel()
        vm.load(session, PeopleFilter(activeWithinDays = 7))
        vm.toggle(5)
        vm.load(session, PeopleFilter(activeWithinDays = 7))
        assertEquals(setOf(5L), vm.state.value.pickedIds)
    }

    // ---- Hand-picked people: the same single-person writes, one after another ----------------------------------------

    @Test
    fun `suspending the picked people changes those who can be, skips the rest with the web's reasons`() = runTest {
        peopleSetup()
        patch("/people/5/memberships/current", ok(F.personResponse(F.person(5, name = "Sam", status = "suspended", revision = 5))))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        listOf(5L, 6L, 1L).forEach(vm::toggle)

        vm.apply(session, BulkAction.Suspend)

        val report = vm.state.value.report!!
        assertEquals(1L, report.job.succeeded)
        assertEquals(
            listOf(6L to "already_applied", 1L to "actor_authority_target"),
            report.job.skippedRecords.map { it.account_id to it.reason },
        )
        assertEquals("completed", report.job.status)
        assertFalse(report.running)
        // Only Sam was written, quoting his own revision.
        val writes = sentTo(HttpMethod.Patch, "/people/5/memberships/current")
        assertEquals(1, writes.size)
        assertEquals("4", bodyJson(writes.single().index).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("suspended", bodyJson(writes.single().index).getValue("status").jsonPrimitive.content)
        assertTrue(sentTo(HttpMethod.Patch, "/people/6/memberships/current").isEmpty())
        assertTrue(sentTo(HttpMethod.Patch, "/people/1/memberships/current").isEmpty())
        // Selection is spent and the list was read again.
        assertTrue(vm.state.value.pickedIds.isEmpty())
        assertFalse(vm.state.value.selecting)
        assertEquals("Sam", report.names[5L])
    }

    @Test
    fun `reactivating skips the active and the invited`() = runTest {
        val invited = F.person(7, "new@example.test", "Newbie", "invited", 1, profiles = "")
        peopleSetup(sam, ana, invited)
        patch("/people/6/memberships/current", ok(F.personResponse(F.person(6, "ana@example.test", "Ana", "active", 3, profiles = ""))))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        listOf(5L, 6L, 7L).forEach(vm::toggle)

        vm.apply(session, BulkAction.Reactivate)

        val job = vm.state.value.report!!.job
        assertEquals(1L, job.succeeded)
        assertEquals(listOf(5L to "already_applied", 7L to "not_suspended"), job.skippedRecords.map { it.account_id to it.reason })
        assertEquals("active", bodyJson(sentTo(HttpMethod.Patch, "/people/6/memberships/current").single().index).getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `moving the picked people moves every profile that is elsewhere, quoting the revision each write returned`() = runTest {
        val twoProfiles = F.profile("p1", "Living room", 3, "Family") + "," + F.profile("p2", "Bedroom", 4, "Kids") + "," + F.profile("p3", "Den", 3, "Family")
        peopleSetup(F.person(5, name = "Sam", revision = 4, profiles = twoProfiles), F.person(6, "ana@example.test", "Ana", "active", 2, profiles = F.profile("p9", "Ana", 4, "Kids")))
        patch("/people/5/profiles/p1", ok(F.personResponse(F.person(5, name = "Sam", revision = 5, profiles = twoProfiles))))
        patch("/people/5/profiles/p3", ok(F.personResponse(F.person(5, name = "Sam", revision = 6, profiles = twoProfiles))))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        listOf(5L, 6L).forEach(vm::toggle)

        vm.apply(session, BulkAction.AssignGroup(vm.state.value.groups.first { it.name == "Kids" }))

        val first = sentTo(HttpMethod.Patch, "/people/5/profiles/p1").single()
        val second = sentTo(HttpMethod.Patch, "/people/5/profiles/p3").single()
        assertEquals("4", bodyJson(first.index).getValue("group_id").jsonPrimitive.content)
        assertEquals("4", bodyJson(first.index).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("the second write quotes what the first returned", "5", bodyJson(second.index).getValue("expected_revision").jsonPrimitive.content)
        assertTrue("a profile already in the group is left alone", sentTo(HttpMethod.Patch, "/people/5/profiles/p2").isEmpty())
        val job = vm.state.value.report!!.job
        assertEquals(1L, job.succeeded)
        assertEquals(listOf(6L to "already_applied"), job.skippedRecords.map { it.account_id to it.reason })
    }

    @Test
    fun `a person without profiles is skipped when moving`() = runTest {
        peopleSetup(ana)
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.toggle(6)
        vm.apply(session, BulkAction.AssignGroup(vm.state.value.groups.first()))
        assertEquals(listOf(6L to "no_profiles"), vm.state.value.report!!.job.skippedRecords.map { it.account_id to it.reason })
    }

    @Test
    fun `the owner, a stale person and a vanished person fail one by one without stopping the rest`() = runTest {
        val owner = F.person(8, "boss@example.test", "Boss", "active", 1, profiles = "")
        val gone = F.person(9, "gone@example.test", "Gone", "active", 1, profiles = "")
        val stale = F.person(10, "stale@example.test", "Stale", "active", 1, profiles = "")
        peopleSetup(owner, gone, stale, sam)
        patch("/people/8/memberships/current", HttpStatusCode.UnprocessableEntity to F.error("validation_failed", "Validation failed"))
        patch("/people/9/memberships/current", HttpStatusCode.NotFound to F.error("not_found"))
        patch("/people/10/memberships/current", HttpStatusCode.Conflict to """{"error":"authorization_state_changed","message":"x","current_revision":9}""")
        patch("/people/5/memberships/current", ok(F.personResponse(F.person(5, name = "Sam", status = "suspended", revision = 5))))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        listOf(8L, 9L, 10L, 5L).forEach(vm::toggle)

        vm.apply(session, BulkAction.Suspend)

        val job = vm.state.value.report!!.job
        assertEquals(1L, job.succeeded)
        assertEquals(
            listOf(8L to "protected_owner", 9L to "not_found", 10L to "authorization_state_changed"),
            job.failedRecords.map { it.account_id to it.reason },
        )
        assertEquals("completed", job.status)
        assertEquals("each write went out once", 1, sentTo(HttpMethod.Patch, "/people/8/memberships/current").size)
        assertEquals(1, sentTo(HttpMethod.Patch, "/people/10/memberships/current").size)
    }

    @Test
    fun `losing the organization halfway stops the run and marks the session denied`() = runTest {
        peopleSetup(sam, F.person(10, "x@example.test", "Xan", "active", 1, profiles = ""))
        patch("/people/5/memberships/current", HttpStatusCode.Forbidden to F.error("organization_suspended", "Organization is suspended"))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        listOf(5L, 10L).forEach(vm::toggle)

        vm.apply(session, BulkAction.Suspend)

        assertEquals(OrganizationDenial.Suspended, session.denial.value)
        assertTrue("the second person was never written", sentTo(HttpMethod.Patch, "/people/10/memberships/current").isEmpty())
        assertEquals(R.string.organization_suspended, resId(vm.state.value.report?.error))
    }

    @Test
    fun `nothing happens without a selection and not while a change runs`() = runTest {
        peopleSetup()
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.apply(session, BulkAction.Suspend)
        assertNull(vm.state.value.report)
        assertTrue(sent.none { it.method == HttpMethod.Patch })
    }

    // ---- Everyone who matches: the server's own job ---------------------------------------------------------------------

    @Test
    fun `select all freezes the filter's matches on the server and shows how many`() = runTest {
        peopleSetup()
        on(HttpMethod.Post, "/people/selections", HttpStatusCode.Created to F.selection(token = "sel-1", matched = 40, excluded = 3))
        val session = opened()
        val vm = viewModel()
        vm.load(session, PeopleFilter(query = "sam", statuses = setOf("active"), groupId = 3))

        vm.selectAllMatching(session)

        val state = vm.state.value
        assertEquals(40L, state.matching?.matched)
        assertEquals(3L, state.matching?.excluded)
        assertEquals(40L, state.selectedCount)
        assertTrue(state.pickedIds.isEmpty())
        val body = bodyJson(sentTo(HttpMethod.Post, "/people/selections").single().index)
        assertEquals("sam", body.getValue("query").jsonPrimitive.content)
        assertFalse("the token never reaches state text", state.toString().contains("sel-1"))
    }

    @Test
    fun `a bulk job is started with the token and followed until it finishes`() = runTest {
        peopleSetup()
        on(HttpMethod.Post, "/people/selections", HttpStatusCode.Created to F.selection(token = "sel-1", matched = 9))
        on(HttpMethod.Post, "/people/bulk-jobs", HttpStatusCode.Created to F.bulkJob("queued", total = 9))
        on(
            HttpMethod.Get, "/people/bulk-jobs/job-1",
            ok(F.bulkJob("running", current = 4, total = 9, succeeded = 4)),
            ok(F.bulkJob("completed", current = 9, total = 9, succeeded = 7, skipped = F.record(8, "protected_owner") + "," + F.record(9, "already_applied"))),
        )
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.selectAllMatching(session)

        vm.apply(session, BulkAction.AssignGroup(vm.state.value.groups.first { it.name == "Kids" }))

        val start = bodyJson(sentTo(HttpMethod.Post, "/people/bulk-jobs").single().index)
        assertEquals("sel-1", start.getValue("selection_token").jsonPrimitive.content)
        assertEquals("assign_group", start.getValue("kind").jsonPrimitive.content)
        assertEquals("4", start.getValue("group_id").jsonPrimitive.content)
        assertEquals("looked at twice: running, then completed", 2, sentTo(HttpMethod.Get, "/people/bulk-jobs/job-1").size)
        val report = vm.state.value.report!!
        assertEquals("completed", report.job.status)
        assertEquals(7L, report.job.succeeded)
        assertEquals(R.string.organization_bulk_some_changed, bulkHeadingRes(report.job))
        assertEquals(listOf(8L, 9L), report.job.skippedRecords.map { it.account_id })
        assertNull("the selection is spent", vm.state.value.matching)
        assertFalse(vm.state.value.selecting)
        assertTrue("the list was read again", sentTo(HttpMethod.Get, "/people").size >= 2)
    }

    @Test
    fun `an expired selection is cleared and the administrator is told to select again`() = runTest {
        peopleSetup()
        on(HttpMethod.Post, "/people/selections", HttpStatusCode.Created to F.selection(token = "old", matched = 9))
        on(HttpMethod.Post, "/people/bulk-jobs", HttpStatusCode.Conflict to F.error("selection_expired", "The immutable selection expired; create a new selection"))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.selectAllMatching(session)

        vm.apply(session, BulkAction.Suspend)

        assertNull(vm.state.value.matching)
        assertEquals(R.string.organization_selection_expired, resId(vm.state.value.error))
        assertNull(vm.state.value.report)
    }

    @Test
    fun `a stale pass on the job start is not replayed and the selection is dropped`() = runTest {
        peopleSetup()
        on(HttpMethod.Post, "/people/selections", HttpStatusCode.Created to F.selection(matched = 9))
        on(HttpMethod.Post, "/people/bulk-jobs", HttpStatusCode.Unauthorized to F.error("authorization_state_stale"))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.selectAllMatching(session)

        vm.apply(session, BulkAction.Suspend)

        assertEquals("the start was sent once", 1, sentTo(HttpMethod.Post, "/people/bulk-jobs").size)
        assertEquals(R.string.organization_selection_expired, resId(vm.state.value.error))
        assertNull(vm.state.value.matching)
    }

    @Test
    fun `a job that cannot be followed keeps what was known and says so`() = runTest {
        peopleSetup()
        on(HttpMethod.Post, "/people/selections", HttpStatusCode.Created to F.selection(matched = 9))
        on(HttpMethod.Post, "/people/bulk-jobs", HttpStatusCode.Created to F.bulkJob("queued", total = 9))
        on(HttpMethod.Get, "/people/bulk-jobs/job-1", HttpStatusCode.ServiceUnavailable to F.error("tenant_unavailable", "Tenant administration is unavailable"))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.selectAllMatching(session)

        vm.apply(session, BulkAction.Reactivate)

        val report = vm.state.value.report!!
        assertEquals("queued", report.job.status)
        assertEquals(ErrorText.Raw("Tenant administration is unavailable"), report.error)
        assertFalse(report.running)
        assertEquals("three looks, then it stops asking", 3, sentTo(HttpMethod.Get, "/people/bulk-jobs/job-1").size)
    }

    @Test
    fun `ticking a person replaces a select-all selection`() = runTest {
        peopleSetup()
        on(HttpMethod.Post, "/people/selections", HttpStatusCode.Created to F.selection(matched = 9))
        val session = opened()
        val vm = viewModel()
        vm.load(session)
        vm.selectAllMatching(session)
        vm.toggle(5)
        assertNull(vm.state.value.matching)
        assertEquals(1L, vm.state.value.selectedCount)
    }

    // ---- One person -------------------------------------------------------------------------------------------------------

    @Test
    fun `a person's detail lists the profiles, the revision and the changes made to their membership`() = runTest {
        get("/people/5", F.personResponse(F.person(5, name = "Sam", revision = 4)))
        get("/groups", F.groups(F.group("3", "Family")))
        get(
            "/activity",
            F.audit(
                """{"id":1,"source":"people","created_at":"2026-11-12T09:30:00Z","actor_account_id":4,"action":"people.membership_updated","target_id":"person-membership-5","outcome":"success"}""",
                """{"id":2,"source":"people","created_at":"2026-11-12T09:00:00Z","actor_account_id":4,"action":"people.membership_updated","target_id":"person-membership-9","outcome":"success"}""",
            ),
        )
        val vm = PersonDetailViewModel()
        vm.load(opened(), 5)
        val state = vm.state.value
        assertEquals("Sam", state.person?.display_name)
        assertEquals(listOf(1L), state.changes.map { it.id })
        assertEquals("looked through two entries", 2, state.changesLooked)
        assertEquals(listOf("Family"), state.groups.map { it.name })
        assertFalse(state.changesUnavailable)
    }

    @Test
    fun `a person the server does not show is reported as gone`() = runTest {
        on(HttpMethod.Get, "/people/77", HttpStatusCode.NotFound to F.error("not_found"))
        val vm = PersonDetailViewModel()
        vm.load(opened(), 77)
        assertTrue(vm.state.value.gone)
        assertNull(vm.state.value.person)
    }

    @Test
    fun `an activity list that fails leaves the person and says the changes are unavailable`() = runTest {
        get("/people/5", F.personResponse(F.person(5, name = "Sam")))
        get("/groups", F.groups())
        on(HttpMethod.Get, "/activity", HttpStatusCode.ServiceUnavailable to F.error("tenant_unavailable"))
        val vm = PersonDetailViewModel()
        vm.load(opened(), 5)
        assertNotNull(vm.state.value.person)
        assertTrue(vm.state.value.changesUnavailable)
    }

    @Test
    fun `suspending from the detail quotes the person's revision, shows the new state and reads the changes again`() = runTest {
        get("/people/5", F.personResponse(F.person(5, name = "Sam", revision = 4)))
        get("/groups", F.groups())
        get("/activity", F.audit())
        patch("/people/5/memberships/current", ok(F.personResponse(F.person(5, name = "Sam", status = "suspended", revision = 5))))
        val session = opened()
        val vm = PersonDetailViewModel()
        vm.load(session, 5)
        vm.setSuspended(session, true)
        assertTrue(vm.state.value.person!!.isSuspended)
        assertEquals(R.string.organization_notice_suspended, resId(vm.state.value.notice))
        assertEquals("4", bodyJson(sentTo(HttpMethod.Patch, "/people/5/memberships/current").single().index).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("activity read on load and after the change", 2, sentTo(HttpMethod.Get, "/activity").size)
    }

    @Test
    fun `the administrator cannot suspend themselves from the detail`() = runTest {
        get("/people/1", F.personResponse(me))
        get("/groups", F.groups())
        get("/activity", F.audit())
        val session = opened()
        val vm = PersonDetailViewModel()
        vm.load(session, 1)
        vm.setSuspended(session, true)
        assertTrue(sent.none { it.method == HttpMethod.Patch })
    }

    // ---- Access groups ------------------------------------------------------------------------------------------------------

    private fun groupsSetup() {
        get("/groups", F.groups(F.group("3", "Family", isDefault = true), F.group("4", "Kids")))
        get("/libraries", F.libraries(F.library(1, "Films"), F.library(2, "Shared", "entitled", F.entitlement("active"))))
    }

    @Test
    fun `groups load with the libraries a group can reach`() = runTest {
        groupsSetup()
        val vm = AccessGroupsViewModel()
        vm.load(opened())
        assertEquals(listOf("Family", "Kids"), vm.state.value.groups.map { it.name })
        assertEquals(listOf(true, true), vm.state.value.libraries.map { it.countsTowardCeiling })
    }

    @Test
    fun `a new group quotes the pass revision, opens in the editor and renews the pass`() = runTest {
        groupsSetup()
        on(HttpMethod.Post, "/groups", HttpStatusCode.Created to F.groupMutation(F.group("9", "Guests", members = 0)))
        val session = opened()
        val vm = AccessGroupsViewModel()
        vm.load(session)

        vm.create(session, "  Guests ")

        val body = bodyJson(sentTo(HttpMethod.Post, "/groups").single().index)
        assertEquals("Guests", body.getValue("name").jsonPrimitive.content)
        assertEquals("7", body.getValue("expected_revision").jsonPrimitive.content)
        assertEquals("9", vm.state.value.createdId)
        assertEquals(R.string.organization_notice_group_created, resId(vm.state.value.notice))
        assertEquals("opened, then renewed after the write", 2, mints)
        assertEquals(8L, session.policyRevision)
        vm.consumeCreated()
        assertNull(vm.state.value.createdId)
    }

    @Test
    fun `a blank group name is refused before anything is sent`() = runTest {
        groupsSetup()
        val session = opened()
        val vm = AccessGroupsViewModel()
        vm.create(session, "   ")
        assertEquals(R.string.organization_error_group_name, resId(vm.state.value.error))
        assertTrue(sent.none { it.method == HttpMethod.Post })
    }

    @Test
    fun `saving a group writes the edited draft and reloads`() = runTest {
        groupsSetup()
        on(HttpMethod.Put, "/groups/4", ok(F.groupMutation(F.group("4", "Kids"))))
        val session = opened()
        val vm = AccessGroupsViewModel()
        vm.load(session)
        val kids = vm.state.value.groups.first { it.id == "4" }
        vm.save(session, kids, AccessGroupDraft.of(kids).copy(name = "Little ones", maxStreams = 1, libraryIds = setOf("1")))
        val body = bodyJson(sentTo(HttpMethod.Put, "/groups/4").single().index)
        assertEquals("Little ones", body.getValue("name").jsonPrimitive.content)
        assertEquals("1", body.getValue("max_streams").jsonPrimitive.content)
        assertEquals("7", body.getValue("expected_revision").jsonPrimitive.content)
        assertEquals(R.string.organization_notice_group_saved, resId(vm.state.value.notice))
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun `a refused group change shows why and reloads what the server holds`() = runTest {
        groupsSetup()
        on(HttpMethod.Put, "/groups/4", HttpStatusCode.UnprocessableEntity to F.error("validation_failed", "Validation failed"))
        val session = opened()
        val vm = AccessGroupsViewModel()
        vm.load(session)
        val kids = vm.state.value.groups.first { it.id == "4" }
        vm.save(session, kids, AccessGroupDraft.of(kids))
        assertEquals(R.string.organization_error_group_refused, resId(vm.state.value.error))
        assertNull(vm.state.value.notice)
        assertEquals("the list was read again", 2, sentTo(HttpMethod.Get, "/groups").size)
    }

    @Test
    fun `deleting a group reports how many profiles moved and a managed group's refusal is shown in the server's words`() = runTest {
        groupsSetup()
        on(HttpMethod.Delete, "/groups/4", ok(F.deletionImpact(reassigned = 4, defaultGroup = 3)))
        val session = opened()
        val vm = AccessGroupsViewModel()
        vm.load(session)
        val kids = vm.state.value.groups.first { it.id == "4" }
        assertTrue(vm.delete(session, kids))
        assertEquals(4L, vm.state.value.lastDeletion?.profiles_reassigned)
        assertEquals(R.string.organization_notice_group_deleted, resId(vm.state.value.notice))
        assertEquals("7", bodyJson(sentTo(HttpMethod.Delete, "/groups/4").single().index).getValue("expected_revision").jsonPrimitive.content)

        on(HttpMethod.Delete, "/groups/3", HttpStatusCode.Conflict to F.error("conflict", "This group is managed by entitlement policy and can only be changed there"))
        assertFalse(vm.delete(session, vm.state.value.groups.first { it.id == "3" }))
        assertEquals(ErrorText.Raw("This group is managed by entitlement policy and can only be changed there"), vm.state.value.error)
    }

    // ---- Libraries ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `giving a shared library back deletes its entitlement with the revision and reads the list again`() = runTest {
        get("/libraries", F.libraries(F.library(1, "Films"), F.library(2, "Shared", "entitled", F.entitlement("active", 9))))
        on(HttpMethod.Delete, "/entitlements/2", HttpStatusCode.NoContent to "")
        val session = opened()
        val vm = LibrariesViewModel()
        vm.load(session)
        get("/libraries", F.libraries(F.library(1, "Films"), F.library(2, "Shared", "entitled", F.entitlement("revoked", 10))))

        vm.giveBack(session, vm.state.value.libraries.first { it.folder_id == 2L })

        assertEquals("9", bodyJson(sentTo(HttpMethod.Delete, "/entitlements/2").single().index).getValue("expected_revision").jsonPrimitive.content)
        assertEquals("revoked", vm.state.value.libraries.first { it.folder_id == 2L }.entitlement?.status)
        assertFalse(vm.state.value.libraries.first { it.folder_id == 2L }.countsTowardCeiling)
        assertEquals(R.string.organization_notice_library_given_back, resId(vm.state.value.notice))
    }

    @Test
    fun `an owned library and a withdrawn one cannot be given back`() = runTest {
        get("/libraries", F.libraries(F.library(1, "Films"), F.library(2, "Gone", "entitled", F.entitlement("revoked"))))
        val session = opened()
        val vm = LibrariesViewModel()
        vm.load(session)
        vm.state.value.libraries.forEach { vm.giveBack(session, it) }
        assertTrue(sent.none { it.method == HttpMethod.Delete })
    }

    @Test
    fun `a stale give-back is not replayed and says someone else changed it`() = runTest {
        get("/libraries", F.libraries(F.library(2, "Shared", "entitled", F.entitlement("active", 9))))
        on(HttpMethod.Delete, "/entitlements/2", HttpStatusCode.Conflict to """{"error":"authorization_state_changed","message":"x","current_revision":10}""")
        val session = opened()
        val vm = LibrariesViewModel()
        vm.load(session)
        vm.giveBack(session, vm.state.value.libraries.single())
        assertEquals(1, sentTo(HttpMethod.Delete, "/entitlements/2").size)
        assertEquals(R.string.organization_changed_conflict, resId(vm.state.value.error))
    }

    @Test
    fun `a paused library counts toward nothing until it is restored`() {
        val owned = OrganizationLibrary(1, "Films", "movie", "owned")
        val paused = OrganizationLibrary(2, "Shared", "movie", "entitled", LibraryEntitlement("e", "o", "suspended", 3))
        val active = paused.copy(entitlement = paused.entitlement?.copy(status = "active"))
        assertTrue(owned.countsTowardCeiling)
        assertFalse(paused.countsTowardCeiling)
        assertTrue(active.countsTowardCeiling)
    }

    // ---- Cohorts and decisions ---------------------------------------------------------------------------------------------------

    @Test
    fun `cohorts load with library names so a policy reads as names`() = runTest {
        get("/entitlement-cohorts", F.cohorts(F.cohort()))
        get("/libraries", F.libraries(F.library(1, "Films")))
        val vm = CohortsViewModel()
        vm.load(opened())
        assertEquals(listOf("Films"), vm.state.value.libraries.map { it.name })
        assertEquals("Family plan", vm.state.value.cohorts.single().name)
    }

    @Test
    fun `opening a decision reads its detail and closing forgets it`() = runTest {
        get("/policy-decisions", F.decisions(F.decision(4)))
        get("/libraries", F.libraries(F.library(1, "Films")))
        get("/policy-decisions/4", F.decisionDetail(4, allowed = false))
        val session = opened()
        val vm = DecisionsViewModel()
        vm.load(session)
        vm.open(session, 4)
        assertEquals("library_not_in_ceiling", vm.state.value.detail?.reason_code)
        assertFalse(vm.state.value.detailLoading)
        vm.close()
        assertNull(vm.state.value.detail)
        assertNull(vm.state.value.selectedId)
    }

    @Test
    fun `a decision that fails to load keeps the list and shows the failure`() = runTest {
        get("/policy-decisions", F.decisions(F.decision(4)))
        get("/libraries", F.libraries())
        on(HttpMethod.Get, "/policy-decisions/4", HttpStatusCode.NotFound to F.error("not_found"))
        val session = opened()
        val vm = DecisionsViewModel()
        vm.load(session)
        vm.open(session, 4)
        assertEquals(1, vm.state.value.decisions.size)
        assertNotNull(vm.state.value.detailError)
        assertNull(vm.state.value.detail)
    }
}
