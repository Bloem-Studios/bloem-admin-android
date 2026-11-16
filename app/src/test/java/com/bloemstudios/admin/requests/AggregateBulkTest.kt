package com.bloemstudios.admin.requests

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bulk triage in All servers: each request is acted on at its own server. */
class AggregateBulkTest {
    private val a = AdminServer("srv-a", "https://a.test", "Server A")
    private val b = AdminServer("srv-b", "https://b.test", "Server B")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Fake(ids: List<String>) {
        var waiting = ids.toMutableList()
        val posts = mutableListOf<String>()
        val refused = mutableSetOf<String>()
        var listStatus = HttpStatusCode.OK
        var listReads = 0
    }

    private fun clients(vararg servers: Pair<AdminServer, Fake>): (AdminServer) -> ApiClient {
        val byId = servers.associate { (server, fake) ->
            server.id to ApiClient("https://x.test", "tok", MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    val id = request.url.encodedPath.substringAfter("/requests/").substringBefore("/")
                    fake.posts += request.url.encodedPath
                    if (id in fake.refused) respond(V2Fixtures.problem(409, "conflict", "Already handled on ${server.displayName}"), HttpStatusCode.Conflict, json)
                    else { fake.waiting.remove(id); respond(V2Fixtures.mediaRequest(id), HttpStatusCode.OK, json) }
                } else {
                    fake.listReads++
                    if (fake.listStatus != HttpStatusCode.OK) respond(V2Fixtures.problem(fake.listStatus.value, "dependency_unavailable", "Down"), fake.listStatus, json)
                    else respond(V2Fixtures.mediaRequestsPage(*fake.waiting.map { V2Fixtures.mediaRequest(it, "pending", "active", "T$it") }.toTypedArray()), HttpStatusCode.OK, json)
                }
            })
        }
        return { byId.getValue(it.id) }
    }

    @Test
    fun `approves each request on its own server`() = runTest {
        val fa = Fake(listOf("1", "2"))
        val fb = Fake(listOf("1", "9"))
        val clientFor = clients(a to fa, b to fb)
        val vm = AggregateRequestsViewModel()
        vm.load(listOf(a, b), clientFor)
        vm.startSelecting()
        vm.selectAllShown()
        assertEquals(setOf("srv-a:1", "srv-a:2", "srv-b:1", "srv-b:9"), vm.selected.value)

        vm.bulk(BulkRequestAction.Approve, null, listOf(a, b), clientFor)

        assertEquals(listOf("1", "2"), fa.posts.map { it.substringAfter("/requests/").substringBefore("/") }.sorted())
        assertEquals(listOf("1", "9"), fb.posts.map { it.substringAfter("/requests/").substringBefore("/") }.sorted())
        assertTrue(vm.selected.value.isEmpty())
        assertFalse(vm.selecting.value)
        assertEquals(4, vm.bulk.value?.done)
        assertTrue(vm.sections.value.all { it.result.getOrThrow().isEmpty() })
    }

    @Test
    fun `a refusal on one server leaves that request selected with the reason`() = runTest {
        val fa = Fake(listOf("1"))
        val fb = Fake(listOf("1"))
        fb.refused += "1"
        val clientFor = clients(a to fa, b to fb)
        val vm = AggregateRequestsViewModel()
        vm.load(listOf(a, b), clientFor)
        vm.startSelecting()
        vm.selectAllShown()

        vm.bulk(BulkRequestAction.Decline, "Duplicate", listOf(a, b), clientFor)

        assertEquals(setOf("srv-b:1"), vm.selected.value)
        assertTrue(vm.selecting.value)
        val failure = vm.bulk.value!!.failures.single()
        assertEquals("srv-b:1", failure.key)
        assertEquals(ErrorText.Prefixed("Server B", ErrorText.Raw("Already handled on Server B")), failure.message)
    }

    @Test
    fun `a request id shared by two servers is two different selections`() = runTest {
        val clientFor = clients(a to Fake(listOf("1")), b to Fake(listOf("1")))
        val vm = AggregateRequestsViewModel()
        vm.load(listOf(a, b), clientFor)
        vm.startSelecting("srv-a:1")
        assertEquals(setOf("srv-a:1"), vm.selected.value)
        vm.toggleSelected("srv-b:1")
        assertEquals(setOf("srv-a:1", "srv-b:1"), vm.selected.value)
    }

    @Test
    fun `a server that says request administration is unavailable is not read again by the poll`() = runTest {
        val fa = Fake(listOf("1"))
        val fb = Fake(listOf("1")).apply { listStatus = HttpStatusCode.ServiceUnavailable }
        val clientFor = clients(a to fa, b to fb)
        val vm = AggregateRequestsViewModel()

        vm.load(listOf(a, b), clientFor)
        vm.load(listOf(a, b), clientFor)
        vm.load(listOf(a, b), clientFor)

        assertEquals(1, fb.listReads)
        assertEquals(3, fa.listReads)
        assertTrue(vm.sections.value.first { it.server.id == "srv-b" }.result.isFailure)

        vm.lookAgain(listOf(a, b), clientFor)
        assertEquals("a person asking again looks again", 2, fb.listReads)
    }
}
