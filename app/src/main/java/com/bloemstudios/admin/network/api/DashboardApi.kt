package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.dashboard.BuildInfo
import com.bloemstudios.admin.dashboard.DashboardLibrary
import com.bloemstudios.admin.dashboard.DashboardNode
import com.bloemstudios.admin.dashboard.PlaybackActivity
import com.bloemstudios.admin.dashboard.Timeseries
import com.bloemstudios.admin.dashboard.TopActivity
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.users.AdminUser

// The reads behind the web dashboard's widgets. The insight endpoints take `refresh=true` only when the
// user forced a refresh; automatic sampling uses the server's cache.

private fun refreshParam(refresh: Boolean): String? = if (refresh) "true" else null

/** Version of the running build. Fixed for the life of the process, so callers read it once. */
suspend fun ApiClient.getBuildInfo(): BuildInfo = get("/api/v2/admin/system/build")

/** Every page of the stream nodes. */
suspend fun ApiClient.listNodes(): List<DashboardNode> = readAllPages("/api/v2/admin/nodes")

/** Playback starts by method, reliability and active profiles over the last [hours] (1 to 744). */
suspend fun ApiClient.getPlaybackActivity(hours: Int, refresh: Boolean): PlaybackActivity =
    get("/api/v2/admin/stats/playback-activity", mapOf("hours" to hours.toString(), "refresh" to refreshParam(refresh)))

/** Sampled concurrent streams and egress over the last [hours] (1 to 744). */
suspend fun ApiClient.getTimeseries(hours: Int, refresh: Boolean): Timeseries =
    get("/api/v2/admin/stats/timeseries", mapOf("hours" to hours.toString(), "refresh" to refreshParam(refresh)))

/** Most-played titles and most-active profiles over the last [days] (1 to 30), at most [limit] of each (1 to 25). */
suspend fun ApiClient.getTopActivity(days: Int, limit: Int, refresh: Boolean): TopActivity =
    get("/api/v2/admin/stats/top-activity", mapOf("days" to days.toString(), "limit" to limit.toString(), "refresh" to refreshParam(refresh)))

/** The libraries; this list is not paginated. */
suspend fun ApiClient.listLibraries(): List<DashboardLibrary> = getCollection<DashboardLibrary>("/api/v2/libraries").items

/** The first page of accounts (up to 200); the dashboard sorts them by last activity and shows the newest few. */
suspend fun ApiClient.listUsersForDashboard(): List<AdminUser> =
    getCollection<AdminUser>("/api/v2/admin/users", mapOf("limit" to "200")).items
