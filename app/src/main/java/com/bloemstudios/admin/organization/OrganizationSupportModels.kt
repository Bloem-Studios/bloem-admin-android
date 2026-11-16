package com.bloemstudios.admin.organization

import kotlinx.serialization.Serializable

/*
 * Wire shapes of the organization support routes (a Bloem server's
 * OpenAPI document): password reset links, a person's sessions and devices, profile edits, the organization's streams,
 * what the server can do, and the owner's break-glass access. Field names stay snake_case like the rest of the app's
 * wire models. Unknown fields are ignored.
 *
 * No client address, node or routing appears anywhere here: the server never sends them in organization context.
 */

// ---- What this server can do -------------------------------------------------------------------------

@Serializable
data class SupportResetCapabilities(val email: Boolean = false, val link: Boolean = false)

@Serializable
data class SupportProfileCapabilities(
    val add: Boolean = false,
    val rename: Boolean = false,
    val clear_pin: Boolean = false,
    val remove: Boolean = false,
)

@Serializable
data class SupportStreamCapabilities(val list: Boolean = false, val stop: Boolean = false, val message: Boolean = false)

/**
 * `GET /admin/organization/support-capabilities`. A client hides what is false and never infers it from a failure.
 * [NONE] is what an older server (no such route) and a server that could not be asked both count as.
 */
@Serializable
data class SupportCapabilities(
    val password_reset: SupportResetCapabilities = SupportResetCapabilities(),
    val sessions: Boolean = false,
    val devices: Boolean = false,
    val profiles: SupportProfileCapabilities = SupportProfileCapabilities(),
    val streams: SupportStreamCapabilities = SupportStreamCapabilities(),
) {
    val canReset: Boolean get() = password_reset.email || password_reset.link
    val canEditProfiles: Boolean get() = profiles.add || profiles.rename || profiles.clear_pin || profiles.remove

    /** Anything at all on a person's page. */
    val hasPersonSupport: Boolean get() = canReset || sessions || devices || canEditProfiles

    companion object {
        val NONE = SupportCapabilities()
    }
}

// ---- Password reset ------------------------------------------------------------------------------------

/** How the member gets the reset link: `email` (24 hours, needs mail set up on the server) or `link` (4 hours, shown once). */
object ResetDelivery {
    const val EMAIL = "email"
    const val LINK = "link"
}

@Serializable
data class SupportResetRequest(val expected_revision: Long, val delivery: String)

/**
 * The reset the server issued. [reset_url] is the one-time link, present for `link` delivery only and never again:
 * it is a secret, so [toString] hides it and it is never logged or saved.
 */
@Serializable
data class SupportReset(
    val delivery: String,
    /** `not_requested` (link), `sent` or `failed_or_unknown` (email: the server could not confirm it). */
    val delivery_status: String,
    val expires_at: String,
    val reset_url: String? = null,
) {
    override fun toString(): String = "SupportReset(delivery=$delivery, status=$delivery_status, expires_at=$expires_at, reset_url=<redacted>)"
}

@Serializable
data class SupportResetResponse(val reset: SupportReset, val person: PersonSummary)

// ---- Sessions and devices ------------------------------------------------------------------------------

/** One sign-in of a person. The server sends no address: only the device, the profile and when. */
@Serializable
data class SupportSession(
    val id: String,
    val device_name: String = "",
    val device_id: String = "",
    val device_platform: String = "",
    val auth_method: String = "",
    val profile_id: String? = null,
    val profile_name: String? = null,
    val created_at: String = "",
    val last_seen_at: String? = null,
    val expires_at: String = "",
)

@Serializable
data class SupportSessionsResponse(val sessions: List<SupportSession> = emptyList())

@Serializable
data class SupportDeviceProfile(val profile_id: String = "", val profile_name: String = "")

@Serializable
data class SupportDevice(
    val device_id: String,
    val name: String = "",
    val platform: String = "",
    val last_seen_at: String = "",
    val profiles: List<SupportDeviceProfile> = emptyList(),
)

@Serializable
data class SupportDevicesResponse(val devices: List<SupportDevice> = emptyList())

@Serializable
data class SupportRevoked(val sessions: Long = 0)

/** The answer to "sign out everywhere": the person, with the revision the sign-out raised, and how many sessions ended. */
@Serializable
data class SupportSignOutResponse(val person: PersonSummary, val revoked: SupportRevoked = SupportRevoked())

// ---- Profiles ------------------------------------------------------------------------------------------

@Serializable
data class SupportProfileCreate(val name: String, val group_id: Long? = null)

@Serializable
data class SupportProfileRename(val expected_revision: Long, val name: String)

@Serializable
data class SupportProfileResponse(val profile: ProfileSummary, val person: PersonSummary)

@Serializable
data class SupportProfileOnlyResponse(val profile: ProfileSummary)

// ---- Streams -------------------------------------------------------------------------------------------

@Serializable
data class SupportStreamDevice(val name: String = "", val platform: String = "", val id: String = "", val app: String = "")

/** One live stream of the organization as support sees it. */
@Serializable
data class SupportStream(
    val session_id: String,
    val account_id: Long = 0,
    val person_name: String = "",
    val profile_id: String = "",
    val profile_name: String = "",
    val title: String = "",
    val series_name: String? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val media_type: String = "",
    val started_at: String = "",
    val updated_at: String = "",
    val position_seconds: Double = 0.0,
    val duration_seconds: Long? = null,
    val is_paused: Boolean = false,
    /** The playback decision: `direct`, `remux`, `direct_stream` or `transcode`. */
    val play_method: String = "",
    val video_decision: String = "",
    val audio_decision: String = "",
    val device: SupportStreamDevice = SupportStreamDevice(),
    /** False for the administrator's own stream and for a platform account's. */
    val can_stop: Boolean = false,
    val can_message: Boolean = false,
)

@Serializable
data class SupportStreamsPage(val streams: List<SupportStream> = emptyList(), val next_cursor: String? = null)

@Serializable
data class SupportStreamStop(val reason: String? = null)

@Serializable
data class SupportStreamStopResult(
    val session_id: String = "",
    val authority_revoked: Boolean = false,
    val already_revoked: Boolean = false,
    val client_notified: Boolean = false,
)

@Serializable
data class SupportStreamMessage(val command_id: String, val sequence: Long, val message: String, val title: String? = null)

@Serializable
data class SupportStreamMessageReceipt(
    val command_id: String = "",
    val sequence: Long = 0,
    /** `applied` or `replayed`. */
    val outcome: String = "",
    /** `dispatched` or `fallback_scheduled`. */
    val delivery: String = "",
)

// ---- The owner's break-glass access --------------------------------------------------------------------

/** The `support` object of the pass request: a [reason] opens the access, an [access_id] renews the one already open. */
@Serializable
data class AdminSessionSupport(val reason: String? = null, val access_id: String? = null)

/** `GET /admin/platform/organizations/`: every organization on the server, for the Owner's support access. */
@Serializable
data class PlatformOrganizationsPage(val organizations: List<OrganizationSummary> = emptyList(), val next_cursor: String? = null)

/** What the server asks for, so the app asks the same before sending anything. */
object SupportReason {
    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 500

    /** The trimmed reason when it is acceptable (10 to 500 characters, no control characters), else null. */
    fun valid(raw: String): String? {
        val text = raw.trim()
        return text.takeIf { it.length in MIN_LENGTH..MAX_LENGTH && it.none(Char::isISOControl) }
    }
}
