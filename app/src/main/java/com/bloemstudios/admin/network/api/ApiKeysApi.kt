package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.apikeys.AdminApiKey
import com.bloemstudios.admin.apikeys.ApiKeyCapabilities
import com.bloemstudios.admin.network.ApiClient
import io.ktor.http.encodeURLPathPart

private fun apiKeyPath(id: String) = "/api/v2/admin/api-keys/${id.encodeURLPathPart()}"

/** Whether the server offers key administration at all. */
suspend fun ApiClient.getApiKeyCapabilities(): ApiKeyCapabilities = get("/api/v2/admin/api-keys/capabilities")

/** One page of keys, newest first. [cursor] is the previous page's `next_cursor`; null asks for the first page. */
suspend fun ApiClient.listApiKeys(cursor: String?, limit: Int = 50): ApiCollection<AdminApiKey> =
    getCollection("/api/v2/admin/api-keys", mapOf("cursor" to cursor, "limit" to limit.toString()))

/** The key and the ETag a revoke must quote; the validator is null when the server sent none. */
suspend fun ApiClient.getApiKeyWithValidator(id: String): Pair<AdminApiKey, String?> = getWithValidator(apiKeyPath(id))

/** Revokes the key for good. Rejected with 412 when [validator] no longer matches; never replayed after a 401. */
suspend fun ApiClient.revokeApiKey(id: String, validator: String) = deleteConditional(apiKeyPath(id), validator)
