package com.bloemstudios.admin.activity

/** Which plays to list: finished, broken off, or both. [wire] is the server's `completed` value; null leaves it out. */
enum class CompletionFilter(val wire: String?) {
    All(null),
    Completed("true"),
    Partial("false"),
}

/**
 * The playback history filters: an account, one of its profiles, and whether the play finished. Ids are the server's
 * opaque strings. A profile only makes sense with its account, so the screen clears it when the account changes.
 */
data class HistoryFilter(
    val userId: String? = null,
    val profileId: String? = null,
    val completion: CompletionFilter = CompletionFilter.All,
) {
    val isEmpty: Boolean get() = userId == null && profileId == null && completion == CompletionFilter.All

    /** Blank ids count as unset, so two spellings of "no filter" are the same filter. */
    fun normalized(): HistoryFilter = copy(
        userId = userId?.trim()?.takeIf(String::isNotEmpty),
        profileId = profileId?.trim()?.takeIf(String::isNotEmpty),
    )
}
