package com.bloemstudios.admin.requests

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Bulk triage, unavailable and stale reporting on the single-server queue. */
class RequestBulkViewModelTest {
    private class Server(ids: List<String> = listOf("1", "2", "3")) {
        var waiting = ids.toMutableList()
        val refused = mutableMapOf<String, String>()
        val posts = mutableListOf<Pair<String, String>>()
        var capabilitiesAvailable = true
        var listStatus = HttpStatusCode.OK
        var listSlug = "dependency_unavailable"
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        fun client() = ApiClient("https://example.test", null, MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post -> {
                    val id = path.substringAfter("/requests/").substringBefore("/")
                    posts += path to request.body.toByteArray().decodeToString()
                    val why = refused[id]
                    if (why != null) respond(V2Fixtures.problem(409, "conflict", why), HttpStatusCode.Conflict, json)
                    else { waiting.remove(id); respond(V2Fixtures.mediaRequest(id), HttpStatusCode.OK, json) }
                }
                path == "/api/v2/admin/requests/capabilities" -> respond("""{"available":$capabilitiesAvailable}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/requests/counts" ->
                    if (listStatus == HttpStatusCode.OK) respond("""{"needs_approval":${waiting.size},"in_progress":0,"failed":0,"done":0}""", HttpStatusCode.OK, json)
                    else respond(V2Fixtures.problem(listStatus.value, listSlug, "Down"), listStatus, json)
                path == "/api/v2/admin/users" -> respond(V2Fixtures.adminUsersPage(V2Fixtures.adminUser("68", "alex")), HttpStatusCode.OK, json)
                else ->
                    if (listStatus == HttpStatusCode.OK) respond(
                        V2Fixtures.mediaRequestsPage(*waiting.map { V2Fixtures.mediaRequest(it, "pending", "active", "T$it") }.toTypedArray()),
                        HttpStatusCode.OK, json
                    ) else respond(V2Fixtures.problem(listStatus.value, listSlug, "Down"), listStatus, json)
            }
        })
    }

    private suspend fun opened(server: Server): RequestsViewModel {
        val vm = RequestsViewModel()
        vm.load(server.client())
        return vm
    }

    @Test
    fun `a long press starts selecting on needs approval and selects that row`() = runTest {
        val vm = opened(Server())
        vm.startSelecting("2")
        assertTrue(vm.state.value.selecting)
        assertEquals(setOf("2"), vm.state.value.selected)
    }

    @Test
    fun `selecting is only for the needs approval view`() = runTest {
        val server = Server()
        val vm = opened(server)
        vm.select(RequestQueueView.InProgress)
        vm.startSelecting("2")
        assertFalse(vm.state.value.selecting)
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test
    fun `picking another view ends selecting`() = runTest {
        val vm = opened(Server())
        vm.startSelecting("1")
        vm.select(RequestQueueView.Failed)
        assertFalse(vm.state.value.selecting)
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test
    fun `select all shown, toggle and clear`() = runTest {
        val vm = opened(Server())
        vm.startSelecting()
        vm.selectAllShown()
        assertEquals(setOf("1", "2", "3"), vm.state.value.selected)
        vm.toggleSelected("2")
        assertEquals(setOf("1", "3"), vm.state.value.selected)
        vm.toggleSelected("2")
        vm.clearSelection()
        assertTrue(vm.state.value.selected.isEmpty())
        assertTrue("still selecting after clearing", vm.state.value.selecting)
        vm.stopSelecting()
        assertFalse(vm.state.value.selecting)
    }

    @Test
    fun `approve N sends one approve per request with no note and leaves select mode`() = runTest {
        val server = Server()
        val vm = opened(server)
        vm.startSelecting()
        vm.selectAllShown()

        vm.bulk(BulkRequestAction.Approve, null, server.client())

        assertEquals(listOf("1", "2", "3"), server.posts.map { it.first.substringAfter("/requests/").substringBefore("/") }.sorted())
        assertTrue(server.posts.all { it.first.endsWith("/approve") })
        assertTrue("the server is told no note", server.posts.all { it.second == "{}" })
        assertTrue(vm.state.value.requests.isEmpty())
        assertFalse(vm.state.value.selecting)
        assertTrue(vm.state.value.selected.isEmpty())
        val result = vm.state.value.bulk
        assertNotNull(result)
        assertEquals(3, result!!.done)
        assertTrue(result.failures.isEmpty())
        assertEquals(0, vm.state.value.counts?.needs_approval)
    }

    @Test
    fun `decline N sends the one note to every request, trimmed`() = runTest {
        val server = Server(listOf("1", "2"))
        val vm = opened(server)
        vm.startSelecting()
        vm.selectAllShown()

        vm.bulk(BulkRequestAction.Decline, "  Already in the library  ", server.client())

        assertTrue(server.posts.all { it.first.endsWith("/decline") })
        assertTrue(server.posts.all { it.second == """{"reason":"Already in the library"}""" })
    }

    @Test
    fun `a blank decline note is not sent`() = runTest {
        val server = Server(listOf("1"))
        val vm = opened(server)
        vm.startSelecting("1")

        vm.bulk(BulkRequestAction.Decline, "   ", server.client())

        assertEquals("{}", server.posts.single().second)
    }

    @Test
    fun `refused requests stay selected with their reason, the rest are done`() = runTest {
        val server = Server()
        server.refused["2"] = "Already approved by someone else"
        val vm = opened(server)
        vm.startSelecting()
        vm.selectAllShown()

        vm.bulk(BulkRequestAction.Approve, null, server.client())

        val state = vm.state.value
        assertEquals(setOf("2"), state.selected)
        assertTrue("select mode stays so the failed one can be tried again", state.selecting)
        val failure = state.bulk!!.failures.single()
        assertEquals("2", failure.key)
        assertEquals("T2", failure.title)
        assertEquals(ErrorText.Raw("Already approved by someone else"), failure.message)
        assertEquals(listOf("2"), state.requests.map { it.id })
    }

    @Test
    fun `dismissing the result keeps the selection`() = runTest {
        val server = Server(listOf("1"))
        server.refused["1"] = "Nope sorry"
        val vm = opened(server)
        vm.startSelecting("1")
        vm.bulk(BulkRequestAction.Approve, null, server.client())
        vm.dismissBulkResult()
        assertNull(vm.state.value.bulk)
        assertEquals(setOf("1"), vm.state.value.selected)
    }

    @Test
    fun `nothing is sent for an empty selection`() = runTest {
        val server = Server()
        val vm = opened(server)
        vm.startSelecting()
        vm.bulk(BulkRequestAction.Approve, null, server.client())
        assertTrue(server.posts.isEmpty())
        assertNull(vm.state.value.bulk)
    }

    @Test
    fun `a row that left the page is dropped from the selection on the next read`() = runTest {
        val server = Server()
        val vm = opened(server)
        vm.startSelecting()
        vm.selectAllShown()
        server.waiting.remove("3")

        vm.load(server.client())

        assertEquals(setOf("1", "2"), vm.state.value.selected)
    }

    @Test
    fun `capabilities that say not available mark the queue unavailable`() = runTest {
        val server = Server().apply { capabilitiesAvailable = false }
        val vm = RequestsViewModel()
        vm.checkAvailability(server.client())
        assertTrue(vm.state.value.unavailable)
    }

    @Test
    fun `capabilities that say available leave the queue alone`() = runTest {
        val vm = RequestsViewModel()
        vm.checkAvailability(Server().client())
        assertFalse(vm.state.value.unavailable)
    }

    @Test
    fun `dependency unavailable on a read marks the queue unavailable`() = runTest {
        val server = Server().apply { listStatus = HttpStatusCode.ServiceUnavailable }
        val vm = RequestsViewModel()
        vm.load(server.client())
        assertTrue(vm.state.value.unavailable)
        assertTrue(vm.state.value.hasLoaded)
    }

    @Test
    fun `another failure is not unavailable`() = runTest {
        val server = Server().apply { listStatus = HttpStatusCode.InternalServerError; listSlug = "internal_error" }
        val vm = RequestsViewModel()
        vm.load(server.client())
        assertFalse(vm.state.value.unavailable)
    }

    @Test
    fun `a failed refresh keeps the rows and says when they were last updated`() = runTest {
        val server = Server()
        val vm = RequestsViewModel()
        vm.clock = { Instant.parse("2026-11-14T14:32:00Z") }
        vm.load(server.client())
        assertFalse(vm.state.value.refreshFailed)
        assertEquals(Instant.parse("2026-11-14T14:32:00Z"), vm.state.value.lastUpdated)

        vm.clock = { Instant.parse("2026-11-14T14:40:00Z") }
        server.listStatus = HttpStatusCode.InternalServerError
        server.listSlug = "internal_error"
        vm.refresh(server.client())

        assertTrue(vm.state.value.refreshFailed)
        assertEquals(3, vm.state.value.requests.size)
        assertEquals("the time is of the last good read", Instant.parse("2026-11-14T14:32:00Z"), vm.state.value.lastUpdated)

        server.listStatus = HttpStatusCode.OK
        vm.refresh(server.client())
        assertFalse(vm.state.value.refreshFailed)
        assertEquals(Instant.parse("2026-11-14T14:40:00Z"), vm.state.value.lastUpdated)
    }
}
