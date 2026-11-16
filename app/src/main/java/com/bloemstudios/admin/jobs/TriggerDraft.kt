package com.bloemstudios.admin.jobs

import java.math.BigDecimal

/*
 * The schedule editor's working copy of one trigger, as the web's trigger form edits it: a type, then the fields that
 * type needs, and a maximum runtime in minutes. A draft is text the person typed, so turning it back into a trigger can
 * fail (null), and the editor keeps Save off until every draft is valid.
 */

enum class IntervalUnit(val millis: Long) { Seconds(1_000), Minutes(60_000), Hours(3_600_000) }

private val KNOWN_TYPES = setOf("interval", "daily", "weekly", "startup")

data class TriggerDraft(
    val type: String = "interval",
    val intervalValue: String = "1",
    val intervalUnit: IntervalUnit = IntervalUnit.Hours,
    /** HH:MM on the server's clock, for daily and weekly. */
    val time: String = "00:00",
    /** 0 = Sunday, as the server numbers weekdays. */
    val day: Int = 0,
    val maxRuntimeMinutes: String = "",
    /** The trigger exactly as the server sent it, kept when its type is not one the editor knows. */
    val original: TaskTrigger? = null,
) {
    val isUnknown: Boolean get() = type !in KNOWN_TYPES

    fun toTrigger(): TaskTrigger? {
        if (isUnknown) return original
        val maxRuntime = parseMaxRuntimeMillis(maxRuntimeMinutes) ?: return null
        val limit = maxRuntime.takeIf { it != NO_LIMIT }
        return when (type) {
            "interval" -> {
                val count = intervalValue.toLongOrNull()?.takeIf { it > 0 } ?: return null
                if (count > Long.MAX_VALUE / intervalUnit.millis) return null
                TaskTrigger("interval", interval_ms = count * intervalUnit.millis, max_runtime_ms = limit)
            }
            "daily" -> if (isValidTimeOfDay(time)) TaskTrigger("daily", time_of_day = time, max_runtime_ms = limit) else null
            "weekly" -> if (isValidTimeOfDay(time) && day in 0..6) TaskTrigger("weekly", time_of_day = time, day_of_week = day, max_runtime_ms = limit) else null
            else -> TaskTrigger("startup", max_runtime_ms = limit)
        }
    }

    companion object {
        /** A new trigger is hourly, as the web form adds it. */
        fun new() = TriggerDraft()
    }
}

private const val NO_LIMIT = -1L

/** Empty text means no limit ([NO_LIMIT]); a number is minutes, fractions allowed; anything else is invalid (null). */
private fun parseMaxRuntimeMillis(text: String): Long? {
    if (text.isBlank()) return NO_LIMIT
    val minutes = text.trim().toBigDecimalOrNull() ?: return null
    if (minutes.signum() < 0) return null
    val millis = minutes.multiply(BigDecimal(60_000)).setScale(0, java.math.RoundingMode.HALF_UP)
    return if (millis > BigDecimal(Long.MAX_VALUE / 1_000)) null else millis.toLong()
}

fun isValidTimeOfDay(text: String): Boolean {
    val match = Regex("""^(\d{2}):(\d{2})$""").matchEntire(text) ?: return false
    return match.groupValues[1].toInt() in 0..23 && match.groupValues[2].toInt() in 0..59
}

fun TaskTrigger.toDraft(): TriggerDraft {
    val minutes = max_runtime_ms?.let { ms ->
        BigDecimal(ms).divide(BigDecimal(60_000), 3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }.orEmpty()
    if (type !in KNOWN_TYPES) return TriggerDraft(type = type, original = this, maxRuntimeMinutes = minutes)
    val ms = interval_ms ?: 3_600_000L
    val unit = when {
        ms >= IntervalUnit.Hours.millis && ms % IntervalUnit.Hours.millis == 0L -> IntervalUnit.Hours
        ms >= IntervalUnit.Minutes.millis && ms % IntervalUnit.Minutes.millis == 0L -> IntervalUnit.Minutes
        else -> IntervalUnit.Seconds
    }
    return TriggerDraft(
        type = type,
        intervalValue = (ms / unit.millis).coerceAtLeast(1).toString(),
        intervalUnit = unit,
        time = time_of_day ?: "00:00",
        day = (day_of_week ?: 0).coerceIn(0, 6),
        maxRuntimeMinutes = minutes,
        original = this,
    )
}

/** The whole trigger set, or null when any draft is invalid. */
fun triggersOf(drafts: List<TriggerDraft>): List<TaskTrigger>? {
    val triggers = drafts.map { it.toTrigger() ?: return null }
    return triggers
}
