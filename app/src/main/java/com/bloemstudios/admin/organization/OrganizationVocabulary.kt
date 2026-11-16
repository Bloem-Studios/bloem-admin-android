package com.bloemstudios.admin.organization

import androidx.annotation.StringRes
import com.bloemstudios.admin.R

/*
 * The words the web's admin uses for policy: plans, cohorts, access checks and bulk results
 * (web/src/components/admin/policy/policyVocabulary.ts). One place per mapping, so a code reads the same on every
 * page. A code the app does not know comes back as null and the caller shows it as sentence-cased words.
 */

/** Why one account in a bulk change was skipped or failed. */
@StringRes
fun resultReasonRes(reason: String): Int? = when (reason) {
    "already_applied" -> R.string.organization_reason_already_applied
    "no_profiles" -> R.string.organization_reason_no_profiles
    "not_found" -> R.string.organization_reason_not_found
    "protected_owner" -> R.string.organization_reason_protected_owner
    "mutation_failed" -> R.string.organization_reason_mutation_failed
    "authorization_state_changed" -> R.string.organization_reason_authorization_changed
    "actor_authority_target" -> R.string.organization_reason_actor_authority
    "stale" -> R.string.organization_reason_stale
    "not_suspended" -> R.string.organization_reason_not_suspended
    else -> null
}

/** The heading of a finished or running bulk change, as the web words it. */
@StringRes
fun bulkHeadingRes(job: BulkJob): Int {
    val notChanged = job.skippedRecords.size + job.failedRecords.size
    if (job.status == "completed" && notChanged > 0) {
        return if (job.succeeded == 0L) R.string.organization_bulk_nothing_changed else R.string.organization_bulk_some_changed
    }
    return when (job.status) {
        "queued" -> R.string.organization_bulk_queued
        "running" -> R.string.organization_bulk_running
        "completed" -> R.string.organization_bulk_completed
        "failed" -> R.string.organization_bulk_failed
        "cancelled" -> R.string.organization_bulk_cancelled
        else -> R.string.organization_bulk_running
    }
}

/** What a check was about ("Library access check"); unknown actions read as sentence-cased words. */
@StringRes
fun accessActionRes(action: String): Int? = when (action) {
    "silo.permission.decision", "data.silo.permission.decision" -> R.string.organization_action_permission_check
    "silo.scope.decision", "data.silo.scope.decision", "library_access_check" -> R.string.organization_action_library_check
    "silo.action.decision", "data.silo.action.decision" -> R.string.organization_action_playback_check
    "data.silo.slow.decision" -> R.string.organization_action_check
    "playback_admission" -> R.string.organization_action_start_playback
    "direct_play" -> R.string.organization_action_play
    "transcode" -> R.string.organization_action_convert_video
    "audio_transcode" -> R.string.organization_action_convert_audio
    "download" -> R.string.organization_action_download
    "download_transcode" -> R.string.organization_action_download_converted
    else -> null
}

/** Why a check was refused. An empty code is "No reason recorded". */
@StringRes
fun accessReasonRes(code: String): Int? = when (code) {
    "" -> R.string.organization_access_reason_none
    "custom_denial" -> R.string.organization_access_reason_custom_denial
    "unknown_permission" -> R.string.organization_access_reason_unknown_permission
    "user_disabled" -> R.string.organization_access_reason_user_disabled
    "admin_role_required" -> R.string.organization_access_reason_admin_role
    "primary_profile_required" -> R.string.organization_access_reason_primary_profile
    "marker_edit_permission_required" -> R.string.organization_access_reason_marker_edit
    "metadata_curation_permission_required" -> R.string.organization_access_reason_metadata
    "item_outside_user_libraries" -> R.string.organization_access_reason_outside_libraries
    "unknown_action" -> R.string.organization_access_reason_unknown_action
    "downloads_disabled" -> R.string.organization_access_reason_downloads_disabled
    "download_permission_required" -> R.string.organization_access_reason_download_not_allowed
    "transcode_disabled", "transcoding_disabled" -> R.string.organization_access_reason_video_conversion_off
    "audio_transcoding_disabled" -> R.string.organization_access_reason_audio_conversion_off
    "download_transcode_permission_required" -> R.string.organization_access_reason_converted_download_not_allowed
    "download_artifacts_unavailable" -> R.string.organization_access_reason_no_downloadable_copy
    "quality_ceiling_exceeded" -> R.string.organization_access_reason_quality
    "content_rating_exceeded" -> R.string.organization_access_reason_content_rating
    "max_streams_exceeded" -> R.string.organization_access_reason_max_streams
    "max_transcodes_exceeded" -> R.string.organization_access_reason_max_transcodes
    else -> null
}

/** How a cohort came to be. */
@StringRes
fun derivationRes(kind: String): Int? = when (kind) {
    "exact_template" -> R.string.organization_derivation_exact_template
    "policy_patch" -> R.string.organization_derivation_policy_patch
    "managed_default" -> R.string.organization_derivation_managed_default
    else -> null
}

/** The best picture quality a policy allows, as the web words it. */
@StringRes
fun qualityRes(quality: String): Int? = when (quality) {
    "" -> R.string.organization_quality_any
    "original" -> R.string.organization_quality_original
    "1080p" -> R.string.organization_quality_1080
    "2160p" -> R.string.organization_quality_4k
    else -> null
}

/** A permission an access group or cohort can grant. */
@StringRes
fun permissionRes(permission: String): Int? = when (permission) {
    GroupPermissions.LIVE_TV -> R.string.organization_permission_live_tv
    GroupPermissions.METADATA -> R.string.organization_permission_metadata
    GroupPermissions.MARKERS -> R.string.organization_permission_markers
    else -> null
}

/** `people.bulk_job_created` is not a sentence; this is the fallback for any code the app has no words for. */
fun sentenceCase(code: String): String {
    val words = code.replace(Regex("[._]+"), " ").trim()
    return if (words.isEmpty()) code else words.replaceFirstChar { it.uppercase() }
}
