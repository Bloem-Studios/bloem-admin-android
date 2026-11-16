package com.bloemstudios.admin.activity

import kotlinx.serialization.Serializable

/**
 * The fields the playback history screens use from AdminPlaybackHistoryEntry (GET /api/v2/admin/playback-history).
 * Everything after [ended_at] has a default, so a server that omits a field still decodes.
 *
 * [watched_seconds] is the position the play ended at, not how long it was watched; [played_seconds] is the measured
 * watch time and is null for a play recorded before it was measured. [media_item_id], [series_title] and [username]
 * are empty when the title or the account is gone.
 */
@Serializable
data class PlaybackHistoryEntry(
    val session_id: String,
    val user_id: String,
    val username: String,
    val media_title: String,
    val media_type: String,
    val watched_seconds: Double,
    val duration_seconds: Double?,
    val completed: Boolean,
    val started_at: String,
    val ended_at: String? = null,
    val profile_id: String = "",
    val profile_name: String = "",
    val media_item_id: String = "",
    val play_method: String = "",
    val series_title: String = "",
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val played_seconds: Double? = null,
    val paused_seconds: Double? = null,
    val client_ip: String? = null,
    val device_platform: String? = null,
    val client_name: String? = null,
    val client_version: String? = null,
    val video_decision: String? = null,
    val audio_decision: String? = null,
    val end_reason: String? = null,
)
