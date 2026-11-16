package com.bloemstudios.admin.logs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One AdminOperationalLog row from the v2 app-log endpoint -- application/operational logs,
 * distinct from the request audit trail (see com.bloemstudios.admin.audit.AuditLogEntry). Only
 * id/timestamp/level/component/message are always present; a log line emitted outside any request
 * has no request_id/session_id at all.
 *
 * `attrs` is decoded as a raw JsonObject but deliberately not rendered; it is kept so a well-formed
 * `attrs` object does not fail decoding.
 */
@Serializable
data class OperationalLogEntry(
    val id: String,
    val timestamp: String,
    val level: String,
    val component: String,
    val message: String,
    val request_id: String? = null,
    val user_id: String? = null,
    val session_id: String? = null,
    val playback_session_id: String? = null,
    val client_ip: String? = null,
    val node_id: String? = null,
    val attrs: JsonObject? = null
)
