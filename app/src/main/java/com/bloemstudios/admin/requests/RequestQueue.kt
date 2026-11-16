package com.bloemstudios.admin.requests

import android.content.res.Resources
import android.icu.text.RelativeDateTimeFormatter
import androidx.annotation.StringRes
import com.bloemstudios.admin.R
import com.bloemstudios.admin.network.api.RequestAction
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The web admin's request queue views, in tab order (`requestQueueModel.ts`). */
enum class RequestQueueView(val wire: String, @StringRes val label: Int, @StringRes val empty: Int) {
    NeedsApproval("needs_approval", R.string.requests_view_needs_approval, R.string.requests_view_needs_approval_empty),
    InProgress("in_progress", R.string.requests_view_in_progress, R.string.requests_view_in_progress_empty),
    Failed("failed", R.string.requests_view_failed, R.string.requests_view_failed_empty),
    Done("done", R.string.requests_view_done, R.string.requests_view_done_empty)
}

/** What an admin can do with a request. [Cancel] on a failed request closes it. */
enum class RequestQueueAction(val requestAction: RequestAction, @StringRes val label: Int) {
    Approve(RequestAction.APPROVE, R.string.requests_approve),
    Decline(RequestAction.DECLINE, R.string.requests_decline),
    Retry(RequestAction.RETRY, R.string.requests_retry),
    Cancel(RequestAction.CANCEL, R.string.requests_cancel_request);

    /** Decline and cancel ask for an optional note first. */
    val asksForReason: Boolean get() = this == Decline || this == Cancel
}

enum class RequestTone { Neutral, Progress, Success, Danger }

/** The state a requester sees, as the web's badge shows it. */
enum class RequestDisplayState(val wire: String, @StringRes val label: Int, val tone: RequestTone) {
    Pending("pending", R.string.requests_state_pending, RequestTone.Neutral),
    Approved("approved", R.string.requests_state_approved, RequestTone.Progress),
    Processing("processing", R.string.requests_state_processing, RequestTone.Progress),
    PartiallyAvailable("partially_available", R.string.requests_state_partially_available, RequestTone.Success),
    Available("available", R.string.requests_state_available, RequestTone.Success),
    Declined("declined", R.string.requests_state_declined, RequestTone.Danger),
    Cancelled("cancelled", R.string.requests_state_cancelled, RequestTone.Neutral),
    Failed("failed", R.string.requests_state_failed, RequestTone.Danger);

    companion object {
        fun fromWire(wire: String?): RequestDisplayState? = entries.firstOrNull { it.wire == wire }
    }
}

/** The media-type filter of the queue. */
enum class RequestMediaFilter(val queryValue: String?, @StringRes val label: Int) {
    All(null, R.string.requests_media_all),
    Movie("movie", R.string.requests_media_movies),
    Series("series", R.string.requests_media_series)
}

/** The error that stopped a request, or else its closing reason. */
data class RequestProblem(val text: String, val isError: Boolean)

/** A decline, cancel or close dialog's wording. */
data class RequestReasonPrompt(val title: String, val message: String, val confirm: String)

/**
 * The web admin queue's rules (`requestQueueModel.ts`, `lib/mediaRequests.ts`), so the apps and the web
 * agree on where a request sits and what can be done with it.
 */
object RequestQueue {
    private val KNOWN_PHASES = setOf("queued", "downloading", "paused", "stalled", "importing", "import_blocked")
    private val MONTH_AFTER = Duration.ofDays(30)
    private val POSTER_PATH = Regex("^/[A-Za-z0-9._-]+$")

    /** The view a request belongs to, by the server's own rule. */
    fun view(request: MediaRequest): RequestQueueView {
        when (request.outcome) {
            "failed" -> return RequestQueueView.Failed
            "declined", "cancelled" -> return RequestQueueView.Done
        }
        if (request.status == "pending") return RequestQueueView.NeedsApproval
        if (request.status == "completed") return RequestQueueView.Done
        return RequestQueueView.InProgress
    }

    /** Bulk triage takes only what still waits for an approval: the rows that offer Approve. */
    fun canSelect(request: MediaRequest): Boolean = RequestQueueAction.Approve in actions(request)

    /** The view the queue opens on: what needs approval, else what is under way. */
    fun defaultView(counts: RequestCounts?): RequestQueueView =
        if ((counts?.needs_approval ?: 0) > 0) RequestQueueView.NeedsApproval else RequestQueueView.InProgress

    /** Active, and either pending or approved with nothing sent for it yet. */
    fun canWithdraw(request: MediaRequest): Boolean {
        if (request.outcome != "active") return false
        if (request.status == "pending") return true
        return request.status == "approved" && request.targets.isEmpty()
    }

    /**
     * Approve or decline what needs approval; cancel what is in progress while nothing has been sent for it;
     * retry or close what failed.
     */
    fun actions(request: MediaRequest): List<RequestQueueAction> = when (view(request)) {
        RequestQueueView.NeedsApproval ->
            if (canWithdraw(request)) listOf(RequestQueueAction.Approve, RequestQueueAction.Decline) else emptyList()
        RequestQueueView.InProgress ->
            if (canWithdraw(request)) listOf(RequestQueueAction.Cancel) else emptyList()
        RequestQueueView.Failed -> listOf(RequestQueueAction.Retry, RequestQueueAction.Cancel)
        RequestQueueView.Done -> emptyList()
    }

    /** The server's own state when it is a known one, else what status and outcome say. */
    fun displayState(request: MediaRequest): RequestDisplayState? {
        RequestDisplayState.fromWire(request.state)?.let { return it }
        RequestDisplayState.fromWire(request.outcome)
            ?.takeIf { it == RequestDisplayState.Declined || it == RequestDisplayState.Cancelled || it == RequestDisplayState.Failed }
            ?.let { return it }
        return when (request.status) {
            "pending" -> RequestDisplayState.Pending
            "approved" -> RequestDisplayState.Approved
            "queued", "downloading" -> RequestDisplayState.Processing
            "completed" -> RequestDisplayState.Available
            else -> null
        }
    }

    /** The error that stopped the request, else its closing reason; blank text is none. */
    fun problem(request: MediaRequest): RequestProblem? {
        request.last_error?.takeIf { it.isNotBlank() }?.let { return RequestProblem(it, true) }
        request.outcome_reason?.takeIf { it.isNotBlank() }?.let { return RequestProblem(it, false) }
        return null
    }

    @StringRes
    fun mediaTypeLabel(mediaType: String): Int =
        if (mediaType == "series") R.string.requests_media_type_series else R.string.requests_media_type_movie

    /** The seasons as ascending runs: [3, 1, 2, 5, 2] reads "1–3, 5". Null for none. */
    fun seasonRuns(seasons: List<Int>): String? {
        val sorted = seasons.distinct().sorted()
        if (sorted.isEmpty()) return null
        val runs = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            runs += if (j > i) "${sorted[i]}–${sorted[j]}" else "${sorted[i]}"
            i = j + 1
        }
        return runs.joinToString(", ")
    }

    /** "Season 2" or "Seasons 1–3, 5", like the web's `formatSeasonList`; null for none. */
    fun seasonList(res: Resources, seasons: List<Int>): String? {
        val list = seasonRuns(seasons) ?: return null
        return if (seasons.distinct().size == 1) res.getString(R.string.requests_season, list)
        else res.getString(R.string.requests_seasons, list)
    }

    /** Year · type · seasons. */
    fun metaLine(res: Resources, request: MediaRequest): String =
        listOfNotNull(
            request.year?.toString(),
            res.getString(mediaTypeLabel(request.media_type)),
            seasonList(res, request.seasons)
        ).joinToString(" · ")

    /** The requester's name when the account list knows it, else "User 12", else "Unknown account". */
    fun requesterLabel(res: Resources, request: MediaRequest, names: Map<String, String>): String {
        val id = request.requested_by_user_id ?: return res.getString(R.string.requests_unknown_account)
        return names[id] ?: res.getString(R.string.requests_user_number, id)
    }

    /** A target's status in words. */
    fun targetStatusLabel(res: Resources, status: String): String = res.getString(
        when (status) {
            "failed" -> R.string.requests_state_failed
            "pending" -> R.string.requests_state_pending
            "approved" -> R.string.requests_state_approved
            "queued" -> R.string.requests_target_queued
            "downloading" -> R.string.requests_target_downloading
            "completed" -> R.string.requests_target_completed
            else -> R.string.requests_target_requested
        }
    )

    /** The target's download server, falling back to its kind. */
    fun targetServerName(res: Resources, target: RequestTarget): String =
        target.instance_name?.takeIf { it.isNotEmpty() }
            ?: target.integration_kind?.takeIf { it.isNotEmpty() }
            ?: res.getString(R.string.requests_unknown_server)

    /** A download's phase in words; an unknown phase reads as downloading. */
    fun downloadPhaseLabel(res: Resources, phase: String): String = res.getString(
        when (phase) {
            "queued" -> R.string.requests_download_queued
            "paused" -> R.string.requests_download_paused
            "stalled" -> R.string.requests_download_stalled
            "importing" -> R.string.requests_download_importing
            "import_blocked" -> R.string.requests_download_import_blocked
            else -> R.string.requests_target_downloading
        }
    )

    /** A known phase shows its percentage; an unknown one shows none, as the schema asks. */
    fun downloadPercent(download: RequestDownload): Int? {
        if (download.phase !in KNOWN_PHASES) return null
        return download.percent?.coerceIn(0, 100)
    }

    /** A history entry's type in plain words; an unknown type shows as it is. */
    fun eventLabel(res: Resources, type: String): String {
        val id = when (type) {
            "created" -> R.string.requests_event_created
            "approved", "status_approved" -> R.string.requests_state_approved
            "status_queued" -> R.string.requests_event_queued
            "status_downloading" -> R.string.requests_target_downloading
            "status_completed" -> R.string.requests_event_downloaded
            "available_in_library" -> R.string.requests_event_in_library
            "outcome_declined" -> R.string.requests_state_declined
            "outcome_cancelled" -> R.string.requests_state_cancelled
            "outcome_failed" -> R.string.requests_state_failed
            "retried" -> R.string.requests_event_retried
            "submit_deferred" -> R.string.requests_event_deferred
            else -> return type
        }
        return res.getString(id)
    }

    /** "3 hours ago", or the date once it is more than 30 days old (the web's `absoluteAfterDays: 30`). */
    fun requestedAgo(res: Resources, instant: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        if (instant == null) return null
        val age = Duration.between(instant, now)
        return when {
            age > MONTH_AFTER -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(instant)
            age < Duration.ofMinutes(1) -> res.getString(R.string.requests_just_now)
            else -> {
                // The system's own relative wording ("3 hours ago"); DateUtils would turn into a date after a week.
                val (amount, unit) = when {
                    age < Duration.ofHours(1) -> age.toMinutes() to RelativeDateTimeFormatter.RelativeUnit.MINUTES
                    age < Duration.ofDays(1) -> age.toHours() to RelativeDateTimeFormatter.RelativeUnit.HOURS
                    else -> age.toDays() to RelativeDateTimeFormatter.RelativeUnit.DAYS
                }
                RelativeDateTimeFormatter.getInstance().format(amount.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
            }
        }
    }

    /** A date and time for the details page. */
    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(instant)

    /**
     * The TMDB poster at the web's list size; null unless [path] is a TMDB image path (one slash and a file name),
     * so a server cannot point the app at another host or folder.
     */
    fun posterUrl(path: String?, size: String = "w92"): String? =
        if (path != null && POSTER_PATH.matches(path)) "https://image.tmdb.org/t/p/$size$path" else null

    /** The reason dialog's title, message and confirm label for an action on a request; null when none is asked. */
    fun reasonPrompt(res: Resources, action: RequestQueueAction, request: MediaRequest): RequestReasonPrompt? = when {
        action == RequestQueueAction.Decline -> RequestReasonPrompt(
            res.getString(R.string.requests_reason_decline_title),
            res.getString(R.string.requests_reason_decline_message, request.title),
            res.getString(R.string.requests_decline)
        )
        action == RequestQueueAction.Cancel && view(request) == RequestQueueView.Failed -> RequestReasonPrompt(
            res.getString(R.string.requests_reason_close_title),
            res.getString(R.string.requests_reason_close_message, request.title),
            res.getString(R.string.requests_reason_close_title)
        )
        action == RequestQueueAction.Cancel -> RequestReasonPrompt(
            res.getString(R.string.requests_cancel_request),
            res.getString(R.string.requests_reason_cancel_message, request.title),
            res.getString(R.string.requests_cancel_request)
        )
        else -> null
    }

    /** The counts of a server's whole request list, worked out with the web's rule (All servers reads lists whole). */
    fun countsOf(requests: Iterable<MediaRequest>): RequestCounts {
        var needs = 0; var progress = 0; var failed = 0; var done = 0
        for (request in requests) when (view(request)) {
            RequestQueueView.NeedsApproval -> needs++
            RequestQueueView.InProgress -> progress++
            RequestQueueView.Failed -> failed++
            RequestQueueView.Done -> done++
        }
        return RequestCounts(needs, progress, failed, done)
    }
}

/** Counts added together, for All servers. */
operator fun RequestCounts.plus(other: RequestCounts) = RequestCounts(
    needs_approval + other.needs_approval, in_progress + other.in_progress, failed + other.failed, done + other.done
)
