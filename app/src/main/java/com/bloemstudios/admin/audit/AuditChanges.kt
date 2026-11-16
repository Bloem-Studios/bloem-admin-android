package com.bloemstudios.admin.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The words for what an audited request changed, ported from the web's AdminLogs.tsx (auditChangeValue, auditPINChange,
// auditChangeSummary, auditTargetLabel) so a change reads the same in the app and on the web.

/** The short words a change is written with, resolved to the user's language by the screen. */
data class AuditWords(
    val absent: String,
    val allAssignable: String,
    val allLibraries: String,
    val changed: String,
    val fullAccess: String,
    val inherit: String,
    val none: String,
    val pinCleared: String,
    val pinNotSet: String,
    val pinSet: String,
    val unset: String,
)

private val json = Json

/** "before → after" for one change; a profile's PIN only ever shows as set, cleared or changed, never its value. */
fun auditChangeLine(change: AuditChange, targetType: String?, words: AuditWords): String {
    if (change.before == null && change.after == null) return words.changed
    if (targetType == "profile" && change.field == "pin") return pinChange(change.before, change.after, words)
    return "${changeValue(change.before, targetType, change.field, words)} → ${changeValue(change.after, targetType, change.field, words)}"
}

private fun pinChange(before: String?, after: String?, words: AuditWords): String {
    if (before != null && after != null) {
        if (before == after) return words.changed
        return if (after == "true") words.pinSet else words.pinCleared
    }
    fun label(value: String?) = when (value) {
        null -> words.absent
        "true" -> words.pinSet
        else -> words.pinNotSet
    }
    return "${label(before)} → ${label(after)}"
}

private fun changeValue(value: String?, targetType: String?, field: String, words: AuditWords): String {
    if (value == null) return words.absent
    val parsed: JsonElement = try { json.parseToJsonElement(value) } catch (_: Exception) { return value }
    return when {
        parsed is JsonNull -> when {
            field == "access_group_id" -> words.none
            targetType == "access_group" -> when (field) {
                "library_ids" -> words.allLibraries
                "allowed_permissions" -> words.allAssignable
                else -> words.unset
            }
            else -> words.inherit
        }
        field == "scopes" && parsed is JsonArray && parsed.isEmpty() -> words.fullAccess
        parsed is JsonArray -> if (parsed.isEmpty()) words.none else parsed.joinToString(", ") { it.asText() }
        parsed is JsonObject -> parsed.toString()
        else -> parsed.asText()
    }
}

private fun JsonElement.asText(): String = if (this is JsonPrimitive) content else toString()

/** A field name as words: "max_streams" reads "max streams". */
fun auditFieldLabel(field: String): String = field.replace('_', ' ')

/** "user #7", "API key #9"; just the type when there is no id, and null when the request names no target. */
fun auditTargetText(type: String?, id: String?, apiKeyLabel: String): String? {
    if (type.isNullOrBlank()) return null
    val label = if (type == "api_key") apiKeyLabel else type.replace('_', ' ')
    return if (id.isNullOrBlank()) label else "$label #$id"
}
