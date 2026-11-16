package com.bloemstudios.admin.widget

import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.links.DeepLinkTarget
import com.bloemstudios.admin.servers.AdminServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every state of every widget, the privacy rules, and where a tap goes. */
class WidgetViewsTest {
    private val now = 10_000_000_000L
    private val home = AdminServer("home", "https://home.test", "Home")
    private val lab = AdminServer("lab", "https://lab.test", "Lab")
    private val servers = listOf(home, lab)

    private fun ok(at: Long = now - 60_000L, build: CachedSlice.() -> CachedSlice) =
        CachedSlice(Outcome.Ok, attemptedAt = at, dataAt = at).build()

    private fun streams(total: Int, vararg names: String) = ok {
        copy(nowPlaying = NowPlayingData(total, streams = names.mapIndexed { i, n -> StreamItem("sess-$i-$n", n, "Title of $n", 0.5f) }))
    }

    private fun inputs(
        target: String?, slices: Map<Pair<String, WidgetKind>, CachedSlice> = emptyMap(), reachable: Map<String, Boolean> = emptyMap(),
        detail: Detail = Detail.Full, notices: Map<String, ActionNotice> = emptyMap(),
        list: List<AdminServer> = servers, at: Long = now,
    ) = WidgetInputs(target, list, { s, k -> slices[s to k] }, { reachable[it] }, { notices[it] }, detail, at)

    // ---- Now playing ----

    @Test fun contentShowsTheCountTheRowsAndTheLinks() {
        val state = WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to streams(2, "ana", "ben"))))
        val view = (state as WidgetState.Content).data
        assertEquals(2, view.total)
        assertEquals(listOf("ana", "ben"), view.rows.map { it.user })
        assertEquals("Title of ana", view.rows[0].title)
        assertEquals(DeepLink.build("home", "now-playing", "sess-0-ana"), view.rows[0].link)
        assertEquals(DeepLink.build("home", "now-playing"), view.link)
        assertNull(state.staleAt)
    }

    @Test fun nothingPlayingIsEmpty() {
        val state = WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to ok { copy(nowPlaying = NowPlayingData(0)) })))
        assertEquals(WidgetState.Empty(null), state)
    }

    @Test fun nothingReadYetIsLoading() {
        assertEquals(WidgetState.Loading, WidgetViews.nowPlaying(inputs("home")))
    }

    @Test fun aServerThatIsDownWithNothingKeptSaysCantReach() {
        val state = WidgetViews.nowPlaying(inputs("home", reachable = mapOf("home" to false)))
        assertEquals(WidgetState.Unreachable<NowPlayingView>("Home", 1, null, null), state)
    }

    @Test fun aServerThatIsDownShowsItsLastDataWithTheTimeItWasRead() {
        val read = now - 3_600_000L
        val slice = CachedSlice(Outcome.Unreachable, attemptedAt = now, dataAt = read, nowPlaying = NowPlayingData(3, streams = listOf(StreamItem("s", "ana", "T"))))
        val state = WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to slice))) as WidgetState.Unreachable
        assertEquals("Home", state.server)
        assertEquals(read, state.at)
        assertEquals(3, state.last?.total)
    }

    @Test fun theHealthCheckSayingDownOutranksAnOlderGoodRead() {
        val state = WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to streams(2, "ana")), reachable = mapOf("home" to false)))
        assertTrue(state is WidgetState.Unreachable<*>)
        assertEquals(2, (state as WidgetState.Unreachable).last?.total)
    }

    @Test fun aRejectedSessionIsSignedOut() {
        val slice = CachedSlice(Outcome.SignInNeeded, now)
        assertEquals(WidgetState.SignedOut("Home"), WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to slice))))
    }

    @Test fun aServerWithoutTheFeatureSaysSo() {
        val slice = CachedSlice(Outcome.Unavailable, now)
        assertEquals(WidgetState.Unsupported("Home"), WidgetViews.requests(inputs("home", mapOf(("home" to WidgetKind.Requests) to slice))))
    }

    @Test fun aRemovedServerAndNoServersAreTheirOwnStates() {
        assertEquals(WidgetState.ServerRemoved, WidgetViews.nowPlaying(inputs("gone")))
        assertEquals(WidgetState.NoServers, WidgetViews.nowPlaying(inputs(null, list = emptyList())))
    }

    @Test fun dataOlderThanTheRefreshWindowIsMarkedWithItsTime() {
        val old = now - WidgetViews.FRESH_WINDOW_MILLIS - 1
        val state = WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to streams(1, "ana").copy(dataAt = old))))
        assertEquals(old, (state as WidgetState.Content).staleAt)
        val fresh = WidgetViews.nowPlaying(inputs("home", mapOf(("home" to WidgetKind.NowPlaying) to streams(1, "ana").copy(dataAt = now - WidgetViews.FRESH_WINDOW_MILLIS))))
        assertNull((fresh as WidgetState.Content).staleAt)
    }

    @Test fun allServersAddsTheServersUpAndCountsThoseThatDidNotAnswer() {
        val slices = mapOf(
            ("home" to WidgetKind.NowPlaying) to streams(2, "ana", "ben"),
            ("lab" to WidgetKind.NowPlaying) to CachedSlice(Outcome.Unreachable, now),
        )
        val state = WidgetViews.nowPlaying(inputs(null, slices)) as WidgetState.Content
        assertEquals(2, state.data.total)
        assertEquals(1, state.notAnswering)
        assertEquals(DeepLink.buildAll("now-playing"), state.data.link)
        // A row always opens on its own server, even from All servers.
        assertEquals(DeepLink.build("home", "now-playing", "sess-0-ana"), state.data.rows.first().link)
    }

    @Test fun allServersWithNoneSupportingTheFeatureSaysSoForAll() {
        val slices = servers.associate { (it.id to WidgetKind.Nodes) to CachedSlice(Outcome.Unavailable, now) }
        assertEquals(WidgetState.Unsupported(null), WidgetViews.nodes(inputs(null, slices)))
    }

    @Test fun allServersWithEveryServerDownCountsThem() {
        val state = WidgetViews.nowPlaying(inputs(null, reachable = mapOf("home" to false, "lab" to false)))
        assertEquals(WidgetState.Unreachable<NowPlayingView>(null, 2, null, null), state)
    }

    // ---- Privacy: counts only unless details are allowed ----

    private val secrets = listOf("ana", "ben", "Title of", "Glass Orchard", "Films", "transcode-a")

    private fun assertNoNames(view: Any) {
        val text = view.toString()
        secrets.forEach { assertFalse("'$it' must not reach a counts-only widget: $text", it in text) }
    }

    @Test fun nowPlayingWithDetailsOffHasNoUserOrTitle() {
        val slices = mapOf(("home" to WidgetKind.NowPlaying) to streams(2, "ana", "ben"))
        val view = (WidgetViews.nowPlaying(inputs("home", slices, detail = Detail.CountsOnly)) as WidgetState.Content).data
        assertEquals(2, view.total)
        assertTrue(view.detailsHidden)
        assertTrue(view.rows.isEmpty())
        assertNoNames(view)
    }

    @Test fun requestsWithDetailsOffHaveACountAndNothingElse() {
        val slice = ok { copy(requests = RequestsData(5, listOf(RequestItem("r1", "Glass Orchard", "ana", "2026-11-15T10:00:00Z")))) }
        val view = (WidgetViews.requests(inputs("home", mapOf(("home" to WidgetKind.Requests) to slice), detail = Detail.CountsOnly)) as WidgetState.Content).data
        assertEquals(5, view.pending)
        assertTrue(view.rows.isEmpty())
        assertNoNames(view)
    }

    @Test fun librariesAndNodesWithDetailsOffKeepOnlyCounts() {
        val libs = ok { copy(libraries = LibrariesData(listOf(LibraryItem("l1", "Films", warning = true, scan = ScanBrief("running"))))) }
        val library = (WidgetViews.libraries(inputs("home", mapOf(("home" to WidgetKind.Libraries) to libs), detail = Detail.CountsOnly)) as WidgetState.Content).data
        assertEquals(1, library.total); assertEquals(1, library.scanning); assertEquals(1, library.withWarnings)
        assertNoNames(library)

        val nodes = ok { copy(nodes = NodesData(listOf(NodeItem("n1", "transcode-a", "unhealthy")))) }
        val node = (WidgetViews.nodes(inputs("home", mapOf(("home" to WidgetKind.Nodes) to nodes), detail = Detail.CountsOnly)) as WidgetState.Content).data
        assertEquals(1, node.needAttention)
        assertNoNames(node)
    }

    @Test fun withDetailsOnTheSameDataShowsTheNames() {
        val slices = mapOf(("home" to WidgetKind.NowPlaying) to streams(1, "ana"))
        val view = (WidgetViews.nowPlaying(inputs("home", slices, detail = Detail.Full)) as WidgetState.Content).data
        assertEquals("ana", view.rows.single().user)
    }

    @Test fun privacyRulesFollowTheSpec() {
        // An unlocked phone: the home screen shows full detail. The app's own lock does not change that, so it is not an input.
        assertEquals(Detail.Full, WidgetPrivacy.detail(Surface.HomeScreen, showDetailsOnLockScreen = false))
        assertEquals(Detail.Full, WidgetPrivacy.detail(Surface.HomeScreen, showDetailsOnLockScreen = true))
        // Lock screen: counts only unless "Show details on lock screen" is on.
        assertEquals(Detail.CountsOnly, WidgetPrivacy.detail(Surface.Keyguard, showDetailsOnLockScreen = false))
        assertEquals(Detail.Full, WidgetPrivacy.detail(Surface.Keyguard, showDetailsOnLockScreen = true))
        // The progress notification on a locked device follows the same setting.
        assertFalse(WidgetPrivacy.detailsOnLockedDevice(showDetailsOnLockScreen = false))
        assertTrue(WidgetPrivacy.detailsOnLockedDevice(showDetailsOnLockScreen = true))
    }

    // ---- Requests ----

    private fun pending() = ok {
        copy(
            requests = RequestsData(
                9,
                listOf(
                    RequestItem("r-old", "Winter Station", "cleo", "2026-11-15T08:00:00Z"),
                    RequestItem("r-new", "Glass Orchard", "ana", "2026-11-15T11:00:00Z"),
                ),
            ),
        )
    }

    @Test fun requestsAreNewestFirstWithTheirAgeAndALinkToTheRequest() {
        val at = java.time.Instant.parse("2026-11-15T11:30:00Z").toEpochMilli()
        val view = (WidgetViews.requests(inputs("home", mapOf(("home" to WidgetKind.Requests) to pending().copy(dataAt = at)), at = at)) as WidgetState.Content).data
        assertEquals(listOf("r-new", "r-old"), view.rows.map { it.requestId })
        assertEquals(30 * 60_000L, view.rows[0].ageMillis)
        assertEquals(DeepLink.build("home", "requests", "r-new"), view.rows[0].link)
        assertEquals("home", view.rows[0].serverId)
        assertEquals(9, view.pending)
    }

    @Test fun requestsMergeAcrossServersNewestFirst() {
        val labSlice = ok { copy(requests = RequestsData(1, listOf(RequestItem("r-lab", "Copper Hill", "ben", "2026-11-15T11:30:00Z")))) }
        val view = (WidgetViews.requests(inputs(null, mapOf(("home" to WidgetKind.Requests) to pending(), ("lab" to WidgetKind.Requests) to labSlice))) as WidgetState.Content).data
        assertEquals(listOf("r-lab", "r-new", "r-old"), view.rows.map { it.requestId })
        assertEquals("lab", view.rows[0].serverId)
        assertEquals(10, view.pending)
    }

    @Test fun aFailedApproveIsCarriedToTheWidget() {
        val notice = ActionNotice("home", approve = true, at = now)
        val view = (WidgetViews.requests(inputs("home", mapOf(("home" to WidgetKind.Requests) to pending()), notices = mapOf("home" to notice))) as WidgetState.Content).data
        assertEquals(notice, view.failedAction)
    }

    @Test fun noRequestsWaitingIsEmpty() {
        val slice = ok { copy(requests = RequestsData(0)) }
        assertEquals(WidgetState.Empty(null), WidgetViews.requests(inputs("home", mapOf(("home" to WidgetKind.Requests) to slice))))
    }

    // ---- Libraries and nodes ----

    @Test fun librariesPutScansAndWarningsFirst() {
        val slice = ok {
            copy(
                libraries = LibrariesData(
                    listOf(
                        LibraryItem("a", "Quiet", "2026-11-14T08:00:00Z"),
                        LibraryItem("b", "Warned", warning = true),
                        LibraryItem("c", "Scanning", scan = ScanBrief("running")),
                    ),
                ),
            )
        }
        val view = (WidgetViews.libraries(inputs("home", mapOf(("home" to WidgetKind.Libraries) to slice))) as WidgetState.Content).data
        assertEquals(listOf("Scanning", "Warned", "Quiet"), view.rows.map { it.name })
        assertEquals(DeepLink.build("home", "libraries"), view.rows[0].link)
        assertEquals(1, view.scanning)
    }

    @Test fun nodesPutUnhealthyOnesFirst() {
        val slice = ok { copy(nodes = NodesData(listOf(NodeItem("a", "fine", "healthy", 10, 20, 30), NodeItem("b", "broken", "unhealthy")))) }
        val view = (WidgetViews.nodes(inputs("home", mapOf(("home" to WidgetKind.Nodes) to slice))) as WidgetState.Content).data
        assertEquals("broken", view.rows.first().name)
        assertEquals(1, view.needAttention)
        assertEquals(DeepLink.build("home", "nodes"), view.rows[0].link)
    }

    @Test fun noLibrariesAndNoNodesAreEmpty() {
        assertEquals(WidgetState.Empty(null), WidgetViews.libraries(inputs("home", mapOf(("home" to WidgetKind.Libraries) to ok { copy(libraries = LibrariesData()) }))))
        assertEquals(WidgetState.Empty(null), WidgetViews.nodes(inputs("home", mapOf(("home" to WidgetKind.Nodes) to ok { copy(nodes = NodesData()) }))))
    }

    // ---- Deep links ----

    @Test fun everyWidgetOpensTheMatchingPageThroughTheAppsOwnLinks() {
        val expected = mapOf(
            WidgetKind.ServerStatus to "live.streams", WidgetKind.NowPlaying to "live.streams", WidgetKind.Requests to "work.requests",
            WidgetKind.Libraries to "content.libraries", WidgetKind.Nodes to "playback.nodes",
        )
        expected.forEach { (kind, page) ->
            assertEquals(kind.name, DeepLinkTarget("home", page), DeepLink.parse(WidgetViews.link("home", kind)))
            assertEquals(kind.name, DeepLinkTarget(null, page), DeepLink.parse(WidgetViews.link(null, kind)))
        }
    }

    @Test fun anItemLinkNamesItsPageAndItsItem() {
        assertEquals(
            DeepLinkTarget("home", "work.requests", "req 1/a"),
            DeepLink.parse(WidgetViews.link("home", WidgetKind.Requests, "req 1/a")),
        )
        assertEquals(
            DeepLinkTarget("home", "live.streams", "sess-9"),
            DeepLink.parse(WidgetViews.link("home", WidgetKind.NowPlaying, "sess-9")),
        )
    }
}
