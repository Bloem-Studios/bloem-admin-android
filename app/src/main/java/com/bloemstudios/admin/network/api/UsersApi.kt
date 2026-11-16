package com.bloemstudios.admin.network.api

import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.NoBody
import com.bloemstudios.admin.streams.AdminSession
import com.bloemstudios.admin.users.AdminUser
import com.bloemstudios.admin.users.CreateUserRequest
import com.bloemstudios.admin.users.LoginSession
import com.bloemstudios.admin.users.PasswordResetCapabilities
import com.bloemstudios.admin.users.PasswordResetReceipt
import com.bloemstudios.admin.users.PasswordResetRequest
import com.bloemstudios.admin.users.UserDevice
import com.bloemstudios.admin.users.detail.AccessGroup
import com.bloemstudios.admin.users.detail.AccountMe
import com.bloemstudios.admin.users.detail.AdminUserCapabilities
import com.bloemstudios.admin.users.detail.UserDownload
import com.bloemstudios.admin.users.detail.UserDownloadSummary
import com.bloemstudios.admin.users.detail.SettingIdentity
import com.bloemstudios.admin.users.detail.SettingsContract
import com.bloemstudios.admin.users.detail.UserIp
import com.bloemstudios.admin.users.detail.UserPolicyDefaults
import com.bloemstudios.admin.users.detail.UserProfile
import com.bloemstudios.admin.users.detail.UserSettingEntry
import com.bloemstudios.admin.users.detail.WatchSummary
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun userPath(id: String, suffix: String = "") = "/api/v2/admin/users/${id.encodeURLPathPart()}$suffix"

/** AdminLoginSessionsRevoked. */
@Serializable
private data class LoginSessionsRevoked(val revoked: Int)

/** Every page of the user list. */
suspend fun ApiClient.listUsers(): List<AdminUser> = readAllPages("/api/v2/admin/users")

/** The user and its ETag; the validator is null when the server sent none. */
suspend fun ApiClient.getUserWithValidator(id: String): Pair<AdminUser, String?> = getWithValidator(userPath(id))

/** Creates the account with a default profile. The created body is not needed. */
suspend fun ApiClient.createUser(request: CreateUserRequest) {
    post<Unit, CreateUserRequest>("/api/v2/admin/users", request)
}

/** Sends only the fields in [body]; rejected with 412/428 when [validator] no longer matches. Never replayed after a 401. */
suspend fun ApiClient.updateUser(id: String, body: JsonObject, validator: String) {
    putConditional<Unit, JsonObject>(userPath(id), body, validator)
}

/** Rejected with 412/428 when [validator] no longer matches. Never replayed after a 401. */
suspend fun ApiClient.deleteUser(id: String, validator: String) = deleteConditional(userPath(id), validator)

/** Every page of the user's login sessions. */
suspend fun ApiClient.listLoginSessions(userId: String): List<LoginSession> =
    readAllPages(userPath(userId, "/login-sessions"))

suspend fun ApiClient.revokeLoginSession(userId: String, sessionId: String) =
    delete(userPath(userId, "/login-sessions/${sessionId.encodeURLPathPart()}"))

/** Revokes every login session of the user, including the caller's own when it is that user. Returns how many were revoked. */
suspend fun ApiClient.revokeAllLoginSessions(userId: String): Int =
    request<LoginSessionsRevoked, NoBody>(userPath(userId, "/login-sessions"), HttpMethod.Delete, NoBody).revoked

/** The device list is not paginated, so it is read without cursor parameters. */
suspend fun ApiClient.listUserDevices(userId: String): List<UserDevice> =
    getCollection<UserDevice>(userPath(userId, "/devices")).items

suspend fun ApiClient.passwordResetCapabilities(): PasswordResetCapabilities = get("/api/v2/admin/users/capabilities")

/**
 * One submission: a reset link whose outcome is uncertain must not be issued twice. [delivery] is `email` (the server
 * mails the link) or `link` (the answer carries it, once, for the admin to hand over).
 */
suspend fun ApiClient.requestPasswordReset(userId: String, delivery: String = "email"): PasswordResetReceipt =
    postOnce(userPath(userId, "/password-reset"), PasswordResetRequest(delivery))

/** The signed-in account; the User page needs its id to hide actions an admin cannot take on themselves. */
suspend fun ApiClient.getAccountMe(): AccountMe = get("/api/v2/account/me")

/** What the server supports for account administration. */
suspend fun ApiClient.getUserCapabilities(): AdminUserCapabilities = get("/api/v2/admin/users/capabilities")

/** The user's profiles; the list is not paginated. */
suspend fun ApiClient.listUserProfiles(userId: String): List<UserProfile> =
    getCollection<UserProfile>(userPath(userId, "/profiles")).items

/** Adds a profile to the account; a name another profile uses, or a full household, is refused with 409. */
suspend fun ApiClient.createUserProfile(userId: String, name: String, pin: String? = null) {
    post<Unit, JsonObject>(userPath(userId, "/profiles"), buildJsonObject {
        put("name", name)
        if (pin != null) put("pin", pin)
    })
}

private fun profilePath(userId: String, profileId: String) = userPath(userId, "/profiles/${profileId.encodeURLPathPart()}")

/** Renames the profile; every member left out of the body is left as it was. */
suspend fun ApiClient.renameUserProfile(userId: String, profileId: String, name: String) {
    patch<Unit, JsonObject>(profilePath(userId, profileId), buildJsonObject { put("name", name) })
}

/** Sets or replaces the profile's PIN; the server ends the profile tokens issued for the old one. */
suspend fun ApiClient.setUserProfilePin(userId: String, profileId: String, pin: String) {
    patch<Unit, JsonObject>(profilePath(userId, profileId), buildJsonObject { put("pin", pin) })
}

/** Removes the profile's PIN: the body carries `"pin": null`, which clears it (an empty string is refused). */
suspend fun ApiClient.removeUserProfilePin(userId: String, profileId: String) {
    patch<Unit, JsonObject>(profilePath(userId, profileId), buildJsonObject { put("pin", JsonNull) })
}

/** Deletes the profile and what it saved; the primary profile is refused with 409. */
suspend fun ApiClient.deleteUserProfile(userId: String, profileId: String) = delete(profilePath(userId, profileId))

/** What the account is playing right now, from the live session list filtered to the account. */
suspend fun ApiClient.listUserLiveSessions(userId: String): List<AdminSession> =
    readAllPages("/api/v2/admin/sessions", mapOf("user_id" to userId))

/** Plays and watch time over the last [days] days (1 to 365). */
suspend fun ApiClient.getUserWatchSummary(userId: String, days: Int = 30): WatchSummary =
    get(userPath(userId, "/watch-summary"), mapOf("days" to days.toString()))

/** One page of the addresses the user signed in from over the last [days] days (1 to 365), newest first. */
suspend fun ApiClient.listUserIps(userId: String, days: Int = 30, limit: Int = 50): List<UserIp> =
    getCollection<UserIp>(userPath(userId, "/ips"), mapOf("days" to days.toString(), "limit" to limit.toString())).items

/** One page of the user's downloads. [cursor] is the previous page's `next_cursor`; null asks for the first page. */
suspend fun ApiClient.listUserDownloads(userId: String, cursor: String?, limit: Int = 50): ApiCollection<UserDownload> =
    getCollection(userPath(userId, "/downloads"), mapOf("cursor" to cursor, "limit" to limit.toString()))

suspend fun ApiClient.getUserDownloadSummary(userId: String): UserDownloadSummary =
    get(userPath(userId, "/downloads/summary"))

/** Every access group, for the account's group picker and for what a group allows. */
suspend fun ApiClient.listAccessGroups(): List<AccessGroup> = readAllPages("/api/v2/admin/access-groups")

/** Every setting the account's people saved, at every scope. */
suspend fun ApiClient.listUserSettingValues(userId: String): List<UserSettingEntry> =
    readAllPages(userPath(userId, "/settings/values"))

private fun settingPath(userId: String, key: String, identity: SettingIdentity, client: ApiClient): String =
    client.appendQuery(userPath(userId, "/settings/values/${key.encodeURLPathPart()}"), identity.query())

/** Stores [value] for [key] at exactly [identity]; the server checks it against the setting's own schema. */
suspend fun ApiClient.setUserSettingValue(userId: String, key: String, identity: SettingIdentity, value: JsonElement) {
    put<Unit, JsonObject>(settingPath(userId, key, identity, this), buildJsonObject { put("value", value) })
}

/** Removes the row at exactly [identity], so the setting falls back to the level below it. */
suspend fun ApiClient.deleteUserSettingValue(userId: String, key: String, identity: SettingIdentity) =
    delete(settingPath(userId, key, identity, this))

/** What unset policy fields resolve to for an admin and for an account without a group. */
suspend fun ApiClient.getUserPolicyDefaults(): UserPolicyDefaults = get("/api/v2/admin/users/policy-defaults")

/** The settings contract: every setting's type, scopes, default and wording. Public, but read with the admin's token like the rest. */
suspend fun ApiClient.getSettingsContract(): SettingsContract = get("/api/v2/settings/contract")
