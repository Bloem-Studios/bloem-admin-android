package com.bloemstudios.admin.platform

/** Server answers for the platform organization tests, shaped as the handlers write them (bloem_admin_platform.go, entitlement_templates.go). */
object PlatformFixtures {
    const val ORG_ID = "7d2c5d8e-3f1a-4a52-9c1e-0a6b8d1c2e3f"
    const val OTHER_ORG_ID = "c0ffee00-1111-4222-8333-444455556666"

    fun platformSession(token: String = "platform-pass", scope: String = "platform") =
        """{"access_token":"$token","expires_at":"2026-11-14T12:15:00Z","context":{"key":"$scope","scope":"$scope",""" +
            """"name":"Platform","status":"active","authority":"platform_admin","policy_revision":0,"security_revision":0}}"""

    fun error(code: String, message: String = "Request refused") = """{"error":"$code","message":"$message"}"""

    fun organizationJson(
        id: String = ORG_ID, name: String = "Acme Media", status: String = "active", owner: Int? = 4, revision: Long = 7,
        members: Int = 12, active: Int = 10, profiles: Int = 25, libraries: Int = 3, slug: String = "acme",
    ) = """{"id":"$id","slug":"$slug","name":"$name","status":"$status",""" +
        (owner?.let { """"owner_account_id":$it,""" } ?: "") +
        """"policy_revision":$revision,"is_default":false,"membership_count":$members,"active_membership_count":$active,""" +
        """"profile_count":$profiles,"library_count":$libraries,"entitlement_count":2}"""

    /** `GET /platform/organizations/{id}`: the organization under `organization`. */
    fun organization(json: String = organizationJson()) = """{"organization":$json}"""

    /** The answer of suspend and reactivate: an organization without counts. */
    fun lifecycleAnswer(status: String, revision: Long = 8) =
        """{"organization":{"id":"$ORG_ID","slug":"acme","name":"Acme Media","status":"$status","owner_account_id":4,""" +
            """"policy_revision":$revision,"is_default":false}}"""

    fun organizationsPage(vararg rows: String, next: String? = null) =
        """{"organizations":[${rows.joinToString(",")}]""" + (next?.let { ""","next_cursor":"$it"""" } ?: "") + "}"

    fun membership(
        id: String = "m4", account: Int = 4, status: String = "active", role: String = "admin",
        username: String = "pat", email: String = "pat@acme.test",
    ) = """{"id":"$id","organization_id":"$ORG_ID","account_id":$account,"status":"$status","legacy_role":"$role",""" +
        """"security_revision":3,"email":"$email","username":"$username"}"""

    fun membershipsPage(vararg rows: String, next: String? = null) =
        """{"memberships":[${rows.joinToString(",")}]""" + (next?.let { ""","next_cursor":"$it"""" } ?: "") + "}"

    fun entitlement(key: String = "basic", revision: Long = 3) =
        """{"template_key":"$key","template_revision":$revision,"managed_default_group":{"id":9,"name":"Basic members",""" +
            """"policy":{"all_libraries":false,"library_ids":[1,2],"playback_allowed":true,"max_streams":2,"max_profiles":4,""" +
            """"transcode_allowed":true,"max_transcodes":1,"download_allowed":false,"download_transcode_allowed":false,""" +
            """"max_playback_quality":"1080p","allowed_permissions":[],"requests_allowed":true}},""" +
            """"tenant_limits":{"slots":25,"transcodes":2},"library_ids":[1,2],"last_reconciled_at":"2026-11-10T09:30:00Z",""" +
            """"audit_history_href":"/api/bloem/v2/admin/platform/organizations/$ORG_ID/entitlement/audit"}"""

    fun noEntitlement() =
        """{"template_key":"","template_revision":0,"managed_default_group":null,"tenant_limits":{"slots":0,"transcodes":0},""" +
            """"library_ids":null,"last_reconciled_at":null,"audit_history_href":"/x"}"""

    fun template(key: String, name: String, revision: Long) =
        """{"key":"$key","name":"$name","revision":$revision,"enabled":true,"archived":false,"status":"enabled",""" +
            """"policy":{"all_libraries":true,"library_ids":null,"playback_allowed":true,"max_streams":4,"max_profiles":5,""" +
            """"transcode_allowed":true,"max_transcodes":2,"download_allowed":true,"download_transcode_allowed":false,""" +
            """"max_playback_quality":"2160p","allowed_permissions":null,"requests_allowed":true},"created_at":"2026-10-01T00:00:00Z"}"""

    fun templates(vararg rows: String) = """{"templates":[${rows.joinToString(",")}]}"""

    fun dryRun(
        key: String = "premium", revision: Long = 2, changed: Boolean = true,
        fromKey: String = "basic", fromRevision: Long = 3, token: String = "dry-run-token-1",
    ) = """{"result":{"organization_id":"$ORG_ID","template_key":"$key","template_revision":$revision,"dry_run":true,""" +
        """"changed":$changed,"previous_template_key":"$fromKey","previous_template_revision":$fromRevision,"policy":{}},""" +
        """"template_key":"$key","template_revision":$revision,"changed":$changed,"dry_run_token":"$token",""" +
        """"confirmation_token":"$token","expires_at":"2026-11-14T12:10:00Z","changes":""" +
        (if (changed) """[{"field":"template_revision","before":{"template_key":"$fromKey","template_revision":$fromRevision},""" +
            """"after":{"template_key":"$key","template_revision":$revision}}]""" else "[]") +
        ""","warnings":[]}"""

    fun applied(key: String = "premium", revision: Long = 2) =
        """{"organization_id":"$ORG_ID","template_key":"$key","template_revision":$revision,"dry_run":false,"changed":true,""" +
            """"profiles_moved":3,"previous_template_key":"basic","previous_template_revision":3,"policy":{}}"""
}
