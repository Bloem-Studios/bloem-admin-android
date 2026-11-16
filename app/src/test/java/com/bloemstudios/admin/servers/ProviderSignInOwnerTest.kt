package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AuthProvider
import com.bloemstudios.admin.auth.AuthProviders
import com.bloemstudios.admin.auth.OAuthRedirects
import com.bloemstudios.admin.auth.V2Fixtures
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ServerOrigin
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The provider sign-in outlives the screen that started it (the app lock removes screens on stop). */
class ProviderSignInOwnerTest {
    private val serverId = "3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35"
    private val corpSso = AuthProvider(
        id = "26", display_name = "Corp SSO", mode = "oauth", default = false,
        installation_id = "26", native_start_path = "/api/v2/auth/oauth/26/native/start"
    )
    private val discovered = DiscoveryResult.Discovered(
        ServerOrigin.parse("https://example.test"), "Bloem West", serverId, AuthProviders(true, listOf(corpSso))
    )

    private val requested = mutableListOf<String>()
    private val persisted = mutableListOf<Triple<String, String, String>>()
    private val urls = mutableListOf<String>()
    private var completeStatus = HttpStatusCode.OK

    private val connector = AddServerConnector(
        clientFactory = { origin, token ->
            ApiClient(origin, token, MockEngine { request ->
                val path = request.url.encodedPath
                requested += path
                val (body, status) = when (path) {
                    "/api/v2/auth/oauth/complete" ->
                        (if (completeStatus == HttpStatusCode.OK) V2Fixtures.OAUTH_COMPLETION else """{"message":"no"}""") to completeStatus
                    "/api/v2/admin/server/status" -> V2Fixtures.SERVER_STATUS to HttpStatusCode.OK
                    else -> error("unexpected path $path")
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        },
        idFactory = { "server-id" }
    )

    private lateinit var scope: TestScope
    private lateinit var owner: ProviderSignInOwner

    @Before fun setUp() {
        OAuthRedirects.end(); OAuthRedirects.takeOrphan()
        scope = TestScope()
        owner = ProviderSignInOwner(CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)))
        owner.openBrowser = { _, url -> urls += url; true }
    }

    @After fun tearDown() {
        OAuthRedirects.end(); OAuthRedirects.takeOrphan()
    }

    private fun start(target: SignInTarget = SignInTarget.AddServer) = owner.start(
        host = null, target = target, connector = connector, discovered = discovered, provider = corpSso,
        probeAdmin = true, persist = { server, access, refresh -> persisted += Triple(server.id, access, refresh) }
    )

    private fun redirectFor(url: String): String {
        val state = Url(url).parameters["app_state"]!!
        return "org.siloserver.silo:/auth/callback?code=one-time&state=$state&server=$serverId&iss=https://example.test"
    }

    private fun arrive(url: String) {
        OAuthRedirects.deliver(redirectFor(url))
        owner.onResume()
    }

    @Test
    fun aSignInOutlivesItsScreenAndTheLockAndCompletesWhenTheRedirectArrivesAtResume() = scope.runTest {
        owner.attach(SignInTarget.AddServer)
        start()
        runCurrent()
        assertEquals(1, urls.size)
        assertNotNull(owner.running.value)

        // The Custom Tab opened, the activity stopped, the lock engaged and the screen left composition.
        owner.detach(SignInTarget.AddServer)
        runCurrent()
        assertNotNull("the sign-in is still waiting", owner.running.value)

        // The redirect arrives and the activity resumes (the lock screen is still showing).
        arrive(urls.single())
        owner.join()

        assertEquals(listOf(Triple("server-id", "a", "r")), persisted)
        assertEquals(listOf("/api/v2/auth/oauth/complete", "/api/v2/admin/server/status"), requested)
        assertNull(owner.running.value)
        assertTrue("Add Server closes once it is composed again", owner.addServerSucceeded.value)
        assertNull(owner.notice.value)
        assertNull(owner.screenOutcome.value)
        assertNull("nothing is left over", OAuthRedirects.takeOrphan())
    }

    @Test
    fun aSuccessWhileTheScreenIsShownGoesToTheScreen() = scope.runTest {
        owner.attach(SignInTarget.AddServer)
        start()
        runCurrent()
        arrive(urls.single())
        owner.join()

        val outcome = owner.screenOutcome.value!!
        assertNull(outcome.rejection)
        assertFalse(owner.addServerSucceeded.value)
        assertEquals(1, persisted.size)
    }

    @Test
    fun aReLoginSuccessBehindTheLockPersistsAndNeedsNoNotice() = scope.runTest {
        start(SignInTarget.ReLogin("saved-1"))
        runCurrent()
        arrive(urls.single())
        owner.join()

        assertEquals(1, persisted.size)
        assertNull(owner.notice.value)
        assertFalse(owner.addServerSucceeded.value)
    }

    @Test
    fun aFailureBehindTheLockIsShownOnceAtActivityLevel() = scope.runTest {
        completeStatus = HttpStatusCode.BadRequest
        start()
        runCurrent()
        arrive(urls.single())
        owner.join()

        assertTrue(persisted.isEmpty())
        val notice = owner.notice.value!!
        assertEquals(AddServerFailure.OAUTH_FAILED, notice.rejection!!.reason)
        assertEquals("Corp SSO", notice.providerName)
        owner.dismissNotice()
        assertNull(owner.notice.value)
    }

    @Test
    fun aFailureForAShownScreenGoesToTheScreenAndNotToTheNotice() = scope.runTest {
        completeStatus = HttpStatusCode.BadRequest
        owner.attach(SignInTarget.AddServer)
        start()
        runCurrent()
        arrive(urls.single())
        owner.join()

        assertEquals(AddServerFailure.OAUTH_FAILED, owner.screenOutcome.value!!.rejection!!.reason)
        assertNull(owner.notice.value)
        // The screen leaves before it showed the failure: the activity shows it instead.
        owner.detach(SignInTarget.AddServer)
        assertNull(owner.screenOutcome.value)
        assertNotNull(owner.notice.value)
    }

    @Test
    fun comingBackWithoutSigningInShowsNothing() = scope.runTest {
        start()
        runCurrent()
        owner.onResume()
        owner.join()

        assertNull(owner.notice.value)
        assertNull(owner.screenOutcome.value)
        assertFalse(owner.addServerSucceeded.value)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun explicitCancelCancelsWithNoRedemption() = scope.runTest {
        start()
        runCurrent()
        owner.cancel(SignInTarget.AddServer)
        owner.join()

        assertNull(owner.running.value)
        assertTrue(requested.isEmpty())
        assertTrue(persisted.isEmpty())
        assertNull(owner.notice.value)
        assertFalse(owner.addServerSucceeded.value)
        // The wait ended: a redirect that arrives now is an orphan, never redeemed.
        OAuthRedirects.deliver(redirectFor(urls.single()))
        owner.onResume()
        runCurrent()
        assertTrue(requested.isEmpty())
        assertNotNull(OAuthRedirects.takeOrphan())
    }

    @Test
    fun cancellingAnotherTargetDoesNothing() = scope.runTest {
        start(SignInTarget.AddServer)
        runCurrent()
        owner.cancel(SignInTarget.ReLogin("saved-1"))
        arrive(urls.single())
        owner.join()

        assertEquals(1, persisted.size)
    }

    @Test
    fun aSecondStartCancelsAndJoinsTheFirst() = scope.runTest {
        start()
        runCurrent()
        val first = urls.single()

        start()
        runCurrent()
        assertEquals(2, urls.size)
        // The first redirect is not the second sign-in's: it is never redeemed.
        arrive(first)
        owner.join()
        assertTrue(persisted.isEmpty())
        assertEquals(AddServerFailure.OAUTH_UNTRUSTED, owner.notice.value!!.rejection!!.reason)
    }

    @Test
    fun aSecondStartAfterTheFirstIsWaitingCompletesWithTheSecondsRedirect() = scope.runTest {
        start()
        runCurrent()
        start()
        runCurrent()
        assertEquals(2, urls.size)

        arrive(urls.last())
        owner.join()

        assertEquals(1, persisted.size)
        assertEquals(listOf("/api/v2/auth/oauth/complete", "/api/v2/admin/server/status"), requested)
        assertNull(owner.notice.value)
    }
}
