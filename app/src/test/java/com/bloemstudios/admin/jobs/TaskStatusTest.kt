package com.bloemstudios.admin.jobs

import com.bloemstudios.admin.auth.V2Fixtures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class TaskStatusTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    private fun task(
        state: String = "idle",
        progress: Double = 0.0,
        next: String? = null,
        last: TaskLastRun? = null,
        manualOnly: Boolean = false,
    ) = AdminTask(
        key = "scan_libraries", name = "Scan libraries", description = "d", category = "library", state = state,
        progress = progress, execution_scope = "process", manual_only = manualOnly, next_run_at = next, last_execution = last,
    )

    @Test fun `the list response decodes the last run, next run and progress`() {
        val raw = V2Fixtures.adminTask(
            state = "running",
            optional = """"progress":42.5,"next_run_at":"2026-11-15T02:00:00.000Z","last_execution":{"task_key":"scan","started_at":"2026-11-14T02:00:00.000Z","completed_at":"2026-11-14T02:04:10.000Z","status":"completed","duration_ms":250000}""",
        ).replace(""""progress":0.0,""", "")
        val decoded = json.decodeFromString<AdminTask>(raw)
        assertEquals(42.5, decoded.progress, 0.0)
        assertEquals("2026-11-15T02:00:00.000Z", decoded.next_run_at)
        assertEquals(250_000L, decoded.last_execution?.duration_ms)
        assertEquals("completed", decoded.last_execution?.status)
    }

    @Test fun `a task without a last run still decodes`() {
        val decoded = json.decodeFromString<AdminTask>(V2Fixtures.adminTask())
        assertNull(decoded.last_execution)
    }

    @Test fun `durations are put in the unit that reads best`() {
        assertEquals(DurationText.UnderOneMs, durationText(0))
        assertEquals(DurationText.Millis(850), durationText(850))
        assertEquals(DurationText.Seconds(42), durationText(42_900))
        assertEquals(DurationText.MinutesSeconds(2, 14), durationText(134_000))
        assertEquals(DurationText.HoursMinutes(1, 5), durationText(3_900_000))
        assertEquals(DurationText.HoursMinutes(26, 0), durationText(26 * 3_600_000L))
    }

    @Test fun `an elapsed span is rounded down to its largest unit`() {
        assertEquals(Elapsed.LessThanAMinute, elapsedOf(59))
        assertEquals(Elapsed.Minutes(1), elapsedOf(60))
        assertEquals(Elapsed.Minutes(59), elapsedOf(59 * 60 + 59))
        assertEquals(Elapsed.Hours(1), elapsedOf(3_600))
        assertEquals(Elapsed.Hours(23), elapsedOf(23 * 3_600 + 3_599))
        assertEquals(Elapsed.Days(1), elapsedOf(86_400))
        assertEquals(Elapsed.Days(3), elapsedOf(3 * 86_400 + 5))
    }

    @Test fun `a future next run counts down and a past one is overdue`() {
        assertEquals(NextRun.In(Elapsed.Hours(16)), nextRun(task(next = "2026-11-15T02:30:00Z"), now))
        assertEquals(NextRun.In(Elapsed.Minutes(30)), nextRun(task(next = "2026-11-14T10:30:00Z"), now))
        assertEquals(NextRun.Overdue, nextRun(task(next = "2026-11-14T09:59:59Z"), now))
        assertEquals(NextRun.In(Elapsed.LessThanAMinute), nextRun(task(next = "2026-11-14T10:00:00Z"), now))
    }

    @Test fun `no next run, a running task or an unreadable time shows nothing`() {
        assertNull(nextRun(task(next = null), now))
        assertNull(nextRun(task(state = "running", next = "2026-11-14T09:00:00Z"), now))
        assertNull(nextRun(task(state = "cancelling", next = "2026-11-14T09:00:00Z"), now))
        assertNull(nextRun(task(next = "soon"), now))
    }

    @Test fun `a last run is aged from when it finished`() {
        val last = TaskLastRun(status = "completed", started_at = "2026-11-14T02:00:00Z", completed_at = "2026-11-14T02:04:10Z", duration_ms = 250_000)
        assertEquals(Elapsed.Hours(7), lastRunAge(last, now))
        assertNull(lastRunAge(null, now))
        assertNull(lastRunAge(last.copy(completed_at = ""), now))
    }

    @Test fun `run is offered only while the task is idle`() {
        assertTrue(canRun(task("idle")))
        assertFalse(canRun(task("running")))
        assertFalse(canRun(task("cancelling")))
    }

    @Test fun `cancel is offered only while the task runs, and a cancelling task reads as stopping`() {
        assertTrue(canCancel(task("running")))
        assertFalse(canCancel(task("cancelling")))
        assertFalse(canCancel(task("idle")))
        assertTrue(isStopping(task("cancelling")))
        assertFalse(isStopping(task("running")))
    }

    @Test fun `progress is a percentage held between 0 and 100`() {
        assertEquals(0.425f, progressFraction(task("running", 42.5)), 0.0001f)
        assertEquals(1f, progressFraction(task("running", 180.0)), 0f)
        assertEquals(0f, progressFraction(task("running", -3.0)), 0f)
        assertEquals(0f, progressFraction(task("running", Double.NaN)), 0f)
    }

    @Test fun `progress text keeps a running task under 100`() {
        assertEquals("42%", progressPercentText(42.0, Locale.ENGLISH))
        assertEquals("42.5%", progressPercentText(42.5, Locale.ENGLISH))
        assertEquals("99.9%", progressPercentText(99.96, Locale.ENGLISH))
        assertEquals("100%", progressPercentText(100.0, Locale.ENGLISH))
    }

    @Test fun `failed steps are the steps that failed`() {
        val run = TaskLastRun(status = "failed", steps = listOf(TaskStep("a", "Vacuum", "completed"), TaskStep("b", "Reindex", "failed")))
        assertEquals(listOf("Reindex"), failedSteps(run.steps))
    }

    @Test fun `a task is a failure when its last run failed`() {
        assertTrue(lastRunFailed(TaskLastRun(status = "failed")))
        assertFalse(lastRunFailed(TaskLastRun(status = "completed")))
        assertFalse(lastRunFailed(TaskLastRun(status = "cancelled")))
        assertFalse(lastRunFailed(null))
    }

    @Test fun `tasks and jobs are followed on the tasks and jobs channels`() {
        assertEquals(setOf("tasks", "jobs"), TASK_CHANNELS)
    }
}
