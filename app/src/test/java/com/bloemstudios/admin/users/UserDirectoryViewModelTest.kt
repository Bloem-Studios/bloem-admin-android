package com.bloemstudios.admin.users

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ErrorText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDirectoryViewModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun user(id: String, name: String, email: String = "$name@example.test") =
        """{"id":"$id","username":"$name","email":"$email","role":"user","enabled":true}"""
    private fun page(vararg users: String) = """{"items":[${users.joinToString(",")}]}"""
    private fun profiles(vararg pairs: Pair<String, String>) =
        """{"items":[${pairs.joinToString(",") { """{"id":"${it.first}","name":"${it.second}"}""" }}]}"""

    @Test fun `users load sorted by name, ignoring case`() = runTest {
        val vm = UserDirectoryViewModel()
        val client = ApiClient("https://example.test", null, MockEngine { respond(page(user("3", "tomas"), user("1", "Amira"), user("2", "jonas")), HttpStatusCode.OK, json) })
        vm.loadUsers(client)
        assertEquals(listOf("Amira", "jonas", "tomas"), vm.users.value.map { it.username })
        assertNull(vm.usersError.value)
    }

    @Test fun `a second load for the same client does not ask again`() = runTest {
        var calls = 0
        val vm = UserDirectoryViewModel()
        val client = ApiClient("https://example.test", null, MockEngine { calls++; respond(page(user("1", "maria")), HttpStatusCode.OK, json) })
        vm.loadUsers(client)
        vm.loadUsers(client)
        assertEquals(1, calls)
    }

    @Test fun `a different client reloads and drops the old server's accounts`() = runTest {
        val vm = UserDirectoryViewModel()
        vm.loadUsers(ApiClient("https://a.example.test", null, MockEngine { respond(page(user("1", "maria")), HttpStatusCode.OK, json) }))
        vm.loadUsers(ApiClient("https://b.example.test", null, MockEngine { respond(page(user("9", "zoe")), HttpStatusCode.OK, json) }))
        assertEquals(listOf("zoe"), vm.users.value.map { it.username })
    }

    @Test fun `a failed load keeps an error and allows a retry`() = runTest {
        var fail = true
        val vm = UserDirectoryViewModel()
        val client = ApiClient("https://example.test", null, MockEngine {
            if (fail) respond("{}", HttpStatusCode.InternalServerError, json) else respond(page(user("1", "maria")), HttpStatusCode.OK, json)
        })
        vm.loadUsers(client)
        assertEquals(ErrorText.Http(R.string.error_could_not_load_users, 500), vm.usersError.value)
        assertTrue(vm.users.value.isEmpty())
        fail = false
        vm.loadUsers(client)
        assertNull(vm.usersError.value)
        assertEquals(1, vm.users.value.size)
    }

    @Test fun `profiles load for the chosen user and clear when none is chosen`() = runTest {
        val seen = mutableListOf<String>()
        val vm = UserDirectoryViewModel()
        val client = ApiClient("https://example.test", null, MockEngine { request ->
            seen += request.url.encodedPath
            respond(profiles("p1" to "Living room", "p2" to "Maria"), HttpStatusCode.OK, json)
        })
        vm.loadProfiles(client, "7")
        assertEquals(listOf("/api/v2/admin/users/7/profiles"), seen)
        assertEquals(listOf("Living room", "Maria"), vm.profiles.value.map { it.name })
        vm.loadProfiles(client, null)
        assertTrue(vm.profiles.value.isEmpty())
        assertEquals(1, seen.size)
    }

    @Test fun `a profile failure says what to do and leaves the list empty`() = runTest {
        val vm = UserDirectoryViewModel()
        val client = ApiClient("https://example.test", null, MockEngine { respond("{}", HttpStatusCode.InternalServerError, json) })
        vm.loadProfiles(client, "7")
        assertTrue(vm.profiles.value.isEmpty())
        assertEquals(ErrorText.Http(R.string.error_could_not_load_profiles, 500), vm.profilesError.value)
    }
}
