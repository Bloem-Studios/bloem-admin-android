package com.bloemstudios.admin.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerDraftTest {
    @Test fun `an interval is shown in the largest whole unit`() {
        val hours = TaskTrigger("interval", interval_ms = 7_200_000).toDraft()
        assertEquals("2", hours.intervalValue); assertEquals(IntervalUnit.Hours, hours.intervalUnit)
        val seconds = TaskTrigger("interval", interval_ms = 90_000).toDraft()
        assertEquals("90", seconds.intervalValue); assertEquals(IntervalUnit.Seconds, seconds.intervalUnit)
        val whole = TaskTrigger("interval", interval_ms = 1_800_000).toDraft()
        assertEquals("30", whole.intervalValue); assertEquals(IntervalUnit.Minutes, whole.intervalUnit)
    }

    @Test fun `an interval draft goes back to milliseconds`() {
        val draft = TriggerDraft(type = "interval", intervalValue = "3", intervalUnit = IntervalUnit.Hours)
        assertEquals(TaskTrigger("interval", interval_ms = 10_800_000), draft.toTrigger())
    }

    @Test fun `a daily draft keeps its time and a weekly one its day`() {
        assertEquals(TaskTrigger("daily", time_of_day = "02:00"), TriggerDraft(type = "daily", time = "02:00").toTrigger())
        assertEquals(
            TaskTrigger("weekly", time_of_day = "04:30", day_of_week = 0),
            TriggerDraft(type = "weekly", time = "04:30", day = 0).toTrigger(),
        )
        assertEquals(TaskTrigger("weekly", time_of_day = "04:30", day_of_week = 6), TaskTrigger("weekly", time_of_day = "04:30", day_of_week = 6).toDraft().toTrigger())
    }

    @Test fun `a startup draft carries nothing else`() {
        assertEquals(TaskTrigger("startup"), TriggerDraft(type = "startup", time = "02:00", intervalValue = "5").toTrigger())
    }

    @Test fun `a time must be HH colon MM on a 24 hour clock`() {
        assertTrue(isValidTimeOfDay("00:00")); assertTrue(isValidTimeOfDay("23:59")); assertTrue(isValidTimeOfDay("2:05").not())
        assertFalse(isValidTimeOfDay("24:00")); assertFalse(isValidTimeOfDay("12:60")); assertFalse(isValidTimeOfDay("")); assertFalse(isValidTimeOfDay("noon"))
        assertNull(TriggerDraft(type = "daily", time = "25:00").toTrigger())
        assertNull(TriggerDraft(type = "weekly", time = "").toTrigger())
    }

    @Test fun `an interval must be a whole number above zero`() {
        assertNull(TriggerDraft(type = "interval", intervalValue = "0").toTrigger())
        assertNull(TriggerDraft(type = "interval", intervalValue = "").toTrigger())
        assertNull(TriggerDraft(type = "interval", intervalValue = "1.5").toTrigger())
        assertNull(TriggerDraft(type = "interval", intervalValue = "99999999999999999999").toTrigger())
    }

    @Test fun `maximum runtime is minutes, empty means no limit and zero stays zero`() {
        assertNull(TriggerDraft(type = "startup", maxRuntimeMinutes = "").toTrigger()!!.max_runtime_ms)
        assertEquals(1_800_000L, TriggerDraft(type = "startup", maxRuntimeMinutes = "30").toTrigger()!!.max_runtime_ms)
        assertEquals(30_000L, TriggerDraft(type = "startup", maxRuntimeMinutes = "0.5").toTrigger()!!.max_runtime_ms)
        assertEquals(0L, TriggerDraft(type = "startup", maxRuntimeMinutes = "0").toTrigger()!!.max_runtime_ms)
        assertNull(TriggerDraft(type = "startup", maxRuntimeMinutes = "-4").toTrigger())
        assertNull(TriggerDraft(type = "startup", maxRuntimeMinutes = "abc").toTrigger())
        assertEquals("30", TaskTrigger("startup", max_runtime_ms = 1_800_000).toDraft().maxRuntimeMinutes)
        assertEquals("0.5", TaskTrigger("startup", max_runtime_ms = 30_000).toDraft().maxRuntimeMinutes)
    }

    @Test fun `a trigger type the app does not know goes back exactly as it came`() {
        val unknown = TaskTrigger("lunar", interval_ms = 5, max_runtime_ms = 9)
        val draft = unknown.toDraft()
        assertTrue(draft.isUnknown)
        assertEquals(unknown, draft.toTrigger())
    }

    @Test fun `the whole schedule is valid only when every trigger is`() {
        val good = listOf(TriggerDraft(type = "daily", time = "02:00"), TriggerDraft(type = "startup"))
        assertEquals(listOf(TaskTrigger("daily", time_of_day = "02:00"), TaskTrigger("startup")), triggersOf(good))
        assertNull(triggersOf(good + TriggerDraft(type = "daily", time = "bad")))
        assertEquals(emptyList<TaskTrigger>(), triggersOf(emptyList()))
    }

    @Test fun `a new draft is an hourly interval, as in the web form`() {
        assertEquals(TaskTrigger("interval", interval_ms = 3_600_000), TriggerDraft.new().toTrigger())
    }
}
