package com.bloemstudios.admin.organization

import kotlinx.serialization.Serializable

/*
 * Wire shapes of the organization routes that change several people or one access group, as the
 * server writes them (internal/adminpeople/service.go, internal/api/handlers/bloem_admin_people.go and
 * bloem_admin_organization.go). Unknown fields are ignored; strings the server may extend (status, reason)
 * are never matched against a closed set when decoding.
 */

// ---- People selections and bulk jobs ------------------------------------------------------------------

/**
 * POST /organization/people/selections: the server freezes everyone who matches the filter into an
 * immutable selection and answers with a signed token. The token is a bearer of that snapshot, so it is
 * kept in memory only and [toString] hides it.
 */
@Serializable
data class PeopleSelection(
    val token: String,
    val matched: Long = 0,
    val excluded: Long = 0,
    val expires_at: String = "",
) {
    override fun toString(): String = "PeopleSelection(matched=$matched, excluded=$excluded, token=<redacted>)"
}

@Serializable
data class PeopleSelectionResponse(val selection: PeopleSelection)

/** The filter a selection is made from. Empty lists stay in the body, like the web's. */
@Serializable
data class PeopleSelectionRequest(
    val query: String,
    val status: List<String>,
    val group_ids: List<Long>,
    val sort: String,
    val active_since: String? = null,
)

/** The three changes the server's bulk job supports. */
object BulkKind {
    const val ASSIGN_GROUP = "assign_group"
    const val SUSPEND = "suspend_memberships"
    const val REACTIVATE = "reactivate_memberships"
}

@Serializable
data class BulkJobRequest(val selection_token: String, val kind: String, val group_id: Long? = null) {
    override fun toString(): String = "BulkJobRequest(kind=$kind, group_id=$group_id, selection_token=<redacted>)"
}

/** One person a job did not change, and why (`already_applied`, `no_profiles`, `protected_owner`, ...). */
@Serializable
data class BulkRecordResult(val account_id: Long, val reason: String = "")

/** A job's progress. `queued` and `running` are not final; `completed`, `failed` and `cancelled` are. */
@Serializable
data class BulkJob(
    val job_id: String,
    val status: String,
    val progress_current: Long = 0,
    val progress_total: Long = 0,
    val succeeded: Long = 0,
    val skipped: List<BulkRecordResult>? = null,
    val failed: List<BulkRecordResult>? = null,
    val target_group_id: Long? = null,
) {
    val skippedRecords: List<BulkRecordResult> get() = skipped.orEmpty()
    val failedRecords: List<BulkRecordResult> get() = failed.orEmpty()
    val isFinished: Boolean get() = status == "completed" || status == "failed" || status == "cancelled"
}

@Serializable
data class BulkJobResponse(val job: BulkJob)

// ---- Access groups --------------------------------------------------------------------------------------

/** Creating a group needs only a name and the policy revision; the rest starts at the server's defaults. */
@Serializable
data class AccessGroupCreateBody(val expected_revision: Long, val name: String)

/**
 * PUT /organization/groups/{id}: the whole group. `library_ids` and `allowed_permissions` have no
 * default so a `null` is always written: `null` means every library / every permission, and leaving the
 * field out would keep the old value instead.
 */
@Serializable
data class AccessGroupWrite(
    val expected_revision: Long,
    val name: String,
    val description: String,
    val library_ids: List<String>?,
    val max_playback_quality: String,
    val download_allowed: Boolean,
    val download_transcode_allowed: Boolean,
    val transcode_allowed: Boolean,
    val audio_transcode_allowed: Boolean,
    val max_streams: Long,
    val max_transcodes: Long,
    val max_remote_stream_bitrate_kbps: Long,
    val max_local_stream_bitrate_kbps: Long,
    val max_profiles: Long,
    val allowed_permissions: List<String>?,
    val requests_allowed: Boolean,
    val is_default: Boolean,
)

@Serializable
data class OrganizationGroupResponse(val group: AccessGroup)

@Serializable
data class OrganizationGroupMutationResponse(val group: AccessGroup, val policy_revision: Long = 0)

/** What deleting a group did: its profiles moved to the default group. */
@Serializable
data class GroupDeletionImpact(val profiles_reassigned: Long = 0, val default_group_id: Long = 0)

/** The permissions an organization group can grant, as the server names them. */
object GroupPermissions {
    const val LIVE_TV = "watch_live_tv"
    const val METADATA = "metadata_curation"
    const val MARKERS = "marker_edit"
}

/** The group as an editable draft, and back to the body the server takes. */
data class AccessGroupDraft(
    val name: String,
    val description: String,
    /** `null` means every library the organization can use. */
    val libraryIds: Set<String>?,
    val maxPlaybackQuality: String,
    val downloadAllowed: Boolean,
    val downloadTranscodeAllowed: Boolean,
    val transcodeAllowed: Boolean,
    val audioTranscodeAllowed: Boolean,
    val maxStreams: Long,
    val maxTranscodes: Long,
    /** `null` means every assignable permission. */
    val allowedPermissions: Set<String>?,
    val requestsAllowed: Boolean,
    val isDefault: Boolean,
    // Not shown in the editor, but written back unchanged so a save never resets them.
    val maxRemoteBitrateKbps: Long,
    val maxLocalBitrateKbps: Long,
    val maxProfiles: Long,
) {
    fun toBody(revision: Long): AccessGroupWrite = AccessGroupWrite(
        expected_revision = revision, name = name.trim(), description = description.trim(),
        library_ids = libraryIds?.sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE },
        max_playback_quality = maxPlaybackQuality,
        download_allowed = downloadAllowed,
        // Converted downloads need downloads: never save the contradictory pair.
        download_transcode_allowed = downloadAllowed && downloadTranscodeAllowed,
        transcode_allowed = transcodeAllowed, audio_transcode_allowed = audioTranscodeAllowed,
        max_streams = maxStreams, max_transcodes = maxTranscodes,
        max_remote_stream_bitrate_kbps = maxRemoteBitrateKbps, max_local_stream_bitrate_kbps = maxLocalBitrateKbps,
        max_profiles = maxProfiles,
        allowed_permissions = allowedPermissions?.sorted(),
        requests_allowed = requestsAllowed, is_default = isDefault,
    )

    companion object {
        fun of(group: AccessGroup) = AccessGroupDraft(
            name = group.name, description = group.description, libraryIds = group.library_ids?.toSet(),
            maxPlaybackQuality = group.max_playback_quality, downloadAllowed = group.download_allowed,
            downloadTranscodeAllowed = group.download_transcode_allowed, transcodeAllowed = group.transcode_allowed,
            audioTranscodeAllowed = group.audio_transcode_allowed, maxStreams = group.max_streams,
            maxTranscodes = group.max_transcodes, allowedPermissions = group.allowed_permissions?.toSet(),
            requestsAllowed = group.requests_allowed, isDefault = group.is_default,
            maxRemoteBitrateKbps = group.max_remote_stream_bitrate_kbps, maxLocalBitrateKbps = group.max_local_stream_bitrate_kbps,
            maxProfiles = group.max_profiles,
        )
    }
}

/**
 * The picture-quality choices the web offers an access group: any, up to 1080p, up to 4K. The server
 * stores `""`, `1080p` and `2160p`; anything else it sent (for example `original`) is kept as its own
 * choice until the administrator picks another.
 */
object PlaybackQuality {
    const val ANY = ""
    const val UP_TO_1080P = "1080p"
    const val UP_TO_4K = "2160p"
    val choices = listOf(ANY, UP_TO_1080P, UP_TO_4K)

    /** The web's own reading of a stored value (lib/playback-quality.ts `canonicalPlaybackQuality`). */
    fun canonical(value: String?): String = when ((value ?: "").trim().lowercase()) {
        "", "any" -> ANY
        "standard", "480p", "720p", "1080p" -> UP_TO_1080P
        "4k", "uhd", "2160p", "4320p" -> UP_TO_4K
        else -> ANY
    }
}
