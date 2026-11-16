package com.bloemstudios.admin.serverconfig

import androidx.annotation.StringRes
import com.bloemstudios.admin.R

// Generated from the web admin's settings catalogue (web/src/lib/adminSettingsSearch.ts and web/src/pages/admin-settings):
// the page names, the setting labels, units, choices and help text. Text lives in res/values/settings_pages_strings.xml.

/** One control: a stored server setting, its label, and how it is edited. */
data class SettingDef(
    val key: String,
    @StringRes val labelRes: Int,
    val kind: SettingKind,
    @StringRes val helpRes: Int = 0,
    /** True for a key the server has no default for: it is absent from the effective settings until someone sets it. */
    val noServerDefault: Boolean = false,
)

data class SettingGroup(
    @StringRes val titleRes: Int,
    val settings: List<SettingDef>,
    @StringRes val noteRes: Int = 0,
)

/** What a page shows beyond its controls. */
enum class PageExtra { SignInProvider, EmailTest, ServerChannels, RedisCheck }

data class SettingsPage(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    /** The web's English search synonyms; never shown. */
    val keywords: List<String>,
    val groups: List<SettingGroup>,
    val extras: Set<PageExtra> = emptySet(),
) {
    val settings: List<SettingDef> get() = groups.flatMap { it.settings }
    val keys: Set<String> get() = settings.mapTo(HashSet()) { it.key }
}

/** The pages built from stored settings. Network access and Requests have their own routes and are listed by the screen. */
val SETTINGS_PAGES: List<SettingsPage> = listOf(
    SettingsPage(
        "general", R.string.sp_general_title, R.string.sp_general_desc,
        listOf("server name", "login subtitle", "branding name", "public url", "lan discovery", "local network", "local address", "lan address", "multicast", "reverse proxy", "trusted proxies", "x-forwarded-for", "client ip", "redis", "postgres", "database", "pool"),
        listOf(
            SettingGroup(R.string.sp_general_group0, listOf(
                SettingDef("branding.server_name", R.string.sp_branding_server_name_label, SettingKind.Text("Silo"), helpRes = R.string.sp_branding_server_name_help),
                SettingDef("branding.login_subtitle", R.string.sp_branding_login_subtitle_label, SettingKind.Text("Sign in with an existing account"), helpRes = R.string.sp_branding_login_subtitle_help),
            )),
            SettingGroup(R.string.sp_general_group1, listOf(
                SettingDef("server.public_url", R.string.sp_server_public_url_label, SettingKind.Text("https://silo.example.com"), helpRes = R.string.sp_server_public_url_help),
                SettingDef("server.lan_discovery", R.string.sp_server_lan_discovery_label, SettingKind.Toggle, helpRes = R.string.sp_server_lan_discovery_help),
                SettingDef("server.local_url", R.string.sp_server_local_url_label, SettingKind.Text("http://192.168.1.20:8080"), helpRes = R.string.sp_server_local_url_help),
                SettingDef("clientip.trusted_proxies", R.string.sp_clientip_trusted_proxies_label, SettingKind.Text("172.16.0.0/12, 203.0.113.7/32"), helpRes = R.string.sp_clientip_trusted_proxies_help),
            )),
            SettingGroup(R.string.sp_general_group2, listOf(
                SettingDef("database.max_connections", R.string.sp_database_max_connections_label, SettingKind.Number(min = 1), helpRes = R.string.sp_database_max_connections_help),
            )),
        ),
        extras = setOf(PageExtra.RedisCheck),
    ),
    SettingsPage(
        "sign-in", R.string.sp_sign_in_title, R.string.sp_sign_in_desc,
        listOf("sso", "single sign-on", "oidc", "openid connect", "ldap", "active directory", "authentik", "authelia", "keycloak", "pocket id", "redirect uri", "callback url", "break-glass", "local password", "auto-create accounts", "email match", "provider logout", "signup", "invite", "forgot password", "password reset", "tailscale"),
        listOf(
            SettingGroup(R.string.sp_sign_in_group0, listOf(
                SettingDef("auth.local_password_login", R.string.sp_auth_local_password_login_label, SettingKind.Toggle, helpRes = R.string.sp_auth_local_password_login_help),
                SettingDef("signup.enabled", R.string.sp_signup_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_signup_enabled_help),
                SettingDef("password_reset.self_service_enabled", R.string.sp_password_reset_self_service_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_password_reset_self_service_enabled_help),
            )),
            SettingGroup(R.string.sp_sign_in_group1, listOf(
                SettingDef("auth.email_auto_match", R.string.sp_auth_email_auto_match_label, SettingKind.Toggle, helpRes = R.string.sp_auth_email_auto_match_help),
            ), R.string.sp_sign_in_group1_note),
        ),
        extras = setOf(PageExtra.SignInProvider),
    ),
    SettingsPage(
        "playback", R.string.sp_playback_title, R.string.sp_playback_desc,
        listOf("ffmpeg", "transcode", "hardware acceleration", "gpu", "hevc", "tone mapping", "chapter thumbnails", "trickplay", "seek preview", "watched threshold", "resume", "4k", "intro", "credits", "recap", "markers", "marker detection", "skip credits", "theintrodb"),
        listOf(
            SettingGroup(R.string.sp_playback_group0, listOf(
                SettingDef("playback.watched_threshold", R.string.sp_playback_watched_threshold_label, SettingKind.Number(unit = "%", min = 0, max = 100)),
                SettingDef("playback.min_resume_threshold", R.string.sp_playback_min_resume_threshold_label, SettingKind.Number(unit = "%", min = 0, max = 100), helpRes = R.string.sp_playback_min_resume_threshold_help),
            )),
            SettingGroup(R.string.sp_playback_group1, listOf(
                SettingDef("playback.transcode_enabled", R.string.sp_playback_transcode_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_playback_transcode_enabled_help),
                SettingDef("playback.hw_accel", R.string.sp_playback_hw_accel_label, SettingKind.Choice(listOf(ChoiceOption("auto", R.string.sp_opt_playback_hw_accel_auto), ChoiceOption("qsv", R.string.sp_opt_playback_hw_accel_qsv), ChoiceOption("vaapi", R.string.sp_opt_playback_hw_accel_vaapi), ChoiceOption("nvenc", R.string.sp_opt_playback_hw_accel_nvenc), ChoiceOption("videotoolbox", R.string.sp_opt_playback_hw_accel_videotoolbox), ChoiceOption("none", R.string.sp_opt_playback_hw_accel_none))), helpRes = R.string.sp_playback_hw_accel_help),
                SettingDef("allow_4k_transcode", R.string.sp_allow_4k_transcode_label, SettingKind.Toggle, helpRes = R.string.sp_allow_4k_transcode_help),
                SettingDef("playback.prefer_lower_version_transcode", R.string.sp_playback_prefer_lower_version_transcode_label, SettingKind.Toggle, helpRes = R.string.sp_playback_prefer_lower_version_transcode_help),
                SettingDef("playback.allow_hevc_encoding", R.string.sp_playback_allow_hevc_encoding_label, SettingKind.Toggle, helpRes = R.string.sp_playback_allow_hevc_encoding_help),
                SettingDef("playback.transcode_hardware_tone_map_enabled", R.string.sp_playback_transcode_hardware_tone_map_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_playback_transcode_hardware_tone_map_enabled_help),
                SettingDef("playback.transcode_software_tone_map_enabled", R.string.sp_playback_transcode_software_tone_map_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_playback_transcode_software_tone_map_enabled_help),
                SettingDef("enable_transcode_throttle", R.string.sp_enable_transcode_throttle_label, SettingKind.Toggle, helpRes = R.string.sp_enable_transcode_throttle_help),
                SettingDef("transcode_throttle_seconds", R.string.sp_transcode_throttle_seconds_label, SettingKind.Number(unit = "seconds", min = 1)),
            )),
            SettingGroup(R.string.sp_playback_group2, listOf(
                SettingDef("playback.bandwidth_probe_automatic", R.string.sp_playback_bandwidth_probe_automatic_label, SettingKind.Toggle, helpRes = R.string.sp_playback_bandwidth_probe_automatic_help),
                SettingDef("playback.bandwidth_probe_max_age_days", R.string.sp_playback_bandwidth_probe_max_age_days_label, SettingKind.Number(unit = "days", min = 1, max = 30), helpRes = R.string.sp_playback_bandwidth_probe_max_age_days_help),
            )),
            SettingGroup(R.string.sp_playback_group3, listOf(
                SettingDef("markers.mode", R.string.sp_markers_mode_label, SettingKind.Choice(listOf(ChoiceOption("off", R.string.sp_opt_markers_mode_off), ChoiceOption("local", R.string.sp_opt_markers_mode_local), ChoiceOption("both", R.string.sp_opt_markers_mode_both), ChoiceOption("online", R.string.sp_opt_markers_mode_online))), helpRes = R.string.sp_markers_mode_help),
            ), R.string.sp_playback_group3_note),
        ),
    ),
    SettingsPage(
        "logging", R.string.sp_logging_title, R.string.sp_logging_desc,
        listOf("log level", "debug", "quiet", "silenced log messages", "ops log", "retention", "decision log", "permission checks", "opa", "audit"),
        listOf(
            SettingGroup(R.string.sp_logging_group0, listOf(
                SettingDef("server.log_level", R.string.sp_server_log_level_label, SettingKind.Choice(listOf(ChoiceOption("debug", R.string.sp_opt_server_log_level_debug), ChoiceOption("info", R.string.sp_opt_server_log_level_info), ChoiceOption("warn", R.string.sp_opt_server_log_level_warn), ChoiceOption("error", R.string.sp_opt_server_log_level_error))), helpRes = R.string.sp_server_log_level_help),
                SettingDef("server.log_quiet", R.string.sp_server_log_quiet_label, SettingKind.Text("metadata, scanner"), helpRes = R.string.sp_server_log_quiet_help),
            )),
            SettingGroup(R.string.sp_logging_group1, listOf(
                SettingDef("opslog.retention_days", R.string.sp_opslog_retention_days_label, SettingKind.Number(unit = "days", min = 1)),
                SettingDef("opslog.max_size_mb", R.string.sp_opslog_max_size_mb_label, SettingKind.Number(unit = "MB", min = 1)),
                SettingDef("opslog.max_rows", R.string.sp_opslog_max_rows_label, SettingKind.Number(min = 1)),
            ), R.string.sp_logging_group1_note),
            SettingGroup(R.string.sp_logging_group2, listOf(
                SettingDef("policy.decision_log_retention_days", R.string.sp_policy_decision_log_retention_days_label, SettingKind.Number(unit = "days", min = 1)),
                SettingDef("policy.decision_log_verbosity", R.string.sp_policy_decision_log_verbosity_label, SettingKind.Choice(listOf(ChoiceOption("digest", R.string.sp_opt_policy_decision_log_verbosity_digest), ChoiceOption("verbose", R.string.sp_opt_policy_decision_log_verbosity_verbose)))),
            )),
        ),
    ),
    SettingsPage(
        "email", R.string.sp_email_title, R.string.sp_email_desc,
        listOf("email", "smtp", "mail", "mail server", "invitations", "password reset", "test email"),
        listOf(
            SettingGroup(R.string.sp_email_group0, listOf(
                SettingDef("email.enabled", R.string.sp_email_enabled_label, SettingKind.Toggle),
                SettingDef("email.from_address", R.string.sp_email_from_address_label, SettingKind.Text("silo@example.com"), noServerDefault = true),
                SettingDef("email.from_name", R.string.sp_email_from_name_label, SettingKind.Text("Silo")),
                SettingDef("email.smtp_host", R.string.sp_email_smtp_host_label, SettingKind.Text("smtp.example.com"), noServerDefault = true),
                SettingDef("email.smtp_port", R.string.sp_email_smtp_port_label, SettingKind.Number(min = 1, max = 65535), helpRes = R.string.sp_email_smtp_port_help),
                SettingDef("email.smtp_security", R.string.sp_email_smtp_security_label, SettingKind.Choice(listOf(ChoiceOption("starttls", R.string.sp_opt_email_smtp_security_starttls), ChoiceOption("tls", R.string.sp_opt_email_smtp_security_tls), ChoiceOption("none", R.string.sp_opt_email_smtp_security_none))), helpRes = R.string.sp_email_smtp_security_help),
                SettingDef("email.smtp_username", R.string.sp_email_smtp_username_label, SettingKind.Text(), helpRes = R.string.sp_email_smtp_username_help, noServerDefault = true),
                SettingDef("email.smtp_password", R.string.sp_email_smtp_password_label, SettingKind.Secret, noServerDefault = true),
            ), R.string.sp_email_group0_note),
        ),
        extras = setOf(PageExtra.EmailTest),
    ),
    SettingsPage(
        "notifications", R.string.sp_notifications_title, R.string.sp_notifications_desc,
        listOf("release events", "new episode", "push relay", "mobile push", "apns", "fcm", "discord", "web push", "webhooks", "server channels", "digest", "retention", "email"),
        listOf(
            SettingGroup(R.string.sp_notifications_group0, listOf(
                SettingDef("notifications.ui_enabled", R.string.sp_notifications_ui_enabled_label, SettingKind.Toggle),
                SettingDef("notifications.web_push_enabled", R.string.sp_notifications_web_push_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_notifications_web_push_enabled_help),
                SettingDef("notifications.apple_push_delivery_enabled", R.string.sp_notifications_apple_push_delivery_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_notifications_apple_push_delivery_enabled_help),
                SettingDef("notifications.android_push_delivery_enabled", R.string.sp_notifications_android_push_delivery_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_notifications_android_push_delivery_enabled_help),
                SettingDef("notifications.email_enabled", R.string.sp_notifications_email_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_notifications_email_enabled_help),
                SettingDef("notifications.email.digest_hour", R.string.sp_notifications_email_digest_hour_label, SettingKind.Number(min = 0, max = 23), helpRes = R.string.sp_notifications_email_digest_hour_help),
                SettingDef("notifications.discord_enabled", R.string.sp_notifications_discord_enabled_label, SettingKind.Toggle),
                SettingDef("notifications.discord.digest_hour", R.string.sp_notifications_discord_digest_hour_label, SettingKind.Number(min = 0, max = 23), helpRes = R.string.sp_notifications_discord_digest_hour_help),
                SettingDef("notifications.webhooks_enabled", R.string.sp_notifications_webhooks_enabled_label, SettingKind.Toggle),
                SettingDef("notifications.server_channels_enabled", R.string.sp_notifications_server_channels_enabled_label, SettingKind.Toggle),
            )),
            SettingGroup(R.string.sp_notifications_group1, listOf(
                SettingDef("notifications.release_events_enabled", R.string.sp_notifications_release_events_enabled_label, SettingKind.Toggle),
                SettingDef("notifications.fanout_enabled", R.string.sp_notifications_fanout_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_notifications_fanout_enabled_help),
                SettingDef("notifications.fanout.settle_seconds", R.string.sp_notifications_fanout_settle_seconds_label, SettingKind.Number(unit = "seconds", min = 0), helpRes = R.string.sp_notifications_fanout_settle_seconds_help),
                SettingDef("notifications.fanout.max_series_burst", R.string.sp_notifications_fanout_max_series_burst_label, SettingKind.Number(min = 1), helpRes = R.string.sp_notifications_fanout_max_series_burst_help),
                SettingDef("notifications.fanout.max_event_age_hours", R.string.sp_notifications_fanout_max_event_age_hours_label, SettingKind.Number(unit = "hours", min = 1), helpRes = R.string.sp_notifications_fanout_max_event_age_hours_help),
            )),
            SettingGroup(R.string.sp_notifications_group2, listOf(
                SettingDef("notifications.retention.read_days", R.string.sp_notifications_retention_read_days_label, SettingKind.Number(unit = "days", min = 1)),
                SettingDef("notifications.retention.unread_days", R.string.sp_notifications_retention_unread_days_label, SettingKind.Number(unit = "days", min = 1)),
                SettingDef("notifications.retention.event_days", R.string.sp_notifications_retention_event_days_label, SettingKind.Number(unit = "days", min = 1), helpRes = R.string.sp_notifications_retention_event_days_help),
            )),
        ),
        extras = setOf(PageExtra.ServerChannels),
    ),
    SettingsPage(
        "downloads", R.string.sp_downloads_title, R.string.sp_downloads_desc,
        listOf("downloads", "offline", "bandwidth", "mbps", "throttle", "quota", "concurrency", "prepared copies", "artifacts", "storage budget", "cache", "disk ceiling"),
        listOf(
            SettingGroup(R.string.sp_downloads_group0, listOf(
                SettingDef("download.enabled", R.string.sp_download_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_download_enabled_help),
                SettingDef("download.transcode_enabled", R.string.sp_download_transcode_enabled_label, SettingKind.Toggle, helpRes = R.string.sp_download_transcode_enabled_help),
                SettingDef("download.max_concurrent_prepares", R.string.sp_download_max_concurrent_prepares_label, SettingKind.Number(min = 0), helpRes = R.string.sp_download_max_concurrent_prepares_help),
                SettingDef("download.artifact_disk_ceiling_percent", R.string.sp_download_artifact_disk_ceiling_percent_label, SettingKind.Number(unit = "%", min = 50, max = 95), helpRes = R.string.sp_download_artifact_disk_ceiling_percent_help),
                SettingDef("download.server_bandwidth_mbps", R.string.sp_download_server_bandwidth_mbps_label, SettingKind.Number(unit = "Mbps", min = 0), helpRes = R.string.sp_download_server_bandwidth_mbps_help),
            )),
            SettingGroup(R.string.sp_downloads_group1, listOf(
                SettingDef("download.user_bandwidth_mbps", R.string.sp_download_user_bandwidth_mbps_label, SettingKind.Number(unit = "Mbps", min = 0), helpRes = R.string.sp_download_user_bandwidth_mbps_help),
                SettingDef("download.max_concurrent_per_user", R.string.sp_download_max_concurrent_per_user_label, SettingKind.Number(min = 0), helpRes = R.string.sp_download_max_concurrent_per_user_help),
                SettingDef("download.max_per_period", R.string.sp_download_max_per_period_label, SettingKind.Number(min = 0), helpRes = R.string.sp_download_max_per_period_help),
                SettingDef("download.period_duration", R.string.sp_download_period_duration_label, SettingKind.Text("24h"), helpRes = R.string.sp_download_period_duration_help),
            )),
            SettingGroup(R.string.sp_downloads_group2, listOf(
                SettingDef("download.local_transcode_fallback", R.string.sp_download_local_transcode_fallback_label, SettingKind.Toggle, helpRes = R.string.sp_download_local_transcode_fallback_help),
                SettingDef("download.artifact_cache_hours", R.string.sp_download_artifact_cache_hours_label, SettingKind.Number(unit = "hours", min = 0, max = 720), helpRes = R.string.sp_download_artifact_cache_hours_help),
            )),
        ),
    ),
)
