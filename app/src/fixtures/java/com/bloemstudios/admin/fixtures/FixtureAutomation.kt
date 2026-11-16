package com.bloemstudios.admin.fixtures

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Duration
import java.time.Instant

/**
 * Sample scheduled tasks, nodes, backups and plugins for the debug-only fixture screen, answered by a mock engine so the
 * real Scheduled tasks, Nodes, Backups and Plugins screens run with no server. Invented: no account, address or library is real.
 */
internal object FixtureAutomation {
    private fun ago(minutes: Long): String = Instant.now().minus(Duration.ofMinutes(minutes)).toString()
    private fun ahead(minutes: Long): String = Instant.now().plus(Duration.ofMinutes(minutes)).toString()

    private fun run(id: Int, status: String, minutesAgo: Long, durationMs: Long, extra: String = "") =
        """{"id":"$id","task_key":"x","started_at":"${ago(minutesAgo + durationMs / 60_000)}","completed_at":"${ago(minutesAgo)}","status":"$status","duration_ms":$durationMs$extra}"""

    private fun task(
        key: String, name: String, description: String, category: String, state: String = "idle", progress: Double = 0.0,
        triggers: String, next: String? = null, last: String? = null,
    ) = """{"key":"$key","name":"$name","description":"$description","category":"$category","state":"$state","progress":$progress,"manual_only":false,"triggers":$triggers,"execution_scope":"process"${next?.let { ""","next_run_at":"$it"""" } ?: ""}${last?.let { ""","last_execution":$it""" } ?: ""}}"""

    private val tasks: List<Pair<String, String>> by lazy {
        listOf(
            "scan_libraries" to task(
                "scan_libraries", "Scan libraries", "Looks for new and changed files in every library and updates the catalog.", "library",
                triggers = """[{"type":"daily","time_of_day":"02:00"}]""", next = ahead(60 * 16 + 5),
                last = """{"task_key":"scan_libraries","started_at":"${ago(60 * 8)}","completed_at":"${ago(60 * 7 + 56)}","status":"completed","duration_ms":254000}""",
            ),
            "refresh_metadata" to task(
                "refresh_metadata", "Refresh metadata", "Fetches missing artwork and details for titles that lack them.", "metadata", state = "running", progress = 42.5,
                triggers = """[{"type":"interval","interval_ms":21600000}]""",
                last = """{"task_key":"refresh_metadata","started_at":"${ago(60 * 7)}","completed_at":"${ago(60 * 6 + 30)}","status":"completed","duration_ms":1800000}""",
            ),
            "database_maintenance" to task(
                "database_maintenance", "Database maintenance", "Vacuums and reindexes the database to keep it fast.", "system",
                triggers = """[{"type":"weekly","day_of_week":0,"time_of_day":"03:30"}]""", next = ago(35),
                last = """{"task_key":"database_maintenance","started_at":"${ago(60 * 24 * 7 + 4)}","completed_at":"${ago(60 * 24 * 7)}","status":"failed","duration_ms":240000,"error_message":"Task failed. Inspect administrator diagnostics for details.","steps":[{"key":"vacuum","name":"Vacuum","status":"completed"},{"key":"reindex","name":"Reindex","status":"failed"}]}""",
            ),
            "backup" to task(
                "backup", "Back up", "Writes the nightly backup of the database and uploaded files.", "system",
                triggers = """[{"type":"daily","time_of_day":"04:00"}]""", next = ahead(60 * 18),
                last = """{"task_key":"backup","started_at":"${ago(60 * 6 + 8)}","completed_at":"${ago(60 * 6)}","status":"completed","duration_ms":480000}""",
            ),
            "check_plugin_updates" to task(
                "check_plugin_updates", "Check plugin updates", "Looks for newer versions of installed plugins.", "system",
                triggers = """[{"type":"interval","interval_ms":86400000},{"type":"startup"}]""", next = ahead(60 * 24 * 3),
            ),
            "clean_transcodes" to task(
                "clean_transcodes", "Clean transcode cache", "Removes temporary transcode files that are no longer needed.", "system", state = "cancelling", progress = 80.0,
                triggers = """[{"type":"interval","interval_ms":3600000}]""",
            ),
        )
    }

    private val history = listOf(
        run(5, "failed", 60 * 7 + 56, 240_000, ""","error_message":"Task failed. Inspect administrator diagnostics for details.","steps":[{"key":"vacuum","name":"Vacuum","status":"completed"},{"key":"reindex","name":"Reindex","status":"failed"}]"""),
        run(4, "completed", 60 * 31, 254_000),
        run(3, "cancelled", 60 * 55, 62_000, ""","error_message":"Stopped at its maximum runtime of 1 minute""""),
        run(2, "completed", 60 * 79, 3_900_000),
        run(1, "completed", 60 * 103, 850),
    )

    private val nodes: String by lazy {
        fun node(id: Int, name: String, type: String, group: String?, healthy: Boolean = true, enabled: Boolean = true, jobs: Int = 0, maxJobs: Int? = null,
                 drainRequested: Boolean = false, drainState: String = "none", resources: String? = null, egress: Int = 0, maxBandwidth: Int? = null, check: String? = ago(0)) =
            """{"id":"$id","name":"$name","type":"$type","enabled":$enabled,"healthy":$healthy,"active_jobs":$jobs,"group":${group?.let { "\"$it\"" } ?: "null"},"max_jobs":${maxJobs ?: "null"},"max_bandwidth_kbps":${maxBandwidth ?: "null"},"egress_kbps":$egress,"last_health_check":${check?.let { "\"$it\"" } ?: "null"},"created_at":"2026-01-01T00:00:00.000Z","drain_requested":$drainRequested,"drain_state":"$drainState"${resources?.let { ""","resources":$it""" } ?: ""}}"""
        fun resources(cpu: Int, memUsed: Int, memTotal: Int, disk: Int, rx: Long, tx: Long) =
            """{"sampled_at":"${ago(0)}","system":{"cpu_pct":$cpu,"load1":1.8,"cores":8,"mem_used_mb":$memUsed,"mem_total_mb":$memTotal,"disks":[{"used_gb":$disk,"total_gb":100.0,"role":"scratch"}],"net_rx_bps":$rx,"net_tx_bps":$tx},"gpu":[]}"""
        listOf(
            node(1, "gpu-1", "transcode", "Rack A", jobs = 3, maxJobs = 4, resources = resources(37, 6_144, 16_384, 62, 12_500_000, 2_500_000)),
            node(2, "gpu-2", "transcode", "Rack A", jobs = 1, maxJobs = 4, drainRequested = true, drainState = "draining", resources = resources(12, 3_072, 16_384, 41, 800_000, 300_000)),
            node(3, "edge-1", "proxy", "Edge", healthy = false, egress = 0, maxBandwidth = 500_000, check = ago(11)),
            node(4, "gpu-3", "transcode", null, enabled = false, check = ago(60 * 30)),
        ).joinToString(",")
    }

    private val backups: String by lazy {
        fun item(id: String, kind: String, minutesAgo: Long, size: Long, status: String = "complete") =
            """{"id":"$id","kind":"$kind","status":"$status","created_at":"${ago(minutesAgo)}","completed_at":"${ago(minutesAgo - 8)}","size_bytes":$size,"location":"/backups","blob_count":120,"missing_blobs":[],"missing_blobs_total":0,"restore_set_omitted":[],"objects":[],"encrypted":false,"key_matches":true,"clean":true}"""
        """{"server_id":"home","destination":"default","destination_available":true,"encrypted":false,"key_check":"abcd","key_confirmed":true,"enabled":true,"busy":true,"active":{"operation":"backup","started_at":"${ago(4)}","progress":42.5,"message":"Copying files"},"keep":7,"last_success_at":"${ago(60 * 6)}","items":[${listOf(
            item("20261114T040000Z-nightly", "nightly", 60 * 6 + 8, 1_932_735_283),
            item("20261113T040000Z-nightly", "nightly", 60 * 30 + 8, 1_902_001_234),
            item("20261113T101500Z-manual", "manual", 60 * 24 + 5, 1_899_001_234),
            item("20261112T040000Z-nightly", "nightly", 60 * 54 + 8, 1_870_123_456, status = "complete_with_warnings"),
        ).joinToString(",")}],"other_server_ids":[],"warnings":[]}"""
    }

    private val plugins: String by lazy {
        fun plugin(id: Int, pluginId: String, display: String, state: String, resident: Boolean = true, enabled: Boolean = true, available: String? = null, error: String? = null,
                   updateFailure: String? = null) =
            """{"id":"$id","repository_id":"1","plugin_id":"$pluginId","version":"1.2.0","install_path":"/p","enabled":$enabled,"kind":"x","update_policy":"notify","source_kind":"silo","updates_paused":false,${available?.let { """"available_version":"$it",""" } ?: ""}${updateFailure?.let { """"update_failure":$it,""" } ?: ""}"presentation":{"display_name":"$display","summary":"","publisher_name":"Silo"},"signature":{"state":"verified"},"capabilities":[],"runtime":{"resident":$resident,"state":"$state","restart_count":2${error?.let { ""","last_error":"$it"""" } ?: ""}}}"""
        listOf(
            plugin(1, "silo.tailscale-access", "Tailscale access", "failed", error = "exit status 1: could not reach the control plane"),
            plugin(2, "silo.oidc", "OpenID Connect", "backoff", error = "connection refused"),
            plugin(3, "silo.ldap", "LDAP sign-in", "running", available = "1.3.0"),
            plugin(4, "silo.tmdb", "TMDB metadata", "running", resident = false, updateFailure = """{"version":"1.4.0","reason":"The download did not match its checksum","failed_at":"${ago(60 * 5)}"}"""),
            plugin(5, "silo.discord", "Discord notifications", "stopped", enabled = false),
        ).joinToString(",")
    }

    private val settings = """{"backup.enabled":"true","backup.time":"04:00","backup.keep":"7"}"""

    /** An ApiClient that answers the automation routes from the samples above; any write is accepted and changes nothing. */
    val client: ApiClient by lazy {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        fun page(rows: String) = """{"items":[$rows],"page":{"has_more":false}}"""
        ApiClient("https://fixture.invalid", "fixture", MockEngine { request ->
            val path = request.url.encodedPath
            val taskKey = Regex("^/api/v2/admin/tasks/([^/]+)$").find(path)?.groupValues?.get(1)
            when {
                request.method != HttpMethod.Get -> respond("{}", HttpStatusCode.OK, json)
                path == "/api/v2/admin/tasks" -> respond(page(tasks.joinToString(",") { it.second }), HttpStatusCode.OK, json)
                taskKey != null -> respond(tasks.first { it.first == taskKey }.second, HttpStatusCode.OK, json)
                path.endsWith("/history") -> respond(page(history.joinToString(",")), HttpStatusCode.OK, json)
                path.endsWith("/triggers") -> respond(
                    """{"task_key":"scan_libraries","triggers":[{"type":"daily","time_of_day":"02:00"}]}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"t1\"")),
                )
                path == "/api/v2/admin/jobs" -> respond(page(""), HttpStatusCode.OK, json)
                path == "/api/v2/admin/nodes/capabilities" -> respond("""{"state":"available","allowed":true,"drain":true,"summary":true,"revision":"r"}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/nodes/summary" -> respond(page(nodes), HttpStatusCode.OK, json)
                path == "/api/v2/admin/nodes" -> respond(page(""), HttpStatusCode.OK, json)
                path == "/api/v2/admin/backups/capabilities" -> respond("""{"state":"available","allowed":true}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/backups" -> respond(backups, HttpStatusCode.OK, json)
                path == "/api/v2/admin/settings/effective" -> respond(
                    settings, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"s1\"")),
                )
                path == "/api/v2/admin/plugins/installations" -> respond(page(plugins), HttpStatusCode.OK, json)
                else -> respond(page(""), HttpStatusCode.OK, json)
            }
        })
    }
}
