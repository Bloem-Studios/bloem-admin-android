package com.bloemstudios.admin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.dashboard.*
import com.bloemstudios.admin.network.ApiClient
import com.bloemstudios.admin.realtime.*
import com.bloemstudios.admin.auth.AuthProviders
import com.bloemstudios.admin.servers.SignInOptionsStep
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.streams.*
import com.bloemstudios.admin.ui.shell.*
import com.bloemstudios.admin.ui.theme.VeilSpacing
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Pure presentation or controllable repository fixtures. No real ServerStore or origin is contacted. */
class FieldActivityVisualTest {
    @get:Rule val compose = createComposeRule()
    private val a = AdminServer("a", "https://fixture.invalid", "North studio")
    private val b = AdminServer("b", "https://fixture.invalid", "Garden studio")
    private val row = AdminSession("one", "user-1", "Alex", "profile-1", "The River", "movie", "Direct play", 1200.0, 5400.0, false, "Living room")
    private val host = SystemResources(true, sampled_at = "2026-10-12T00:00:00Z", system = SystemStats(18.2, 2384, 7629, 1500000.0, 9200000.0))
    private val stats = AdminStats(24, 28, 3, 20, 24, 4, 4, 99, 1000000, emptyList())
    private val noop = AggregateStreamActions({_,_->},{_,_->},{_,_->},{_,_->},{_,_,_,_->SendMessageResult.Success},{},{})

    @Composable private fun Frame(largeFont: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 2f else density.fontScale)) {
            VeilAdminTheme {
                AdminShell(AdminShellContext("All servers", true), aggregateDestinations(), "overview.dashboard", {}, LocalConfiguration.current.screenWidthDp >= 600,
                    onContextRequested = {}, activityScope = ActivityScopeControl(true, null, emptyList(), {}, {})) { content() }
            }
        }
    }

    private fun capture(name: String, keyboard: Boolean = false) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.getExternalFilesDir(null), "field-activity-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun emptySetupHasActionableAddServer() {
        var adds = 0
        compose.setContent { Frame { LazyColumn(contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) { item { EmptyActivitySetup { adds++ } } } } }
        compose.onNodeWithText("Add a Silo server").assertIsDisplayed()
        compose.onNodeWithText("Add server").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, adds) }
        capture("empty")
    }

    @Test fun connectingKeepsUnknownSessionsDistinctFromEmpty() {
        compose.setContent { Frame {
            LazyColumn(contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) { item { AggregateStatusSection(a, null, RealtimeState.Connecting, null, null, null, null, null, null, false, {}, {}) } }
        } }
        compose.onNodeWithText("Connecting").assertIsDisplayed()
        compose.onNodeWithText("Nothing playing right now").assertDoesNotExist()
        capture("loading")
    }

    @Test fun partialAccessAndStaleSessionsKeepSourceAttribution() = partial(false)
    @Test fun partialAccessAndStaleAtTwoHundredPercentFont() = partial(true)
    private fun partial(large: Boolean) {
        var wide = false
        compose.setContent { Frame(large) {
            wide = LocalConfiguration.current.screenWidthDp >= 600
            LazyColumn(Modifier.fillMaxSize().testTag("fixture-status-list"), contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) {
                item { AggregateStatusSection(a, listOf(row), RealtimeState.Reconnecting, SessionFailure.Connection,
                    host, null, stats, "Fixture metrics refresh failed", "2026-10-12T00:00:00Z", false, {}, {}) }
                item { AggregateStatusSection(b, listOf(row.copy(media_title = "Hidden private title")), RealtimeState.SignInRequired,
                    SessionFailure.SignInRequired, host, null, stats, null, null, true, {}, {}) }
            }
        } }
        compose.onNodeWithText("North studio").assertIsDisplayed()
        compose.onNodeWithText("Reconnecting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The River").performScrollTo().assertIsDisplayed()
        if (large && wide) listOf("Now playing", "Playback history", "Requests", "Scheduled tasks").forEach { label ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasClickAction())).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label must use a single readable line at 200%", layouts.isNotEmpty() && layouts.all { it.lineCount == 1 })
        }
        capture(if (large) "partial-200-top" else "partial-top")
        compose.onNodeWithText("Fixture metrics refresh failed").performScrollTo().assertIsDisplayed()
        capture(if (large) "sample-error-200" else "sample-error")
        compose.onNodeWithTag("fixture-status-list").performScrollToIndex(1)
        compose.onNodeWithText("Garden studio").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hidden private title").assertDoesNotExist()
        capture(if (large) "partial-200-sign-in" else "partial-sign-in")
    }

    @Test fun emptyReplacementChangesStatusCountAndRows() = replacement(false)
    @Test fun emptyReplacementChangesStreamRowsWithoutUnknownEmptyConfusion() = replacement(true)

    private fun replacement(streams: Boolean) {
        val api = ApiClient("https://fixture.invalid", null)
        // The socket only cues a re-read; the rows come from the list read.
        val served = java.util.concurrent.atomic.AtomicReference<List<AdminSession>?>(null)
        val replacements = Channel<Unit>(Channel.UNLIMITED)
        val live = LiveConnection(api, LiveTransport { control ->
            control.onSubscribed(setOf(LiveChannels.SESSIONS), emptyMap())
            control.onSnapshot(LiveChannels.SESSIONS)
            for (cue in replacements) control.onEvent(LiveChannels.SESSIONS)
        }, kotlinx.coroutines.CoroutineScope(Dispatchers.Default))
        val repo = LiveSessionsRepository(api, live, {
            var rows = served.get()
            while (rows == null) { delay(20); rows = served.get() }
            rows
        })
        compose.setContent { Frame {
            ForegroundEffect(repo) { repo.observe() }
            val rows by repo.sessions.collectAsState(); val state by repo.state.collectAsState()
            LazyColumn(contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) { item {
                if (streams) LiveServerStreamsSection(a, rows, state, null, false, noop)
                else AggregateStatusSection(a, rows, state, null, host, null, stats, null, null, false, {}, {})
            } }
        } }
        try {
            compose.onNodeWithText(if (streams) "No streams" else "Nothing playing right now").assertDoesNotExist()
            served.set(listOf(row)); replacements.trySend(Unit)
            compose.waitUntil { repo.sessions.value?.size == 1 }
            compose.onNodeWithText("The River").performScrollTo().assertIsDisplayed()
            if (!streams) { compose.onNodeWithText("1 session").performScrollTo().assertIsDisplayed(); capture("live") }
            served.set(emptyList()); replacements.trySend(Unit)
            compose.waitUntil { repo.sessions.value?.isEmpty() == true }
            compose.onNodeWithText("The River").assertDoesNotExist()
            if (streams) compose.onNodeWithText("No streams").assertIsDisplayed()
            else { compose.onNodeWithText("0 sessions").performScrollTo().assertIsDisplayed(); compose.onNodeWithText("Nothing playing right now").performScrollTo().assertIsDisplayed() }
            capture(if (streams) "streams-empty" else "replacement-empty")
        } finally { repo.close(); live.close(); api.close(); replacements.close() }
    }

    @Test fun actualShellDestinationsObserveOneRepositoryThroughOverlap() {
        val api = ApiClient("https://fixture.invalid", null)
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val live = LiveConnection(api, LiveTransport { control ->
            active.incrementAndGet(); maximum.updateAndGet { maxOf(it, active.get()) }
            try {
                control.onSubscribed(setOf(LiveChannels.SESSIONS), emptyMap()); control.onSnapshot(LiveChannels.SESSIONS)
                awaitCancellation()
            } finally { active.decrementAndGet() }
        }, kotlinx.coroutines.CoroutineScope(Dispatchers.Default), config = LiveConfig(leaveGraceMillis = 300))
        val repo = LiveSessionsRepository(api, live, { listOf(row) })
        var selected by mutableStateOf("overview.dashboard"); var visible by mutableStateOf(true)
        compose.setContent { VeilAdminTheme {
            if (visible) AdminShell(AdminShellContext("Fixture", false), singleServerDestinations(false), selected, { selected = it }, false, {}) { destination ->
                ForegroundEffect(repo, destination) { repo.observe() }
                val rows by repo.sessions.collectAsState(); val state by repo.state.collectAsState(); val error by repo.error.collectAsState()
                if (destination == "overview.dashboard") LazyColumn(contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) { item {
                    AggregateStatusSection(a, rows, state, error, host, null, stats, null, null, false, {}, {})
                } } else LazyColumn(contentPadding = PaddingValues(VeilSpacing.CompactHorizontal)) { item { LiveServerStreamsSection(a, rows, state, error, false, noop) } }
            }
        } }
        try {
            compose.waitUntil { active.get() == 1 }
            compose.runOnIdle { selected = "live.streams" }
            compose.waitForIdle()
            assertEquals(1, maximum.get())
            compose.runOnIdle { visible = false }
            compose.waitUntil { active.get() == 0 }
        } finally { repo.close(); live.close(); api.close() }
    }

    @Test fun navigationLabelsAndSelectionRemainUsableAtTwoHundredPercent() {
        var selected by mutableStateOf("overview.dashboard")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                VeilAdminTheme {
                    AdminShell(AdminShellContext("Fixture", false), singleServerDestinations(false), selected,
                        { selected = it }, false, {}) { Text(it) }
                }
            }
        }
        compose.onNodeWithText("Dashboard").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shell-destination:overview.dashboard").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("shell-destination:live.streams").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithTag("shell-destination:live.streams").assertIsSelected()
        compose.onNodeWithTag("shell-destination:work.jobs").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("work.jobs", selected) }
        capture("navigation-200")
    }

    @Test fun loginKeyboardAndReloginRemainUsableAtTwoHundredPercent() {
        var username by mutableStateOf(""); var password by mutableStateOf(""); var imeBottom by mutableStateOf(0); var showKeyboard by mutableStateOf(false); var connects = 0
        compose.setContent {
            val density = LocalDensity.current
            val bottom = WindowInsets.ime.getBottom(density)
            SideEffect { imeBottom = bottom }
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            LaunchedEffect(showKeyboard) { if (showKeyboard) keyboard?.show() else keyboard?.hide() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { VeilAdminTheme {
                SignInOptionsStep(
                    serverName = "Fixture", providers = AuthProviders(true, emptyList()),
                    username = username, onUsernameChange = { username = it },
                    password = password, onPasswordChange = { password = it },
                    errorMessage = "Fixture sign-in failed", isConnecting = false, connectingProviderId = null,
                    compact = true, onPasswordSignIn = { connects++ }, onProviderSignIn = {},
                    title = "Sign in", guidance = "Fixture session expired", passwordActionLabel = "Sign in",
                )
            } }
        }
        compose.onNodeWithText("Username").performScrollTo().performClick().performTextInput("fixture-admin")
        compose.onNodeWithText("Password").performScrollTo().performClick().performTextInput("fixture-password")
        compose.runOnIdle { showKeyboard = true }
        compose.waitUntil(timeoutMillis = 5_000) { imeBottom > 0 }
        compose.onNodeWithText("Password").performScrollTo().assertIsDisplayed()
        val signIn = compose.onAllNodesWithText("Sign in")[1]
        val visibleBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - imeBottom
        assertTrue("Sign in must remain above the open keyboard", signIn.fetchSemanticsNode().boundsInRoot.bottom <= visibleBottom)
        capture("login-keyboard-200", keyboard = true)
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val ready = File(directory, "field-activity-keyboard-ready")
        val release = File(directory, "field-activity-keyboard-release")
        release.delete(); ready.writeText("fixture ready")
        try {
            val deadline = System.nanoTime() + 25_000_000_000L
            while (!release.exists() && System.nanoTime() < deadline) Thread.sleep(100)
        } finally { ready.delete(); release.delete() }
        compose.onAllNodesWithText("Sign in")[1].assertIsDisplayed().assertHasClickAction()
        compose.onAllNodesWithText("Sign in")[1].performClick()
        compose.runOnIdle { assertEquals(1, connects) }
        capture("login-200")
        // Leave no keyboard up for the next test on this device.
        compose.runOnIdle { showKeyboard = false }
        compose.waitUntil(timeoutMillis = 5_000) { imeBottom == 0 }
    }
}
