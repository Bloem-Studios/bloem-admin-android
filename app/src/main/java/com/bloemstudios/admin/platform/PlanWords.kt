package com.bloemstudios.admin.platform

import android.content.res.Resources
import com.bloemstudios.admin.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A plan and the changes a dry-run reports, in the web's plain words (web/src/components/admin/policy/policyVocabulary.ts):
 * a screen is a stream, a conversion is a transcode, a plan is an entitlement template. Nothing here shows JSON or a key
 * the server uses internally. The vocabulary is the web's; only the plan line is new, because the web shows the raw
 * `{template_key, template_revision}` objects there.
 */
class PlanWords(private val resources: Resources) {
    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)
    private fun count(id: Int, n: Int) = resources.getQuantityString(id, n, n)

    /** The plan with its revision, for example `Basic, revision 3`; a blank key is "No plan". */
    fun planName(key: String, revision: Long, templates: List<EntitlementTemplate>): String =
        if (key.isBlank()) text(R.string.platform_plan_value_no_plan)
        else text(R.string.platform_plan_value_plan, templates.firstOrNull { it.key == key }?.label ?: key, revision)

    /** One line for each change of a dry-run: `Label: before → after`. */
    fun changeLines(changes: List<EntitlementChange>, templates: List<EntitlementTemplate>): List<String> =
        changes.map { change ->
            if (change.field == PLAN_FIELD) {
                text(
                    R.string.platform_plan_change_line, text(R.string.platform_plan_change_plan),
                    planSide(change.before, templates), planSide(change.after, templates),
                )
            } else {
                text(R.string.platform_plan_change_line, fieldLabel(change.field), value(change.field, change.before), value(change.field, change.after))
            }
        }

    /** What a plan or default group allows, as label and value rows (the web's policyRows). */
    fun policyRows(policy: EntitlementPolicy): List<Pair<String, String>> = listOf(
        text(R.string.platform_policy_field_libraries) to libraries(policy.library_ids),
        text(R.string.platform_policy_field_plays_video) to yesNo(policy.playback_allowed),
        text(R.string.platform_policy_field_screens) to limit(policy.max_streams, R.plurals.platform_policy_screens),
        text(R.string.platform_policy_field_profiles) to limit(policy.max_profiles, R.plurals.platform_policy_profiles),
        text(R.string.platform_policy_field_video_conversion) to yesNo(policy.transcode_allowed),
        text(R.string.platform_policy_field_conversions) to limit(policy.max_transcodes, R.plurals.platform_policy_conversions),
        text(R.string.platform_policy_field_download_originals) to yesNo(policy.download_allowed),
        text(R.string.platform_policy_field_download_converted) to yesNo(policy.download_transcode_allowed),
        text(R.string.platform_policy_field_quality) to quality(policy.max_playback_quality),
        text(R.string.platform_policy_field_requests) to yesNo(policy.requests_allowed),
        text(R.string.platform_policy_field_permissions) to permissions(policy.allowed_permissions),
    )

    private fun planSide(side: JsonElement?, templates: List<EntitlementTemplate>): String {
        val obj = side as? JsonObject ?: return text(R.string.platform_plan_value_no_plan)
        val key = (obj["template_key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val revision = (obj["template_revision"] as? JsonPrimitive)?.longOrNull ?: 0L
        return planName(key, revision, templates)
    }

    private fun fieldLabel(field: String): String = when (field) {
        "library_ids", "libraries" -> text(R.string.platform_policy_field_libraries)
        "playback_allowed" -> text(R.string.platform_policy_field_plays_video)
        "max_streams" -> text(R.string.platform_policy_field_screens)
        "max_profiles" -> text(R.string.platform_policy_field_profiles)
        "transcode_allowed" -> text(R.string.platform_policy_field_video_conversion)
        "audio_transcode_allowed" -> text(R.string.platform_policy_field_audio_conversion)
        "max_transcodes" -> text(R.string.platform_policy_field_conversions)
        "download_allowed" -> text(R.string.platform_policy_field_download_originals)
        "download_transcode_allowed" -> text(R.string.platform_policy_field_download_converted)
        "max_playback_quality" -> text(R.string.platform_policy_field_quality)
        "requests_allowed" -> text(R.string.platform_policy_field_requests)
        "allowed_permissions", "permissions" -> text(R.string.platform_policy_field_permissions)
        else -> field.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
    }

    /** One side of a change, by what the field is, as the web's formatPolicyValue does. */
    private fun value(field: String, value: JsonElement?): String {
        val primitive = value as? JsonPrimitive
        return when {
            primitive != null && primitive !is JsonNull && primitive.booleanOrNull != null && !primitive.isString -> yesNo(primitive.booleanOrNull == true)
            field == "max_playback_quality" && primitive != null && primitive.isString -> quality(primitive.content)
            field in LIMIT_FIELDS && primitive?.longOrNull == 0L -> text(R.string.platform_policy_no_limit)
            field == "allowed_permissions" || field == "permissions" ->
                if (value == null || value is JsonNull) text(R.string.platform_policy_all_permissions)
                else if (value is JsonArray) permissions(value.map { it.asText() }) else value.asText()
            value == null || value is JsonNull ->
                if ("librar" in field) text(R.string.platform_policy_all) else text(R.string.platform_policy_none)
            value is JsonArray -> if (value.isEmpty()) text(R.string.platform_policy_none) else value.joinToString(", ") { it.asText() }
            value is JsonObject -> value.entries.joinToString(", ") { (key, inner) -> "$key: ${inner.asText()}" }
            else -> value.asText()
        }
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> content
        is JsonArray -> joinToString(", ") { it.asText() }
        is JsonObject -> entries.joinToString(", ") { (key, inner) -> "$key: ${inner.asText()}" }
    }

    private fun yesNo(value: Boolean) = text(if (value) R.string.platform_policy_yes else R.string.platform_policy_no)

    private fun limit(value: Int, plural: Int) = if (value == 0) text(R.string.platform_policy_no_limit) else count(plural, value)

    /** The libraries a plan or limit covers: all, none, or how many. */
    fun libraries(ids: List<Int>?) = when {
        ids == null -> text(R.string.platform_policy_all_libraries)
        ids.isEmpty() -> text(R.string.platform_policy_none)
        else -> count(R.plurals.platform_policy_library_count, ids.size)
    }

    private fun quality(value: String) = when (value) {
        "" -> text(R.string.platform_policy_quality_any)
        "original" -> text(R.string.platform_policy_quality_original)
        "1080p" -> text(R.string.platform_policy_quality_1080p)
        "2160p" -> text(R.string.platform_policy_quality_4k)
        else -> value
    }

    private fun permissions(values: List<String>?) = when {
        values == null -> text(R.string.platform_policy_all_permissions)
        values.isEmpty() -> text(R.string.platform_policy_none)
        else -> values.joinToString(", ") { permission ->
            when (permission) {
                "metadata_curation" -> text(R.string.platform_policy_permission_metadata)
                "marker_edit" -> text(R.string.platform_policy_permission_markers)
                "watch_live_tv" -> text(R.string.platform_policy_permission_live_tv)
                else -> permission.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }
    }

    private companion object {
        const val PLAN_FIELD = "template_revision"
        val LIMIT_FIELDS = setOf("max_streams", "max_profiles", "max_transcodes")
    }
}
