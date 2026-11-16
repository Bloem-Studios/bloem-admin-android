package com.bloemstudios.admin.users

import kotlinx.serialization.Serializable

// LoginSession. device_id and device_platform are absent when the client sent none; last_seen_at
// is null until recorded; device_name and ip_address are empty strings when unknown.
@Serializable
data class LoginSession(
    val id: String,
    val device_name: String,
    val ip_address: String,
    val created_at: String,
    val expires_at: String,
    val last_seen_at: String?,
    val current: Boolean,
    val device_id: String? = null,
    val device_platform: String? = null
)

// AdminUserDevice. last_seen_at and last_updated are null for a device known only from saved settings.
@Serializable
data class UserDevice(
    val device_id: String,
    val device_name: String,
    val device_platform: String,
    val last_seen_at: String?,
    val last_updated: String?,
    val override_count: Int,
    val profiles: List<UserDeviceProfile>
)

// AdminUserDeviceProfile.
@Serializable
data class UserDeviceProfile(
    val profile_id: String,
    val profile_name: String,
    val override_count: Int,
    val last_seen_at: String?
)

sealed class RevokeResult {
    /** [revoked] is the server's count after "revoke all"; null for a single-session revoke. */
    data class Success(val revoked: Int? = null) : RevokeResult()
    data class Failure(val message: com.bloemstudios.admin.network.ErrorText) : RevokeResult()
}
