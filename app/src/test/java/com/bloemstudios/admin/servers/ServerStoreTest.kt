package com.bloemstudios.admin.servers

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.CredentialChangeObserver
import com.bloemstudios.admin.auth.CredentialChangeReason
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.RefreshExchange
import com.bloemstudios.admin.auth.RefreshResponse
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.InvalidServerOrigin
import com.bloemstudios.admin.network.OriginProblem
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.manifest.AndroidManifest
import org.robolectric.annotation.Config
import java.nio.file.Paths
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM unit tests for ServerStore using mock SharedPreferences.
 *
 * These tests verify ServerStore's core logic (adding, removing, reloading servers)
 * in isolation using mocked SharedPreferences. This allows fast, deterministic testing
 * of the store's data persistence logic without platform-specific concerns.
 *
 * For real EncryptedSharedPreferences testing, see ServerStoreRealEncryptionTest
 * (instrumented androidTest) which runs on a real Android environment where
 * EncryptedSharedPreferences's cryptographic APIs are available. JVM unit tests
 * cannot support real EncryptedSharedPreferences because Robolectric does not
 * provide the full AndroidKeyStore and MasterKey infrastructure that the
 * androidx.security:security-crypto library requires.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ServerStoreTest {
    @Serializable
    private data class TestBody(val value: String)

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()
    private val server = AdminServer("srv-refresh-races", "https://example.test", "Refresh races")
    private lateinit var tokenPrefs: SharedPreferences

    @Test
    fun credentialRevisionAdvancesOnlyAfterAcceptedCredentialMutations() = runTest {
        val store = ServerStore(context, createMockTokenPrefs())
        val initial = store.credentialRevision.value

        store.updateAccessToken("server-a", "first")
        assertEquals(initial + 1, store.credentialRevision.value)

        store.forgetCredentials("server-a")
        assertEquals(initial + 2, store.credentialRevision.value)
        store.close()
    }

    @Test
    fun clearingAnAlreadyNullCredentialDoesNotAdvanceItsGeneration() = runTest {
        val store = ServerStore(context, createMockTokenPrefs())
        store.addServer(server, "expired")
        store.forgetCredentials(server.id)
        val alreadyCleared = store.accessTokenSnapshot(server.id)

        assertEquals(RecoveryResult.LoginRequired, store.recoverUnauthorized(server, alreadyCleared))

        assertEquals(alreadyCleared, store.accessTokenSnapshot(server.id))
    }

    @Test
    fun loginRequiredRecoveryClearsCredentialAndRoutesTheBoundAdminGate() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                """{"error":"unauthorized","message":"expired"}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val store = ServerStore(
            context = context,
            tokenPreferencesOpenResult = TokenPreferencesOpenResult(
                createMockTokenPrefs(),
                recovered = false,
                storageAvailable = true
            ),
            clientFactory = { origin, snapshot, recovery ->
                ApiClient(origin, snapshot, engine, recovery)
            },
            refreshExchange = RefreshExchange { _, _ -> error("no refresh token should be exchanged") },
            metadataWriter = {}
        )
        val target = AdminServer("login-required", "https://example.test", "Login required")
        store.addServer(target, "expired-access")
        val client = store.client(target)
        val routeOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val routing = adminGateRoutingViewModels(routeOwner)
        val featureStores = SensitiveViewModelStoreRegistry()
        featureStores.owner(target.id, store.accessTokenSnapshot(target.id).generation)
        val binding = AdminUnauthorizedBinding(
            client = client,
            currentCredential = { store.accessTokenSnapshot(target.id) },
            onCurrentUnauthorized = { routing.session.reportAdminUnauthorized(target.id) }
        )

        assertFailsWith<ApiError> {
            client.get<TestBody>("/api/v1/admin/server/status")
        }

        assertNull(store.accessToken(target.id))

        val cleared = store.accessTokenSnapshot(target.id)
        featureStores.owner(target.id, cleared.generation)
        val replacementRouting = adminGateRoutingViewModels(routeOwner)
        replacementRouting.gate.checkAccess(client)
        val reason = replacementRouting.session.sessionExpiredReason(target.id)!!
        replacementRouting.gate.forceNeedsLogin(reason)

        assertEquals(AdminGateState.NeedsLogin(reason), replacementRouting.gate.state.value)
        assertEquals(cleared, store.accessTokenSnapshot(target.id))
        assertEquals(1, requests)
        binding.close()
        store.close()
    }

    @Before
    fun setUpTokenPreferences() {
        removeRefreshRaceServerMetadata()
        tokenPrefs = createMockTokenPrefs()
    }

    @After
    fun cleanUpRefreshRaceServerMetadata() {
        removeRefreshRaceServerMetadata()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentOldTokenFailuresExchangeOneRefreshToken() = runTest {
        var exchanges = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = storeWithRefreshExchange { _, refreshToken ->
            assertEquals("old-refresh", refreshToken)
            exchanges++
            entered.complete(Unit)
            release.await()
            RefreshResponse("new-access", "new-refresh", expires_in = 3600)
        }
        store.addServer(server, "old-access", "old-refresh")
        val rejected = store.accessTokenSnapshot(server.id)

        val first = async { store.recoverUnauthorized(server, rejected) }
        entered.await()
        val second = async { store.recoverUnauthorized(server, rejected) }
        runCurrent()
        assertTrue(second.isActive)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        val firstResult = first.await() as RecoveryResult.Retry
        val secondResult = second.await() as RecoveryResult.Retry

        assertEquals("new-access", firstResult.snapshot.token)
        assertEquals(firstResult, secondResult)
        assertTrue(firstResult.snapshot.generation > rejected.generation)
        assertEquals(1, exchanges)
    }

    @Test
    fun `ServerStore stores both rotated tokens after a v2 refresh`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v2/auth/refresh", request.url.encodedPath)
            respond(
                content = """{"access_token":"v2-access","refresh_token":"v2-refresh","expires_in":900}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val store = storeWithRefreshExchange { _, refreshToken ->
            val client = ApiClient("https://example.test", null, engine)
            try { com.bloemstudios.admin.auth.refresh(client, refreshToken) } finally { client.close() }
        }
        store.addServer(server, "old-access", "old-refresh")
        val rejected = store.accessTokenSnapshot(server.id)

        val result = store.recoverUnauthorized(server, rejected) as RecoveryResult.Retry

        assertEquals("v2-access", result.snapshot.token)
        assertEquals("v2-access", store.accessTokenSnapshot(server.id).token)
        assertEquals("v2-refresh", store.refreshToken(server.id))
    }

    @Test
    fun closeWaitsForAnAlreadyActiveCredentialMutationBeforeReturning() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(context, controlledPrefs)
        runBlocking { store.addServer(server, "initial-access", "initial-refresh") }
        controlledPrefs.beforeCommit = {
            commitEntered.countDown()
            assertTrue(releaseCommit.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val update = executor.submit {
                runBlocking {
                    store.updateAccessToken(server.id, "accepted-access", "accepted-refresh")
                }
            }
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
            val close = executor.submit { store.close() }

            val closeWaitedForMutation = try {
                close.get(1, TimeUnit.SECONDS)
                false
            } catch (_: TimeoutException) {
                true
            }
            releaseCommit.countDown()
            update.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)

            assertTrue(closeWaitedForMutation)
            assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
            assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
        } finally {
            releaseCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeDisplacesAQueuedMutationWithoutSplittingItsExistingCredentialBoundary() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(context, controlledPrefs)
        runBlocking { store.addServer(server, "initial-access", "initial-refresh") }
        val events = mutableListOf<Pair<String, CredentialChangeReason>>()
        store.setCredentialChangeObserver(CredentialChangeObserver { serverId, reason ->
            events += serverId to reason
        })
        controlledPrefs.beforeCommit = {
            commitEntered.countDown()
            assertTrue(releaseCommit.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(3)
        val queuedThread = AtomicReference<Thread>()
        val closeThread = AtomicReference<Thread>()

        try {
            val activeUpdate = executor.submit {
                runBlocking {
                    store.updateAccessToken(server.id, "accepted-access", "accepted-refresh")
                }
            }
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS))

            val queuedUpdate = executor.submit {
                queuedThread.set(Thread.currentThread())
                runBlocking {
                    store.updateAccessToken(server.id, "displaced-access", "displaced-refresh")
                }
            }
            assertTrue(awaitCondition { queuedThread.get()?.state == Thread.State.BLOCKED })

            val close = executor.submit {
                closeThread.set(Thread.currentThread())
                store.close()
            }
            assertTrue(
                awaitCondition {
                    close.isDone || closeThread.get()?.state in setOf(
                        Thread.State.BLOCKED,
                        Thread.State.WAITING,
                        Thread.State.TIMED_WAITING
                    )
                }
            )

            releaseCommit.countDown()
            activeUpdate.get(5, TimeUnit.SECONDS)
            val queuedFailure = try {
                queuedUpdate.get(5, TimeUnit.SECONDS)
                null
            } catch (failure: ExecutionException) {
                failure.cause
            }
            close.get(5, TimeUnit.SECONDS)

            assertTrue(queuedFailure is StoreClosedException)
            assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
            assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
            assertEquals(listOf(server.id to CredentialChangeReason.AUTHENTICATED), events)
        } finally {
            releaseCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedLoginCommitPreservesAcceptedPairClientGenerationAndObserverState() = runTest {
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(context, controlledPrefs)
        store.addServer(server, "accepted-access", "accepted-refresh")
        val client = store.client(server)
        val accepted = store.accessTokenSnapshot(server.id)
        val events = mutableListOf<Pair<String, CredentialChangeReason>>()
        store.setCredentialChangeObserver(CredentialChangeObserver { serverId, reason ->
            events += serverId to reason
        })
        controlledPrefs.commitSucceeds = false

        val failure = assertFailsWith<IOException> {
            store.updateAccessToken(server.id, "uncommitted-access", "uncommitted-refresh")
        }

        assertFalse(failure.message.orEmpty().contains("uncommitted-access"))
        assertEquals(accepted, store.accessTokenSnapshot(server.id))
        assertEquals("accepted-access", store.accessToken(server.id))
        assertEquals("accepted-refresh", store.refreshToken(server.id))
        assertEquals("accepted-access", client.accessToken)
        assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
        assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedCommitRestoresPreferenceMemoryToTheAcceptedPair() = runTest {
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(context, controlledPrefs)
        store.addServer(server, "accepted-access", "accepted-refresh")
        controlledPrefs.applyFailedCommitToMemory = true
        controlledPrefs.commitSucceeds = false

        assertFailsWith<IOException> {
            store.updateAccessToken(server.id, "uncommitted-access", "uncommitted-refresh")
        }

        assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
        assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
        assertEquals("accepted-access", store.accessToken(server.id))
        assertEquals("accepted-refresh", store.refreshToken(server.id))
    }

    @Test
    fun failedRefreshCommitReturnsUnavailableAndPreservesAcceptedState() = runTest {
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(
            context = context,
            tokenPrefs = controlledPrefs,
            refreshExchange = RefreshExchange { _, _ ->
                RefreshResponse("uncommitted-access", "uncommitted-refresh", 3600)
            }
        )
        store.addServer(server, "accepted-access", "accepted-refresh")
        val client = store.client(server)
        val accepted = store.accessTokenSnapshot(server.id)
        val events = mutableListOf<Pair<String, CredentialChangeReason>>()
        store.setCredentialChangeObserver(CredentialChangeObserver { serverId, reason ->
            events += serverId to reason
        })
        controlledPrefs.commitSucceeds = false

        val result = store.recoverUnauthorized(server, accepted) as RecoveryResult.Unavailable

        assertTrue(result.cause is IOException)
        assertEquals(accepted, store.accessTokenSnapshot(server.id))
        assertEquals("accepted-access", store.accessToken(server.id))
        assertEquals("accepted-refresh", store.refreshToken(server.id))
        assertEquals("accepted-access", client.accessToken)
        assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
        assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedForgetCommitDoesNotPublishCredentialClearing() = runTest {
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(context, controlledPrefs)
        store.addServer(server, "accepted-access", "accepted-refresh")
        val client = store.client(server)
        val accepted = store.accessTokenSnapshot(server.id)
        val events = mutableListOf<Pair<String, CredentialChangeReason>>()
        store.setCredentialChangeObserver(CredentialChangeObserver { serverId, reason ->
            events += serverId to reason
        })
        controlledPrefs.commitSucceeds = false

        assertFailsWith<IOException> { store.forgetCredentials(server.id) }

        assertEquals(accepted, store.accessTokenSnapshot(server.id))
        assertEquals("accepted-access", store.accessToken(server.id))
        assertEquals("accepted-refresh", store.refreshToken(server.id))
        assertEquals("accepted-access", client.accessToken)
        assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
        assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedRejectedRefreshClearReturnsUnavailableWithoutClearing() = runTest {
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(
            context = context,
            tokenPrefs = controlledPrefs,
            refreshExchange = RefreshExchange { _, _ -> throw ApiError(401, "invalid_token") }
        )
        store.addServer(server, "accepted-access", "accepted-refresh")
        val accepted = store.accessTokenSnapshot(server.id)
        val events = mutableListOf<Pair<String, CredentialChangeReason>>()
        store.setCredentialChangeObserver(CredentialChangeObserver { serverId, reason ->
            events += serverId to reason
        })
        controlledPrefs.commitSucceeds = false

        val result = store.recoverUnauthorized(server, accepted) as RecoveryResult.Unavailable

        assertTrue(result.cause is IOException)
        assertEquals(accepted, store.accessTokenSnapshot(server.id))
        assertEquals("accepted-access", store.accessToken(server.id))
        assertEquals("accepted-refresh", store.refreshToken(server.id))
        assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
        assertEquals("accepted-refresh", controlledPrefs.getString("${server.id}:refresh", null))
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedAbsentRefreshClearReturnsUnavailableWithoutClearingAccess() = runTest {
        val controlledPrefs = ControllableTokenPreferences()
        val store = ServerStore(
            context = context,
            tokenPrefs = controlledPrefs,
            refreshExchange = RefreshExchange { _, _ -> error("exchange must not run") }
        )
        store.addServer(server, "accepted-access", refreshToken = null)
        val accepted = store.accessTokenSnapshot(server.id)
        controlledPrefs.commitSucceeds = false

        val result = store.recoverUnauthorized(server, accepted) as RecoveryResult.Unavailable

        assertTrue(result.cause is IOException)
        assertEquals(accepted, store.accessTokenSnapshot(server.id))
        assertEquals("accepted-access", store.accessToken(server.id))
        assertNull(store.refreshToken(server.id))
        assertEquals("accepted-access", controlledPrefs.getString(server.id, null))
    }

    @Test
    fun lateFailureForDisplacedAccessTokenReusesCurrentTokenWithoutExchange() = runTest {
        val store = storeWithRefreshExchange { _, _ ->
            fail("late failure must not exchange a refresh token")
            error("unreachable")
        }
        store.addServer(server, "old", "refresh")
        val rejected = store.accessTokenSnapshot(server.id)
        store.updateAccessToken(server.id, "new", "new-refresh")

        assertEquals(
            RecoveryResult.Retry(store.accessTokenSnapshot(server.id)),
            store.recoverUnauthorized(server, rejected)
        )
    }

    @Test
    fun sameTokenValueAtNewGenerationDoesNotRefreshAgain() = runTest {
        var refreshExchangeCalls = 0
        val store = storeWithRefreshExchange { _, _ ->
            refreshExchangeCalls++
            RefreshResponse("unexpected", "unexpected", 3600)
        }
        store.addServer(server, "access", "refresh")
        val rejected = store.accessTokenSnapshot(server.id)
        store.updateAccessToken(server.id, rejected.token!!, "rotated-refresh")

        assertEquals(rejected.token, store.accessTokenSnapshot(server.id).token)
        assertTrue(store.accessTokenSnapshot(server.id).generation > rejected.generation)
        assertEquals(
            RecoveryResult.Retry(store.accessTokenSnapshot(server.id)),
            store.recoverUnauthorized(server, rejected)
        )
        assertEquals(0, refreshExchangeCalls)
    }

    @Test
    fun networkFailureKeepsTokensButRejectedRefreshClearsThem() = runTest {
        val transientFailure = IOException("offline")
        val transientStore = ServerStore(
            context = context,
            tokenPrefs = createMockTokenPrefs(),
            refreshExchange = RefreshExchange { _, _ -> throw transientFailure }
        )
        transientStore.addServer(server, "access", "refresh")

        val transientResult = transientStore.recoverUnauthorized(
            server,
            transientStore.accessTokenSnapshot(server.id)
        ) as RecoveryResult.Unavailable
        assertSame(transientFailure, transientResult.cause)
        assertEquals("access", transientStore.accessToken(server.id))
        assertEquals("refresh", transientStore.refreshToken(server.id))

        val rejectedStore = ServerStore(
            context = context,
            tokenPrefs = createMockTokenPrefs(),
            refreshExchange = RefreshExchange { _, _ -> throw ApiError(401, "invalid_token") }
        )
        rejectedStore.addServer(server, "access", "refresh")

        assertEquals(
            RecoveryResult.LoginRequired,
            rejectedStore.recoverUnauthorized(server, rejectedStore.accessTokenSnapshot(server.id))
        )
        assertNull(rejectedStore.accessToken(server.id))
        assertNull(rejectedStore.refreshToken(server.id))
    }

    @Test
    fun absentRefreshClearsRejectedAccessCredential() = runTest {
        val store = storeWithRefreshExchange { _, _ ->
            fail("absent refresh token must not start an exchange")
            error("unreachable")
        }
        store.addServer(server, "access", refreshToken = null)
        val rejected = store.accessTokenSnapshot(server.id)

        assertEquals(RecoveryResult.LoginRequired, store.recoverUnauthorized(server, rejected))
        assertNull(store.accessToken(server.id))
        assertTrue(store.accessTokenSnapshot(server.id).generation > rejected.generation)
    }

    @Test
    fun loginDuringRefreshDiscardsLateRotatedPair() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exchange = CompletableDeferred<RefreshResponse>()
        val store = storeWithRefreshExchange { _, _ ->
            entered.complete(Unit)
            exchange.await()
        }
        store.addServer(server, "old-access", "old-refresh")
        val rejected = store.accessTokenSnapshot(server.id)
        val recovery = async { store.recoverUnauthorized(server, rejected) }
        entered.await()

        store.updateAccessToken(
            server.id,
            "login-access",
            "login-refresh",
            CredentialChangeReason.AUTHENTICATED
        )
        exchange.complete(RefreshResponse("late-access", "late-refresh", 3600))

        assertEquals(RecoveryResult.Retry(store.accessTokenSnapshot(server.id)), recovery.await())
        assertEquals("login-access", store.accessToken(server.id))
        assertEquals("login-refresh", store.refreshToken(server.id))
    }

    @Test
    fun forgetDuringRefreshCannotBeUndoneByLateExchange() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exchange = CompletableDeferred<RefreshResponse>()
        val store = storeWithRefreshExchange { _, _ ->
            entered.complete(Unit)
            exchange.await()
        }
        store.addServer(server, "old-access", "old-refresh")
        val rejected = store.accessTokenSnapshot(server.id)
        val recovery = async { store.recoverUnauthorized(server, rejected) }
        entered.await()

        store.forgetCredentials(server.id)
        exchange.complete(RefreshResponse("late-access", "late-refresh", 3600))

        assertEquals(RecoveryResult.LoginRequired, recovery.await())
        assertNull(store.accessToken(server.id))
        assertNull(store.refreshToken(server.id))
    }

    @Test
    fun removeDuringRefreshCannotBeUndoneByLateExchange() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exchange = CompletableDeferred<RefreshResponse>()
        val store = storeWithRefreshExchange { _, _ ->
            entered.complete(Unit)
            exchange.await()
        }
        store.addServer(server, "old-access", "old-refresh")
        val rejected = store.accessTokenSnapshot(server.id)
        val recovery = async { store.recoverUnauthorized(server, rejected) }
        entered.await()

        store.removeServer(server.id)
        exchange.complete(RefreshResponse("late-access", "late-refresh", 3600))

        assertEquals(RecoveryResult.LoginRequired, recovery.await())
        assertNull(store.accessToken(server.id))
        assertNull(store.refreshToken(server.id))
        assertTrue(store.servers.value.none { it.id == server.id })
    }

    @Test
    fun closeDuringRefreshDiscardsLateExchangeWithoutChangingPersistedPair() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exchange = CompletableDeferred<RefreshResponse>()
        val store = storeWithRefreshExchange { _, _ ->
            entered.complete(Unit)
            exchange.await()
        }
        store.addServer(server, "old-access", "old-refresh")
        val recovery = async {
            store.recoverUnauthorized(server, store.accessTokenSnapshot(server.id))
        }
        entered.await()

        store.close()
        exchange.complete(RefreshResponse("late-access", "late-refresh", 3600))

        assertTrue(recovery.await() is RecoveryResult.Unavailable)
        assertEquals("old-access", tokenPrefs.getString(server.id, null))
        assertEquals("old-refresh", tokenPrefs.getString("${server.id}:refresh", null))
    }

    @Test
    fun refreshCancellationPropagatesAndKeepsCredentials() = runTest {
        val store = storeWithRefreshExchange { _, _ ->
            throw CancellationException("cancelled")
        }
        store.addServer(server, "access", "refresh")

        assertFailsWith<CancellationException> {
            store.recoverUnauthorized(server, store.accessTokenSnapshot(server.id))
        }

        assertEquals("access", store.accessToken(server.id))
        assertEquals("refresh", store.refreshToken(server.id))
    }

    @Test
    fun credentialObserverReceivesOnlyServerAndReasonForEveryMutation() = runTest {
        val events = mutableListOf<Pair<String, CredentialChangeReason>>()
        val store = storeWithRefreshExchange { _, _ ->
            RefreshResponse("refreshed-access", "refreshed-refresh", 3600)
        }
        store.setCredentialChangeObserver(CredentialChangeObserver { serverId, reason ->
            events += serverId to reason
        })

        store.addServer(server, "access", "refresh")
        store.updateAccessToken(server.id, "access", "login-refresh")
        store.recoverUnauthorized(server, store.accessTokenSnapshot(server.id))
        store.forgetCredentials(server.id)

        assertEquals(
            listOf(
                server.id to CredentialChangeReason.AUTHENTICATED,
                server.id to CredentialChangeReason.AUTHENTICATED,
                server.id to CredentialChangeReason.REFRESHED,
                server.id to CredentialChangeReason.CLEARED
            ),
            events
        )
    }

    @Test
    fun credentialObserverRejectsSecondOwnerAndAllowsDetach() {
        val store = storeWithRefreshExchange { _, _ ->
            error("no refresh expected")
        }
        val first = CredentialChangeObserver { _, _ -> }
        val second = CredentialChangeObserver { _, _ -> }

        store.setCredentialChangeObserver(first)
        store.setCredentialChangeObserver(first)
        assertThrows(IllegalStateException::class.java) {
            store.setCredentialChangeObserver(second)
        }
        store.setCredentialChangeObserver(null)
        store.setCredentialChangeObserver(second)
    }

    @Test
    fun recoveredCredentialStorageStartsAtANewGeneration() {
        val store = ServerStore(
            context = context,
            tokenPreferencesOpenResult = TokenPreferencesOpenResult(
                tokenPrefs,
                recovered = true,
                storageAvailable = true
            )
        )

        assertTrue(store.accessTokenSnapshot(server.id).generation > 0L)
    }

    @Test
    fun cachedClientUsesStoreSnapshotAndTransparentRecovery() = runTest {
        val authorizations = mutableListOf<String?>()
        var calls = 0
        val engine = io.ktor.client.engine.mock.MockEngine { request ->
            calls++
            authorizations += request.headers[io.ktor.http.HttpHeaders.Authorization]
            if (calls == 1) {
                respond("denied", io.ktor.http.HttpStatusCode.Unauthorized)
            } else {
                respond(
                    """{"value":"ok"}""",
                    io.ktor.http.HttpStatusCode.OK,
                    io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val store = ServerStore(
            context = context,
            tokenPreferencesOpenResult = TokenPreferencesOpenResult(
                tokenPrefs,
                recovered = false,
                storageAvailable = true
            ),
            clientFactory = { origin, snapshot, recovery ->
                ApiClient(origin, snapshot, engine, recovery)
            },
            refreshExchange = RefreshExchange { _, refreshToken ->
                assertEquals("old-refresh", refreshToken)
                RefreshResponse("new-access", "new-refresh", 3600)
            }
        )
        store.addServer(server, "old-access", "old-refresh")

        assertEquals("ok", store.client(server).get<TestBody>("/value").value)

        assertEquals(listOf("Bearer old-access", "Bearer new-access"), authorizations)
        assertEquals("new-access", store.accessToken(server.id))
        assertEquals("new-refresh", store.refreshToken(server.id))
    }

    @Test
    fun loginRacingClientCreationUpdatesTheNewlyCachedClient() = runTest {
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val updateStarted = CountDownLatch(1)
        val updateFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val store = ServerStore(context, tokenPrefs, clientFactory = { origin, token ->
            factoryEntered.countDown()
            assertTrue(releaseFactory.await(5, TimeUnit.SECONDS))
            ApiClient(origin, token)
        })
        store.addServer(server, "old-access", "old-refresh")

        val clientFuture = executor.submit<ApiClient> { store.client(server) }
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
        val updateFuture = executor.submit {
            updateStarted.countDown()
            try {
                runBlocking {
                    store.updateAccessToken(server.id, "login-access", "login-refresh")
                }
            } finally {
                updateFinished.countDown()
            }
        }
        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))
        val updateWaitedForClientCreation = !updateFinished.await(1, TimeUnit.SECONDS)
        releaseFactory.countDown()

        val client = clientFuture.get(5, TimeUnit.SECONDS)
        updateFuture.get(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(updateWaitedForClientCreation)
        assertSame(client, store.client(server))
        assertEquals("login-access", client.accessToken)
        client.close()
    }

    @Test
    fun `manifest backup resources exclude encrypted token preferences`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val applicationInfo = context.applicationInfo
        val manifest = AndroidManifest(
            Paths.get("src/main/AndroidManifest.xml"),
            Paths.get("src/main/res"),
            Paths.get("src/main/assets")
        )
        val applicationAttributes = manifest.applicationAttributes
        val fullBackupContent = applicationAttributes.getValue("android:fullBackupContent")
        val dataExtractionRules = applicationAttributes.getValue("android:dataExtractionRules")

        assertEquals(0, applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals("@xml/backup_rules", fullBackupContent)
        assertEquals("@xml/data_extraction_rules", dataExtractionRules)
        assertTrue(xmlExcludesTokenPreferences(manifest.resDirectory, fullBackupContent))
        assertTrue(xmlExcludesTokenPreferences(manifest.resDirectory, dataExtractionRules))
    }

    @Test
    fun `encrypted preference failure recreates only token storage and reports recovery`() {
        var opens = 0
        var deleted = false

        val result = openEncryptedTokenPreferences(
            openAndValidate = {
                opens++
                if (opens == 1) throw RecoverableTokenStoreException()
                createMockTokenPrefs()
            },
            deletePreferencesAndDedicatedKey = { deleted = true },
            nonPersistingFallback = { NonPersistingSharedPreferences() }
        )

        assertTrue(deleted)
        assertTrue(result.recovered)
        assertTrue(result.storageAvailable)
        assertEquals(2, opens)
    }

    @Test
    fun `second encrypted preference failure falls back without persisting secrets`() {
        var opens = 0

        val result = openEncryptedTokenPreferences(
            openAndValidate = {
                opens++
                throw RecoverableTokenStoreException()
            },
            deletePreferencesAndDedicatedKey = {},
            nonPersistingFallback = { NonPersistingSharedPreferences() }
        )

        assertEquals(2, opens)
        assertTrue(result.recovered)
        assertFalse(result.storageAvailable)
        result.preferences.edit().putString("server", "secret").commit()
        assertNull(result.preferences.getString("server", null))
    }

    @Test
    fun `classified cleanup failure falls back without crashing or persisting`() {
        val result = openEncryptedTokenPreferences(
            openAndValidate = { throw RecoverableTokenStoreException() },
            deletePreferencesAndDedicatedKey = { throw RecoverableTokenStoreException() },
            nonPersistingFallback = { NonPersistingSharedPreferences() }
        )

        assertTrue(result.recovered)
        assertFalse(result.storageAvailable)
        result.preferences.edit().putString("server", "secret").commit()
        assertNull(result.preferences.getString("server", null))
    }

    @Test
    fun `unclassified encrypted preference failure is not hidden`() {
        assertThrows(IllegalStateException::class.java) {
            openEncryptedTokenPreferences(
                openAndValidate = { error("programmer failure") },
                deletePreferencesAndDedicatedKey = {},
                nonPersistingFallback = { NonPersistingSharedPreferences() }
            )
        }
    }

    @Test
    fun `legacy cleartext metadata remains visible but cannot construct a client`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var clientsCreated = 0
        val store = ServerStore(context, createMockTokenPrefs(), clientFactory = { _, _ ->
            clientsCreated++
            error("legacy origins must not construct a client")
        })
        val legacyServer = AdminServer("legacy-http", "http://example.test", "Legacy server")

        store.addServer(legacyServer, "legacy-token")
        val reloaded = ServerStore(context, createMockTokenPrefs(), clientFactory = { _, _ ->
            clientsCreated++
            error("legacy origins must not construct a client")
        })
        val loaded = reloaded.servers.value.single { it.id == legacyServer.id }

        assertEquals(OriginProblem.HTTPS_REQUIRED, loaded.connectionProblem)
        assertThrows(InvalidServerOrigin::class.java) { reloaded.client(loaded) }
        assertEquals(0, clientsCreated)
        reloaded.removeServer(legacyServer.id)
    }

    @Test
    fun `server JSON contains only persisted fields`() {
        val server = AdminServer("srv-json", "https://example.test", "JSON Server")
        val encoded = Json.encodeToString(server)

        assertEquals("""{"id":"srv-json","baseUrl":"https://example.test","displayName":"JSON Server"}""", encoded)
        assertEquals(server, Json.decodeFromString<AdminServer>(encoded))
    }

    @Test
    fun `addServer persists metadata and token, reloads in a fresh instance`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrefs = createMockTokenPrefs()
        val store = ServerStore(context, mockPrefs)
        val server = AdminServer("srv-1", "https://example.test", "My Server")

        store.addServer(server, "tok-abc")

        assertEquals(listOf(server), store.servers.value)
        assertEquals("tok-abc", store.accessToken("srv-1"))

        val mockPrefs2 = createMockTokenPrefs(mapOf("srv-1" to "tok-abc"))
        val reloaded = ServerStore(context, mockPrefs2)
        assertEquals(listOf(server), reloaded.servers.value)
        assertEquals("tok-abc", reloaded.accessToken("srv-1"))
    }

    @Test
    fun `removeServer deletes metadata and token`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrefs = createMockTokenPrefs()
        val store = ServerStore(context, mockPrefs)
        val server = AdminServer("srv-1", "https://example.test", "My Server")
        store.addServer(server, "tok-abc")

        store.removeServer("srv-1")

        assertTrue(store.servers.value.isEmpty())
        assertNull(store.accessToken("srv-1"))
    }

    @Test
    fun `add metadata failure leaves credentials and published servers unchanged`() = runTest {
        val server = AdminServer("srv-add-rollback", "https://example.test", "Rollback")
        val prefs = createMockTokenPrefs()
        val store = ServerStore(
            context = context,
            tokenPrefs = prefs,
            metadataWriter = { throw IOException("metadata unavailable") }
        )

        assertFailsWith<IOException> { store.addServer(server, "secret", "refresh-secret") }

        assertFalse(store.servers.value.any { it.id == server.id })
        assertNull(prefs.getString(server.id, null))
        assertNull(prefs.getString("${server.id}:refresh", null))
    }

    @Test
    fun `rename metadata failure leaves published name unchanged`() = runTest {
        val server = AdminServer("srv-rename-rollback", "https://example.test", "Accepted")
        var failWrites = false
        val store = ServerStore(
            context = context,
            tokenPrefs = createMockTokenPrefs(),
            metadataWriter = { if (failWrites) throw IOException("metadata unavailable") }
        )
        store.addServer(server, "secret")
        failWrites = true

        assertFailsWith<IOException> { store.renameServer(server.id, "Uncommitted") }

        assertEquals("Accepted", store.servers.value.single { it.id == server.id }.displayName)
    }

    @Test
    fun `remove credential failure restores metadata and keeps published server`() = runTest {
        val server = AdminServer("srv-remove-rollback", "https://example.test", "Rollback")
        val prefs = ControllableTokenPreferences()
        val writes = mutableListOf<List<AdminServer>>()
        val store = ServerStore(
            context = context,
            tokenPrefs = prefs,
            metadataWriter = { writes += it }
        )
        store.addServer(server, "secret", "refresh-secret")
        prefs.commitSucceeds = false

        assertFailsWith<IOException> { store.removeServer(server.id) }

        assertTrue(store.servers.value.any { it.id == server.id })
        assertEquals(store.servers.value, writes.last())
        assertEquals("secret", store.accessToken(server.id))
        assertEquals("refresh-secret", store.refreshToken(server.id))
    }

    @Test
    fun `updateAccessToken persists the new token and rewrites any cached client's in-memory token`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrefs = createMockTokenPrefs()
        val store = ServerStore(context, mockPrefs)
        val server = AdminServer("srv-1", "https://example.test", "My Server")
        store.addServer(server, "old-token")
        val cachedClient = store.client(server)

        store.updateAccessToken("srv-1", "new-token")

        assertEquals("new-token", store.accessToken("srv-1"))
        // Confirm the ALREADY-CACHED ApiClient instance (the one the gate's
        // re-check would reuse via store.client(server)) had its in-memory
        // token rewritten too, not just the persisted value.
        assertSame(cachedClient, store.client(server))
        assertEquals("new-token", cachedClient.accessToken)
    }

    @Test
    fun `credential reason binds cached client updates to principal replacement or refresh`() = runTest {
        val cachedClient = mockk<ApiClient>(relaxed = true)
        val store = ServerStore(context, createMockTokenPrefs(), clientFactory = { _, _ -> cachedClient })
        val target = AdminServer("srv-principal-binding", "https://example.test", "Binding")
        store.addServer(target, "initial", "initial-refresh")
        assertSame(cachedClient, store.client(target))

        store.updateAccessToken(
            target.id,
            "different-admin",
            "different-refresh",
            CredentialChangeReason.AUTHENTICATED
        )
        store.updateAccessToken(
            target.id,
            "same-admin-rotated",
            "same-refresh-rotated",
            CredentialChangeReason.REFRESHED
        )

        verify(exactly = 1) {
            cachedClient.replaceAuthenticatedPrincipal(match<AccessTokenSnapshot> { it.token == "different-admin" })
        }
        verify(exactly = 1) {
            cachedClient.refreshAccessToken(match<AccessTokenSnapshot> { it.token == "same-admin-rotated" })
        }
    }

    @Test
    fun `renameServer updates the display name and leaves baseUrl and tokens untouched`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrefs = createMockTokenPrefs()
        val store = ServerStore(context, mockPrefs)
        // A unique id (not shared with any other test in this file) --
        // the underlying preferencesDataStore file is a real on-disk store
        // keyed by the shared Robolectric application context, so it isn't
        // reset between test methods the way the mocked token prefs are;
        // reusing "srv-1" here would risk colliding with residue another
        // test in this class left behind.
        val server = AdminServer("srv-rename-1", "https://example.test", "My Server")
        store.addServer(server, "tok-abc", "refresh-abc")

        store.renameServer("srv-rename-1", "Renamed Server")

        val updated = store.servers.value.single { it.id == "srv-rename-1" }
        assertEquals("Renamed Server", updated.displayName)
        assertEquals("https://example.test", updated.baseUrl)
        assertEquals("tok-abc", store.accessToken("srv-rename-1"))
        assertEquals("refresh-abc", store.refreshToken("srv-rename-1"))

        store.removeServer("srv-rename-1")
    }

    @Test
    fun `setSignInProvider records and clears the provider and leaves everything else untouched`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServerStore(context, createMockTokenPrefs())
        val server = AdminServer("srv-provider-1", "https://example.test", "My Server")
        store.addServer(server, "tok-abc", "refresh-abc")

        store.setSignInProvider("srv-provider-1", "26")
        val recorded = store.servers.value.single { it.id == "srv-provider-1" }
        assertEquals("26", recorded.signInProviderId)
        assertEquals("My Server", recorded.displayName)
        assertEquals("https://example.test", recorded.baseUrl)
        assertEquals("tok-abc", store.accessToken("srv-provider-1"))

        store.setSignInProvider("srv-provider-1", null)
        assertEquals(null, store.servers.value.single { it.id == "srv-provider-1" }.signInProviderId)

        store.removeServer("srv-provider-1")
    }

    @Test
    fun `completeReLogin records the provider then installs the tokens`() = runTest {
        val store = ServerStore(context, createMockTokenPrefs())
        store.addServer(AdminServer("srv-relogin-1", "https://example.test", "S", "26"), "old", "old-r")

        store.completeReLogin("srv-relogin-1", "new", "new-r", null)

        assertEquals(null, store.servers.value.single { it.id == "srv-relogin-1" }.signInProviderId)
        assertEquals("new", store.accessToken("srv-relogin-1"))
        assertEquals("new-r", store.refreshToken("srv-relogin-1"))
        store.removeServer("srv-relogin-1")
    }

    @Test
    fun `completeReLogin installs no token when recording the provider fails`() = runTest {
        var failWrites = false
        val store = ServerStore(
            context = context,
            tokenPrefs = createMockTokenPrefs(),
            metadataWriter = { if (failWrites) throw IOException("metadata unavailable") }
        )
        store.addServer(AdminServer("srv-relogin-2", "https://example.test", "S", null), "old", "old-r")
        failWrites = true

        assertFailsWith<IOException> { store.completeReLogin("srv-relogin-2", "new", "new-r", "26") }

        assertEquals("old", store.accessToken("srv-relogin-2"))
        assertEquals(null, store.servers.value.single { it.id == "srv-relogin-2" }.signInProviderId)
    }

    @Test
    fun `forgetCredentials removes both tokens and clears cached client`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrefs = createMockTokenPrefs()
        val store = ServerStore(context, mockPrefs)
        val server = AdminServer("srv-signout-1", "https://example.test", "My Server")
        store.addServer(server, "tok-abc", "refresh-abc")
        val cachedClient = store.client(server)

        store.forgetCredentials("srv-signout-1")

        assertTrue(store.servers.value.any { it.id == "srv-signout-1" })
        assertNull(store.accessToken("srv-signout-1"))
        assertNull(store.refreshToken("srv-signout-1"))
        // The already-cached ApiClient's in-memory token is cleared too, not
        // just the persisted value -- same reasoning as updateAccessToken's
        // own test above.
        assertSame(cachedClient, store.client(server))
        assertNull(cachedClient.accessToken)

        store.removeServer("srv-signout-1")
    }

    @Test
    fun `close closes every cached client once and rejects new clients`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = mockk<ApiClient>(relaxed = true)
        val second = mockk<ApiClient>(relaxed = true)
        val clientsToCreate = ArrayDeque(listOf(first, second))
        val store = ServerStore(context, createMockTokenPrefs(), clientFactory = { _, _ ->
            clientsToCreate.removeFirst()
        })
        val serverOne = AdminServer("srv-close-1", "https://one.example.test", "One")
        val serverTwo = AdminServer("srv-close-2", "https://two.example.test", "Two")

        assertSame(first, store.client(serverOne))
        assertSame(second, store.client(serverTwo))

        store.close()
        store.close()

        verify(exactly = 1) { first.close() }
        verify(exactly = 1) { second.close() }
        assertThrows(IllegalStateException::class.java) { store.client(serverOne) }
    }

    @Test
    fun `client() called concurrently from many threads for a never-cached server returns one shared instance`() {
        // fanOutToServers' per-server async {} blocks are free to run
        // client() from Dispatchers.IO/Default rather than the main
        // dispatcher every previous caller happened to use -- this is a
        // real stress test of ServerStore's ConcurrentHashMap-backed
        // computeIfAbsent, not a single-threaded proxy for it: a plain
        // (unsynchronized) mutableMapOf + Kotlin's getOrPut would risk
        // racing threads each building and returning a DIFFERENT ApiClient
        // for the same never-yet-cached server id.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mockPrefs = createMockTokenPrefs()
        val store = ServerStore(context, mockPrefs)
        val server = AdminServer("srv-1", "https://example.test", "My Server")
        val threadCount = 24
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<com.bloemstudios.admin.network.ApiClient>()

        val futures = (1..threadCount).map {
            executor.submit {
                startLatch.await()
                results.add(store.client(server))
            }
        }
        startLatch.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(threadCount, results.size)
        assertEquals(1, results.toSet().size)
    }

    private fun storeWithRefreshExchange(
        block: suspend (AdminServer, String) -> RefreshResponse
    ): ServerStore = ServerStore(
        context = context,
        tokenPrefs = tokenPrefs,
        refreshExchange = RefreshExchange(block)
    )

    private fun awaitCondition(
        timeout: Long = 5,
        unit: TimeUnit = TimeUnit.SECONDS,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.yield()
        }
        return condition()
    }

    private class ControllableTokenPreferences(
        initialValues: Map<String, Any> = emptyMap()
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()

        @Volatile
        var commitSucceeds: Boolean = true

        @Volatile
        var applyFailedCommitToMemory: Boolean = false

        @Volatile
        var beforeCommit: (() -> Unit)? = null

        override fun getAll(): Map<String, *> = synchronized(values) { values.toMap() }

        override fun getString(key: String?, defValue: String?): String? = synchronized(values) {
            values[key] as? String ?: defValue
        }

        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = synchronized(values) {
            @Suppress("UNCHECKED_CAST")
            (values[key] as? Set<String>)?.toSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = synchronized(values) {
            values[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long = synchronized(values) {
            values[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float = synchronized(values) {
            values[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(values) {
            values[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val replacements = mutableMapOf<String, Any>()
            private val removals = mutableSetOf<String>()
            private var clearsAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                stage(key, value)
            }

            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = apply {
                stage(key, values?.toSet())
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                stage(key, value)
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                stage(key, value)
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                stage(key, value)
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                stage(key, value)
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                val requiredKey = requireNotNull(key)
                replacements.remove(requiredKey)
                removals += requiredKey
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearsAll = true
                replacements.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                beforeCommit?.invoke()
                val succeeds = commitSucceeds
                if (succeeds || applyFailedCommitToMemory) {
                    synchronized(values) {
                        if (clearsAll) values.clear()
                        removals.forEach(values::remove)
                        values.putAll(replacements)
                    }
                }
                return succeeds
            }

            override fun apply() {
                commit()
            }

            private fun stage(key: String?, value: Any?) {
                val requiredKey = requireNotNull(key)
                if (value == null) {
                    replacements.remove(requiredKey)
                    removals += requiredKey
                } else {
                    removals.remove(requiredKey)
                    replacements[requiredKey] = value
                }
            }
        }
    }

    private fun removeRefreshRaceServerMetadata() = runBlocking {
        val cleanupStore = ServerStore(context, createMockTokenPrefs())
        if (cleanupStore.servers.value.any { it.id == server.id }) {
            cleanupStore.removeServer(server.id)
        }
        cleanupStore.close()
    }

    private fun createMockTokenPrefs(initialTokens: Map<String, String> = emptyMap()): SharedPreferences {
        val tokenMap = mutableMapOf<String, String>()
        tokenMap.putAll(initialTokens)

        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { mockPrefs.getString(any(), any()) }.answers { call ->
            @Suppress("UNCHECKED_CAST")
            val defaultValue = call.invocation.args[1] as String?
            tokenMap[call.invocation.args[0] as String] ?: defaultValue
        }

        every { mockEditor.putString(any(), any()) }.answers { call ->
            @Suppress("UNCHECKED_CAST")
            val key = call.invocation.args[0] as String
            val value = call.invocation.args[1] as String
            tokenMap[key] = value
            mockEditor
        }

        every { mockEditor.remove(any()) }.answers { call ->
            val key = call.invocation.args[0] as String
            tokenMap.remove(key)
            mockEditor
        }

        every { mockEditor.apply() }.returns(Unit)
        every { mockEditor.commit() }.returns(true)
        every { mockPrefs.edit() }.returns(mockEditor)

        return mockPrefs
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(
        noinline block: suspend () -> Unit
    ): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }

    private fun xmlExcludesTokenPreferences(resDirectory: Path, reference: String): Boolean {
        val resourceName = reference.removePrefix("@xml/")
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resDirectory.resolve("xml/$resourceName.xml").toFile())
        val excludes = document.getElementsByTagName("exclude")
        for (index in 0 until excludes.length) {
            val attributes = excludes.item(index).attributes
            if (
                attributes.getNamedItem("domain")?.nodeValue == "sharedpref" &&
                attributes.getNamedItem("path")?.nodeValue == "admin_tokens.xml"
            ) {
                return true
            }
        }
        return false
    }

}
