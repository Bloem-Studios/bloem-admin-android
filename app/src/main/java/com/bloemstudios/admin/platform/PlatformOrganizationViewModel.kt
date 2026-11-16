package com.bloemstudios.admin.platform

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getPlatformOrganization
import com.bloemstudios.admin.network.api.listOrganizationMemberships
import com.bloemstudios.admin.network.api.listPlatformOrganizationMemberships
import com.bloemstudios.admin.network.api.reactivatePlatformOrganization
import com.bloemstudios.admin.network.api.suspendPlatformOrganization
import com.bloemstudios.admin.organization.OrganizationSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** How "Open as administrator" would enter an organization, or [None] when the account may not. */
enum class OpenAccess {
    None,

    /** The account is an administrator member of it: it opens like any organization in the context menu. */
    Membership,

    /** The server Owner is not a member: it opens under audited support access, after stating a reason. */
    Support,
}

/**
 * Whether the account may enter [organization] as administrator. Only an active organization opens. A member opens with its
 * membership, even when the account could also use support access; anyone else opens only through the Owner's support access,
 * which [supportAvailable] says this account has on this server. The server decides again when the pass is minted.
 */
fun openAccessFor(organization: OrganizationSummary, memberOrganizationIds: Set<String>, supportAvailable: Boolean): OpenAccess = when {
    organization.status != "active" -> OpenAccess.None
    organization.id in memberOrganizationIds -> OpenAccess.Membership
    supportAvailable -> OpenAccess.Support
    else -> OpenAccess.None
}

/** What the page tells the administrator after a change. The words are chosen where the text is shown. */
sealed interface PlatformNotice {
    data class Suspended(val organization: String) : PlatformNotice
    data class Reactivated(val organization: String) : PlatformNotice

    /** The organization moved on while the administrator was looking at it; it has been reloaded. */
    data object Changed : PlatformNotice
    data class PlanApplied(val organization: String, val plan: String) : PlatformNotice
    data class PlanUnchanged(val organization: String, val plan: String) : PlatformNotice
}

data class PlatformOrganizationState(
    val loading: Boolean = true,
    val organization: OrganizationSummary? = null,
    val notFound: Boolean = false,
    val members: List<PlatformMembership> = emptyList(),
    val membersNext: String? = null,
    val loadingMoreMembers: Boolean = false,
    val membersError: Boolean = false,
    /** The owner's username or email; null when there is no owner, or the owner is not among the members read. */
    val ownerName: String? = null,
    /** True once the owner has been looked for, so "name unavailable" is not shown while the page is still loading. */
    val ownerResolved: Boolean = false,
    val openAccess: OpenAccess = OpenAccess.None,
    /** A suspend or reactivate is on its way. */
    val busy: Boolean = false,
    val error: ErrorText? = null,
    val notice: PlatformNotice? = null,
)

/**
 * One organization on the Platform › Organizations page: its status, owner by name, counts and members, and the lifecycle
 * actions. Every call goes through the [PlatformSession]; a write is sent once, and when the server says the organization
 * moved on the page is reloaded and the administrator asks again.
 */
class PlatformOrganizationViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(PlatformOrganizationState())
    val state: StateFlow<PlatformOrganizationState> = _state

    private var account: ApiClient? = null
    private var organizationId: String = ""
    private var supportAvailable = false
    private var memberOrganizationIds: Set<String> = emptySet()

    /**
     * Reads the organization, its first page of members and the owner's name. [account] is the signed-in account's own client,
     * used only to learn which organizations it belongs to; [supportAvailable] says whether it may use the Owner's support access.
     */
    suspend fun load(session: PlatformSession, account: ApiClient, organizationId: String, supportAvailable: Boolean) {
        this.account = account
        this.organizationId = organizationId
        this.supportAvailable = supportAvailable
        val request = gate.begin()
        _state.update { it.copy(loading = it.organization == null, notFound = false, error = null) }
        val organization = try {
            Result.success(session.read { it.getPlatformOrganization(organizationId) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        if (!gate.isCurrent(request)) return
        organization.exceptionOrNull()?.let { failure ->
            _state.update {
                if (failure is ApiError && failure.statusCode == 404) it.copy(loading = false, notFound = true, organization = null)
                else it.copy(loading = false, error = failure.platformFailureText(R.string.platform_org_load_error))
            }
            return
        }
        val summary = organization.getOrThrow()

        val firstPage = try {
            Result.success(session.read { it.listPlatformOrganizationMemberships(organizationId, cursor = null, limit = MEMBERS_PAGE) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val page = firstPage.getOrNull()
        val owner = summary.owner_account_id?.let { ownerId -> findOwner(session, ownerId, page) }
        val memberOf = memberOrganizations(account)
        if (!gate.isCurrent(request)) return
        memberOrganizationIds = memberOf
        _state.update {
            it.copy(
                loading = false, organization = summary, notFound = false,
                members = page?.memberships ?: it.members, membersNext = if (page != null) page.next_cursor?.takeIf(String::isNotBlank) else it.membersNext,
                membersError = page == null,
                ownerName = owner, ownerResolved = true,
                openAccess = openAccessFor(summary, memberOf, supportAvailable),
            )
        }
    }

    /** Reads the next page of members and adds it to the list. */
    suspend fun loadMoreMembers(session: PlatformSession) {
        val cursor = _state.value.membersNext ?: return
        if (_state.value.loadingMoreMembers) return
        val id = organizationId
        _state.update { it.copy(loadingMoreMembers = true) }
        val result = try {
            Result.success(session.read { it.listPlatformOrganizationMemberships(id, cursor, MEMBERS_PAGE) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        _state.update { current ->
            result.fold(
                onSuccess = { page ->
                    current.copy(
                        loadingMoreMembers = false, members = current.members + page.memberships,
                        membersNext = page.next_cursor?.takeIf(String::isNotBlank), membersError = false,
                    )
                },
                onFailure = { current.copy(loadingMoreMembers = false, membersError = true) },
            )
        }
    }

    /**
     * Suspends ([suspended] true) or reactivates the organization, quoting the revision on screen. The request is sent once.
     * If the server says the organization changed meanwhile, the page is reloaded and the administrator asks again.
     */
    suspend fun setSuspended(session: PlatformSession, suspended: Boolean) {
        val before = _state.value.organization ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        try {
            val after = session.write { client ->
                if (suspended) client.suspendPlatformOrganization(before.id, before.policy_revision)
                else client.reactivatePlatformOrganization(before.id, before.policy_revision)
            }
            // The answer has no counts: keep the ones on screen.
            val updated = before.copy(status = after.status, policy_revision = after.policy_revision)
            _state.update {
                it.copy(
                    busy = false, organization = updated, openAccess = openAccessFor(updated, memberOrganizationIds, supportAvailable),
                    notice = if (suspended) PlatformNotice.Suspended(updated.name) else PlatformNotice.Reactivated(updated.name),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: PlatformStateChanged) {
            _state.update { it.copy(busy = false) }
            account?.let { load(session, it, organizationId, supportAvailable) }
            _state.update { it.copy(notice = PlatformNotice.Changed) }
        } catch (error: Exception) {
            _state.update {
                it.copy(busy = false, error = error.platformFailureText(if (suspended) R.string.platform_org_suspend_error else R.string.platform_org_reactivate_error))
            }
        }
    }

    /** Shows [notice] from another part of the page (the plan), in the same place. */
    fun announce(notice: PlatformNotice) = _state.update { it.copy(notice = notice, error = null) }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    /** The owner's name: from the page already read, or from the next pages, a few at most. The list on screen is not changed. */
    private suspend fun findOwner(session: PlatformSession, ownerId: Long, first: PlatformMembershipsPage?): String? {
        first?.memberships?.firstOrNull { it.account_id == ownerId }?.let { return it.label.ifBlank { null } }
        var cursor = first?.next_cursor?.takeIf(String::isNotBlank)
        var pages = 0
        while (cursor != null && pages < OWNER_SEARCH_PAGES) {
            val next: String = cursor
            val page = try {
                session.read { it.listPlatformOrganizationMemberships(organizationId, next, OWNER_PAGE) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
            page.memberships.firstOrNull { it.account_id == ownerId }?.let { return it.label.ifBlank { null } }
            cursor = page.next_cursor?.takeIf(String::isNotBlank)
            pages++
        }
        return null
    }

    /** The organizations the account belongs to; if they cannot be read, none is assumed and the server decides. */
    private suspend fun memberOrganizations(account: ApiClient): Set<String> = try {
        account.listOrganizationMemberships().map { it.id }.toSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }

    companion object {
        const val MEMBERS_PAGE = 100
        const val OWNER_PAGE = 200
        const val OWNER_SEARCH_PAGES = 5
    }
}
