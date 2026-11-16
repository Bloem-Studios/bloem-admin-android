package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.users.AggregateUsersViewModel
import com.bloemstudios.admin.users.UsersTestServer
import com.bloemstudios.admin.users.UsersViewModel
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferencesViewModelTest {
    private fun page(vararg rows: String) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""
    private fun row(key: String, scope: String, value: String, extra: String = "") =
        """{"key":"$key","scope":"$scope","value":$value,"revision":3$extra}"""

    private val contract = """{"api_version":1,"revision":28,"definitions":[
        {"key":"playback.subtitle_mode","persistence":"remote","allowed_scopes":["profile","profile_device"],"value_schema":{"type":"enum","values":[{"value":"auto","label":"Auto"},{"value":"always","label":"Always on"}]},"default_value":"auto","category":"playback","label":"Subtitles"},
        {"key":"playback.show_forced_subtitles","persistence":"remote","allowed_scopes":["profile","profile_device"],"value_schema":{"type":"boolean"},"default_value":true,"category":"playback","label":"Forced subtitles"},
        {"key":"player.playback_speed","persistence":"remote","allowed_scopes":["profile_device"],"value_schema":{"type":"number","minimum":0.25,"maximum":3.0},"default_value":1.0,"category":"player","label":"Speed"}
    ]}"""

    private fun server(vararg rows: String): UsersTestServer {
        val s = UsersTestServer(users = listOf(V2Fixtures.adminUser("1", "alice")))
        s.routes["/api/v2/admin/users/1/settings/values"] = page(*rows)
        s.routes["/api/v2/admin/users/1/profiles"] = page("""{"id":"p1","name":"Main","has_pin":false,"is_primary":true,"is_child":false,"last_seen_at":null,"avatar":"","avatar_source":"none"}""")
        s.routes["/api/v2/admin/users/1/devices"] = page(V2Fixtures.adminUserDevice("d1", "Shield TV"))
        s.routes["/api/v2/libraries"] = page("""{"id":"3","name":"TV Shows","type":"show"}""")
        s.routes["/api/v2/settings/contract"] = contract
        return s
    }

    private fun single(s: UsersTestServer, devices: Boolean = true) =
        UserPreferencesViewModel(SingleServerUserAdmin(UsersViewModel(), s.client()), "1", devices)

    private fun ready(vm: UserPreferencesViewModel) = vm.state.value as PreferencesState.Ready

    @Test
    fun `loading reads the settings with the names and the contract, and devices only when offered`() = runTest {
        val s = server(row("playback.subtitle_mode", "profile", "\"always\"", ""","profile_id":"p1""""))
        val vm = single(s)

        vm.load()

        val levels = ready(vm).levels
        assertEquals(listOf("profile.p1", "device.p1.d1"), levels.map { it.id })
        assertEquals("Always on", valueText(ready(vm).definition("playback.subtitle_mode"), levels[0].entries.single().value, "On", "Off", "-"))
        assertTrue(s.calls.any { it.path == "/api/v2/admin/users/1/devices" })

        val without = server()
        single(without, devices = false).load()
        assertTrue(without.calls.none { it.path.endsWith("/devices") })
    }

    @Test
    fun `an unreadable contract leaves every value shown and none editable`() = runTest {
        val s = server(row("playback.subtitle_mode", "profile", "\"always\"", ""","profile_id":"p1""""))
        s.routes.remove("/api/v2/settings/contract")
        val vm = single(s)

        vm.load()

        assertNull(ready(vm).definitions)
        assertEquals(ValueEditor.ViewOnly, editorFor(ready(vm).definition("playback.subtitle_mode")))
        assertEquals(1, ready(vm).levels.first().entries.size)
    }

    @Test
    fun `unreadable settings fail the tab with the server's message, and a failed re-read keeps what is shown`() = runTest {
        var fail = true
        val client = com.bloemstudios.admin.network.ApiClient("https://example.test", "tok", io.ktor.client.engine.mock.MockEngine { request ->
            val json = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
            when {
                request.url.encodedPath.endsWith("/settings/values") && fail -> respond(
                    V2Fixtures.problem(500, "internal", "The server hit an error."), HttpStatusCode.InternalServerError,
                    io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/problem+json")
                )
                else -> respond(page(), HttpStatusCode.OK, json)
            }
        })
        val vm = UserPreferencesViewModel(SingleServerUserAdmin(UsersViewModel(), client), "1", false)

        vm.load()
        assertEquals(PreferencesState.Failed(ErrorText.Raw("The server hit an error.")), vm.state.value)

        fail = false
        vm.load()
        assertTrue(vm.state.value is PreferencesState.Ready)
        fail = true
        vm.load()
        assertTrue(vm.state.value is PreferencesState.Ready)
    }

    @Test
    fun `changing a value writes that one row and reads the settings again`() = runTest {
        val s = server(row("playback.subtitle_mode", "profile_device", "\"auto\"", ""","profile_id":"p1","device_id":"d1""""))
        val vm = single(s)
        vm.load()
        s.calls.clear()
        s.routes["/api/v2/admin/users/1/settings/values"] = page(row("playback.subtitle_mode", "profile_device", "\"always\"", ""","profile_id":"p1","device_id":"d1""""))

        val entry = ready(vm).levels.first { it.kind == LevelKind.Device }.entries.single()
        assertTrue(vm.set(entry.key, entry.identity(), JsonPrimitive("always")))

        val write = s.writes.single()
        assertEquals("PUT", write.method.value)
        assertEquals("/api/v2/admin/users/1/settings/values/playback.subtitle_mode", write.path)
        assertEquals("scope=profile_device&profile_id=p1&device_id=d1", write.query)
        assertEquals("""{"value":"always"}""", write.body)
        assertNull(write.ifMatch)
        assertEquals("always", (ready(vm).levels.first { it.kind == LevelKind.Device }.entries.single().value as JsonPrimitive).content)
    }

    @Test
    fun `adding starts at what the setting replaces and skips what is already set`() = runTest {
        val s = server(
            row("playback.subtitle_mode", "profile", "\"always\"", ""","profile_id":"p1""""),
            row("playback.show_forced_subtitles", "profile_device", "false", ""","profile_id":"p1","device_id":"d1"""")
        )
        val vm = single(s)
        vm.load()
        s.calls.clear()
        val device = ready(vm).levels.first { it.kind == LevelKind.Device }

        // Subtitles: the profile's own value. Forced subtitles: already set here, so left alone. Speed: the default.
        assertTrue(vm.add(device, listOf("playback.subtitle_mode", "playback.show_forced_subtitles", "player.playback_speed", "not.in.contract")))

        assertEquals(
            listOf("""{"value":"always"}""", """{"value":1.0}"""),
            s.writes.map { it.body }
        )
        assertEquals(
            listOf("/api/v2/admin/users/1/settings/values/playback.subtitle_mode", "/api/v2/admin/users/1/settings/values/player.playback_speed"),
            s.writes.map { it.path }
        )
    }

    @Test
    fun `removing deletes exactly the row and a refused write is shown and still re-read`() = runTest {
        val s = server(row("playback.show_forced_subtitles", "profile_library", "false", ""","profile_id":"p1","library_id":"3""""))
        val vm = single(s)
        vm.load()
        s.calls.clear()
        val entry = ready(vm).levels.first { it.kind == LevelKind.Library }.entries.single()

        assertTrue(vm.remove(entry))
        assertEquals("DELETE", s.writes.single().method.value)
        assertEquals("scope=profile_library&profile_id=p1&library_id=3", s.writes.single().query)

        s.writeStatus = HttpStatusCode.UnprocessableEntity
        s.writeBody = V2Fixtures.problem(422, "validation_failed", "value is not allowed")
        s.calls.clear()
        assertTrue(!vm.set(entry.key, entry.identity(), JsonPrimitive(true)))
        assertEquals(ErrorText.Raw("value is not allowed"), vm.error.value)
        assertTrue(s.calls.any { it.method.value == "GET" && it.path == "/api/v2/admin/users/1/settings/values" })
        vm.dismissError()
        assertNull(vm.error.value)
    }

    @Test
    fun `in All servers a write goes to that server only and a failure names it`() = runTest {
        val a = server(row("playback.show_forced_subtitles", "profile", "true", ""","profile_id":"p1""""))
        val b = server()
        val serverA = AdminServer("srv-a", "https://a.test", "Server A")
        val clients = mapOf("srv-a" to a.client(), "srv-b" to b.client())
        val admin = AggregateUserAdmin(AggregateUsersViewModel(), serverA) { clients.getValue(it.id) }
        val vm = UserPreferencesViewModel(admin, "1", true)
        vm.load()
        val entry = ready(vm).levels.first().entries.single()

        assertTrue(vm.set(entry.key, entry.identity(), JsonPrimitive(false)))
        assertEquals(1, a.writes.size)
        assertTrue(b.calls.isEmpty())

        a.writeStatus = HttpStatusCode.UnprocessableEntity
        a.writeBody = V2Fixtures.problem(422, "validation_failed", "That value is not allowed.")
        assertTrue(!vm.set(entry.key, entry.identity(), JsonPrimitive(true)))
        assertEquals(ErrorText.Prefixed("Server A", ErrorText.Raw("That value is not allowed.")), vm.error.value)
        assertTrue(b.calls.isEmpty())
        assertNotNull(vm.state.value)
    }

    @Test
    fun `a starting value is always one the setting accepts`() {
        fun def(json: String) = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SettingDefinition>(json)
        val none = ReplacedValue(null, null)
        assertEquals(JsonPrimitive(false), startingValue(none, def("""{"key":"k","value_schema":{"type":"boolean"}}""")))
        assertEquals(JsonPrimitive("a"), startingValue(none, def("""{"key":"k","value_schema":{"type":"enum","values":[{"value":"x","deprecated":true},{"value":"a"}]}}""")))
        assertEquals(JsonPrimitive(100L), startingValue(none, def("""{"key":"k","value_schema":{"type":"integer","minimum":100}}""")))
        assertEquals(kotlinx.serialization.json.JsonNull, startingValue(none, def("""{"key":"k","value_schema":{"type":"string","nullable":true}}""")))
        assertEquals(JsonPrimitive(7), startingValue(ReplacedValue("Main", JsonPrimitive(7)), def("""{"key":"k","value_schema":{"type":"integer"}}""")))
    }
}
