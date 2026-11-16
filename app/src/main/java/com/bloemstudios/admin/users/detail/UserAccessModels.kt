package com.bloemstudios.admin.users.detail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One row of GET /api/v2/admin/access-groups (AdminAccessGroupListItem): what a member inherits.
 * [library_ids] null means every library; [allowed_permissions] null means every permission is allowed.
 */
@Serializable
data class AccessGroup(
    val id: String,
    val name: String,
    val library_ids: List<String>? = null,
    val allowed_permissions: List<String>? = null,
    val max_streams: Int = 0,
    val max_playback_quality: String = "",
    val member_count: Int = 0
)

/** One stored setting of an account (SettingValue): [value] is the JSON the apps saved at that scope. */
@Serializable
data class UserSettingEntry(
    val key: String,
    val scope: String,
    val value: JsonElement,
    val revision: Long = 0,
    val profile_id: String? = null,
    val client_family: String? = null,
    val device_id: String? = null,
    val library_id: String? = null,
    val series_id: String? = null
)

/** The storage identity of one setting row: the scope and the ids that scope names. */
data class SettingIdentity(
    val scope: String,
    val profileId: String? = null,
    val clientFamily: String? = null,
    val deviceId: String? = null,
    val libraryId: String? = null,
    val seriesId: String? = null
) {
    fun query(): Map<String, String?> = mapOf(
        "scope" to scope, "profile_id" to profileId, "client_family" to clientFamily,
        "device_id" to deviceId, "library_id" to libraryId, "series_id" to seriesId
    )
}

fun UserSettingEntry.identity() = SettingIdentity(scope, profile_id, client_family, device_id, library_id, series_id)

/** The libraries an unset field resolves to for admins and for regular accounts outside every group. */
@Serializable
data class PolicyDefaultsHint(val library_ids: List<String>? = null)

/** GET /api/v2/admin/users/policy-defaults: what unset policy fields resolve to without a group. */
@Serializable
data class UserPolicyDefaults(val admin: PolicyDefaultsHint? = null, val ungrouped: PolicyDefaultsHint? = null)
