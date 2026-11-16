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
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request queue's view model, against a server that answers like the real one (counts, pages, accounts, actions). */
class RequestQueueViewModelTest {
    private class Server(
        var needsApproval: Int = 2,
        var pages: MutableList<String> = mutableListOf(),
        var users: String = V2Fixtures.adminUsersPage(V2Fixtures.adminUser("68", "alex")),
        var usersStatus: HttpStatusCode = HttpStatusCode.OK,
        var countsStatus: HttpStatusCode = HttpStatusCode.OK,
        var actionStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        val gets = mutableListOf<Url>()
        val posts = mutableListOf<Pair<String, String>>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        fun client() = ApiClient("https://example.test", null, MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post -> {
                    posts += path to request.body.toByteArray().decodeToString()
                    if (actionStatus == HttpStatusCode.OK) respond(V2Fixtures.mediaRequest("1"), HttpStatusCode.OK, json)
                    else respond(V2Fixtures.problem(409, "conflict", "Already approved"), actionStatus, json)
                }
                path == "/api/v2/admin/requests/counts" ->
                    if (countsStatus == HttpStatusCode.OK) respond("""{"needs_approval":$needsApproval,"in_progress":3,"failed":1,"done":7}""", HttpStatusCode.OK, json)
                    else respond(V2Fixtures.problem(503, "dependency_unavailable", "Counts down"), countsStatus, json)
                path == "/api/v2/admin/users" ->
                    if (usersStatus == HttpStatusCode.OK) respond(users, HttpStatusCode.OK, json)
                    else respond(V2Fixtures.problem(403, "forbidden", "No"), usersStatus, json)
                else -> {
                    gets += request.url
                    respond(if (pages.isEmpty()) V2Fixtures.mediaRequestsPage() else pages.removeAt(0), HttpStatusCode.OK, json)
                }
            }
        })

        fun page(vararg ids: String, status: String = "pending", next: String? = null) =
            V2Fixtures.mediaRequestsPage(*ids.map { V2Fixtures.mediaRequest(it, status, "active", "T$it", """"requested_by_user_id":"68"""") }.toTypedArray(), nextCursor = next)
    }

    @Test
    fun `opens on needs approval when anything waits`() = runTest {
        val server = Server(needsApproval = 2).apply { pages += page("1", "2") }
        val vm = RequestsViewModel()

        vm.load(server.client())

        assertEquals(RequestQueueView.NeedsApproval, vm.state.value.view)
        assertEquals(2, vm.state.value.counts?.needs_approval)
        assertEquals(listOf("1", "2"), vm.state.value.requests.map { it.id })
        assertEquals("needs_approval", server.gets.last().parameters["view"])
        assertEquals("50", server.gets.last().parameters["limit"])
        assertEquals("names come from the account list", mapOf("68" to "alex"), vm.state.value.requesterNames)
    }

    @Test
    fun `opens on in progress when nothing waits and keeps a picked view`() = runTest {
        val server = Server(needsApproval = 0).apply { pages += page("1", status = "approved"); pages += page("9", status = "queued") }
        val vm = RequestsViewModel()
        val client = server.client()

        vm.load(client)
        assertEquals(RequestQueueView.InProgress, vm.state.value.view)

        vm.select(RequestQueueView.Failed)
        vm.load(client)
        assertEquals(RequestQueueView.Failed, vm.state.value.view)
        assertEquals("failed", server.gets.last().parameters["view"])
    }

    @Test
    fun `a counts change after a view was picked does not move the view`() = runTest {
        val server = Server(needsApproval = 0)
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)
        vm.select(RequestQueueView.Done)
        server.needsApproval = 4
        vm.load(client)
        assertEquals(RequestQueueView.Done, vm.state.value.view)
    }

    @Test
    fun `filters go to the server`() = runTest {
        val server = Server(needsApproval = 1).apply { pages += page() }
        val vm = RequestsViewModel()
        vm.setSearch("  dune ")
        vm.setMedia(RequestMediaFilter.Series)
        vm.filterByUser("68")

        vm.load(server.client())

        val sent = server.gets.last().parameters
        assertEquals("dune", sent["q"])
        assertEquals("series", sent["media_type"])
        assertEquals("68", sent["requested_by_user_id"])
        assertTrue(vm.state.value.isFiltered)
        assertTrue(vm.state.value.requests.isEmpty())

        vm.setSearch("   ")
        vm.setMedia(RequestMediaFilter.All)
        vm.clearUserFilter()
        assertFalse(vm.state.value.isFiltered)
    }

    @Test
    fun `an unfiltered read sends no filter parameters`() = runTest {
        val server = Server()
        RequestsViewModel().load(server.client())
        val sent = server.gets.last().parameters
        assertNull(sent["q"]); assertNull(sent["media_type"]); assertNull(sent["requested_by_user_id"]); assertNull(sent["cursor"])
    }

    @Test
    fun `load more follows the cursor and keeps the rows`() = runTest {
        val server = Server(needsApproval = 3).apply { pages += page("1", "2", next = "c1"); pages += page("2", "3") }
        val vm = RequestsViewModel()
        val client = server.client()

        vm.load(client)
        assertEquals("c1", vm.state.value.nextCursor)
        vm.loadMore(client)

        assertEquals("a repeated row is not shown twice", listOf("1", "2", "3"), vm.state.value.requests.map { it.id })
        assertNull(vm.state.value.nextCursor)
        assertEquals("c1", server.gets.last().parameters["cursor"])
        assertEquals("needs_approval", server.gets.last().parameters["view"])
    }

    @Test
    fun `a failed next page keeps the rows shown`() = runTest {
        var calls = 0
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/api/v2/admin/requests/counts" -> respond("""{"needs_approval":3}""", HttpStatusCode.OK, json)
                "/api/v2/admin/users" -> respond("""{"items":[]}""", HttpStatusCode.OK, json)
                else -> if (calls++ == 0) respond(V2Fixtures.mediaRequestsPage(V2Fixtures.mediaRequest("1"), nextCursor = "c1"), HttpStatusCode.OK, json)
                else respond(V2Fixtures.problem(503, "dependency_unavailable", "down"), HttpStatusCode.ServiceUnavailable, json)
            }
        })
        val vm = RequestsViewModel()

        vm.load(client)
        vm.loadMore(client)

        assertEquals(listOf("1"), vm.state.value.requests.map { it.id })
        assertTrue(vm.state.value.moreFailed)
        assertFalse(vm.state.value.loadingMore)
        assertNull(vm.loadError.value)
    }

    @Test
    fun `a failed queue says so and a later read recovers`() = runTest {
        val server = Server(countsStatus = HttpStatusCode.ServiceUnavailable)
        val vm = RequestsViewModel()
        val client = server.client()

        vm.load(client)
        assertTrue(vm.state.value.hasLoaded)
        assertEquals(ErrorText.Raw("Counts down"), vm.loadError.value)

        server.countsStatus = HttpStatusCode.OK
        vm.load(client)
        assertNull(vm.loadError.value)
    }

    @Test
    fun `a failed refresh keeps the rows already shown`() = runTest {
        val server = Server().apply { pages += page("1") }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)
        server.countsStatus = HttpStatusCode.ServiceUnavailable

        vm.load(client)

        assertEquals(listOf("1"), vm.state.value.requests.map { it.id })
        assertNotNull(vm.loadError.value)
    }

    @Test
    fun `without the account list the requester reads as the account number`() = runTest {
        val server = Server(usersStatus = HttpStatusCode.Forbidden).apply { pages += page("1") }
        val vm = RequestsViewModel()
        vm.load(server.client())
        assertTrue(vm.state.value.requesterNames.isEmpty())
        assertEquals(listOf("1"), vm.state.value.requests.map { it.id })
    }

    @Test
    fun `an action posts then reloads the view`() = runTest {
        val server = Server(needsApproval = 1).apply { pages += page("1"); pages += page() }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)

        val ok = vm.decline(vm.state.value.requests[0], client, "  duplicate ")

        assertTrue(ok)
        assertNull(vm.actionError.value)
        assertEquals("/api/v2/admin/requests/1/decline" to """{"reason":"duplicate"}""", server.posts.single())
        assertTrue("the declined request left the view", vm.state.value.requests.isEmpty())
        assertEquals(2, server.gets.size)
    }

    @Test
    fun `approving sends no reason even when one is passed`() = runTest {
        val server = Server().apply { pages += page("1") }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)

        vm.perform(RequestQueueAction.Approve, vm.state.value.requests[0], client, "ignored")

        assertEquals("/api/v2/admin/requests/1/approve" to "{}", server.posts.single())
    }

    @Test
    fun `a blank note is not sent`() = runTest {
        val server = Server().apply { pages += page("1") }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)

        vm.cancel(vm.state.value.requests[0], client, "   ")

        assertEquals("/api/v2/admin/requests/1/cancel" to "{}", server.posts.single())
    }

    @Test
    fun `a refused action keeps the row and says why`() = runTest {
        val server = Server(actionStatus = HttpStatusCode.Conflict).apply { pages += page("1") }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)

        val ok = vm.approve(vm.state.value.requests[0], client)

        assertFalse(ok)
        assertEquals(ErrorText.Raw("Already approved"), vm.actionError.value)
        assertEquals(listOf("1"), vm.state.value.requests.map { it.id })
        assertEquals("no reload after a refusal", 1, server.gets.size)
    }

    @Test
    fun `a background refresh keeps rows read further down`() = runTest {
        val first = (1..50).map { it.toString() }.toTypedArray()
        val server = Server(needsApproval = 51).apply { pages += page(*first, next = "c1"); pages += page("51") }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)
        vm.loadMore(client)
        assertTrue(vm.state.value.hasLoadedMore)

        server.needsApproval = 60
        vm.refresh(client)

        assertEquals(51, vm.state.value.requests.size)
        assertEquals(60, vm.state.value.counts?.needs_approval)
        assertEquals("only the counts were read again", 2, server.gets.size)
    }

    @Test
    fun `a refresh with nothing more loaded reads the first page again`() = runTest {
        val server = Server().apply { pages += page("1"); pages += page("1", "2") }
        val vm = RequestsViewModel()
        val client = server.client()
        vm.load(client)
        vm.refresh(client)
        assertEquals(listOf("1", "2"), vm.state.value.requests.map { it.id })
    }
}
