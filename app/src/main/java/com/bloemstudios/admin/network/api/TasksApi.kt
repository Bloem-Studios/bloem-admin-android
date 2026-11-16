package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.jobs.AdminTask
import com.bloemstudios.admin.jobs.RetainedAdminJob
import com.bloemstudios.admin.jobs.ScheduleUpdate
import com.bloemstudios.admin.jobs.TaskExecution
import com.bloemstudios.admin.jobs.TaskSchedule
import com.bloemstudios.admin.jobs.TaskTrigger
import com.bloemstudios.admin.network.ApiClient
import io.ktor.http.encodeURLPathPart

private fun taskPath(key: String, suffix: String = "") = "/api/v2/admin/tasks/${key.encodeURLPathPart()}$suffix"

/** The whole task list. The route declares no paging parameters and the server rejects unknown query parameters, so it is read unpaged. */
suspend fun ApiClient.listTasks(): List<AdminTask> = getCollection<AdminTask>("/api/v2/admin/tasks").items

suspend fun ApiClient.getTask(key: String): AdminTask = get(taskPath(key))

suspend fun ApiClient.runTask(key: String) = post(taskPath(key, "/run"))

suspend fun ApiClient.cancelTask(key: String) = post(taskPath(key, "/cancel"))

/** The schedule and its ETag; the validator is null when the server sent none. */
suspend fun ApiClient.getTaskSchedule(key: String): Pair<TaskSchedule, String?> =
    getWithValidator(taskPath(key, "/triggers"))

/** Replaces the whole trigger set; rejected with 412/428 when [validator] no longer matches. Never replayed after a 401. */
suspend fun ApiClient.updateTaskSchedule(key: String, triggers: List<TaskTrigger>, validator: String): TaskSchedule =
    putConditional(taskPath(key, "/triggers"), ScheduleUpdate(triggers), validator)

/** One page of retained jobs. [cursor] is the previous page's `next_cursor`; null asks for the first page. */
suspend fun ApiClient.listJobHistory(cursor: String?, limit: Int = 20): ApiCollection<RetainedAdminJob> =
    getCollection("/api/v2/admin/jobs", mapOf("limit" to limit.toString(), "cursor" to cursor))

/** One page of a task's executions. [cursor] is the previous page's `next_cursor`; null asks for the first page. */
suspend fun ApiClient.listTaskHistory(key: String, cursor: String?, limit: Int = 20): ApiCollection<TaskExecution> =
    getCollection(taskPath(key, "/history"), mapOf("limit" to limit.toString(), "cursor" to cursor))
