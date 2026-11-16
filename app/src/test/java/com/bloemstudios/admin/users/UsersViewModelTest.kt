package com.bloemstudios.admin.users

import com.bloemstudios.admin.ActionResult
import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class UsersViewModelTest {
    @Test
    fun `admin users feature cannot mint an impersonation credential`() {
        assertFalse(UsersViewModel::class.java.methods.any {
            it.name.contains("impersonat", ignoreCase = true)
        })
    }

    @Test
    fun `load decodes users`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alex", maxStreams = 3)))
        val viewModel = UsersViewModel()

        viewModel.load(server.client())

        assertEquals(1, viewModel.users.value.size)
        assertEquals("alex", viewModel.users.value.first().username)
        assertEquals("1", viewModel.users.value.first().id)
        assertEquals(3, viewModel.users.value.first().max_streams)
        assertEquals(6, viewModel.users.value.first().effective_policy?.max_streams)
    }

    @Test
    fun `users list follows cursors`() = runTest {
        val cursors = mutableListOf<String?>()
        val client = ApiClient("https://example.test", "tok", MockEngine { request ->
            cursors += request.url.parameters["cursor"]
            val body = if (request.url.parameters["cursor"] == null)
                V2Fixtures.adminUsersPage(V2Fixtures.adminUser("1", "alice"), nextCursor = "c2")
            else V2Fixtures.adminUsersPage(V2Fixtures.adminUser("2", "bob"))
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val viewModel = UsersViewModel()

        viewModel.load(client)

        assertEquals(listOf<String?>(null, "c2"), cursors)
        assertEquals(listOf("alice", "bob"), viewModel.users.value.map { it.username })
    }

    @Test
    fun `disable sends If-Match from a fresh read`() = runTest {
        val server = UsersTestServer(etag = "\"fresh-7\"")
        val viewModel = UsersViewModel()
        val stale = AdminUser("1", "alice", "alice@example.test", "user", true)

        val result = viewModel.setEnabled(false, stale, server.client())

        assertEquals(ActionResult.Success, result)
        val write = server.writes.single()
        assertEquals(HttpMethod.Put, write.method)
        assertEquals("/api/v2/admin/users/1", write.path)
        assertEquals("\"fresh-7\"", write.ifMatch)
        assertEquals("""{"enabled":false}""", write.body)
        // The validator came from a GET issued before the write; the list reloads after it.
        val order = server.calls.map { it.method.value + " " + it.path }
        assertEquals(listOf("GET /api/v2/admin/users/1", "PUT /api/v2/admin/users/1", "GET /api/v2/admin/users"), order)
    }

    @Test
    fun `delete sends If-Match`() = runTest {
        val server = UsersTestServer(etag = "\"fresh-9\"")
        val viewModel = UsersViewModel()

        val result = viewModel.delete(AdminUser("42", "test", "t@example.test", "user", true), server.client())

        assertEquals(ActionResult.Success, result)
        val write = server.writes.single()
        assertEquals(HttpMethod.Delete, write.method)
        assertEquals("/api/v2/admin/users/42", write.path)
        assertEquals("\"fresh-9\"", write.ifMatch)
    }

    @Test
    fun `missing ETag refuses the write`() = runTest {
        val server = UsersTestServer(etag = null)
        val viewModel = UsersViewModel()
        val user = AdminUser("1", "alice", "alice@example.test", "user", true)

        assertEquals(ActionResult.Failure, viewModel.delete(user, server.client()))

        assertEquals(ErrorText.Res(R.string.users_missing_version), viewModel.actionError.value)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `an edit dialog opened without an ETag reports no version so Save stays disabled`() = runTest {
        val server = UsersTestServer(etag = null)

        val load = UsersViewModel().loadUserForEdit("1", server.client())

        assertEquals(null, (load as UserEditLoad.Ready).validator)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `dialog opens with the fresh user's values`() = runTest {
        // The list row says 3; the server has since changed it to 8.
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice", maxStreams = 8)), etag = "\"open-1\"")

        val load = UsersViewModel().loadUserForEdit("1", server.client()) as UserEditLoad.Ready

        assertEquals("\"open-1\"", load.validator)
        assertEquals(8, load.user.max_streams)
        assertEquals("8", load.user.policyEditorValues().maxStreamsText)
        assertEquals("GET /api/v2/admin/users/1", server.calls.single().let { it.method.value + " " + it.path })
    }

    @Test
    fun `a quota edit saved after someone else changed the user shows a conflict`() = runTest {
        val server = UsersTestServer(
            users = listOf(V2Fixtures.adminUser("1", "alice", maxStreams = 3)), etag = "\"v1\"",
            writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "The user was modified")
        )
        val client = server.client()
        val viewModel = UsersViewModel()
        val opened = viewModel.loadUserForEdit("1", client) as UserEditLoad.Ready
        server.etag = "\"v2\"" // someone else saves while the dialog is open
        server.calls.clear()

        val result = viewModel.updateQuotas(
            opened.user, UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)), opened.validator!!, client
        )

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_conflict)), result)
        val write = server.writes.single()
        assertEquals("\"v1\"", write.ifMatch)
        assertEquals("""{"max_streams":5}""", write.body)
        // No read between open and write; the only call after the PUT is the list reload.
        assertEquals(listOf("PUT /api/v2/admin/users/1", "GET /api/v2/admin/users"), server.calls.map { it.method.value + " " + it.path })
    }

    @Test
    fun `a 412 shows a conflict and reloads without retrying`() = runTest {
        val server = UsersTestServer(
            writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "The user was modified")
        )
        val viewModel = UsersViewModel()

        val result = viewModel.setEnabled(false, AdminUser("1", "alice", "alice@example.test", "user", true), server.client())

        assertEquals(ActionResult.Failure, result)
        assertEquals(1, server.writes.size)
        assertEquals(ErrorText.Res(R.string.users_conflict), viewModel.actionError.value)
        // Exactly one PUT, and the list is reloaded after it.
        val order = server.calls.map { it.method.value + " " + it.path }
        assertEquals(listOf("GET /api/v2/admin/users/1", "PUT /api/v2/admin/users/1", "GET /api/v2/admin/users"), order)
        assertEquals(1, viewModel.users.value.size)
    }

    @Test
    fun `a 428 is treated as a conflict too`() = runTest {
        val server = UsersTestServer(
            writeStatus = HttpStatusCode(428, "Precondition Required"),
            writeBody = V2Fixtures.problem(428, "precondition_required", "If-Match is required")
        )
        val viewModel = UsersViewModel()

        val result = viewModel.updateQuotas(
            AdminUser("1", "alice", "alice@example.test", "user", true),
            UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)), "\"v1\"", server.client()
        )

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_conflict)), result)
        assertEquals(1, server.writes.size)
        assertEquals("GET /api/v2/admin/users", server.calls.last().let { it.method.value + " " + it.path })
    }

    @Test
    fun `createUser posts the required fields with a default profile and reloads on success`() = runTest {
        val server = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "newbie")))
        val viewModel = UsersViewModel()

        val result = viewModel.createUser("newbie", "n@example.test", "hunter2hunter2", "user", server.client())

        assertTrue(result is CreateUserResult.Success)
        val post = server.calls.first()
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("/api/v2/admin/users", post.path)
        assertEquals(
            """{"username":"newbie","email":"n@example.test","password":"hunter2hunter2","role":"user","create_default_profile":true}""",
            post.body
        )
        assertEquals(2, server.calls.size)
        assertEquals("newbie", viewModel.users.value.single().username)
    }

    @Test
    fun `createUser surfaces the server's validation message on 422 and leaves the list unchanged`() = runTest {
        val client = ApiClient("https://example.test", "tok", MockEngine {
            respond(
                V2Fixtures.problem(422, "validation_failed", "email is already in use"),
                HttpStatusCode.UnprocessableEntity, headersOf(HttpHeaders.ContentType, "application/problem+json")
            )
        })
        val viewModel = UsersViewModel()

        val result = viewModel.createUser("dupe", "dupe@example.test", "hunter2hunter2", "user", client)

        assertTrue(result is CreateUserResult.Failure)
        assertEquals(ErrorText.Raw("email is already in use"), (result as CreateUserResult.Failure).message)
        assertNull(viewModel.errorMessage.value)
        assertTrue(viewModel.users.value.isEmpty())
    }

    @Test
    fun `updateQuotas sends only the changed fields with the dialog's If-Match and reloads on success`() = runTest {
        val server = UsersTestServer(etag = "\"q1\"")
        val viewModel = UsersViewModel()
        val target = AdminUser("1", "alex", "j@example.test", "admin", true, max_streams = 3)

        val result = viewModel.updateQuotas(target, UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)), "\"q1\"", server.client())

        assertTrue(result is UpdateUserResult.Success)
        val write = server.writes.single()
        assertEquals("/api/v2/admin/users/1", write.path)
        assertEquals("\"q1\"", write.ifMatch)
        assertEquals("""{"max_streams":5}""", write.body)
        // The version is the one the dialog opened with: no read precedes the write.
        assertEquals("PUT", server.calls.first().method.value)
        assertEquals("GET /api/v2/admin/users", server.calls.last().let { it.method.value + " " + it.path })
    }

    @Test
    fun `updateQuotas sends explicit null when clearing an override`() = runTest {
        val server = UsersTestServer()
        val viewModel = UsersViewModel()
        val target = AdminUser("1", "alex", "j@example.test", "admin", true, max_streams = 3)

        val result = viewModel.updateQuotas(target, UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Clear), "\"v1\"", server.client())

        assertTrue(result is UpdateUserResult.Success)
        assertEquals("""{"max_streams":null}""", server.writes.single().body)
    }

    @Test
    fun `updateQuotas surfaces the server's validation message on failure`() = runTest {
        val server = UsersTestServer(
            writeStatus = HttpStatusCode.UnprocessableEntity,
            writeBody = V2Fixtures.problem(422, "validation_failed", "max_streams must be positive")
        )
        val viewModel = UsersViewModel()
        val target = AdminUser("1", "alex", "j@example.test", "admin", true)

        val result = viewModel.updateQuotas(target, UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(-1)), "\"v1\"", server.client())

        assertEquals(UpdateUserResult.Failure(ErrorText.Raw("max_streams must be positive")), result)
    }

    @Test
    fun `a 401 on a guarded write is not replayed`() = runTest {
        var puts = 0
        val client = ApiClient("https://example.test", "tok", MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.method == HttpMethod.Put -> { puts++; respond(V2Fixtures.problem(401, "session_expired", "Sign in again"), HttpStatusCode.Unauthorized, json) }
                else -> respond(V2Fixtures.adminUser(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v\"")))
            }
        }, unauthorizedRecovery = com.bloemstudios.admin.auth.UnauthorizedRecovery {
            fail("a guarded write must not replay after a 401")
            com.bloemstudios.admin.auth.RecoveryResult.Retry(com.bloemstudios.admin.auth.AccessTokenSnapshot("new", 1L))
        })
        val result = UsersViewModel().setEnabled(false, AdminUser("1", "a", "a@example.test", "user", true), client)

        assertEquals(ActionResult.Failure, result)
        assertEquals(1, puts)
    }

    @Test
    fun `setPassword sends the password and the temporary flag with a fresh If-Match`() = runTest {
        val server = UsersTestServer(etag = "\"fresh-3\"")
        val viewModel = UsersViewModel()

        val result = viewModel.setPassword(AdminUser("1", "alice", "a@example.test", "user", true, password_login = false), "correct horse", true, server.client())

        assertTrue(result is UpdateUserResult.Success)
        val write = server.writes.single()
        assertEquals("/api/v2/admin/users/1", write.path)
        assertEquals("\"fresh-3\"", write.ifMatch)
        assertEquals("""{"password":"correct horse","require_password_change":true}""", write.body)
        assertEquals(listOf("GET /api/v2/admin/users/1", "PUT /api/v2/admin/users/1", "GET /api/v2/admin/users"), server.calls.map { it.method.value + " " + it.path })
    }

    @Test
    fun `setPassword omits the temporary flag when it is off`() = runTest {
        val server = UsersTestServer()

        UsersViewModel().setPassword(AdminUser("1", "alice", "a@example.test", "user", true), "correct horse", false, server.client())

        assertEquals("""{"password":"correct horse"}""", server.writes.single().body)
    }

    @Test
    fun `setPassword without an ETag sends nothing`() = runTest {
        val server = UsersTestServer(etag = null)

        val result = UsersViewModel().setPassword(AdminUser("1", "alice", "a@example.test", "user", true), "correct horse", true, server.client())

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_missing_version)), result)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `setPassword shows the server's rule and never repeats the password`() = runTest {
        val server = UsersTestServer(
            writeStatus = HttpStatusCode.UnprocessableEntity,
            writeBody = V2Fixtures.problem(422, "validation_failed", "password is too common")
        )

        val result = UsersViewModel().setPassword(AdminUser("1", "alice", "a@example.test", "user", true), "password1", true, server.client())

        assertEquals(UpdateUserResult.Failure(ErrorText.Raw("password is too common")), result)
    }

    @Test
    fun `setPassword conflict reloads the list and is not retried`() = runTest {
        val server = UsersTestServer(
            writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )

        val result = UsersViewModel().setPassword(AdminUser("1", "alice", "a@example.test", "user", true), "correct horse", true, server.client())

        assertEquals(UpdateUserResult.Failure(ErrorText.Res(R.string.users_conflict)), result)
        assertEquals(1, server.writes.size)
        assertEquals("GET /api/v2/admin/users", server.calls.last().let { it.method.value + " " + it.path })
    }

    private val alice = AdminUser("1", "alice", "alice@example.test", "user", true)
    private val bob = AdminUser("2", "bob99", "bob@work.test", "user", true)
    private val allUsers = listOf(alice, bob)

    @Test
    fun `filterUsers returns everything when the query is blank`() {
        assertEquals(allUsers, filterUsers(allUsers, ""))
        assertEquals(allUsers, filterUsers(allUsers, "   "))
    }

    @Test
    fun `filterUsers matches username case-insensitively`() {
        assertEquals(listOf(alice), filterUsers(allUsers, "ALICE"))
        assertEquals(listOf(bob), filterUsers(allUsers, "bob9"))
    }

    @Test
    fun `filterUsers matches email substrings`() {
        assertEquals(listOf(bob), filterUsers(allUsers, "work.test"))
    }

    @Test
    fun `filterUsers returns an empty list when nothing matches`() {
        assertTrue(filterUsers(allUsers, "nonexistent").isEmpty())
    }
}
