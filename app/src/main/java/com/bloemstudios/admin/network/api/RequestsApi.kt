package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.AppDataSignal
import com.bloemstudios.admin.requests.MediaRequest
import com.bloemstudios.admin.requests.RequestCapabilities
import com.bloemstudios.admin.requests.RequestCounts
import com.bloemstudios.admin.requests.RequestEvent
import com.bloemstudios.admin.requests.RequestQueueView
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

enum class RequestAction(val path: String) { APPROVE("approve"), DECLINE("decline"), RETRY("retry"), CANCEL("cancel") }

@Serializable
private data class RequestActionBody(val reason: String? = null)

/** The route declares `limit` with maximum 50 and the server rejects larger values. */
const val REQUEST_PAGE_LIMIT = 50

/** One page of a queue view, newest request first. [nextCursor] is null on the last page. */
data class RequestQueuePage(val items: List<MediaRequest>, val nextCursor: String?)

private fun requestPath(id: String, suffix: String = "") = "/api/v2/admin/requests/${id.encodeURLPathPart()}$suffix"

/** Every page of the request list. */
suspend fun ApiClient.listRequests(): List<MediaRequest> =
    readAllPages("/api/v2/admin/requests", pageSize = REQUEST_PAGE_LIMIT)

/**
 * One page of a queue view, filtered like the web admin's queue: [search] is a title substring or an exact
 * TMDB ID, [mediaType] is `movie` or `series`, [requestedBy] an account ID.
 */
suspend fun ApiClient.listRequestQueuePage(
    view: RequestQueueView,
    search: String? = null,
    mediaType: String? = null,
    requestedBy: String? = null,
    cursor: String? = null,
    limit: Int = REQUEST_PAGE_LIMIT
): RequestQueuePage {
    val page = getCollection<MediaRequest>(
        "/api/v2/admin/requests",
        mapOf(
            "view" to view.wire,
            "q" to search?.trim()?.takeIf { it.isNotEmpty() },
            "media_type" to mediaType,
            "requested_by_user_id" to requestedBy,
            "limit" to limit.coerceIn(1, REQUEST_PAGE_LIMIT).toString(),
            "cursor" to cursor
        )
    )
    val next = page.page?.takeIf { it.has_more }?.next_cursor?.takeIf { it.isNotEmpty() }
    return RequestQueuePage(page.items, next)
}

/** How many requests each queue view holds. */
suspend fun ApiClient.getRequestCounts(): RequestCounts = get("/api/v2/admin/requests/counts")

/** Whether the server offers request administration at all. */
suspend fun ApiClient.getRequestCapabilities(): RequestCapabilities = get("/api/v2/admin/requests/capabilities")

/** A request's history, newest first. */
suspend fun ApiClient.listRequestEvents(id: String): List<RequestEvent> =
    getCollection<RequestEvent>(requestPath(id, "/events")).items

/** The server requires a JSON object body for every action; [reason] is optional and omitted when null. */
suspend fun ApiClient.actOnRequest(id: String, action: RequestAction, reason: String? = null) {
    post<Unit, RequestActionBody>(requestPath(id, "/${action.path}"), RequestActionBody(reason))
    // Approve and Decline change the queue a widget shows; the other actions move a request that is not waiting.
    if (action == RequestAction.APPROVE || action == RequestAction.DECLINE) AppDataSignal.emit(AppDataSignal.RequestDecided(origin.issuer))
}
