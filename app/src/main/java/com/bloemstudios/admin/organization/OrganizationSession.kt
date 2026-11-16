package com.bloemstudios.admin.organization

import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.auth.RecoveryResult
import com.bloemstudios.admin.auth.UnauthorizedRecovery
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ServerOrigin
import com.bloemstudios.admin.network.api.ORGANIZATION_LIVE_TOKEN
import com.bloemstudios.admin.network.api.endSupportAccess
import com.bloemstudios.admin.network.api.getSupportCapabilities
import com.bloemstudios.admin.network.api.mintAdminSession
import com.bloemstudios.admin.platform.bloem
import com.bloemstudios.admin.realtime.LiveConnection
import com.bloemstudios.admin.realtime.LiveEnvironments
import com.bloemstudios.admin.realtime.OrganizationLiveTransport
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why the server turned an administrator away from an organization. */
enum class OrganizationDenial {
    /** `403 organization_suspended`: the organization itself is suspended. */
    Suspended,

    /** A 403 for the account: the role is no longer `admin`, the membership ended, or platform authority was lost. */
    LostAuthority,

    /** `404`: the server does not show this organization to the account at all. */
    NotFound,

    /** The Owner's support access ended (closed, or past its four hours): the pass can no longer be renewed. */
    SupportEnded,
}

/** The server says this account may not administer this organization (403), or does not show it (404). */
class OrganizationAccessDenied(
    cause: Throwable? = null,
    val reason: OrganizationDenial = (cause as? ApiError).denialReason(),
) : Exception("No access to this organization", cause)

/** The one reading of a refused request: [ApiError.code] decides suspended, the status decides the rest. */
internal fun ApiError?.denialReason(): OrganizationDenial = when {
    this == null -> OrganizationDenial.LostAuthority
    code == "organization_suspended" -> OrganizationDenial.Suspended
    statusCode == 404 -> OrganizationDenial.NotFound
    else -> OrganizationDenial.LostAuthority
}

/**
 * A write found the organization changed since it was loaded (stale pass, or a revision conflict).
 * The pass has been renewed; the write was NOT applied and must not be replayed. Reload, then ask again.
 */
class OrganizationStateChanged(
    /** True for a `409 authorization_state_changed` (what was quoted is out of date); false for a stale pass (401). */
    val wasConflict: Boolean = false,
) : Exception("The organization changed. Reload and try again.")

/**
 * What the server Owner stated to open one organization he is not a member of (break-glass support access):
 * the [reason], and, once the server has opened the access, its id, so a renewal or a recreated screen continues
 * the same access instead of opening a second. Held in memory only, for one server and organization.
 */
class SupportAccessGrant(val reason: String) {
    @Volatile var accessId: String? = null

    /** When the server ends the access (at most four hours after opening); null until it has opened. */
    @Volatile var expiresAt: Instant? = null
}

/** The grants of this run of the app, by server and organization. Never saved: a restart asks for a reason again. */
object SupportAccessGrants {
    private val grants = ConcurrentHashMap<String, SupportAccessGrant>()
    private fun key(serverId: String, organizationId: String) = "$serverId:$organizationId"

    /** Starts a new grant, replacing any earlier one: every opening states its reason. */
    fun start(serverId: String, organizationId: String, reason: String): SupportAccessGrant =
        SupportAccessGrant(reason).also { grants[key(serverId, organizationId)] = it }

    /** The grant still in force for this organization, or null (none, or its access has run out). */
    fun current(serverId: String, organizationId: String, now: Instant = Instant.now()): SupportAccessGrant? {
        val grant = grants[key(serverId, organizationId)] ?: return null
        val end = grant.expiresAt
        if (end != null && !now.isBefore(end)) {
            grants.remove(key(serverId, organizationId), grant)
            return null
        }
        return grant
    }

    fun forget(grant: SupportAccessGrant) {
        grants.entries.removeIf { it.value === grant }
    }
}

/**
 * One administrator's hold on one organization: exchanges the account's login session for a short-lived
 * context pass (POST /api/bloem/v2/admin/session), keeps the pass in memory only, and exposes an
 * [ApiClient] whose bearer is the pass. Screens get [client]; nothing else ever sees the account token
 * or the pass.
 *
 * - Reads that come back 401 (`tenant_session_required`, `authorization_state_stale`, or an expired pass)
 *   re-mint the pass once through the client's own recovery hook and retry, sharing one mint between
 *   parallel requests.
 * - Writes go through [write]: they are never replayed. A stale pass or revision conflict renews the pass
 *   and throws [OrganizationStateChanged] so the caller reloads. A pass close to expiry is renewed first.
 * - The account client keeps its own refresh and re-login handling: a 401 on the mint call reaches it,
 *   so an expired sign-in still lands on today's re-login form.
 */
class OrganizationSession(
    private val account: ApiClient,
    val organizationId: String,
    private val clock: () -> Instant = Instant::now,
    private val clientFactory: (ServerOrigin, AccessTokenSnapshot, UnauthorizedRecovery) -> ApiClient =
        { origin, snapshot, recovery -> ApiClient(origin, snapshot, unauthorizedRecovery = recovery) },
    /** Set when the server Owner opens an organization he is not a member of; the pass then comes from support access. */
    private val support: SupportAccessGrant? = null,
) : Closeable {
    private val lock = Mutex()
    private val contextState = MutableStateFlow<AdminContext?>(null)
    private var passClient: ApiClient? = null
    private var generation = 0L
    private var expiresAt: Instant = Instant.EPOCH

    /** The organization's context as the server last described it; null before [open]. */
    val context: StateFlow<AdminContext?> = contextState.asStateFlow()

    private val denialState = MutableStateFlow<OrganizationDenial?>(null)

    /** Why the server refused this account the organization (403, 404, or a pass for another one); null while it has not. */
    val denial: StateFlow<OrganizationDenial?> = denialState.asStateFlow()

    private val deniedState = MutableStateFlow(false)

    /** True once the server has refused this account the organization. */
    val accessDenied: StateFlow<Boolean> = deniedState.asStateFlow()

    /** The first reason sticks: a suspended organization stays "suspended" even if a later call answers 403 plainly. */
    internal fun markDenied(reason: OrganizationDenial = OrganizationDenial.LostAuthority) {
        if (denialState.compareAndSet(null, reason)) deniedState.value = true
    }

    private val capabilitiesState = MutableStateFlow(SupportCapabilities.NONE)

    /**
     * What this server can do for support. Everything false until the server has said so: an older server (no such
     * route) and a server that could not be asked both show nothing new. See [loadSupportCapabilities].
     */
    val supportCapabilities: StateFlow<SupportCapabilities> = capabilitiesState.asStateFlow()

    @Volatile private var capabilitiesKnown = false

    /**
     * Asks the server once what it can do. A 404 is a final "nothing"; a refusal of the organization is recorded like any
     * other; any other failure leaves "nothing" in place and asks again on the next call.
     */
    suspend fun loadSupportCapabilities() {
        if (capabilitiesKnown) return
        try {
            capabilitiesState.value = client.getSupportCapabilities()
            capabilitiesKnown = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiError) {
            when (error.statusCode) {
                404, 405, 501 -> capabilitiesKnown = true
                403 -> markDenied(error.denialReason())
            }
        } catch (_: Exception) {
            // Unknown for now: nothing new is shown, and the next load asks again.
        }
    }

    /**
     * Whether this server has the organization live socket: it advertises `organization_live_v1` in its public capabilities.
     * Any doubt (offline, an older server) is "no", and the pages keep what they did before. A final answer is kept by the
     * account client; a failure to ask is asked again next time.
     */
    suspend fun liveOffered(): Boolean = ORGANIZATION_LIVE_TOKEN in account.bloem.capabilities()?.feature_tokens.orEmpty()

    private var liveHold: Pair<ApiClient, LiveConnection>? = null

    /**
     * This organization's one live socket, shared by the pages that show a channel (see [watchOrganization]). It is bound to
     * the pass client, so it ends with the pass and renews the pass through the same recovery as any request.
     */
    val live: LiveConnection
        get() = synchronized(this) {
            val current = client
            liveHold?.takeIf { it.first === current }?.second
                ?: LiveConnection(current, OrganizationLiveTransport(current), CoroutineScope(Dispatchers.Default), LiveEnvironments.current)
                    .also { liveHold = current to it }
        }

    /** True while the pass was opened by support access rather than by a membership. */
    val isSupportAccess: Boolean get() = support != null && contextState.value?.isSupport == true

    /**
     * Ends the Owner's support access and, with it, this pass. Already ended counts as ended. A stale pass is renewed and
     * the call made once more, since ending must not be refused for a pass that merely went stale.
     */
    suspend fun endSupportAccess() {
        try {
            try {
                write { client, _ -> client.endSupportAccess() }
            } catch (_: OrganizationStateChanged) {
                write { client, _ -> client.endSupportAccess() }
            }
        } catch (error: ApiError) {
            if (error.statusCode != 404) throw error
        } catch (_: OrganizationAccessDenied) {
            // The server no longer finds the access when the pass is renewed: it is already over.
        }
        support?.let(SupportAccessGrants::forget)
    }

    /** The server's origin, for building links the server hands back as tokens. */
    val origin: ServerOrigin get() = account.origin

    /** The organization policy revision a write quotes. Zero before [open]. */
    val policyRevision: Long get() = contextState.value?.policyRevision ?: 0L

    /** Bearer is the context pass. Valid after [open]. */
    val client: ApiClient get() = checkNotNull(passClient) { "OrganizationSession.open() has not completed" }

    override fun toString(): String = "OrganizationSession(organizationId=$organizationId)"

    /** Mints the first pass. Throws [OrganizationAccessDenied] for 403/404, or the [ApiError] otherwise. */
    suspend fun open(): AdminContext = lock.withLock {
        val minted = mint()
        generation = 0L
        val snapshot = AccessTokenSnapshot(minted.token, generation)
        passClient?.close()
        passClient = clientFactory(account.origin, snapshot, UnauthorizedRecovery { rejected -> recover(rejected) })
        publish(minted)
    }

    /** Renews the pass, for example after a conflict. */
    suspend fun refresh(): AdminContext = lock.withLock { install(mint()) }

    /**
     * Runs one write with the current organization policy revision. See the class comment for the failure
     * rules. [block] receives the pass client and the revision to quote.
     */
    suspend fun <T> write(block: suspend (client: ApiClient, policyRevision: Long) -> T): T {
        if (!clock().isBefore(expiresAt.minus(EXPIRY_MARGIN))) refresh()
        try {
            return block(client, policyRevision)
        } catch (error: ApiError) {
            if (!error.isStaleState()) throw error
            refresh()
            throw OrganizationStateChanged(wasConflict = error.statusCode == 409)
        }
    }

    override fun close() {
        synchronized(this) { liveHold?.second?.close(); liveHold = null }
        passClient?.close()
        passClient = null
        contextState.value = null
    }

    private suspend fun recover(rejected: AccessTokenSnapshot): RecoveryResult = lock.withLock {
        val current = currentSnapshot()
        // Someone already replaced the rejected pass: use theirs rather than minting again.
        if (current.generation > rejected.generation) return@withLock RecoveryResult.Retry(current)
        try {
            install(mint())
            RecoveryResult.Retry(currentSnapshot())
        } catch (denied: OrganizationAccessDenied) {
            // A renewal the server refuses ends the hold on this organization for good: say so before the request fails.
            markDenied(denied.reason)
            RecoveryResult.Unavailable(denied)
        } catch (failure: ApiError) {
            RecoveryResult.Unavailable(failure)
        }
    }

    private fun currentSnapshot(): AccessTokenSnapshot = checkNotNull(passClient).authenticatedCredential.get().snapshot

    private class Minted(val token: String, val expiresAt: Instant, val context: AdminContext)

    private suspend fun mint(): Minted {
        val renewing = support?.accessId
        val request = AdminSessionRequest(
            scope = "organization", organization_id = organizationId,
            support = support?.let { grant -> if (renewing != null) AdminSessionSupport(access_id = renewing) else AdminSessionSupport(reason = grant.reason) },
        )
        val response = try {
            account.mintAdminSession(request)
        } catch (error: ApiError) {
            // A renewal the server no longer finds: the access was closed, or ran out.
            if (renewing != null && error.statusCode == 404) throw OrganizationAccessDenied(error, OrganizationDenial.SupportEnded)
            if (error.statusCode == 403 || error.statusCode == 404) throw OrganizationAccessDenied(error, error.denialReason())
            throw error
        }
        val described = response.context
        // Never accept a pass the server did not issue for exactly this organization.
        if (described.scope != "organization" || described.organization_id != organizationId) {
            throw OrganizationAccessDenied(reason = OrganizationDenial.LostAuthority)
        }
        val bySupport = described.access_basis == "support" && !described.support_access_id.isNullOrBlank()
        val supportEnds = if (bySupport) described.support_expires_at?.let { runCatching { Instant.parse(it) }.getOrNull() } else null
        // Support authority is the Owner's alone and only for a request that stated a reason: never take it unasked.
        if (bySupport && support == null) throw OrganizationAccessDenied(reason = OrganizationDenial.LostAuthority)
        val context = AdminContext(
            key = described.key, name = described.name, scope = AdminContextScope.Organization,
            organizationId = organizationId, status = described.status,
            authority = if (described.authority == "platform_admin") AdminAuthority.PlatformAdmin else AdminAuthority.OrganizationAdmin,
            membershipId = described.membership_id, policyRevision = described.policy_revision,
            securityRevision = described.security_revision,
            accessBasis = if (bySupport) AccessBasis.Support else AccessBasis.Membership,
            supportAccessId = if (bySupport) described.support_access_id else null,
            supportExpiresAt = supportEnds,
        )
        if (bySupport) support?.let { grant ->
            grant.accessId = described.support_access_id
            grant.expiresAt = supportEnds
        }
        val expiry = runCatching { Instant.parse(response.expires_at) }.getOrElse { clock().plus(FALLBACK_LIFETIME) }
        return Minted(response.access_token, expiry, context)
    }

    private fun install(minted: Minted): AdminContext {
        generation += 1
        checkNotNull(passClient).refreshAccessToken(AccessTokenSnapshot(minted.token, generation))
        return publish(minted)
    }

    private fun publish(minted: Minted): AdminContext {
        expiresAt = minted.expiresAt
        contextState.value = minted.context
        return minted.context
    }

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
        val FALLBACK_LIFETIME: Duration = Duration.ofMinutes(14)
    }
}

private fun ApiError.isStaleState(): Boolean =
    (statusCode == 401 && (code == "tenant_session_required" || code == "authorization_state_stale")) ||
        (statusCode == 409 && code == "authorization_state_changed")
