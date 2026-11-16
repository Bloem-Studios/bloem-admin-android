package com.bloemstudios.admin.requests

import com.bloemstudios.admin.network.ErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** What a bulk run does to every selected request: the web's `BulkRequestAction`. */
enum class BulkRequestAction(val queueAction: RequestQueueAction) {
    Approve(RequestQueueAction.Approve),
    Decline(RequestQueueAction.Decline)
}

/** Requests a bulk action sends at once; the rest wait for a free slot (the web's `BULK_REQUEST_CONCURRENCY`). */
const val BULK_REQUEST_CONCURRENCY = 4

/** One selected request. [key] is the request's ID, or `serverId:requestId` in All servers. */
data class BulkTarget(val key: String, val request: MediaRequest)

/** A request the server refused, with its reason. */
data class BulkFailure(val key: String, val title: String, val message: ErrorText)

/** How far a bulk run is: every answer counts, failed ones included. */
data class BulkProgress(
    val action: BulkRequestAction,
    val total: Int,
    val done: Int = 0,
    val failures: List<BulkFailure> = emptyList(),
) {
    val finished: Boolean get() = done >= total
}

/**
 * Approves or declines [targets] through the per-request endpoints, [concurrency] at a time. [send] answers null when
 * the server accepted the request, else the reason it refused. One refusal does not stop the rest; the result keeps
 * every failure with its reason. [onProgress] hears the start and every answer.
 */
suspend fun runBulkRequests(
    action: BulkRequestAction,
    targets: List<BulkTarget>,
    concurrency: Int = BULK_REQUEST_CONCURRENCY,
    onProgress: (BulkProgress) -> Unit,
    send: suspend (BulkTarget) -> ErrorText?,
): BulkProgress {
    var progress = BulkProgress(action, targets.size)
    onProgress(progress)
    if (targets.isEmpty()) return progress
    val next = AtomicInteger()
    val lock = Mutex()
    coroutineScope {
        repeat(minOf(concurrency, targets.size)) {
            launch {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= targets.size) break
                    val target = targets[index]
                    val refusal = try {
                        send(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ErrorText.Raw(e.message.orEmpty())
                    }
                    lock.withLock {
                        progress = progress.copy(
                            done = progress.done + 1,
                            failures = if (refusal == null) progress.failures
                            else progress.failures + BulkFailure(target.key, target.request.title, refusal),
                        )
                        onProgress(progress)
                    }
                }
            }
        }
    }
    return progress
}
