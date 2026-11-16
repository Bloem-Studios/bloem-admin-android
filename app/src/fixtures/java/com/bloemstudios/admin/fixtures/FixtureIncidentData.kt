package com.bloemstudios.admin.fixtures

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * Sample Playback history, Logs and Audit for the debug-only fixture screen, answered by a mock server so the real
 * screens (filters, sheets) run on it. Invented: no real account, address, library or request. The rows are plain JSON,
 * so this file links to no app class and stays valid in the minified test build.
 */
internal object FixtureIncidentData {
    private fun ago(minutes: Long): String = Instant.now().minus(Duration.ofMinutes(minutes)).toString()

    /** The playback session of the transcode on Now playing: its logs, FFmpeg lines and audit entries tie to this ID. */
    const val TRANSCODE_SESSION = "7c1d2e9a-4b3f-4a10-9e55-2b6c8f0a1d34"

    private fun row(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        fields.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                is JsonElement -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun history(
        id: String, user: String, name: String, profile: String, profileName: String, title: String, type: String, method: String,
        watched: Double, duration: Double, completed: Boolean, started: Long, ended: Long, played: Double?, item: String,
        series: String = "", season: Int? = null, episode: Int? = null, paused: Double? = null, ip: String? = null, platform: String? = null,
        client: String? = null, version: String? = null, video: String? = null, audio: String? = null, reason: String? = null,
    ) = row(
        "session_id" to id, "user_id" to user, "username" to name, "profile_id" to profile, "profile_name" to profileName,
        "media_item_id" to item, "media_title" to title, "media_type" to type, "play_method" to method, "started_at" to ago(started),
        "ended_at" to ago(ended), "watched_seconds" to watched, "duration_seconds" to duration, "completed" to completed,
        "played_seconds" to played, "series_title" to series, "season_number" to season, "episode_number" to episode, "paused_seconds" to paused,
        "client_ip" to ip, "device_platform" to platform, "client_name" to client, "client_version" to version,
        "video_decision" to video, "audio_decision" to audio, "end_reason" to reason,
    )

    private val historyRows: List<JsonObject> = listOf(
        history("h1", "2", "maria", "p1", "Living room", "Who Is Alive?", "episode", "direct", 2_652.0, 2_652.0, true, 95, 51, 2_610.0, "i1",
            series = "Severance", season = 2, episode = 3, ip = "203.0.113.24", platform = "tvos", client = "Infuse", version = "8.1",
            video = "direct", audio = "direct", reason = "stopped"),
        history("h2", "2", "maria", "p1", "Living room", "Dune: Part Two", "movie", "transcode", 4_330.0, 9_960.0, false, 260, 188, 4_270.0, "i2",
            paused = 120.0, ip = "203.0.113.24", platform = "tvos", client = "Infuse", version = "8.1", video = "transcode", audio = "direct", reason = "expired"),
        history(TRANSCODE_SESSION, "3", "jonas", "p2", "Jonas", "Oceans", "episode", "transcode", 1_980.0, 3_000.0, false, 70, 36, 1_890.0, "i3",
            series = "Planet Earth III", season = 1, episode = 2, ip = "198.51.100.7", platform = "ios", client = "Bloem", version = "1.4.1",
            video = "transcode", audio = "transcode", reason = "terminated"),
        history("h4", "4", "amira", "p3", "Amira", "The River", "movie", "remux", 5_400.0, 5_400.0, true, 1_500, 1_410, 5_380.0, "i4",
            ip = "198.51.100.31", platform = "android", client = "Bloem", version = "1.4.2", video = "remux", audio = "remux", reason = "stopped"),
        history("h5", "5", "tomas", "p4", "tomas", "Severance", "episode", "direct_stream", 3_100.0, 3_100.0, true, 2_900, 2_840, null, "i1",
            series = "Severance", season = 1, episode = 1, ip = "192.0.2.58", platform = "web", client = "Web", video = "direct", audio = "transcode", reason = "stopped"),
    )

    private fun attrs(vararg fields: Pair<String, Any>) = buildJsonObject {
        fields.forEach { (key, value) -> if (value is Number) put(key, value) else put(key, value.toString()) }
    }

    private fun log(
        id: String, minutesAgo: Long, level: String, component: String, message: String, request: String? = null, playback: String? = null,
        node: String? = "main", user: String? = null, ip: String? = null, attrs: JsonObject = JsonObject(emptyMap()),
    ) = row(
        "id" to id, "timestamp" to ago(minutesAgo), "level" to level, "component" to component, "message" to message,
        "request_id" to request, "user_id" to user, "playback_session_id" to playback, "client_ip" to ip, "node_id" to node,
        "session_id" to user?.let { "sess-$it-9d41" }, "attrs" to attrs,
    )

    private val logRows: List<JsonObject> = listOf(
        log("l1", 2, "error", "playback", "Transcode failed: encoder exited with code 1", "req-4f9a21c07b", TRANSCODE_SESSION, "gpu-1", "3", "198.51.100.7",
            attrs("method" to "GET", "path" to "/api/v2/playback/transcode/segment/seg_0042.ts", "status" to 500, "duration_ms" to 1_842)),
        log("l2", 2, "warn", "ffmpeg", "[hevc_qsv] Failed to allocate surface, retrying in software", null, TRANSCODE_SESSION, "gpu-1"),
        log("l3", 3, "info", "ffmpeg", "frame= 1204 fps= 48 q=26.0 size= 38400kB time=00:00:50.1 bitrate=6275.2kbits/s speed=1.9x", null, TRANSCODE_SESSION, "gpu-1"),
        log("l4", 4, "info", "ffmpeg", "Input #0, matroska,webm, from 'Planet Earth III S01E02.mkv'", null, TRANSCODE_SESSION, "gpu-1"),
        log("l5", 6, "info", "api", "GET /api/v2/admin/users 200", "req-81c3d7e2aa", null, "main", "1", "192.0.2.10",
            attrs("method" to "GET", "path" to "/api/v2/admin/users", "status" to 200, "duration_ms" to 18)),
        log("l6", 11, "warn", "scanner", "Skipped 3 files with unreadable metadata in /media/shows", null, null, "main"),
        log("l7", 18, "info", "taskmanager", "Scan finished: 2 new items, 0 removed", null, null, "main", attrs = attrs("new" to 2, "removed" to 0)),
        log("l8", 42, "debug", "ratelimit", "Bucket refilled for 198.51.100.7", null, null, "main"),
    )

    private fun change(field: String, before: String?, after: String?) = row("field" to field, "before" to before, "after" to after)

    private fun audit(
        id: String, minutesAgo: Long, ip: String, user: String?, method: String, path: String, status: Int, agent: String, duration: Int,
        impersonator: String? = null, session: String? = null, request: String? = null, node: String? = "main", playback: String? = null,
        action: String? = null, targetType: String? = null, targetId: String? = null, changes: List<JsonObject>? = null,
    ) = row(
        "id" to id, "timestamp" to ago(minutesAgo), "client_ip" to ip, "user_id" to user, "impersonator_user_id" to impersonator,
        "session_id" to session, "playback_session_id" to playback, "request_id" to request, "node_id" to node, "method" to method,
        "path" to path, "status_code" to status, "user_agent" to agent, "duration_ms" to duration, "action" to action,
        "target_type" to targetType, "target_id" to targetId, "changes" to changes?.let { JsonArray(it) },
    )

    private val auditRows: List<JsonObject> = listOf(
        audit("a1", 5, "192.0.2.10", "1", "PATCH", "/api/v2/admin/users/3", 200, "Bloem Admin/1.0 (Android 15)", 41, session = "sess-1-77b2",
            request = "req-5d20c9ee14", action = "Update user", targetType = "user", targetId = "3",
            changes = listOf(change("max_streams", "2", "4"), change("transcode_allowed", "false", "true"), change("role", "\"user\"", "\"admin\""))),
        audit("a2", 26, "192.0.2.10", "1", "PUT", "/api/v2/admin/access-groups/3", 200, "Bloem Admin/1.0 (Android 15)", 33, session = "sess-1-77b2",
            request = "req-0a77be3190", action = "Update access group", targetType = "access_group", targetId = "3",
            changes = listOf(change("library_ids", "null", "[1,2]"), change("max_profiles", "4", "5"))),
        audit("a3", 58, "203.0.113.90", "3", "PATCH", "/api/v2/profiles/p2", 200, "Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0", 27,
            impersonator = "1", session = "sess-3-c1e0", request = "req-9b6f44d7c2", action = "Update profile", targetType = "profile", targetId = "p2",
            changes = listOf(change("pin", "false", "true"))),
        audit("a4", 130, "198.51.100.7", "3", "GET", "/api/v2/playback/transcode/segment/seg_0042.ts", 500, "Bloem/1.4.1 (iOS 18.1)", 1_842,
            session = "sess-3-9d41", request = "req-4f9a21c07b", node = "gpu-1", playback = TRANSCODE_SESSION),
        audit("a5", 300, "192.0.2.10", "1", "POST", "/api/v2/admin/api-keys", 201, "Bloem Admin/1.0 (Android 15)", 52, session = "sess-1-77b2",
            request = "req-33e10a4f8b", action = "Create API key", targetType = "api_key", targetId = "9", changes = listOf(change("scopes", "null", "[]"))),
    )

    private val profilesByUser = mapOf(
        "2" to listOf("p1" to "Living room", "p5" to "maria"), "3" to listOf("p2" to "Jonas"), "4" to listOf("p3" to "Amira"),
        "5" to listOf("p4" to "tomas"),
    )

    private val contentType = headersOf(HttpHeaders.ContentType, "application/json")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun page(rows: List<JsonObject>) = buildJsonObject {
        put("items", JsonArray(rows))
        put("page", buildJsonObject { put("has_more", false) })
    }.toString()

    /** An ApiClient answering the history, log, audit, users and profiles routes from the rows above, honoring their filters. */
    val client: ApiClient by lazy {
        ApiClient("https://fixture.invalid", "fixture", MockEngine { request ->
            val path = request.url.encodedPath
            val q = request.url.parameters
            val profilesMatch = Regex("^/api/v2/admin/users/(\\d+)/profiles$").find(path)
            when {
                request.method != HttpMethod.Get -> respond("", HttpStatusCode.NoContent)
                path == "/api/v2/admin/users" -> respond(
                    """{"items":[${FixtureData.usersPageItems()}],"page":{"has_more":false}}""", HttpStatusCode.OK, contentType,
                )
                profilesMatch != null -> respond(
                    """{"items":[${profilesByUser[profilesMatch.groupValues[1]].orEmpty().joinToString(",") { """{"id":"${it.first}","name":"${it.second}"}""" }}]}""",
                    HttpStatusCode.OK, contentType,
                )
                path == "/api/v2/admin/playback-history" -> respond(
                    page(
                        historyRows.filter { r ->
                            (q["user_id"] == null || r.text("user_id") == q["user_id"]) &&
                                (q["profile_id"] == null || r.text("profile_id") == q["profile_id"]) &&
                                (q["completed"] == null || q["completed"] == "all" || (r["completed"]?.jsonPrimitive?.boolean == (q["completed"] == "true")))
                        },
                    ),
                    HttpStatusCode.OK, contentType,
                )
                path == "/api/v2/admin/logs/app" -> respond(
                    page(
                        logRows.filter { r ->
                            (q["level"] == null || r.text("level") in q["level"]!!.split(",")) &&
                                (q["component"] == null || r.text("component") == q["component"]) &&
                                (q["request_id"] == null || r.text("request_id") == q["request_id"]) &&
                                (q["playback_session_id"] == null || r.text("playback_session_id") == q["playback_session_id"]) &&
                                (q["q"] == null || r.text("message").orEmpty().contains(q["q"]!!, ignoreCase = true))
                        },
                    ),
                    HttpStatusCode.OK, contentType,
                )
                path == "/api/v2/admin/logs/audit" -> respond(
                    page(
                        auditRows.filter { r ->
                            (q["actor_user_id"] == null || r.text("user_id") == q["actor_user_id"] || r.text("impersonator_user_id") == q["actor_user_id"]) &&
                                (q["playback_session_id"] == null || r.text("playback_session_id") == q["playback_session_id"]) &&
                                (q["request_id"] == null || r.text("request_id") == q["request_id"])
                        },
                    ),
                    HttpStatusCode.OK, contentType,
                )
                else -> respond("""{"items":[],"page":{"has_more":false}}""", HttpStatusCode.OK, contentType)
            }
        })
    }
}
