package com.bloemstudios.admin.organization

import androidx.annotation.StringRes
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.toErrorText
import kotlinx.coroutines.CancellationException

/** What an organization call came to: a value, or text to show. */
internal sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val message: ErrorText) : Outcome<Nothing>
}

/**
 * The one place an organization failure becomes text. A refusal of the organization itself (403, or
 * [OrganizationAccessDenied]) also records why on [OrganizationSession.denial] so the host can leave and
 * the context list can drop the organization: a suspended organization reads differently from lost authority.
 */
internal fun OrganizationSession.failureText(
    error: Throwable,
    @StringRes fallback: Int,
    /** Shown for a 422, whose server text is a generic "validation failed". */
    @StringRes refused: Int? = null,
): ErrorText = when {
    error is OrganizationAccessDenied || (error is ApiError && error.statusCode == 403) -> {
        val reason = (error as? OrganizationAccessDenied)?.reason ?: (error as ApiError).denialReason()
        markDenied(reason)
        ErrorText.Res(reason.messageRes())
    }
    error is OrganizationStateChanged ->
        ErrorText.Res(if (error.wasConflict) R.string.organization_changed_conflict else R.string.organization_changed_reloaded)
    // A refused person (not the organization): say who is protected, in the app's words, not the server's generic one.
    error is ApiError && error.statusCode == 409 && error.code == "target_protected" -> ErrorText.Res(protectedReasonRes(error.reason))
    error is ApiError && error.statusCode == 422 && refused != null -> ErrorText.Res(refused)
    error is ApiError -> error.toErrorText(fallback)
    else -> ErrorText.Res(fallback)
}

/** Runs [block]; cancellation propagates, anything else becomes [Outcome.Failed]. */
internal suspend fun <T> OrganizationSession.attempt(
    @StringRes fallback: Int,
    @StringRes refused: Int? = null,
    block: suspend () -> T,
): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Outcome.Failed(failureText(error, fallback, refused))
    }


/** What the administrator is told for each reason; the web says the same in its context chooser. */
@StringRes
internal fun OrganizationDenial.messageRes(): Int = when (this) {
    OrganizationDenial.Suspended -> R.string.organization_suspended
    OrganizationDenial.LostAuthority -> R.string.organization_lost_authority
    OrganizationDenial.NotFound -> R.string.organization_no_access
    OrganizationDenial.SupportEnded -> R.string.organization_support_ended
}

/** Why the server will not let this administrator help this person (`target_protected`). */
@StringRes
internal fun protectedReasonRes(reason: String?): Int = when (reason) {
    "self" -> R.string.organization_support_protected_self
    "platform_account" -> R.string.organization_support_protected_platform
    "organization_owner" -> R.string.organization_support_protected_owner
    "organization_admin" -> R.string.organization_support_protected_admin
    else -> R.string.organization_support_error_protected
}
