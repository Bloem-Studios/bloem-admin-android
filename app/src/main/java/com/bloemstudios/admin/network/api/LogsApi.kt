package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.audit.AuditLogEntry
import com.bloemstudios.admin.logs.LogFilter
import com.bloemstudios.admin.logs.OperationalLogEntry
import com.bloemstudios.admin.network.ApiClient

private fun String?.filterValue() = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * One page of the audit trail, newest first. [cursor] is the previous page's `next_cursor`; null asks for the first page.
 * [userId] filters on the account the request ran as; [actorUserId] on the acting account, including an impersonator
 * (the web's Audit filter). [playbackSessionId] and [requestId] narrow to one playback session or request.
 */
suspend fun ApiClient.listAuditLogs(
    userId: String?,
    cursor: String?,
    limit: Int = 100,
    actorUserId: String? = null,
    playbackSessionId: String? = null,
    requestId: String? = null,
): ApiCollection<AuditLogEntry> = getCollection(
    "/api/v2/admin/logs/audit",
    mapOf(
        "limit" to limit.toString(),
        "cursor" to cursor,
        "user_id" to userId.filterValue(),
        "actor_user_id" to actorUserId.filterValue(),
        "playback_session_id" to playbackSessionId.filterValue(),
        "request_id" to requestId.filterValue(),
    )
)

/** One page of application logs, newest first, optionally narrowed by [level] and free-text [q]. */
suspend fun ApiClient.listAppLogs(
    level: String?,
    q: String?,
    cursor: String?,
    limit: Int = 100,
    component: String? = null,
    requestId: String? = null,
    playbackSessionId: String? = null,
): ApiCollection<OperationalLogEntry> = getCollection(
    "/api/v2/admin/logs/app",
    mapOf(
        "limit" to limit.toString(),
        "cursor" to cursor,
        "level" to level.filterValue(),
        "q" to q.filterValue(),
        "component" to component.filterValue(),
        "request_id" to requestId.filterValue(),
        "playback_session_id" to playbackSessionId.filterValue(),
    )
)

/** One page of application logs for [filter]. */
suspend fun ApiClient.listAppLogs(filter: LogFilter, cursor: String?, limit: Int = 100): ApiCollection<OperationalLogEntry> =
    listAppLogs(filter.level, filter.q, cursor, limit, filter.component, filter.requestId, filter.playbackSessionId)
