package com.bloemstudios.admin.users

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class AdminUser(
    val id: String,
    val username: String,
    val email: String,
    val role: String,
    val enabled: Boolean,
    val max_streams: Int? = null,
    val max_transcodes: Int? = null,
    val download_allowed: Boolean? = null,
    val requests_allowed: Boolean? = null,
    val transcode_allowed: Boolean? = null,
    val audio_transcode_allowed: Boolean? = null,
    val max_profiles: Int? = null,
    val effective_policy: AdminEffectivePolicy? = null,
    /** False for an account a sign-in provider manages; it then has no password to reset. */
    val password_login: Boolean = true,
    val password_change_required: Boolean = false,
    /** The server owner is the platform admin; nobody else may change or delete the account. */
    val is_owner: Boolean = false,
    val access_group_id: String? = null,
    /** The account's own library allowlist; null inherits the group's (or the server's), empty means none. */
    val library_ids: List<String>? = null,
    /** Permissions assigned to the account itself; the access group can still block them. */
    val permissions: List<String> = emptyList(),
    val created_at: String? = null,
    // Null until the account has been active; the dashboard's Users section sorts on it.
    val last_active_at: String? = null
)

@Serializable
data class AdminEffectivePolicy(
    val library_ids: List<String>? = null,
    val max_playback_quality: String,
    val max_streams: Int,
    val max_profiles: Int,
    val max_transcodes: Int,
    val transcode_allowed: Boolean,
    val audio_transcode_allowed: Boolean,
    val download_allowed: Boolean,
    val download_transcode_allowed: Boolean,
    val requests_allowed: Boolean,
    val permissions: List<String>? = null
)

sealed interface PolicyOverrideChange<out T> {
    data object Unchanged : PolicyOverrideChange<Nothing>
    data object Clear : PolicyOverrideChange<Nothing>
    data class Set<T>(val value: T) : PolicyOverrideChange<T>
}

data class UpdateUserQuotasRequest(
    val max_streams: PolicyOverrideChange<Int> = PolicyOverrideChange.Unchanged,
    val max_transcodes: PolicyOverrideChange<Int> = PolicyOverrideChange.Unchanged,
    val download_allowed: PolicyOverrideChange<Boolean> = PolicyOverrideChange.Unchanged,
    val requests_allowed: PolicyOverrideChange<Boolean> = PolicyOverrideChange.Unchanged,
    val transcode_allowed: PolicyOverrideChange<Boolean> = PolicyOverrideChange.Unchanged,
    val audio_transcode_allowed: PolicyOverrideChange<Boolean> = PolicyOverrideChange.Unchanged,
    val max_profiles: PolicyOverrideChange<Int> = PolicyOverrideChange.Unchanged,
    /** Clear means no group. Never sent for an admin: the server keeps admins out of groups. */
    val access_group_id: PolicyOverrideChange<String> = PolicyOverrideChange.Unchanged,
    /** Clear inherits the group's libraries; Set stores an explicit list, possibly empty. */
    val library_ids: PolicyOverrideChange<List<String>> = PolicyOverrideChange.Unchanged,
    /** The whole assigned set; the server replaces it, so it is only ever Set. */
    val permissions: PolicyOverrideChange<List<String>> = PolicyOverrideChange.Unchanged
) {
    fun isEmpty(): Boolean = max_streams == PolicyOverrideChange.Unchanged && max_transcodes == PolicyOverrideChange.Unchanged &&
        download_allowed == PolicyOverrideChange.Unchanged && requests_allowed == PolicyOverrideChange.Unchanged &&
        transcode_allowed == PolicyOverrideChange.Unchanged && audio_transcode_allowed == PolicyOverrideChange.Unchanged &&
        max_profiles == PolicyOverrideChange.Unchanged && access_group_id == PolicyOverrideChange.Unchanged &&
        library_ids == PolicyOverrideChange.Unchanged && permissions == PolicyOverrideChange.Unchanged

    fun toJsonObject(): JsonObject = buildJsonObject {
        putChange("max_streams", max_streams)
        putChange("max_transcodes", max_transcodes)
        putChange("download_allowed", download_allowed)
        putChange("requests_allowed", requests_allowed)
        putChange("transcode_allowed", transcode_allowed)
        putChange("audio_transcode_allowed", audio_transcode_allowed)
        putChange("max_profiles", max_profiles)
        putChange("access_group_id", access_group_id)
        putChange("library_ids", library_ids)
        putChange("permissions", permissions)
    }
}

private fun <T> kotlinx.serialization.json.JsonObjectBuilder.putChange(name: String, change: PolicyOverrideChange<T>) {
    when (change) {
        PolicyOverrideChange.Unchanged -> Unit
        PolicyOverrideChange.Clear -> put(name, JsonNull)
        is PolicyOverrideChange.Set -> put(name, when (val value = change.value) {
            is Int -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
            else -> error("Unsupported policy value")
        })
    }
}

data class UserPolicyEditorValues(
    val maxStreamsText: String,
    val maxStreamsInherited: Boolean,
    val maxTranscodesText: String,
    val maxTranscodesInherited: Boolean,
    val maxProfilesText: String,
    val downloadAllowed: Boolean,
    val downloadAllowedInherited: Boolean,
    val requestsAllowed: Boolean,
    val requestsAllowedInherited: Boolean,
    val transcodeAllowed: Boolean,
    val transcodeAllowedInherited: Boolean,
    val audioTranscodeAllowed: Boolean,
    val audioTranscodeAllowedInherited: Boolean,
    val libraryAccess: EffectiveLibraryAccess,
    val permissionAccess: EffectivePermissionAccess,
    val maxProfilesInherited: Boolean = false
)

sealed interface EffectiveLibraryAccess {
    data object Unrestricted : EffectiveLibraryAccess
    data class Restricted(val libraryIds: List<String>) : EffectiveLibraryAccess
}

sealed interface EffectivePermissionAccess {
    data object None : EffectivePermissionAccess
    data class Explicit(val permissions: List<String>) : EffectivePermissionAccess
}

fun AdminUser.policyEditorValues() = UserPolicyEditorValues(
    maxStreamsText = (max_streams ?: effective_policy?.max_streams)?.toString().orEmpty(),
    maxStreamsInherited = max_streams == null,
    maxTranscodesText = (max_transcodes ?: effective_policy?.max_transcodes)?.toString().orEmpty(),
    maxTranscodesInherited = max_transcodes == null,
    maxProfilesText = (max_profiles ?: effective_policy?.max_profiles)?.toString().orEmpty(),
    downloadAllowed = download_allowed ?: effective_policy?.download_allowed ?: false,
    downloadAllowedInherited = download_allowed == null,
    requestsAllowed = requests_allowed ?: effective_policy?.requests_allowed ?: false,
    requestsAllowedInherited = requests_allowed == null,
    transcodeAllowed = transcode_allowed ?: effective_policy?.transcode_allowed ?: false,
    transcodeAllowedInherited = transcode_allowed == null,
    audioTranscodeAllowed = audio_transcode_allowed ?: effective_policy?.audio_transcode_allowed ?: false,
    audioTranscodeAllowedInherited = audio_transcode_allowed == null,
    libraryAccess = effective_policy?.library_ids?.let(EffectiveLibraryAccess::Restricted)
        ?: EffectiveLibraryAccess.Unrestricted,
    maxProfilesInherited = max_profiles == null,
    permissionAccess = effective_policy?.permissions?.let(EffectivePermissionAccess::Explicit)
        ?: EffectivePermissionAccess.None
)

fun buildUserPolicyUpdate(original: AdminUser, edited: UserPolicyEditorValues) = UpdateUserQuotasRequest(
    max_streams = overrideChange(original.max_streams, edited.maxStreamsInherited, edited.maxStreamsText.toIntOrNull()),
    max_transcodes = overrideChange(original.max_transcodes, edited.maxTranscodesInherited, edited.maxTranscodesText.toIntOrNull()),
    max_profiles = overrideChange(original.max_profiles, edited.maxProfilesInherited, edited.maxProfilesText.toIntOrNull()),
    download_allowed = overrideChange(original.download_allowed, edited.downloadAllowedInherited, edited.downloadAllowed),
    requests_allowed = overrideChange(original.requests_allowed, edited.requestsAllowedInherited, edited.requestsAllowed),
    transcode_allowed = overrideChange(original.transcode_allowed, edited.transcodeAllowedInherited, edited.transcodeAllowed),
    audio_transcode_allowed = overrideChange(original.audio_transcode_allowed, edited.audioTranscodeAllowedInherited, edited.audioTranscodeAllowed)
)

private fun <T> overrideChange(original: T?, inherited: Boolean, value: T?): PolicyOverrideChange<T> = when {
    inherited && original != null -> PolicyOverrideChange.Clear
    inherited -> PolicyOverrideChange.Unchanged
    value != null && value != original -> PolicyOverrideChange.Set(value)
    else -> PolicyOverrideChange.Unchanged
}

/**
 * What the Access & limits tab edits besides the limits: the access group, the library allowlist and the
 * permissions assigned to the account. [groupId] null is "no group"; [libraryIds] null inherits.
 */
data class UserAccessEdit(
    val groupId: String?,
    val libraryIds: List<String>?,
    val permissions: List<String>
)

fun AdminUser.accessEdit() = UserAccessEdit(access_group_id, library_ids, permissions.sorted())

/**
 * The access part of a save, added to [limits]. Only what differs from [original] is sent: libraries and
 * permissions compare as sets, and the group is left out for an admin (the server keeps admins ungrouped).
 */
fun buildUserAccessUpdate(original: AdminUser, edited: UserAccessEdit, limits: UpdateUserQuotasRequest = UpdateUserQuotasRequest()): UpdateUserQuotasRequest {
    val groupChange: PolicyOverrideChange<String> = when {
        original.role == "admin" || edited.groupId == original.access_group_id -> PolicyOverrideChange.Unchanged
        edited.groupId == null -> PolicyOverrideChange.Clear
        else -> PolicyOverrideChange.Set(edited.groupId)
    }
    val libraryChange: PolicyOverrideChange<List<String>> = when {
        edited.libraryIds == null && original.library_ids == null -> PolicyOverrideChange.Unchanged
        edited.libraryIds == null -> PolicyOverrideChange.Clear
        original.library_ids != null && edited.libraryIds.toSet() == original.library_ids.toSet() -> PolicyOverrideChange.Unchanged
        else -> PolicyOverrideChange.Set(edited.libraryIds.distinct().sorted())
    }
    val permissionChange: PolicyOverrideChange<List<String>> =
        if (edited.permissions.toSet() == original.permissions.toSet()) PolicyOverrideChange.Unchanged
        else PolicyOverrideChange.Set(edited.permissions.distinct().sorted())
    return limits.copy(access_group_id = groupChange, library_ids = libraryChange, permissions = permissionChange)
}

// AdminAccountCreateBody's required fields only; the optional ones (permissions,
// library_ids, quotas, access_group_id, ...) are left for the server to default.
@Serializable
data class CreateUserRequest(
    val username: String,
    val email: String,
    val password: String,
    val role: String,
    val create_default_profile: Boolean
)
