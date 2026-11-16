package com.bloemstudios.admin.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.dashboard.DashboardSection
import com.bloemstudios.admin.fixtures.FixtureIncidentData
import com.bloemstudios.admin.fixtures.FixtureServer
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback history, Logs, Audit and the dashboard figures, drawn by the real screens from sample data (a mock server in
 * FixtureIncidentData): what each row and sheet shows, and what a filter does to the list.
 */
@RunWith(AndroidJUnit4::class)
class IncidentTracingTest {
    @get:Rule val compose = createComposeRule()

    private fun show(page: String, focus: LogsFocus? = null, fontScale: Float? = null) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
            VeilAdminTheme { FixtureServer(page, logsFocus = focus) }
        }
    }

    private fun ComposeContentTestRule.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeContentTestRule.waitForText(text: String, substring: Boolean = false) =
        waitUntil(15_000) { has(text, substring) }

    private fun ComposeContentTestRule.waitForNoText(text: String) = waitUntil(15_000) { !has(text) }

    // ---- Playback history ----------------------------------------------------------------------------------

    @Test fun historyRowReadsEpisodeCodeProfileMethodAndWatchTime() {
        show("live.activity")
        compose.waitForText("Severance")
        compose.onNodeWithText("S02E03 · Who Is Alive?").assertIsDisplayed()
        assertTrue(compose.has("maria · Living room"))
        assertTrue(compose.has("43m 30s of 44m 12s"))
        assertTrue(compose.has("Direct play"))
        // The unmeasured play (the last row) shows its estimate, marked.
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("history-row-h5"))
        compose.waitForText("≈ ", substring = true)
        assertTrue(compose.has("Direct stream"))
    }

    @Test fun userFilterPicksByNameAndNarrowsTheList() {
        show("live.activity")
        compose.waitForText("Dune: Part Two")
        compose.onNodeWithTag("history-filter-user").performClick()
        compose.waitForText("jonas")
        compose.onNodeWithText("jonas").performClick()
        compose.waitForNoText("Dune: Part Two")
        assertTrue(compose.has("Planet Earth III"))
        // The profile picker now has that user's profile to offer.
        compose.onNodeWithTag("history-filter-profile").performClick()
        compose.waitForText("Jonas")
    }

    @Test fun partialFilterKeepsOnlyPlaysThatBrokeOff() {
        show("live.activity")
        compose.waitForText("Severance")
        compose.onNodeWithTag("history-filter-status-2").performClick()
        compose.waitForNoText("Severance")
        assertTrue(compose.has("Dune: Part Two"))
        assertTrue(compose.has("Planet Earth III"))
    }

    @Test fun tappingAPlayOpensItsDetailsWithCopyShareAndLogs() {
        show("live.activity")
        compose.waitForText("Dune: Part Two")
        compose.onNodeWithTag("history-row-h2").performClick()
        compose.waitForText("Playback session")
        assertTrue(compose.has("Position at end"))
        assertTrue(compose.has("Ended by an administrator").not())
        assertTrue(compose.has("Client stopped responding"))
        listOf("history-detail-logs", "history-detail-ffmpeg", "history-detail-copy", "history-detail-share").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("history-detail-copy").performClick()
        compose.waitForText("Copied")
    }

    // ---- Logs -----------------------------------------------------------------------------------------------

    @Test fun logRowOpensDetailsWithRequestMethodStatusAndAttributes() {
        show("system.logs")
        compose.waitForText("Transcode failed", substring = true)
        compose.onNodeWithTag("log-row-l1").performClick()
        compose.waitForText("Request ID")
        assertTrue(compose.has("req-4f9a21c07b"))
        assertTrue(compose.has("GET"))
        assertTrue(compose.has("500"))
        assertTrue(compose.has("1842 ms"))
        assertTrue(compose.has("198.51.100.7"))
        assertTrue(compose.has("/api/v2/playback/transcode/segment/seg_0042.ts", substring = true))
        compose.onNodeWithTag("log-detail-copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("log-detail-share").performScrollTo().assertIsDisplayed()
    }

    @Test fun theDetailLinksToTheLogsOfItsPlaybackSession() {
        show("system.logs")
        compose.waitForText("Transcode failed", substring = true)
        compose.onNodeWithTag("log-row-l1").performClick()
        compose.waitForText("Logs for this playback session")
        compose.onNodeWithTag("log-detail-session-logs").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.hasRow("l1") && !compose.hasRow("l5") }
        compose.waitForText("Playback session")
        compose.onNodeWithTag("logs-session-summary").assertIsDisplayed()
    }

    private fun ComposeContentTestRule.hasRow(id: String, prefix: String = "log-row-") =
        onAllNodes(androidx.compose.ui.test.hasTestTag(prefix + id)).fetchSemanticsNodes().isNotEmpty()

    @Test fun openedOnAPlaybackSessionForFfmpegShowsTheSummaryAndOnlyFfmpegLines() {
        show("system.logs", LogsFocus(FixtureIncidentData.TRANSCODE_SESSION, ffmpegOnly = true))
        compose.waitUntil(15_000) { compose.hasRow("logs-session-summary", prefix = "") }
        compose.onNodeWithTag("logs-session-summary").assertIsDisplayed()
        // Three FFmpeg lines are loaded for the session, and its one audit entry is counted from the audit page.
        compose.waitUntil(15_000) { compose.has("3") && compose.has("1") }
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("log-row-l4"))
        assertTrue(compose.has("Input #0, matroska", substring = true))
        assertTrue("The playback line is not FFmpeg", compose.hasRow("l1").not())
        assertTrue(compose.has("All components"))
        // Back to every component for the session: the error line returns.
        compose.onNodeWithTag("logs-summary-ffmpeg").performClick()
        compose.waitUntil(15_000) { compose.hasRow("l1") }
    }

    @Test fun warnAndErrorChoiceHidesInfoAndDebugLines() {
        show("system.logs")
        compose.waitUntil(15_000) { compose.hasRow("l3") }
        compose.onNodeWithTag("logs-level-WarnError").performScrollTo().performClick()
        compose.waitUntil(15_000) { !compose.hasRow("l3") && compose.hasRow("l6") }
        assertTrue(compose.hasRow("l1"))
        assertTrue(compose.hasRow("l2"))
    }

    @Test fun requestIdFilterNarrowsToOneRequest() {
        show("system.logs")
        compose.waitUntil(15_000) { compose.hasRow("l1") }
        compose.onNodeWithTag("logs-filters-toggle").performClick()
        compose.waitForText("Request ID")
        compose.onNodeWithTag("logs-filter-request-id").performClick()
        compose.onNodeWithTag("logs-filter-request-id").performTextInput("req-81c3d7e2aa")
        // The filter fields push the lines down the page, so scroll to the one request's line once it has loaded.
        compose.waitUntil(15_000) {
            runCatching { compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("log-row-l5")) }.isSuccess
        }
        assertTrue(compose.hasRow("l5"))
        assertTrue(compose.hasRow("l1").not())
    }

    // ---- Audit ----------------------------------------------------------------------------------------------

    @Test fun auditRowLeadsWithTheActionAndItsBeforeAndAfterLines() {
        show("system.audit")
        compose.waitForText("Update user")
        compose.onNodeWithText("max streams: 2 → 4", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("role: user → admin", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("PATCH /api/v2/admin/users/3", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun auditPinChangeShowsSetNotThePinAndARequestWithoutAnActionSaysSo() {
        show("system.audit")
        compose.waitForText("Update user")
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("audit-row-a4"))
        compose.waitForText("HTTP request")
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasTestTag("audit-row-a3"))
        compose.waitForText("pin: set")
    }

    @Test fun auditDetailShowsAddressAgentDurationAndRequestId() {
        show("system.audit")
        compose.waitForText("Update user")
        compose.onNodeWithTag("audit-row-a1").performClick()
        compose.waitForText("User agent")
        assertTrue(compose.has("Bloem Admin/1.0 (Android 15)"))
        assertTrue(compose.has("41 ms"))
        assertTrue(compose.has("req-5d20c9ee14"))
        assertTrue(compose.has("192.0.2.10"))
        assertTrue(compose.has("main"))
        compose.onNodeWithTag("audit-detail-copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audit-detail-share").performScrollTo().assertIsDisplayed()
    }

    @Test fun auditUserFilterPicksByNameAndIncludesImpersonation() {
        show("system.audit")
        compose.waitForText("Update user")
        compose.onNodeWithTag("audit-filter-user").performClick()
        compose.waitForText("jonas")
        compose.onNodeWithText("jonas").performClick()
        compose.waitForNoText("Update user")
        assertTrue(compose.has("Update profile"))
        assertTrue(compose.has("pat as jonas", substring = true))
    }

    // ---- Dashboard figures ----------------------------------------------------------------------------------

    private fun figureLabelsStayOnOneLine(fontScale: Float) {
        show("overview.dashboard", fontScale = fontScale)
        compose.waitForText("Profiles · 24h")
        // Profiles and Movies share a row: if "Profiles · 24h" wrapped, its figure would be a line taller than Movies'.
        val profiles = compose.onNodeWithTag("dashboard-tile-ProfilesActive").getBoundsInRoot()
        val movies = compose.onNodeWithTag("dashboard-tile-Movies").getBoundsInRoot()
        assertEquals("Profiles · 24h must not wrap at font scale $fontScale", movies.height, profiles.height)
    }

    @Test fun theFourFiguresReadOnOneLineEachAtPhoneWidth() = figureLabelsStayOnOneLine(1.0f)

    @Test fun theFourFiguresReadOnOneLineEachAtFontScale13() = figureLabelsStayOnOneLine(1.3f)

    @Test fun hostResourcesAreLabelledFiguresNotARunOnSentence() {
        show("overview.dashboard", fontScale = 1.3f)
        compose.waitForText("Server health")
        compose.onNodeWithTag(DashboardSection.Health.tag).performScrollTo()
        compose.waitForText("Network in")
        listOf("CPU", "Memory", "Network out", "Fullest disk", "GPU busy").forEach { label ->
            assertTrue("$label figure is missing", compose.has(label))
        }
        assertTrue(compose.has("87%"))
        assertTrue(compose.has("VRAM", substring = true))
        assertTrue("A rate must not break inside its unit", compose.has("MB/ s").not())
    }
}
