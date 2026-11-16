package com.bloemstudios.admin.ui

import com.bloemstudios.admin.R
import com.bloemstudios.admin.jobs.TaskTrigger
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayLabelsTest {
    @Test fun `known task states map to words and an unknown one has none`() {
        assertEquals(R.string.task_state_idle, taskStateLabelRes("idle"))
        assertEquals(R.string.task_state_running, taskStateLabelRes("running"))
        assertEquals(R.string.task_state_cancelling, taskStateLabelRes("cancelling"))
        assertNull(taskStateLabelRes("paused_by_alien"))
    }

    @Test fun `run outcomes cover retained jobs and task executions and both spellings of cancelled`() {
        for (code in listOf("queued", "running", "succeeded", "completed", "failed", "cancelling")) {
            assertNotEquals("$code has a label", null, runStatusLabelRes(code))
        }
        assertEquals(runStatusLabelRes("canceled"), runStatusLabelRes("cancelled"))
        assertNotEquals(null, runStatusLabelRes("cancelled"))
        assertNull(runStatusLabelRes("exploded"))
    }

    @Test fun `roles map including the owner and an unknown role has no label`() {
        assertEquals(R.string.role_user, roleLabelRes("user"))
        assertEquals(R.string.role_admin, roleLabelRes("admin"))
        assertEquals(R.string.role_owner, roleLabelRes("owner"))
        assertNull(roleLabelRes("auditor"))
    }

    @Test fun `invitation statuses map`() {
        assertEquals(
            listOf(R.string.invitation_status_pending, R.string.invitation_status_accepted, R.string.invitation_status_revoked, R.string.invitation_status_expired),
            listOf("pending", "accepted", "revoked", "expired").map { invitationStatusLabelRes(it) }
        )
        assertNull(invitationStatusLabelRes("lost"))
    }

    @Test fun `library types map in both singular and plural spellings`() {
        assertEquals(libraryTypeLabelRes("movie"), libraryTypeLabelRes("movies"))
        assertEquals(libraryTypeLabelRes("audiobook"), libraryTypeLabelRes("audiobooks"))
        assertNotEquals(null, libraryTypeLabelRes("tv"))
        assertNull(libraryTypeLabelRes("holograms"))
    }

    @Test fun `interval triggers read as hours and minutes`() {
        assertEquals(TriggerLabel.Every(hours = 1, minutes = 0, seconds = 0), describeTrigger(TaskTrigger("interval", interval_ms = 3_600_000)))
        assertEquals(TriggerLabel.Every(hours = 1, minutes = 30, seconds = 0), describeTrigger(TaskTrigger("interval", interval_ms = 5_400_000)))
        assertEquals(TriggerLabel.Every(hours = 0, minutes = 15, seconds = 0), describeTrigger(TaskTrigger("interval", interval_ms = 900_000)))
        assertEquals(TriggerLabel.Every(hours = 0, minutes = 0, seconds = 45), describeTrigger(TaskTrigger("interval", interval_ms = 45_000)))
        assertEquals(TriggerLabel.Every(hours = 25, minutes = 0, seconds = 0), describeTrigger(TaskTrigger("interval", interval_ms = 90_000_000)))
    }

    @Test fun `an interval with no length is shown by its type`() {
        assertEquals(TriggerLabel.Other("interval"), describeTrigger(TaskTrigger("interval")))
        assertEquals(TriggerLabel.Other("interval"), describeTrigger(TaskTrigger("interval", interval_ms = 0)))
    }

    @Test fun `startup, daily and weekly triggers`() {
        assertEquals(TriggerLabel.Startup, describeTrigger(TaskTrigger("startup")))
        assertEquals(TriggerLabel.Daily("03:00"), describeTrigger(TaskTrigger("daily", time_of_day = "03:00")))
        assertEquals(TriggerLabel.Weekly(java.time.DayOfWeek.SUNDAY, "03:00"), describeTrigger(TaskTrigger("weekly", time_of_day = "03:00", day_of_week = 0)))
        assertEquals(TriggerLabel.Weekly(java.time.DayOfWeek.SATURDAY, "21:30"), describeTrigger(TaskTrigger("weekly", time_of_day = "21:30", day_of_week = 6)))
        assertEquals(TriggerLabel.Other("monthly"), describeTrigger(TaskTrigger("monthly")))
    }

    @Test fun `weekday names follow the locale`() {
        assertEquals("Sunday", weekdayName(java.time.DayOfWeek.SUNDAY, Locale.ENGLISH))
        assertEquals("zondag", weekdayName(java.time.DayOfWeek.SUNDAY, Locale("nl")))
    }
}
