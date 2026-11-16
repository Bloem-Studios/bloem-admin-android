package com.bloemstudios.admin.requests

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real page and view model against a scripted server: what it reads, what it sends, and what the person sees. */
@RunWith(AndroidJUnit4::class)
class RequestsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(server: RequestServerFixture) {
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalPosterLoader provides { _ -> null }) { RequestsScreen(server.client) }
            }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun action(name: String) = compose.onNode(hasTestTag(REQUEST_ACTION_TAG_PREFIX + name))

    @Test
    fun opensOnNeedsApprovalNamesTheRequesterAndApprovesAPendingRequest() {
        val server = RequestServerFixture()
        server.pages += server.page(listOf(queueRequest()))
        server.pages += server.page(emptyList())
        show(server)
        waitForText("Dune")

        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "needs_approval").assertIsSelected()
        assertEquals("needs_approval", server.queueReads.first().query["view"])
        assertEquals("50", server.queueReads.first().query["limit"])
        compose.onNodeWithText("alex · 3 hours ago").assertIsDisplayed()

        action("approve").performClick()
        compose.waitUntil(10_000) { server.queueReads.size >= 2 }

        val post = server.posts.single()
        assertEquals("/api/v2/admin/requests/r1/approve", post.path)
        assertEquals("{}", post.body)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Dune")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Nothing is waiting for approval").assertIsDisplayed()
    }

    @Test
    fun opensOnInProgressWhenNothingWaitsForApproval() {
        val server = RequestServerFixture(counts = """{"needs_approval":0,"in_progress":2,"failed":0,"done":4}""")
        server.pages += server.page(listOf(queueRequest(status = "approved", state = "approved")))
        show(server)
        waitForText("Dune")
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "in_progress").assertIsSelected()
        assertEquals("in_progress", server.queueReads.first().query["view"])
    }

    @Test
    fun declineSendsTheNoteAndAPickedViewIsKept() {
        val server = RequestServerFixture()
        server.pages += server.page(listOf(queueRequest()))
        show(server)
        waitForText("Dune")

        action("decline").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("duplicate")
        compose.onNode(hasText("Decline") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { server.posts.isNotEmpty() }
        assertEquals("""{"reason":"duplicate"}""", server.posts.single().body)

        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "failed").performClick()
        compose.waitUntil(10_000) { server.queueReads.any { it.query["view"] == "failed" } }
        compose.waitForIdle()
        compose.onNodeWithTag(REQUEST_VIEW_TAG_PREFIX + "failed").assertIsSelected()
    }

    @Test
    fun aRefusedActionIsShownAndTheRowStays() {
        val server = RequestServerFixture(actionStatus = HttpStatusCode.Conflict)
        server.pages += server.page(listOf(queueRequest()))
        show(server)
        waitForText("Dune")

        action("approve").performClick()
        waitForText("Already approved")
        compose.onNodeWithText("Dune").assertIsDisplayed()
        assertEquals("no reload after a refusal", 1, server.queueReads.size)
    }

    @Test
    fun filtersAndSearchReachTheServer() {
        val server = RequestServerFixture()
        server.pages += server.page(listOf(queueRequest()))
        show(server)
        waitForText("Dune")

        compose.onNodeWithTag("request-media:series").performClick()
        compose.waitUntil(10_000) { server.queueReads.any { it.query["media_type"] == "series" } }

        compose.onNode(hasText("Search requests")).performTextInput("dune")
        compose.waitUntil(10_000) { server.queueReads.any { it.query["q"] == "dune" } }

        // On Needs approval a long press starts selecting; the row's own menu still filters to the account.
        compose.onNodeWithContentDescription("More about Dune").performClick()
        compose.onNodeWithText("Show only this account's requests").performClick()
        compose.waitUntil(10_000) { server.queueReads.any { it.query["requested_by_user_id"] == "68" } }
        compose.onNodeWithText("Requested by alex").assertIsDisplayed()

        compose.onNodeWithTag("request-user-filter").performClick()
        compose.waitUntil(10_000) { server.queueReads.last().query["requested_by_user_id"] == null }
    }

    @Test
    fun loadMoreFollowsTheCursorAndKeepsTheRows() {
        val server = RequestServerFixture()
        server.pages += server.page(listOf(queueRequest(id = "a", title = "Alpha")), next = "c1")
        server.pages += server.page(listOf(queueRequest(id = "b", title = "Beta")))
        show(server)
        waitForText("Alpha")

        compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(REQUEST_LOAD_MORE_TAG))
        compose.onNodeWithTag(REQUEST_LOAD_MORE_TAG).performClick()
        compose.waitUntil(10_000) { server.queueReads.size == 2 }

        assertEquals("c1", server.queueReads.last().query["cursor"])
        assertEquals("needs_approval", server.queueReads.last().query["view"])
        compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(REQUEST_ROW_TAG_PREFIX + "b"))
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(REQUEST_ROW_TAG_PREFIX + "a"))
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun theDetailsReadTheRequestsHistoryFromTheServer() {
        val server = RequestServerFixture(
            events = """{"items":[{"id":"e1","type":"status_queued","message":"sent to Radarr 4K","actor_username":"admin","created_at":"2026-11-14T08:00:00.000Z"},
                {"id":"e0","type":"warp_drive","created_at":"2026-11-14T07:00:00.000Z"}]}""",
        )
        server.pages += server.page(listOf(queueRequest()))
        show(server)
        waitForText("Dune")

        compose.onNodeWithText("Dune").performClick()
        waitForText("Sent to the server")
        compose.onNodeWithText("warp_drive").assertIsDisplayed()
        assertTrue(server.calls.any { it.path == "/api/v2/admin/requests/r1/events" })
    }

    @Test
    fun aFailedQueueSaysSoAndRetryRecovers() {
        val server = RequestServerFixture(counts = "not json")
        show(server)
        waitForText("Requests failed")
        server.counts = """{"needs_approval":0,"in_progress":0,"failed":0,"done":0}"""
        compose.onNodeWithText("Try again").performClick()
        waitForText("All clear")
    }
}
