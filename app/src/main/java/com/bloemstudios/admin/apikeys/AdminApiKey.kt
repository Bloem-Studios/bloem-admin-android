package com.bloemstudios.admin.apikeys

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.api.getApiKeyCapabilities
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.detail.Viewer
import com.bloemstudios.admin.users.detail.userActions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * One key as `GET /api/v2/admin/api-keys` lists it (AdminAPIKeyListItem): who owns it, what it may reach and when it was
 * last used. The secret is never in a list; [key_prefix] is the start of it, enough to recognise the key.
 */
@Serializable
data class AdminApiKey(
    val id: String,
    val user_id: String,
    val label: String,
    val key_prefix: String,
    /** `standard` or `elevated`: which request rate limit the key's own counter uses. */
    val rate_tier: String = "standard",
    val scopes: List<String> = emptyList(),
    val created_at: String,
    val username: String = "",
    val last_used_at: String? = null,
) {
    /** An empty scope list is how the server stores a key with full access. */
    val hasFullAccess: Boolean get() = scopes.isEmpty()
}

/** `GET /api/v2/admin/api-keys/capabilities`: [available] is false where the server has no key administration. */
@Serializable
data class ApiKeyCapabilities(val available: Boolean = false)

/**
 * Whether the signed-in admin may revoke [key]: as on the web, only the server owner changes another admin's keys and
 * nobody touches the owner's. Until the account list is known, nobody is offered the action; a key whose account is not
 * in the list (a deleted account) can be revoked.
 */
internal fun canRevokeKey(key: AdminApiKey, accounts: List<AdminUser>?, viewerId: String?, viewerIsOwner: Boolean): Boolean {
    if (accounts == null || viewerId == null) return false
    val owner = accounts.firstOrNull { it.id == key.user_id } ?: return true
    return userActions(owner, Viewer(viewerId, viewerIsOwner)).manageable
}

/** Whether to list the API keys page: the server says it offers key administration. Any doubt is no. */
suspend fun ApiClient.canOpenApiKeys(): Boolean = try {
    getApiKeyCapabilities().available
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}
