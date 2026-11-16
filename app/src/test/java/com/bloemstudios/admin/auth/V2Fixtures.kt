package com.bloemstudios.admin.auth

/** Schema-complete Silo /api/v2 bodies (every `required` field, including nested objects). */
object V2Fixtures {
    /** TokenPair; user is Account. */
    const val TOKEN_PAIR =
        """{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u1","username":"admin","email":"admin@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}}"""

    /** ServerIdentity. */
    const val SERVER_IDENTITY = """{"server_id":"3f2a9d5e-6b1c-4c7e-9a0d-2f4b8c1e7a35"}"""

    /** AuthProviderCollection with one credentials and one oauth AuthProvider. */
    const val AUTH_PROVIDERS =
        """{"password_login":true,"items":[{"id":"local","display_name":"Silo account","mode":"credentials","default":true},{"id":"26","display_name":"Corp SSO","mode":"oauth","default":false,"installation_id":"26","native_start_path":"/api/v2/auth/oauth/26/native/start"}]}"""

    /** OAuthCompletion; user is Account. */
    const val OAUTH_COMPLETION =
        """{"access_token":"a","refresh_token":"r","expires_in":3600,"next":"/","user":{"id":"u1","username":"admin","email":"admin@example.test","role":"admin","permissions":[],"download_allowed":true,"password_change_required":false}}"""

    /** RefreshedTokens. */
    const val REFRESHED_TOKENS = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""

    /** AdminServerStatus with AdminServerHealth, AdminServerHealthComponent and AdminArtworkStorageStatus. */
    const val SERVER_STATUS =
        """{"started_at":"2026-08-28T10:00:00Z","restart_required":false,"restart_mark_count":0,"restart_requested":false,"health":{"postgres":{"configured":true},"redis":{"configured":true},"errors_24h":0,"warnings_24h":0},"artwork_storage":{"locked":false,"status_known":true,"private_locked":false}}"""

    /** AdminDashboardStats with one AdminDashboardWatchProviderStats. */
    const val ADMIN_STATS =
        """{"total_items":4200,"total_files":5100,"total_users":12,"total_movies":900,"total_movie_files":950,"total_shows":60,"total_show_files":4150,"advisory_titles":0,"active_streams":3,"total_storage_bytes":987654321098,"watch_providers":[{"provider":"trakt","display_name":"Trakt","registered":true,"scrobbling":true,"exporting":true,"connected_profiles":5,"enabled_profiles":4,"export_enabled_profiles":2,"scrobble_enabled_profiles":3,"last_sync_completed_at":"2026-08-25T00:00:00Z","sync_runs_24h":10,"sync_errors_24h":1,"imported_watched_24h":0,"imported_progress_24h":0,"exported_watched_24h":0,"pending_exports":0,"failed_exports":0,"open_scrobbles":0,"scrobbles_24h":0}]}"""

    /** AdminSystemResources with AdminSystemStats (and one AdminDiskStats). */
    const val SYSTEM_RESOURCES =
        """{"available":true,"stale":false,"gpu":[],"sampled_at":"2026-01-01T00:00:00Z","system":{"cpu_pct":42,"load1":1.5,"cores":8,"mem_used_mb":1000,"mem_total_mb":4000,"disks":[{"used_gb":10,"total_gb":100}],"net_rx_bps":123,"net_tx_bps":56}}"""

    /** AdminSystemResources when the host cannot be sampled: no system, no sampled_at. */
    const val SYSTEM_RESOURCES_UNAVAILABLE = """{"available":false,"stale":false,"gpu":[]}"""

    /** AdminSystemResources serving the last good sample after sampling stopped. */
    val SYSTEM_RESOURCES_STALE = SYSTEM_RESOURCES.replace("\"stale\":false", "\"stale\":true")

    /** GET /admin/settings: a bare string map. */
    const val SETTINGS = """{"s3.public_bucket":"general-purpose-silo-us-east"}"""

    /** AdminServerRestart. */
    const val SERVER_RESTART = """{"status":"restart_requested","message":"Restart requested","notified_sessions":0}"""

    /**
     * AdminPlaybackSession with every required field. [optional] appends further optional members
     * (for example `"client_name":"Silo Android"`), written without a leading comma.
     */
    fun adminPlaybackSession(
        sessionId: String = "s1",
        username: String = "mchan969",
        title: String = "The Real Housewives of Miami",
        mediaType: String = "series",
        playMethod: String = "direct",
        position: String = "169.71",
        duration: String = "2600",
        paused: Boolean = false,
        optional: String = ""
    ): String =
        """{"session_id":"$sessionId","user_id":"user-1","username":"$username","profile_id":"profile-1","media_file_id":"file-1","requested_media_file_id":"file-1","media_title":"$title","media_type":"$mediaType","play_method":"$playMethod","reporting_node":"node-1","file_duration":$duration,"started_at":"2026-08-28T10:00:00Z","updated_at":"2026-08-28T10:02:49Z","position_seconds":$position,"is_paused":$paused,"has_playback_control":true,"stream_location":"lan","audio_track_index":0,"transcode_audio":false,"stream_bitrate_kbps":null,"target_bitrate_kbps":null,"source_bitrate_kbps":null,"source_audio_channels":null${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** CollectionAdminPlaybackSession; [nextCursor] set means another page follows. */
    fun adminPlaybackSessionsPage(vararg rows: String, nextCursor: String? = null): String =
        """{"items":[${rows.joinToString(",")}],"page":{"has_more":${nextCursor != null}${if (nextCursor != null) ",\"next_cursor\":\"$nextCursor\"" else ""}}}"""

    /** AdminPlaybackCommandReceipt. */
    const val PLAYBACK_COMMAND_RECEIPT =
        """{"command_id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","sequence":7,"outcome":"applied","delivery":"delivered"}"""

    /** AdminPlaybackTerminateReceipt. */
    const val PLAYBACK_TERMINATE_RECEIPT =
        """{"session_id":"s1","authority_revoked":true,"already_revoked":false,"durable_state":"revoked","client_notified":true,"delivery":"delivered"}"""

    /**
     * Schema-complete Problem (type, title, status, detail, instance); [status] must match the HTTP status it is sent with.
     * Real slugs: 400 malformed_request, invalid_cursor; 401 authentication_required, invalid_token, session_expired,
     * token_refresh_required; 403 permission_denied; 404 not_found; 409 conflict; 410 client_upgrade_required;
     * 412 precondition_failed; 428 precondition_required; 500 internal_error; 503 dependency_unavailable.
     */
    fun problem(status: Int, slug: String, detail: String, title: String = detail, instance: String = "urn:silo:request:test-1"): String =
        """{"type":"https://siloserver.org/docs/api/v2/problems/$slug","title":"$title","status":$status,"detail":"$detail","instance":"$instance"}"""

    /** Problem (409 conflict). */
    val PROBLEM_CONFLICT = problem(409, "conflict", "Conflict", instance = "urn:silo:request:test-conflict")

    /** AdminPlaybackHistoryEntry with every required field. */
    fun adminPlaybackHistoryEntry(sessionId: String = "h1", title: String = "Hot Off The Press", userId: String = "user-1"): String =
        """{"session_id":"$sessionId","user_id":"$userId","username":"mchan969","profile_id":"profile-1","profile_name":"Main","media_item_id":"item-1","media_file_id":"file-1","media_title":"$title","media_type":"episode","play_method":"direct","started_at":"2026-08-25T15:20:32Z","ended_at":"2026-08-25T16:03:39Z","watched_seconds":2659.854,"duration_seconds":2692.0,"completed":true,"played_seconds":2659.854,"series_title":"Housewives","season_number":2,"episode_number":3,"measured":true,"start_position_seconds":0.0,"paused_seconds":null,"measured_since":null,"measured_until":null,"client_ip":null,"device_id":null,"device_platform":null,"client_name":null,"client_version":null,"video_decision":"direct","audio_decision":"direct","end_reason":"stopped","lifecycle_seq":null}"""

    /** AdminPlaybackHistoryCollection; [nextCursor] set means another page follows. */
    fun adminPlaybackHistoryPage(vararg rows: String, nextCursor: String? = null): String =
        """{"items":[${rows.joinToString(",")}],"page":{"has_more":${nextCursor != null}${if (nextCursor != null) ",\"next_cursor\":\"$nextCursor\"" else ""}}}"""

    /** AdminTask with every required field (triggers is a list of AdminTaskTrigger, each with its required `type`). [optional] appends further members, without a leading comma. */
    fun adminTask(
        key: String = "scan",
        state: String = "idle",
        category: String = "library",
        manualOnly: Boolean = false,
        triggers: String = """[{"type":"startup"}]""",
        optional: String = ""
    ): String =
        """{"key":"$key","name":"Task $key","description":"d","category":"$category","state":"$state","progress":0.0,"manual_only":$manualOnly,"triggers":$triggers,"execution_scope":"process"${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** CollectionAdminTask; [nextCursor] set means another page follows. */
    fun adminTasksPage(vararg rows: String, nextCursor: String? = null): String = collection(rows, nextCursor)

    /** MediaRequest with every required field (including one RequestSeasonProgress and one RequestTarget). [optional] appends further members, without a leading comma. */
    fun mediaRequest(
        id: String = "r1",
        status: String = "queued",
        outcome: String = "pending",
        title: String = "Movie $id",
        optional: String = """"requested_by_user_id":"1""""
    ): String =
        """{"id":"$id","provider":"radarr","media_type":"movie","tmdb_id":949,"title":"$title","status":"$status","outcome":"$outcome","state":"pending","seasons":[],"season_progress":[{"season_number":1,"episodes_aired":10,"episodes_available":4}],"source":"direct","is_anime":false,"targets":[{"id":"t-$id","request_id":"$id","quality":"1080p","is_anime":false,"status":"pending","created_at":"2026-08-25T15:20:32.000Z","updated_at":"2026-08-25T15:20:32.000Z"}],"created_at":"2026-08-25T15:20:32.000Z","updated_at":"2026-08-25T15:20:32.000Z"${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** MediaRequestCollection; [nextCursor] set means another page follows. */
    fun mediaRequestsPage(vararg rows: String, nextCursor: String? = null): String = collection(rows, nextCursor)

    /** EffectivePolicy with every required field. [libraryIds] is a JSON array literal or `null`. */
    fun effectivePolicy(libraryIds: String = "null", maxStreams: Int = 6, permissions: String = "[]"): String =
        """{"library_ids":$libraryIds,"max_playback_quality":"1080p","max_streams":$maxStreams,"max_transcodes":2,"max_remote_stream_bitrate_kbps":0,"max_local_stream_bitrate_kbps":0,"max_profiles":4,"transcode_allowed":true,"audio_transcode_allowed":true,"download_allowed":true,"download_transcode_allowed":false,"requests_allowed":true,"permissions":$permissions}"""

    /** AdminUser with every required field, including the nested EffectivePolicy. [maxStreams] is the override (null inherits). */
    fun adminUser(id: String = "1", username: String = "alice", enabled: Boolean = true, maxStreams: Int? = null, role: String = "user", policy: String = effectivePolicy()): String =
        """{"id":"$id","username":"$username","email":"$username@example.test","role":"$role","permissions":[],"enabled":$enabled,"library_ids":null,"max_playback_quality":null,"max_streams":${maxStreams ?: "null"},"max_transcodes":null,"max_remote_stream_bitrate_kbps":null,"max_local_stream_bitrate_kbps":null,"transcode_allowed":null,"audio_transcode_allowed":null,"max_profiles":null,"download_allowed":null,"download_transcode_allowed":null,"requests_allowed":null,"access_group_id":null,"password_login":true,"password_change_required":false,"is_owner":false,"break_glass":false,"effective_policy":$policy,"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z","last_active_at":null}"""

    /** AdminAccountCapabilitiesOutputBody with all 21 required fields; [passwordResetEmail] is the one the app reads. */
    fun adminAccountCapabilities(passwordResetEmail: Boolean = true): String =
        """{"available":true,"guarded_configuration":true,"default_profile":true,"exact_identity_filter":true,"access_groups":true,"profile_limits":true,"password_reset_link":false,"password_reset_email":$passwordResetEmail,"ownership_transfer":true,"account_devices":true,"login_sessions":true,"watch_summary":true,"account_downloads":true,"request_usage":true,"policy_defaults":true,"profile_avatars":true,"profile_sections":true,"profile_management":true,"revision":"rev-1","state":"available","allowed":true}"""

    /** AdminUserCollection; [nextCursor] set means another page follows. */
    fun adminUsersPage(vararg rows: String, nextCursor: String? = null): String = collection(rows, nextCursor)

    /** LoginSession with every required field. [optional] appends further members, without a leading comma. */
    fun loginSession(id: String = "ls1", deviceName: String = "iPhone", current: Boolean = false, optional: String = ""): String =
        """{"id":"$id","device_name":"$deviceName","ip_address":"203.0.113.7","created_at":"2026-01-02T03:04:05.678Z","expires_at":"2026-02-01T03:04:05.678Z","last_seen_at":null,"current":$current${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** LoginSessionCollection. */
    fun loginSessionsPage(vararg rows: String, nextCursor: String? = null): String =
        collection(rows, nextCursor).replaceFirst("{", "{\"current_session\":null,")

    /** AdminLoginSessionsRevoked. */
    fun loginSessionsRevoked(revoked: Int): String = """{"revoked":$revoked}"""

    /** AdminUserDevice with every required field, including one AdminUserDeviceProfile. */
    fun adminUserDevice(deviceId: String = "d1", name: String = "Living Room TV"): String =
        """{"device_id":"$deviceId","device_name":"$name","device_platform":"tvOS","last_seen_at":"2026-01-02T03:04:05.678Z","last_updated":null,"override_count":2,"profiles":[{"profile_id":"p1","profile_name":"Household","override_count":2,"last_seen_at":null}]}"""

    /** CollectionAdminUserDevice (unpaginated). */
    fun adminUserDevicesPage(vararg rows: String): String = collection(rows, null)

    /** AdminAuditLog with every required field. [optional] appends further members, without a leading comma. */
    fun adminAuditLog(id: String = "1", method: String = "GET", path: String = "/api/v2/admin/users", optional: String = ""): String =
        """{"id":"$id","timestamp":"2026-08-25T12:00:00.000Z","client_ip":"203.0.113.7","method":"$method","path":"$path","status_code":200,"duration_ms":45${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** CollectionAdminAuditLog; [nextCursor] set means another page follows. */
    fun adminAuditLogsPage(vararg rows: String, nextCursor: String? = null): String = collection(rows, nextCursor)

    /** AdminOperationalLog with every required field. [optional] appends further members, without a leading comma. */
    fun adminOperationalLog(id: String = "1", level: String = "info", message: String = "scan completed", optional: String = ""): String =
        """{"id":"$id","timestamp":"2026-08-25T12:00:00.000Z","level":"$level","component":"scanner","message":"$message"${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** CollectionAdminOperationalLog; [nextCursor] set means another page follows. */
    fun adminOperationalLogsPage(vararg rows: String, nextCursor: String? = null): String = collection(rows, nextCursor)

    /** AdminInvitation with every required field. [optional] appends further members, without a leading comma. */
    fun adminInvitation(id: String = "1", email: String = "new@example.com", delivery: String = "email_sent", status: String = "pending", optional: String = ""): String =
        """{"id":"$id","email":"$email","delivery":"$delivery","role":"user","library_ids":null,"create_profile":true,"show_tour":true,"note":"","invited_by":"1","invited_by_name":"Admin","status":"$status","expires_at":"2026-09-01T00:00:00.000Z","created_at":"2026-08-25T12:00:00.000Z"${if (optional.isEmpty()) "" else ",$optional"}}"""

    /** CollectionAdminInvitation. */
    fun adminInvitationsPage(vararg rows: String, nextCursor: String? = null): String = collection(rows, nextCursor)

    /**
     * InvitationDelivery with every required field. [deliveryStatus] is one of sent, not_configured,
     * failed_or_unknown, not_requested.
     */
    fun invitationDelivery(deliveryStatus: String = "sent", claimUrl: String = "https://example.test/claim/secret", invitation: String = adminInvitation()): String =
        """{"invitation":$invitation,"claim_url":"$claimUrl","delivery_status":"$deliveryStatus"}"""

    private fun collection(rows: Array<out String>, nextCursor: String?): String =
        """{"items":[${rows.joinToString(",")}],"page":{"has_more":${nextCursor != null}${if (nextCursor != null) ",\"next_cursor\":\"$nextCursor\"" else ""}}}"""
}
