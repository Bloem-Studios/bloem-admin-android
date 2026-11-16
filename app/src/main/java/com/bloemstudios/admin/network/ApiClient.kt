package com.bloemstudios.admin.network

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.websocket.WebSockets
import com.bloemstudios.admin.realtime.EventsProtocol
import com.bloemstudios.admin.realtime.SessionFailure
import com.bloemstudios.admin.realtime.SessionTransportException
import com.bloemstudios.admin.realtime.sessionFailureForStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class ServerErrorBody(
    val error: JsonElement? = null,
    val message: String? = null,
    val detail: String? = null,
    val code: String? = null,
    val type: String? = null,
    val reason: String? = null
)

@PublishedApi
internal object NoBody

@PublishedApi
internal data class AuthenticatedCredentialBinding(
    val snapshot: AccessTokenSnapshot,
    val principalEpoch: Long
)

/** onResponse runs for each received response, before HttpRedirect can follow it. */
private val EventsHandshakeValidation = createClientPlugin("EventsHandshakeValidation") {
    onResponse { response ->
        if (response.call.request.url.protocol.isWebsocket() && response.status != HttpStatusCode.SwitchingProtocols) {
            throw SessionTransportException(sessionFailureForStatus(response.status.value))
        }
    }
}

@Serializable
internal class EventsSocketTicket(val ticket: String, val protocol: String)

class ApiClient(
    @PublishedApi internal val origin: ServerOrigin,
    initialAccessToken: AccessTokenSnapshot,
    engine: HttpClientEngine = CIO.create(),
    @PublishedApi internal val unauthorizedRecovery: UnauthorizedRecovery? = null
) {
    @PublishedApi
    internal val responseJson = Json { ignoreUnknownKeys = true }

    constructor(
        origin: ServerOrigin,
        accessToken: String?,
        engine: HttpClientEngine = CIO.create(),
        unauthorizedRecovery: UnauthorizedRecovery? = null
    ) : this(origin, AccessTokenSnapshot(accessToken, 0L), engine, unauthorizedRecovery)

    constructor(
        baseUrl: String,
        accessToken: String?,
        engine: HttpClientEngine = CIO.create(),
        unauthorizedRecovery: UnauthorizedRecovery? = null,
        @Suppress("UNUSED_PARAMETER") stringConstructorMarker: Unit = Unit
    ) : this(ServerOrigin.parse(baseUrl), accessToken, engine, unauthorizedRecovery)

    @PublishedApi
    internal val authenticatedCredential = AtomicReference(AuthenticatedCredentialBinding(initialAccessToken, 0L))

    private val principalEpochState = MutableStateFlow(0L)
    /** Value updates before replacement returns; collectors are scheduled, so fence publication too. */
    internal val principalEpoch = principalEpochState.asStateFlow()
    internal val currentPrincipalEpoch: Long get() = authenticatedCredential.get().principalEpoch
    private val closedState = MutableStateFlow(false)
    internal val isClosed = closedState.asStateFlow()

    // Session commits and principal replacement share this monitor. Never suspend under it.
    internal val sessionAuthorityLock = Any()
    internal val sessionInvalidations = mutableSetOf<() -> Unit>()
    /**
     * This server's one events socket. Screens ask it for channels (see `watchLive`); it opens only while some screen does
     * and the app is in front.
     */
    val live: com.bloemstudios.admin.realtime.LiveConnection by lazy {
        com.bloemstudios.admin.realtime.LiveConnection(
            this, com.bloemstudios.admin.realtime.KtorLiveTransport(this), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            com.bloemstudios.admin.realtime.LiveEnvironments.current,
        )
    }
    val liveSessions by lazy { com.bloemstudios.admin.realtime.LiveSessionsRepository(this) }

    /** Whether this server is a Bloem server, asked once through `GET /api/bloem/v2/capabilities` and kept for the session. */
    @PublishedApi
    internal val bloemDetector = com.bloemstudios.admin.platform.BloemDetector {
        get<com.bloemstudios.admin.platform.PlatformCapabilities>(com.bloemstudios.admin.network.api.BLOEM_CAPABILITIES_PATH)
    }

    /**
     * Refuses a `/api/bloem/` route on a server already found to be plain Silo, without sending it. The capability check is
     * the one route that is always allowed; it is how a server is found out in the first place.
     */
    @PublishedApi
    internal fun requireBloemRouteAllowed(path: String) {
        if (path.startsWith(com.bloemstudios.admin.network.api.BLOEM_ROUTE_PREFIX) &&
            path.substringBefore('?') != com.bloemstudios.admin.network.api.BLOEM_CAPABILITIES_PATH &&
            bloemDetector.knownPlainSilo
        ) {
            throw ApiError(404, "not_bloem_server", "This server does not have this feature")
        }
    }

    @PublishedApi
    internal val accessToken: String?
        get() = authenticatedCredential.get().snapshot.token

    @Volatile
    var onUnauthorized: (suspend (AccessTokenSnapshot) -> Unit)? = null

    /** Terminal authority loss is epoch-bound, independent of same-principal token rotation. */
    @Volatile
    internal var onTerminalAuthorityLost: ((Long) -> Unit)? = null

    @PublishedApi internal val http = HttpClient(engine) {
        install(WebSockets) { maxFrameSize = 1_048_576 }
        install(EventsHandshakeValidation)
        install(ContentNegotiation) {
            json(responseJson)
        }
        // Without this, a server that goes silent (dropped packets, not even
        // a refused connection) hangs a request forever. That's always been
        // a latent single-server risk, but the "All Servers" fan-out makes
        // it acute: awaitAll() waits for the slowest child and only assigns
        // results once every server has returned, so one unreachable server
        // would otherwise freeze the entire aggregate view indefinitely.
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    fun replaceAuthenticatedPrincipal(token: String?) {
        synchronized(sessionAuthorityLock) {
            while (true) {
                val current = authenticatedCredential.get()
                val replacement = AuthenticatedCredentialBinding(
                    AccessTokenSnapshot(token, current.snapshot.generation + 1L),
                    current.principalEpoch + 1L
                )
                if (authenticatedCredential.compareAndSet(current, replacement)) {
                    sessionInvalidations.toList().forEach { it() }
                    principalEpochState.value = replacement.principalEpoch
                    return
                }
            }
        }
    }

    internal fun replaceAuthenticatedPrincipal(snapshot: AccessTokenSnapshot): AccessTokenSnapshot {
        synchronized(sessionAuthorityLock) {
            while (true) {
                val current = authenticatedCredential.get()
                val installed = if (snapshot.generation > current.snapshot.generation) {
                    snapshot
                } else {
                    AccessTokenSnapshot(snapshot.token, current.snapshot.generation + 1L)
                }
                val replacement = AuthenticatedCredentialBinding(installed, current.principalEpoch + 1L)
                if (authenticatedCredential.compareAndSet(current, replacement)) {
                    sessionInvalidations.toList().forEach { it() }
                    principalEpochState.value = replacement.principalEpoch
                    return installed
                }
            }
        }
    }

    @PublishedApi
    internal fun refreshAccessToken(snapshot: AccessTokenSnapshot, requiredPrincipalEpoch: Long? = null): AccessTokenSnapshot {
        while (true) {
            val current = authenticatedCredential.get()
            check(requiredPrincipalEpoch == null || current.principalEpoch == requiredPrincipalEpoch) {
                "Request belongs to a replaced authenticated principal"
            }
            if (snapshot == current.snapshot || snapshot.generation <= current.snapshot.generation) return current.snapshot
            val replacement = current.copy(snapshot = snapshot)
            if (authenticatedCredential.compareAndSet(current, replacement)) return snapshot
        }
    }

    internal fun accessTokenSnapshotForTest(): AccessTokenSnapshot = authenticatedCredential.get().snapshot

    fun close() {
        synchronized(sessionAuthorityLock) {
            closedState.value = true
            sessionInvalidations.toList().forEach { it() }
        }
        http.close()
    }

    /** Mint with the shared bearer/recovery path, bound to one principal rather than one token. */
    internal suspend fun mintEventsSocketTicket(principalEpoch: Long): EventsSocketTicket {
        try {
            val result = request<EventsSocketTicket, NoBody>(
                com.bloemstudios.admin.network.api.EVENTS_TICKET_PATH, HttpMethod.Post, NoBody,
                requiredPrincipalEpoch = principalEpoch
            )
            if (currentPrincipalEpoch != principalEpoch) throw SessionTransportException(SessionFailure.SignInRequired)
            if (result.protocol != EventsProtocol.PROTOCOL || !Regex("^[A-Za-z0-9_-]{43}$").matches(result.ticket)) {
                throw SessionTransportException(SessionFailure.Protocol)
            }
            return result
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SessionTransportException) {
            throw failure
        } catch (failure: ApiError) {
            throw SessionTransportException(sessionFailureForStatus(failure.statusCode))
        } catch (_: Exception) {
            throw SessionTransportException(if (currentPrincipalEpoch != principalEpoch) SessionFailure.SignInRequired else SessionFailure.Connection)
        }
    }

    suspend inline fun <reified T> getWithValidator(path: String): Pair<T, String?> {
        var validator: String? = null
        val body: T = request(path, HttpMethod.Get, NoBody, onResponse = { validator = it[HttpHeaders.ETag] })
        return body to validator
    }

    suspend inline fun <reified T, reified B> putConditional(path: String, body: B, validator: String): T =
        request(path, HttpMethod.Put, body, extraHeaders = mapOf(HttpHeaders.IfMatch to validator), replayUnauthorized = false)

    suspend inline fun deleteConditional(path: String, validator: String) {
        request<Unit, NoBody>(
            path, HttpMethod.Delete, NoBody,
            extraHeaders = mapOf(HttpHeaders.IfMatch to validator), replayUnauthorized = false
        )
    }

    /** One submission: an uncertain credential-changing response must not be replayed. */
    suspend inline fun <reified T, reified B> postOnce(path: String, body: B): T =
        request(path, HttpMethod.Post, body, replayUnauthorized = false)

    suspend inline fun <reified T> get(path: String): T = request(path, HttpMethod.Get, NoBody)

    // Query params for endpoints that filter/paginate via the query string
    // (e.g. GET /api/v2/admin/logs/audit's cursor/limit/user_id/... filters,
    // GET /api/v2/admin/logs/app's level/q filters).
    // Null values are omitted entirely rather than sent as the literal
    // string "null" -- that's what "filter not set" means to these
    // endpoints. Built as a path+query string (not via Ktor's `parameter()`
    // builder) so this can stay a single request(path, ...) call identical
    // to the no-params overload above.
    suspend inline fun <reified T> get(path: String, params: Map<String, String?>): T =
        request(appendQuery(path, params), HttpMethod.Get, NoBody)

    suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        request(path, HttpMethod.Post, body)

    suspend inline fun post(path: String) {
        request<Unit, NoBody>(path, HttpMethod.Post, NoBody)
    }

    suspend inline fun <reified T, reified B> put(path: String, body: B): T =
        request(path, HttpMethod.Put, body)

    /** A partial update: members left out of [body] are left as they were. */
    suspend inline fun <reified T, reified B> patch(path: String, body: B): T =
        request(path, HttpMethod.Patch, body)

    suspend inline fun delete(path: String) {
        request<Unit, NoBody>(path, HttpMethod.Delete, NoBody)
    }

    @PublishedApi
    internal fun appendQuery(path: String, params: Map<String, String?>): String {
        val present = params.filterValues { it != null }
        if (present.isEmpty()) return path
        val query = present.entries.joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value!!.encodeURLParameter()}"
        }
        return "$path?$query"
    }

    @PublishedApi
    internal suspend inline fun <reified T, reified B> request(
        path: String,
        method: HttpMethod,
        body: B,
        requiredPrincipalEpoch: Long? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        replayUnauthorized: Boolean = true,
        noinline onResponse: ((Headers) -> Unit)? = null
    ): T {
        requireBloemRouteAllowed(path)
        var attempt = 0
        val initialCredential = authenticatedCredential.get()
        check(requiredPrincipalEpoch == null || initialCredential.principalEpoch == requiredPrincipalEpoch) {
            "Request belongs to a replaced authenticated principal"
        }
        var attemptSnapshot = initialCredential.snapshot
        while (true) {
            check(requiredPrincipalEpoch == null || authenticatedCredential.get().principalEpoch == requiredPrincipalEpoch) {
                "Request belongs to a replaced authenticated principal"
            }
            val response = http.request(origin.resolve(path)) {
                this.method = method
                extraHeaders.forEach { (key, value) -> header(key, value) }
                attemptSnapshot.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (body !is NoBody) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            if (response.status.isSuccess()) {
                onResponse?.invoke(response.headers)
                // The widgets on the home screen show server data too: an accepted write may have changed it.
                if (method != HttpMethod.Get) AppDataSignal.emit(AppDataSignal.Wrote(origin.issuer, path))
                if (T::class == Unit::class) {
                    response.bodyAsChannel().cancel()
                    @Suppress("UNCHECKED_CAST")
                    return Unit as T
                }
                return response.body()
            }

            val errorBody = try {
                responseJson.decodeFromString<ServerErrorBody>(response.bodyAsText())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            } finally {
                response.bodyAsChannel().cancel()
            }
            val nestedError = errorBody?.error?.runCatching { jsonObject }?.getOrNull()
            val code = nestedError?.get("code")?.jsonPrimitive?.contentOrNull
                ?: errorBody?.error?.runCatching { jsonPrimitive.contentOrNull }?.getOrNull()
                ?: errorBody?.code
                ?: errorBody?.type?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
            val message = nestedError?.get("message")?.jsonPrimitive?.contentOrNull
                ?: errorBody?.message
                ?: errorBody?.detail
                ?: code
                ?: DEFAULT_ERROR_MESSAGE
            val failure = ApiError(
                statusCode = response.status.value,
                code = code,
                message = message,
                retryAfter = response.headers[HttpHeaders.RetryAfter],
                latestSequence = if (response.status == HttpStatusCode.Conflict) response.headers["X-Silo-Latest-Sequence"]?.toLongOrNull() else null,
                reason = errorBody?.reason
            )
            if (response.status != HttpStatusCode.Unauthorized) throw failure

            if (!replayUnauthorized || attempt > 0) {
                onUnauthorized?.invoke(attemptSnapshot)
                throw failure
            }

            when (val recovery = unauthorizedRecovery?.recover(attemptSnapshot) ?: RecoveryResult.LoginRequired) {
                is RecoveryResult.Retry -> {
                    attemptSnapshot = refreshAccessToken(recovery.snapshot, requiredPrincipalEpoch)
                    attempt++
                }
                RecoveryResult.LoginRequired -> {
                    onUnauthorized?.invoke(attemptSnapshot)
                    throw failure
                }
                is RecoveryResult.Unavailable -> throw recovery.cause
            }
        }
    }
}
