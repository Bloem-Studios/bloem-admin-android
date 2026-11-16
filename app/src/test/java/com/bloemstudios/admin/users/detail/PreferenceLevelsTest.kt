package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.users.UserDevice
import com.bloemstudios.admin.users.UserDeviceProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceLevelsTest {
    private fun entry(key: String, scope: String, value: Any = true, profile: String? = null, device: String? = null, family: String? = null, library: String? = null, series: String? = null) =
        UserSettingEntry(key, scope, if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString()), 1, profile, family, device, library, series)

    private val main = UserProfile("p1", "Main")
    private val kids = UserProfile("p2", "Kids")
    private val tv = UserDevice("d1", "Shield TV", "androidtv", null, null, 1, listOf(UserDeviceProfile("p1", "Main", 1, null)))

    @Test
    fun `an account with no settings still lists each profile's All devices level`() {
        val groups = buildPreferenceLevels(emptyList(), listOf(main, kids), emptyList(), emptyMap())
        assertEquals(listOf("profile.p1", "profile.p2"), groups.map { it.key })
        assertEquals(listOf("profile.p1"), groups[0].levels.map { it.id })
        assertTrue(groups[0].levels.single().entries.isEmpty())
    }

    @Test
    fun `account-wide rows get their own group first`() {
        val groups = buildPreferenceLevels(listOf(entry("a.b", "account"), entry("c.d", "profile", profile = "p1")), listOf(main), emptyList(), emptyMap())
        assertEquals(listOf("account", "profile.p1"), groups.map { it.key })
        assertEquals(LevelKind.Account, groups[0].levels.single().kind)
    }

    @Test
    fun `a profile's levels run from all devices through devices, app families, libraries and series`() {
        val rows = listOf(
            entry("k", "profile_series", profile = "p1", series = "tv:9"),
            entry("k", "profile_library", profile = "p1", library = "3"),
            entry("k", "profile_client", profile = "p1", family = "mobile"),
            entry("k", "profile_client", profile = "p1", family = "tv"),
            entry("k", "profile_device", profile = "p1", device = "d1"),
            entry("k", "profile_device", profile = "p1", device = "ghost"),
            entry("k", "profile", profile = "p1"),
        )
        val levels = buildPreferenceLevels(rows, listOf(main), listOf(tv), mapOf("3" to "TV Shows")).single().levels
        assertEquals(
            listOf("profile.p1", "device.p1.d1", "device.p1.ghost", "client.p1.tv", "client.p1.mobile", "library.p1.3", "series.p1.tv:9"),
            levels.map { it.id }
        )
        assertEquals("Shield TV", levels[1].deviceName)
        assertNull(levels[2].deviceName) // not in the device list: named by its id in the UI
        assertEquals("TV Shows", levels.first { it.kind == LevelKind.Library }.libraryName)
    }

    @Test
    fun `a device the profile was seen on is listed even with no settings, one it was not seen on is not`() {
        val other = UserDevice("d2", "Phone", "ios", null, null, 0, listOf(UserDeviceProfile("p2", "Kids", 0, null)))
        val levels = buildPreferenceLevels(emptyList(), listOf(main), listOf(tv, other), emptyMap()).single().levels
        assertEquals(listOf("profile.p1", "device.p1.d1"), levels.map { it.id })
    }

    @Test
    fun `rows of a profile the account no longer lists still show, named by their id`() {
        val groups = buildPreferenceLevels(listOf(entry("k", "profile", profile = "p9")), listOf(main), emptyList(), emptyMap())
        assertEquals(listOf("profile.p1", "profile.p9"), groups.map { it.key })
        assertEquals("p9", groups[1].profileName)
    }

    @Test
    fun `entries follow the contract's order, then the key`() {
        val rows = listOf(entry("z", "profile", profile = "p1"), entry("a", "profile", profile = "p1"), entry("m", "profile", profile = "p1"))
        val levels = buildPreferenceLevels(rows, listOf(main), emptyList(), emptyMap(), order = mapOf("z" to 0, "m" to 1)).single().levels
        assertEquals(listOf("z", "m", "a"), levels.single().entries.map { it.key })
    }

    @Test
    fun `a setting on a device replaces the profile's value, else the app default`() {
        val def = SettingDefinition("playback.subtitle_mode", default_value = JsonPrimitive("auto"), allowed_scopes = listOf("profile", "profile_device"))
        val level = buildPreferenceLevels(emptyList(), listOf(main), listOf(tv), emptyMap()).single().levels.first { it.kind == LevelKind.Device }
        val profileRows = listOf(entry("playback.subtitle_mode", "profile", "always", profile = "p1"))
        assertEquals(ReplacedValue("Main", JsonPrimitive("always")), replacedValue(level, def.key, profileRows, def))
        assertEquals(ReplacedValue(null, JsonPrimitive("auto")), replacedValue(level, def.key, emptyList(), def))
        val profile = buildPreferenceLevels(emptyList(), listOf(main), emptyList(), emptyMap()).single().levels.single()
        assertEquals(ReplacedValue(null, JsonPrimitive("auto")), replacedValue(profile, def.key, profileRows, def))
    }

    @Test
    fun `only remote, current settings for the level's scope that are not set there can be added`() {
        val defs = listOf(
            SettingDefinition("a", allowed_scopes = listOf("profile", "profile_device")),
            SettingDefinition("b", allowed_scopes = listOf("profile")),
            SettingDefinition("c", persistence = "client_local", allowed_scopes = listOf("profile_device")),
            SettingDefinition("d", allowed_scopes = listOf("profile_device"), deprecated = true),
            SettingDefinition("e", allowed_scopes = listOf("profile_device")),
        )
        val level = buildPreferenceLevels(listOf(entry("e", "profile_device", profile = "p1", device = "d1")), listOf(main), listOf(tv), emptyMap())
            .single().levels.first { it.kind == LevelKind.Device }
        assertEquals(listOf("a"), addableSettings(level, defs).map { it.key })
    }

    @Test
    fun `the contract's value types pick the editor`() {
        fun def(json: String) = Json { ignoreUnknownKeys = true }.decodeFromString<SettingDefinition>(json)
        assertEquals(ValueEditor.Switch, editorFor(def("""{"key":"k","value_schema":{"type":"boolean"}}""")))
        val choice = editorFor(def("""{"key":"k","value_schema":{"type":"enum","values":[{"value":"a","label":"A"},{"value":"b","deprecated":true}]}}"""))
        assertEquals(listOf(JsonPrimitive("a")), (choice as ValueEditor.Choice).options.map { it.value })
        // A deprecated option stays listed while it is the stored value.
        val kept = editorFor(def("""{"key":"k","value_schema":{"type":"enum","values":[{"value":"a"},{"value":"b","deprecated":true}]}}"""), JsonPrimitive("b"))
        assertEquals(2, (kept as ValueEditor.Choice).options.size)
        assertEquals(ValueEditor.Number(true, 100.0, 200000.0, true), editorFor(def("""{"key":"k","value_schema":{"type":"integer","nullable":true,"minimum":100,"maximum":200000}}""")))
        assertEquals(ValueEditor.Text(true), editorFor(def("""{"key":"k","value_schema":{"type":"language_tag","nullable":true}}""")))
        assertEquals(ValueEditor.ViewOnly, editorFor(def("""{"key":"k","value_schema":{"type":"object","schema_ref":"x.json"}}""")))
        assertEquals(ValueEditor.ViewOnly, editorFor(null))
    }

    @Test
    fun `numbers keep their JSON type and are checked against the contract's range`() {
        val whole = ValueEditor.Number(true, 100.0, 200000.0, true)
        assertEquals(ParsedValue.Ok(JsonPrimitive(5000L)), parseNumber(" 5000 ", whole))
        assertEquals(ParsedValue.Ok(JsonNull), parseNumber("", whole))
        assertEquals(ParsedValue.NotANumber, parseNumber("5.5", whole))
        assertEquals(ParsedValue.NotANumber, parseNumber("abc", whole))
        assertEquals(ParsedValue.OutOfRange(100.0, 200000.0), parseNumber("99", whole))
        val speed = ValueEditor.Number(false, 0.25, 3.0, false)
        assertEquals(ParsedValue.Ok(JsonPrimitive(1.5)), parseNumber("1.5", speed))
        assertEquals(ParsedValue.NotANumber, parseNumber("", speed))
        assertEquals(ParsedValue.OutOfRange(0.25, 3.0), parseNumber("3.5", speed))
    }

    @Test
    fun `values are shown as the contract words them`() {
        val def = SettingDefinition("k", value_schema = SettingValueSchema("enum", values = listOf(SettingOptionDef(JsonPrimitive("always"), "Always on"))))
        assertEquals("Always on", valueText(def, JsonPrimitive("always"), "On", "Off", "Not set"))
        assertEquals("Off", valueText(null, JsonPrimitive(false), "On", "Off", "Not set"))
        assertEquals("Not set", valueText(null, JsonNull, "On", "Off", "Not set"))
        assertEquals("10", valueText(null, JsonPrimitive(10), "On", "Off", "Not set"))
        assertEquals("""{"a":1}""", valueText(null, Json.parseToJsonElement("""{"a":1}"""), "On", "Off", "Not set"))
        assertEquals("Watch Party", categoryTitle("watch_party"))
    }
}
