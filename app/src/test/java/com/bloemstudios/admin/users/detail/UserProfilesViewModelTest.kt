package com.bloemstudios.admin.users.detail

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Profiles tab: add, rename, set or remove a PIN, delete, through the admin profile routes. */
class UserProfilesViewModelTest {
    private class Server {
        val calls = mutableListOf<Triple<HttpMethod, String, String>>()
        var profiles = mutableListOf(
            """{"id":"p1","name":"Alex","has_pin":true,"is_primary":true,"is_child":false,"last_seen_at":"2026-11-14T10:00:00Z"}""",
            """{"id":"p2","name":"Kids","has_pin":false,"is_primary":false,"is_child":true,"last_seen_at":null}""",
        )
        var refuse: Pair<HttpStatusCode, String>? = null
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        fun client() = ApiClient("https://example.test", null, MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            calls += Triple(request.method, request.url.encodedPath, body)
            val refusal = refuse
            when {
                request.method == HttpMethod.Get -> respond("""{"items":[${profiles.joinToString(",")}],"page":{"has_more":false}}""", HttpStatusCode.OK, json)
                refusal != null -> respond(V2Fixtures.problem(refusal.first.value, if (refusal.first == HttpStatusCode.Forbidden) "permission_denied" else "conflict", refusal.second), refusal.first, json)
                request.method == HttpMethod.Post -> respond("""{"id":"p3","name":"x"}""", HttpStatusCode.Created, json)
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent, json)
                else -> respond("""{"id":"p1","name":"x"}""", HttpStatusCode.OK, json)
            }
        })

        val writes get() = calls.filter { it.first != HttpMethod.Get }
    }

    private fun model() = UserProfilesViewModel(serverName = null)

    @Test
    fun `reads the account's profiles with their PIN, primary and kids flags`() = runTest {
        val server = Server()
        val vm = model()
        vm.load(server.client(), "7")
        val loaded = (vm.profiles.value as Section.Loaded).value
        assertEquals(listOf("Alex", "Kids"), loaded.map { it.name })
        assertTrue(loaded[0].has_pin && loaded[0].is_primary)
        assertTrue(loaded[1].is_child)
        assertEquals("/api/v2/admin/users/7/profiles", server.calls.first().second)
    }

    @Test
    fun `add posts the trimmed name and reads the list again`() = runTest {
        val server = Server()
        val vm = model()
        vm.load(server.client(), "7")

        assertTrue(vm.add(server.client(), "7", "  Maya  "))

        val write = server.writes.single()
        assertEquals(HttpMethod.Post, write.first)
        assertEquals("/api/v2/admin/users/7/profiles", write.second)
        assertEquals("""{"name":"Maya"}""", write.third)
        assertEquals("the list was read again", 2, server.calls.count { it.first == HttpMethod.Get })
        assertEquals(ProfileNotice.Added, vm.notice.value)
    }

    @Test
    fun `rename patches only the name`() = runTest {
        val server = Server()
        val vm = model()
        assertTrue(vm.rename(server.client(), "7", "p2", " Little ones "))
        val write = server.writes.single()
        assertEquals(HttpMethod.Patch, write.first)
        assertEquals("/api/v2/admin/users/7/profiles/p2", write.second)
        assertEquals("""{"name":"Little ones"}""", write.third)
        assertEquals(ProfileNotice.Renamed, vm.notice.value)
    }

    @Test
    fun `set PIN sends four digits`() = runTest {
        val server = Server()
        val vm = model()
        assertTrue(vm.setPin(server.client(), "7", "p1", "0420"))
        assertEquals("""{"pin":"0420"}""", server.writes.single().third)
        assertEquals(ProfileNotice.PinSet, vm.notice.value)
    }

    @Test
    fun `a PIN that is not four digits is not sent`() = runTest {
        val server = Server()
        val vm = model()
        for (bad in listOf("", "123", "12345", "12a4", " 123")) assertFalse(vm.setPin(server.client(), "7", "p1", bad))
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `remove PIN sends a null pin, which is how the server clears it`() = runTest {
        val server = Server()
        val vm = model()
        assertTrue(vm.removePin(server.client(), "7", "p1"))
        val write = server.writes.single()
        assertEquals(HttpMethod.Patch, write.first)
        assertEquals("""{"pin":null}""", write.third)
        assertEquals(ProfileNotice.PinRemoved, vm.notice.value)
    }

    @Test
    fun `delete removes the profile`() = runTest {
        val server = Server()
        val vm = model()
        assertTrue(vm.delete(server.client(), "7", "p2"))
        val write = server.writes.single()
        assertEquals(HttpMethod.Delete, write.first)
        assertEquals("/api/v2/admin/users/7/profiles/p2", write.second)
        assertEquals(ProfileNotice.Deleted, vm.notice.value)
    }

    @Test
    fun `a blank or over long name is not sent`() = runTest {
        val server = Server()
        val vm = model()
        assertFalse(vm.add(server.client(), "7", "   "))
        assertFalse(vm.rename(server.client(), "7", "p1", ""))
        assertFalse(vm.add(server.client(), "7", "x".repeat(65)))
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `a refusal keeps the server's words and the list unchanged`() = runTest {
        val server = Server()
        val vm = model()
        vm.load(server.client(), "7")
        server.refuse = HttpStatusCode.Conflict to "The account is at its profile limit"

        assertFalse(vm.add(server.client(), "7", "Maya"))

        assertEquals(ErrorText.Raw("The account is at its profile limit"), vm.error.value)
        assertNull(vm.notice.value)
        assertEquals("no second read after a refusal", 1, server.calls.count { it.first == HttpMethod.Get })
        assertEquals(2, (vm.profiles.value as Section.Loaded).value.size)
    }

    @Test
    fun `the next action clears the last notice and error`() = runTest {
        val server = Server()
        val vm = model()
        server.refuse = HttpStatusCode.Forbidden to "Only the server Owner may change this account's profiles"
        vm.delete(server.client(), "7", "p2")
        assertTrue(vm.error.value != null)
        server.refuse = null
        vm.delete(server.client(), "7", "p2")
        assertNull(vm.error.value)
        assertEquals(ProfileNotice.Deleted, vm.notice.value)
    }

    @Test
    fun `PIN and name rules`() {
        assertTrue(isValidProfilePin("0000"))
        assertFalse(isValidProfilePin("٠٠٠٠"))
        assertEquals("Maya", cleanProfileName("  Maya "))
        assertNull(cleanProfileName("  "))
        assertNull(cleanProfileName("a".repeat(65)))
        assertEquals("a".repeat(64), cleanProfileName("a".repeat(64)))
    }
}
