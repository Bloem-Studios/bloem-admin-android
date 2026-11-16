package com.bloemstudios.admin.apikeys

import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getAccountMe
import com.bloemstudios.admin.network.api.getApiKeyCapabilities
import com.bloemstudios.admin.network.api.getApiKeyWithValidator
import com.bloemstudios.admin.network.api.listApiKeys
import com.bloemstudios.admin.network.api.listUsers
import com.bloemstudios.admin.network.api.revokeApiKey
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.users.AdminUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

sealed interface ApiKeysAvailability {
    data object Checking : ApiKeysAvailability
    data object Available : ApiKeysAvailability

    /** The server has no key administration, or an older server has no such route. */
    data object Unavailable : ApiKeysAvailability
    data class Failed(val message: ErrorText) : ApiKeysAvailability
}

data class ApiKeysState(
    val keys: List<AdminApiKey> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    /** A failed next page: the keys already shown stay. */
    val moreFailed: Boolean = false,
    val listError: ErrorText? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}

sealed interface RevokeNotice {
    data class Revoked(val label: String) : RevokeNotice
    data class AlreadyGone(val label: String) : RevokeNotice
}

/** Who is looking: their ID and whether they own the server, and every account, for the owner rules. */
data class KeyAccounts(val accounts: List<AdminUser>, val viewerId: String, val viewerIsOwner: Boolean)

/**
 * The API keys page: the list a page at a time, and Revoke. Creating keys and changing rate limits stay on the web.
 * Revoke reads the key's version first and deletes with `If-Match`, so a key someone else changed since is refused
 * rather than revoked from a stale view.
 */
class ApiKeysViewModel {
    private val _availability = MutableStateFlow<ApiKeysAvailability>(ApiKeysAvailability.Checking)
    val availability: StateFlow<ApiKeysAvailability> = _availability
    private val _state = MutableStateFlow(ApiKeysState())
    val state: StateFlow<ApiKeysState> = _state
    private val _accounts = MutableStateFlow<KeyAccounts?>(null)
    val accounts: StateFlow<KeyAccounts?> = _accounts
    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** IDs of keys with a revoke under way. */
    val busy: StateFlow<Set<String>> = _busy
    private val _error = MutableStateFlow<ErrorText?>(null)
    val error: StateFlow<ErrorText?> = _error
    private val _notice = MutableStateFlow<RevokeNotice?>(null)
    val notice: StateFlow<RevokeNotice?> = _notice

    fun dismissMessages() {
        _error.value = null
        _notice.value = null
    }

    /** Asks whether the server offers key administration, then reads the first page. */
    suspend fun load(client: ApiClient) {
        try {
            _availability.value = if (client.getApiKeyCapabilities().available) ApiKeysAvailability.Available else ApiKeysAvailability.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _availability.value =
                if (e.statusCode == 404) ApiKeysAvailability.Unavailable else ApiKeysAvailability.Failed(e.toErrorText(R.string.api_keys_load_failed))
            return
        } catch (e: Exception) {
            _availability.value = ApiKeysAvailability.Failed(ErrorText.Res(R.string.api_keys_load_failed))
            return
        }
        if (_availability.value == ApiKeysAvailability.Available) readFirstPage(client)
    }

    /** Reads the first page again. A failure keeps the keys already shown. */
    suspend fun reload(client: ApiClient) {
        if (_availability.value == ApiKeysAvailability.Available) readFirstPage(client)
    }

    private suspend fun readFirstPage(client: ApiClient) {
        try {
            val page = client.listApiKeys(null)
            _state.value = ApiKeysState(
                keys = page.items.distinctBy { it.id }, nextCursor = nextOf(page.page), loaded = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = if (e is ApiError) e.toErrorText(R.string.api_keys_load_failed) else ErrorText.Res(R.string.api_keys_load_failed)
            _state.update { it.copy(loaded = true, listError = text) }
        }
    }

    suspend fun loadMore(client: ApiClient) {
        val before = _state.value
        val cursor = before.nextCursor ?: return
        if (before.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        try {
            val page = client.listApiKeys(cursor)
            _state.update {
                val known = it.keys.mapTo(HashSet()) { key -> key.id }
                it.copy(keys = it.keys + page.items.filter { key -> key.id !in known }, nextCursor = nextOf(page.page), moreFailed = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(moreFailed = true) }
        } finally {
            _state.update { it.copy(loadingMore = false) }
        }
    }

    private fun nextOf(page: com.bloemstudios.admin.network.api.PageInfo?): String? =
        page?.takeIf { it.has_more }?.next_cursor?.takeIf { it.isNotEmpty() }

    /** Reads the accounts and who is looking, for the rule on who may revoke what. Without them nobody is offered Revoke. */
    suspend fun loadAccounts(client: ApiClient) {
        try {
            val me = client.getAccountMe()
            val users = client.listUsers()
            _accounts.value = KeyAccounts(users, me.id, users.any { it.id == me.id && it.is_owner })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Without the account list no key can be revoked from here; the next open reads it again.
        }
    }

    /** Revokes [key]. Returns false when the server refused or the key was already gone. */
    suspend fun revoke(client: ApiClient, key: AdminApiKey): Boolean {
        if (key.id in _busy.value) return false
        _busy.update { it + key.id }
        _error.value = null
        _notice.value = null
        try {
            val validator = client.getApiKeyWithValidator(key.id).second
            if (validator == null) {
                _error.value = ErrorText.Res(R.string.api_keys_revoke_no_version)
                return false
            }
            client.revokeApiKey(key.id, validator)
            _state.update { it.copy(keys = it.keys.filterNot { k -> k.id == key.id }) }
            _notice.value = RevokeNotice.Revoked(key.label)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            when (e.statusCode) {
                404 -> {
                    _state.update { it.copy(keys = it.keys.filterNot { k -> k.id == key.id }) }
                    _notice.value = RevokeNotice.AlreadyGone(key.label)
                }
                412, 428 -> {
                    _error.value = e.toErrorText(R.string.api_keys_revoke_failed)
                    readFirstPage(client)
                }
                else -> _error.value = e.toErrorText(R.string.api_keys_revoke_failed)
            }
            return false
        } catch (_: Exception) {
            _error.value = ErrorText.Res(R.string.api_keys_revoke_failed)
            return false
        } finally {
            _busy.update { it - key.id }
        }
    }
}
