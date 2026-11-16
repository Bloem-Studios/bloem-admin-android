package com.bloemstudios.admin.users

import androidx.annotation.StringRes
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.getUserWithValidator
import com.bloemstudios.admin.network.toErrorText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The fresh read of a user came back without an ETag, so no guarded write can be made. */
internal class UserVersionMissingException : Exception("The server returned no ETag for the user")

/** A body that changes only whether the account is enabled. */
internal fun enabledBody(enabled: Boolean): JsonObject = buildJsonObject { put("enabled", enabled) }

/**
 * A body that sets the account's password. The server also turns password sign-in back on, and with
 * [requireChange] the account must choose its own password at its next sign-in.
 */
internal fun passwordBody(password: String, requireChange: Boolean): JsonObject = buildJsonObject {
    put("password", password)
    if (requireChange) put("require_password_change", true)
}

/**
 * Reads the user fresh and hands its ETag to [write]. A missing ETag throws [UserVersionMissingException]
 * before anything is sent.
 */
internal suspend fun ApiClient.withFreshUserVersion(id: String, write: suspend (validator: String) -> Unit) {
    val validator = getUserWithValidator(id).second ?: throw UserVersionMissingException()
    write(validator)
}

/** 412 (stale ETag) and 428 (missing precondition) both mean the user must be reviewed again. */
internal fun Throwable.isUserConflict(): Boolean = this is ApiError && (statusCode == 412 || statusCode == 428)

/**
 * User-facing text for a failed user write. [serverName] is set by the aggregate view, which names the server
 * on version, conflict and server-sent texts; a failure that never reached the server keeps its plain resource.
 */
internal fun userWriteFailure(error: Exception, @StringRes fallback: Int, serverName: String? = null): ErrorText {
    fun named(text: ErrorText) = if (serverName == null) text else ErrorText.Prefixed(serverName, text)
    return when {
        error is UserVersionMissingException -> named(ErrorText.Res(R.string.users_missing_version))
        error.isUserConflict() -> named(ErrorText.Res(R.string.users_conflict))
        error is ApiError -> if (serverName == null) error.toErrorText(fallback) else error.toErrorText(serverName, fallback)
        else -> ErrorText.Res(fallback)
    }
}

/** What an edit dialog opens with: the user as the server has it now, and the ETag of that read (null when none was sent). */
internal sealed interface UserEditLoad {
    data class Ready(val user: AdminUser, val validator: String?) : UserEditLoad
    /** [notFound] means the server answered 404: the user was deleted after the list was read. */
    data class Failed(val message: ErrorText, val notFound: Boolean = false) : UserEditLoad
}

/** Reads [id] fresh for an edit dialog. The returned validator travels with the dialog's draft and is used by its save. */
internal suspend fun ApiClient.readUserForEdit(id: String, serverName: String? = null): UserEditLoad = try {
    val (user, validator) = getUserWithValidator(id)
    UserEditLoad.Ready(user, validator)
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    UserEditLoad.Failed(userWriteFailure(e, R.string.error_could_not_load_user, serverName), notFound = e is ApiError && e.statusCode == 404)
}
