package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.dashboard.AdminStats
import com.bloemstudios.admin.dashboard.SystemResources
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import io.ktor.http.Url
import com.bloemstudios.admin.serverconfig.AdminSetting
import com.bloemstudios.admin.serverconfig.UpdateSettingRequest
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject
import com.bloemstudios.admin.serverconfig.ServerStatus
import com.bloemstudios.admin.servers.HealthResponse

/**
 * Public, unauthenticated liveness check. This is the one v1 route the server still
 * serves; every other v1 route answers 410.
 */
suspend fun ApiClient.getHealth(): HealthResponse = get("/api/v1/health")

/** The same route as [getHealth], as a bare address, for the background check that must not use a signed-in client. */
fun ServerOrigin.healthUrl(): Url = resolve("/api/v1/health")

/** Admin-only server status. Doubles as the admin-access probe used when adding a server and by the gate. */
suspend fun ApiClient.getServerStatus(): ServerStatus = get("/api/v2/admin/server/status")

/** Catalog and playback totals. `refresh=true` is sent only when the user forced a refresh. */
suspend fun ApiClient.getAdminStats(refresh: Boolean): AdminStats =
    if (refresh) get("/api/v2/admin/stats", mapOf("refresh" to "true")) else get("/api/v2/admin/stats")

/** Host CPU, memory and network. `available:false` is a normal answer, not an error. */
suspend fun ApiClient.getSystemResources(): SystemResources = get("/api/v2/admin/system/resources")

/** Every server setting as key to value. */
suspend fun ApiClient.getSettings(): Map<String, String> = get("/api/v2/admin/settings")

/**
 * Every active setting, including keys still at their default, plus the ETag of the whole settings snapshot.
 * That ETag is the validator PUT /settings/{key} accepts as If-Match; GET /settings/{key} sends none.
 */
suspend fun ApiClient.getEffectiveSettingsWithValidator(): Pair<Map<String, String>, String?> =
    getWithValidator("/api/v2/admin/settings/effective")

/** Guarded write: sends [validator] as If-Match and never replays after a 401. */
suspend fun ApiClient.updateSetting(key: String, value: String, validator: String): AdminSetting =
    putConditional<AdminSetting, UpdateSettingRequest>(
        "/api/v2/admin/settings/${key.encodeURLPathPart()}", UpdateSettingRequest(value), validator
    )

/** Asks the server to restart. The route requires a body, which may be empty (`{}`). The 202 answer says whether this was the first request. */
suspend fun ApiClient.restartServer(): com.bloemstudios.admin.serverconfig.RestartAnswer = try {
    post("/api/v2/admin/server/restart", JsonObject(emptyMap()))
} catch (_: io.ktor.serialization.ContentConvertException) {
    // The restart was accepted (the 2xx came back); an answer this app cannot read is not a failure.
    com.bloemstudios.admin.serverconfig.RestartAnswer()
} catch (_: io.ktor.client.call.NoTransformationFoundException) {
    com.bloemstudios.admin.serverconfig.RestartAnswer()
} catch (_: kotlinx.serialization.SerializationException) {
    com.bloemstudios.admin.serverconfig.RestartAnswer()
}
