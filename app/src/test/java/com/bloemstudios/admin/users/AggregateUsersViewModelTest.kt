package com.bloemstudios.admin.users

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

private fun userJson(id: Int, username: String, enabled: Boolean = true) =
    V2Fixtures.adminUser(id.toString(), username, enabled)

class AggregateUsersViewModelTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")

    private fun clientRespondingWith(vararg users: String): ApiClient = UsersTestServer(users = users.toList()).client()

    private fun failingClient(): ApiClient {
        val engine = MockEngine { respond(V2Fixtures.problem(401, "invalid_token", "nope"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/problem+json")) }
        return ApiClient("https://x.test", "tok", engine)
    }

    @Test
    fun `aggregate mode does not expose impersonation while other tabs use administrator clients`() {
        val exposesImpersonation = AggregateUsersViewModel::class.java.methods.any {
            it.name == "impersonate"
        }

        assertFalse(
            "Aggregate mode must not claim an effective-user identity while its other tabs execute administrator calls",
            exposesImpersonation
        )
    }

    @Test
    fun `load sections users per server`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(userJson(1, "alice")),
            "srv-b" to clientRespondingWith(userJson(2, "bob"), userJson(3, "carol"))
        )

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertEquals(listOf("alice"), byServer.getValue("srv-a").result.getOrThrow().map { it.username })
        assertEquals(listOf("bob", "carol"), byServer.getValue("srv-b").result.getOrThrow().map { it.username })
    }

    @Test
    fun `a failing server produces an error section and does not affect the other`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val clients = mapOf("srv-a" to clientRespondingWith(userJson(1, "alice")), "srv-b" to failingClient())

        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertTrue(byServer.getValue("srv-b").result.isFailure)
    }

    @Test
    fun `disabling a user on one server reloads only that server's section`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val clients = mapOf(
            "srv-a" to clientRespondingWith(userJson(1, "alice")),
            "srv-b" to clientRespondingWith(userJson(2, "bob"))
        )
        viewModel.load(listOf(serverA, serverB)) { clients.getValue(it.id) }
        val originalBSection = viewModel.sections.value.first { it.server.id == "srv-b" }
        val alice = viewModel.sections.value.first { it.server.id == "srv-a" }.result.getOrThrow().first()

        viewModel.setEnabled(serverA, false, alice) { clients.getValue(it.id) }

        val byServer = viewModel.sections.value.associateBy { it.server.id }
        assertTrue(byServer.getValue("srv-a").result.isSuccess)
        assertSame(originalBSection, byServer.getValue("srv-b"))
    }

    @Test
    fun `a slow poll cannot overwrite a faster action's result once both complete`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val server = AdminServer("srv-x", "https://x.test", "Server X")
        val gate = CompletableDeferred<Unit>()
        var blockedOnce = false
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v2/admin/users" && !blockedOnce -> {
                    blockedOnce = true
                    gate.await()
                    respond(V2Fixtures.adminUsersPage(userJson(1, "alice", enabled = true)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v2/admin/users/1" ->
                    respond(userJson(1, "alice", enabled = true), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\"")))
                request.method == HttpMethod.Put -> respond("", HttpStatusCode.NoContent)
                else -> respond(V2Fixtures.adminUsersPage(userJson(1, "alice", enabled = false)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://x.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        val alice = AdminUser("1", "alice", "alice@example.test", "user", true)

        val loadJob = launch { viewModel.load(listOf(server), clientFor) }
        yield()
        val actJob = launch { viewModel.setEnabled(server, false, alice, clientFor) }
        yield()

        assertTrue(viewModel.sections.value.isEmpty())

        gate.complete(Unit)
        loadJob.join()
        actJob.join()

        val section = viewModel.sections.value.first { it.server.id == "srv-x" }
        assertFalse(section.result.getOrThrow().first().enabled)
    }

    @Test
    fun `a failed action surfaces an error message instead of failing silently`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Delete) {
                respond(V2Fixtures.problem(409, "conflict", "cannot delete the last admin"), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            } else if (request.url.encodedPath == "/api/v2/admin/users/1") {
                respond(userJson(1, "alice"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\"")))
            } else {
                respond(V2Fixtures.adminUsersPage(userJson(1, "alice")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = ApiClient("https://a.test", "tok", engine)
        val clientFor: (AdminServer) -> ApiClient = { client }
        val alice = AdminUser("1", "alice", "alice@example.test", "user", true)

        viewModel.load(listOf(serverA), clientFor)
        assertNull(viewModel.errorMessage.value)

        viewModel.delete(serverA, alice, clientFor)

        assertNotNull("a failed action must surface an error, not fail silently", viewModel.errorMessage.value)
    }


    @Test
    fun `disable sends If-Match from a fresh read on that server only`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val a = UsersTestServer(users = listOf(userJson(1, "alice")), etag = "\"a-etag\"")
        val b = UsersTestServer(users = listOf(userJson(2, "bob")))
        val clients = mapOf("srv-a" to a.client(), "srv-b" to b.client())

        viewModel.setEnabled(serverA, false, AdminUser("1", "alice", "alice@example.test", "user", true)) { clients.getValue(it.id) }

        val write = a.writes.single()
        assertEquals("\"a-etag\"", write.ifMatch)
        assertEquals("""{"enabled":false}""", write.body)
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `a 412 shows a conflict, reloads that section and does not retry`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val a = UsersTestServer(
            users = listOf(userJson(1, "alice")), writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )
        val clientFor: (AdminServer) -> ApiClient = { a.client() }
        viewModel.load(listOf(serverA), clientFor)
        a.calls.clear()

        val result = viewModel.delete(serverA, AdminUser("1", "alice", "alice@example.test", "user", true), clientFor)

        assertEquals(com.bloemstudios.admin.ActionResult.Failure, result)
        assertEquals(1, a.writes.size)
        assertEquals(ErrorText.Prefixed("Server A", ErrorText.Res(com.bloemstudios.admin.R.string.users_conflict)), viewModel.actionError.value)
        assertEquals("/api/v2/admin/users", a.calls.last().path)
        assertTrue(viewModel.sections.value.single().result.isSuccess)
    }

    @Test
    fun `an edit dialog opened without an ETag reports no version`() = runTest {
        val a = UsersTestServer(users = listOf(userJson(1, "alice")), etag = null)

        val load = AggregateUsersViewModel().loadUserForEdit(serverA, "1") { a.client() }

        assertNull((load as UserEditLoad.Ready).validator)
        assertTrue(a.writes.isEmpty())
    }

    @Test
    fun `dialog opens with the fresh user's values`() = runTest {
        val a = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice", maxStreams = 8)), etag = "\"open-1\"")

        val load = AggregateUsersViewModel().loadUserForEdit(serverA, "1") { a.client() } as UserEditLoad.Ready

        assertEquals("\"open-1\"", load.validator)
        assertEquals("8", load.user.policyEditorValues().maxStreamsText)
    }

    @Test
    fun `a quota edit saved after someone else changed the user shows a conflict`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val a = UsersTestServer(
            users = listOf(userJson(1, "alice")), etag = "\"v1\"", writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )
        val clientFor: (AdminServer) -> ApiClient = { a.client() }
        viewModel.load(listOf(serverA), clientFor)
        val opened = viewModel.loadUserForEdit(serverA, "1", clientFor) as UserEditLoad.Ready
        a.etag = "\"v2\""
        a.calls.clear()

        val result = viewModel.updateQuotas(
            serverA, opened.user, UpdateUserQuotasRequest(max_streams = PolicyOverrideChange.Set(5)), opened.validator!!, clientFor
        )

        assertEquals(UpdateUserResult.Failure(ErrorText.Prefixed("Server A", ErrorText.Res(com.bloemstudios.admin.R.string.users_conflict))), result)
        assertEquals("\"v1\"", a.writes.single().ifMatch)
        assertEquals(listOf("PUT /api/v2/admin/users/1", "GET /api/v2/admin/users"), a.calls.map { it.method.value + " " + it.path })
        assertTrue(viewModel.sections.value.single().result.isSuccess)
    }

    @Test
    fun `setPassword writes to that server only with its fresh If-Match`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val a = UsersTestServer(users = listOf(userJson(1, "alice")), etag = "\"a-etag\"")
        val b = UsersTestServer(users = listOf(userJson(2, "bob")))
        val clients = mapOf("srv-a" to a.client(), "srv-b" to b.client())

        val result = viewModel.setPassword(serverA, AdminUser("1", "alice", "alice@example.test", "user", true), "correct horse", true) { clients.getValue(it.id) }

        assertTrue(result is UpdateUserResult.Success)
        val write = a.writes.single()
        assertEquals("\"a-etag\"", write.ifMatch)
        assertEquals("""{"password":"correct horse","require_password_change":true}""", write.body)
        assertTrue(b.calls.isEmpty())
    }

    @Test
    fun `setPassword conflict names the server and reloads its section`() = runTest {
        val viewModel = AggregateUsersViewModel()
        val a = UsersTestServer(
            users = listOf(userJson(1, "alice")), writeStatus = HttpStatusCode.PreconditionFailed,
            writeBody = V2Fixtures.problem(412, "precondition_failed", "changed")
        )

        val result = viewModel.setPassword(serverA, AdminUser("1", "alice", "alice@example.test", "user", true), "correct horse", true) { a.client() }

        assertEquals(UpdateUserResult.Failure(ErrorText.Prefixed("Server A", ErrorText.Res(com.bloemstudios.admin.R.string.users_conflict))), result)
        assertEquals("/api/v2/admin/users", a.calls.last().path)
    }

}
