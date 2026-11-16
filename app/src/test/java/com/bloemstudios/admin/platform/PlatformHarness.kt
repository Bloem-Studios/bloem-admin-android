package com.bloemstudios.admin.platform

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant

/** The text of a request body a test reads inside a non-suspending route. */
fun HttpRequestData.bodyText(): String = when (val content = body) {
    is io.ktor.http.content.OutgoingContent.ByteArrayContent -> String(content.bytes())
    is io.ktor.http.content.TextContent -> content.text
    else -> ""
}

/**
 * A platform session over fake servers: the account client mints platform passes and lists the account's own memberships;
 * the pass client answers whatever [route] says. [passRequests] holds every call made with a pass, in order.
 */
class PlatformHarness(
    /** The account's own organizations (`GET /api/bloem/v2/organizations`), for "Open as administrator". */
    var accountOrganizations: String = """{"organizations":[]}""",
    var route: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.NotFound to PlatformFixtures.error("not_found") },
) {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    val passRequests = mutableListOf<HttpRequestData>()
    val accountRequests = mutableListOf<HttpRequestData>()
    private var minted = 0

    val account = ApiClient("https://example.test", "account-token", MockEngine { request ->
        accountRequests += request
        when {
            request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/admin/session") ->
                respond(PlatformFixtures.platformSession(token = "pass-${++minted}"), HttpStatusCode.OK, json)
            request.url.encodedPath == "/api/bloem/v2/organizations" -> respond(accountOrganizations, HttpStatusCode.OK, json)
            else -> respond(PlatformFixtures.error("not_found"), HttpStatusCode.NotFound, json)
        }
    })

    val session = PlatformSession(
        account = account, clock = { Instant.parse("2026-11-14T12:00:00Z") },
        clientFactory = { origin: ServerOrigin, snapshot: AccessTokenSnapshot, recovery: UnauthorizedRecovery ->
            ApiClient(origin, snapshot, MockEngine { request ->
                passRequests += request
                val (status, body) = route(request)
                respond(body, status, json)
            }, recovery)
        },
    )

    /** The pass requests whose path ends with [suffix], in order. */
    fun passCalls(suffix: String) = passRequests.filter { it.url.encodedPath.endsWith(suffix) }

    fun accountMemberOf(vararg organizationIds: String) {
        accountOrganizations = """{"organizations":[""" + organizationIds.joinToString(",") {
            """{"id":"$it","slug":"x","name":"X","default":false,"membership_id":"mem-$it","membership_role":"admin","policy_revision":1,"security_revision":1}"""
        } + "]}"
    }
}
