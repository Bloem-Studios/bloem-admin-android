package com.bloemstudios.admin.platform

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.bloemstudios.admin.LatestRequestGate
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.listPlatformOrganizations
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.organization.OrganizationSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The one place a platform failure becomes text: a refusal of platform authority, a changed organization, or the screen's own words. */
internal fun Throwable.platformFailureText(@StringRes fallback: Int): ErrorText = when {
    this is PlatformStateChanged -> ErrorText.Res(R.string.platform_org_changed)
    this is ApiError && statusCode == 403 -> ErrorText.Res(R.string.platform_orgs_no_access)
    this is ApiError -> toErrorText(fallback)
    else -> ErrorText.Res(fallback)
}

data class PlatformOrganizationsState(
    val loading: Boolean = true,
    val organizations: List<OrganizationSummary> = emptyList(),
    val query: String = "",
    /** `active`, `suspended` or `initializing`; null is any status. */
    val status: String? = null,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val error: ErrorText? = null,
)

/**
 * The Platform › Organizations list: every organization on the server with its member, profile and library counts,
 * searched by name and filtered by status on the server, a page at a time. Reads go through the [PlatformSession], which
 * keeps the platform pass and renews it when the server says it is stale.
 */
class PlatformOrganizationsViewModel : ViewModel() {
    private val gate = LatestRequestGate()
    private val _state = MutableStateFlow(PlatformOrganizationsState())
    val state: StateFlow<PlatformOrganizationsState> = _state

    /** Reads the first page for [query] and [status]. Rows already on screen stay until the answer replaces them. */
    suspend fun load(session: PlatformSession, query: String = _state.value.query, status: String? = _state.value.status) {
        val request = gate.begin()
        val cleaned = query.trim()
        _state.update { it.copy(query = cleaned, status = status, loading = it.organizations.isEmpty(), loadingMore = false, error = null) }
        val result = try {
            Result.success(session.read { it.listPlatformOrganizations(cleaned, cursor = null, limit = PAGE_SIZE, status = status) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        if (!gate.isCurrent(request)) return
        _state.update { current ->
            result.fold(
                onSuccess = { page ->
                    current.copy(loading = false, organizations = page.organizations, nextCursor = page.next_cursor?.takeIf(String::isNotBlank), error = null)
                },
                onFailure = { current.copy(loading = false, error = it.platformFailureText(R.string.platform_orgs_load_error)) },
            )
        }
    }

    /** Appends the next page, if there is one. */
    suspend fun loadMore(session: PlatformSession) {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        val request = gate.current()
        _state.update { it.copy(loadingMore = true) }
        val result = try {
            Result.success(session.read { it.listPlatformOrganizations(current.query, cursor = cursor, limit = PAGE_SIZE, status = current.status) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        if (!gate.isCurrent(request)) return
        _state.update { state ->
            result.fold(
                onSuccess = { page ->
                    state.copy(loadingMore = false, organizations = state.organizations + page.organizations, nextCursor = page.next_cursor?.takeIf(String::isNotBlank))
                },
                onFailure = { state.copy(loadingMore = false, error = it.platformFailureText(R.string.platform_orgs_load_error)) },
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        const val PAGE_SIZE = 50
    }
}
