package com.bloemstudios.admin.fixtures

import com.bloemstudios.admin.network.ApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Sample data for the Settings pages: an ApiClient whose engine answers the settings routes the way a current server does
 * (effective settings with their ETag, restart keys, secret status, a pending restart, request settings, network access,
 * server channels and a sign-in provider), and gives the test actions a mix of passes and failures.
 */
object FixtureSettings {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun ago(hours: Long) = Instant.now().minus(hours, ChronoUnit.HOURS).toString()

    /** Effective settings as a server reports them: every key with its default, a few changed, secrets left out. */
    private val effective: Map<String, String> = mapOf(
        "branding.server_name" to "Bloem Home", "branding.login_subtitle" to "Sign in with an existing account.",
        "server.public_url" to "https://bloem.example.com", "server.lan_discovery" to "true", "server.local_url" to "",
        "clientip.trusted_proxies" to "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, ::1/128",
        "database.max_connections" to "20",
        "auth.local_password_login" to "true", "signup.enabled" to "false", "password_reset.self_service_enabled" to "true",
        "auth.email_auto_match" to "false",
        "playback.watched_threshold" to "90", "playback.min_resume_threshold" to "5",
        "playback.transcode_enabled" to "true", "playback.hw_accel" to "vaapi", "allow_4k_transcode" to "false",
        "playback.prefer_lower_version_transcode" to "true", "playback.allow_hevc_encoding" to "false",
        "playback.transcode_hardware_tone_map_enabled" to "true", "playback.transcode_software_tone_map_enabled" to "true",
        "enable_transcode_throttle" to "true", "transcode_throttle_seconds" to "60",
        "playback.bandwidth_probe_automatic" to "true", "playback.bandwidth_probe_max_age_days" to "7",
        "markers.mode" to "both",
        "server.log_level" to "info", "server.log_quiet" to "", "opslog.retention_days" to "7", "opslog.max_size_mb" to "1024",
        "opslog.max_rows" to "1000000", "policy.decision_log_retention_days" to "14", "policy.decision_log_verbosity" to "digest",
        "email.enabled" to "true", "email.from_address" to "bloem@example.com", "email.from_name" to "Bloem Home",
        "email.smtp_host" to "smtp.example.com", "email.smtp_port" to "587", "email.smtp_security" to "starttls",
        "email.smtp_username" to "bloem@example.com",
        "notifications.ui_enabled" to "true", "notifications.web_push_enabled" to "true",
        "notifications.apple_push_delivery_enabled" to "true", "notifications.android_push_delivery_enabled" to "true",
        "notifications.email_enabled" to "true", "notifications.email.digest_hour" to "8",
        "notifications.discord_enabled" to "false", "notifications.discord.digest_hour" to "8",
        "notifications.webhooks_enabled" to "false", "notifications.server_channels_enabled" to "true",
        "notifications.release_events_enabled" to "true", "notifications.fanout_enabled" to "true",
        "notifications.fanout.settle_seconds" to "30", "notifications.fanout.max_series_burst" to "3",
        "notifications.fanout.max_event_age_hours" to "72", "notifications.retention.read_days" to "90",
        "notifications.retention.unread_days" to "180", "notifications.retention.event_days" to "30",
        "download.enabled" to "true", "download.transcode_enabled" to "false", "download.max_concurrent_prepares" to "2",
        "download.artifact_disk_ceiling_percent" to "85", "download.server_bandwidth_mbps" to "0",
        "download.user_bandwidth_mbps" to "0", "download.max_concurrent_per_user" to "3", "download.max_per_period" to "0",
        "download.period_duration" to "24h", "download.local_transcode_fallback" to "true", "download.artifact_cache_hours" to "72",
        // Not on a named page: these show under Other settings.
        "scanner.workers" to "8", "scanner.realtime_monitoring" to "true", "matcher.workers" to "8", "metadata.cache_images" to "true",
        "taskmanager.history_retention_days" to "30", "overlays.enabled" to "true",
    )

    private fun effectiveJson() = effective.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\"", "\\\"")}\"" }

    val client: ApiClient by lazy {
        ApiClient("https://fixture.invalid", "fixture", MockEngine { request ->
            val path = request.url.encodedPath
            val etag = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"fixture-1\""))
            when {
                path == "/api/v2/admin/settings/effective" -> respond(effectiveJson(), HttpStatusCode.OK, etag)
                path == "/api/v2/admin/settings/restart-keys" ->
                    respond("""{"keys":["playback.hw_accel","playback.ffmpeg_path","server.listen"],"prefixes":["s3.","scanner.max_concurrent"]}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/settings/sensitive-status" ->
                    respond("""{"configured":["email.smtp_password"],"managed_by_env":["clientip.trusted_proxies"]}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/server/status" ->
                    respond("""{"started_at":"${ago(76)}","restart_required":true,"restart_requested":false,"restart_required_reasons":["setting:playback.hw_accel","setting:server.public_url"]}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Put && path == "/api/v2/admin/settings" ->
                    respond("""{"values":{},"restart_required":false}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/requests/capabilities" -> respond("""{"available":true,"guarded_configuration":true,"routing":true}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/request-settings" -> respond(
                    """{"requests_enabled":true,"global_max_requests":5,"global_window_days":7,"global_auto_approval_enabled":false,"force_dual_quality":false,"watchlist_requests":true}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"req-1\"")),
                )
                path == "/api/v2/network-access/capabilities" -> respond("""{"available":true,"providers":[{"provider":"tailscale","display_name":"Tailscale","installation_id":"3"}]}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/network-access/tailscale/status" -> respond(
                    """{"provider":"tailscale","hosts":[
                        {"host":{"id":"api","role":"api","name":"Bloem Home"},"state":"connected","hostname":"bloem-home.tail1234.ts.net","origin":"https://bloem-home.tail1234.ts.net","addresses":["100.64.0.7"],"updated_at":"${ago(1)}"},
                        {"host":{"id":"node:2","role":"proxy","name":"Garage node"},"state":"awaiting_authorization","addresses":[],"auth_url":"https://login.tailscale.com/a/abc123","updated_at":"${ago(0)}"}]}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v2/admin/notifications/server-channels" -> respond(
                    """{"items":[
                        {"id":"ch1","name":"Requests in Discord","type":"discord","url_host":"discord.com","enabled":true,"consecutive_failures":0,"disabled_reason":null,"last_success_at":"${ago(2)}","last_failure_at":null,"last_failure_message":null},
                        {"id":"ch2","name":"Ops webhook","type":"generic","url_host":"hooks.example.com","enabled":true,"consecutive_failures":4,"disabled_reason":null,"last_success_at":"${ago(30)}","last_failure_at":"${ago(1)}","last_failure_message":"The webhook answered 502"}],"page":{"has_more":false}}""",
                    HttpStatusCode.OK, json,
                )
                request.method == HttpMethod.Post && path == "/api/v2/admin/notifications/server-channels/ch1/test" -> respond("""{"ok":true,"http_status":204,"duration_ms":180}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path.startsWith("/api/v2/admin/notifications/server-channels/") -> respond("""{"ok":false,"http_status":502,"duration_ms":930,"message":"The webhook answered 502 Bad Gateway"}""", HttpStatusCode.OK, json)
                path == "/api/v2/auth/external-sign-in/capabilities" -> respond("""{"available":true,"connection_test":true}""", HttpStatusCode.OK, json)
                path == "/api/v2/admin/plugins/installations" -> respond(
                    """{"items":[{"id":"7","plugin_id":"silo-auth-oidc","enabled":true,"capabilities":[{"type":"auth_provider.v1","id":"oidc","display_name":"OpenID Connect","sign_in_mode":"oauth","metadata":{"connection_test":true}}],"auth_bindings":[{"capability_id":"oidc","enabled":true}],"presentation":{"display_name":"Authentik"}}],"page":{"has_more":false}}""",
                    HttpStatusCode.OK, json,
                )
                request.method == HttpMethod.Post && path == "/api/v2/admin/plugins/installations/7/auth-binding/test" -> respond(
                    """{"ok":false,"steps":[{"id":"discovery","label":"Discovery document reachable","ok":true,"message":"Loaded the provider's discovery document"},{"id":"token","label":"Token endpoint","ok":false,"message":"The provider answered 401 invalid_client"}],"callback_url":""}""",
                    HttpStatusCode.OK, json,
                )
                request.method == HttpMethod.Post && path == "/api/v2/admin/email/test" -> respond("""{"ok":false,"duration_ms":3012,"message":"535 5.7.8 Authentication failed: check the mail server username and password"}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path == "/api/v2/admin/settings/check/redis" -> respond("""{"success":true,"message":"Connected to Redis in 4 ms"}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path == "/api/v2/admin/settings/check/meilisearch" ->
                    respond("""{"success":false,"message":"Connection check failed. Verify the submitted settings and provider availability."}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path.startsWith("/api/v2/admin/settings/check/") -> respond("""{"success":true,"message":""}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        })
    }
}
