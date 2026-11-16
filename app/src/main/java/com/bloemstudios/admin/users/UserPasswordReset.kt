package com.bloemstudios.admin.users

import androidx.annotation.StringRes
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.network.ApiError
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.network.api.requestPasswordReset
import com.bloemstudios.admin.network.toErrorText
import com.bloemstudios.admin.users.detail.AdminUserCapabilities
import kotlinx.coroutines.CancellationException

/** How a reset reaches the account holder: the server mails the link, or the admin is handed it to pass on. */
internal enum class ResetChoice(val delivery: String) { Email("email"), Link("link") }

internal sealed interface ResetChoiceState {
    data object Available : ResetChoiceState

    /** Why the option is closed, as a string resource. */
    data class Unavailable(@StringRes val reason: Int) : ResetChoiceState
}

internal data class ResetOptions(val email: ResetChoiceState, val link: ResetChoiceState) {
    val anyAvailable: Boolean get() = email is ResetChoiceState.Available || link is ResetChoiceState.Available
}

/**
 * What the Reset password dialog offers for [user], from what the server says it can do. A link needs
 * `password_reset_link` (the server's public address) and an account that is turned on; email also needs an address
 * and `password_reset_email`. With no capabilities read, nothing is offered.
 */
internal fun resetOptions(user: AdminUser, capabilities: AdminUserCapabilities?): ResetOptions {
    if (capabilities == null) return ResetOptions(
        ResetChoiceState.Unavailable(R.string.user_reset_none), ResetChoiceState.Unavailable(R.string.user_reset_none),
    )
    if (!user.enabled) return ResetOptions(
        ResetChoiceState.Unavailable(R.string.user_reset_disabled), ResetChoiceState.Unavailable(R.string.user_reset_disabled),
    )
    val email = when {
        !capabilities.password_reset_email -> ResetChoiceState.Unavailable(R.string.user_reset_email_off)
        user.email.isBlank() -> ResetChoiceState.Unavailable(R.string.user_reset_email_missing)
        else -> ResetChoiceState.Available
    }
    val link = if (capabilities.password_reset_link) ResetChoiceState.Available else ResetChoiceState.Unavailable(R.string.user_reset_link_off)
    return ResetOptions(email, link)
}

/** A one-time reset link. Held in memory only while it is shown; [toString] leaves the URL out, so it cannot reach a log. */
internal class IssuedResetLink(val url: String, val expiresAt: String) {
    override fun toString(): String = "IssuedResetLink(expiresAt=$expiresAt, url=<redacted>)"
}

internal sealed interface ResetOutcome {
    data class EmailSent(val expiresAt: String) : ResetOutcome
    data class EmailUnconfirmed(val expiresAt: String) : ResetOutcome
    data class LinkIssued(val link: IssuedResetLink) : ResetOutcome
    data class Failed(val message: ErrorText) : ResetOutcome

    /** The answer could not be read: the reset may exist, so the admin is told to check before asking again. */
    data object Uncertain : ResetOutcome
}

/** Asks the server for one reset, once: an answer that cannot be read is [ResetOutcome.Uncertain], never retried. */
internal suspend fun issuePasswordReset(client: ApiClient, user: AdminUser, choice: ResetChoice): ResetOutcome = try {
    val receipt = client.requestPasswordReset(user.id, choice.delivery)
    when (choice) {
        ResetChoice.Email ->
            if (receipt.delivery_status == "sent") ResetOutcome.EmailSent(receipt.expires_at) else ResetOutcome.EmailUnconfirmed(receipt.expires_at)
        ResetChoice.Link ->
            receipt.reset_url?.takeIf { it.isNotBlank() }?.let { ResetOutcome.LinkIssued(IssuedResetLink(it, receipt.expires_at)) }
                ?: ResetOutcome.Failed(ErrorText.Res(R.string.user_reset_link_missing))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: ApiError) {
    ResetOutcome.Failed(e.toErrorText(if (choice == ResetChoice.Link) R.string.user_reset_link_failed else R.string.users_reset_failed))
} catch (_: Exception) {
    ResetOutcome.Uncertain
}
