package com.bloemstudios.admin.requests

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bulk triage, and an unavailable queue, on a device: the real page and view model against a scripted server. */
@RunWith(AndroidJUnit4::class)
class RequestBulkScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(server: RequestServerFixture) {
        compose.setContent {
            VeilAdminTheme {
                CompositionLocalProvider(LocalPosterLoader provides { _ -> null }) { RequestsScreen(server.client) }
            }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** The queue is a lazy list: on a small screen only the first rows are composed, so bring the row into view first. */
    private fun scrollToRow(id: String) {
        compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(REQUEST_ROW_TAG_PREFIX + id))
    }

    private fun three(server: RequestServerFixture) {
        server.pages += server.page(listOf(queueRequest("r1", "Dune"), queueRequest("r2", "Severance"), queueRequest("r3", "Planet Earth")))
    }

    private fun approvePath(id: String) = "/api/v2/admin/requests/$id/approve"

    @Test
    fun aLongPressStartsSelectingAndApproveNSendsOneApprovePerRequest() {
        val server = RequestServerFixture(counts = """{"needs_approval":3,"in_progress":0,"failed":0,"done":0}""")
        three(server)
        server.pages += server.page(emptyList())
        show(server)
        waitForText("Dune")

        scrollToRow("r1")
        // Press near the title: on a narrow screen the row's centre falls on its Approve and Decline buttons, which take the press.
        compose.onNodeWithTag(REQUEST_ROW_TAG_PREFIX + "r1").performTouchInput { longClick(percentOffset(0.5f, 0.12f)) }
        // The select bar is a list item above the rows, so on a small screen it has to be scrolled back to.
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasTestTag(REQUEST_SELECT_BAR_TAG)) }.isSuccess
        }
        waitForTag(REQUEST_SELECT_BAR_TAG)
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        compose.onNodeWithTag(REQUEST_SELECT_ALL_TAG).performClick()
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        compose.onNodeWithText("Approve 3").assertIsDisplayed()
        compose.onNodeWithTag(REQUEST_BULK_APPROVE_TAG).performClick()

        compose.waitUntil(10_000) { server.posts.size == 3 }
        assertEquals(listOf("r1", "r2", "r3").map(::approvePath), server.posts.map { it.path }.sorted())
        assertTrue("no note goes with an approval", server.posts.all { it.body == "{}" })
        waitForText("Approved 3 requests")
        compose.onNodeWithTag(REQUEST_LIST_TAG).performScrollToNode(hasText("Nothing is waiting for approval"))
        compose.onNodeWithText("Nothing is waiting for approval").assertIsDisplayed()
    }

    @Test
    fun selectAllThenDeclineSendsTheOneNoteToEveryRequest() {
        val server = RequestServerFixture(counts = """{"needs_approval":3,"in_progress":0,"failed":0,"done":0}""")
        three(server)
        server.pages += server.page(emptyList())
        show(server)
        waitForText("Dune")

        compose.onNodeWithTag(REQUEST_SELECT_START_TAG).performClick()
        compose.onNodeWithTag(REQUEST_SELECT_ALL_TAG).performClick()
        compose.onNodeWithTag(REQUEST_BULK_DECLINE_TAG).performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("duplicate")
        compose.onNode(hasText("Decline 3") and hasAnyAncestor(isDialog()) and androidx.compose.ui.test.hasClickAction()).performClick()

        compose.waitUntil(10_000) { server.posts.size == 3 }
        assertTrue(server.posts.all { it.path.endsWith("/decline") })
        assertTrue(server.posts.all { it.body == """{"reason":"duplicate"}""" })
    }

    @Test
    fun aRefusedRequestStaysSelectedWithItsReasonAndTheRestAreDone() {
        val server = RequestServerFixture(counts = """{"needs_approval":3,"in_progress":0,"failed":0,"done":0}""")
        server.refused["r2"] = "Another admin already approved this request"
        three(server)
        server.pages += server.page(listOf(queueRequest("r2", "Severance")))
        show(server)
        waitForText("Dune")

        compose.onNodeWithTag(REQUEST_SELECT_START_TAG).performClick()
        compose.onNodeWithTag(REQUEST_SELECT_ALL_TAG).performClick()
        compose.onNodeWithTag(REQUEST_BULK_APPROVE_TAG).performClick()

        waitForTag(REQUEST_BULK_RESULT_TAG)
        compose.onNodeWithText("Severance: Another admin already approved this request").assertIsDisplayed()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Approve 1").assertIsEnabled()
        assertEquals(0, compose.onAllNodesWithText("Dune").fetchSemanticsNodes().size)
    }

    @Test
    fun doneLeavesSelectMode() {
        val server = RequestServerFixture()
        three(server)
        show(server)
        waitForText("Dune")
        compose.onNodeWithTag(REQUEST_SELECT_START_TAG).performClick()
        compose.onNodeWithTag(REQUEST_SELECT_DONE_TAG).performClick()
        waitForTag(REQUEST_SELECT_START_TAG)
        assertEquals(0, compose.onAllNodesWithTag(REQUEST_SELECT_BAR_TAG).fetchSemanticsNodes().size)
        // Each row, scrolled into view in turn (a small screen composes only the rows it shows), has its own Approve button again.
        listOf("r1", "r2", "r3").forEach { id ->
            scrollToRow(id)
            val approveInRow = hasTestTag(REQUEST_ACTION_TAG_PREFIX + "approve") and hasAnyAncestor(hasTestTag(REQUEST_ROW_TAG_PREFIX + id))
            assertEquals("$id has its own Approve button again", 1, compose.onAllNodes(approveInRow, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun nothingCanBeSentWithNothingSelected() {
        val server = RequestServerFixture()
        three(server)
        show(server)
        waitForText("Dune")
        compose.onNodeWithTag(REQUEST_SELECT_START_TAG).performClick()
        compose.onNodeWithTag(REQUEST_BULK_APPROVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(REQUEST_BULK_DECLINE_TAG).assertIsNotEnabled()
    }

    @Test
    fun anUnavailableServerSaysSoPlainlyWithNoRetryAndNothingIsRead() {
        val server = RequestServerFixture(capabilities = """{"available":false}""")
        show(server)
        waitForTag(REQUEST_UNAVAILABLE_TAG)
        compose.onNodeWithText("Request administration isn't available on this server").assertIsDisplayed()
        assertEquals("no Retry", 0, compose.onAllNodesWithText("Retry").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Try again").fetchSemanticsNodes().size)
        compose.waitForIdle()
        assertEquals("the queue was never read", 0, server.queueReads.size)
        assertTrue(server.calls.none { it.path == "/api/v2/admin/requests/counts" })
    }
}
