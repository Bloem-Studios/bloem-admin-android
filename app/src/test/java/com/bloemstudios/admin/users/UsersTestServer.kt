package com.bloemstudios.admin.users

import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted /api/v2 users server. GET /admin/users lists [users]; GET /admin/users/{id} returns the
 * first fixture with [etag] (no header when null); PUT and DELETE answer [writeStatus] and record
 * their If-Match.
 */
internal class UsersTestServer(
    var users: List<String> = listOf(V2Fixtures.adminUser()),
    var etag: String? = "\"v1\"",
    var writeStatus: HttpStatusCode = HttpStatusCode.NoContent,
    var writeBody: String = "",
    /** The signed-in account GET /account/me reports. */
    var meId: String = "1",
    var capabilities: String = V2Fixtures.adminAccountCapabilities()
) {
    class Call(val method: HttpMethod, val path: String, val ifMatch: String?, val body: String?, val query: String = "")

    /** GET bodies by exact path, checked before the user routes: access groups, libraries, settings and so on. */
    val routes = java.util.concurrent.ConcurrentHashMap<String, String>()

    val calls = CopyOnWriteArrayList<Call>()
    val writes get() = calls.filter { it.method == HttpMethod.Put || it.method == HttpMethod.Delete }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    fun client(): ApiClient = ApiClient("https://example.test", "tok", MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
        calls += Call(request.method, path, request.headers[HttpHeaders.IfMatch], body, request.url.encodedQuery)
        when {
            request.method == HttpMethod.Get && routes.containsKey(path) -> respond(routes.getValue(path), HttpStatusCode.OK, json)
            request.method == HttpMethod.Get && path == "/api/v2/admin/users" ->
                respond(V2Fixtures.adminUsersPage(*users.toTypedArray()), HttpStatusCode.OK, json)
            request.method == HttpMethod.Get && path == "/api/v2/account/me" ->
                respond("""{"id":"$meId","username":"me","email":"me@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""", HttpStatusCode.OK, json)
            request.method == HttpMethod.Get && path == "/api/v2/admin/users/capabilities" ->
                respond(capabilities, HttpStatusCode.OK, json)
            request.method == HttpMethod.Get && path.startsWith("/api/v2/admin/users/") -> {
                val headers = if (etag == null) json else headersOf(
                    HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag!!)
                )
                respond(users.first(), HttpStatusCode.OK, headers)
            }
            request.method == HttpMethod.Post -> respond("{}", HttpStatusCode.Created, json)
            else -> respond(writeBody, writeStatus, json)
        }
    })
}
