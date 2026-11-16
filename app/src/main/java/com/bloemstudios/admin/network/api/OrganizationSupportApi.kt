package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.NoBody
import com.bloemstudios.admin.organization.ProfileSummary
import com.bloemstudios.admin.organization.PlatformOrganizationsPage
import com.bloemstudios.admin.organization.RevisionBody
import com.bloemstudios.admin.organization.SupportCapabilities
import com.bloemstudios.admin.organization.SupportDevice
import com.bloemstudios.admin.organization.SupportDevicesResponse
import com.bloemstudios.admin.organization.SupportProfileCreate
import com.bloemstudios.admin.organization.SupportProfileOnlyResponse
import com.bloemstudios.admin.organization.SupportProfileRename
import com.bloemstudios.admin.organization.SupportProfileResponse
import com.bloemstudios.admin.organization.SupportResetRequest
import com.bloemstudios.admin.organization.SupportResetResponse
import com.bloemstudios.admin.organization.SupportSession
import com.bloemstudios.admin.organization.SupportSessionsResponse
import com.bloemstudios.admin.organization.SupportSignOutResponse
import com.bloemstudios.admin.organization.SupportStreamMessage
import com.bloemstudios.admin.organization.SupportStreamMessageReceipt
import com.bloemstudios.admin.organization.SupportStreamStop
import com.bloemstudios.admin.organization.SupportStreamStopResult
import com.bloemstudios.admin.organization.SupportStreamsPage
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

/*
 * The organization support routes: help a person get back in, sign them out, fix their profiles, see and stop the
 * organization's streams, and the Owner's break-glass access. Same rules as OrganizationApi.kt: the bearer is the
 * context pass, the organization is never named in a path, and every write is sent once (replayUnauthorized =
 * false), so a pass the server refused is renewed by OrganizationSession and the administrator decides whether to
 * ask again. No call here ever asks for, or receives, a client address.
 */

private const val ORGANIZATION = "/api/bloem/v2/admin/organization"

private fun person(accountId: Long) = "$ORGANIZATION/people/$accountId"
private fun profile(accountId: Long, profileId: String) = "${person(accountId)}/profiles/${profileId.encodeURLPathPart()}"

/** Context pass. What this server can do for support; a 404 means it has none of it. */
suspend fun ApiClient.getSupportCapabilities(): SupportCapabilities = get("$ORGANIZATION/support-capabilities")

/**
 * Context pass. Issues a reset link: `email` sends it to the member, `link` returns it once in the answer.
 * [revision] is the person's `security_revision`; the reset does not raise it. Sent once, since the link is shown once.
 */
suspend fun ApiClient.createSupportPasswordReset(accountId: Long, delivery: String, revision: Long): SupportResetResponse =
    request<SupportResetResponse, SupportResetRequest>(
        "${person(accountId)}/password-reset", HttpMethod.Post, SupportResetRequest(revision, delivery), replayUnauthorized = false,
    )

/** Context pass. The person's sign-ins, newest first as the server orders them. */
suspend fun ApiClient.listSupportSessions(accountId: Long): List<SupportSession> =
    get<SupportSessionsResponse>("${person(accountId)}/sessions").sessions

/** Context pass. Signs the person out of everything; [revision] is their `security_revision`, which this raises. */
suspend fun ApiClient.signOutSupportEverywhere(accountId: Long, revision: Long): SupportSignOutResponse =
    request<SupportSignOutResponse, RevisionBody>(
        "${person(accountId)}/sessions", HttpMethod.Delete, RevisionBody(revision), replayUnauthorized = false,
    )

/** Context pass. Signs the person out of one session. The answer is 204. */
suspend fun ApiClient.signOutSupportSession(accountId: Long, sessionId: String) {
    request<Unit, NoBody>(
        "${person(accountId)}/sessions/${sessionId.encodeURLPathPart()}", HttpMethod.Delete,
        NoBody, replayUnauthorized = false,
    )
}

/** Context pass. The devices the person is signed in on, with the profiles used on each. */
suspend fun ApiClient.listSupportDevices(accountId: Long): List<SupportDevice> =
    get<SupportDevicesResponse>("${person(accountId)}/devices").devices

/** Context pass. Signs the person out of one device. The answer is 204. */
suspend fun ApiClient.signOutSupportDevice(accountId: Long, deviceId: String) {
    request<Unit, NoBody>(
        "${person(accountId)}/devices/${deviceId.encodeURLPathPart()}", HttpMethod.Delete,
        NoBody, replayUnauthorized = false,
    )
}

/** Context pass. Adds a profile to the person's account, in [groupId] or the default group. */
suspend fun ApiClient.addSupportProfile(accountId: Long, name: String, groupId: Long?): SupportProfileResponse =
    request<SupportProfileResponse, SupportProfileCreate>(
        "${person(accountId)}/profiles", HttpMethod.Post, SupportProfileCreate(name.trim(), groupId), replayUnauthorized = false,
    )

/** Context pass. Renames a profile; [revision] is the profile's own `revision`. */
suspend fun ApiClient.renameSupportProfile(accountId: Long, profileId: String, name: String, revision: Long): SupportProfileResponse =
    request<SupportProfileResponse, SupportProfileRename>(
        "${profile(accountId, profileId)}/details", HttpMethod.Patch, SupportProfileRename(revision, name.trim()), replayUnauthorized = false,
    )

/** Context pass. Clears a profile's PIN (the member chooses a new one); [revision] is the profile's `revision`. */
suspend fun ApiClient.clearSupportProfilePin(accountId: Long, profileId: String, revision: Long): ProfileSummary =
    request<SupportProfileOnlyResponse, RevisionBody>(
        "${profile(accountId, profileId)}/pin", HttpMethod.Delete, RevisionBody(revision), replayUnauthorized = false,
    ).profile

/** Context pass. Removes a profile; [revision] is the profile's `revision`. The answer is 204. */
suspend fun ApiClient.removeSupportProfile(accountId: Long, profileId: String, revision: Long) {
    request<Unit, RevisionBody>(
        profile(accountId, profileId), HttpMethod.Delete, RevisionBody(revision), replayUnauthorized = false,
    )
}

/** Context pass. One page of the organization's live streams. The list is polled: there is no realtime channel. */
suspend fun ApiClient.listSupportStreams(cursor: String?, limit: Int = 100): SupportStreamsPage =
    get("$ORGANIZATION/streams", mapOf("cursor" to cursor, "limit" to limit.toString()))

/** Context pass. Ends a stream and revokes its playback authority. [reason] is optional (up to 1024 characters). */
suspend fun ApiClient.stopSupportStream(sessionId: String, reason: String? = null): SupportStreamStopResult =
    postOnce("$ORGANIZATION/streams/${sessionId.encodeURLPathPart()}/stop", SupportStreamStop(reason?.trim()?.ifEmpty { null }))

/** Context pass. Puts a message on the stream's screen; the command identity is the same as Silo's (applied once per session). */
suspend fun ApiClient.messageSupportStream(sessionId: String, title: String?, message: String, commandId: String, sequence: Long): SupportStreamMessageReceipt =
    postOnce(
        "$ORGANIZATION/streams/${sessionId.encodeURLPathPart()}/message",
        SupportStreamMessage(commandId, sequence, message.trim(), title?.trim()?.ifEmpty { null }),
    )

/** Context pass opened under support access. Ends that access, and with it the pass. The answer is 204. */
suspend fun ApiClient.endSupportAccess() {
    request<Unit, NoBody>(
        "$ORGANIZATION/support-access", HttpMethod.Delete, NoBody, replayUnauthorized = false,
    )
}

/**
 * Platform pass. Organizations by name: the Platform › Organizations list and the Owner's choice of one to open under support
 * access. [status] is `active`, `suspended` or `initializing`; null lists every status.
 */
suspend fun ApiClient.listPlatformOrganizations(query: String?, cursor: String?, limit: Int = 50, status: String? = null): PlatformOrganizationsPage =
    get(
        "/api/bloem/v2/admin/platform/organizations/",
        mapOf("query" to query?.trim()?.ifEmpty { null }, "cursor" to cursor, "limit" to limit.toString(), "status" to status),
    )

/** No account token or pass: the server's public feature list. `organization_support_v1` says it has the support routes. */
suspend fun ApiClient.getBloemFeatureTokens(): List<String> =
    get<BloemCapabilities>("/api/bloem/v2/capabilities").feature_tokens

@Serializable
private data class BloemCapabilities(val feature_tokens: List<String> = emptyList())

/** The public token the server advertises when it has the organization support routes. */
const val ORGANIZATION_SUPPORT_TOKEN = "organization_support_v1"
