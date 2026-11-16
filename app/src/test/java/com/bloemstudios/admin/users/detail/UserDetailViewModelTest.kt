package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.AggregateUsersViewModel
import com.bloemstudios.admin.users.PolicyOverrideChange
import com.bloemstudios.admin.users.UpdateUserQuotasRequest
import com.bloemstudios.admin.users.UpdateUserResult
import com.bloemstudios.admin.users.UsersTestServer
import com.bloemstudios.admin.users.UsersViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDetailViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")

    private fun single(server: UsersTestServer, list: UsersViewModel = UsersViewModel()): Pair<UserDetailViewModel, UsersViewModel> {
        val client: ApiClient = server.client()
        return UserDetailViewModel(SingleServerUserAdmin(list, client), "1") to list
    }

    private fun ready(vm: UserDetailViewModel) = vm.state.value as UserPageState.Ready

    @Test
    fun `opening reads the account, who is looking and what the server supports`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = "\"open-1\"", meId = "7")
        val (vm, _) = single(server)

        vm.open()

        assertEquals("alice", ready(vm).user.username)
        assertEquals("\"open-1\"", ready(vm).validator)
        assertEquals("7", vm.viewer.value?.id)
        assertEquals(true, vm.capabilities.value?.login_sessions)
        assertEquals(
            listOf("GET /api/v2/admin/users/1", "GET /api/v2/account/me", "GET /api/v2/admin/users/7", "GET /api/v2/admin/users/capabilities"),
            server.calls.map { it.method.value + " " + it.path }
        )
    }

    @Test
    fun `a viewer reading their own page is not read twice`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), meId = "1")
        val (vm, _) = single(server)

        vm.open()

        assertEquals(1, server.calls.count { it.path == "/api/v2/admin/users/1" })
    }

    @Test
    fun `no viewer means no actions are offered`() = runTest {
        val client = ApiClient("https://example.test", "tok", MockEngine { request ->
            if (request.url.encodedPath == "/api/v2/account/me")
                respond(V2Fixtures.problem(500, "boom", "boom"), HttpStatusCode.InternalServerError,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"))
            else respond(V2Fixtures.adminUser(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = UserDetailViewModel(SingleServerUserAdmin(UsersViewModel(), client), "1")

        vm.open()

        assertNull(vm.viewer.value)
        assertFalse(userActions(ready(vm).user, vm.viewer.value).canDelete)
    }

    @Test
    fun `a deleted account reads as gone`() = runTest {
        val client = ApiClient("https://example.test", "tok", MockEngine {
            respond(V2Fixtures.problem(404, "not_found", "no such user"), HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/problem+json"))
        })
        val vm = UserDetailViewModel(SingleServerUserAdmin(UsersViewModel(), client), "1")

        vm.open()

        assertEquals(UserPageState.Gone, vm.state.value)
    }

    @Test
    fun `a failed read shows the server's message and a failed re-read keeps the account`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")))
        val (vm, _) = single(server)
        vm.open()
        val before = ready(vm)

        server.users = emptyList() // the next GET would throw on first()
        runCatching { vm.reload() }

        assertEquals(before.user.username, (vm.state.value as UserPageState.Ready).user.username)
    }

    @Test
    fun `disable sends If-Match from a fresh read, then reads the account again`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = "\"fresh-7\"")
        val (vm, list) = single(server)
        vm.open()
        server.calls.clear()

        val result = vm.setEnabled(false)

        assertEquals(ActionResult.Success, result)
        val write = server.writes.single()
        assertEquals("\"fresh-7\"", write.ifMatch)
        assertEquals("""{"enabled":false}""", write.body)
        assertEquals(
            listOf("GET /api/v2/admin/users/1", "PUT /api/v2/admin/users/1", "GET /api/v2/admin/users", "GET /api/v2/admin/users/1"),
            server.calls.map { it.method.value + " " + it.path }
        )
        assertEquals(1, list.users.value.size)
    }

    @Test
    fun `a 412 on disable shows the conflict and does not retry`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice")), writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )
        val (vm, list) = single(server)
        vm.open()

        val result = vm.setEnabled(false)

        assertEquals(ActionResult.Failure, result)
        assertEquals(1, server.writes.size)
        assertEquals(ErrorText.Res(R.string.users_conflict), list.actionError.value)
    }

    @Test
    fun `delete sends If-Match and a missing ETag refuses it`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = "\"fresh-9\"")
        val (vm, _) = single(server)
        vm.open()

        assertEquals(ActionResult.Success, vm.delete())
        assertEquals("\"fresh-9\"", server.writes.single().ifMatch)

        val noEtag = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = null)
        val (vm2, list2) = single(noEtag)
        vm2.open()
        assertEquals(ActionResult.Failure, vm2.delete())
        assertEquals(ErrorText.Res(R.string.users_missing_version), list2.actionError.value)
        assertTrue(noEtag.writes.isEmpty())
    }

    @Test
    fun `a page opened without an ETag has nothing to save against`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = null)
        val (vm, _) = single(server)
        vm.open()

        assertNull(ready(vm).validator)
        assertEquals(UpdateUserResult.Busy, vm.saveAccess(UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5))))
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `saving access sends only the changed fields with the version the page was read with`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice", maxStreams = 3)), etag = "\"q1\"")
        val (vm, _) = single(server)
        vm.open()
        server.etag = "\"q2\""
        server.calls.clear()

        val result = vm.saveAccess(UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)))

        assertTrue(result is UpdateUserResult.Success)
        val write = server.writes.single()
        assertEquals("\"q1\"", write.ifMatch)
        assertEquals("""{"max_streams":5}""", write.body)
        // No read between open and write; afterwards the list and the page read again and pick up the new ETag.
        assertEquals("PUT", server.calls.first().method.value)
        assertEquals("\"q2\"", ready(vm).validator)
    }

    @Test
    fun `saving after someone else changed the account shows the conflict and reloads the form`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice", maxStreams = 3)), etag = "\"v1\"",
            writeStatus = HttpStatusCode.PreconditionFailed, writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )
        val (vm, _) = single(server)
        vm.open()
        server.etag = "\"v2\""
        server.users = listOf(V2Fixtures.adminUser("1", "alice", maxStreams = 9))

        val result = vm.saveAccess(UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)))

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_conflict)), result)
        assertEquals(1, server.writes.size)
        assertEquals("\"v1\"", server.writes.single().ifMatch)
        // The form now shows what the other admin saved, with the ETag to save against.
        assertEquals(9, ready(vm).user.max_streams)
        assertEquals("\"v2\"", ready(vm).validator)
    }

    @Test
    fun `a 428 is a conflict too`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice")),
            writeStatus = HttpStatusCode(428, "Precondition Required"),
            writeBody = V2Fixtures.problem(428, "precondition_required", "If-Match is required")
        )
        val (vm, _) = single(server)
        vm.open()

        val result = vm.saveAccess(UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)))

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_conflict)), result)
    }

    @Test
    fun `a validation message from the server is shown as is`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice")),
            writeStatus = HttpStatusCode.UnprocessableEntity,
            writeBody = V2Fixtures.problem(422, "validation_failed", "max_streams must be positive")
        )
        val (vm, _) = single(server)
        vm.open()

        val result = vm.saveAccess(UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(-1)))

        assertEquals(UpdateUserResult.Failure(ErrorText.Raw("max_streams must be positive")), result)
    }

    @Test
    fun `set password goes to the server with a temporary flag and never into an error`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice")),
            writeStatus = HttpStatusCode.UnprocessableEntity,
            writeBody = V2Fixtures.problem(422, "validation_failed", "password is too common")
        )
        val (vm, _) = single(server)
        vm.open()

        val result = vm.setPassword("hunter2hunter2", true)

        assertEquals("""{"password":"hunter2hunter2","require_password_change":true}""", server.writes.single().body)
        assertEquals(UpdateUserResult.Failure(ErrorText.Raw("password is too common")), result)
    }

    @Test
    fun `in All servers the writes go to that server only and failures name it`() = runTest {
        val a = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = "\"a-etag\"")
        val b = UsersTestServer(users = listOf(V2Fixtures.adminUser("2", "bob")))
        val clients = mapOf("srv-a" to a.client(), "srv-b" to b.client())
        val aggregate = AggregateUsersViewModel()
        val clientFor: (AdminServer) -> ApiClient = { clients.getValue(it.id) }
        val vm = UserDetailViewModel(com.bloemstudios.admin.users.detail.AggregateUserAdmin(aggregate, serverA, clientFor), "1")
        vm.open()

        assertEquals(ActionResult.Success, vm.setEnabled(false))

        assertEquals("\"a-etag\"", a.writes.single().ifMatch)
        assertTrue(b.calls.isEmpty())
        assertEquals("Server A", com.bloemstudios.admin.users.detail.AggregateUserAdmin(aggregate, serverA, clientFor).serverName)

        a.writeStatus = HttpStatusCode.PreconditionFailed
        a.writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        assertEquals(ActionResult.Failure, vm.delete())
        assertEquals(ErrorText.Prefixed("Server A", ErrorText.Res(R.string.users_conflict)), aggregate.actionError.value)
        assertTrue(b.calls.isEmpty())
        assertNotNull(aggregate.actionError.value)
    }

    @Test
    fun `the access choices are read when asked, not when the page opens`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")))
        server.routes["/api/v2/admin/access-groups"] = page(GROUP_JSON)
        server.routes["/api/v2/libraries"] = page(LIBRARY_JSON)
        server.routes["/api/v2/admin/users/policy-defaults"] = """{"admin":{"library_ids":null},"ungrouped":{"library_ids":["1"]}}"""
        val (vm, _) = single(server)

        vm.open()
        assertTrue(server.calls.none { it.path == "/api/v2/admin/access-groups" })
        assertEquals(Section.Loading, vm.groups.value)

        vm.loadAccessChoices()

        assertEquals("Family", (vm.groups.value as Section.Loaded).value.single().name)
        assertEquals("Movies", (vm.libraries.value as Section.Loaded).value.single().name)
        assertEquals(listOf("1"), (vm.policyDefaults.value as Section.Loaded).value.ungrouped?.library_ids)
    }

    @Test
    fun `saving the group, libraries and permissions sends them in one guarded write`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")), etag = "\"a1\"")
        val (vm, _) = single(server)
        vm.open()
        server.calls.clear()

        val user = ready(vm).user
        val request = com.bloemstudios.admin.users.buildUserAccessUpdate(
            user, com.bloemstudios.admin.users.UserAccessEdit("g1", listOf("1", "2"), listOf("marker_edit"))
        )
        val result = vm.saveAccess(request)

        assertTrue(result is UpdateUserResult.Success)
        val write = server.writes.single()
        assertEquals("\"a1\"", write.ifMatch)
        assertEquals("""{"access_group_id":"g1","library_ids":["1","2"],"permissions":["marker_edit"]}""", write.body)
    }

    @Test
    fun `a conflict on an access save reloads the account so the form shows the server's values`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice")), etag = "\"v1\"",
            writeStatus = HttpStatusCode.PreconditionFailed, writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )
        val (vm, _) = single(server)
        vm.open()
        server.etag = "\"v2\""
        server.users = listOf(V2Fixtures.adminUser("1", "alice").replace("\"permissions\":[]", "\"permissions\":[\"watch_live_tv\"]"))

        val request = com.bloemstudios.admin.users.buildUserAccessUpdate(
            ready(vm).user, com.bloemstudios.admin.users.UserAccessEdit(null, null, listOf("marker_edit"))
        )
        val result = vm.saveAccess(request)

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_conflict)), result)
        assertEquals(1, server.writes.size) // never retried
        assertEquals(listOf("watch_live_tv"), ready(vm).user.permissions)
        assertEquals("\"v2\"", ready(vm).validator)
    }

    private fun page(row: String) = """{"items":[$row],"page":{"has_more":false}}"""

    private companion object {
        const val GROUP_JSON = """{"id":"g1","name":"Family","description":"","library_ids":["1"],"max_playback_quality":"1080p","download_allowed":true,"download_transcode_allowed":false,"transcode_allowed":true,"audio_transcode_allowed":true,"max_streams":2,"max_transcodes":1,"max_remote_stream_bitrate_kbps":0,"max_local_stream_bitrate_kbps":0,"max_profiles":5,"allowed_permissions":null,"requests_allowed":true,"is_default":false,"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","member_count":3}"""
        const val LIBRARY_JSON = """{"id":"1","name":"Movies","type":"movie","paths":[],"enabled":true}"""
    }
}
