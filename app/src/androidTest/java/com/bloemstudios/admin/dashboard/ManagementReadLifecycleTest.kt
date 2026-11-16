package com.bloemstudios.admin.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagementReadLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
    @Test fun historyRefresh() = refresh("activity")
    @Test fun auditRefresh() = refresh("audit")
    @Test fun invitationsRefresh() = refresh("invitations")
    @Test fun jobsRefresh() = refresh("jobs")
    @Test fun logsRefresh() = refresh("logs")
    @Test fun requestsRefresh() = refresh("requests")
    @Test fun settingsRefresh() = refresh("settings")
    @Test fun usersRefresh() = refresh("users")

    private fun withFixture(kind: String, block: (ManagementReadFixture, Owner) -> Unit) {
        val fixture = ManagementReadFixture(kind)
        lateinit var owner: Owner
        compose.runOnIdle { owner = Owner(); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { VeilAdminTheme { fixture.Render() } } }
        try { block(fixture, owner) }
        finally { compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }; fixture.close() }
    }
    private fun stop(fixture: ManagementReadFixture, owner: Owner) {
        compose.waitUntil(5_000) { fixture.held.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { fixture.canceled.get() == 1 }
        fixture.releaseHeldRead(); compose.waitForIdle()
    }
    private fun start(owner: Owner) { compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.waitForIdle() }
    private fun refresh(kind: String) = withFixture(kind) { fixture, owner ->
        val seeded = kind in setOf("settings", "invitations")
        compose.waitUntil(5_000) { fixture.reads.get() == 1 && if (seeded) fixture.rows() == 20 else fixture.hasError() }
        triggerRefresh(kind)
        stop(fixture, owner)
        if (seeded) assertEquals("No held result may replace the retained rows", 20, fixture.rows())
        else assertTrue("No held result may publish after STOP", fixture.hasError())
        start(owner)
        compose.waitUntil(5_000) { fixture.reads.get() == 3 }
        // The read counter ticks when the request arrives; the failure is published after the response.
        compose.waitUntil(5_000) { fixture.hasError() }
        compose.waitForIdle()
        assertEquals("Only the automatic foreground sample resumes", 3, fixture.reads.get())
        assertTrue(fixture.hasError())
        // Starting another refresh proves the previous pull indicator ended in finally.
        triggerRefresh(kind)
        compose.waitUntil(5_000) { fixture.reads.get() == 4 && !fixture.hasError() }
    }

    private fun triggerRefresh(kind: String) {
        if (kind in setOf("settings", "invitations")) {
            compose.onAllNodes(hasScrollAction()).onLast().performTouchInput { swipeDown() }
        } else compose.onNodeWithText("Try again").performClick()
    }

    @Test fun auditRowsShowTheLocalDateAndTimeNotTheRawIsoTimestamp() = withFixture("page") { fixture, _ ->
        compose.waitUntil(5_000) { fixture.rows() == 1 }
        // Written out here: the minified build renames the app's own formatter.
        val local = java.time.format.DateTimeFormatter
            .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withLocale(java.util.Locale.getDefault()).withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.parse("2026-11-01T00:00:00Z"))
        compose.onNodeWithText("$local · 200").assertExists()
        compose.onNodeWithText("2026-11-01T00:00:00Z", substring = true).assertDoesNotExist()
    }

    @Test fun loadMoreCancelsWithoutLosingCursorOrReplaying() = withFixture("page") { fixture, owner ->
        compose.waitUntil(5_000) { fixture.rows() == 1 }
        compose.onNodeWithText("Load more").performClick()
        stop(fixture, owner)
        assertEquals(1, fixture.rows())
        start(owner)
        compose.waitUntil(5_000) { fixture.reads.get() == 3 }
        assertEquals(1, fixture.rows())
        assertEquals(listOf("", "cursor-2", ""), fixture.cursors.toList())
        compose.onNodeWithText("Load more").performClick()
        compose.waitUntil(5_000) { fixture.rows() == 2 }
        assertEquals("cursor-2", fixture.cursors.last())
    }

    /**
     * A lazy list measures on the main thread, so any list-backing snapshot state written from
     * another thread can change the item count between two reads inside one measure pass
     * ("Index 1, size 1" from LazyLayoutMeasureScopeImpl.measure). Compose's test rule runs effects
     * on an unconfined dispatcher, so a collector resumes on whichever thread set the flow: the HTTP
     * engine's thread. The screens must keep their collection on the main thread.
     */
    private fun assertNoOffMainSnapshotWrites(kind: String, block: (ManagementReadFixture, Owner) -> Unit) {
        val mainThread = android.os.Looper.getMainLooper().thread
        val offMain = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val handle = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
            if (Thread.currentThread() !== mainThread) offMain += Thread.currentThread().name
        }
        try { withFixture(kind, block) } finally { handle.dispose() }
        assertTrue("Snapshot state was written off the main thread by: ${offMain.toSet()}", offMain.isEmpty())
    }

    @Test fun loadMoreWritesListStateOnlyOnTheMainThread() = assertNoOffMainSnapshotWrites("page") { fixture, _ ->
        compose.waitUntil(5_000) { fixture.rows() == 1 }
        compose.onNodeWithText("Load more").performClick()
        compose.waitUntil(5_000) { fixture.held.get() == 1 }
        fixture.releaseHeldRead()
        compose.waitUntil(5_000) { fixture.rows() == 2 }
        compose.waitForIdle()
    }

    @Test fun settingReviewCancelsAndPreservesDraftAndOriginalValidator() = withFixture("review") { fixture, owner ->
        compose.onNodeWithText("original").performTextReplacement("my draft")
        compose.onNodeWithText("Save changes").performClick()
        compose.onNodeWithText("Review current value").performClick()
        stop(fixture, owner)
        compose.onNodeWithText("my draft").assertExists()
        compose.onNodeWithText("Review current value").assertIsEnabled()
        assertEquals(listOf("\"original\""), fixture.validators.toList())
        start(owner)
        assertEquals(2, fixture.reads.get())
        compose.onNodeWithText("Keep draft and use this revision").assertDoesNotExist()
        compose.onNodeWithText("Review current value").performClick()
        compose.onNodeWithText("Keep draft and use this revision").performClick()
        compose.onNodeWithText("my draft").assertExists()
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) { fixture.writes.get() == 2 }
        assertEquals(listOf("\"original\"", "\"latest\""), fixture.validators.toList())
    }

    @Test fun sessionsRetryCancelsAndResetsItsIndicator() = withFixture("sessions") { fixture, owner ->
        compose.waitUntil(5_000) { fixture.reads.get() == 1 }
        compose.onNodeWithText("Try again").performClick()
        stop(fixture, owner)
        compose.onNodeWithText("Try again").assertIsEnabled()
        start(owner)
        assertEquals(2, fixture.reads.get())
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { fixture.reads.get() == 3 }
        compose.onNodeWithText("Try again").assertIsEnabled()
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { fixture.reads.get() == 4 }
        compose.onNodeWithText("No active sessions").assertExists()
    }

    @Test fun memberOpenCancelsAndCanBeRetriedAfterResume() = withFixture("members") { fixture, owner ->
        compose.onNodeWithText("Members").performClick()
        stop(fixture, owner)
        compose.onNodeWithText("Try again").assertIsEnabled()
        start(owner)
        assertEquals(1, fixture.reads.get())
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { fixture.reads.get() == 2 }
    }
    @Test fun acceptedSettingSaveSurvivesStopAndPublishesItsOutcomeOnce() = withFixture("save") { fixture, owner ->
        compose.onNodeWithText("original").performTextReplacement("my draft")
        compose.onNodeWithText("Save changes").performClick()
        compose.waitUntil(5_000) { fixture.held.get() == 1 && fixture.writes.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        assertEquals(0, fixture.canceled.get())
        fixture.releaseHeldRead()
        compose.waitUntil(5_000) { fixture.saved.get() == 1 }
        start(owner)
        assertEquals(1, fixture.writes.get()); assertEquals(1, fixture.saved.get())
        assertEquals(listOf("\"original\""), fixture.validators.toList())
    }

}
