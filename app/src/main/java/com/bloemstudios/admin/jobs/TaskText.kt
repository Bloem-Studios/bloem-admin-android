package com.bloemstudios.admin.jobs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.codeLabel
import com.bloemstudios.admin.ui.label
import com.bloemstudios.admin.ui.runStatusLabelRes
import kotlinx.coroutines.delay
import java.time.Instant

/** The moment the screen is drawn for, refreshed every [everyMillis] so "next run in 5 min" and Overdue stay true. */
@Composable
fun rememberClock(everyMillis: Long = 30_000L): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(everyMillis) {
        while (true) {
            delay(everyMillis)
            now = Instant.now()
        }
    }
    return now
}

@Composable
fun DurationText.text(): String = when (this) {
    DurationText.UnderOneMs -> stringResource(R.string.duration_under_ms)
    is DurationText.Millis -> stringResource(R.string.duration_ms, ms)
    is DurationText.Seconds -> stringResource(R.string.duration_s, seconds)
    is DurationText.MinutesSeconds -> stringResource(R.string.duration_min_s, minutes, seconds)
    is DurationText.HoursMinutes -> stringResource(R.string.duration_hr_min, hours, minutes)
}

/** "in 5 hr" for something ahead. */
@Composable
fun Elapsed.textAhead(): String = when (this) {
    Elapsed.LessThanAMinute -> stringResource(R.string.elapsed_in_less_than_minute)
    is Elapsed.Minutes -> stringResource(R.string.elapsed_in_minutes, count)
    is Elapsed.Hours -> stringResource(R.string.elapsed_in_hours, count)
    is Elapsed.Days -> pluralStringResource(R.plurals.elapsed_in_days, count.toInt(), count)
}

/** "5 hr ago" for something behind. */
@Composable
fun Elapsed.textBehind(): String = when (this) {
    Elapsed.LessThanAMinute -> stringResource(R.string.elapsed_ago_less_than_minute)
    is Elapsed.Minutes -> stringResource(R.string.elapsed_ago_minutes, count)
    is Elapsed.Hours -> stringResource(R.string.elapsed_ago_hours, count)
    is Elapsed.Days -> pluralStringResource(R.plurals.elapsed_ago_days, count.toInt(), count)
}

/** The task's schedule in words, "Every 2 hr, Daily at 02:00", or null when it has none. */
@Composable
fun scheduleWords(triggers: List<TaskTrigger>): String? =
    triggers.takeIf { it.isNotEmpty() }?.map { it.label() }?.joinToString(", ")

/** "Completed · 2 min 14 s · 7 hr ago": how the last run ended, how long it took and when. */
@Composable
fun lastRunLine(last: TaskLastRun, now: Instant): String {
    val status = codeLabel(last.status, ::runStatusLabelRes)
    val age = lastRunAge(last, now)?.textBehind()
    val duration = last.duration_ms.takeIf { last.status != "" }?.let { durationText(it).text() }
    return when {
        duration != null && age != null -> stringResource(R.string.jobs_last_run_line, status, duration, age)
        duration != null -> stringResource(R.string.jobs_last_run_line_no_duration, status, duration)
        age != null -> stringResource(R.string.jobs_last_run_line_no_duration, status, age)
        else -> status
    }
}

@Composable
fun categoryLabel(category: String): String = when (category) {
    "library" -> stringResource(R.string.jobs_category_library)
    "metadata" -> stringResource(R.string.jobs_category_metadata)
    "system" -> stringResource(R.string.jobs_category_system)
    else -> category
}
