package com.bloemstudios.admin.jobs

import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/*
 * What a task row and the task page say about a task, worked out from the fields GET /api/v2/admin/tasks sends
 * (state, progress, next_run_at, last_execution). Pure: the screens turn these into words with string resources.
 */

/** A length of time in the unit that reads best, as the web's task list puts it. */
sealed interface DurationText {
    data object UnderOneMs : DurationText
    data class Millis(val ms: Long) : DurationText
    data class Seconds(val seconds: Long) : DurationText
    data class MinutesSeconds(val minutes: Long, val seconds: Long) : DurationText
    data class HoursMinutes(val hours: Long, val minutes: Long) : DurationText
}

fun durationText(ms: Long): DurationText {
    if (ms <= 0) return DurationText.UnderOneMs
    if (ms < 1_000) return DurationText.Millis(ms)
    val totalSeconds = ms / 1_000
    if (totalSeconds < 60) return DurationText.Seconds(totalSeconds)
    val minutes = totalSeconds / 60
    if (minutes < 60) return DurationText.MinutesSeconds(minutes, totalSeconds % 60)
    return DurationText.HoursMinutes(minutes / 60, minutes % 60)
}

/** A span rounded down to its largest unit: "in 5 hr", "3 days ago". */
sealed interface Elapsed {
    data object LessThanAMinute : Elapsed
    data class Minutes(val count: Long) : Elapsed
    data class Hours(val count: Long) : Elapsed
    data class Days(val count: Long) : Elapsed
}

fun elapsedOf(seconds: Long): Elapsed {
    val minutes = seconds.coerceAtLeast(0) / 60
    return when {
        minutes < 1 -> Elapsed.LessThanAMinute
        minutes < 60 -> Elapsed.Minutes(minutes)
        minutes < 60 * 24 -> Elapsed.Hours(minutes / 60)
        else -> Elapsed.Days(minutes / (60 * 24))
    }
}

/** When an idle task runs next: in a while, or Overdue when that time has passed. */
sealed interface NextRun {
    data class In(val span: Elapsed) : NextRun
    data object Overdue : NextRun
}

/**
 * The next run of an idle task. A task that runs or is stopping has no next run to show, and a missing or unreadable
 * time shows nothing rather than a guess.
 */
fun nextRun(task: AdminTask, now: Instant): NextRun? {
    if (task.state != "idle") return null
    val at = task.next_run_at?.let(::parseInstant) ?: return null
    val seconds = Duration.between(now, at).seconds
    return if (at.isBefore(now)) NextRun.Overdue else NextRun.In(elapsedOf(seconds))
}

/** How long ago the last run finished. */
fun lastRunAge(last: TaskLastRun?, now: Instant): Elapsed? {
    val finished = last?.completed_at?.let(::parseInstant) ?: return null
    return elapsedOf(Duration.between(finished, now).seconds)
}

private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

/** Run is offered only for an idle task: starting one that runs or is stopping is refused with 409. */
fun canRun(task: AdminTask): Boolean = task.state == "idle"

/** Cancel is offered for a running task only; a task already stopping shows Stopping instead. */
fun canCancel(task: AdminTask): Boolean = task.state == "running"

fun isStopping(task: AdminTask): Boolean = task.state == "cancelling"

fun inFlight(task: AdminTask): Boolean = task.state == "running" || task.state == "cancelling"

/** The progress bar's fill, 0 to 1, from the server's percentage. */
fun progressFraction(task: AdminTask): Float {
    val percent = task.progress
    if (!percent.isFinite()) return 0f
    return (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
}

/** "42%" or "42.5%". A run still going never reads 100.0% because of rounding. */
fun progressPercentText(percent: Double, locale: Locale = Locale.getDefault()): String {
    val clamped = if (percent.isFinite()) percent.coerceIn(0.0, 100.0) else 0.0
    val whole = clamped == Math.floor(clamped)
    val shown = if (whole) clamped else (Math.round(clamped * 10) / 10.0).let { if (it >= 100.0) 99.9 else it }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (whole) 0 else 1; minimumFractionDigits = 0 }
    return format.format(shown) + "%"
}

fun failedSteps(steps: List<TaskStep>): List<String> = steps.filter { it.status == "failed" }.map { it.name }

fun lastRunFailed(last: TaskLastRun?): Boolean = last?.status == "failed"

/** The channels of the events socket that say a task or a job started, moved or ended. */
val TASK_CHANNELS: Set<String> = setOf(com.bloemstudios.admin.realtime.LiveChannels.TASKS, com.bloemstudios.admin.realtime.LiveChannels.JOBS)
