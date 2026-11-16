package com.bloemstudios.admin.servers

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.RefreshExchange
import com.bloemstudios.admin.auth.RefreshResponse
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's screens and the background check (widget and tile counts) share one [ServerStore] per process through
 * [ServerStoreHolder]. A refresh token works once, so these pin the guarantees that sharing relies on: one store, one
 * refresh at a time per server, and a background failure that cannot sign out a session the app is using.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SharedStoreRefreshTest {
    @Serializable
    private data class Body(val value: String = "")

    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()
    private val server = AdminServer("srv-shared", "https://example.test", "Shared")
    private val originalFactory = ServerStoreHolder.factory

    @After fun restoreHolder() {
        ServerStoreHolder.factory = originalFactory
    }

    private fun prefs(): SharedPreferences {
        val values = ConcurrentHashMap<String, String>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.commit() } returns true
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        return prefs
    }

    /** A store whose clients talk to [engine] and whose refresh exchange is [exchange]. */
    private fun store(engine: MockEngine, exchange: suspend (AdminServer, String) -> RefreshResponse) = ServerStore(
        context = context,
        tokenPreferencesOpenResult = TokenPreferencesOpenResult(prefs(), recovered = false, storageAvailable = true),
        clientFactory = { origin, snapshot, recovery -> ApiClient(origin, snapshot, engine, recovery) },
        refreshExchange = RefreshExchange(exchange),
        metadataWriter = {},
    )

    /** Answers 401 to the old access token and 200 to the refreshed one. */
    private fun serverThatExpires(seen: MutableList<String?> = mutableListOf()) = MockEngine { request ->
        val bearer = request.headers[HttpHeaders.Authorization]
        synchronized(seen) { seen += bearer }
        if (bearer == "Bearer new-access") {
            respond("""{"value":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond("""{"error":"unauthorized","message":"expired"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    // ---- ServerStoreHolder ---------------------------------------------------------------------------------------

    @Test fun theAppAndTheBackgroundCheckGetTheSameStore() {
        val built = AtomicInteger()
        ServerStoreHolder.factory = { built.incrementAndGet(); store(serverThatExpires()) { _, _ -> error("unused") } }
        val app = ServerStoreHolder.acquire(context)
        val background = ServerStoreHolder.acquire(context)
        assertSame(app.store, background.store)
        assertEquals(1, built.get())
        background.close()
        app.close()
    }

    @Test fun theStoreStaysOpenUntilTheLastLeaseIsReleased() = runBlocking {
        ServerStoreHolder.factory = { store(serverThatExpires()) { _, _ -> error("unused") } }
        val app = ServerStoreHolder.acquire(context)
        val background = ServerStoreHolder.acquire(context)
        val shared = app.store
        shared.addServer(server, "access", "refresh")

        background.close()
        background.close() // a lease can be closed twice without releasing the app's share
        assertEquals("access", shared.accessToken(server.id)) // the app's session is untouched: the store is still open
        shared.client(server) // and still hands out clients

        app.close()
        val closed = runCatching { shared.client(server) }.exceptionOrNull()
        assertTrue("the last lease closes the store, got $closed", closed is StoreClosedException)
    }

    @Test fun aStoreIsNeverOpenedWhileThePreviousOneIsStillClosing() {
        // The first store is closed before the holder builds the next: two live stores would each spend one refresh token.
        val built = mutableListOf<ServerStore>()
        val overlaps = AtomicInteger()
        ServerStoreHolder.factory = {
            built.lastOrNull()?.let { previous ->
                if (runCatching { previous.client(server) }.exceptionOrNull() !is StoreClosedException) overlaps.incrementAndGet()
            }
            store(serverThatExpires()) { _, _ -> error("unused") }.also { built += it }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val first = ServerStoreHolder.acquire(context)
                val start = CountDownLatch(1)
                val release = pool.submit { start.await(); first.close() }
                val acquire = pool.submit<ServerStoreHolder.Lease> { start.await(); ServerStoreHolder.acquire(context) }
                start.countDown()
                release.get(10, TimeUnit.SECONDS)
                acquire.get(10, TimeUnit.SECONDS).close()
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("a store opened while the previous one was still open", 0, overlaps.get())
        assertTrue("stores were rebuilt, so the race was exercised", built.size > 1)
    }

    // ---- One refresh at a time, app and background together --------------------------------------------------------

    @Test fun anAppReadAndABackgroundReadOnAnExpiredSessionSpendTheRefreshTokenOnce() = runBlocking {
        val exchanges = AtomicInteger()
        val seen = mutableListOf<String?>()
        ServerStoreHolder.factory = {
            store(serverThatExpires(seen)) { _, refreshToken ->
                assertEquals("old-refresh", refreshToken)
                exchanges.incrementAndGet()
                kotlinx.coroutines.delay(50) // long enough for the other caller to meet the same expired token
                RefreshResponse("new-access", "new-refresh", 3600)
            }
        }
        val appLease = ServerStoreHolder.acquire(context)
        val backgroundLease = ServerStoreHolder.acquire(context)
        appLease.store.addServer(server, "old-access", "old-refresh")

        // The screen's read and the background check's read both go through the one client the store hands out.
        val app = async(kotlinx.coroutines.Dispatchers.Default) { appLease.store.client(server).get<Body>("/api/v2/admin/sessions") }
        val background = async(kotlinx.coroutines.Dispatchers.Default) { backgroundLease.store.client(server).get<Body>("/api/v2/admin/requests/counts") }
        awaitAll(app, background)

        assertEquals("one refresh token spent", 1, exchanges.get())
        assertEquals("new-access", appLease.store.accessToken(server.id))
        assertEquals("new-refresh", appLease.store.refreshToken(server.id))
        backgroundLease.close()
        appLease.close()
    }

    @Test fun twoServersRefreshIndependentlyButEachOnlyOnce() = runBlocking {
        val other = AdminServer("srv-other", "https://other.test", "Other")
        val spent = ConcurrentHashMap<String, AtomicInteger>()
        val store = store(serverThatExpires()) { target, refreshToken ->
            spent.computeIfAbsent(target.id + ":" + refreshToken) { AtomicInteger() }.incrementAndGet()
            RefreshResponse("new-access", "next-${target.id}", 3600)
        }
        store.addServer(server, "old-access", "refresh-a")
        store.addServer(other, "old-access", "refresh-b")
        val results = (1..4).map { index ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                store.client(if (index % 2 == 0) server else other).get<Body>("/api/v2/admin/sessions")
            }
        }
        results.awaitAll()
        assertEquals(mapOf("srv-shared:refresh-a" to 1, "srv-other:refresh-b" to 1), spent.mapValues { it.value.get() })
        store.close()
    }

    // ---- A failed background refresh --------------------------------------------------------------------------------

    @Test fun aRefreshThatCouldNotBeAttemptedLeavesTheAppsSessionAndNeverSignsItOut() = runBlocking {
        for (failure in listOf<Throwable>(IOException("offline"), ApiError(503, "unavailable"), ApiError(500, "boom"))) {
            val store = store(serverThatExpires()) { _, _ -> throw failure }
            store.addServer(server, "old-access", "old-refresh")
            val client = store.client(server)
            val signedOut = AtomicInteger()
            client.onUnauthorized = { signedOut.incrementAndGet() } // what the app's AdminUnauthorizedBinding installs

            val result = runCatching { client.get<Body>("/api/v2/admin/requests/counts") }
            assertTrue("the background read fails, it does not hang: $result", result.isFailure)
            assertEquals("old-access", store.accessToken(server.id))
            assertEquals("old-refresh", store.refreshToken(server.id))
            assertEquals("the app is not told to sign in again after $failure", 0, signedOut.get())
            store.close()
        }
    }

    @Test fun aRefreshTokenTheServerRefusedSignsOutOnceForTheAppAndTheBackgroundAlike() = runBlocking {
        // The one case where a background run changes the session: the server itself refused the refresh token, which the
        // app would have found out on its next read. It happens once, through the same store, and is not retried.
        val exchanges = AtomicInteger()
        val store = store(serverThatExpires()) { _, _ -> exchanges.incrementAndGet(); throw ApiError(401, "invalid_token") }
        store.addServer(server, "old-access", "old-refresh")
        val client = store.client(server)
        val signedOut = AtomicInteger()
        client.onUnauthorized = { signedOut.incrementAndGet() }

        val reads = listOf("/api/v2/admin/sessions", "/api/v2/admin/requests/counts").map { path ->
            async(kotlinx.coroutines.Dispatchers.Default) { runCatching { client.get<Body>(path) } }
        }
        reads.awaitAll()

        assertEquals(1, exchanges.get())
        assertNull(store.accessToken(server.id))
        assertNull(store.refreshToken(server.id))
        assertTrue("the app is told to sign in", signedOut.get() >= 1)
        store.close()
    }

    // ---- A stopped background run ---------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aRefreshThatIsCancelledMidExchangeStillStoresTheRotatedTokens() = runTest {
        // WorkManager can stop the background check while the refresh is on the wire. The server has then rotated the
        // single-use token, so the answer must still be stored or the next refresh would spend a dead token.
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<RefreshResponse>()
        val store = store(serverThatExpires()) { _, _ -> entered.complete(Unit); answer.await() }
        store.addServer(server, "old-access", "old-refresh")
        val rejected = store.accessTokenSnapshot(server.id)

        val recovery = async { store.recoverUnauthorized(server, rejected) }
        entered.await()
        recovery.cancel()
        answer.complete(RefreshResponse("new-access", "new-refresh", 3600))
        advanceUntilIdle()

        assertTrue(recovery.isCancelled)
        assertEquals("new-access", store.accessToken(server.id))
        assertEquals("new-refresh", store.refreshToken(server.id))
        // The next caller finds the rotated pair and retries with it instead of refreshing again.
        assertEquals(RecoveryResult.Retry(store.accessTokenSnapshot(server.id)), store.recoverUnauthorized(server, rejected))
        store.close()
    }
}
