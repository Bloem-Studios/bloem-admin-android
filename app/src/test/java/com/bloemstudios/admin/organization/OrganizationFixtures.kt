package com.bloemstudios.admin.organization

/**
 * Bodies shaped exactly like the server's handlers write them (internal/api/handlers/
 * bloem_admin_organization.go, bloem_admin_people.go, bloem_admin_session.go, bloem_system.go and the
 * OpenAPI document): same field names, string ids where the server sends strings, and `null` for nil
 * sets, since the server does not omit those.
 */
object OrganizationFixtures {
    const val ORG_ID = "10000000-0000-0000-0000-000000000001"
    const val MEMBERSHIP_ID = "20000000-0000-0000-0000-000000000001"
    const val PASS = "pass.jwt.value"

    fun memberships(vararg rows: String) = """{"organizations":[${rows.joinToString(",")}]}"""

    fun membership(
        id: String = ORG_ID, name: String = "Acme Media", role: String = "admin",
        policyRevision: Long = 7, securityRevision: Long = 11,
    ) = """{"id":"$id","slug":"acme","name":"$name","default":false,"membership_id":"$MEMBERSHIP_ID",""" +
        """"membership_role":"$role","policy_revision":$policyRevision,"security_revision":$securityRevision}"""

    fun session(
        token: String = PASS, organizationId: String = ORG_ID, name: String = "Acme Media",
        authority: String = "organization_admin", policyRevision: Long = 7,
    ) = """{"access_token":"$token","expires_at":"2026-11-13T12:15:00Z","context":{"key":"organization:$organizationId",""" +
        """"scope":"organization","organization_id":"$organizationId","membership_id":"$MEMBERSHIP_ID","name":"$name",""" +
        """"status":"active","authority":"$authority","policy_revision":$policyRevision,"security_revision":11}}"""

    fun overview(policyRevision: Long = 7) =
        """{"organization":{"id":"$ORG_ID","slug":"acme","name":"Acme Media","status":"active","owner_account_id":4,""" +
            """"policy_revision":$policyRevision,"is_default":false,"membership_count":12,"active_membership_count":10,""" +
            """"profile_count":25,"library_count":3,"entitlement_count":2}}"""

    fun profile(id: String = "p1", name: String = "Living room", groupId: Long = 3, groupName: String = "Family") =
        """{"id":"$id","name":"$name","group_id":$groupId,"group_name":"$groupName","updated_at":"2026-11-12T08:00:00Z"}"""

    fun person(
        accountId: Long = 5, email: String = "sam@example.test", name: String = "Sam", status: String = "active",
        revision: Long = 4, role: String = "user", profiles: String = profile(),
    ) = """{"organization_id":"$ORG_ID","account_id":$accountId,"email":"$email","display_name":"$name",""" +
        """"membership_id":"person-membership-$accountId","membership_status":"$status","legacy_role":"$role","security_revision":$revision,""" +
        """"last_activity":"2026-11-12T09:30:00Z","profiles":[$profiles]}"""

    fun peoplePage(vararg people: String, next: String? = null, total: Long = people.size.toLong()) =
        """{"items":[${people.joinToString(",")}],""" + (next?.let { """"next_cursor":"$it",""" } ?: "") +
            """"approximate_total":$total}"""

    fun personResponse(person: String = person()) = """{"person":$person}"""

    fun group(id: String = "3", name: String = "Family", isDefault: Boolean = false, members: Long = 6) =
        """{"id":"$id","name":"$name","description":"","library_ids":["1","2"],"max_playback_quality":"original",""" +
            """"download_allowed":true,"download_transcode_allowed":false,"transcode_allowed":true,""" +
            """"audio_transcode_allowed":true,"max_streams":2,"max_transcodes":1,"max_remote_stream_bitrate_kbps":0,""" +
            """"max_local_stream_bitrate_kbps":0,"max_profiles":5,"allowed_permissions":null,"requests_allowed":true,""" +
            """"is_default":$isDefault,"member_count":$members,"created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-02T00:00:00Z"}"""

    fun groups(vararg rows: String) = """{"groups":[${rows.joinToString(",")}]}"""

    fun library(folderId: Long = 1, name: String = "Films", kind: String = "owned", entitlement: String? = null) =
        """{"folder_id":$folderId,"name":"$name","type":"movie","access_kind":"$kind"""" +
            (entitlement?.let { ""","entitlement":$it""" } ?: "") + "}"

    fun entitlement(status: String = "active", revision: Long = 9) =
        """{"id":"30000000-0000-0000-0000-000000000001","organization_id":"$ORG_ID","status":"$status","security_revision":$revision}"""

    fun libraries(vararg rows: String) = """{"libraries":[${rows.joinToString(",")}]}"""

    fun invitation(
        id: String = "i1", email: String = "new@example.test", status: String = "pending", groupId: String? = "3",
    ) = """{"id":"$id","email":"$email","delivery":"link","role":"user",""" +
        (groupId?.let { """"access_group_id":"$it",""" } ?: "") +
        """"library_ids":null,"create_profile":true,"show_tour":true,"note":"","invited_by":"4","invited_by_name":"Owner",""" +
        """"status":"$status","expires_at":"2026-11-20T00:00:00Z","created_at":"2026-11-13T00:00:00Z"}"""

    fun invitations(vararg rows: String) = """{"invitations":[${rows.joinToString(",")}]}"""

    fun handoff(invitation: String = invitation(), token: String = "claim-token-value") =
        """{"invitation":$invitation,"claim_token":"$token"}"""

    fun auditEvent(
        id: Long = 1, action: String = "people.membership_updated", outcome: String = "success", actor: String = "4",
        authority: String? = null, reason: String? = null, supportAccessId: String? = null,
    ) =
        """{"id":$id,"source":"people","created_at":"2026-11-12T09:30:00Z","actor_account_id":$actor,""" +
            (authority?.let { """"actor_authority":"$it",""" } ?: "") +
            (supportAccessId?.let { """"support_access_id":"$it",""" } ?: "") +
            (reason?.let { """"reason":"$it",""" } ?: "") +
            """"action":"$action","target_id":"abc","outcome":"$outcome"}"""

    fun audit(vararg rows: String, next: String? = null) =
        """{"events":[${rows.joinToString(",")}]""" + (next?.let { ""","next_cursor":"$it"""" } ?: "") + "}"

    fun cohort(id: String = "c1", name: String = "Family plan", archived: Boolean = false) =
        """{"cohort_id":"$id","organization_id":"$ORG_ID","name":"$name","revision":2,"access_group_id":3,""" +
            """"source_template_key":"family","source_template_revision":1,"parent_cohort_id":"c0","derivation_kind":"exact_template","policy":{"library_ids":null,""" +
            """"playback_allowed":true,"max_streams":2,"max_profiles":5,"transcode_allowed":true,"max_transcodes":1,""" +
            """"download_allowed":false,"download_transcode_allowed":false,"max_playback_quality":"original",""" +
            """"allowed_permissions":null,"requests_allowed":true},"policy_digest":"d","archived":$archived,"member_count":6,""" +
            """"created_by_account_id":4,"created_at":"2026-10-01T00:00:00Z"}"""

    fun cohorts(vararg rows: String) = """{"cohorts":[${rows.joinToString(",")}]}"""

    fun decision(id: Long = 1, action: String = "watch", allowed: Boolean = true) =
        """{"id":$id,"timestamp":"2026-11-12T09:30:00Z","organization":{"id":"$ORG_ID"},"subject":{"account_id":5,"profile_id":"p1"},""" +
            """"group":{"id":3,"name":"Family"},"library_ceiling":[1,2],"action":"$action","resource":{"library_id":1},""" +
            """"allowed":$allowed,"reason_code":"allowed_by_group","policy_versions":[{"kind":"group","version":2}]}"""

    fun decisions(vararg rows: String, next: String? = null) =
        """{"decisions":[${rows.joinToString(",")}]""" + (next?.let { ""","next_cursor":"$it"""" } ?: "") + "}"

    fun error(code: String, message: String = "Refused") = """{"error":"$code","message":"$message"}"""

    // ---- Selections, bulk jobs, group writes and decision detail (wave 2) ----------------------------

    fun selection(token: String = "sel-token", matched: Long = 12, excluded: Long = 0) =
        """{"selection":{"token":"$token","matched":$matched,"excluded":$excluded,"expires_at":"2026-11-13T12:30:00Z"}}"""

    fun bulkJob(
        status: String = "queued", current: Long = 0, total: Long = 12, succeeded: Long = 0,
        skipped: String = "", failed: String = "", jobId: String = "job-1",
    ) = """{"job":{"job_id":"$jobId","status":"$status","progress_current":$current,"progress_total":$total,""" +
        """"succeeded":$succeeded,"skipped":[$skipped],"failed":[$failed]}}"""

    fun record(accountId: Long, reason: String) = """{"account_id":$accountId,"reason":"$reason"}"""

    fun groupMutation(group: String = group(), policyRevision: Long = 7) = """{"group":$group,"policy_revision":$policyRevision}"""

    fun deletionImpact(reassigned: Long = 4, defaultGroup: Long = 3) =
        """{"profiles_reassigned":$reassigned,"default_group_id":$defaultGroup}"""

    fun decisionDetail(id: Long = 1, allowed: Boolean = true, resource: String = """{"library_id":1,"token":"[redacted]"}""") =
        """{"decision":{"id":$id,"timestamp":"2026-11-12T09:30:00Z","organization":{"id":"$ORG_ID","membership_id":"$MEMBERSHIP_ID"},""" +
            """"subject":{"account_id":5,"profile_id":"p1"},"group":{"id":3,"name":"Family"},"library_ceiling":[1,2],""" +
            """"action":"library_access_check","resource":$resource,"allowed":$allowed,"reason_code":"${if (allowed) "allowed_by_group" else "library_not_in_ceiling"}",""" +
            """"policy_versions":[{"kind":"vendor","version":4},{"kind":"custom","name":"Kids rule","version":2}]}}"""

    // ---- Organization support (Bloem server) -----------------

    const val SUPPORT_ACCESS_ID = "40000000-0000-0000-0000-000000000001"

    fun supportCapabilities(
        email: Boolean = true, link: Boolean = true, sessions: Boolean = true, devices: Boolean = true,
        add: Boolean = true, rename: Boolean = true, clearPin: Boolean = true, remove: Boolean = true,
        list: Boolean = true, stop: Boolean = true, message: Boolean = true,
    ) = """{"password_reset":{"email":$email,"link":$link},"sessions":$sessions,"devices":$devices,""" +
        """"profiles":{"add":$add,"rename":$rename,"clear_pin":$clearPin,"remove":$remove},""" +
        """"streams":{"list":$list,"stop":$stop,"message":$message}}"""

    fun supportProfile(
        id: String = "p1", name: String = "Living room", revision: Long = 3, hasPin: Boolean = false, primary: Boolean = false,
    ) = """{"id":"$id","name":"$name","group_id":3,"group_name":"Family","updated_at":"2026-11-12T08:00:00Z",""" +
        """"revision":$revision,"has_pin":$hasPin,"is_primary":$primary}"""

    fun supportPerson(revision: Long = 4, profiles: String = supportProfile(primary = true)) =
        person(revision = revision, profiles = profiles)

    fun reset(delivery: String, status: String, url: String? = null) =
        """{"reset":{"delivery":"$delivery","delivery_status":"$status","expires_at":"2026-11-14T10:00:00Z"""" +
            (url?.let { ""","reset_url":"$it"""" } ?: "") + """},"person":${supportPerson()}}"""

    const val RESET_URL = "https://example.test/reset-password/one-time-secret"

    fun supportSession(id: String = "s1", device: String = "Sam's TV", profile: String? = "Living room") =
        """{"id":"$id","device_name":"$device","device_id":"d1","device_platform":"tvos","auth_method":"password",""" +
            """"profile_id":${profile?.let { "\"p1\"" } ?: "null"},"profile_name":${profile?.let { "\"$it\"" } ?: "null"},""" +
            """"created_at":"2026-11-10T08:00:00Z","last_seen_at":"2026-11-13T20:00:00Z","expires_at":"2026-12-10T08:00:00Z"}"""

    fun supportSessions(vararg rows: String) = """{"sessions":[${rows.joinToString(",")}]}"""

    fun supportDevice(id: String = "d1", name: String = "Sam's TV", profiles: String = """{"profile_id":"p1","profile_name":"Living room"}""") =
        """{"device_id":"$id","name":"$name","platform":"tvos","last_seen_at":"2026-11-13T20:00:00Z","profiles":[$profiles]}"""

    fun supportDevices(vararg rows: String) = """{"devices":[${rows.joinToString(",")}]}"""

    fun signedOut(count: Long = 2, revision: Long = 5) = """{"person":${supportPerson(revision = revision)},"revoked":{"sessions":$count}}"""

    fun profileResponse(profile: String = supportProfile(), revision: Long = 4) = """{"profile":$profile,"person":${supportPerson(revision, profile)}}"""

    fun profileOnly(profile: String = supportProfile()) = """{"profile":$profile}"""

    fun supportStream(
        id: String = "st1", person: String = "Sam", title: String = "Pilot", series: String? = "Show", season: Int? = 1, episode: Int? = 2,
        method: String = "transcode", paused: Boolean = false, canStop: Boolean = true, canMessage: Boolean = true, accountId: Long = 5,
    ) = """{"session_id":"$id","account_id":$accountId,"person_name":"$person","profile_id":"p1","profile_name":"Living room","title":"$title",""" +
        (series?.let { """"series_name":"$it","season_number":$season,"episode_number":$episode,""" } ?: "") +
        """"media_type":"episode","started_at":"2026-11-14T08:00:00Z","updated_at":"2026-11-14T08:30:00Z","position_seconds":1800.5,""" +
        """"duration_seconds":3600,"is_paused":$paused,"play_method":"$method","video_decision":"transcode","audio_decision":"direct",""" +
        """"device":{"name":"Sam's TV","platform":"tvos","id":"d1","app":"Bloem tvOS"},"can_stop":$canStop,"can_message":$canMessage}"""

    fun supportStreams(vararg rows: String, next: String? = null) =
        """{"streams":[${rows.joinToString(",")}]""" + (next?.let { ""","next_cursor":"$it"""" } ?: "") + "}"

    fun stopped(already: Boolean = false) = """{"session_id":"st1","authority_revoked":true,"already_revoked":$already,"client_notified":true}"""

    fun messageReceipt(outcome: String = "applied", delivery: String = "dispatched") =
        """{"command_id":"c1","sequence":7,"outcome":"$outcome","delivery":"$delivery"}"""

    fun supportSessionPass(
        token: String = PASS, expiresAt: String = "2026-11-14T10:15:00Z", accessExpires: String = "2026-11-14T14:00:00Z",
        organizationId: String = ORG_ID, accessId: String = SUPPORT_ACCESS_ID,
    ) = """{"access_token":"$token","expires_at":"$expiresAt","context":{"key":"organization:$organizationId",""" +
        """"scope":"organization","organization_id":"$organizationId","name":"Acme Media","status":"active",""" +
        """"authority":"platform_admin","policy_revision":7,"access_basis":"support","support_access_id":"$accessId",""" +
        """"support_expires_at":"$accessExpires"}}"""

    fun platformOrganizations(vararg rows: String, next: String? = null) =
        """{"organizations":[${rows.joinToString(",")}]""" + (next?.let { ""","next_cursor":"$it"""" } ?: "") + "}"

    fun platformOrganization(id: String = ORG_ID, name: String = "Acme Media", status: String = "active") =
        """{"id":"$id","slug":"acme","name":"$name","status":"$status","policy_revision":7,"is_default":false,""" +
            """"membership_count":12,"active_membership_count":10,"profile_count":25,"library_count":3,"entitlement_count":2}"""

    fun protectedTarget(reason: String) =
        """{"error":"target_protected","message":"This person is protected from this action","reason":"$reason"}"""
}

