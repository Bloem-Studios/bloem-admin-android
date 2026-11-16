package com.bloemstudios.admin.libraries

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.fixtures.LibraryFixtures
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.ui.components.ServerGroupHeader
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Libraries page on a device, against the scripted server in [LibraryFixtures]: no credentials, no network. */
@RunWith(AndroidJUnit4::class)
class LibrariesScreenTest {
    @get:Rule val compose = createComposeRule()

    private companion object { const val WAIT = 30_000L }

    private fun show(fixtures: LibraryFixtures = LibraryFixtures()) {
        compose.setContent { VeilAdminTheme(ThemeMode.Light) { LibrariesScreen(fixtures.client) } }
        compose.waitUntil(WAIT) { compose.onAllNodes(hasTestTag("library-1")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun inRow(id: String, text: String) = hasTestTag("library-$id") and hasAnyDescendant(hasText(text, substring = true))

    private fun assertRowHas(id: String, text: String) {
        compose.onNode(hasTestTag("library-$id")).performScrollTo()
        compose.onNode(inRow(id, text)).assertExists()
    }

    @Test
    fun eachLibraryShowsItsTypePathsStateAndLastScan() {
        show()
        assertRowHas("1", "Movies · 2 paths · Enabled")
        assertRowHas("2", "TV shows · 3 paths · Enabled")
        assertRowHas("4", "Disabled")
        assertRowHas("4", "Not scanned yet")
        assertRowHas("1", "Last scanned")
    }

    @Test
    fun aDeadRootWarningIsShownPlainlyWithTheServersWords() {
        show()
        assertRowHas("2", "Unreachable root")
        assertRowHas("2", "Root /mnt/nas/tv is not reachable: stale file handle")
        assertRowHas("2", "check the mount")
    }

    /** A failed scan and the warning it raised carry the same message; it is said once, under the warning. */
    @Test
    fun aFailedScanAndItsWarningSayTheSameThingOnce() {
        show()
        compose.onNode(hasTestTag("library-2")).performScrollTo()
        compose.onAllNodes(hasText("Root /mnt/nas/tv is not reachable: stale file handle") and hasAnyAncestor(hasTestTag("library-2")))
            .assertCountEquals(1)
    }

    @Test
    fun aLibraryWithNoWarningShowsNone() {
        show()
        compose.onNode(hasTestTag("library-1")).performScrollTo()
        compose.onNode(hasTestTag("library-1") and hasAnyDescendant(hasTestTag("scan-warning"))).assertDoesNotExist()
    }

    @Test
    fun scanShowsQueuedOrRunningThenTheRunThatFollows() {
        show()
        // Music is already mid-scan: its Scan is off and its Stop is on.
        compose.onNodeWithContentDescription("Scan Music").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop scanning Music").assertIsEnabled()
        assertRowHas("3", "Running")

        compose.onNodeWithContentDescription("Scan Movies").performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(inRow("1", "Running")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Scan Movies").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop scanning Movies").assertIsEnabled()
    }

    @Test
    fun stopNamesTheLibraryAndEndsTheScan() {
        show()
        compose.onNodeWithContentDescription("Stop scanning Music").performScrollTo().performClick()
        compose.onNodeWithTag("stop-scan-dialog").assertExists()
        compose.onNodeWithText("Stop scanning Music?").assertExists()
        compose.onNodeWithText("Stop scan").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(inRow("3", "Running")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Scan Music").performScrollTo().assertIsEnabled()
    }

    @Test
    fun keepScanningLeavesTheScanRunning() {
        show()
        compose.onNodeWithContentDescription("Stop scanning Music").performScrollTo().performClick()
        compose.onNodeWithText("Keep scanning").performClick()
        compose.onNodeWithTag("stop-scan-dialog").assertDoesNotExist()
        assertRowHas("3", "Running")
    }

    @Test
    fun checkTheMountGivesAClearAnswerNamingTheBadRoot() {
        show()
        compose.onNodeWithContentDescription("Check the mount of TV shows").performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasTestTag("mount-check")).fetchSemanticsNodes().isNotEmpty() }
        assertRowHas("2", "Mount is not reachable")
        assertRowHas("2", "/mnt/nas/tv: stale file handle")
        assertRowHas("2", "Checked")
    }

    @Test
    fun aHealthyMountIsSaidToBeReachable() {
        show()
        compose.onNodeWithContentDescription("Check the mount of Movies").performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasTestTag("mount-check")).fetchSemanticsNodes().isNotEmpty() }
        assertRowHas("1", "Mount is reachable")
    }

    @Test
    fun refreshMetadataOffersMissingOnlyOrAllAndNamesTheLibrary() {
        show()
        compose.onNodeWithContentDescription("Refresh metadata of Movies").performScrollTo().performClick()
        compose.onNodeWithTag("refresh-metadata-dialog").assertExists()
        compose.onNodeWithText("Refresh metadata for Movies").assertExists()
        compose.onNodeWithText("Refresh missing metadata").assertExists()
        compose.onNodeWithText("Refresh all metadata").assertExists()
        // Missing only is the default; it confirms as "Refresh missing".
        compose.onNodeWithText("Refresh missing").assertExists()
        compose.onNodeWithText("Refresh all metadata").performClick()
        compose.onNodeWithText("Refresh all").assertExists()
    }

    @Test
    fun aStartedRefreshShowsProgressAndCanBeCancelled() {
        show()
        compose.onNodeWithContentDescription("Refresh metadata of Movies").performScrollTo().performClick()
        compose.onNodeWithText("Refresh all metadata").performClick()
        compose.onNodeWithText("Refresh all").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(inRow("1", "Refreshing metadata: 120 of 1900 items")).fetchSemanticsNodes().isNotEmpty() }
        // A refresh is under way, so another cannot be started on the same library.
        compose.onNodeWithContentDescription("Refresh metadata of Movies").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Cancel the metadata refresh of Movies").performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(inRow("1", "Cancelling the metadata refresh")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRefreshStartedElsewhereIsShownWhileItRuns() {
        show()
        assertRowHas("3", "Refreshing metadata: 340 of 1200 items")
        compose.onNodeWithContentDescription("Cancel the metadata refresh of Music").assertExists()
    }

    @Test
    fun recentScansShowNewUpdatedMissingErrorsAndHowLong() {
        show()
        compose.onNodeWithTag("recent-scans").performScrollTo()
        compose.onNode(hasTestTag("scan-run-run-1") and hasAnyDescendant(hasText("4 new, 2 updated, 0 missing, 0 errors"))).assertExists()
        compose.onNode(hasTestTag("scan-run-run-1") and hasAnyDescendant(hasText("Took", substring = true))).assertExists()
        compose.onNode(hasTestTag("scan-run-run-2") and hasAnyDescendant(hasText("Failed"))).assertExists()
        compose.onNode(hasTestTag("scan-run-run-2") and hasAnyDescendant(hasText("Root /mnt/nas/tv is not reachable: stale file handle"))).assertExists()
        compose.onNode(hasTestTag("scan-run-run-3") and hasAnyDescendant(hasText("Folder /mnt/nas/movies/Example Film (2026)"))).assertExists()
        compose.onNode(hasTestTag("scan-run-run-3") and hasAnyDescendant(hasText("Autoscan", substring = true))).assertExists()
    }

    @Test
    fun allServersSectionsActOnTheirOwnServer() {
        val first = LibraryFixtures()
        val second = LibraryFixtures()
        // Pages come from the view model All servers uses; the minified build keeps only the constructors production calls.
        val viewModel = AggregateLibrariesViewModel()
        val pageA = viewModel.pageFor(AdminServer("srv-a", "https://a.test", "First"))
        val pageB = viewModel.pageFor(AdminServer("srv-b", "https://b.test", "Second"))
        compose.setContent {
            VeilAdminTheme(ThemeMode.Light) {
                LaunchedEffect(Unit) { pageA.refresh(first.client); pageB.refresh(second.client) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ServerGroupHeader("First")
                    LibraryServerContent(pageA, first.client)
                    ServerGroupHeader("Second")
                    LibraryServerContent(pageB, second.client)
                }
            }
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription("Scan Movies").fetchSemanticsNodes().size == 2 }

        // Scan the second server's Movies only. Only that section's server was asked: its Scan turns off
        // (the run is Running) while the first server's Movies stays ready to scan.
        compose.onAllNodesWithContentDescription("Scan Movies")[1].performScrollTo().performClick()
        compose.waitUntil(WAIT) { compose.onAllNodes(hasContentDescription("Scan Movies") and isNotEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithContentDescription("Scan Movies")[0].assertIsEnabled()
        compose.onAllNodesWithContentDescription("Scan Movies")[1].assertIsNotEnabled()
    }
}
