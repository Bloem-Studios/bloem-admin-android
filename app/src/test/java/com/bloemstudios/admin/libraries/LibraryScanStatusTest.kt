package com.bloemstudios.admin.libraries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScanStatusTest {
    private fun run(
        id: String,
        status: String,
        library: String = "1",
        requested: String? = "2026-11-14T09:00:00.000Z",
        started: String? = null,
        completed: String? = null,
        result: ScanResult? = null,
        error: String? = null,
    ) = ScanRun(id, library, status = status, requested_at = requested, started_at = started, completed_at = completed, result = result, error_message = error)

    @Test
    fun `a library with no runs and no request has no scan status`() {
        assertNull(libraryScanStatus("1", emptyList(), pending = null, nowMillis = 0))
    }

    @Test
    fun `an accepted run is queued and a running run is running`() {
        assertEquals(ScanState.Queued, libraryScanStatus("1", listOf(run("a", "accepted")), null, 0)?.state)
        assertEquals(ScanState.Running, libraryScanStatus("1", listOf(run("a", "running", started = "2026-11-14T09:00:01.000Z")), null, 0)?.state)
    }

    @Test
    fun `a running run wins over a queued one and over older finished ones`() {
        val runs = listOf(
            run("old", "completed", completed = "2026-11-14T08:00:00.000Z"),
            run("q", "accepted"),
            run("r", "running", started = "2026-11-14T09:00:01.000Z"),
        )
        assertEquals("r", libraryScanStatus("1", runs, null, 0)?.run?.id)
        assertEquals(ScanState.Running, libraryScanStatus("1", runs, null, 0)?.state)
    }

    @Test
    fun `finished runs map to completed failed cancelled and skipped`() {
        fun state(r: ScanRun) = libraryScanStatus("1", listOf(r), null, 0)?.state
        assertEquals(ScanState.Completed, state(run("a", "completed", result = ScanResult(new = 3))))
        assertEquals(ScanState.Failed, state(run("b", "failed", error = "root unreachable")))
        assertEquals(ScanState.Cancelled, state(run("c", "cancelled")))
        // The server says a scan of the same scope was already running and this one did nothing.
        assertEquals(ScanState.Skipped, state(run("d", "completed", result = ScanResult(skipped = 1))))
    }

    @Test
    fun `the newest finished run is the one shown`() {
        val runs = listOf(
            run("older", "failed", completed = "2026-11-14T07:00:00.000Z", error = "x"),
            run("newer", "completed", completed = "2026-11-14T09:30:00.000Z", result = ScanResult()),
        )
        assertEquals("newer", libraryScanStatus("1", runs, null, 0)?.run?.id)
    }

    @Test
    fun `other libraries runs are ignored`() {
        val runs = listOf(run("a", "running", library = "2"), run("b", "failed", library = "3", error = "x"))
        assertNull(libraryScanStatus("1", runs, null, 0))
    }

    @Test
    fun `a request not yet seen in the runs shows queued until a new run appears`() {
        val pending = PendingScan(knownRunIds = setOf("old"), requestedAtMillis = 1_000)
        val before = listOf(run("old", "completed", completed = "2026-11-14T08:00:00.000Z", result = ScanResult()))

        assertEquals(ScanState.Queued, libraryScanStatus("1", before, pending, nowMillis = 5_000)?.state)

        val after = before + run("new", "running", started = "2026-11-14T09:00:01.000Z")
        assertEquals(ScanState.Running, libraryScanStatus("1", after, pending, nowMillis = 5_000)?.state)
        assertTrue(isPendingResolved(pending, "1", after, nowMillis = 5_000))
    }

    @Test
    fun `a request resolves when its run already finished before the next read`() {
        val pending = PendingScan(setOf("old"), 1_000)
        val runs = listOf(run("fast", "completed", completed = "2026-11-14T09:00:02.000Z", result = ScanResult(new = 1)))
        assertEquals(ScanState.Completed, libraryScanStatus("1", runs, pending, 5_000)?.state)
        assertTrue(isPendingResolved(pending, "1", runs, 5_000))
    }

    @Test
    fun `a request that never shows up stops claiming queued after the timeout`() {
        val pending = PendingScan(emptySet(), requestedAtMillis = 0)
        assertEquals(ScanState.Queued, libraryScanStatus("1", emptyList(), pending, nowMillis = PENDING_SCAN_TIMEOUT_MILLIS - 1)?.state)
        assertNull(libraryScanStatus("1", emptyList(), pending, nowMillis = PENDING_SCAN_TIMEOUT_MILLIS))
        assertTrue(isPendingResolved(pending, "1", emptyList(), nowMillis = PENDING_SCAN_TIMEOUT_MILLIS))
    }

    @Test
    fun `merging keeps one copy of each run, prefers the active copy and sorts newest first`() {
        val activeCopy = run("a", "running", started = "2026-11-14T09:00:05.000Z")
        val staleCopy = run("a", "accepted")
        val older = run("b", "completed", completed = "2026-11-14T08:00:00.000Z", result = ScanResult())
        val merged = mergeScanRuns(active = listOf(activeCopy), recent = listOf(older, staleCopy))
        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals("running", merged.first().status)
    }

    @Test
    fun `duration is the time between start and completion`() {
        val r = run("a", "completed", started = "2026-11-14T09:00:00.000Z", completed = "2026-11-14T10:03:04.900Z", result = ScanResult())
        assertEquals(ScanDuration(hours = 1, minutes = 3, seconds = 4), scanDuration(r))
        assertNull(scanDuration(run("b", "running", started = "2026-11-14T09:00:00.000Z")))
        assertNull(scanDuration(run("c", "completed", started = "garbage", completed = "2026-11-14T10:00:00.000Z")))
    }

    @Test
    fun `an unfinished or unknown status has no scan status`() {
        assertNull(libraryScanStatus("1", listOf(run("a", "something-new")), null, 0))
    }

    @Test
    fun `scanning is in progress while any library has a queued or running run or a fresh request`() {
        assertTrue(scanIsActive(listOf(run("a", "accepted")), emptyMap(), 0))
        assertTrue(scanIsActive(emptyList(), mapOf("1" to PendingScan(emptySet(), 0)), nowMillis = 10))
        assertEquals(false, scanIsActive(listOf(run("a", "completed", result = ScanResult())), emptyMap(), 0))
    }
}
