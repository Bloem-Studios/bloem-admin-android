package com.bloemstudios.admin.network

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bloemstudios.admin.R

/**
 * Carries a user-facing error message without requiring the ViewModel that
 * produced it to have a Context/Resources. Two cases:
 *  - [Raw]: a sentence that already came from the server (ApiError.serverSentence)
 *    -- genuinely dynamic text, not worth (or possible) to localize from a
 *    resource file, so it's carried verbatim.
 *  - [Res]: a client-side fallback for when the request never got a
 *    structured server response at all (network failure, decode failure,
 *    etc). Only a resource *id* (a plain Int constant) is stored -- no
 *    Context/Application/Robolectric needed to produce it, unlike calling
 *    Resources.getString() from a ViewModel. [resolve] turns it into text at
 *    the Composable layer, where stringResource() is available.
 */
sealed class ErrorText {
    data class Raw(val message: String) : ErrorText()
    data class Res(@StringRes val id: Int) : ErrorText()

    /** The screen's own fallback plus the HTTP status, for a failure whose body held no sentence. */
    data class Http(@StringRes val id: Int, val status: Int) : ErrorText()

    /** An aggregate view's error for one server: resolves as "<prefix>: <inner>". */
    data class Prefixed(val prefix: String, val inner: ErrorText) : ErrorText()
}

@Composable
fun ErrorText.resolve(): String = when (this) {
    is ErrorText.Raw -> message
    is ErrorText.Res -> stringResource(id)
    is ErrorText.Http -> stringResource(R.string.error_with_http_status, stringResource(id).withoutClosingPeriod(), status)
    is ErrorText.Prefixed -> "$prefix: ${inner.resolve()}"
}

/** "Couldn't load tasks." becomes "Couldn't load tasks", ready for a status in brackets. */
fun String.withoutClosingPeriod(): String = trimEnd().removeSuffix(".")

/**
 * The one place an ApiError becomes user-facing text. The server's text is shown only when it is a real sentence;
 * otherwise the screen's own [fallback] and the HTTP status are shown. Upgrade tombstones get a resource string.
 */
fun ApiError.toErrorText(@StringRes fallback: Int): ErrorText = when {
    isClientUpgradeRequired -> ErrorText.Res(R.string.error_client_upgrade_required)
    else -> serverSentence?.let { ErrorText.Raw(it) } ?: ErrorText.Http(fallback, statusCode)
}

/** The aggregate views' one way to name the server an error came from: "<serverName>: <text>". */
fun ApiError.toErrorText(serverName: String, @StringRes fallback: Int): ErrorText =
    ErrorText.Prefixed(serverName, toErrorText(fallback))
