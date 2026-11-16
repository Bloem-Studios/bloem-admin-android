package com.bloemstudios.admin.users

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One account of a scripted server. */
class FixturePerson(
    val id: String,
    val name: String,
    val role: String = "user",
    var enabled: Boolean = true,
    val passwordLogin: Boolean = true,
    val owner: Boolean = false,
    var groupId: String? = null,
    var libraryIds: String = "null",
    var permissions: String = "[]"
) {
    fun json() = """{"id":"$id","username":"$name","email":"$name@example.test","role":"$role","permissions":$permissions,"enabled":$enabled,""" +
        """"library_ids":$libraryIds,"max_playback_quality":null,"max_streams":null,"max_transcodes":null,"transcode_allowed":null,""" +
        """"audio_transcode_allowed":null,"max_profiles":null,"download_allowed":null,"download_transcode_allowed":null,""" +
        """"requests_allowed":null,"access_group_id":${groupId?.let { "\"$it\"" } ?: "null"},"password_login":$passwordLogin,"password_change_required":false,""" +
        """"is_owner":$owner,"break_glass":false,"effective_policy":{"library_ids":null,"max_playback_quality":"1080p","max_streams":6,""" +
        """"max_transcodes":2,"max_profiles":4,"transcode_allowed":true,"audio_transcode_allowed":true,"download_allowed":true,""" +
        """"download_transcode_allowed":false,"requests_allowed":true,"permissions":[]},""" +
        """"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","last_active_at":null}"""
}

class FixtureCall(val method: HttpMethod, val path: String, val ifMatch: String?, val body: String?, val query: String)

/**
 * A scripted /api/v2 server for the User page: the account list, one ETag, and any extra GET bodies by exact
 * path in [routes] (access groups, libraries, settings and so on). Writes answer [writeStatus].
 */
class UserPageFixture(val people: List<FixturePerson>, val meId: String = "1", var writeStatus: HttpStatusCode = HttpStatusCode.NoContent) {
    val calls = CopyOnWriteArrayList<FixtureCall>()
    val writes get() = calls.filter { it.method == HttpMethod.Put || it.method == HttpMethod.Delete }
    val routes = ConcurrentHashMap<String, String>()

    /** Answers a POST to `.../password-reset` with 201 and this body, when set. */
    @Volatile var resetResponse: String? = null

    /** Runs when a write arrives, before it is answered: lets a test change what the next read returns. */
    @Volatile var onWrite: (FixtureCall) -> Unit = {}
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun page(rows: List<String>) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""

    val client = ApiClient("https://fixture.invalid", "tok", MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
        val call = FixtureCall(request.method, path, request.headers[HttpHeaders.IfMatch], body, request.url.encodedQuery)
        calls += call
        if (request.method != HttpMethod.Get) onWrite(call)
        val userId = Regex("^/api/v2/admin/users/(\\d+)").find(path)?.groupValues?.get(1)
        when {
            request.method == HttpMethod.Post && path.endsWith("/password-reset") && resetResponse != null ->
                respond(resetResponse!!, HttpStatusCode.Created, json)
            request.method != HttpMethod.Get -> {
                if (writeStatus == HttpStatusCode.PreconditionFailed) respond(
                    """{"type":"https://siloserver.org/docs/api/v2/problems/precondition_failed","title":"Precondition Failed","status":412,"detail":"changed","instance":"urn:silo:request:1"}""",
                    writeStatus, headersOf(HttpHeaders.ContentType, "application/problem+json")
                ) else respond("", writeStatus)
            }
            routes.containsKey(path) -> respond(routes.getValue(path), HttpStatusCode.OK, json)
            path == "/api/v2/account/me" -> respond("""{"id":"$meId","username":"me","email":"me@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""", HttpStatusCode.OK, json)
            path == "/api/v2/admin/users/capabilities" -> respond(
                """{"available":true,"login_sessions":true,"account_devices":false,"account_downloads":false,"watch_summary":true,"password_reset_email":true}""",
                HttpStatusCode.OK, json
            )
            path == "/api/v2/admin/users" -> respond(page(people.map { it.json() }), HttpStatusCode.OK, json)
            userId != null && path == "/api/v2/admin/users/$userId" -> respond(
                people.first { it.id == userId }.json(), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\""))
            )
            path.endsWith("/watch-summary") -> respond("""{"days":30,"since":"2026-01-01T00:00:00Z","plays":0,"completed_plays":0,"watched_seconds":0,"last_played_at":null}""", HttpStatusCode.OK, json)
            else -> respond(page(emptyList()), HttpStatusCode.OK, json)
        }
    })

    companion object {
        fun page(vararg rows: String) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""
    }
}
