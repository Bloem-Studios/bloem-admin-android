package com.bloemstudios.admin.requests

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * One media request as `GET /api/v2/admin/requests` returns it (the server's `MediaRequest`). The fields
 * the queue shows beyond the original eight are defaulted, so an older server's answer still reads.
 */
@Serializable
data class MediaRequest(
    val id: String,
    val provider: String,
    /** `movie` or `series`. */
    val media_type: String,
    val title: String,
    /** pending, approved, queued, downloading, completed. */
    val status: String,
    /** active, declined, cancelled, failed. */
    val outcome: String,
    val requested_by_user_id: String? = null,
    val last_error: String? = null,
    /** The one state the requester sees: pending, approved, processing, partially_available, available, declined, cancelled or failed. */
    val state: String? = null,
    val outcome_reason: String? = null,
    val year: Int? = null,
    val poster_path: String? = null,
    val overview: String? = null,
    /** Series only: the requested seasons; empty means the whole series. */
    val seasons: List<Int> = emptyList(),
    /** `direct` or `watchlist`; more values may be added. */
    val source: String = "direct",
    val tmdb_id: Long? = null,
    val targets: List<RequestTarget> = emptyList(),
    val created_at: String? = null,
    val approved_at: String? = null,
    val completed_at: String? = null
)

/** Where one quality of a request went: a download server and its progress there. */
@Serializable
data class RequestTarget(
    val id: String,
    val quality: String,
    val status: String,
    val instance_name: String? = null,
    val integration_kind: String? = null,
    /** The routing rule that sent it there, as named when it was sent. */
    val route_name: String? = null,
    val last_error: String? = null,
    val download: RequestDownload? = null
)

/** How far a target's downloads are, while its download server reports them. */
@Serializable
data class RequestDownload(
    /** queued, downloading, paused, stalled, importing or import_blocked; more may be added. */
    val phase: String,
    /** Rounded down; absent while the size is unknown. */
    val percent: Int? = null
)

/** `GET /api/v2/admin/requests/counts`: how many requests each queue view holds. */
@Serializable
data class RequestCounts(
    val needs_approval: Int = 0,
    val in_progress: Int = 0,
    val failed: Int = 0,
    val done: Int = 0
) {
    operator fun get(view: RequestQueueView): Int = when (view) {
        RequestQueueView.NeedsApproval -> needs_approval
        RequestQueueView.InProgress -> in_progress
        RequestQueueView.Failed -> failed
        RequestQueueView.Done -> done
    }
}

/**
 * `GET /api/v2/admin/requests/capabilities`. [available] is false where the server has no request administration;
 * a server that omits it is taken to have it. [guarded_configuration] says the request settings are edited with If-Match
 * (the Settings page for Requests needs it).
 */
@Serializable
data class RequestCapabilities(val available: Boolean = true, val guarded_configuration: Boolean = false)

/** One entry of a request's history (`GET /api/v2/admin/requests/{id}/events`). */
@Serializable
data class RequestEvent(
    val id: String,
    val type: String,
    val message: String? = null,
    val actor_username: String? = null,
    val created_at: String? = null
)

/** The server's RFC 3339 instants, with or without fractional seconds. */
object RequestDates {
    fun parse(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }
}
