package com.bloemstudios.admin.servers

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.CredentialChangeObserver
import com.bloemstudios.admin.auth.CredentialChangeReason
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.RefreshExchange
import com.bloemstudios.admin.auth.RefreshResponse
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.auth.refresh
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ServerOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

private val Context.serverDataStore by preferencesDataStore(name = "admin_servers")
private val SERVERS_KEY = stringPreferencesKey("servers")

/**
 * The saved servers (names and addresses only), read without opening the encrypted token store. The background check
 * uses it so a run that only watches reachability never touches a token.
 */
suspend fun readSavedServers(context: Context): List<AdminServer> {
    val prefs = context.applicationContext.serverDataStore.data.first()
    return prefs[SERVERS_KEY]?.let { kotlin.runCatching { Json.decodeFromString<List<AdminServer>>(it) }.getOrDefault(emptyList()) } ?: emptyList()
}

internal const val TOKEN_PREFERENCES_NAME = "admin_tokens"
internal const val TOKEN_MASTER_KEY_ALIAS = "bloem_admin_tokens_master_key"

internal class RecoverableTokenStoreException : Exception()

internal data class TokenPreferencesOpenResult(
    val preferences: SharedPreferences,
    val recovered: Boolean,
    val storageAvailable: Boolean
)

internal fun openEncryptedTokenPreferences(
    openAndValidate: () -> SharedPreferences,
    deletePreferencesAndDedicatedKey: () -> Unit,
    nonPersistingFallback: () -> SharedPreferences
): TokenPreferencesOpenResult {
    return try {
        TokenPreferencesOpenResult(openAndValidate(), recovered = false, storageAvailable = true)
    } catch (first: RecoverableTokenStoreException) {
        try {
            deletePreferencesAndDedicatedKey()
        } catch (cleanup: RecoverableTokenStoreException) {
            return TokenPreferencesOpenResult(
                nonPersistingFallback(),
                recovered = true,
                storageAvailable = false
            )
        }
        try {
            TokenPreferencesOpenResult(openAndValidate(), recovered = true, storageAvailable = true)
        } catch (second: RecoverableTokenStoreException) {
            TokenPreferencesOpenResult(
                nonPersistingFallback(),
                recovered = true,
                storageAvailable = false
            )
        }
    }
}

internal class NonPersistingSharedPreferences : SharedPreferences {
    override fun getAll(): Map<String, *> = emptyMap<String, Any?>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = NonPersistingEditor

    private object NonPersistingEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}

class StoreClosedException : IllegalStateException("ServerStore is closed")

class CredentialPersistenceException internal constructor(
    internal val rollbackPersisted: Boolean = false
) : IOException("Credential persistence failed") {
    override fun toString(): String = "CredentialPersistenceException"
}

class ServerStore internal constructor(
    private val context: Context,
    tokenPreferencesOpenResult: TokenPreferencesOpenResult,
    private val clientFactory: (ServerOrigin, AccessTokenSnapshot, UnauthorizedRecovery) -> ApiClient = defaultClientFactory,
    private val refreshExchange: RefreshExchange = defaultRefreshExchange,
    private val metadataWriter: suspend (List<AdminServer>) -> Unit = { servers ->
        context.serverDataStore.edit { it[SERVERS_KEY] = Json.encodeToString(servers) }
    },
    private val metadataReader: suspend () -> List<AdminServer> = {
        val prefs = context.serverDataStore.data.first()
        prefs[SERVERS_KEY]?.let { kotlin.runCatching { Json.decodeFromString<List<AdminServer>>(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }
) : Closeable {
    companion object {
        private val defaultClientFactory:
            (ServerOrigin, AccessTokenSnapshot, UnauthorizedRecovery) -> ApiClient =
            { origin, snapshot, recovery ->
                ApiClient(origin, snapshot, unauthorizedRecovery = recovery)
            }

        private val defaultRefreshExchange = RefreshExchange { server, refreshToken ->
            val refreshClient = ApiClient(server.validatedOrigin(), null)
            try {
                refresh(refreshClient, refreshToken)
            } finally {
                refreshClient.close()
            }
        }

        fun createEncryptedPrefs(context: Context): SharedPreferences {
            return createEncryptedPrefs(context, TOKEN_PREFERENCES_NAME, TOKEN_MASTER_KEY_ALIAS)
        }

        internal fun createEncryptedPrefs(
            context: Context,
            preferencesName: String,
            masterKeyAlias: String
        ): SharedPreferences {
            return EncryptedSharedPreferences.create(
                context,
                preferencesName,
                MasterKey.Builder(context, masterKeyAlias)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun openTokenPreferences(context: Context): TokenPreferencesOpenResult =
            openTokenPreferences(context, TOKEN_PREFERENCES_NAME, TOKEN_MASTER_KEY_ALIAS)

        internal fun openTokenPreferences(
            context: Context,
            preferencesName: String,
            masterKeyAlias: String
        ): TokenPreferencesOpenResult = openEncryptedTokenPreferences(
            openAndValidate = {
                classifyTokenStoreFailure {
                    createEncryptedPrefs(context, preferencesName, masterKeyAlias).also { preferences ->
                        preferences.all
                    }
                }
            },
            deletePreferencesAndDedicatedKey = {
                classifyTokenStoreFailure {
                    context.deleteSharedPreferences(preferencesName)
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
                        if (containsAlias(masterKeyAlias)) deleteEntry(masterKeyAlias)
                    }
                }
            },
            nonPersistingFallback = ::NonPersistingSharedPreferences
        )

        private inline fun <T> classifyTokenStoreFailure(block: () -> T): T {
            return try {
                block()
            } catch (failure: GeneralSecurityException) {
                throw RecoverableTokenStoreException()
            } catch (failure: IOException) {
                throw RecoverableTokenStoreException()
            } catch (failure: SecurityException) {
                throw RecoverableTokenStoreException()
            }
        }
    }

    constructor(context: Context) : this(
        context,
        openTokenPreferences(context),
        defaultClientFactory,
        defaultRefreshExchange,
    )

    internal constructor(
        context: Context,
        tokenPrefs: SharedPreferences,
        clientFactory: ((ServerOrigin, String?) -> ApiClient)? = null,
        refreshExchange: RefreshExchange = defaultRefreshExchange,
        metadataWriter: suspend (List<AdminServer>) -> Unit = { servers ->
            context.serverDataStore.edit { it[SERVERS_KEY] = Json.encodeToString(servers) }
        },
        metadataReader: suspend () -> List<AdminServer> = {
            val prefs = context.serverDataStore.data.first()
            prefs[SERVERS_KEY]?.let { kotlin.runCatching { Json.decodeFromString<List<AdminServer>>(it) }.getOrDefault(emptyList()) } ?: emptyList()
        }
    ) : this(
        context,
        TokenPreferencesOpenResult(tokenPrefs, recovered = false, storageAvailable = true),
        clientFactory?.let { factory ->
            { origin, snapshot, _ -> factory(origin, snapshot.token) }
        } ?: defaultClientFactory,
        refreshExchange,
        metadataWriter,
        metadataReader
    )

    private val tokenPrefs = tokenPreferencesOpenResult.preferences
    private val initialCredentialGeneration = if (tokenPreferencesOpenResult.recovered) 1L else 0L
    private val _recoveredCredentials = MutableStateFlow(tokenPreferencesOpenResult.recovered)
    val recoveredCredentials: StateFlow<Boolean> = _recoveredCredentials
    private val _credentialStorageAvailable = MutableStateFlow(tokenPreferencesOpenResult.storageAvailable)
    val credentialStorageAvailable: StateFlow<Boolean> = _credentialStorageAvailable
    private val credentialRevisionCounter = AtomicLong(0L)
    private val _credentialRevision = MutableStateFlow(0L)
    val credentialRevision: StateFlow<Long> = _credentialRevision

    private val _servers = MutableStateFlow<List<AdminServer>>(loadServersSync())
    val servers: StateFlow<List<AdminServer>> = _servers

    // ConcurrentHashMap (not a plain mutableMapOf) because client() is now
    // called from inside fanOutToServers' per-server async {} blocks (see
    // ServerFanOut.kt/AggregateStreamsViewModel), which the caller is free to
    // dispatch on Dispatchers.IO/Default for a real network fan-out -- this
    // was previously safe only by accident, since every prior caller
    // happened to run on the main dispatcher.
    private val clients = ConcurrentHashMap<String, ApiClient>()
    private val clientLifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val credentialLifecycleLock = ReentrantReadWriteLock(true)
    private val credentialStates = ConcurrentHashMap<String, AtomicReference<CredentialState>>()
    private val credentialMutationLocks = ConcurrentHashMap<String, Any>()
    private val credentialObserverLock = Any()

    @Volatile
    private var credentialChangeObserver: CredentialChangeObserver? = null

    // One Mutex per server id, created lazily, guarding each single-flight
    // recoverUnauthorized() exchange. The refresh token is single-use
    // (the server rotates it on every successful call), so two concurrent
    // 401s on the same server racing to refresh must be serialized -- without
    // this, the second caller could read the same (about-to-be-invalidated)
    // refresh token the first caller is still exchanging, and its own refresh
    // call would fail with invalid_token even though the first one succeeded.
    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverMutationMutex = Mutex()

    private data class CredentialState(
        val accessToken: String?,
        val refreshToken: String?,
        val generation: Long
    ) {
        fun accessSnapshot(): AccessTokenSnapshot = AccessTokenSnapshot(accessToken, generation)

        override fun toString(): String =
            "CredentialState(accessToken=<redacted>, refreshToken=<redacted>, generation=$generation)"
    }

    private fun loadServersSync(): List<AdminServer> = runBlocking { metadataReader() }

    suspend fun addServer(server: AdminServer, accessToken: String, refreshToken: String? = null) =
        serverMutationMutex.withLock {
            val accepted = _servers.value
            val updated = accepted.filterNot { it.id == server.id } + server
            persistOrRollback(updated, accepted)
            try {
                withCredentialMutation(server.id) {
                    installCredentialLocked(
                        serverId = server.id,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        replaceRefreshToken = true,
                        reason = CredentialChangeReason.AUTHENTICATED
                    )
                }
            } catch (failure: Throwable) {
                rollbackMetadata(accepted, failure)
                throw failure
            }
            _servers.value = updated
        }

    suspend fun removeServer(id: String) = serverMutationMutex.withLock {
        val accepted = _servers.value
        val updated = accepted.filterNot { it.id == id }
        persistOrRollback(updated, accepted)
        try {
            withCredentialMutation(id) { clearCredentialLocked(id) }
        } catch (failure: Throwable) {
            rollbackMetadata(accepted, failure)
            throw failure
        }
        _servers.value = updated
        synchronized(clientLifecycleLock) { clients.remove(id) }?.close()
    }

    /**
     * Rewrites just [id]'s display name, leaving baseUrl and every stored
     * token untouched -- backs the server list's "Rename" action.
     */
    suspend fun renameServer(id: String, displayName: String) = serverMutationMutex.withLock {
        val accepted = _servers.value
        val updated = accepted.map { if (it.id == id) it.copy(displayName = displayName) else it }
        persistOrRollback(updated, accepted)
        _servers.value = updated
    }

    /** Records the provider [id] was last signed in with, or null after a password sign-in. */
    suspend fun setSignInProvider(id: String, providerId: String?) = serverMutationMutex.withLock {
        val accepted = _servers.value
        val updated = accepted.map { if (it.id == id) it.copy(signInProviderId = providerId) else it }
        if (updated == accepted) return@withLock
        persistOrRollback(updated, accepted)
        _servers.value = updated
    }

    /**
     * Stores the tokens of a successful re-login and the provider it used (null for a password).
     * The provider is recorded first: if that write fails, no token is installed.
     */
    suspend fun completeReLogin(id: String, accessToken: String, refreshToken: String, signInProviderId: String?) {
        setSignInProvider(id, signInProviderId)
        updateAccessToken(id, accessToken, refreshToken)
    }

    /**
     * Clears [id]'s stored access and refresh tokens WITHOUT removing the
     * server entry itself -- backs the server list's "Forget credentials"
     * action,
     * distinct from [removeServer]. Also pushes the cleared token into any
     * already-cached ApiClient for this server (same reasoning as
     * [updateAccessToken]) so a screen still holding that client sends its
     * very next request without an access token instead of only picking up
     * the local clear after the client is recreated; that unauthenticated
     * request gets a 401 and routes through the normal re-login flow.
     */
    suspend fun forgetCredentials(id: String) {
        withCredentialMutation(id) {
            clearCredentialLocked(id)
        }
    }

    fun accessTokenSnapshot(serverId: String): AccessTokenSnapshot =
        credentialState(serverId).get().accessSnapshot()

    fun accessToken(serverId: String): String? = accessTokenSnapshot(serverId).token

    /** The stored refresh token for [serverId], or null if none is stored (e.g. a server added before refresh-token support existed, or one that's never logged in since). */
    fun refreshToken(serverId: String): String? = credentialState(serverId).get().refreshToken

    private fun refreshTokenKey(serverId: String) = "$serverId:refresh"

    /**
     * Rewrites just the access token (and, when given, the refresh token) for
     * a server -- e.g. after a successful re-login following a 401 from the
     * admin-access probe, or after a successful /auth/refresh exchange --
     * without touching the server's other metadata. Also pushes the new
     * access token into any already-cached ApiClient instance for this
     * server so a subsequent client(server) call picks it up immediately.
     */
    suspend fun updateAccessToken(
        serverId: String,
        accessToken: String,
        refreshToken: String? = null,
        reason: CredentialChangeReason = CredentialChangeReason.AUTHENTICATED
    ) {
        withCredentialMutation(serverId) {
            installCredentialLocked(
                serverId = serverId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                replaceRefreshToken = refreshToken != null,
                reason = reason
            )
        }
    }

    suspend fun recoverUnauthorized(
        server: AdminServer,
        rejected: AccessTokenSnapshot
    ): RecoveryResult {
        val refreshMutex = refreshMutexes.computeIfAbsent(server.id) { Mutex() }
        return refreshMutex.withLock refreshGate@{
            val refreshToken = try {
                withCredentialMutation(server.id) {
                    val current = accessTokenSnapshot(server.id)
                    if (current != rejected) {
                        return@refreshGate recoveryForDisplacedCredential(current)
                    }
                    refreshToken(server.id) ?: run {
                        return@refreshGate try {
                            clearCredentialLocked(server.id)
                            RecoveryResult.LoginRequired
                        } catch (failure: CredentialPersistenceException) {
                            RecoveryResult.Unavailable(failure)
                        }
                    }
                }
            } catch (failure: StoreClosedException) {
                return@refreshGate RecoveryResult.Unavailable(failure)
            }

            // The refresh token is single use: once the exchange has been sent, the server may have rotated it, and the
            // new pair exists only in the answer. So the exchange and the install that stores its answer run to the end
            // even if the caller is cancelled (a screen left, or WorkManager stopping the background check); the caller
            // then sees its cancellation. Cancelling between them would leave the spent token stored, and the next
            // refresh would be refused and sign the admin out.
            withContext(NonCancellable) {
                try {
                    val rotated = refreshExchange.exchange(server, refreshToken)
                    installRefreshedCredentialIfCurrent(server.id, rejected, rotated)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: ApiError) {
                    if (error.statusCode == 400 || error.statusCode == 401) {
                        clearRejectedCredentialOrUseNewer(server.id, rejected)
                    } else {
                        unavailableIfStillCurrent(server.id, rejected, error)
                    }
                } catch (error: IOException) {
                    unavailableIfStillCurrent(server.id, rejected, error)
                }
            }
        }
    }

    fun setCredentialChangeObserver(observer: CredentialChangeObserver?) {
        synchronized(credentialObserverLock) {
            val current = credentialChangeObserver
            check(observer == null || current == null || current === observer) {
                "A credential-change observer is already attached"
            }
            credentialChangeObserver = observer
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw StoreClosedException()
    }

    private fun credentialState(serverId: String): AtomicReference<CredentialState> =
        credentialStates.computeIfAbsent(serverId) {
            AtomicReference(
                CredentialState(
                    accessToken = tokenPrefs.getString(serverId, null),
                    refreshToken = tokenPrefs.getString(refreshTokenKey(serverId), null),
                    generation = initialCredentialGeneration
                )
            )
        }

    private fun credentialMutationLock(serverId: String): Any =
        credentialMutationLocks.computeIfAbsent(serverId) { Any() }

    private inline fun <T> withCredentialMutation(serverId: String, block: () -> T): T {
        val lifecyclePermit = credentialLifecycleLock.readLock()
        lifecyclePermit.lock()
        try {
            checkOpen()
            val mutationLock = credentialMutationLock(serverId)
            return synchronized(mutationLock) {
                checkOpen()
                block()
            }
        } finally {
            lifecyclePermit.unlock()
        }
    }

    private fun installCredentialLocked(
        serverId: String,
        accessToken: String,
        refreshToken: String?,
        replaceRefreshToken: Boolean,
        reason: CredentialChangeReason
    ): AccessTokenSnapshot {
        val state = credentialState(serverId)
        val current = state.get()
        val next = CredentialState(
            accessToken = accessToken,
            refreshToken = if (replaceRefreshToken) refreshToken else current.refreshToken,
            generation = current.generation + 1L
        )
        val editor = tokenPrefs.edit().apply {
            putString(serverId, accessToken)
            if (replaceRefreshToken) {
                if (refreshToken == null) remove(refreshTokenKey(serverId))
                else putString(refreshTokenKey(serverId), refreshToken)
            }
        }
        commitCredentialEditOrThrow(serverId, editor, current)
        state.set(next)
        val accessSnapshot = next.accessSnapshot()
        clients[serverId]?.let { client ->
            when (reason) {
                CredentialChangeReason.REFRESHED -> client.refreshAccessToken(accessSnapshot)
                CredentialChangeReason.AUTHENTICATED,
                CredentialChangeReason.CLEARED -> client.replaceAuthenticatedPrincipal(accessSnapshot)
            }
        }
        _credentialRevision.value = credentialRevisionCounter.incrementAndGet()
        credentialChangeObserver?.onChanged(serverId, reason)
        return accessSnapshot
    }

    private fun clearCredentialLocked(serverId: String): AccessTokenSnapshot {
        val state = credentialState(serverId)
        val current = state.get()
        if (current.accessToken == null && current.refreshToken == null) return current.accessSnapshot()
        val next = CredentialState(
            accessToken = null,
            refreshToken = null,
            generation = current.generation + 1L
        )
        val editor = tokenPrefs.edit().remove(serverId).remove(refreshTokenKey(serverId))
        commitCredentialEditOrThrow(serverId, editor, current)
        state.set(next)
        val accessSnapshot = next.accessSnapshot()
        clients[serverId]?.replaceAuthenticatedPrincipal(accessSnapshot)
        _credentialRevision.value = credentialRevisionCounter.incrementAndGet()
        credentialChangeObserver?.onChanged(serverId, CredentialChangeReason.CLEARED)
        return accessSnapshot
    }

    private fun commitCredentialEditOrThrow(
        serverId: String,
        editor: SharedPreferences.Editor,
        accepted: CredentialState
    ) {
        if (editor.commit()) return

        val rollbackPersisted = tokenPrefs.edit().apply {
            if (accepted.accessToken == null) remove(serverId)
            else putString(serverId, accepted.accessToken)
            if (accepted.refreshToken == null) remove(refreshTokenKey(serverId))
            else putString(refreshTokenKey(serverId), accepted.refreshToken)
        }.commit()
        throw CredentialPersistenceException(rollbackPersisted)
    }

    private fun recoveryForDisplacedCredential(current: AccessTokenSnapshot): RecoveryResult =
        if (current.token == null) RecoveryResult.LoginRequired else RecoveryResult.Retry(current)

    private fun installRefreshedCredentialIfCurrent(
        serverId: String,
        rejected: AccessTokenSnapshot,
        rotated: RefreshResponse
    ): RecoveryResult = try {
        withCredentialMutation(serverId) {
            val current = accessTokenSnapshot(serverId)
            if (current != rejected) return@withCredentialMutation recoveryForDisplacedCredential(current)
            try {
                RecoveryResult.Retry(
                    installCredentialLocked(
                        serverId = serverId,
                        accessToken = rotated.access_token,
                        refreshToken = rotated.refresh_token,
                        replaceRefreshToken = true,
                        reason = CredentialChangeReason.REFRESHED
                    )
                )
            } catch (failure: CredentialPersistenceException) {
                RecoveryResult.Unavailable(failure)
            }
        }
    } catch (failure: StoreClosedException) {
        RecoveryResult.Unavailable(failure)
    }

    private fun clearRejectedCredentialOrUseNewer(
        serverId: String,
        rejected: AccessTokenSnapshot
    ): RecoveryResult = try {
        withCredentialMutation(serverId) {
            val current = accessTokenSnapshot(serverId)
            if (current != rejected) return@withCredentialMutation recoveryForDisplacedCredential(current)
            try {
                clearCredentialLocked(serverId)
                RecoveryResult.LoginRequired
            } catch (failure: CredentialPersistenceException) {
                RecoveryResult.Unavailable(failure)
            }
        }
    } catch (failure: StoreClosedException) {
        RecoveryResult.Unavailable(failure)
    }

    private fun unavailableIfStillCurrent(
        serverId: String,
        rejected: AccessTokenSnapshot,
        error: Throwable
    ): RecoveryResult = try {
        withCredentialMutation(serverId) {
            val current = accessTokenSnapshot(serverId)
            if (current != rejected) recoveryForDisplacedCredential(current)
            else RecoveryResult.Unavailable(error)
        }
    } catch (failure: StoreClosedException) {
        RecoveryResult.Unavailable(failure)
    }

    // computeIfAbsent (ConcurrentHashMap's own atomic version), not Kotlin's
    // generic getOrPut extension -- getOrPut is a plain get-then-put with no
    // atomicity guarantee of its own, so two callers racing on the same
    // never-yet-cached server id could each build and return a different
    // ApiClient instance.
    fun client(server: AdminServer): ApiClient {
        val origin = server.validatedOrigin()
        return withCredentialMutation(server.id) {
            synchronized(clientLifecycleLock) {
                clients.computeIfAbsent(server.id) {
                    clientFactory(
                        origin,
                        accessTokenSnapshot(server.id),
                        UnauthorizedRecovery { rejected -> recoverUnauthorized(server, rejected) }
                    )
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            awaitCloseCompletion()
            return
        }

        val lifecyclePermit = credentialLifecycleLock.writeLock()
        lifecyclePermit.lock()
        try {
            val clientsToClose = synchronized(clientLifecycleLock) {
                clients.values.toList().also {
                    clients.clear()
                    refreshMutexes.clear()
                    credentialMutationLocks.clear()
                    credentialStates.clear()
                }
            }
            synchronized(credentialObserverLock) {
                credentialChangeObserver = null
            }
            clientsToClose.forEach { it.close() }
        } finally {
            lifecyclePermit.unlock()
            closeCompleted.countDown()
        }
    }

    private fun awaitCloseCompletion() {
        var interrupted = false
        while (true) {
            try {
                closeCompleted.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private suspend fun persist(servers: List<AdminServer>) {
        metadataWriter(servers)
    }

    private suspend fun rollbackMetadata(accepted: List<AdminServer>, failure: Throwable) {
        try {
            withContext(NonCancellable) { persist(accepted) }
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private suspend fun persistOrRollback(updated: List<AdminServer>, accepted: List<AdminServer>) {
        try {
            persist(updated)
        } catch (failure: Throwable) {
            rollbackMetadata(accepted, failure)
            throw failure
        }
    }
}
