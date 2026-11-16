package com.bloemstudios.admin.organization

import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.auth.AccessTokenSnapshot
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.ORGANIZATION_SUPPORT_TOKEN
import com.bloemstudios.admin.network.api.getAccountMe
import com.bloemstudios.admin.platform.bloem
import com.bloemstudios.admin.network.api.getUserWithValidator
import com.bloemstudios.admin.network.api.listOrganizationMemberships
import com.bloemstudios.admin.network.api.listPlatformOrganizations
import com.bloemstudios.admin.network.api.mintAdminSession
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether this account may be offered the Owner's support access: the server advertises `organization_support_v1` and the
 * account is the server Owner. The server decides again on every request; this only keeps the page out of sight of anyone
 * who could not use it, and of an older server that has none of it. Any doubt is "no".
 */
suspend fun ApiClient.canOpenSupportAccess(): Boolean = try {
    if (ORGANIZATION_SUPPORT_TOKEN !in bloem.capabilities()?.feature_tokens.orEmpty()) false
    else getUserWithValidator(getAccountMe().id).first.is_owner
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** A reason that the page can say is wrong before anything is sent. */
enum class ReasonProblem { Short, Long, Control }

fun reasonProblem(raw: String): ReasonProblem? {
    val text = raw.trim()
    return when {
        text.any(Char::isISOControl) -> ReasonProblem.Control
        text.length < SupportReason.MIN_LENGTH -> ReasonProblem.Short
        text.length > SupportReason.MAX_LENGTH -> ReasonProblem.Long
        else -> null
    }
}

data class SupportOrganizationsState(
    val loading: Boolean = true,
    val organizations: List<OrganizationSummary> = emptyList(),
    val query: String = "",
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val error: ErrorText? = null,
    /** Organizations the account is a member of: they open from the context menu, not under support access. */
    val memberOrganizationIds: Set<String> = emptySet(),
)

/**
 * The server Owner's list of organizations to open for support. The list needs a short platform pass (a read, never kept):
 * it is exchanged for each page, used once and dropped. Nothing here opens an organization; the page asks for a reason first
 * and hands the grant to the organization route.
 */
class SupportOrganizationsViewModel(
    private val passClientFactory: (ApiClient, String) -> ApiClient = { account, token ->
        ApiClient(account.origin, AccessTokenSnapshot(token, 0L))
    },
) : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(SupportOrganizationsState())
    val state: StateFlow<SupportOrganizationsState> = _state

    private suspend fun <T> withPlatformPass(account: ApiClient, block: suspend (ApiClient) -> T): T {
        val minted = account.mintAdminSession(AdminSessionRequest(scope = "platform"))
        if (minted.context.scope != "platform") throw ApiError(403, "forbidden", "The server didn't open support access. Try again")
        val client = passClientFactory(account, minted.access_token)
        try {
            return block(client)
        } finally {
            // The pass client is this call's own; the account client is shared and never closed here.
            if (client !== account) client.close()
        }
    }

    /** Reads the first page for [query] (blank lists everything, by name). */
    suspend fun load(account: ApiClient, query: String = _state.value.query) {
        val request = gate.begin()
        _state.update { it.copy(query = query, loading = it.organizations.isEmpty(), error = null) }
        val result = read(account, query, cursor = null)
        val members = if (_state.value.memberOrganizationIds.isEmpty()) memberOrganizations(account) else _state.value.memberOrganizationIds
        if (!gate.isCurrent(request)) return
        _state.update {
            when (result) {
                is Read.Page -> it.copy(
                    loading = false, organizations = result.page.organizations, memberOrganizationIds = members,
                    nextCursor = result.page.next_cursor?.takeIf(String::isNotBlank), error = null,
                )
                is Read.Failed -> it.copy(loading = false, error = result.message)
            }
        }
    }

    suspend fun loadMore(account: ApiClient) {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        val request = gate.current()
        _state.update { it.copy(loadingMore = true) }
        val result = read(account, _state.value.query, cursor)
        if (!gate.isCurrent(request)) return
        _state.update {
            when (result) {
                is Read.Page -> it.copy(
                    loadingMore = false, organizations = it.organizations + result.page.organizations,
                    nextCursor = result.page.next_cursor?.takeIf(String::isNotBlank),
                )
                is Read.Failed -> it.copy(loadingMore = false, error = result.message)
            }
        }
    }

    /** The organizations the account belongs to; if they cannot be read, none is assumed and the server decides. */
    private suspend fun memberOrganizations(account: ApiClient): Set<String> = try {
        account.listOrganizationMemberships().map { it.id }.toSet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }

    private sealed interface Read {
        data class Page(val page: PlatformOrganizationsPage) : Read
        data class Failed(val message: ErrorText) : Read
    }

    private suspend fun read(account: ApiClient, query: String, cursor: String?): Read = try {
        Read.Page(withPlatformPass(account) { it.listPlatformOrganizations(query, cursor, PAGE_SIZE) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ApiError) {
        Read.Failed(error.toErrorText(R.string.organization_support_load_error))
    } catch (_: Exception) {
        Read.Failed(ErrorText.Res(R.string.organization_support_load_error))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        const val PAGE_SIZE = 50
    }
}
