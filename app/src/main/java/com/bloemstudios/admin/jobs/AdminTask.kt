package com.bloemstudios.admin.jobs

import kotlinx.serialization.Serializable

/** v2 AdminTask. The list and the per-task read share this shape, so triggers are always present. */
@Serializable
data class AdminTask(
    val key: String,
    val name: String,
    val description: String,
    val category: String,
    val state: String,
    /** Percent complete, 0 to 100, while the task runs. */
    val progress: Double,
    val execution_scope: String,
    val manual_only: Boolean = false,
    val triggers: List<TaskTrigger> = emptyList(),
    val next_run_at: String? = null,
    /** How the latest run ended; absent for a task that has never run. */
    val last_execution: TaskLastRun? = null
)

/** One step of a task that runs several steps (database maintenance). */
@Serializable
data class TaskStep(val key: String, val name: String, val status: String)

/** A task's latest run as the list sends it: the execution without its id. */
@Serializable
data class TaskLastRun(
    val task_key: String = "",
    val started_at: String = "",
    val completed_at: String = "",
    val status: String = "",
    val duration_ms: Long = 0,
    val error_message: String? = null,
    val steps: List<TaskStep> = emptyList()
)

// One entry of a task's trigger array. `type` is a free-form string on the live
// server (interval, daily, weekly or startup today); interval_ms is only meaningful
// for type == "interval", time_of_day (HH:MM on the server's clock) for daily and
// weekly, day_of_week (0 = Sunday) for weekly. An unknown type round-trips unedited.
@Serializable
data class TaskTrigger(
    val type: String,
    val interval_ms: Long? = null,
    val time_of_day: String? = null,
    val day_of_week: Int? = null,
    val max_runtime_ms: Long? = null
)
