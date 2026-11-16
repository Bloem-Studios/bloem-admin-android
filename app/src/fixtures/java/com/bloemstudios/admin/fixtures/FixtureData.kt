package com.bloemstudios.admin.fixtures

import com.bloemstudios.admin.activity.PlaybackHistoryEntry
import com.bloemstudios.admin.apikeys.AdminApiKey
import com.bloemstudios.admin.dashboard.AdminStats
import com.bloemstudios.admin.dashboard.BuildInfo
import com.bloemstudios.admin.dashboard.DashboardInsights
import com.bloemstudios.admin.dashboard.DashboardLibrary
import com.bloemstudios.admin.dashboard.DashboardNode
import com.bloemstudios.admin.dashboard.DiskStats
import com.bloemstudios.admin.dashboard.GpuStats
import com.bloemstudios.admin.dashboard.HealthComponent
import com.bloemstudios.admin.dashboard.PlaybackActivity
import com.bloemstudios.admin.dashboard.PlaybackBucket
import com.bloemstudios.admin.dashboard.PlaybackReliability
import com.bloemstudios.admin.dashboard.SectionState
import com.bloemstudios.admin.dashboard.ServerHealth
import com.bloemstudios.admin.dashboard.SystemResources
import com.bloemstudios.admin.dashboard.SystemStats
import com.bloemstudios.admin.dashboard.Timeseries
import com.bloemstudios.admin.dashboard.TimeseriesPoint
import com.bloemstudios.admin.dashboard.TopActivity
import com.bloemstudios.admin.dashboard.TopProfile
import com.bloemstudios.admin.dashboard.TopTitle
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.organization.AccessGroup
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.organization.PersonSummary
import com.bloemstudios.admin.organization.ProfileSummary
import com.bloemstudios.admin.requests.MediaRequest
import com.bloemstudios.admin.requests.RequestCounts
import com.bloemstudios.admin.requests.RequestDownload
import com.bloemstudios.admin.requests.RequestTarget
import com.bloemstudios.admin.platform.EntitlementChange
import com.bloemstudios.admin.platform.EntitlementDryRun
import com.bloemstudios.admin.platform.EntitlementPolicy
import com.bloemstudios.admin.platform.EntitlementTemplate
import com.bloemstudios.admin.platform.ManagedDefaultGroup
import com.bloemstudios.admin.platform.OrganizationEntitlement
import com.bloemstudios.admin.platform.PlatformMembership
import com.bloemstudios.admin.platform.ServerOrganizations
import com.bloemstudios.admin.platform.TenantLimits
import com.bloemstudios.admin.serverconfig.ServerStatus
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.users.AdminUser
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Sample data for the debug-only fixture screen. Invented: no server, no account, no real library or person.
 * Timestamps are relative to now, so charts always end at the present.
 */
internal object FixtureData {
    /** Saved servers for the App settings alerts list. Invented names and addresses that resolve nowhere. */
    val savedServers = listOf(
        AdminServer("home", "https://home.example.test", "Bloem Home"),
        AdminServer("lab", "https://lab.example.test", "Lab"),
        AdminServer("garden", "https://garden.example.test", "Garden"),
    )

    private val now: Instant get() = Instant.now()
    private fun ago(minutes: Long): String = now.minus(Duration.ofMinutes(minutes)).toString()

    // ---- Now playing ----------------------------------------------------------------------------------------

    val sessions: List<AdminSession> = listOf(
        AdminSession(
            session_id = "s1", user_id = "u1", username = "maria", profile_id = "p1", media_title = "Dune: Part Two",
            media_type = "movie", play_method = "direct", position_seconds = 4_330.0, file_duration = 9_960.0, is_paused = false,
            profile_name = "Living room", device_platform = "tvos", client_name = "Infuse", client_version = "8.1",
            stream_bitrate_kbps = 38_400, video_decision = "direct", audio_decision = "direct", effective_play_method = "direct",
            source_video_codec = "hevc", source_video_resolution = "2160p", source_audio_codec = "truehd", source_audio_channels = 8,
            source_container = "mkv", source_bitrate_kbps = 38_400, stream_location = "local", started_at = ago(72), updated_at = ago(0),
        ),
        AdminSession(
            session_id = "s2", user_id = "u2", username = "jonas", profile_id = "p2", media_title = "Severance",
            media_type = "episode", play_method = "remux", position_seconds = 754.0, file_duration = 2_652.0, is_paused = false,
            profile_name = "Jonas", device_platform = "android", client_name = "Bloem", client_version = "1.4.2",
            stream_bitrate_kbps = 12_800, video_decision = "copy", audio_decision = "copy", effective_play_method = "remux",
            source_video_codec = "h264", source_video_resolution = "1080p", source_audio_codec = "eac3", source_audio_channels = 6,
            source_container = "mkv", output_container = "mpegts", output_protocol = "hls", stream_location = "remote",
            series_name = "Severance", episode_name = "Who Is Alive?", season_number = 2, episode_number = 3,
            started_at = ago(13), updated_at = ago(0),
        ),
        AdminSession(
            session_id = "s3", user_id = "u3", username = "amira", profile_id = "p3", media_title = "Planet Earth III",
            media_type = "episode", play_method = "transcode", position_seconds = 1_980.0, file_duration = 3_000.0, is_paused = false,
            profile_name = "Amira", device_platform = "ios", client_name = "Bloem", client_version = "1.4.1",
            stream_bitrate_kbps = 8_200, video_decision = "transcode", audio_decision = "transcode", effective_play_method = "transcode",
            transcode_hw_accel = "qsv", tone_map_mode = "hardware", source_video_codec = "hevc", source_video_resolution = "2160p",
            target_video_codec = "h264", target_resolution = "1080p", source_audio_codec = "eac3", source_audio_channels = 6,
            target_audio_codec = "aac", source_container = "mkv", output_container = "mpegts", output_protocol = "hls",
            stream_location = "remote", series_name = "Planet Earth III", episode_name = "Oceans", season_number = 1, episode_number = 2,
            started_at = ago(33), updated_at = ago(0), routing_workload = "video_transcode", routing_execution = "transcode",
            routing_execution_node_name = "gpu-1", routing_egress = "api", reporting_node = "main",
        ),
        AdminSession(
            session_id = "s4", user_id = "u4", username = "tomas", profile_id = "p4", media_title = "The River",
            media_type = "movie", play_method = "direct", position_seconds = 1_200.0, file_duration = 5_400.0, is_paused = true,
            profile_name = "Tomas", device_platform = "web", client_name = "Web", stream_bitrate_kbps = 6_100,
            video_decision = "direct", audio_decision = "direct", effective_play_method = "direct", source_video_codec = "h264",
            source_video_resolution = "1080p", source_audio_codec = "aac", source_audio_channels = 2, source_container = "mp4",
            stream_location = "remote", started_at = ago(41), updated_at = ago(2),
        ),
    )

    // ---- Dashboard ------------------------------------------------------------------------------------------

    private fun streamsAt(minutesAgo: Int): Triple<Int, Int, Int> {
        // A quiet night, a lunchtime bump and an evening peak, over the last 24 hours.
        val hour = ((now.epochSecond / 3600 - minutesAgo / 60) % 24).toInt()
        val base = 0.5 + 0.5 * sin((hour - 14) / 24.0 * 2 * PI)
        val total = max(0, (base * 7 + ((minutesAgo / 35) % 3)).toInt())
        val transcode = if (total >= 4) (total / 4) else 0
        val remux = if (total >= 2) (total / 3) else 0
        return Triple(total - transcode - remux, remux, transcode)
    }

    val timeseries24h: Timeseries by lazy {
        val step = 5
        val count = 24 * 60 / step
        val points = (count downTo 0).map { i ->
            val minutes = i * step
            val (direct, remux, transcode) = streamsAt(minutes)
            val total = direct + remux + transcode
            TimeseriesPoint(
                t = ago(minutes.toLong()), streams = total, egress_kbps = total * 6_200.0 + (minutes % 7) * 300.0,
                download_egress_kbps = if (minutes % 90 < 20) 14_000.0 else 0.0, direct = direct, remux = remux, transcode = transcode,
            )
        }
        Timeseries(
            resolution_seconds = step * 60, from = ago(24 * 60), to = now.toString(), oldest_sample_at = ago(30 * 24 * 60),
            egress_bytes = 412.0e9, points = points,
        )
    }

    private val activity: PlaybackActivity by lazy {
        val buckets = (23 downTo 0).map { h ->
            val (d, r, t) = streamsAt(h * 60)
            PlaybackBucket(
                hour = now.minus(Duration.ofHours(h.toLong())).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString(),
                direct = d + 1, remux = r, transcode = t,
            )
        }
        PlaybackActivity(
            hours = 24, bucket_seconds = 3600, from = ago(24 * 60), to = now.toString(), buckets = buckets,
            reliability = PlaybackReliability(
                sessions_started = buckets.sumOf { it.direct + it.remux + it.transcode }, finalized_sessions = 61,
                completed_sessions = 52, completion_rate = 0.85, unique_profiles = 14,
            ),
            profiles_active_24h = 14,
        )
    }

    val stats = AdminStats(
        total_items = 18_204, total_files = 20_113, total_users = 9, total_movies = 2_418, total_movie_files = 2_503,
        total_shows = 612, total_show_files = 17_610, active_streams = sessions.size, total_storage_bytes = 63_400_000_000_000L,
        watch_providers = emptyList(),
    )

    val host = SystemResources(
        available = true, sampled_at = ago(0),
        system = SystemStats(
            cpu_pct = 23.0, mem_used_mb = 11_264, mem_total_mb = 32_768, net_rx_bps = 21_000_000.0, net_tx_bps = 188_000_000.0,
            cores = 16, load1 = 3.42,
            disks = listOf(
                DiskStats(role = "media", used_gb = 41_800.0, total_gb = 48_000.0), DiskStats(role = "scratch", used_gb = 96.0, total_gb = 480.0, scratch = true),
            ),
        ),
        gpu = listOf(
            GpuStats(
                device = "Intel Arc A380", vendor = "intel", source = "intel_gpu_top", sessions = 1, total_busy_pct = 38, video_busy_pct = 31,
                render_busy_pct = 12, vram_used_mb = 1_536, vram_total_mb = 6_144,
            ),
        ),
    )

    private fun user(id: String, name: String, role: String = "user", enabled: Boolean = true, minutes: Long? = null) = AdminUser(
        id = id, username = name, email = "$name@example.test", role = role, enabled = enabled,
        created_at = "2026-01-02T03:04:05Z", last_active_at = minutes?.let { ago(it) },
    )

    val users = listOf(
        user("1", "pat", "admin", minutes = 3), user("2", "maria", minutes = 9), user("3", "jonas", minutes = 14),
        user("4", "amira", minutes = 33), user("5", "tomas", minutes = 41), user("6", "lena", enabled = false, minutes = 60 * 24 * 12),
    )

    val insights: DashboardInsights by lazy {
        val series = SectionState(timeseries24h)
        DashboardInsights(
            status = SectionState(
                ServerStatus(
                    started_at = now.minus(Duration.ofHours(76)).toString(), restart_required = false, restart_requested = false,
                    health = ServerHealth(HealthComponent(true, true, 2.1), HealthComponent(false), errors_24h = 2, warnings_24h = 11),
                ),
            ),
            build = SectionState(BuildInfo(build_number = 1482, display = "2026.11.14")),
            nodes = SectionState(listOf(DashboardNode("n1", "main", enabled = true, healthy = true), DashboardNode("n2", "gpu-1", enabled = true, healthy = true))),
            activity = SectionState(activity),
            egressNow = series,
            egress24h = series,
            top = SectionState(
                TopActivity(
                    titles = listOf(
                        TopTitle("1", "Severance", "show", 38, 71_000.0), TopTitle("2", "Dune: Part Two", "movie", 14, 140_000.0),
                        TopTitle("3", "Planet Earth III", "show", 12, 30_000.0),
                    ),
                    profiles = listOf(
                        TopProfile("2", "maria", "p1", "Living room", 21, 90_000.0), TopProfile("3", "jonas", "p2", "Jonas", 17, 48_000.0),
                    ),
                ),
            ),
            libraries = SectionState(
                listOf(
                    DashboardLibrary("1", "Movies", "movies", listOf("/media/movies")),
                    DashboardLibrary("2", "Shows", "shows", listOf("/media/shows", "/media/shows-uhd")),
                ),
            ),
            users = SectionState(users),
            recent = SectionState(
                listOf(
                    PlaybackHistoryEntry("h1", "2", "maria", "Dune: Part Two", "movie", 4_330.0, 9_960.0, false, ago(72)),
                    PlaybackHistoryEntry("h2", "3", "jonas", "Severance · S2E3 · Who Is Alive?", "episode", 2_652.0, 2_652.0, true, ago(180), ago(140)),
                ),
            ),
        )
    }

    // ---- A server's users, for the User page ----------------------------------------------------------------

    private fun userJson(u: AdminUser) =
        """{"id":"${u.id}","username":"${u.username}","email":"${u.email}","role":"${u.role}","permissions":[],"enabled":${u.enabled},""" +
            """"library_ids":null,"max_playback_quality":null,"max_streams":null,"max_transcodes":null,"transcode_allowed":null,""" +
            """"audio_transcode_allowed":null,"max_profiles":null,"download_allowed":null,"download_transcode_allowed":null,""" +
            """"requests_allowed":null,"access_group_id":null,"password_login":true,"password_change_required":false,""" +
            """"is_owner":${u.id == "1"},"break_glass":false,"effective_policy":{"library_ids":null,"max_playback_quality":"1080p","max_streams":4,""" +
            """"max_transcodes":2,"max_profiles":4,"transcode_allowed":true,"audio_transcode_allowed":true,"download_allowed":true,""" +
            """"download_transcode_allowed":false,"requests_allowed":true,"permissions":[]},""" +
            """"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","last_active_at":${u.last_active_at?.let { "\"$it\"" } ?: "null"}}"""

    /** The accounts as the items of a users page, for the other fixture clients. */
    fun usersPageItems(): String = users.joinToString(",", transform = ::userJson)

    /** An ApiClient whose engine answers the users routes from [users]; every other read gets an empty page. */
    val usersClient: ApiClient by lazy {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        fun page(rows: List<String>) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""
        ApiClient("https://fixture.invalid", "fixture", MockEngine { request ->
            val path = request.url.encodedPath
            val userId = Regex("^/api/v2/admin/users/(\\d+)$").find(path)?.groupValues?.get(1)
            when {
                // A fixture reset link: invented, not a credential for anything.
                request.method == HttpMethod.Post && path.endsWith("/password-reset") -> respond(
                    """{"delivery":"link","delivery_status":"not_requested","expires_at":"${now.plus(Duration.ofHours(4))}","reset_url":"https://bloem.example/reset-password#fixture-only-not-a-real-token"}""",
                    HttpStatusCode.Created, json,
                )
                request.method != HttpMethod.Get -> respond("", HttpStatusCode.NoContent)
                path == "/api/v2/account/me" -> respond(
                    """{"id":"1","username":"pat","email":"pat@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v2/admin/users/capabilities" -> respond(
                    """{"available":true,"login_sessions":true,"account_devices":true,"account_downloads":false,"watch_summary":true,"password_reset_email":true,"password_reset_link":true,"profile_management":true}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v2/admin/users" -> respond(page(users.map(::userJson)), HttpStatusCode.OK, json)
                userId != null -> respond(
                    userJson(users.first { it.id == userId }), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\"")),
                )
                path.endsWith("/admin/sessions") -> respond(
                    page(listOf(kotlinx.serialization.json.Json.encodeToString(AdminSession.serializer(), sessions[0].copy(user_id = request.url.parameters["user_id"] ?: "2", username = "maria")))),
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/profiles") -> respond(
                    page(
                        listOf(
                            """{"id":"p1","name":"Maria","has_pin":true,"is_primary":true,"is_child":false,"last_seen_at":"${ago(9)}"}""",
                            """{"id":"p2","name":"Living room","has_pin":false,"is_primary":false,"is_child":false,"last_seen_at":"${ago(72)}"}""",
                            """{"id":"p3","name":"Kids","has_pin":true,"is_primary":false,"is_child":true,"last_seen_at":"${ago(60 * 30)}"}""",
                        ),
                    ),
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/login-sessions") -> respond(
                    page(
                        listOf(
                            """{"id":"ls1","device_name":"Pixel 8","ip_address":"192.0.2.14","created_at":"${ago(60 * 24 * 5)}","expires_at":"${now.plus(Duration.ofDays(25))}","last_seen_at":"${ago(9)}","current":true}""",
                            """{"id":"ls2","device_name":"Living room TV","ip_address":"192.0.2.20","created_at":"${ago(60 * 24 * 40)}","expires_at":"${now.plus(Duration.ofDays(3))}","last_seen_at":"${ago(60 * 26)}","current":false}""",
                        ),
                    ),
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/devices") -> respond(
                    """{"items":[{"device_id":"d1","device_name":"Pixel 8","device_platform":"android","last_seen_at":"${ago(9)}","last_updated":"${ago(60)}","override_count":2,"profiles":[]},{"device_id":"d2","device_name":"Living room TV","device_platform":"tvos","last_seen_at":"${ago(60 * 26)}","last_updated":null,"override_count":0,"profiles":[]}]}""",
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/watch-summary") -> respond(
                    """{"days":30,"since":"2026-10-15T00:00:00Z","plays":42,"completed_plays":31,"watched_seconds":151200,"last_played_at":"${ago(33)}"}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond(page(emptyList()), HttpStatusCode.OK, json)
            }
        })
    }

    // ---- Requests -------------------------------------------------------------------------------------------

    val requesterNames = mapOf("2" to "maria", "3" to "jonas", "4" to "amira", "5" to "tomas")

    val requestCounts = RequestCounts(needs_approval = 4, in_progress = 2, failed = 1, done = 18)

    private fun request(id: String, title: String, type: String, year: Int, by: String, minutesAgo: Long, seasons: List<Int> = emptyList()) = MediaRequest(
        id = id, provider = "tmdb", media_type = type, title = title, status = "pending", outcome = "active", state = "pending",
        requested_by_user_id = by, year = year, seasons = seasons, created_at = ago(minutesAgo),
    )

    /** The Needs approval view: four requests waiting. */
    val pendingRequests = listOf(
        request("r1", "Dune: Part Two", "movie", 2024, "2", 12),
        request("r2", "Severance", "series", 2022, "3", 95, seasons = listOf(1, 2)),
        request("r3", "Planet Earth III", "series", 2023, "4", 60 * 5, seasons = listOf(1)),
        request("r4", "The River", "movie", 2019, "5", 60 * 29),
    )

    /** An in-progress request, for the Requests page with a download on it. */
    val downloadingRequest = MediaRequest(
        id = "r9", provider = "tmdb", media_type = "movie", title = "Past Lives", status = "downloading", outcome = "active", state = "processing",
        requested_by_user_id = "2", year = 2023, created_at = ago(60 * 3),
        targets = listOf(RequestTarget("t9", "1080p", "downloading", instance_name = "radarr-main", download = RequestDownload("downloading", 62))),
    )

    // ---- API keys -------------------------------------------------------------------------------------------

    val apiKeys = listOf(
        AdminApiKey("11", "1", "Home dashboard", "bk_8f2a", "standard", emptyList(), "2026-09-02T10:00:00Z", "pat", ago(25)),
        AdminApiKey("12", "2", "Requests bot", "bk_41cc", "standard", listOf("requests:write", "library:read"), "2026-10-11T08:30:00Z", "maria", ago(60 * 26)),
        AdminApiKey("13", "1", "Backup script", "bk_c07d", "elevated", listOf("admin:read"), "2026-06-20T19:12:00Z", "pat", null),
    )

    /** An ApiClient that answers the API key routes from [apiKeys] and the accounts they belong to. */
    val apiKeysClient: ApiClient by lazy {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val keyJson = kotlinx.serialization.json.Json { encodeDefaults = true }
        fun page(rows: List<String>) = """{"items":[${rows.joinToString(",")}],"page":{"has_more":false}}"""
        ApiClient("https://fixture.invalid", "fixture", MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v2/admin/api-keys/capabilities" -> respond("""{"available":true}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/api-keys" -> respond(page(apiKeys.map { keyJson.encodeToString(AdminApiKey.serializer(), it) }), HttpStatusCode.OK, json)
                path == "/api/v2/account/me" -> respond(
                    """{"id":"1","username":"pat","email":"pat@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v2/admin/users" -> respond(page(users.map(::userJson)), HttpStatusCode.OK, json)
                else -> respond(page(emptyList()), HttpStatusCode.OK, json)
            }
        })
    }

    // ---- An organization ------------------------------------------------------------------------------------

    val organization = OrganizationSummary(
        id = "o", slug = "acme", name = "Acme Media", status = "active", owner_account_id = 12, policy_revision = 7,
        membership_count = 48, active_membership_count = 44, profile_count = 91, library_count = 3, entitlement_count = 5,
    )

    val accessGroups = listOf(
        AccessGroup(id = "3", name = "Family", is_default = true, member_count = 31, max_streams = 2, max_profiles = 5,
            max_playback_quality = "original", download_allowed = true, requests_allowed = true),
        AccessGroup(id = "4", name = "Kids", member_count = 9, max_streams = 1, max_profiles = 3),
    )

    val people = listOf(
        PersonSummary(
            organization_id = "o", account_id = 1, email = "pat@acme.test", display_name = "Pat Owner", membership_id = "m1",
            membership_status = "active", legacy_role = "admin", security_revision = 4, last_activity = ago(4),
        ),
        PersonSummary(
            organization_id = "o", account_id = 5, email = "sam@acme.test", display_name = "Sam", membership_id = "m5",
            membership_status = "active", security_revision = 4, last_activity = ago(95),
            profiles = listOf(ProfileSummary("p1", "Living room", 3, "Family", ago(95))),
        ),
        PersonSummary(
            organization_id = "o", account_id = 6, email = "ana@acme.test", display_name = "Ana", membership_id = "m6",
            membership_status = "suspended", security_revision = 2, last_activity = ago(60 * 24 * 9),
        ),
    )
    // ---- Platform › Organizations ---------------------------------------------------------------------------

    val platformOrganizations = listOf(
        OrganizationSummary(
            id = "o1", slug = "acme", name = "Acme Media", status = "active", owner_account_id = 4, policy_revision = 7,
            membership_count = 48, active_membership_count = 44, profile_count = 91, library_count = 3, entitlement_count = 5,
        ),
        OrganizationSummary(
            id = "o2", slug = "north-sea-media", name = "North Sea Media", status = "suspended", owner_account_id = 9, policy_revision = 12,
            membership_count = 17, active_membership_count = 0, profile_count = 30, library_count = 2, entitlement_count = 1,
        ),
        OrganizationSummary(
            id = "o3", slug = "pier-9", name = "Pier 9 Studio", status = "initializing", owner_account_id = 15, policy_revision = 1,
            membership_count = 1, active_membership_count = 1, profile_count = 1, library_count = 0, entitlement_count = 0,
        ),
        OrganizationSummary(
            id = "o4", slug = "lantern", name = "Lantern Pictures", status = "active", owner_account_id = 21, policy_revision = 3,
            membership_count = 6, active_membership_count = 6, profile_count = 11, library_count = 1, entitlement_count = 0,
        ),
    )

    val platformMembers = listOf(
        PlatformMembership("m4", "o1", 4, "active", "admin", 3, "pat@acme.test", "pat"),
        PlatformMembership("m5", "o1", 5, "active", "user", 2, "sam@acme.test", "sam"),
        PlatformMembership("m6", "o1", 6, "invited", "user", 1, "ana@acme.test", "ana"),
        PlatformMembership("m7", "o1", 7, "suspended", "user", 5, "lee@acme.test", "lee"),
    )

    private val standardPolicy = EntitlementPolicy(
        library_ids = null, playback_allowed = true, max_streams = 2, max_profiles = 5, transcode_allowed = true, max_transcodes = 1,
        download_allowed = false, download_transcode_allowed = false, max_playback_quality = "1080p", allowed_permissions = emptyList(),
        requests_allowed = true,
    )

    val platformEntitlement = OrganizationEntitlement(
        template_key = "standard", template_revision = 3,
        managed_default_group = ManagedDefaultGroup(9, "Standard members", standardPolicy),
        tenant_limits = TenantLimits(slots = 50, transcodes = 4), library_ids = null, last_reconciled_at = ago(60 * 24 * 4),
    )

    val platformTemplates = listOf(
        EntitlementTemplate("standard", "Standard", 3, enabled = true, status = "enabled", policy = standardPolicy),
        EntitlementTemplate(
            "premium", "Premium", 2, enabled = true, status = "enabled",
            policy = standardPolicy.copy(max_streams = 4, max_transcodes = 2, download_allowed = true, max_playback_quality = "2160p"),
        ),
    )

    /** What putting Acme Media on Premium would change, as the server's dry-run answers it. */
    val platformDryRun = EntitlementDryRun(
        template_key = "premium", template_revision = 2, changed = true, dry_run_token = "fixture-token",
        changes = listOf(
            EntitlementChange(
                "template_revision",
                buildJsonObject { put("template_key", "standard"); put("template_revision", 3L) },
                buildJsonObject { put("template_key", "premium"); put("template_revision", 2L) },
            ),
            EntitlementChange("max_streams", JsonPrimitive(2), JsonPrimitive(4)),
            EntitlementChange("download_allowed", JsonPrimitive(false), JsonPrimitive(true)),
            EntitlementChange("max_playback_quality", JsonPrimitive("1080p"), JsonPrimitive("2160p")),
        ),
    )

    /** Two saved servers for All servers: one with organizations, one that is a plain server. */
    private val loft = AdminServer("loft", "https://loft.example.test", "Loft")
    private val shed = AdminServer("shed", "https://shed.example.test", "Shed")
    private val barn = AdminServer("barn", "https://barn.example.test", "Barn")

    val platformAggregate = listOf(
        ServerSectionResult(loft, Result.success(ServerOrganizations(true, platformOrganizations.take(2)))),
        ServerSectionResult(shed, Result.success(ServerOrganizations(false))),
        ServerSectionResult(barn, Result.success(ServerOrganizations(true, listOf(platformOrganizations[3]), nextCursor = "next"))),
    )
}
