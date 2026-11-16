package com.bloemstudios.admin.organization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The pieces cohort and decision pages share: a complete policy as labelled rows (the web's `PolicySummary`),
 * libraries read as names, and a label-and-value row.
 */

@Composable
internal fun yesNo(value: Boolean): String = stringResource(if (value) R.string.organization_policy_yes else R.string.organization_policy_no)

@Composable
internal fun limitText(value: Long, noun: LimitNoun): String {
    if (value == 0L) return stringResource(R.string.organization_policy_no_limit)
    val count = value.toInt()
    return when (noun) {
        LimitNoun.Screen -> pluralStringResource(R.plurals.organization_policy_screens, count, count)
        LimitNoun.Profile -> pluralStringResource(R.plurals.organization_policy_profiles, count, count)
        LimitNoun.Conversion -> pluralStringResource(R.plurals.organization_policy_conversions, count, count)
    }
}

internal enum class LimitNoun { Screen, Profile, Conversion }

/** Library ids as the names the organization knows them by; one it does not know reads "Library 7". `null` is every library. */
@Composable
internal fun libraryListText(ids: List<Long>?, libraries: List<OrganizationLibrary>): String {
    if (ids == null) return stringResource(R.string.organization_policy_all_libraries)
    if (ids.isEmpty()) return stringResource(R.string.organization_policy_none)
    val names = libraries.associate { it.folder_id to it.name }
    return ids.map { id -> names[id] ?: stringResource(R.string.organization_policy_library_number, id.toInt()) }.joinToString(", ")
}

@Composable
internal fun permissionListText(permissions: List<String>?): String {
    if (permissions == null) return stringResource(R.string.organization_policy_all_permissions)
    if (permissions.isEmpty()) return stringResource(R.string.organization_policy_none)
    return permissions.map { permission -> permissionRes(permission)?.let { stringResource(it) } ?: sentenceCase(permission) }.joinToString(", ")
}

@Composable
internal fun qualityText(quality: String): String = qualityRes(quality)?.let { stringResource(it) } ?: quality

/** The settings rows of a complete policy, in the web's order. */
@Composable
internal fun PolicySummary(policy: PolicyView, libraries: List<OrganizationLibrary>) {
    Fact(stringResource(R.string.organization_policy_libraries), libraryListText(policy.library_ids, libraries))
    Fact(stringResource(R.string.organization_policy_plays_video), yesNo(policy.playback_allowed))
    Fact(stringResource(R.string.organization_policy_screens_at_once), limitText(policy.max_streams, LimitNoun.Screen))
    Fact(stringResource(R.string.organization_policy_profiles), limitText(policy.max_profiles, LimitNoun.Profile))
    Fact(stringResource(R.string.organization_policy_video_conversion), yesNo(policy.transcode_allowed))
    policy.audio_transcode_allowed?.let { Fact(stringResource(R.string.organization_policy_audio_conversion), yesNo(it)) }
    Fact(stringResource(R.string.organization_policy_conversions_at_once), limitText(policy.max_transcodes, LimitNoun.Conversion))
    Fact(stringResource(R.string.organization_policy_download_originals), yesNo(policy.download_allowed))
    Fact(stringResource(R.string.organization_policy_download_converted), yesNo(policy.download_transcode_allowed))
    Fact(stringResource(R.string.organization_policy_best_quality), qualityText(policy.max_playback_quality))
    Fact(stringResource(R.string.organization_policy_media_requests), yesNo(policy.requests_allowed))
    Fact(stringResource(R.string.organization_policy_extra_permissions), permissionListText(policy.allowed_permissions))
}

/** One label and its value on a line; read as one sentence by a screen reader. */
@Composable
internal fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Muted, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = VeilColors.Text, modifier = Modifier.weight(0.55f))
    }
}

/** A value from a policy check's recorded resource, as text. The server has already hidden sensitive values. */
internal fun resourceValueText(value: JsonElement): String = when (value) {
    is JsonNull -> "—"
    is JsonPrimitive -> value.content
    is JsonArray, is JsonObject -> value.toString()
}
