package com.bloemstudios.admin.requests

import com.bloemstudios.admin.network.ErrorText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Bulk approve and decline: the web's `useBulkRequestAction` rules, four requests at a time. */
class RequestBulkTest {
    private fun target(id: String, server: String? = null) = BulkTarget(
        key = if (server == null) id else "$server:$id",
        request = MediaRequest(id = id, provider = "radarr", media_type = "movie", title = "Title $id", status = "pending", outcome = "active"),
    )

    @Test
    fun `sends at most four at a time and answers every request`() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val targets = (1..12).map { target(it.toString()) }

        val result = runBulkRequests(BulkRequestAction.Approve, targets, onProgress = {}) {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(10)
            running.decrementAndGet()
            null
        }

        assertEquals(4, peak.get())
        assertEquals(12, result.total)
        assertEquals(12, result.done)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `one refusal does not stop the rest and keeps its reason`() = runTest {
        val sent = mutableListOf<String>()
        val targets = (1..6).map { target(it.toString()) }

        val result = runBulkRequests(BulkRequestAction.Decline, targets, onProgress = {}) { t ->
            sent += t.request.id
            if (t.request.id == "3") ErrorText.Raw("Already approved") else null
        }

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), sent.sorted())
        assertEquals(6, result.done)
        assertEquals(listOf("3"), result.failures.map { it.key })
        assertEquals("Title 3", result.failures.single().title)
        assertEquals(ErrorText.Raw("Already approved"), result.failures.single().message)
    }

    @Test
    fun `progress counts failed answers too and ends at the total`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        val targets = (1..3).map { target(it.toString()) }

        runBulkRequests(BulkRequestAction.Approve, targets, concurrency = 1, onProgress = { seen += it.done to it.failures.size }) { t ->
            if (t.request.id == "2") ErrorText.Raw("No") else null
        }

        assertEquals(listOf(0 to 0, 1 to 0, 2 to 1, 3 to 1), seen)
    }

    @Test
    fun `an empty selection sends nothing`() = runTest {
        var calls = 0
        val result = runBulkRequests(BulkRequestAction.Approve, emptyList(), onProgress = {}) { calls++; null }
        assertEquals(0, calls)
        assertEquals(0, result.total)
    }

    @Test
    fun `cancelling stops the run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var sent = 0
        val job = launch {
            runBulkRequests(BulkRequestAction.Approve, (1..8).map { target(it.toString()) }, onProgress = {}) {
                sent++
                gate.await()
                null
            }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(4, sent)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `only requests that still wait for approval can be selected`() {
        val waiting = target("1").request
        val approved = waiting.copy(id = "2", status = "approved")
        val failed = waiting.copy(id = "3", outcome = "failed")
        assertTrue(RequestQueue.canSelect(waiting))
        assertEquals(false, RequestQueue.canSelect(approved))
        assertEquals(false, RequestQueue.canSelect(failed))
    }
}
