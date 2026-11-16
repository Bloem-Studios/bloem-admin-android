package com.bloemstudios.admin.servers

import com.bloemstudios.admin.auth.AuthProvider
import com.bloemstudios.admin.auth.AuthProviders
import com.bloemstudios.admin.auth.OAuthRedirect
import com.bloemstudios.admin.auth.Pkce
import com.bloemstudios.admin.auth.PendingOAuth
import com.bloemstudios.admin.auth.completeOAuthLogin
import com.bloemstudios.admin.auth.getServerIdentity
import com.bloemstudios.admin.auth.listAuthProviders
import com.bloemstudios.admin.auth.login
import com.bloemstudios.admin.auth.nativeStartUrl
import com.bloemstudios.admin.auth.newAppState
import com.bloemstudios.admin.auth.parseOAuthRedirect
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.InvalidServerOrigin
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.isClientUpgradeRequired
import com.bloemstudios.admin.organization.discoverAdminContexts
import com.bloemstudios.admin.network.api.getHealth
import com.bloemstudios.admin.network.api.getServerStatus
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import java.util.UUID

enum class AddServerFailure {
    UNSAFE_ORIGIN,
    HEALTH_CHECK_FAILED,
    INVALID_CREDENTIALS,
    ADMIN_REQUIRED,
    INCOMPATIBLE_SERVER,
    UNREACHABLE,
    STORAGE_FAILED,
    /** The person came back without signing in. Shows nothing. */
    OAUTH_CANCELLED,
    /** The redirect or the redemption failed; [AddServerResult.Rejected.detail] is the reason. */
    OAUTH_FAILED,
    /** The redirect did not come from this server and sign-in. */
    OAUTH_UNTRUSTED,
    NO_BROWSER,
    NO_SUPPORTED_SIGN_IN,
    /** The server answered 410 client_upgrade_required. */
    CLIENT_UPGRADE_REQUIRED
}

sealed interface AddServerResult {
    data class Connected(
        val server: AdminServer,
        val accessToken: String,
        val refreshToken: String
    ) : AddServerResult

    data class Rejected(
        val reason: AddServerFailure,
        val detail: String? = null
    ) : AddServerResult
}

/**
 * Persists a successfully admitted server for the add-server UI. Persistence
 * happens after networking, so convert write failures into an ordinary form
 * rejection instead of letting them escape the UI coroutine.
 */
suspend fun persistConnectedServer(
    connected: AddServerResult.Connected,
    persist: suspend (AdminServer, String, String) -> Unit
): AddServerResult = try {
    persist(connected.server, connected.accessToken, connected.refreshToken)
    connected
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    AddServerResult.Rejected(AddServerFailure.STORAGE_FAILED, error.message)
}

/** What step one learned about a server: enough to show its sign-in options and to sign in. */
sealed interface DiscoveryResult {
    data class Discovered(
        val origin: ServerOrigin,
        val displayName: String,
        val serverId: String,
        val providers: AuthProviders
    ) : DiscoveryResult

    data class Rejected(val reason: AddServerFailure) : DiscoveryResult
}

/** A pending sign-in lives this long in memory (spec: Security baseline). */
internal const val PENDING_OAUTH_LIFETIME_MILLIS = 10 * 60_000L

class AddServerConnector(
    private val clientFactory: (ServerOrigin, String?) -> ApiClient =
        { origin, token -> ApiClient(origin, token) },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Step one: reads the server's name, identity and sign-in providers. */
    suspend fun discover(rawOrigin: String): DiscoveryResult {
        val origin = try {
            ServerOrigin.parseTyped(rawOrigin)
        } catch (_: InvalidServerOrigin) {
            return DiscoveryResult.Rejected(AddServerFailure.UNSAFE_ORIGIN)
        }
        val client = clientFactory(origin, null)
        var stage = ConnectStage.HEALTH
        try {
            val health = client.getHealth()
            stage = ConnectStage.DISCOVERY
            val identity = getServerIdentity(client)
            val providers = listAuthProviders(client)
            val displayName = health.server_name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: origin.resolve("/").host
            return DiscoveryResult.Discovered(origin, displayName, identity.server_id, providers)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiError) {
            if (error.statusCode == HttpStatusCode.Gone.value) {
                return DiscoveryResult.Rejected(AddServerFailure.CLIENT_UPGRADE_REQUIRED)
            }
            return DiscoveryResult.Rejected(error.toFailure(stage))
        } catch (_: Exception) {
            return DiscoveryResult.Rejected(AddServerFailure.UNREACHABLE)
        } finally {
            client.close()
        }
    }

    /**
     * Step one for re-login: reads the saved server's identity and providers. The saved name is kept
     * and no health check is made. An upgrade-required answer is
     * reported; any other failure of either call degrades to the password alone (no provider can start
     * without an identity), as re-login always did.
     */
    suspend fun discoverForReLogin(server: AdminServer): DiscoveryResult {
        val origin = try {
            ServerOrigin.parse(server.baseUrl)
        } catch (_: InvalidServerOrigin) {
            return DiscoveryResult.Rejected(AddServerFailure.UNSAFE_ORIGIN)
        }
        val client = clientFactory(origin, null)
        try {
            val identity = getServerIdentity(client)
            val providers = listAuthProviders(client)
            return DiscoveryResult.Discovered(origin, server.displayName, identity.server_id, providers)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiError) {
            if (error.isClientUpgradeRequired) return DiscoveryResult.Rejected(AddServerFailure.CLIENT_UPGRADE_REQUIRED)
            return passwordOnly(origin, server)
        } catch (_: Exception) {
            return passwordOnly(origin, server)
        } finally {
            client.close()
        }
    }

    /** Pre-OIDC behavior: without identity and providers the person can still try the password. */
    private fun passwordOnly(origin: ServerOrigin, server: AdminServer) =
        DiscoveryResult.Discovered(origin, server.displayName, "", AuthProviders(true, emptyList()))

    /** [probeAdmin] is false for re-login, which leaves the admin check to the gate as it always did. */
    suspend fun signInWithPassword(
        discovered: DiscoveryResult.Discovered,
        username: String,
        password: String,
        probeAdmin: Boolean = true
    ): AddServerResult {
        val client = clientFactory(discovered.origin, null)
        var stage = ConnectStage.LOGIN
        try {
            val loginResponse = login(client, username, password)
            if (probeAdmin) {
                client.replaceAuthenticatedPrincipal(loginResponse.access_token)
                stage = ConnectStage.ADMIN_STATUS
                client.requireAdminAccess()
            }
            return connected(discovered, loginResponse.access_token, loginResponse.refresh_token, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiError) {
            return AddServerResult.Rejected(error.toFailure(stage), error.message)
        } catch (error: Exception) {
            return AddServerResult.Rejected(AddServerFailure.UNREACHABLE, error.message)
        } finally {
            client.close()
        }
    }

    /**
     * Signs in through [provider]'s browser flow. [openBrowser] receives the start URL;
     * [awaitRedirect] returns the redirect URI, or null when the person came back without signing in.
     * Neither the verifier, the state, the code nor the redirect leave this function.
     */
    suspend fun signInWithProvider(
        discovered: DiscoveryResult.Discovered,
        provider: AuthProvider,
        openBrowser: (String) -> Boolean,
        probeAdmin: Boolean = true,
        awaitRedirect: suspend () -> String?
    ): AddServerResult {
        val nativeStartPath = provider.native_start_path
        if (provider.mode != "oauth" || nativeStartPath == null) {
            return AddServerResult.Rejected(AddServerFailure.NO_SUPPORTED_SIGN_IN)
        }
        val pkce = Pkce.generate()
        val appState = newAppState()
        val startUrl = try {
            nativeStartUrl(discovered.origin, nativeStartPath, pkce, appState)
        } catch (_: IllegalArgumentException) {
            return AddServerResult.Rejected(AddServerFailure.NO_SUPPORTED_SIGN_IN)
        }
        val pending = PendingOAuth(
            origin = discovered.origin,
            serverId = discovered.serverId,
            providerId = provider.id,
            providerName = provider.display_name,
            pkce = pkce,
            appState = appState,
            startedAtMillis = clock()
        )
        if (!openBrowser(startUrl)) return AddServerResult.Rejected(AddServerFailure.NO_BROWSER)
        val redirect = awaitRedirect() ?: return AddServerResult.Rejected(AddServerFailure.OAUTH_CANCELLED)
        val parsed = parseOAuthRedirect(redirect, pending)
        if (parsed is OAuthRedirect.Untrusted) return AddServerResult.Rejected(AddServerFailure.OAUTH_UNTRUSTED)
        if (clock() - pending.startedAtMillis > PENDING_OAUTH_LIFETIME_MILLIS) {
            return AddServerResult.Rejected(AddServerFailure.OAUTH_FAILED, "session_expired")
        }
        val code = when (parsed) {
            is OAuthRedirect.Code -> parsed.code
            is OAuthRedirect.Failed -> return AddServerResult.Rejected(AddServerFailure.OAUTH_FAILED, parsed.reason)
            OAuthRedirect.Untrusted -> return AddServerResult.Rejected(AddServerFailure.OAUTH_UNTRUSTED)
        }

        val client = clientFactory(discovered.origin, null)
        var stage = ConnectStage.OAUTH_COMPLETE
        try {
            val completion = completeOAuthLogin(client, code, pkce.verifier)
            if (probeAdmin) {
                client.replaceAuthenticatedPrincipal(completion.access_token)
                stage = ConnectStage.ADMIN_STATUS
                client.requireAdminAccess()
            }
            return connected(discovered, completion.access_token, completion.refresh_token, provider.id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiError) {
            return if (stage == ConnectStage.OAUTH_COMPLETE) {
                when {
                    error.isClientUpgradeRequired -> AddServerResult.Rejected(AddServerFailure.CLIENT_UPGRADE_REQUIRED)
                    // The server answered the redemption and refused it. 403 (not an admin) and 404
                    // (no such route) keep their own copy; any other 4xx is a failed sign-in, not
                    // an unreachable server.
                    error.statusCode in 400..499 &&
                        error.statusCode != HttpStatusCode.Forbidden.value &&
                        error.statusCode != HttpStatusCode.NotFound.value ->
                        AddServerResult.Rejected(AddServerFailure.OAUTH_FAILED, "login_failed")
                    else -> AddServerResult.Rejected(error.toFailure(stage), error.message)
                }
            } else {
                AddServerResult.Rejected(error.toFailure(stage), error.message)
            }
        } catch (error: Exception) {
            return AddServerResult.Rejected(AddServerFailure.UNREACHABLE, error.message)
        } finally {
            client.close()
        }
    }

    /**
     * Admits an account that is a platform administrator (the status route answers) or administers at least one
     * organization (the status route answers 403 and `GET /api/bloem/v2/organizations` lists a context). An account
     * with no admin context keeps the original 403, which the caller reports as ADMIN_REQUIRED.
     */
    private suspend fun ApiClient.requireAdminAccess() {
        try {
            getServerStatus()
        } catch (error: ApiError) {
            if (error.statusCode != HttpStatusCode.Forbidden.value) throw error
            if (discoverAdminContexts().isEmpty()) throw error
        }
    }

    private fun connected(
        discovered: DiscoveryResult.Discovered,
        accessToken: String,
        refreshToken: String,
        providerId: String?
    ) = AddServerResult.Connected(
        server = AdminServer(idFactory(), discovered.origin.value, discovered.displayName, providerId),
        accessToken = accessToken,
        refreshToken = refreshToken
    )

    private fun ApiError.toFailure(stage: ConnectStage): AddServerFailure = when (stage) {
        ConnectStage.HEALTH -> AddServerFailure.HEALTH_CHECK_FAILED
        ConnectStage.DISCOVERY -> when (statusCode) {
            HttpStatusCode.NotFound.value -> AddServerFailure.INCOMPATIBLE_SERVER
            else -> AddServerFailure.UNREACHABLE
        }
        ConnectStage.LOGIN, ConnectStage.OAUTH_COMPLETE -> when (statusCode) {
            HttpStatusCode.Unauthorized.value -> AddServerFailure.INVALID_CREDENTIALS
            HttpStatusCode.Forbidden.value -> AddServerFailure.ADMIN_REQUIRED
            HttpStatusCode.NotFound.value -> AddServerFailure.INCOMPATIBLE_SERVER
            else -> AddServerFailure.UNREACHABLE
        }
        ConnectStage.ADMIN_STATUS -> when (statusCode) {
            HttpStatusCode.Unauthorized.value -> AddServerFailure.INVALID_CREDENTIALS
            HttpStatusCode.Forbidden.value -> AddServerFailure.ADMIN_REQUIRED
            HttpStatusCode.NotFound.value -> AddServerFailure.INCOMPATIBLE_SERVER
            else -> AddServerFailure.INCOMPATIBLE_SERVER
        }
    }

    private enum class ConnectStage { HEALTH, DISCOVERY, LOGIN, OAUTH_COMPLETE, ADMIN_STATUS }
}
