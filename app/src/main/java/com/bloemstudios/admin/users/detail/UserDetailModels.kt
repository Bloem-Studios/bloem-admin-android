package com.bloemstudios.admin.users.detail

import kotlinx.serialization.Serializable

/** The signed-in account (GET /api/v2/account/me); only who they are is used. */
@Serializable
data class AccountMe(val id: String, val username: String = "", val role: String = "")

/**
 * AdminAccountCapabilitiesOutputBody. A missing member means the server does not state the capability.
 * [available] defaults to true so a server that omits it does not lock the page.
 */
@Serializable
data class AdminUserCapabilities(
    val available: Boolean = true,
    val login_sessions: Boolean = false,
    val account_devices: Boolean = false,
    val account_downloads: Boolean = false,
    val watch_summary: Boolean = false,
    val password_reset_email: Boolean = false,
    val password_reset_link: Boolean = false,
    /** Whether the server lets an admin add, rename, set a PIN on and delete an account's profiles. */
    val profile_management: Boolean = false
)

/**
 * AdminAccountProfile. [has_pin], [is_primary] and [is_child] are reported only where the server has
 * `profile_management`; an older server leaves them false.
 */
@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val last_seen_at: String? = null,
    val has_pin: Boolean = false,
    /** The household parent, which cannot be deleted. */
    val is_primary: Boolean = false,
    val is_child: Boolean = false
)

/** AdminUserWatchSummary. */
@Serializable
data class WatchSummary(
    val days: Int,
    val plays: Int,
    val completed_plays: Int,
    val watched_seconds: Double,
    val last_played_at: String? = null
)

/** AdminUserIP. */
@Serializable
data class UserIp(
    val client_ip: String,
    val first_seen: String,
    val last_seen: String,
    val location: String = "",
    val request_count: Int = 0
)

/** The fields the Downloads tab uses from AdminUserDownload. */
@Serializable
data class UserDownload(
    val id: String,
    val title: String,
    val media_type: String,
    val status: String,
    val file_size: Long = 0,
    val quality: String = "",
    val created_at: String
)

/** AdminUserDownloadSummary. */
@Serializable
data class UserDownloadSummary(
    val total: Int,
    val completed: Int,
    val in_progress: Int,
    val failed: Int,
    val revoked: Int,
    val total_bytes: Long = 0,
    val devices: Int = 0,
    val monitored_series: Int = 0
)
