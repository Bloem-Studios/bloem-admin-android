package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AuthProvider
import com.bloemstudios.admin.auth.Pkce
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.OrganizationFixtures
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddServerConnectorTest {
    private val serverId = "3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35"
    private val corpSso = AuthProvider(
        id = "26", display_name = "Corp SSO", mode = "oauth", default = false,
        installation_id = "26", native_start_path = "/api/v2/auth/oauth/26/native/start"
    )

    private class Recorder {
        val paths = mutableListOf<String>()
        val completeBodies = mutableListOf<String>()
    }

    private fun engine(
        recorder: Recorder = Recorder(),
        overrides: Map<String, Pair<String, HttpStatusCode>> = emptyMap()
    ) = MockEngine { request ->
        val path = request.url.encodedPath
        recorder.paths += path
        if (path == "/api/v2/auth/oauth/complete") {
            recorder.completeBodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        }
        overrides[path]?.let { (body, status) -> return@MockEngine respondJson(body, status) }
        when (path) {
            "/api/v1/health" -> respondJson("""{"server_name":"Bloem West"}""")
            "/api/v2/system/identity" -> respondJson(V2Fixtures.SERVER_IDENTITY)
            "/api/v2/auth/providers" -> respondJson(V2Fixtures.AUTH_PROVIDERS)
            "/api/v2/auth/login" -> respondJson(V2Fixtures.TOKEN_PAIR)
            "/api/v2/auth/oauth/complete" -> respondJson(V2Fixtures.OAUTH_COMPLETION)
            "/api/v2/admin/server/status" -> respondJson(V2Fixtures.SERVER_STATUS)
            "/api/bloem/v2/capabilities" -> respondJson("""{"api":"bloem/v2","features":{"organization_memberships":true},"feature_tokens":[]}""")
            "/api/bloem/v2/organizations" -> respondJson(OrganizationFixtures.memberships())
            else -> error("unexpected path $path")
        }
    }

    private suspend fun discovered(engine: MockEngine): DiscoveryResult.Discovered =
        connector(engine).discover("https://example.test/") as DiscoveryResult.Discovered

    @Test
    fun invalidOriginPerformsNoRequest() = runTest {
        var clientsCreated = 0
        val connector = AddServerConnector(clientFactory = { _, _ ->
            clientsCreated++
            error("must not connect")
        })

        val result = connector.discover("http://example.test")

        assertEquals(AddServerFailure.UNSAFE_ORIGIN, (result as DiscoveryResult.Rejected).reason)
        assertEquals(0, clientsCreated)
    }

    @Test
    fun discoveryDecodesNameIdentityAndProviders() = runTest {
        val recorder = Recorder()
        val result = discovered(engine(recorder))

        assertEquals(listOf("/api/v1/health", "/api/v2/system/identity", "/api/v2/auth/providers"), recorder.paths)
        assertEquals("https://example.test", result.origin.value)
        assertEquals("Bloem West", result.displayName)
        assertEquals(serverId, result.serverId)
        assertTrue(result.providers.password_login)
        assertEquals(listOf("26"), result.providers.nativeOAuth.map { it.id })
    }

    @Test
    fun discoveryAssumesHttpsForATypedHostWithoutAScheme() = runTest {
        val result = connector(engine()).discover("example.test") as DiscoveryResult.Discovered
        assertEquals("https://example.test", result.origin.value)
    }

    @Test
    fun discoveryFallsBackToTheHostWhenTheServerHasNoName() = runTest {
        val result = discovered(engine(overrides = mapOf("/api/v1/health" to ("""{}""" to HttpStatusCode.OK))))
        assertEquals("example.test", result.displayName)
    }

    @Test
    fun discoveryMapsHealthFailureAndMissingRoutes() = runTest {
        suspend fun failure(path: String, status: HttpStatusCode): AddServerFailure {
            val e = engine(overrides = mapOf(path to ("""{"message":"x"}""" to status)))
            return (connector(e).discover("https://example.test") as DiscoveryResult.Rejected).reason
        }
        assertEquals(AddServerFailure.HEALTH_CHECK_FAILED, failure("/api/v1/health", HttpStatusCode.ServiceUnavailable))
        assertEquals(AddServerFailure.INCOMPATIBLE_SERVER, failure("/api/v2/system/identity", HttpStatusCode.NotFound))
        assertEquals(AddServerFailure.INCOMPATIBLE_SERVER, failure("/api/v2/auth/providers", HttpStatusCode.NotFound))
        assertEquals(AddServerFailure.UNREACHABLE, failure("/api/v2/auth/providers", HttpStatusCode.BadGateway))
    }

    @Test
    fun passwordSignInRequiresLoginAndAdminStatusBeforeReturningServer() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()

        val result = connector(engine).signInWithPassword(found, "admin", "secret")

        assertEquals(listOf("/api/v2/auth/login", "/api/v2/admin/server/status"), recorder.paths)
        result as AddServerResult.Connected
        assertEquals("https://example.test", result.server.baseUrl)
        assertEquals("Bloem West", result.server.displayName)
        assertEquals("server-id", result.server.id)
        assertNull(result.server.signInProviderId)
    }

    @Test
    fun passwordSignInFailuresKeepTheirMapping() = runTest {
        suspend fun failure(path: String, status: HttpStatusCode): AddServerFailure {
            val e = engine(overrides = mapOf(path to ("""{"message":"rejected"}""" to status)))
            val found = discovered(e)
            return (connector(e).signInWithPassword(found, "admin", "secret") as AddServerResult.Rejected).reason
        }
        assertEquals(AddServerFailure.INVALID_CREDENTIALS, failure("/api/v2/auth/login", HttpStatusCode.Unauthorized))
        assertEquals(AddServerFailure.ADMIN_REQUIRED, failure("/api/v2/auth/login", HttpStatusCode.Forbidden))
        assertEquals(AddServerFailure.INCOMPATIBLE_SERVER, failure("/api/v2/auth/login", HttpStatusCode.NotFound))
        assertEquals(AddServerFailure.UNREACHABLE, failure("/api/v2/auth/login", HttpStatusCode.InternalServerError))
        assertEquals(AddServerFailure.INCOMPATIBLE_SERVER, failure("/api/v2/admin/server/status", HttpStatusCode.NotFound))
        assertEquals(AddServerFailure.ADMIN_REQUIRED, failure("/api/v2/admin/server/status", HttpStatusCode.Forbidden))
        assertEquals(AddServerFailure.INVALID_CREDENTIALS, failure("/api/v2/admin/server/status", HttpStatusCode.Unauthorized))
    }

    private val notPlatformAdmin = "/api/v2/admin/server/status" to ("""{"message":"no"}""" to HttpStatusCode.Forbidden)

    @Test
    fun anAccountThatOnlyAdministersAnOrganizationCanAddTheServerWithAPassword() = runTest {
        val recorder = Recorder()
        val e = engine(recorder, mapOf(
            notPlatformAdmin,
            "/api/bloem/v2/organizations" to (OrganizationFixtures.memberships(OrganizationFixtures.membership(role = "admin")) to HttpStatusCode.OK)
        ))
        val found = discovered(e)
        recorder.paths.clear()

        val result = connector(e).signInWithPassword(found, "owner", "secret")

        result as AddServerResult.Connected
        assertEquals("https://example.test", result.server.baseUrl)
        assertEquals("/api/bloem/v2/organizations", recorder.paths.last())
    }

    @Test
    fun anAccountThatOnlyAdministersAnOrganizationCanAddTheServerWithProviderSignIn() = runTest {
        val e = engine(overrides = mapOf(
            notPlatformAdmin,
            "/api/bloem/v2/organizations" to (OrganizationFixtures.memberships(OrganizationFixtures.membership(role = "admin")) to HttpStatusCode.OK)
        ))
        val found = discovered(e)
        val browser = Browser()

        val result = connector(e).signInWithProvider(found, corpSso, browser::open) { callback(browser, "code=c") }

        assertTrue(result is AddServerResult.Connected)
    }

    @Test
    fun anAccountWithNoAdminContextIsStillRefused() = runTest {
        val noContext = listOf(
            // A member who does not administer the organization.
            OrganizationFixtures.memberships(OrganizationFixtures.membership(role = "member")) to HttpStatusCode.OK,
            OrganizationFixtures.memberships() to HttpStatusCode.OK,
            // A plain Silo server has no organizations route.
            """{"message":"not found"}""" to HttpStatusCode.NotFound
        )
        for ((body, status) in noContext) {
            val e = engine(overrides = mapOf(notPlatformAdmin, "/api/bloem/v2/organizations" to (body to status)))
            val found = discovered(e)
            val result = connector(e).signInWithPassword(found, "someone", "secret")
            assertEquals("$status $body", AddServerFailure.ADMIN_REQUIRED, (result as AddServerResult.Rejected).reason)
        }
    }

    @Test
    fun aPlainSiloServerIsAskedOnlyForItsCapabilitiesBeforeAnAccountIsRefused() = runTest {
        val recorder = Recorder()
        val e = engine(recorder, mapOf(
            notPlatformAdmin,
            "/api/bloem/v2/capabilities" to ("404 page not found" to HttpStatusCode.NotFound)
        ))
        val found = discovered(e)
        recorder.paths.clear()

        val result = connector(e).signInWithPassword(found, "someone", "secret")

        assertEquals(AddServerFailure.ADMIN_REQUIRED, (result as AddServerResult.Rejected).reason)
        assertEquals(listOf("/api/bloem/v2/capabilities"), recorder.paths.filter { it.startsWith("/api/bloem/") })
    }

    @Test
    fun aPlatformAdministratorDoesNotNeedTheOrganizationsRoute() = runTest {
        val recorder = Recorder()
        val e = engine(recorder)
        val found = discovered(e)
        recorder.paths.clear()
        assertTrue(connector(e).signInWithPassword(found, "admin", "secret") is AddServerResult.Connected)
        assertEquals(listOf("/api/v2/auth/login", "/api/v2/admin/server/status"), recorder.paths)
    }

    @Test
    fun anExpiredTokenOnTheOrganizationsRouteIsInvalidCredentials() = runTest {
        val e = engine(overrides = mapOf(notPlatformAdmin, "/api/bloem/v2/organizations" to ("""{"message":"x"}""" to HttpStatusCode.Unauthorized)))
        val found = discovered(e)
        val result = connector(e).signInWithPassword(found, "someone", "secret")
        assertEquals(AddServerFailure.INVALID_CREDENTIALS, (result as AddServerResult.Rejected).reason)
    }

    // --- provider sign-in -------------------------------------------------------------------

    private class Browser {
        var url: String? = null
        fun open(url: String): Boolean { this.url = url; return true }
        fun param(name: String): String = Url(url!!).parameters[name]!!
    }

    private fun callback(browser: Browser, extra: String, state: String = browser.param("app_state"), iss: String = "https://example.test") =
        "org.siloserver.silo:/auth/callback?$extra&state=$state&server=$serverId&iss=$iss"

    @Test
    fun providerSignInSendsTheVerifierForTheChallengeAndSetsTheProvider() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()
        val browser = Browser()

        val result = connector(engine).signInWithProvider(found, corpSso, browser::open) {
            callback(browser, "code=one-time-code")
        }

        result as AddServerResult.Connected
        assertEquals("26", result.server.signInProviderId)
        assertEquals("a", result.accessToken)
        assertEquals("r", result.refreshToken)
        assertEquals(listOf("/api/v2/auth/oauth/complete", "/api/v2/admin/server/status"), recorder.paths)
        assertTrue(browser.url!!.startsWith("https://example.test/api/v2/auth/oauth/26/native/start?code_challenge="))
        assertEquals("S256", browser.param("code_challenge_method"))
        val body = recorder.completeBodies.single()
        assertTrue(body.contains("one-time-code"))
        val verifier = Regex("\"code_verifier\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        assertEquals(browser.param("code_challenge"), Pkce.challengeFor(verifier))
        assertEquals(43, browser.param("app_state").length)
    }

    @Test
    fun untrustedRedirectIsNeverRedeemed() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()
        val browser = Browser()
        val bad = listOf(
            { callback(browser, "code=c", state = "other") },
            { callback(browser, "code=c", iss = "https://evil.test") },
            { "org.siloserver.silo:/other?code=c" },
            { "https://example.test/auth/callback?code=c" }
        )
        for (redirect in bad) {
            val result = connector(engine).signInWithProvider(found, corpSso, browser::open) { redirect() }
            assertEquals(AddServerFailure.OAUTH_UNTRUSTED, (result as AddServerResult.Rejected).reason)
        }
        assertTrue(recorder.paths.isEmpty())
    }

    @Test
    fun redirectErrorIsReportedWithItsReasonAndNothingIsRedeemed() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()
        val browser = Browser()

        val result = connector(engine).signInWithProvider(found, corpSso, browser::open) {
            callback(browser, "error=account_disabled")
        }

        result as AddServerResult.Rejected
        assertEquals(AddServerFailure.OAUTH_FAILED, result.reason)
        assertEquals("account_disabled", result.detail)
        assertTrue(recorder.paths.isEmpty())
    }

    @Test
    fun nullRedirectIsACancelWithNoRequest() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()

        val result = connector(engine).signInWithProvider(found, corpSso, { true }) { null }

        assertEquals(AddServerFailure.OAUTH_CANCELLED, (result as AddServerResult.Rejected).reason)
        assertTrue(recorder.paths.isEmpty())
    }

    @Test
    fun noBrowserStopsBeforeWaiting() = runTest {
        val engine = engine()
        val found = discovered(engine)
        var waited = false

        val result = connector(engine).signInWithProvider(found, corpSso, { false }) { waited = true; null }

        assertEquals(AddServerFailure.NO_BROWSER, (result as AddServerResult.Rejected).reason)
        assertFalse(waited)
    }

    @Test
    fun badStartPathNeverOpensTheBrowser() = runTest {
        val engine = engine()
        val found = discovered(engine)
        var opened = false
        for (path in listOf("//evil.test/x", "https://evil.test/x", null)) {
            val provider = corpSso.copy(native_start_path = path)
            val result = connector(engine).signInWithProvider(found, provider, { opened = true; true }) { null }
            assertEquals(AddServerFailure.NO_SUPPORTED_SIGN_IN, (result as AddServerResult.Rejected).reason)
        }
        assertFalse(opened)
    }

    @Test
    fun adminProbe403AfterOAuthIsAdminRequired() = runTest {
        val e = engine(overrides = mapOf("/api/v2/admin/server/status" to ("""{"message":"no"}""" to HttpStatusCode.Forbidden)))
        val found = discovered(e)
        val browser = Browser()

        val result = connector(e).signInWithProvider(found, corpSso, browser::open) { callback(browser, "code=c") }

        assertEquals(AddServerFailure.ADMIN_REQUIRED, (result as AddServerResult.Rejected).reason)
    }

    @Test
    fun rejectedCompletionIsLoginFailed() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)) {
            val e = engine(overrides = mapOf("/api/v2/auth/oauth/complete" to ("""{"message":"no"}""" to status)))
            val found = discovered(e)
            val browser = Browser()

            val result = connector(e).signInWithProvider(found, corpSso, browser::open) { callback(browser, "code=c") }

            result as AddServerResult.Rejected
            assertEquals(AddServerFailure.OAUTH_FAILED, result.reason)
            assertEquals("login_failed", result.detail)
        }
    }

    @Test
    fun redemptionUpgradeRequiredShowsTheUpgradeCopy() = runTest {
        val body = V2Fixtures.problem(410, "client_upgrade_required", "Update the app to continue.")
        val e = engine(overrides = mapOf("/api/v2/auth/oauth/complete" to (body to HttpStatusCode.Gone)))
        val found = discovered(e)
        val browser = Browser()

        val result = connector(e).signInWithProvider(found, corpSso, browser::open) { callback(browser, "code=c") }

        assertEquals(AddServerFailure.CLIENT_UPGRADE_REQUIRED, (result as AddServerResult.Rejected).reason)
    }

    @Test
    fun otherRedemptionClientErrorsAreLoginFailedNotUnreachable() = runTest {
        for (status in listOf(HttpStatusCode.Gone, HttpStatusCode.Conflict, HttpStatusCode.UnprocessableEntity, HttpStatusCode.TooManyRequests)) {
            val e = engine(overrides = mapOf("/api/v2/auth/oauth/complete" to ("""{"message":"no"}""" to status)))
            val found = discovered(e)
            val browser = Browser()

            val result = connector(e).signInWithProvider(found, corpSso, browser::open) { callback(browser, "code=c") }

            result as AddServerResult.Rejected
            assertEquals(status.toString(), AddServerFailure.OAUTH_FAILED, result.reason)
            assertEquals(status.toString(), "login_failed", result.detail)
        }
    }

    @Test
    fun aServerErrorAtRedemptionIsStillUnreachable() = runTest {
        val e = engine(overrides = mapOf("/api/v2/auth/oauth/complete" to ("""{"message":"no"}""" to HttpStatusCode.BadGateway)))
        val found = discovered(e)
        val browser = Browser()

        val result = connector(e).signInWithProvider(found, corpSso, browser::open) { callback(browser, "code=c") }

        assertEquals(AddServerFailure.UNREACHABLE, (result as AddServerResult.Rejected).reason)
    }

    @Test
    fun discoveryMapsGoneToTheUpgradeCopy() = runTest {
        val upgrade = V2Fixtures.problem(410, "client_upgrade_required", "Update the app to continue.")
        for (path in listOf("/api/v1/health", "/api/v2/system/identity", "/api/v2/auth/providers")) {
            val e = engine(overrides = mapOf(path to (upgrade to HttpStatusCode.Gone)))
            val result = connector(e).discover("https://example.test")
            assertEquals(path, AddServerFailure.CLIENT_UPGRADE_REQUIRED, (result as DiscoveryResult.Rejected).reason)
        }
    }

    @Test
    fun aRedirectTakenAfterTenMinutesIsExpiredAndNotRedeemed() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        var now = 1_000L
        val connector = AddServerConnector(
            clientFactory = { origin, token -> ApiClient(origin, token, engine) },
            idFactory = { "server-id" },
            clock = { now }
        )
        val found = connector.discover("https://example.test") as DiscoveryResult.Discovered
        recorder.paths.clear()
        val browser = Browser()

        val result = connector.signInWithProvider(found, corpSso, browser::open) {
            now += 10 * 60_000L + 1
            callback(browser, "code=c")
        }

        result as AddServerResult.Rejected
        assertEquals(AddServerFailure.OAUTH_FAILED, result.reason)
        assertEquals("session_expired", result.detail)
        assertTrue(recorder.paths.isEmpty())
    }

    @Test
    fun aRedirectJustInsideTenMinutesIsRedeemed() = runTest {
        val engine = engine()
        var now = 1_000L
        val connector = AddServerConnector(
            clientFactory = { origin, token -> ApiClient(origin, token, engine) },
            idFactory = { "server-id" },
            clock = { now }
        )
        val found = connector.discover("https://example.test") as DiscoveryResult.Discovered
        val browser = Browser()

        val result = connector.signInWithProvider(found, corpSso, browser::open) {
            now += 10 * 60_000L
            callback(browser, "code=c")
        }

        assertTrue(result is AddServerResult.Connected)
    }

    @Test
    fun reLoginDiscoveryReadsIdentityAndProvidersOnlyAndKeepsTheSavedName() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val saved = AdminServer("saved-1", "https://example.test", "My Saved Name", "26")

        val result = connector(engine).discoverForReLogin(saved)

        assertEquals(listOf("/api/v2/system/identity", "/api/v2/auth/providers"), recorder.paths)
        result as DiscoveryResult.Discovered
        assertEquals("My Saved Name", result.displayName)
        assertEquals(serverId, result.serverId)
        assertEquals("https://example.test", result.origin.value)
        assertEquals(listOf("26"), result.providers.nativeOAuth.map { it.id })
    }

    @Test
    fun reLoginDiscoveryReportsUpgradeRequiredFromIdentityOrProviders() = runTest {
        val saved = AdminServer("saved-1", "https://example.test", "Old")
        for (path in listOf("/api/v2/system/identity", "/api/v2/auth/providers")) {
            val body = V2Fixtures.problem(410, "client_upgrade_required", "Update the app to continue.")
            val e = engine(overrides = mapOf(path to (body to HttpStatusCode.Gone)))
            val result = connector(e).discoverForReLogin(saved)
            assertEquals(AddServerFailure.CLIENT_UPGRADE_REQUIRED, (result as DiscoveryResult.Rejected).reason)
        }
    }

    @Test
    fun reLoginDiscoveryDegradesToPasswordOnlyOnAnyOtherFailure() = runTest {
        val saved = AdminServer("saved-1", "https://example.test", "Old")
        for (path in listOf("/api/v2/system/identity", "/api/v2/auth/providers")) {
            for (status in listOf(
                HttpStatusCode.NotFound, HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway,
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden
            )) {
                val e = engine(overrides = mapOf(path to ("""{"message":"x"}""" to status)))
                val result = connector(e).discoverForReLogin(saved)
                result as DiscoveryResult.Discovered
                assertTrue("$path $status", result.providers.password_login)
                assertTrue("$path $status", result.providers.items.isEmpty())
                assertEquals("Old", result.displayName)
            }
        }
    }

    @Test
    fun reLoginDiscoveryDegradesToPasswordOnlyWhenTheNetworkFails() = runTest {
        val failing = MockEngine { throw java.io.IOException("offline") }
        val result = connector(failing).discoverForReLogin(AdminServer("saved-1", "https://example.test", "Old"))
        result as DiscoveryResult.Discovered
        assertTrue(result.providers.items.isEmpty())
    }

    @Test
    fun reLoginPasswordSignInSkipsTheAdminProbeAndClearsTheProvider() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()

        val result = connector(engine).signInWithPassword(found, "admin", "secret", probeAdmin = false)

        assertEquals(listOf("/api/v2/auth/login"), recorder.paths)
        result as AddServerResult.Connected
        assertNull(result.server.signInProviderId)
        assertEquals("a", result.accessToken)
    }

    @Test
    fun reLoginProviderSignInSkipsTheAdminProbeAndRecordsTheProvider() = runTest {
        val recorder = Recorder()
        val engine = engine(recorder)
        val found = discovered(engine)
        recorder.paths.clear()
        val browser = Browser()

        val result = connector(engine).signInWithProvider(found, corpSso, browser::open, probeAdmin = false) {
            callback(browser, "code=c")
        }

        assertEquals(listOf("/api/v2/auth/oauth/complete"), recorder.paths)
        result as AddServerResult.Connected
        assertEquals("26", result.server.signInProviderId)
    }

    private fun connector(engine: MockEngine) = AddServerConnector(
        clientFactory = { origin, token -> ApiClient(origin, token, engine) },
        idFactory = { "server-id" }
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}
