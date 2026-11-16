package com.bloemstudios.admin.users.detail

import com.bloemstudios.admin.users.UserDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One allowed value of an enum setting. */
@Serializable
data class SettingOptionDef(val value: JsonElement, val label: String? = null, val deprecated: Boolean = false)

/** The type of a setting's value, from the settings contract's `value_schema`. */
@Serializable
data class SettingValueSchema(
    val type: String = "",
    val nullable: Boolean = false,
    val values: List<SettingOptionDef> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null
)

/** One setting of the settings contract (GET /api/v2/settings/contract): its type, scopes, default and wording. */
@Serializable
data class SettingDefinition(
    val key: String,
    val persistence: String = "remote",
    val allowed_scopes: List<String> = emptyList(),
    val value_schema: SettingValueSchema = SettingValueSchema(),
    val default_value: JsonElement? = null,
    val category: String = "",
    val label: String = "",
    val description: String = "",
    val deprecated: Boolean = false
)

@Serializable
data class SettingsContract(val revision: Int = 0, val definitions: List<SettingDefinition> = emptyList())

internal enum class LevelKind { Account, Profile, Device, Client, Library, Series }

/**
 * One place a setting can be stored for an account, in the order the server resolves them. Each level is
 * exactly one storage [identity], so every edit, add or removal on it addresses one row.
 */
internal data class PreferenceLevel(
    val id: String,
    val kind: LevelKind,
    val identity: SettingIdentity,
    val profileId: String?,
    val profileName: String,
    /** Settings stored at exactly this identity, in the contract's order. */
    val entries: List<UserSettingEntry>,
    val deviceId: String? = null,
    /** Null for a device the account's device list does not know. */
    val deviceName: String? = null,
    val clientFamily: String? = null,
    val libraryId: String? = null,
    /** Null for a library the server's list does not know. */
    val libraryName: String? = null,
    val seriesId: String? = null
)

/** The account level, or one profile with its device, app family, library and series levels. */
internal data class PreferenceLevelGroup(val key: String, val profileName: String?, val levels: List<PreferenceLevel>)

internal fun profileLevelId(profileId: String) = "profile.$profileId"

private val FAMILY_ORDER = listOf("tv", "mobile", "tablet", "desktop", "web")

/**
 * Folds the stored settings into levels: the account-wide level when any row has that scope, then one group
 * per profile in the account's order (a profile no longer listed but still holding rows follows). Under each
 * profile: All devices, each device the profile has settings on or was seen with, its app families, libraries
 * and series. [order] ranks keys as the contract lists them.
 */
internal fun buildPreferenceLevels(
    entries: List<UserSettingEntry>,
    profiles: List<UserProfile>,
    devices: List<UserDevice>,
    libraryNames: Map<String, String>,
    order: Map<String, Int> = emptyMap()
): List<PreferenceLevelGroup> {
    val ranked = compareBy<UserSettingEntry>({ order[it.key] ?: Int.MAX_VALUE }, { it.key })
    val account = entries.filter { it.scope == "account" }.sortedWith(ranked)
    val perProfile = entries.filter { it.scope != "account" }.groupBy { it.profile_id.orEmpty() }

    val groups = mutableListOf<PreferenceLevelGroup>()
    if (account.isNotEmpty()) {
        groups += PreferenceLevelGroup(
            "account", null,
            listOf(PreferenceLevel("account", LevelKind.Account, SettingIdentity("account"), null, "", account))
        )
    }

    val listed = profiles.map { it.id }.toSet()
    val orphans = perProfile.keys.filter { it !in listed }.sorted()
    val ordered = profiles.map { it.id to it.name } + orphans.map { it to it }
    for ((profileId, profileName) in ordered) {
        val rows = perProfile[profileId].orEmpty()
        fun at(scope: String) = rows.filter { it.scope == scope }
        val levels = mutableListOf<PreferenceLevel>()
        levels += PreferenceLevel(
            profileLevelId(profileId), LevelKind.Profile, SettingIdentity("profile", profileId = profileId),
            profileId, profileName, at("profile").sortedWith(ranked)
        )

        val deviceRows = at("profile_device").groupBy { it.device_id.orEmpty() }
        fun deviceLevel(deviceId: String, name: String?) = PreferenceLevel(
            "device.$profileId.$deviceId", LevelKind.Device, SettingIdentity("profile_device", profileId = profileId, deviceId = deviceId),
            profileId, profileName, deviceRows[deviceId].orEmpty().sortedWith(ranked), deviceId = deviceId, deviceName = name
        )
        val registered = mutableSetOf<String>()
        for (device in devices) {
            val seen = device.profiles.any { it.profile_id == profileId }
            if (!seen && device.device_id !in deviceRows) continue
            registered += device.device_id
            levels += deviceLevel(device.device_id, device.device_name.ifBlank { null })
        }
        for (deviceId in deviceRows.keys.sorted()) if (deviceId !in registered) levels += deviceLevel(deviceId, null)

        val clientRows = at("profile_client").filter { it.client_family != null }.groupBy { it.client_family!! }
        for (family in FAMILY_ORDER + (clientRows.keys - FAMILY_ORDER.toSet()).sorted()) {
            val familyRows = clientRows[family] ?: continue
            levels += PreferenceLevel(
                "client.$profileId.$family", LevelKind.Client, SettingIdentity("profile_client", profileId = profileId, clientFamily = family),
                profileId, profileName, familyRows.sortedWith(ranked), clientFamily = family
            )
        }

        val libraryRows = at("profile_library").filter { it.library_id != null }.groupBy { it.library_id!! }
        fun libraryLabel(id: String) = libraryNames[id] ?: id
        for (libraryId in libraryRows.keys.sortedWith(compareBy({ libraryLabel(it).lowercase() }, { it }))) {
            levels += PreferenceLevel(
                "library.$profileId.$libraryId", LevelKind.Library, SettingIdentity("profile_library", profileId = profileId, libraryId = libraryId),
                profileId, profileName, libraryRows.getValue(libraryId).sortedWith(ranked), libraryId = libraryId, libraryName = libraryNames[libraryId]
            )
        }

        val seriesRows = at("profile_series").filter { it.series_id != null }.groupBy { it.series_id!! }
        for (seriesId in seriesRows.keys.sorted()) {
            levels += PreferenceLevel(
                "series.$profileId.$seriesId", LevelKind.Series, SettingIdentity("profile_series", profileId = profileId, seriesId = seriesId),
                profileId, profileName, seriesRows.getValue(seriesId).sortedWith(ranked), seriesId = seriesId
            )
        }
        groups += PreferenceLevelGroup("profile.$profileId", profileName, levels)
    }
    return groups
}

internal fun LevelKind.scope(): String = when (this) {
    LevelKind.Account -> "account"
    LevelKind.Profile -> "profile"
    LevelKind.Device -> "profile_device"
    LevelKind.Client -> "profile_client"
    LevelKind.Library -> "profile_library"
    LevelKind.Series -> "profile_series"
}

/**
 * What a setting on a level replaces. A device, app-family, library or series level replaces the profile's own
 * stored value when there is one; every other case replaces the app default. [profileName] is set only for the
 * first case. This names only those two layers, as the web does for a setting with no layer in between.
 */
internal data class ReplacedValue(val profileName: String?, val value: JsonElement?)

internal fun replacedValue(level: PreferenceLevel, key: String, profileEntries: List<UserSettingEntry>, definition: SettingDefinition?): ReplacedValue {
    val below = level.kind == LevelKind.Device || level.kind == LevelKind.Client || level.kind == LevelKind.Library || level.kind == LevelKind.Series
    if (below) profileEntries.firstOrNull { it.key == key }?.let { return ReplacedValue(level.profileName, it.value) }
    return ReplacedValue(null, definition?.default_value)
}

/** Settings a level can hold that are not set on it yet: stored on the server, not deprecated, for this level's scope. */
internal fun addableSettings(level: PreferenceLevel, definitions: List<SettingDefinition>): List<SettingDefinition> {
    val stored = level.entries.map { it.key }.toSet()
    return definitions.filter {
        it.persistence == "remote" && !it.deprecated && level.kind.scope() in it.allowed_scopes && it.key !in stored
    }
}

/** How a stored value is edited in place. Anything else is shown but not edited here. */
internal sealed interface ValueEditor {
    data object Switch : ValueEditor
    data class Choice(val options: List<SettingOptionDef>) : ValueEditor
    data class Number(val integer: Boolean, val min: Double?, val max: Double?, val nullable: Boolean) : ValueEditor
    data class Text(val nullable: Boolean) : ValueEditor
    data object ViewOnly : ValueEditor
}

internal fun editorFor(definition: SettingDefinition?, current: JsonElement? = null): ValueEditor {
    val schema = definition?.value_schema ?: return ValueEditor.ViewOnly
    return when (schema.type) {
        "boolean" -> ValueEditor.Switch
        "enum" -> ValueEditor.Choice(schema.values.filter { !it.deprecated || it.value == current })
        "integer" -> ValueEditor.Number(true, schema.minimum, schema.maximum, schema.nullable)
        "number" -> ValueEditor.Number(false, schema.minimum, schema.maximum, schema.nullable)
        "string", "language_tag" -> ValueEditor.Text(schema.nullable)
        else -> ValueEditor.ViewOnly
    }
}

/** The text a value is edited as: its content, or empty for null. */
internal fun editText(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

/** The outcome of reading what the admin typed into a number or text field. */
internal sealed interface ParsedValue {
    data class Ok(val value: JsonElement) : ParsedValue
    data object NotANumber : ParsedValue
    data class OutOfRange(val min: Double?, val max: Double?) : ParsedValue
}

internal fun parseNumber(text: String, editor: ValueEditor.Number): ParsedValue {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return if (editor.nullable) ParsedValue.Ok(JsonNull) else ParsedValue.NotANumber
    val number = if (editor.integer) trimmed.toLongOrNull()?.toDouble() else trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
    number ?: return ParsedValue.NotANumber
    if ((editor.min != null && number < editor.min) || (editor.max != null && number > editor.max)) return ParsedValue.OutOfRange(editor.min, editor.max)
    return ParsedValue.Ok(if (editor.integer) JsonPrimitive(trimmed.toLong()) else JsonPrimitive(trimmed.toDouble()))
}

internal fun parseText(text: String, editor: ValueEditor.Text): JsonElement =
    if (text.isEmpty() && editor.nullable) JsonNull else JsonPrimitive(text)

/** A value for reading: the option's label for an enum, [on] or [off] for a boolean, [unset] for null, else its content. */
internal fun valueText(definition: SettingDefinition?, value: JsonElement?, on: String, off: String, unset: String): String {
    if (value == null || value == JsonNull) return unset
    if (value is JsonPrimitive) {
        value.booleanOrNull?.let { return if (it) on else off }
        definition?.value_schema?.values?.firstOrNull { it.value == value }?.let { option -> option.label?.let { return it } }
        return value.contentOrNull ?: value.toString()
    }
    return value.toString()
}

/** "Playback", "Watch party": a category's title, from its id. */
internal fun categoryTitle(category: String): String =
    category.split('_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/**
 * The value a new setting starts with: what it replaces, so nothing changes until it is edited. When that is
 * unknown (no default in the contract), a value the setting's type accepts.
 */
internal fun startingValue(replaced: ReplacedValue, definition: SettingDefinition): JsonElement {
    replaced.value?.takeIf { it != JsonNull }?.let { return it }
    val schema = definition.value_schema
    if (schema.nullable) return JsonNull
    return when (schema.type) {
        "boolean" -> JsonPrimitive(false)
        "enum" -> schema.values.firstOrNull { !it.deprecated }?.value ?: JsonNull
        "integer" -> JsonPrimitive((schema.minimum ?: 0.0).toLong())
        "number" -> JsonPrimitive(schema.minimum ?: 0.0)
        "string", "language_tag" -> JsonPrimitive("")
        else -> JsonNull
    }
}
