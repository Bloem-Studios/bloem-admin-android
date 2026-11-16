package com.bloemstudios.admin.requests

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.users.FixturePerson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** A request as the queue holds it, with defaults a test overrides. */
fun queueRequest(
    id: String = "r1",
    title: String = "Dune",
    status: String = "pending",
    outcome: String = "active",
    mediaType: String = "movie",
    state: String? = null,
    requestedBy: String? = "68",
    seasons: List<Int> = emptyList(),
    year: Int? = 2021,
    source: String = "direct",
    targets: List<RequestTarget> = emptyList(),
    lastError: String? = null,
    reason: String? = null,
    createdAt: Instant? = Instant.now().minusSeconds(3 * 3600),
    overview: String? = null,
    tmdb: Long? = 438631,
) = MediaRequest(
    id = id, provider = "tmdb", media_type = mediaType, title = title, status = status, outcome = outcome,
    requested_by_user_id = requestedBy, last_error = lastError, state = state, outcome_reason = reason, year = year,
    poster_path = "/dune.jpg", overview = overview, seasons = seasons, source = source, tmdb_id = tmdb, targets = targets,
    created_at = createdAt?.toString(),
)

/**
 * A scripted /api/v2 admin server for the Requests page: the counts, the pages of the queue in order (the last one
 * repeats), the account list, one request's history, and actions that answer [actionStatus]. Every call is recorded.
 */
class RequestServerFixture(
    var counts: String = """{"needs_approval":1,"in_progress":2,"failed":3,"done":4}""",
    val pages: MutableList<String> = mutableListOf(),
    val people: List<FixturePerson> = listOf(FixturePerson("68", "alex")),
    var events: String = """{"items":[]}""",
    var actionStatus: HttpStatusCode = HttpStatusCode.OK,
    /** What `GET /api/v2/admin/requests/capabilities` answers. */
    var capabilities: String = """{"available":true}""",
    /** Request IDs whose approve or decline the server refuses with 409 and this reason, whatever [actionStatus] says. */
    val refused: MutableMap<String, String> = mutableMapOf(),
) {
    class Call(val method: HttpMethod, val path: String, val query: Map<String, String>, val body: String?)

    val calls = CopyOnWriteArrayList<Call>()
    val posts get() = calls.filter { it.method == HttpMethod.Post }
    val queueReads get() = calls.filter { it.method == HttpMethod.Get && it.path == "/api/v2/admin/requests" }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    fun page(requests: List<MediaRequest>, next: String? = null): String {
        val rows = requests.joinToString(",") { kotlinx.serialization.json.Json.encodeToString(MediaRequest.serializer(), it) }
        val info = if (next == null) "" else ""","page":{"has_more":true,"next_cursor":"$next"}"""
        return """{"items":[$rows]$info}"""
    }

    val client = ApiClient("https://fixture.invalid", "tok", MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
        calls += Call(request.method, path, request.url.parameters.entries().associate { it.key to it.value.first() }, body)
        when {
            request.method == HttpMethod.Post && refused.containsKey(path.substringAfter("/requests/").substringBefore("/")) -> respond(
                """{"type":"about:blank","title":"Conflict","status":409,"detail":"${refused.getValue(path.substringAfter("/requests/").substringBefore("/"))}","instance":"urn:x"}""",
                HttpStatusCode.Conflict, json,
            )
            path == "/api/v2/admin/requests/capabilities" -> respond(capabilities, HttpStatusCode.OK, json)
            request.method == HttpMethod.Post ->
                if (actionStatus == HttpStatusCode.OK) respond("{}", HttpStatusCode.OK, json)
                else respond(
                    """{"type":"about:blank","title":"Conflict","status":409,"detail":"Already approved","instance":"urn:x"}""",
                    actionStatus, json,
                )
            path == "/api/v2/admin/requests/counts" -> respond(counts, HttpStatusCode.OK, json)
            path == "/api/v2/admin/users" -> respond("""{"items":[${people.joinToString(",") { it.json() }}]}""", HttpStatusCode.OK, json)
            path.endsWith("/events") -> respond(events, HttpStatusCode.OK, json)
            else -> respond(if (pages.size > 1) pages.removeAt(0) else pages.firstOrNull() ?: """{"items":[]}""", HttpStatusCode.OK, json)
        }
    })
}
