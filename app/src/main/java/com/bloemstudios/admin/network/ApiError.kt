package com.bloemstudios.admin.network

class ApiError(
    val statusCode: Int,
    val code: String?,
    override val message: String,
    val retryAfter: String? = null,
    /** X-Silo-Latest-Sequence on a 409: the session's latest applied command sequence (a stale refusal). */
    val latestSequence: Long? = null,
    /** The server's machine reason beside the code, for example `target_protected` carries `self` or `organization_owner`. */
    val reason: String? = null
) : Exception(message) {
    constructor(statusCode: Int, message: String) : this(statusCode, null, message, null)
}

/** What [ApiError.message] holds when the response carried neither a message nor a code. */
const val DEFAULT_ERROR_MESSAGE = "Request failed"

/**
 * The server's own words, when they read as a sentence: more than one word, and not the client's placeholder or the
 * machine code. A bare code such as `forbidden` or `internal_error` is not a message for a person.
 */
val ApiError.serverSentence: String?
    get() = message.trim().takeIf { it != DEFAULT_ERROR_MESSAGE && it != code && it.any(Char::isWhitespace) }

/** The server has retired the API this build speaks and answers with a tombstone. */
val ApiError.isClientUpgradeRequired: Boolean
    get() = statusCode == 410 && code == CLIENT_UPGRADE_REQUIRED_CODE

const val CLIENT_UPGRADE_REQUIRED_CODE = "client_upgrade_required"
