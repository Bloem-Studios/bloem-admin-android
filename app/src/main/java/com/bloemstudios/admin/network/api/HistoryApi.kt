package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.activity.HistoryFilter
import com.bloemstudios.admin.activity.PlaybackHistoryEntry
import com.bloemstudios.admin.network.ApiClient

/**
 * One page of playback history, newest first. [cursor] is the previous page's `next_cursor`; null asks for the first page.
 * [profileId] and [completed] ("true" or "false"; null for both) narrow it as the web's History page does.
 */
suspend fun ApiClient.listPlaybackHistory(
    userId: String?,
    cursor: String?,
    limit: Int = 50,
    profileId: String? = null,
    completed: String? = null,
): ApiCollection<PlaybackHistoryEntry> = getCollection(
    "/api/v2/admin/playback-history",
    mapOf(
        "limit" to limit.toString(),
        "cursor" to cursor,
        "user_id" to userId?.trim()?.takeIf { it.isNotEmpty() },
        "profile_id" to profileId?.trim()?.takeIf { it.isNotEmpty() },
        "completed" to completed,
    )
)

/** One page of playback history for [filter]. */
suspend fun ApiClient.listPlaybackHistory(filter: HistoryFilter, cursor: String?, limit: Int = 50): ApiCollection<PlaybackHistoryEntry> =
    listPlaybackHistory(filter.userId, cursor, limit, filter.profileId, filter.completion.wire)
