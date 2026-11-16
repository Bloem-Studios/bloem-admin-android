package com.bloemstudios.admin.requests

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.servers.ServerSectionResult
import com.bloemstudios.admin.ui.shell.AdminShell
import com.bloemstudios.admin.ui.shell.AdminShellContext
import com.bloemstudios.admin.ui.shell.singleServerDestinations
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Requests page on a device, from fixture state. The test device is CI's 320 x 640 dp screen, so every row is laid out at
 * 288 dp (the page's 16 dp gutters) and must neither run off it nor shrink a control under 48 dp.
 */
@RunWith(AndroidJUnit4::class)
class RequestQueuePageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Performed(val action: RequestQueueAction, val server: String?, val request: String, val note: String?)

    private val performed = mutableListOf<Performed>()
    private val posters = mutableListOf<String>()
    private var performResult = true
    private var state by mutableStateOf(RequestQueueState())
    private var actionError by mutableStateOf<ErrorText?>(null)
    private var loadError by mutableStateOf<ErrorText?>(null)
    private val calls = mutableListOf<String>()
    private var history = listOf<RequestEvent>()
    private var historyFails = false

    private val sent = RequestTarget(
        id = "t1", quality = "4K", status = "downloading", instance_name = "Radarr 4K", route_name = "Big screens",
        download = RequestDownload("downloading", 42), last_error = "Disk is full",
    )

    private fun flow() = RequestFlow(
        perform = { action, server, request, note -> performed += Performed(action, server, request.id, note); performResult },
        loadEvents = { _, _ -> if (historyFails) error("down") else history },
        current = { _, id -> state.requests.firstOrNull { it.id == id } },
        requester = { _, request -> RequestQueue.requesterLabel(compose.activity.resources, request, state.requesterNames) },
        actionError = { _ -> actionError },
    )

    private fun show() {
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalPosterLoader provides { url -> posters += url; ImageBitmap(2, 3) }) {
                    val flow = flow()
                    val ui = rememberRequestFlowState(flow)
                    Box(Modifier.fillMaxSize()) {
                        RequestsContent(
                            state = state, loadError = loadError, actionError = actionError, pendingKeys = emptySet(),
                            onSelectView = { calls += "view:${it.wire}" },
                            onSearch = { calls += "search:$it" },
                            onMedia = { calls += "media:${it.queryValue}" },
                            onFilterUser = { calls += "user:$it" },
                            onClearUserFilter = { calls += "clear-user" },
                            onAction = ui::act, onOpen = ui::open,
                            onLoadMore = { calls += "more" }, onRetry = { calls += "retry" }, onDismissError = { calls += "dismiss" },
                        )
                    }
                    RequestFlowDialogs(flow, ui)
                }
            }
        }
    }

    private fun load(vararg requests: MediaRequest, view: RequestQueueView = RequestQueueView.NeedsApproval, next: String? = null) {
        state = RequestQueueState(
            view = view, counts = RequestCounts(1, 2, 3, 4), requests = requests.toList(), nextCursor = next, hasLoaded = true,
            requesterNames = mapOf("68" to "alex"),
        )
    }

    private fun action(name: String) = compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + name))
    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()) and hasClickAction().not())
    private fun dialogButton(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()) and hasClickAction())
    private fun inRow(id: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(REQUEST_ROW_TAG_PREFIX + id)))
    private fun detailHas(text: String) = assertTrue("the details show '$text'", inDetail(text).fetchSemanticsNodes().isNotEmpty())
    private fun inDetail(text: String) = compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG)))
    private fun scrollTo(tag: String) = compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(tag))

    // ---- The bug: a request waiting for approval --------------------------------------------------------------------

    @Test
    fun aPendingRequestCanBeApprovedAndDeclined() {
        load(queueRequest())
        show()
        action("approve").assertIsDisplayed().assertIsEnabled().performClick()
        action("decline").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        assertEquals(listOf("approve"), performed.map { it.action.name.lowercase() })
        assertEquals("r1", performed.single().request)
        assertEquals(null, performed.single().note)
    }

    @Test
    fun aRowReadsInWordsNotRawServerWords() {
        load(queueRequest())
        show()
        compose.onNodeWithText("Dune").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Pending · Active").assertDoesNotExist()
        compose.onNodeWithText("2021 · Movie").assertIsDisplayed()
    }

    // ---- The row ---------------------------------------------------------------------------------------------------

    @Test
    fun aRowShowsWhoAskedWhenAndWhereEachQualityWent() {
        load(
            queueRequest(
                status = "downloading", state = "processing", source = "watchlist", targets = listOf(sent),
                mediaType = "series", seasons = listOf(1, 2, 3), year = 2011,
            ),
            view = RequestQueueView.InProgress,
        )
        show()
        compose.onNodeWithText("2011 · Series · Seasons 1–3").assertIsDisplayed()
        compose.onNodeWithText("alex · 3 hours ago · via watchlist").assertIsDisplayed()
        compose.onNodeWithText("Processing").assertIsDisplayed()
        compose.onNodeWithText("4K").assertIsDisplayed()
        compose.onNodeWithText("Radarr 4K").assertIsDisplayed()
        compose.onNodeWithText("Downloading").assertIsDisplayed()
        compose.onNodeWithText("by Big screens").assertIsDisplayed()
        compose.onNodeWithText("Downloading · 42%").assertIsDisplayed()
        compose.onNodeWithText("Disk is full").assertIsDisplayed()
        // The server holds it already, so there is nothing left to cancel.
        action("cancel").assertDoesNotExist()
    }

    @Test
    fun anUnknownRequesterReadsAsTheAccountNumberOrUnknown() {
        load(queueRequest(id = "a", title = "Alpha", requestedBy = "99"), queueRequest(id = "b", title = "Beta", requestedBy = null))
        show()
        compose.onNodeWithText("User 99 · 3 hours ago").assertIsDisplayed()
        compose.onNodeWithText("Unknown account · 3 hours ago").assertIsDisplayed()
    }

    @Test
    fun theErrorShowsAndAClosedRequestShowsItsReason() {
        load(
            queueRequest(id = "a", title = "Alpha", status = "queued", outcome = "failed", lastError = "Radarr refused it"),
            queueRequest(id = "b", title = "Beta", status = "pending", outcome = "declined", reason = "Duplicate request"),
            view = RequestQueueView.Failed,
        )
        show()
        compose.onNodeWithText("Radarr refused it").assertIsDisplayed()
        inRow("a", "Failed").assertIsDisplayed()
        scrollTo(REQUEST_ROW_TAG_PREFIX + "b")
        compose.onNodeWithText("Duplicate request").assertIsDisplayed()
        inRow("b", "Declined").assertIsDisplayed()
    }

    @Test
    fun theServersOwnStateWins() {
        load(queueRequest(status = "queued", state = "partially_available"), view = RequestQueueView.InProgress)
        show()
        compose.onNodeWithText("Partially available").assertIsDisplayed()
    }

    @Test
    fun theTmdbPosterIsFetchedAtListSizeAndANoPosterRowStaysATile() {
        load(queueRequest(id = "a", title = "Alpha"), queueRequest(id = "b", title = "Beta").copy(poster_path = null))
        show()
        compose.waitUntil(5_000) { posters.isNotEmpty() }
        assertEquals(listOf("https://image.tmdb.org/t/p/w92/dune.jpg"), posters)
        compose.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun rowsFitTheSmallScreenAndEveryControlIs48dpTall() {
        load(queueRequest(targets = listOf(sent), status = "approved"), queueRequest(id = "p", title = "Pending one"), view = RequestQueueView.NeedsApproval)
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onAllNodesWithTag(REQUEST_ROW_TAG_PREFIX + "p").fetchSemanticsNodes().forEach {
            assertTrue("row stays inside the screen: ${it.boundsInRoot}", it.boundsInRoot.right <= root.right + 0.5f && it.boundsInRoot.left >= -0.5f)
        }
        action("approve").assertHeightIsAtLeast(48.dp)
        action("decline").assertHeightIsAtLeast(48.dp)
        RequestQueueView.entries.forEach { compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + it.wire).assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithTag("request-media:movie").assertHeightIsAtLeast(48.dp)
    }

    // ---- Notes ------------------------------------------------------------------------------------------------------

    @Test
    fun declineAsksForAnOptionalNoteFirst() {
        load(queueRequest())
        show()
        action("decline").performClick()
        inDialog("Decline request").assertIsDisplayed()
        inDialog("\"Dune\" will be marked declined. Add an optional note for the requester.").assertIsDisplayed()
        assertTrue("nothing runs until the note is confirmed", performed.isEmpty())
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("duplicate")
        dialogButton("Decline").performClick()
        compose.waitForIdle()
        val ran = performed.single()
        assertEquals(RequestQueueAction.Decline, ran.action)
        assertEquals("duplicate", ran.note)
    }

    @Test
    fun keepingTheRequestRunsNothing() {
        load(queueRequest())
        show()
        action("decline").performClick()
        dialogButton("Keep request").performClick()
        compose.waitForIdle()
        assertTrue(performed.isEmpty())
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun cancellingAFailedRequestIsClosingItAndAnApprovedOneIsWithdrawing() {
        load(queueRequest(id = "f", title = "Failing", status = "queued", outcome = "failed"), view = RequestQueueView.Failed)
        show()
        action("retry").assertIsDisplayed()
        action("cancel").performClick()
        inDialog("Close request").assertIsDisplayed()
        inDialog("\"Failing\" failed. Closing it moves it to Done, and nothing more is sent for it. Add an optional note.").assertIsDisplayed()
    }

    @Test
    fun cancellingARequestNothingWasSentForWithdrawsIt() {
        load(queueRequest(status = "approved", state = "approved"), view = RequestQueueView.InProgress)
        show()
        action("cancel").performClick()
        inDialog("\"Dune\" will be withdrawn before anything is sent for it. Add an optional note.").assertIsDisplayed()
        dialogButton("Cancel request").performClick()
        compose.waitForIdle()
        assertEquals(RequestQueueAction.Cancel, performed.single().action)
    }

    @Test
    fun retryRunsAtOnceWithoutANote() {
        load(queueRequest(status = "queued", outcome = "failed"), view = RequestQueueView.Failed)
        show()
        action("retry").performClick()
        compose.waitForIdle()
        assertEquals(RequestQueueAction.Retry, performed.single().action)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun aDoneRequestOffersNoActions() {
        load(queueRequest(status = "completed", state = "available"), view = RequestQueueView.Done)
        show()
        compose.onNodeWithText("Available").assertIsDisplayed()
        action("approve").assertDoesNotExist()
        action("cancel").assertDoesNotExist()
        action("retry").assertDoesNotExist()
    }

    // ---- Views, filters, paging ---------------------------------------------------------------------------------

    @Test
    fun theFourViewsShowTheirCountsAndTheOpenOneIsSelected() {
        load(queueRequest())
        show()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "needs_approval").assertIsSelected()
        compose.onNodeWithContentDescription("Needs approval, 1 requests").assertExists()
        compose.onNodeWithContentDescription("In progress, 2 requests").assertExists()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "in_progress").performClick()
        assertEquals(listOf("view:in_progress"), calls)
    }

    @Test
    fun theLastViewIsReachableOnTheSmallScreenByScrollingTheRow() {
        load(queueRequest())
        show()
        compose.onNodeWithTag(REQUEST_VIEW_PICKER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "done").assertIsDisplayed().performClick()
        assertEquals(listOf("view:done"), calls)
    }

    @Test
    fun searchRunsAfterTypingPausesAndFiltersGoToThePage() {
        load(queueRequest())
        show()
        compose.onNode(hasText("Search requests")).performTextInput("dune")
        compose.waitUntil(5_000) { calls.contains("search:dune") }
        compose.onNodeWithTag("request-media:series").performClick()
        compose.onNodeWithTag("request-media:movie").performClick()
        assertEquals(listOf("search:dune", "media:series", "media:movie"), calls)
    }

    @Test
    fun anAccountFilterShowsWhoAndClearsOnTap() {
        state = RequestQueueState(
            hasLoaded = true, requests = listOf(queueRequest()), userFilter = "68", requesterNames = mapOf("68" to "alex"),
            counts = RequestCounts(1, 0, 0, 0),
        )
        show()
        compose.onNodeWithText("Requested by alex").assertIsDisplayed()
        compose.onNodeWithTag("request-user-filter").performClick()
        assertEquals(listOf("clear-user"), calls)
    }

    @Test
    fun longPressingARowOffersOnlyThisAccountsRequests() {
        load(queueRequest())
        show()
        compose.onNodeWithTag(REQUEST_ROW_TAG_PREFIX + "r1").performTouchInput { longClick(center.copy(y = 48f)) }
        compose.onNodeWithText("Show only this account's requests").assertIsDisplayed().performClick()
        assertEquals(listOf("user:68"), calls)
    }

    @Test
    fun theRowMenuOffersTheSameForTalkBackAndTouch() {
        load(queueRequest())
        show()
        compose.onNodeWithContentDescription("More about Dune").performClick()
        compose.onNodeWithText("Show only this account's requests").assertIsDisplayed()
    }

    @Test
    fun aRowWithoutARequesterHasNoAccountFilter() {
        load(queueRequest(requestedBy = null))
        show()
        compose.onNodeWithContentDescription("More about Dune").assertDoesNotExist()
    }

    @Test
    fun loadMoreAsksForTheNextPageAndAFailedPageSaysSo() {
        load(queueRequest(), next = "c1")
        show()
        scrollTo(REQUEST_LOAD_MORE_TAG)
        compose.onNodeWithText("Load more").assertIsEnabled().performClick()
        assertEquals(listOf("more"), calls)
        state = state.copy(moreFailed = true)
        compose.onNodeWithText("More requests couldn't be loaded").assertIsDisplayed()
        state = state.copy(nextCursor = null, moreFailed = false)
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test
    fun whileTheNextPageLoadsTheButtonWaits() {
        load(queueRequest(), next = "c1")
        state = state.copy(loadingMore = true)
        show()
        scrollTo(REQUEST_LOAD_MORE_TAG)
        compose.onNode(hasTestTag(REQUEST_LOAD_MORE_TAG) and hasText("Loading…")).assertIsNotEnabled()
    }

    // ---- States -----------------------------------------------------------------------------------------------------

    @Test
    fun anEmptyViewSaysAllClearInTheWebsWords() {
        load(view = RequestQueueView.Failed)
        show()
        compose.onNodeWithText("All clear").assertIsDisplayed()
        compose.onNodeWithText("No request has failed").assertIsDisplayed()
    }

    @Test
    fun anEmptyFilteredViewSaysNoMatchingRequests() {
        state = RequestQueueState(hasLoaded = true, search = "zzz", counts = RequestCounts(1, 2, 3, 4))
        show()
        compose.onNodeWithText("No matching requests").assertIsDisplayed()
        compose.onNodeWithText("No request in this view matches the filters").assertIsDisplayed()
    }

    @Test
    fun aFailedQueueOffersARetry() {
        state = RequestQueueState(hasLoaded = true)
        loadError = ErrorText.Raw("Server unreachable")
        show()
        compose.onNodeWithText("Requests failed").assertIsDisplayed()
        compose.onNodeWithText("Server unreachable").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun aRefusedActionShowsAboveTheQueueAndDismisses() {
        load(queueRequest())
        actionError = ErrorText.Raw("Already approved")
        show()
        compose.onNodeWithText("Already approved").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
        compose.onNodeWithText("Dune").assertIsDisplayed()
    }

    @Test
    fun beforeTheFirstReadTheQueueIsLoading() {
        state = RequestQueueState()
        show()
        compose.onNodeWithTag("state-loading").assertIsDisplayed()
    }

    // ---- Details ----------------------------------------------------------------------------------------------------

    private fun open(title: String = "Dune") = compose.onNodeWithText(title).performClick()

    @Test
    fun aTapOpensTheDetailsWithEverythingTheWebShows() {
        history = listOf(
            RequestEvent("e2", "status_queued", "sent to Radarr 4K", "admin", "2026-11-14T08:00:00.000Z"),
            RequestEvent("e1", "something_new", null, null, "2026-11-14T07:00:00.000Z"),
        )
        load(
            queueRequest(
                mediaType = "series", seasons = emptyList(), status = "approved", state = "approved", overview = "A mythic journey.",
                createdAt = java.time.Instant.parse("2026-11-14T06:00:00Z"),
            ).copy(approved_at = "2026-11-14T07:30:00Z", completed_at = "2026-11-14T09:00:00Z", targets = listOf(sent)),
            view = RequestQueueView.InProgress,
        )
        show()
        open()
        compose.onNodeWithTag(REQUEST_DETAIL_TAG).assertIsDisplayed()
        listOf("Requested by", "alex", "State", "Requested", "Approved", "Completed", "Whole series", "TMDB ID", "438631", "A mythic journey.", "Radarr 4K")
            .forEach(::detailHas)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Sent to the server").assertExists() }.isSuccess }
        detailHas("sent to Radarr 4K")
        detailHas("something_new")
    }

    @Test
    fun seasonsReadAsRunsInTheDetails() {
        load(queueRequest(mediaType = "series", seasons = listOf(3, 1, 2, 5)))
        show()
        open()
        detailHas("Seasons 1–3, 5")
    }

    @Test
    fun theHistoryOfARequestWithNoneSaysSoAndAFailedReadSaysSo() {
        load(queueRequest())
        show()
        open()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("No history yet").assertExists() }.isSuccess }
        compose.onNodeWithContentDescription("Back to requests").performClick()
        compose.onNodeWithTag(REQUEST_DETAIL_TAG).assertDoesNotExist()

        historyFails = true
        open()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("The history couldn't be loaded").assertExists() }.isSuccess }
    }

    @Test
    fun backClosesTheDetails() {
        load(queueRequest())
        show()
        open()
        compose.onNodeWithTag(REQUEST_DETAIL_TAG).assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag(REQUEST_DETAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun anActionInTheDetailsRunsAndClosesThemWhenTheServerTakesIt() {
        load(queueRequest())
        show()
        open()
        compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + "approve") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).performClick()
        compose.waitForIdle()
        assertEquals(RequestQueueAction.Approve, performed.single().action)
        compose.onNodeWithTag(REQUEST_DETAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun aRefusedActionInTheDetailsStaysOpenAndSaysWhy() {
        performResult = false
        actionError = ErrorText.Raw("Already approved")
        load(queueRequest())
        show()
        open()
        compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + "approve") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(REQUEST_DETAIL_TAG).assertIsDisplayed()
        compose.onNode(hasText("Already approved") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).assertExists()
    }

    @Test
    fun theDetailsFollowTheRowAsItChanges() {
        load(queueRequest())
        show()
        open()
        compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + "approve") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).assertExists()
        load(queueRequest(status = "approved", state = "approved"), view = RequestQueueView.InProgress)
        compose.waitForIdle()
        compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + "approve") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).assertDoesNotExist()
        compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + "cancel") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).assertExists()
    }

    // ---- Page swipe -------------------------------------------------------------------------------------------------

    @Test
    fun theViewRowScrollsSidewaysWithoutChangingPagesButASwipeElsewhereDoes() {
        load(queueRequest(), queueRequest(id = "b", title = "Beta"))
        var selected by mutableStateOf("work.requests")
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalPosterLoader provides { _ -> null }) {
                    AdminShell(
                        AdminShellContext("Loft", false), singleServerDestinations(false), selected, { selected = it }, false, {},
                    ) {
                        RequestsContent(
                            state = state, loadError = null, actionError = null, pendingKeys = emptySet(),
                            onSelectView = {}, onSearch = {}, onMedia = {}, onFilterUser = {}, onClearUserFilter = {},
                            onAction = { _, _, _ -> }, onOpen = { _, _ -> }, onLoadMore = {}, onRetry = {}, onDismissError = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag(REQUEST_VIEW_PICKER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("the chips took the swipe", "work.requests", selected)
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "done").assertIsDisplayed()

        compose.onNodeWithTag("shell-page").performTouchInput { swipeLeft(startX = right - 8f, endX = left + 8f, durationMillis = 200) }
        compose.waitForIdle()
        assertFalse("a swipe over the rows changes pages", selected == "work.requests")
    }

    // ---- All servers ------------------------------------------------------------------------------------------------

    private val serverA = AdminServer("srv-a", "https://a.test", "Server A")
    private val serverB = AdminServer("srv-b", "https://b.test", "Server B")

    private var sections by mutableStateOf<List<ServerSectionResult<List<MediaRequest>>>>(emptyList())

    private fun showAggregate() {
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalPosterLoader provides { _ -> null }) {
                    val flow = RequestFlow(
                        perform = { action, server, request, note -> performed += Performed(action, server, request.id, note); true },
                        loadEvents = { _, _ -> emptyList() },
                        current = { server, id -> sections.firstOrNull { it.server.id == server }?.result?.getOrNull()?.firstOrNull { it.id == id } },
                        requester = { server, request ->
                            RequestQueue.requesterLabel(compose.activity.resources, request, mapOf("srv-a" to mapOf("68" to "alex-a"), "srv-b" to mapOf("68" to "bea-b"))[server].orEmpty())
                        },
                        serverName = { server -> listOf(serverA, serverB).firstOrNull { it.id == server }?.displayName },
                    )
                    val ui = rememberRequestFlowState(flow)
                    AggregateRequestsContent(
                        sections = sections,
                        names = mapOf("srv-a" to mapOf("68" to "alex-a"), "srv-b" to mapOf("68" to "bea-b")),
                        actionError = null, pendingKeys = emptySet(), pendingKey = { s, r -> "${s.id}:${r.id}" },
                        onAction = ui::act, onOpen = ui::open, onSignIn = { calls += "sign-in:${it.id}" },
                        onRetrySection = { calls += "retry:${it.id}" }, onDismissError = {},
                    )
                    RequestFlowDialogs(flow, ui)
                }
            }
        }
    }

    @Test
    fun allServersShowsOneSectionPerServerWithSummedCountsAndOpensOnWhatNeedsApproval() {
        sections = listOf(
            ServerSectionResult(serverA, Result.success(listOf(queueRequest(id = "1", title = "Alpha"), queueRequest(id = "2", title = "Aprov", status = "approved")))),
            ServerSectionResult(serverB, Result.success(listOf(queueRequest(id = "1", title = "Beta"), queueRequest(id = "3", title = "Bfail", status = "queued", outcome = "failed")))),
        )
        showAggregate()
        compose.onNodeWithContentDescription("Needs approval, 2 requests").assertExists()
        compose.onNodeWithContentDescription("In progress, 1 requests").assertExists()
        compose.onNodeWithContentDescription("Failed, 1 requests").assertExists()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "needs_approval").assertIsSelected()
        compose.onNodeWithText("Server A").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("alex-a · 3 hours ago").assertIsDisplayed()
        compose.onNodeWithText("Aprov").assertDoesNotExist()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "failed").performClick()
        compose.onNodeWithText("Bfail").assertIsDisplayed()
        compose.onNodeWithText("No request has failed").assertIsDisplayed()
    }

    @Test
    fun allServersOpensOnInProgressWhenNothingWaits() {
        sections = listOf(ServerSectionResult(serverA, Result.success(listOf(queueRequest(id = "2", title = "Aprov", status = "approved")))))
        showAggregate()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "in_progress").assertIsSelected()
        compose.onNodeWithText("Aprov").assertIsDisplayed()
    }

    @Test
    fun allServersActsOnTheRowsOwnServerEvenWhenIdsRepeat() {
        sections = listOf(
            ServerSectionResult(serverA, Result.success(listOf(queueRequest(id = "1", title = "Alpha")))),
            ServerSectionResult(serverB, Result.success(listOf(queueRequest(id = "1", title = "Beta")))),
        )
        showAggregate()
        compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(REQUEST_ROW_TAG_PREFIX + "srv-b:1"))
        compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + "approve") and hasAnyAncestor(hasTestTag(REQUEST_ROW_TAG_PREFIX + "srv-b:1"))).performClick()
        compose.waitForIdle()
        assertEquals("srv-b", performed.single().server)
        assertEquals("1", performed.single().request)
    }

    @Test
    fun allServersDetailsNameTheServerAndUseItsAccountNames() {
        sections = listOf(ServerSectionResult(serverB, Result.success(listOf(queueRequest(id = "1", title = "Beta")))))
        showAggregate()
        compose.onNodeWithText("Beta").performClick()
        compose.onNode(hasText("Server B") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).assertExists()
        compose.onNode(hasText("bea-b") and hasAnyAncestor(hasTestTag(REQUEST_DETAIL_TAG))).assertExists()
    }

    @Test
    fun allServersKeepsAHealthyServerWhenAnotherCannotBeRead() {
        sections = listOf(
            ServerSectionResult(serverA, Result.failure(RuntimeException("down"))),
            ServerSectionResult(serverB, Result.success(listOf(queueRequest(id = "1", title = "Beta")))),
        )
        showAggregate()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("Can't reach Server A. Tap to try again").assertIsDisplayed().performClick()
        assertEquals(listOf("retry:srv-a"), calls)
    }
}
