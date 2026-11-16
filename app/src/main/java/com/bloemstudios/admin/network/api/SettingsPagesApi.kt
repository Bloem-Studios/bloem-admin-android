package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.serverconfig.RestartKeys
import com.bloemstudios.admin.serverconfig.SensitiveStatus
import com.bloemstudios.admin.serverconfig.SettingsReceipt
import com.bloemstudios.admin.serverconfig.SettingsWriteRequest
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

private const val SETTINGS = "/api/v2/admin/settings"

/** Keys, and key prefixes, that only take effect after a restart. Compiled into the server, so it never changes while it runs. */
suspend fun ApiClient.getRestartKeys(): RestartKeys = get("$SETTINGS/restart-keys")

/** Which secrets are set, and which the server's environment owns. Names only, never values. */
suspend fun ApiClient.getSensitiveStatus(): SensitiveStatus = get("$SETTINGS/sensitive-status")

/**
 * Saves several settings in one write. [validator] is the ETag of the effective-settings snapshot the edits were made
 * on; the server answers 412 when anything changed since. Never replayed after a 401.
 */
suspend fun ApiClient.updateSettings(values: Map<String, String>, validator: String): SettingsReceipt =
    putConditional<SettingsReceipt, SettingsWriteRequest>(SETTINGS, SettingsWriteRequest(values), validator)

@Serializable
data class SettingsCheckRequest(val values: Map<String, String>, val dirty_keys: List<String>)

@Serializable
data class SettingsCheckResult(val success: Boolean = false, val message: String = "")

/** One connection check (redis, s3_public, s3_private, meilisearch, mdblist, ai_chat, ai_transcription...) against the given values. */
suspend fun ApiClient.checkSettingsConnection(kind: String, values: Map<String, String>, dirtyKeys: List<String>): SettingsCheckResult =
    postOnce("$SETTINGS/check/${kind.encodeURLPathPart()}", SettingsCheckRequest(values, dirtyKeys))

@Serializable
data class EmailTestRequest(val to: String)

@Serializable
data class EmailTestResult(val ok: Boolean = false, val duration_ms: Long = 0, val message: String = "")

/** Sends one real message through the saved mail settings. */
suspend fun ApiClient.sendTestEmail(to: String): EmailTestResult = postOnce("/api/v2/admin/email/test", EmailTestRequest(to))
