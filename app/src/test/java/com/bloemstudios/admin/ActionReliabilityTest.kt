package com.bloemstudios.admin

import com.bloemstudios.admin.network.*
import com.bloemstudios.admin.streams.*
import com.bloemstudios.admin.users.*
import com.bloemstudios.admin.serverconfig.*
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** HTTP barriers expose erased outcomes and queued duplicate intents in actual view models. */
class ActionReliabilityTest {
    private val user = AdminUser("1", "fixture", "fixture@example.test", "user", true)
    private val session = AdminSession("s1", "user-1", "fixture", "profile-1", "Title", "movie", "direct", 0.0, 100.0, false, "Fixture")
    private val a = AdminServer("a", "https://a.example.test", "A")
    private val b = AdminServer("b", "https://b.example.test", "B")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun readBody(path: String) = when {
        path.endsWith("/server/status") -> com.bloemstudios.admin.auth.V2Fixtures.SERVER_STATUS
        path.endsWith("/settings") -> "{}"
        path.endsWith("/admin/users") -> com.bloemstudios.admin.auth.V2Fixtures.adminUsersPage()
        Regex(".*/admin/users/[^/]+$").matches(path) -> com.bloemstudios.admin.auth.V2Fixtures.adminUser()
        else -> "[]"
    }
    /** A single user is read with its ETag, which guarded user writes require. */
    private fun readHeaders(path: String) =
        if (Regex(".*/admin/users/[^/]+$").matches(path)) headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\""))
        else json
    private class AggregateSubject(val error: StateFlow<ErrorText?>, val act: suspend (AdminServer) -> Unit)
    private class Subject(val error: StateFlow<ErrorText?>, val act: suspend (ApiClient) -> Unit, val load: suspend (ApiClient) -> Unit)
    private fun single(family: String): Subject = when(family) {
        "users" -> UsersViewModel().let { vm -> Subject(vm.errorMessage, { vm.delete(user, it) }, {vm.load(it)}) }
        "settings" -> ServerConfigViewModel().let { vm -> Subject(vm.errorMessage, { vm.restartServer(it) }, {vm.load(it)}) }
        else -> StreamsViewModel().let { vm -> Subject(vm.errorMessage, { vm.pause(session, it) }, {vm.load(it)}) }
    }
    @Test fun `users action failure survives successful read`() = persistent("users")
    @Test fun `settings action failure survives successful read`() = persistent("settings")
    @Test fun `streams action failure survives repository refresh`() = persistent("streams")
    private fun persistent(family: String) = runTest {
        val client = ApiClient("https://example.test", null, MockEngine { req ->
            if (req.method == HttpMethod.Get) respond(readBody(req.url.encodedPath), headers=readHeaders(req.url.encodedPath))
            else respond(com.bloemstudios.admin.auth.V2Fixtures.problem(400, "malformed_request", "denied"), HttpStatusCode.BadRequest, json)
        })
        try { val subject=single(family); subject.act(client); val failure=subject.error.value; assertNotNull(failure); subject.load(client); assertEquals(failure, subject.error.value) } finally { client.close() }
    }
    @Test fun `users duplicates are dropped during request`() = duplicates("users")
    @Test fun `settings duplicates are dropped during request`() = duplicates("settings")
    @Test fun `streams duplicates are dropped during request`() = duplicates("streams")
    private fun duplicates(family: String) = runTest {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var writes=0
        val client=ApiClient("https://example.test", null, MockEngine { req ->
            if(req.method != HttpMethod.Get) { writes++; entered.complete(Unit); release.await() }
            respond(readBody(req.url.encodedPath), headers=readHeaders(req.url.encodedPath))
        })
        try {
            val subject=single(family); val first=launch {subject.act(client)}; entered.await()
            val duplicate=launch {subject.act(client)}
            // A dropped intent completes while the first HTTP request remains suspended.
            withContext(Dispatchers.Default) { withTimeout(2_000) { duplicate.join() } }
            assertEquals(1,writes); release.complete(Unit); first.join()
        } finally {release.complete(Unit);client.close()}
    }
    @Test fun `aggregate streams old success cannot erase newer failure`() = aggregateOrder("streams")
    @Test fun `aggregate users old success cannot erase newer failure`() = aggregateOrder("users")
    @Test fun `aggregate settings old success cannot erase newer failure`() = aggregateOrder("settings")
    private fun aggregateOrder(family: String) = runTest {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val first=ApiClient(a.baseUrl,null,MockEngine { req ->
            if(req.method != HttpMethod.Get) {entered.complete(Unit);release.await()}
            respond(readBody(req.url.encodedPath),headers=readHeaders(req.url.encodedPath))
        })
        val second=ApiClient(b.baseUrl,null,MockEngine { req ->
            if(req.method == HttpMethod.Get) respond(readBody(req.url.encodedPath),headers=readHeaders(req.url.encodedPath))
            else respond(com.bloemstudios.admin.auth.V2Fixtures.problem(400, "malformed_request", "new failure"),HttpStatusCode.BadRequest,json)
        })
        val clientFor:(AdminServer)->ApiClient={if(it.id==a.id)first else second}
        val subject = when(family) {
            "users" -> AggregateUsersViewModel().let { vm -> AggregateSubject(vm.errorMessage, { server:AdminServer -> vm.delete(server,user,clientFor); Unit }) }
            "settings" -> AggregateServerConfigViewModel().let { vm -> AggregateSubject(vm.errorMessage, { server:AdminServer -> vm.restartServer(server,clientFor); Unit }) }
            else -> AggregateStreamsViewModel().let { vm -> AggregateSubject(vm.errorMessage, { server:AdminServer -> vm.pause(server,session,clientFor); Unit }) }
        }
        val error = subject.error; val act = subject.act
        try { val older=launch {act(a)}; entered.await(); act(b); val failure=error.value; assertNotNull(failure); release.complete(Unit); older.join(); assertEquals(failure,error.value) }
        finally {release.complete(Unit);first.close();second.close()}
    }
}
