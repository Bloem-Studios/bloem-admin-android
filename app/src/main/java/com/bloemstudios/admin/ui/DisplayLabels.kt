package com.bloemstudios.admin.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.jobs.TaskTrigger
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/*
 * Words for the codes the server sends (task state, role, invitation status, library type, schedule trigger).
 * Each lookup returns a string resource for a code the app knows and null for any other, so a code from a newer
 * server is shown as the server wrote it instead of being guessed at.
 */

@StringRes
fun taskStateLabelRes(state: String): Int? = when (state) {
    "idle" -> R.string.task_state_idle
    "running" -> R.string.task_state_running
    "cancelling" -> R.string.task_state_cancelling
    else -> null
}

/** Retained job states and task execution outcomes share one list; the server spells cancelled both ways. */
@StringRes
fun runStatusLabelRes(status: String): Int? = when (status) {
    "queued" -> R.string.run_status_queued
    "running" -> R.string.run_status_running
    "succeeded" -> R.string.run_status_succeeded
    "completed" -> R.string.run_status_completed
    "failed" -> R.string.run_status_failed
    "cancelling" -> R.string.run_status_cancelling
    "canceled", "cancelled" -> R.string.run_status_cancelled
    else -> null
}

@StringRes
fun roleLabelRes(role: String): Int? = when (role) {
    "user" -> R.string.role_user
    "admin" -> R.string.role_admin
    "owner" -> R.string.role_owner
    else -> null
}

@StringRes
fun invitationStatusLabelRes(status: String): Int? = when (status) {
    "pending" -> R.string.invitation_status_pending
    "accepted" -> R.string.invitation_status_accepted
    "revoked" -> R.string.invitation_status_revoked
    "expired" -> R.string.invitation_status_expired
    else -> null
}

@StringRes
fun libraryTypeLabelRes(type: String): Int? = when (type) {
    "movie", "movies" -> R.string.library_type_movies
    "tv", "series", "shows" -> R.string.library_type_tv
    "audiobook", "audiobooks" -> R.string.library_type_audiobooks
    "music" -> R.string.library_type_music
    else -> null
}

/** The words for [code], or the code itself when the app has none. */
@Composable
fun codeLabel(code: String, labelRes: (String) -> Int?): String = labelRes(code)?.let { stringResource(it) } ?: code

/** How a task is scheduled to run, before it is put into words. */
sealed interface TriggerLabel {
    data class Every(val hours: Long, val minutes: Long, val seconds: Long) : TriggerLabel
    data object Startup : TriggerLabel
    data class Daily(val timeOfDay: String) : TriggerLabel
    data class Weekly(val day: DayOfWeek, val timeOfDay: String) : TriggerLabel
    /** A trigger the app cannot describe: shown by its type. */
    data class Other(val type: String) : TriggerLabel
}

fun describeTrigger(trigger: TaskTrigger): TriggerLabel = when (trigger.type) {
    "interval" -> trigger.interval_ms?.takeIf { it > 0 }?.let { ms ->
        val totalSeconds = ms / 1000
        if (totalSeconds < 60) TriggerLabel.Every(0, 0, totalSeconds.coerceAtLeast(1))
        else TriggerLabel.Every(totalSeconds / 3600, (totalSeconds % 3600) / 60, 0)
    } ?: TriggerLabel.Other(trigger.type)
    "startup" -> TriggerLabel.Startup
    "daily" -> TriggerLabel.Daily(trigger.time_of_day.orEmpty())
    // The server numbers weekdays from Sunday = 0.
    "weekly" -> TriggerLabel.Weekly(
        // Sunday is 0 on the server and 7 in java.time.
        DayOfWeek.of(((trigger.day_of_week ?: 0).coerceIn(0, 6)).let { if (it == 0) 7 else it }),
        trigger.time_of_day.orEmpty()
    )
    else -> TriggerLabel.Other(trigger.type)
}

fun weekdayName(day: DayOfWeek, locale: Locale = Locale.getDefault()): String = day.getDisplayName(TextStyle.FULL, locale)

@Composable
fun TaskTrigger.label(): String = when (val label = describeTrigger(this)) {
    is TriggerLabel.Every -> when {
        label.hours > 0 && label.minutes > 0 -> stringResource(R.string.jobs_trigger_every_hours_minutes, label.hours, label.minutes)
        label.hours > 0 -> stringResource(R.string.jobs_trigger_every_hours, label.hours)
        label.minutes > 0 -> stringResource(R.string.jobs_trigger_every_minutes, label.minutes)
        else -> stringResource(R.string.jobs_trigger_every_seconds, label.seconds)
    }
    TriggerLabel.Startup -> stringResource(R.string.jobs_trigger_startup)
    is TriggerLabel.Daily -> stringResource(R.string.jobs_trigger_daily, label.timeOfDay)
    is TriggerLabel.Weekly -> stringResource(R.string.jobs_trigger_weekly, weekdayName(label.day), label.timeOfDay)
    is TriggerLabel.Other -> label.type
}
