package com.bloemstudios.admin.audit

import kotlinx.serialization.Serializable

/**
 * One AdminAuditLog row from the v2 audit-log endpoint. id, timestamp, client_ip, method, path,
 * status_code and duration_ms are always present; everything else is omitted when it does not apply
 * (an unauthenticated request has no user_id; impersonator_user_id is recorded only when the request
 * was made while impersonating another user; action, target_* and changes are present when the route
 * names what it changed). All identifiers are strings.
 */
@Serializable
data class AuditLogEntry(
    val id: String,
    val timestamp: String,
    val client_ip: String,
    val user_id: String? = null,
    val impersonator_user_id: String? = null,
    val session_id: String? = null,
    val playback_session_id: String? = null,
    val request_id: String? = null,
    val node_id: String? = null,
    val method: String,
    val path: String,
    val path_pattern: String? = null,
    val status_code: Int,
    val user_agent: String? = null,
    val duration_ms: Long,
    val action: String? = null,
    val target_type: String? = null,
    val target_id: String? = null,
    val changes: List<AuditChange>? = null,
)

/**
 * One field an audited request changed. [before] and [after] are JSON text ("\"admin\"", "3", "null", "[1,2]"); a side
 * the server did not record is null, and a field with neither side only says that it changed.
 */
@Serializable
data class AuditChange(val field: String, val before: String? = null, val after: String? = null)
