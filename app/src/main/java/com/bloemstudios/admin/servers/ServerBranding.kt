package com.bloemstudios.admin.servers

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.getHealth
import kotlinx.serialization.Serializable

/**
 * GET /api/v1/health (the one v1 route the server still serves; see StatusApi.kt) is public/unauthenticated and confirmed live. Fields
 * are optional -- a minimally-configured server may omit server_name and/or
 * server_id entirely.
 */
@Serializable
data class HealthResponse(
    val status: String? = null,
    val server_name: String? = null,
    val server_id: String? = null
)

/**
 * Resolves the display name to show for a newly-added server: prefer the
 * server's own advertised name from the health endpoint (trimmed, must be
 * non-blank), falling back to [hostFallback] only when the field is
 * absent/blank. Health failures are intentionally propagated so admission
 * can reject an incompatible or unreachable server. [client] must not
 * require auth for this call to work --
 * the caller is expected to pass an unauthenticated client (no access
 * token set).
 */
suspend fun fetchServerDisplayName(client: ApiClient, hostFallback: String): String {
    val name = client.getHealth().server_name?.trim()
    return if (!name.isNullOrEmpty()) name else hostFallback
}
