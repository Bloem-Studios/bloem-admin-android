package com.bloemstudios.admin.streams

import kotlinx.serialization.Serializable

// One row of the v2 admin session list (AdminPlaybackSession). The schema marks
// session_id, user_id, username, profile_id, media_title, media_type, play_method,
// position_seconds, file_duration (nullable), is_paused and the bitrate fields as
// always present; everything else is omitted when it does not apply (target_*
// only while transcoding, series/episode fields only for TV, client and device
// fields when the player did not report them), so null is the steady-state value.
// The decision and codec fields stay nullable so a row from a server that omits
// them still decodes. The model keeps only what the screens show or need to address
// the row; the rest of the schema is ignored on decode.
@Serializable
data class AdminSession(
    val session_id: String,
    val user_id: String,
    val username: String,
    val profile_id: String,
    val media_title: String,
    val media_type: String,
    val play_method: String,
    val position_seconds: Double,
    val file_duration: Double?,
    val is_paused: Boolean,
    val profile_name: String? = null,
    val device_platform: String? = null,
    val client_name: String? = null,
    val stream_bitrate_kbps: Int? = null,
    val video_decision: String? = null,
    val audio_decision: String? = null,
    val effective_play_method: String? = null,
    val transcode_hw_accel: String? = null,
    val source_video_resolution: String? = null,
    val source_video_codec: String? = null,
    val target_resolution: String? = null,
    val target_video_codec: String? = null,
    val source_audio_codec: String? = null,
    val target_audio_codec: String? = null,
    val series_name: String? = null,
    val episode_name: String? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    // Added for the stream details. All are optional on the wire and null when the server
    // (or the player) did not report them.
    val transcode_audio: Boolean = false,
    val stream_location: String? = null,
    val client_label: String? = null,
    val client_label_full: String? = null,
    val client_version: String? = null,
    val client_ip: String? = null,
    val started_at: String? = null,
    val updated_at: String? = null,
    val reporting_node: String? = null,
    val node_display_name: String? = null,
    val source_container: String? = null,
    val source_bitrate_kbps: Int? = null,
    val source_audio_channels: Int? = null,
    val source_audio_language: String? = null,
    val source_audio_title: String? = null,
    val source_video_height: Int? = null,
    val target_audio_channels: Int? = null,
    val target_bitrate_kbps: Int? = null,
    val tone_map_mode: String? = null,
    val output_container: String? = null,
    val output_protocol: String? = null,
    val routing_workload: String? = null,
    val routing_execution: String? = null,
    val routing_execution_node_name: String? = null,
    val routing_egress: String? = null,
    val routing_egress_node_name: String? = null
)

fun streamViewerLabel(session: AdminSession): String =
    listOfNotNull(session.username, session.client_name?.takeIf(String::isNotBlank)).joinToString(" · ")
