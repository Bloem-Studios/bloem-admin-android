package com.bloemstudios.admin.requests

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All servers: the lists are read whole, views and counts come from the shared rule, actions run on the row's own server. */
class AggregateRequestQueueTest {
    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Seen { val posts = mutableListOf<Triple<String, String, String>>() }

    private fun client(host: String, seen: Seen, rows: List<String>, refuse: Boolean = false, noUsers: Boolean = false) =
        ApiClient("https://x.test", "tok", MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post -> {
                    seen.posts += Triple(host, path, request.body.toByteArray().decodeToString())
                    if (refuse) respond(V2Fixtures.problem(409, "conflict", "Already approved"), HttpStatusCode.Conflict, json)
                    else respond(V2Fixtures.mediaRequest("x"), HttpStatusCode.OK, json)
                }
                path == "/api/v2/admin/users" ->
                    if (noUsers) respond(V2Fixtures.problem(403, "forbidden", "No"), HttpStatusCode.Forbidden, json)
                    else respond(V2Fixtures.adminUsersPage(V2Fixtures.adminUser("1", "alex-$host")), HttpStatusCode.OK, json)
                else -> respond(V2Fixtures.mediaRequestsPage(*rows.toTypedArray()), HttpStatusCode.OK, json)
            }
        })

    private fun row(id: String, status: String, outcome: String = "active") = V2Fixtures.mediaRequest(id, status, outcome)

    @Test
    fun `counts are summed over the servers by the shared rule`() = runTest {
        val seen = Seen()
        val clients = mapOf(
            "srv-a" to client("a", seen, listOf(row("1", "pending"), row("2", "approved"))),
            "srv-b" to client("b", seen, listOf(row("3", "pending"), row("4", "queued", "failed"), row("5", "completed")))
        )
        val vm = AggregateRequestsViewModel()

        vm.load(listOf(serverA, serverB)) { clients.getValue(it.id) }

        val total = vm.sections.value.fold(RequestCounts()) { sum, section -> sum + RequestQueue.countsOf(section.result.getOrThrow()) }
        assertEquals(RequestCounts(needs_approval = 2, in_progress = 1, failed = 1, done = 1), total)
    }

    @Test
    fun `an action runs on the rows own server and carries the note`() = runTest {
        val seen = Seen()
        val clients = mapOf("srv-a" to client("a", seen, listOf(row("1", "pending"))), "srv-b" to client("b", seen, listOf(row("1", "pending"))))
        val vm = AggregateRequestsViewModel()
        val servers = listOf(serverA, serverB)
        vm.load(servers) { clients.getValue(it.id) }
        val request = vm.sections.value.first { it.server.id == "srv-b" }.result.getOrThrow().first()

        val ok = vm.perform(RequestQueueAction.Decline, serverB, request, "  duplicate ") { clients.getValue(it.id) }

        assertTrue(ok)
        assertEquals(listOf(Triple("b", "/api/v2/admin/requests/1/decline", """{"reason":"duplicate"}""")), seen.posts)
        assertNull(vm.actionError.value)
    }

    @Test
    fun `a note is dropped for approve and retry and when blank`() = runTest {
        val seen = Seen()
        val c = client("a", seen, listOf(row("1", "pending")))
        val vm = AggregateRequestsViewModel()
        vm.load(listOf(serverA)) { c }
        val request = vm.sections.value.single().result.getOrThrow().single()

        vm.perform(RequestQueueAction.Approve, serverA, request, "ignored") { c }
        vm.perform(RequestQueueAction.Cancel, serverA, request, "  ") { c }
        vm.perform(RequestQueueAction.Cancel, serverA, request, "changed my mind") { c }

        assertEquals(listOf("{}", "{}", """{"reason":"changed my mind"}"""), seen.posts.map { it.third })
    }

    @Test
    fun `a refused action says which server and returns false`() = runTest {
        val seen = Seen()
        val c = client("a", seen, listOf(row("1", "pending")), refuse = true)
        val vm = AggregateRequestsViewModel()
        vm.load(listOf(serverA)) { c }
        val request = vm.sections.value.single().result.getOrThrow().single()

        val ok = vm.perform(RequestQueueAction.Approve, serverA, request, clientFor = { c })

        assertFalse(ok)
        assertEquals(com.bloemstudios.admin.network.ErrorText.Prefixed("Server A", com.bloemstudios.admin.network.ErrorText.Raw("Already approved")), vm.actionError.value)
    }

    @Test
    fun `names are read once per server and a server without the list is skipped`() = runTest {
        val seen = Seen()
        val clients = mapOf(
            "srv-a" to client("a", seen, emptyList()),
            "srv-b" to client("b", seen, emptyList(), noUsers = true)
        )
        val vm = AggregateRequestsViewModel()

        vm.loadNames(listOf(serverA, serverB)) { clients.getValue(it.id) }

        assertEquals(mapOf("srv-a" to mapOf("1" to "alex-a")), vm.names.value)
    }
}
