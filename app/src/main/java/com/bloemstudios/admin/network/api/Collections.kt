package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import kotlinx.serialization.Serializable

@Serializable
data class PageInfo(val has_more: Boolean = false, val next_cursor: String? = null)

/** The v2 list envelope. A missing [page] means the whole collection came back in one response. */
@Serializable
data class ApiCollection<T>(val items: List<T> = emptyList(), val page: PageInfo? = null)

suspend inline fun <reified T> ApiClient.getCollection(
    path: String,
    params: Map<String, String?> = emptyMap()
): ApiCollection<T> = get(path, params)

/**
 * Follows `next_cursor` until the server reports no more pages. Stops early when a cursor
 * repeats (a server that ignores the cursor would otherwise loop forever) or after [maxPages].
 */
suspend inline fun <reified T> ApiClient.readAllPages(
    path: String,
    params: Map<String, String?> = emptyMap(),
    pageSize: Int = 200,
    maxPages: Int = 50
): List<T> {
    val all = mutableListOf<T>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    var pages = 0
    while (pages < maxPages) {
        val page = getCollection<T>(path, params + mapOf("cursor" to cursor, "limit" to pageSize.toString()))
        pages++
        all += page.items
        val next = page.page?.takeIf { it.has_more }?.next_cursor
        if (next == null || !seen.add(next)) break
        cursor = next
    }
    return all
}
