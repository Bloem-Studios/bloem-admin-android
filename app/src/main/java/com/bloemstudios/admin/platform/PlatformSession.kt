package com.bloemstudios.admin.platform

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.mintAdminSession
import com.bloemstudios.admin.organization.AdminSessionRequest
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A write found the platform state changed since it was loaded (a stale pass, or a revision conflict). The pass has been
 * renewed; the write was NOT applied and must not be replayed. Reload, then ask again.
 */
class PlatformStateChanged(
    /** True for a `409 authorization_state_changed` (what was quoted is out of date); false for a stale pass (401). */
    val wasConflict: Boolean = false,
) : Exception("The organization changed. Reload and try again.")

/**
 * The administrator's hold on the platform: exchanges the account's login session for a short-lived platform pass
 * (POST /api/bloem/v2/admin/session with scope `platform`), keeps the pass in memory only, and serves the platform routes
 * with it. Screens get a client only inside [read] and [write]; nothing else ever sees the account token or the pass.
 *
 * It follows the rules of the organization session (organization/OrganizationSession.kt) without sharing its code, so the
 * platform routes stay out of the organization-mode code paths:
 * - A read that comes back 401 (`tenant_session_required`, `authorization_state_stale`, or an expired pass) renews the pass
 *   once through the client's own recovery hook and is retried, with parallel requests sharing one mint.
 * - A write goes through [write]: it is never replayed. A stale pass or a revision conflict renews the pass and throws
 *   [PlatformStateChanged] so the caller reloads. A pass close to its end is renewed first.
 * - The account client keeps its own refresh and re-login handling: a 401 on the mint call reaches it, so an expired
 *   sign-in still lands on the re-login form.
 */
class PlatformSession(
    private val account: ApiClient,
    private val clock: () -> Instant = Instant::now,
    private val clientFactory: (ServerOrigin, AccessTokenSnapshot, UnauthorizedRecovery) -> ApiClient =
        { origin, snapshot, recovery -> ApiClient(origin, snapshot, unauthorizedRecovery = recovery) },
) : Closeable {
    private val lock = Mutex()
    private var passClient: ApiClient? = null
    private var generation = 0L
    private var expiresAt: Instant = Instant.EPOCH

    override fun toString(): String = "PlatformSession"

    /** Runs one read with the pass client, opening the pass first when there is none. */
    suspend fun <T> read(block: suspend (ApiClient) -> T): T = block(opened())

    /** Runs one write with the pass client. See the class comment for the failure rules. */
    suspend fun <T> write(block: suspend (ApiClient) -> T): T {
        val client = opened(renewNearEnd = true)
        try {
            return block(client)
        } catch (error: ApiError) {
            if (!error.isStaleState()) throw error
            refresh()
            throw PlatformStateChanged(wasConflict = error.statusCode == 409)
        }
    }

    override fun close() {
        passClient?.close()
        passClient = null
        expiresAt = Instant.EPOCH
    }

    private suspend fun opened(renewNearEnd: Boolean = false): ApiClient = lock.withLock {
        val current = passClient
        if (current == null) {
            install(mint(), first = true)
        } else if (renewNearEnd && !clock().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            install(mint(), first = false)
        }
        checkNotNull(passClient)
    }

    private suspend fun refresh() = lock.withLock { if (passClient != null) install(mint(), first = false) }

    private suspend fun recover(rejected: AccessTokenSnapshot): RecoveryResult = lock.withLock {
        val client = passClient ?: return@withLock RecoveryResult.Unavailable(ApiError(401, "Sign in again"))
        val current = client.authenticatedCredential.get().snapshot
        // Someone already replaced the rejected pass: use theirs rather than minting again.
        if (current.generation > rejected.generation) return@withLock RecoveryResult.Retry(current)
        try {
            install(mint(), first = false)
            RecoveryResult.Retry(checkNotNull(passClient).authenticatedCredential.get().snapshot)
        } catch (failure: ApiError) {
            RecoveryResult.Unavailable(failure)
        }
    }

    private class Minted(val token: String, val expiresAt: Instant)

    private suspend fun mint(): Minted {
        val response = account.mintAdminSession(AdminSessionRequest(scope = "platform"))
        // Never accept a pass the server did not issue for the platform.
        if (response.context.scope != "platform") {
            throw ApiError(403, "forbidden", "The server did not open platform access. Sign in again, or ask the server owner")
        }
        val expiry = runCatching { Instant.parse(response.expires_at) }.getOrElse { clock().plus(FALLBACK_LIFETIME) }
        return Minted(response.access_token, expiry)
    }

    private fun install(minted: Minted, first: Boolean) {
        if (first || passClient == null) {
            generation = 0L
            passClient?.close()
            passClient = clientFactory(account.origin, AccessTokenSnapshot(minted.token, generation), UnauthorizedRecovery { rejected -> recover(rejected) })
        } else {
            generation += 1
            checkNotNull(passClient).refreshAccessToken(AccessTokenSnapshot(minted.token, generation))
        }
        expiresAt = minted.expiresAt
    }

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
        val FALLBACK_LIFETIME: Duration = Duration.ofMinutes(14)
    }
}

private fun ApiError.isStaleState(): Boolean =
    (statusCode == 401 && (code == "tenant_session_required" || code == "authorization_state_stale")) ||
        (statusCode == 409 && code == "authorization_state_changed")
