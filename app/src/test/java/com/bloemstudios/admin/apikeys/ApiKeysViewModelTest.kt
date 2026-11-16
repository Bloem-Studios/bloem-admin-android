package com.bloemstudios.admin.apikeys

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.users.AdminUser
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The API keys page: list with Load more, and Revoke with the version it read, as the web's AdminApiKeys does. */
class ApiKeysViewModelTest {
    private fun key(id: String, user: String = "7", scopes: String = "[]", lastUsed: String? = null) =
        """{"id":"$id","user_id":"$user","label":"Key $id","key_prefix":"bk_$id","rate_tier":"standard","scopes":$scopes,"created_at":"2026-11-01T10:00:00Z","username":"alex"${if (lastUsed != null) ""","last_used_at":"$lastUsed"""" else ""}}"""

    private fun page(vararg keys: String, next: String? = null) =
        """{"items":[${keys.joinToString(",")}],"page":{"has_more":${next != null}${if (next != null) ""","next_cursor":"$next"""" else ""}}}"""

    private class Server(var capability: String = """{"available":true,"state":"available","revision":"1","guarded_configuration":true,"scopes":[],"rate_tiers":["standard"]}""") {
        val pages = mutableListOf<String>()
        val requests = mutableListOf<Triple<HttpMethod, Url, String?>>()
        var capabilityStatus = HttpStatusCode.OK
        var deleteStatus = HttpStatusCode.NoContent
        var etag = "\"rev-3\""
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        fun client() = ApiClient("https://example.test", null, MockEngine { request ->
            requests += Triple(request.method, request.url, request.headers[HttpHeaders.IfMatch])
            val path = request.url.encodedPath
            when {
                path == "/api/v2/admin/api-keys/capabilities" ->
                    if (capabilityStatus == HttpStatusCode.OK) respond(capability, HttpStatusCode.OK, json)
                    else respond(V2Fixtures.problem(capabilityStatus.value, "not_found", "Not found"), capabilityStatus, json)
                request.method == HttpMethod.Get && path == "/api/v2/admin/api-keys" -> respond(pages.removeAt(0), HttpStatusCode.OK, json)
                request.method == HttpMethod.Get -> respond(
                    """{"id":"k1","user_id":"7","label":"Key k1","key_prefix":"bk_k1","rate_tier":"standard","scopes":[],"created_at":"2026-11-01T10:00:00Z"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag)),
                )
                request.method == HttpMethod.Delete ->
                    if (deleteStatus == HttpStatusCode.NoContent) respond("", HttpStatusCode.NoContent)
                    else respond(V2Fixtures.problem(deleteStatus.value, "precondition_failed", "The API key changed; reload before saving."), deleteStatus, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
    }

    @Test
    fun `reads the capability, then the first page`() = runTest {
        val server = Server().apply { pages += page(key("k1"), key("k2"), next = "c1") }
        val vm = ApiKeysViewModel()
        vm.load(server.client())

        assertEquals(ApiKeysAvailability.Available, vm.availability.value)
        assertEquals(listOf("k1", "k2"), vm.state.value.keys.map { it.id })
        assertTrue(vm.state.value.hasMore)
        assertEquals("/api/v2/admin/api-keys/capabilities", server.requests.first().second.encodedPath)
        assertEquals("50", server.requests.last().second.parameters["limit"])
    }

    @Test
    fun `Load more appends the next page and skips a key it already has`() = runTest {
        val server = Server().apply { pages += page(key("k1"), key("k2"), next = "c1"); pages += page(key("k2"), key("k3")) }
        val vm = ApiKeysViewModel()
        vm.load(server.client())
        vm.loadMore(server.client())

        assertEquals(listOf("k1", "k2", "k3"), vm.state.value.keys.map { it.id })
        assertFalse(vm.state.value.hasMore)
        assertEquals("c1", server.requests.last().second.parameters["cursor"])
    }

    @Test
    fun `a server that says the page is not available has no list to read`() = runTest {
        val server = Server(capability = """{"available":false,"state":"not_configured","revision":"1","guarded_configuration":false,"scopes":[],"rate_tiers":[]}""")
        val vm = ApiKeysViewModel()
        vm.load(server.client())
        assertEquals(ApiKeysAvailability.Unavailable, vm.availability.value)
        assertEquals("only the capability was asked", 1, server.requests.size)
    }

    @Test
    fun `an older server without the route is unavailable, not failed`() = runTest {
        val server = Server().apply { capabilityStatus = HttpStatusCode.NotFound }
        val vm = ApiKeysViewModel()
        vm.load(server.client())
        assertEquals(ApiKeysAvailability.Unavailable, vm.availability.value)
    }

    @Test
    fun `revoke reads the key's version and deletes with If-Match`() = runTest {
        val server = Server().apply { pages += page(key("k1"), key("k2")) }
        val vm = ApiKeysViewModel()
        vm.load(server.client())

        assertTrue(vm.revoke(server.client(), vm.state.value.keys.first()))

        val get = server.requests.first { it.first == HttpMethod.Get && it.second.encodedPath == "/api/v2/admin/api-keys/k1" }
        val delete = server.requests.single { it.first == HttpMethod.Delete }
        assertEquals("/api/v2/admin/api-keys/k1", delete.second.encodedPath)
        assertEquals("\"rev-3\"", delete.third)
        assertEquals(listOf("k2"), vm.state.value.keys.map { it.id })
        assertEquals(RevokeNotice.Revoked("Key k1"), vm.notice.value)
        assertNull(get.third)
    }

    @Test
    fun `a key someone else changed is refused, and the list is read again`() = runTest {
        val server = Server().apply { pages += page(key("k1")); pages += page(key("k1")) ; deleteStatus = HttpStatusCode.PreconditionFailed }
        val vm = ApiKeysViewModel()
        vm.load(server.client())

        assertFalse(vm.revoke(server.client(), vm.state.value.keys.single()))

        assertEquals(ErrorText.Raw("The API key changed; reload before saving."), vm.error.value)
        assertEquals("the page was read again", 2, server.requests.count { it.first == HttpMethod.Get && it.second.encodedPath == "/api/v2/admin/api-keys" })
        assertEquals(listOf("k1"), vm.state.value.keys.map { it.id })
    }

    @Test
    fun `a key that is already gone leaves the list`() = runTest {
        val server = Server().apply { pages += page(key("k1")); deleteStatus = HttpStatusCode.NotFound }
        val vm = ApiKeysViewModel()
        vm.load(server.client())
        vm.revoke(server.client(), vm.state.value.keys.single())
        assertTrue("the delete answered not found: nothing left to revoke", vm.state.value.keys.isEmpty())
    }

    // --- who may revoke ------------------------------------------------------------------------------------------

    private fun account(id: String, role: String = "user", owner: Boolean = false) =
        AdminUser(id = id, username = "u$id", email = "", role = role, enabled = true, is_owner = owner)

    private fun apiKey(user: String) = AdminApiKey(id = "k", user_id = user, label = "k", key_prefix = "bk_", rate_tier = "standard", created_at = "2026-11-01T10:00:00Z")

    @Test
    fun `an admin revokes a user's key but not another admin's or the owner's`() {
        val accounts = listOf(account("1", "admin"), account("2", "user"), account("3", "admin"), account("4", "admin", owner = true))
        assertTrue(canRevokeKey(apiKey("2"), accounts, viewerId = "1", viewerIsOwner = false))
        assertTrue("an admin's own key", canRevokeKey(apiKey("1"), accounts, viewerId = "1", viewerIsOwner = false))
        assertFalse(canRevokeKey(apiKey("3"), accounts, viewerId = "1", viewerIsOwner = false))
        assertFalse(canRevokeKey(apiKey("4"), accounts, viewerId = "1", viewerIsOwner = false))
        assertTrue("the owner revokes an admin's key", canRevokeKey(apiKey("3"), accounts, viewerId = "4", viewerIsOwner = true))
    }

    @Test
    fun `nothing is offered until the account list is known`() {
        assertFalse(canRevokeKey(apiKey("2"), null, viewerId = "1", viewerIsOwner = false))
        assertFalse(canRevokeKey(apiKey("2"), emptyList(), viewerId = null, viewerIsOwner = false))
    }

    @Test
    fun `a key whose account is not in the list can still be revoked`() {
        assertTrue(canRevokeKey(apiKey("99"), listOf(account("1", "admin")), viewerId = "1", viewerIsOwner = false))
    }

    @Test
    fun `scope text says full access for an empty list`() {
        assertEquals(emptyList<String>(), apiKey("1").scopes)
        assertTrue(apiKey("1").hasFullAccess)
        assertFalse(apiKey("1").copy(scopes = listOf("library:read")).hasFullAccess)
    }
}
