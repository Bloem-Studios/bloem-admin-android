package com.bloemstudios.admin.links

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A link that has arrived and not been acted on yet. [offeredAt] is epoch milliseconds. */
data class DeepLinkRequest(val target: DeepLinkTarget, val offeredAt: Long)

/**
 * Holds the one link waiting to be opened. The activity offers it the moment it arrives; the app shell acts on it only
 * once it exists, which is after the app lock and sign-in screens, so no link ever skips them. A link nobody opened
 * within [MAX_AGE_MILLIS] (the lock was never passed) is dropped instead of surprising the admin later.
 */
object DeepLinkInbox {
    const val MAX_AGE_MILLIS = 10L * 60 * 1000

    private val _pending = MutableStateFlow<DeepLinkRequest?>(null)
    val pending: StateFlow<DeepLinkRequest?> = _pending

    fun offer(target: DeepLinkTarget, nowMillis: Long = System.currentTimeMillis()) {
        _pending.value = DeepLinkRequest(target, nowMillis)
    }

    /** Marks [request] as handled. A newer link that arrived meanwhile stays. */
    fun consume(request: DeepLinkRequest) {
        _pending.compareAndSet(request, null)
    }

    fun isFresh(request: DeepLinkRequest, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - request.offeredAt <= MAX_AGE_MILLIS

    internal fun reset() {
        _pending.value = null
    }
}

/** The item a link named on a page, waiting for that page to open it. [offeredAt] is epoch milliseconds. */
data class DeepLinkItemRequest(val destinationId: String, val itemId: String, val offeredAt: Long)

/**
 * Holds the one item waiting to be opened. [DeepLinkPageEffect] offers it when it selects the link's page; the page
 * (Requests, Now playing) takes it with [take] once its rows are loaded. An item nobody took within
 * [DeepLinkInbox.MAX_AGE_MILLIS] is dropped.
 */
object DeepLinkItems {
    private val _pending = MutableStateFlow<DeepLinkItemRequest?>(null)
    val pending: StateFlow<DeepLinkItemRequest?> = _pending

    fun offer(destinationId: String, itemId: String, nowMillis: Long = System.currentTimeMillis()) {
        _pending.value = DeepLinkItemRequest(destinationId, itemId, nowMillis)
    }

    /** The waiting item id for [destinationId], removed so it opens once. Null when there is none, it is for another page, or it is stale. */
    fun take(destinationId: String, nowMillis: Long = System.currentTimeMillis()): String? {
        val request = _pending.value ?: return null
        if (request.destinationId != destinationId) return null
        _pending.compareAndSet(request, null)
        return request.itemId.takeIf { nowMillis - request.offeredAt <= DeepLinkInbox.MAX_AGE_MILLIS }
    }

    internal fun reset() {
        _pending.value = null
    }
}
