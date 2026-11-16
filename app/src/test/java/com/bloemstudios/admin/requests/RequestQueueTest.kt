package com.bloemstudios.admin.requests

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * The web admin queue's rules (`requestQueueModel.ts`, `lib/mediaRequests.ts`), so the app and the web agree
 * on where a request sits and what can be done with it. Wording is checked against the real English resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class RequestQueueTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun request(
        status: String = "pending", outcome: String = "active", state: String? = null,
        targets: List<RequestTarget> = emptyList(), lastError: String? = null, outcomeReason: String? = null,
        mediaType: String = "movie", seasons: List<Int> = emptyList(), year: Int? = null, requestedBy: String? = "7"
    ) = MediaRequest(
        id = "r1", provider = "tmdb", media_type = mediaType, title = "Dune", status = status, outcome = outcome,
        requested_by_user_id = requestedBy, last_error = lastError, state = state, outcome_reason = outcomeReason,
        year = year, seasons = seasons, targets = targets
    )

    private val sent = RequestTarget(id = "t1", quality = "4K", status = "queued")

    @Test
    fun `each request belongs to the view the server puts it in`() {
        assertEquals(RequestQueueView.NeedsApproval, RequestQueue.view(request(status = "pending")))
        assertEquals(RequestQueueView.InProgress, RequestQueue.view(request(status = "approved")))
        assertEquals(RequestQueueView.InProgress, RequestQueue.view(request(status = "queued")))
        assertEquals(RequestQueueView.InProgress, RequestQueue.view(request(status = "downloading")))
        assertEquals(RequestQueueView.Done, RequestQueue.view(request(status = "completed")))
        assertEquals(RequestQueueView.Failed, RequestQueue.view(request(status = "queued", outcome = "failed")))
        assertEquals(RequestQueueView.Done, RequestQueue.view(request(status = "pending", outcome = "declined")))
        assertEquals(RequestQueueView.Done, RequestQueue.view(request(status = "approved", outcome = "cancelled")))
    }

    @Test
    fun `a request waiting for approval can be approved and declined`() {
        // The bug this rebuild fixes: the old page enabled these only for status "queued", which never waits.
        assertEquals(listOf(RequestQueueAction.Approve, RequestQueueAction.Decline), RequestQueue.actions(request(status = "pending")))
    }

    @Test
    fun `actions follow the view and whether anything was sent`() {
        assertEquals(listOf(RequestQueueAction.Cancel), RequestQueue.actions(request(status = "approved")))
        assertEquals(emptyList<RequestQueueAction>(), RequestQueue.actions(request(status = "approved", targets = listOf(sent))))
        assertEquals(emptyList<RequestQueueAction>(), RequestQueue.actions(request(status = "downloading", targets = listOf(sent))))
        assertEquals(
            listOf(RequestQueueAction.Retry, RequestQueueAction.Cancel),
            RequestQueue.actions(request(status = "queued", outcome = "failed", targets = listOf(sent)))
        )
        assertEquals(emptyList<RequestQueueAction>(), RequestQueue.actions(request(status = "completed")))
        assertEquals(emptyList<RequestQueueAction>(), RequestQueue.actions(request(status = "pending", outcome = "declined")))
    }

    @Test
    fun `only decline and cancel ask for a note`() {
        assertTrue(RequestQueueAction.Decline.asksForReason)
        assertTrue(RequestQueueAction.Cancel.asksForReason)
        assertFalse(RequestQueueAction.Approve.asksForReason)
        assertFalse(RequestQueueAction.Retry.asksForReason)
    }

    @Test
    fun `the queue opens on what needs approval else what is under way`() {
        assertEquals(RequestQueueView.NeedsApproval, RequestQueue.defaultView(RequestCounts(2, 5, 1, 9)))
        assertEquals(RequestQueueView.InProgress, RequestQueue.defaultView(RequestCounts(0, 5, 1, 9)))
        assertEquals(RequestQueueView.InProgress, RequestQueue.defaultView(null))
    }

    @Test
    fun `the state badge prefers the servers own state`() {
        assertEquals(RequestDisplayState.PartiallyAvailable, RequestQueue.displayState(request(status = "queued", state = "partially_available")))
        assertEquals(RequestDisplayState.Failed, RequestQueue.displayState(request(status = "queued", outcome = "failed")))
        assertEquals(RequestDisplayState.Processing, RequestQueue.displayState(request(status = "downloading")))
        assertEquals(RequestDisplayState.Available, RequestQueue.displayState(request(status = "completed")))
        assertEquals(RequestDisplayState.Pending, RequestQueue.displayState(request(status = "pending", state = "something-new")))
        assertNull(RequestQueue.displayState(request(status = "unknown")))
    }

    @Test
    fun `the state badge reads in words not raw server words`() {
        assertEquals("Pending", res.getString(RequestDisplayState.Pending.label))
        assertEquals("Partially available", res.getString(RequestDisplayState.PartiallyAvailable.label))
        assertEquals(RequestTone.Danger, RequestDisplayState.Declined.tone)
        assertEquals(RequestTone.Success, RequestDisplayState.Available.tone)
    }

    @Test
    fun `the error that stopped a request wins over its closing reason`() {
        val both = RequestQueue.problem(request(lastError = "Radarr refused it", outcomeReason = "duplicate"))
        assertEquals("Radarr refused it", both?.text)
        assertEquals(true, both?.isError)
        val reason = RequestQueue.problem(request(outcomeReason = "duplicate"))
        assertEquals("duplicate", reason?.text)
        assertEquals(false, reason?.isError)
        assertNull(RequestQueue.problem(request()))
        assertNull("a blank error is no error", RequestQueue.problem(request(lastError = "  ", outcomeReason = "")))
    }

    @Test
    fun `seasons read as runs like the web`() {
        assertNull(RequestQueue.seasonList(res, emptyList()))
        assertEquals("Season 2", RequestQueue.seasonList(res, listOf(2)))
        assertEquals("Season 2", RequestQueue.seasonList(res, listOf(2, 2)))
        assertEquals("Seasons 1–3, 5", RequestQueue.seasonList(res, listOf(3, 1, 2, 5, 2)))
    }

    @Test
    fun `the meta line names year type and seasons`() {
        assertEquals("2021 · Movie", RequestQueue.metaLine(res, request(year = 2021)))
        assertEquals("Series · Seasons 1–2", RequestQueue.metaLine(res, request(mediaType = "series", seasons = listOf(1, 2))))
    }

    @Test
    fun `the requester falls back to the account number then unknown`() {
        assertEquals("alex", RequestQueue.requesterLabel(res, request(), mapOf("7" to "alex")))
        assertEquals("User 7", RequestQueue.requesterLabel(res, request(), emptyMap()))
        assertEquals("Unknown account", RequestQueue.requesterLabel(res, request(requestedBy = null), emptyMap()))
    }

    @Test
    fun `an unknown history type shows as it is`() {
        assertEquals("Approved", RequestQueue.eventLabel(res, "status_approved"))
        assertEquals("Approved", RequestQueue.eventLabel(res, "approved"))
        assertEquals("Sent to the server", RequestQueue.eventLabel(res, "status_queued"))
        assertEquals("Couldn't send yet", RequestQueue.eventLabel(res, "submit_deferred"))
        assertEquals("something_new", RequestQueue.eventLabel(res, "something_new"))
    }

    @Test
    fun `an unknown download phase reads as downloading without a percentage`() {
        assertEquals(42, RequestQueue.downloadPercent(RequestDownload("downloading", 42)))
        assertEquals(100, RequestQueue.downloadPercent(RequestDownload("downloading", 140)))
        assertEquals(0, RequestQueue.downloadPercent(RequestDownload("importing", -3)))
        assertNull(RequestQueue.downloadPercent(RequestDownload("warping", 42)))
        assertNull(RequestQueue.downloadPercent(RequestDownload("downloading", null)))
        assertEquals("Downloading", RequestQueue.downloadPhaseLabel(res, "warping"))
        assertEquals("Import blocked", RequestQueue.downloadPhaseLabel(res, "import_blocked"))
        assertEquals("Waiting to download", RequestQueue.downloadPhaseLabel(res, "queued"))
    }

    @Test
    fun `targets name their server else its kind`() {
        assertEquals("Radarr 4K", RequestQueue.targetServerName(res, RequestTarget("t", "HD", "queued", instance_name = "Radarr 4K", integration_kind = "radarr")))
        assertEquals("radarr", RequestQueue.targetServerName(res, RequestTarget("t", "HD", "queued", integration_kind = "radarr")))
        assertEquals("Unknown server", RequestQueue.targetServerName(res, RequestTarget("t", "HD", "queued")))
        assertEquals("Queued", RequestQueue.targetStatusLabel(res, "queued"))
        assertEquals("Requested", RequestQueue.targetStatusLabel(res, "something-else"))
    }

    @Test
    fun `posters come from TMDB only for a TMDB image path`() {
        assertEquals("https://image.tmdb.org/t/p/w92/abc.jpg", RequestQueue.posterUrl("/abc.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", RequestQueue.posterUrl("/abc.jpg", "w185"))
        assertNull(RequestQueue.posterUrl(null))
        assertNull(RequestQueue.posterUrl("https://elsewhere.test/x.jpg"))
        assertNull(RequestQueue.posterUrl("/../secret"))
        assertNull(RequestQueue.posterUrl("/a/b.jpg"))
        assertNull(RequestQueue.posterUrl("/a.jpg?x=1"))
    }

    @Test
    fun `cancelling a failed request closes it`() {
        assertEquals("Close request", RequestQueue.reasonPrompt(res, RequestQueueAction.Cancel, request(status = "queued", outcome = "failed"))?.title)
        assertEquals("Cancel request", RequestQueue.reasonPrompt(res, RequestQueueAction.Cancel, request(status = "approved"))?.title)
        assertEquals("Decline", RequestQueue.reasonPrompt(res, RequestQueueAction.Decline, request())?.confirm)
        assertEquals(
            "\"Dune\" will be marked declined. Add an optional note for the requester.",
            RequestQueue.reasonPrompt(res, RequestQueueAction.Decline, request())?.message
        )
        assertNull(RequestQueue.reasonPrompt(res, RequestQueueAction.Approve, request()))
        assertNull(RequestQueue.reasonPrompt(res, RequestQueueAction.Retry, request()))
    }

    @Test
    fun `time reads relative until a month has passed then as a date`() {
        val now = Instant.parse("2026-11-14T12:00:00Z")
        assertNull(RequestQueue.requestedAgo(res, null, now))
        assertEquals("Just now", RequestQueue.requestedAgo(res, now.minusSeconds(20), now))
        assertEquals("3 hours ago", RequestQueue.requestedAgo(res, now.minusSeconds(3 * 3600), now))
        // 30 days exactly is still relative; one second more is a date.
        assertEquals("30 days ago", RequestQueue.requestedAgo(res, now.minusSeconds(30L * 24 * 3600), now))
        val old = RequestQueue.requestedAgo(res, Instant.parse("2026-10-01T09:00:00Z"), now, ZoneOffset.UTC)
        assertEquals("Oct 1, 2026", old)
    }

    @Test
    fun `counts of a whole list use the same rule as the views`() {
        val list = listOf(
            request(status = "pending"), request(status = "pending"), request(status = "approved"),
            request(status = "queued", outcome = "failed"), request(status = "completed"), request(status = "pending", outcome = "declined"),
        )
        assertEquals(RequestCounts(2, 1, 1, 2), RequestQueue.countsOf(list))
        assertEquals(RequestCounts(3, 2, 1, 4), RequestCounts(2, 1, 1, 2) + RequestCounts(1, 1, 0, 2))
        assertEquals(2, RequestCounts(2, 1, 1, 2)[RequestQueueView.NeedsApproval])
    }

    // ---- Decoding ---------------------------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a full request decodes with its targets and dates`() {
        val text = """
            {"id":"r9","provider":"tmdb","media_type":"series","tmdb_id":1399,"title":"Show","year":2011,
             "poster_path":"/p.jpg","status":"downloading","outcome":"active","state":"processing","seasons":[1,2],
             "season_progress":[],"source":"watchlist","is_anime":false,"requested_by_user_id":"7",
             "created_at":"2026-11-01T10:00:00.000Z","updated_at":"2026-11-01T10:00:00.000Z","approved_at":"2026-11-01T11:00:00Z",
             "targets":[{"id":"t1","request_id":"r9","quality":"4K","status":"downloading","is_anime":false,
                         "instance_name":"Sonarr 4K","route_name":"Big screens","created_at":"2026-11-01T11:00:00.000Z",
                         "updated_at":"2026-11-01T11:00:00.000Z",
                         "download":{"phase":"downloading","percent":37,"downloads":2,"updated_at":"2026-11-01T11:05:00.000Z"}}]}
        """.trimIndent()
        val decoded = json.decodeFromString<MediaRequest>(text)
        assertEquals(2011, decoded.year)
        assertEquals(listOf(1, 2), decoded.seasons)
        assertEquals("watchlist", decoded.source)
        assertEquals(1399L, decoded.tmdb_id)
        assertNotNull(RequestDates.parse(decoded.created_at))
        assertNotNull("a date without fractional seconds still reads", RequestDates.parse(decoded.approved_at))
        assertEquals("Big screens", decoded.targets.first().route_name)
        assertEquals(RequestDownload("downloading", 37), decoded.targets.first().download)
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), RequestDates.parse(decoded.created_at))
    }

    @Test
    fun `a minimal request still decodes`() {
        val decoded = json.decodeFromString<MediaRequest>(
            """{"id":"r1","provider":"tmdb","media_type":"movie","title":"X","status":"pending","outcome":"active"}"""
        )
        assertEquals(emptyList<RequestTarget>(), decoded.targets)
        assertEquals("direct", decoded.source)
        assertNull(decoded.created_at)
        assertNull(RequestDates.parse(null))
        assertNull(RequestDates.parse("not a date"))
    }
}
