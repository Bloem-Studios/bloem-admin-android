package com.bloemstudios.admin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.dashboard.DashboardSection
import com.bloemstudios.admin.fixtures.FixtureIncidentData
import com.bloemstudios.admin.alerts.AlertDetailsTag
import com.bloemstudios.admin.fixtures.FixtureAlerts
import com.bloemstudios.admin.fixtures.FixtureAppSettings
import com.bloemstudios.admin.fixtures.FixtureOrganization
import com.bloemstudios.admin.fixtures.FixtureServer
import com.bloemstudios.admin.logs.LogsFocus
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.ThemeMode
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draws the app's key screens from sample data in light and dark and writes them as PNGs for the design review, to the
 * run's additional test output (app/build/outputs/connected_android_test_additional_output/). It asserts nothing about pixels.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTourTest {
    @get:Rule val compose = createComposeRule()

    private var mode by mutableStateOf(ThemeMode.Dark)
    // The pictures go where the Android Gradle plugin collects a run's additional test output, so they outlive the
    // uninstall that ends the run: app/build/outputs/connected_android_test_additional_output/.
    private val outDir: File by lazy {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)?.apply { mkdirs() }
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }


    /** [dialog] photographs the screen with the open dialog on it. */
    private fun shoot(name: String, dialog: Boolean = false) {
        for (themeMode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = themeMode
            compose.waitForIdle()
            // A dialog window repaints a moment after the page does when the theme changes.
            if (dialog) Thread.sleep(700)
            // A dialog is a window of its own that the page's root image leaves out, so the whole display is captured.
            val bitmap = if (dialog) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "$name-${themeMode.storageValue}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The whole screen, dialogs included (the compose root alone leaves a dialog's window out). */
    private fun shootScreen(name: String) {
        for (themeMode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = themeMode
            compose.waitForIdle()
            // A dialog sits in its own window and fades in over about a quarter of a second; wait it out before the shot.
            Thread.sleep(900)
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(outDir, "$name-${themeMode.storageValue}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The organization page is a lazy list: bring the row with [tag] into composition before using it. */
    private fun scrollOrganizationTo(tag: String) {
        val page = compose.onNodeWithTag("organization-page")
        if (runCatching { page.performScrollToNode(androidx.compose.ui.test.hasTestTag(tag)) }.isFailure) {
            page.performScrollToNode(androidx.compose.ui.test.hasTestTag("platform-plan"))
        }
        compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalReducedMotionOverride provides true) {
            VeilAdminTheme(mode) {
                // The real window is painted with the page color; the test window is not, so do it here.
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(com.bloemstudios.admin.ui.theme.VeilColors.Canvas),
                ) { content() }
            }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTextCount(text) > 0 }

    private fun showScaled(fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) = show {
        val density = androidx.compose.ui.platform.LocalDensity.current
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
        ) { content() }
    }

    @Test fun nowPlayingMenu() {
        show { FixtureServer("live.streams") }
        compose.onAllNodesWithContentDescription("Actions for maria", substring = true)[0].performClick()
        compose.waitForIdle()
        shootScreen("now-playing-menu")
    }

    @Test fun history() {
        show { FixtureServer("live.activity") }
        waitForText("Dune: Part Two")
        shoot("history")
    }

    @Test fun historyFilteredToOneUser() {
        show { FixtureServer("live.activity") }
        waitForText("Dune: Part Two")
        compose.onNodeWithTag("history-filter-user").performClick()
        waitForText("jonas")
        shootScreen("history-user-picker")
        compose.onNodeWithText("jonas").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTextCount("Dune: Part Two") == 0 }
        shoot("history-filtered")
    }

    @Test fun historyDetails() {
        show { FixtureServer("live.activity") }
        waitForText("Dune: Part Two")
        compose.onNodeWithTag("history-row-h2").performClick()
        waitForText("Position at end")
        shootScreen("history-details")
    }

    @Test fun logs() {
        show { FixtureServer("system.logs") }
        waitForText("Transcode failed")
        shoot("logs")
    }

    @Test fun logDetails() {
        show { FixtureServer("system.logs") }
        waitForText("Transcode failed")
        compose.onNodeWithTag("log-row-l1").performClick()
        waitForText("Request ID")
        shootScreen("log-details")
    }

    @Test fun logsForOnePlaybackSession() {
        show { FixtureServer("system.logs", logsFocus = LogsFocus(FixtureIncidentData.TRANSCODE_SESSION, ffmpegOnly = true)) }
        waitForText("All components")
        shoot("logs-session")
    }

    @Test fun audit() {
        show { FixtureServer("system.audit") }
        waitForText("Update user")
        shoot("audit")
    }

    @Test fun auditDetails() {
        show { FixtureServer("system.audit") }
        waitForText("Update user")
        compose.onNodeWithTag("audit-row-a1").performClick()
        waitForText("User agent")
        shootScreen("audit-details")
    }

    @Test fun dashboardAtLargeFont() {
        showScaled(1.3f) { FixtureServer("overview.dashboard") }
        waitForText("Profiles · 24h")
        shoot("dashboard-top-font-1.3")
        compose.onNodeWithTag(DashboardSection.Health.tag).performScrollTo()
        shoot("dashboard-health-font-1.3")
    }

    @Test fun dashboardHealth() {
        show { FixtureServer("overview.dashboard") }
        waitForText("Profiles · 24h")
        compose.onNodeWithTag(DashboardSection.Health.tag).performScrollTo()
        shoot("dashboard-health")
    }

    @Test fun nowPlaying() {
        show { FixtureServer("live.streams") }
        shoot("now-playing")
    }

    @Test fun shellMenu() {
        show { FixtureServer("live.streams") }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        shoot("menu")
    }

    /** The server switcher: the current name and a chevron in the top bar opens a sheet of servers, with Add server last. */
    @Test fun serverSwitcher() {
        show { FixtureServer("live.streams") }
        compose.onNodeWithContentDescription("Change server context from Bloem Home").performClick()
        compose.waitForIdle()
        shootScreen("switcher")
    }

    /** The menu is longer than the screen: its last pages (Backups, API keys, Plugins) need the list scrolled to the end. */
    @Test fun shellMenuEnd() {
        show { FixtureServer("live.streams") }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shell-destination:system.plugins").performScrollTo()
        compose.waitForIdle()
        shoot("menu-end")
    }

    @Test fun dashboard() {
        show { FixtureServer("overview.dashboard") }
        shoot("dashboard-top")
        compose.onNodeWithTag(DashboardSection.ConcurrentStreams.tag).performScrollTo()
        shoot("dashboard-charts")
    }

    @Test fun userPage() {
        show { FixtureServer("people.users") }
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("maria") > 0 }
        shoot("users")
        compose.onNodeWithText("maria").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("maria@example.test") > 0 }
        shoot("user-page")
    }

    // ---- Wave 2, people and requests -----------------------------------------------------------------------------------

    @Test fun requestsNeedsApproval() {
        show { FixtureServer("work.requests") }
        shoot("requests-needs-approval")
    }

    @Test fun requestsSelecting() {
        show { FixtureServer("work.requests", variant = "select") }
        shoot("requests-select")
    }

    @Test fun requestsBulkProgress() {
        show { FixtureServer("work.requests", variant = "progress") }
        shoot("requests-bulk-progress")
    }

    @Test fun requestsBulkFailed() {
        show { FixtureServer("work.requests", variant = "failed") }
        shoot("requests-bulk-failed")
    }

    @Test fun requestsUnavailable() {
        show { FixtureServer("work.requests", variant = "unavailable") }
        shoot("requests-unavailable")
    }

    @Test fun requestsStale() {
        show { FixtureServer("work.requests", variant = "stale") }
        shoot("requests-stale")
    }

    @Test fun menuWithRequestCount() {
        show { FixtureServer("work.requests") }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        shoot("menu-requests-count")
    }

    @Test fun apiKeys() {
        show { FixtureServer("system.api-keys") }
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Requests bot") > 0 }
        shoot("api-keys")
    }

    @Test fun apiKeysRevoke() {
        show { FixtureServer("system.api-keys") }
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Backup script") > 0 }
        compose.onNodeWithTag("api-key-revoke:13").performScrollTo().performClick()
        compose.waitForIdle()
        shootScreen("api-keys-revoke")
    }

    private fun openMaria() {
        show { FixtureServer("people.users") }
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("maria") > 0 }
        compose.onNodeWithText("maria").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("maria@example.test") > 0 }
    }

    @Test fun userWatchingNow() {
        openMaria()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Watching now") > 0 }
        compose.onNodeWithText("72 of 166 min").performScrollTo()
        shoot("user-watching-now")
    }

    @Test fun userProfiles() {
        openMaria()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Profiles") > 0 }
        compose.onNode(hasText("Profiles") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Living room") > 0 }
        compose.onNodeWithTag("user-profile-row:p3").performScrollTo()
        shoot("user-profiles")
    }

    @Test fun userProfilePin() {
        openMaria()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Profiles") > 0 }
        compose.onNode(hasText("Profiles") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Living room") > 0 }
        compose.onNodeWithTag("user-profile-menu:p2").performScrollTo().performClick()
        compose.onNodeWithText("Set PIN").performClick()
        compose.waitForIdle()
        shootScreen("user-profile-pin")
    }

    @Test fun userSignIn() {
        openMaria()
        compose.onNode(hasText("Sign-in") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Living room TV") > 0 }
        compose.onAllNodesWithText("Living room TV")[1].performScrollTo()
        shoot("user-sign-in")
    }

    @Test fun userResetOptions() {
        openMaria()
        compose.onNodeWithText("Reset password").performClick()
        compose.waitForIdle()
        shootScreen("user-reset-options")
    }

    @Test fun userResetLink() {
        openMaria()
        compose.onNodeWithText("Reset password").performClick()
        compose.onNodeWithTag("user-reset-link").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("user-reset-link-done").fetchSemanticsNodes().isNotEmpty() }
        shootScreen("user-reset-link")
    }

    @Test fun userSetTemporaryPassword() {
        openMaria()
        compose.onNodeWithText("Set temporary password").performClick()
        compose.waitForIdle()
        shootScreen("user-temporary-password")
    }

    // App settings with its Server alerts section: a new install (all off), alerts on for two servers, and alerts that
    // Android is blocking. The body scrolls, so each picture is scrolled to the end of the alerts section.
    @Test fun appSettingsAlertsOff() {
        show { FixtureAppSettings(FixtureAlerts.Off) }
        compose.onNodeWithTag(AlertDetailsTag).performScrollTo()
        shoot("settings-alerts-off")
    }

    @Test fun appSettingsAlertsOn() {
        show { FixtureAppSettings(FixtureAlerts.On) }
        compose.onNodeWithTag(AlertDetailsTag).performScrollTo()
        shoot("settings-alerts-on")
    }

    @Test fun appSettingsAlertsBlocked() {
        show { FixtureAppSettings(FixtureAlerts.Blocked) }
        compose.onNodeWithTag(AlertDetailsTag).performScrollTo()
        shoot("settings-alerts-blocked")
    }

    @Test fun lockScreen() {
        show { com.bloemstudios.admin.lock.LockScreen(message = null, onUnlockRequested = {}) }
        shoot("lock")
    }

    @Test fun organization() {
        show { FixtureOrganization("organization.overview") }
        shoot("org-overview")
    }

    @Test fun organizationPeople() {
        show { FixtureOrganization("organization.people") }
        shoot("org-people")
    }


    // Settings pages (wave 2): the list, search, a page with an edit pending, and the test actions with their answers.
    private fun waitForTag(tag: String) = try {
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        compose.onRoot().printToLog("TOUR-MISSING-$tag")
        throw e
    }

    /**
     * A page is a lazy list: scroll to the control first, or it is not drawn. Rows that come from a read (the sign-in
     * providers, the notification channels) are not in the list until it answers, so keep trying until the row exists.
     */
    private fun scrollPageTo(pageTag: String, tag: String) {
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag(pageTag).performScrollToNode(androidx.compose.ui.test.hasTestTag(tag)) }.isSuccess
        }
        waitForTag(tag)
    }

    @Test fun settingsHome() {
        show { FixtureServer("system.server") }
        waitForTag("settings-row:general")
        shoot("settings-home")
    }

    @Test fun settingsSearch() {
        show { FixtureServer("system.server") }
        waitForTag("settings-search")
        compose.onNodeWithTag("settings-search").performTextInput("log level")
        waitForTag("settings-hit:server.log_level")
        shoot("settings-search")
    }

    @Test fun settingsLoggingEdited() {
        show { FixtureServer("system.server", settingsRoute = "page:logging") }
        waitForTag("setting-control:server.log_level")
        compose.onNodeWithText("Debug").performClick()
        waitForTag("settings-save-bar")
        shoot("settings-logging-edited")
    }

    @Test fun settingsPlayback() {
        show { FixtureServer("system.server", settingsRoute = "page:playback") }
        waitForTag("settings-page:playback")
        scrollPageTo("settings-page:playback", "setting-control:playback.hw_accel")
        shoot("settings-playback")
    }

    @Test fun settingsGeneral() {
        show { FixtureServer("system.server", settingsRoute = "page:general") }
        waitForTag("setting-control:server.public_url")
        shoot("settings-general")
    }

    @Test fun settingsEmailTestFailed() {
        show { FixtureServer("system.server", settingsRoute = "page:email") }
        waitForTag("settings-page:email")
        scrollPageTo("settings-page:email", "settings-email-test")
        compose.onNodeWithText("Test email recipient").performTextInput("me@example.test")
        // Typing opens the keyboard, which shrinks the shell's page: bring the button back into view before tapping it.
        compose.onNodeWithTag("settings-page:email").performScrollToNode(hasText("Send test email"))
        compose.onNodeWithText("Send test email").performScrollTo().performClick()
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Authentication failed") > 0 }
        } catch (e: Throwable) {
            compose.onRoot().printToLog("TOUR-EMAIL")
            throw e
        }
        compose.onNodeWithTag("settings-page:email").performScrollToNode(hasText("Authentication failed", substring = true))
        shoot("settings-email-test-failed")
    }

    @Test fun settingsSignIn() {
        show { FixtureServer("system.server", settingsRoute = "page:sign-in") }
        waitForTag("settings-page:sign-in")
        scrollPageTo("settings-page:sign-in", "settings-signin:7")
        compose.onNodeWithText("Test connection").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("invalid_client") > 0 }
        compose.onNodeWithTag("settings-page:sign-in").performScrollToNode(hasText("invalid_client", substring = true))
        shoot("settings-sign-in-test")
    }

    @Test fun settingsNotifications() {
        show { FixtureServer("system.server", settingsRoute = "page:notifications") }
        waitForTag("settings-page:notifications")
        scrollPageTo("settings-page:notifications", "settings-channel:ch2")
        shoot("settings-notifications-channels")
    }

    @Test fun settingsRequests() {
        show { FixtureServer("system.server", settingsRoute = "requests") }
        waitForTag("setting-control:requests.limit")
        shoot("settings-requests")
    }

    @Test fun settingsNetworkAccess() {
        show { FixtureServer("system.server", settingsRoute = "network") }
        waitForTag("network-host:node:2")
        shoot("settings-network-access")
    }

    @Test fun settingsChecks() {
        show { FixtureServer("system.server", settingsRoute = "checks") }
        waitForTag("settings-check:meilisearch")
        compose.onNodeWithTag("settings-check:redis").performScrollTo()
        compose.onAllNodes(hasText("Check")).onFirst().performClick()
        compose.onNode(hasText("Check") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("settings-check:meilisearch"))).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount("Connected to Redis") > 0 && compose.onAllNodesWithTextCount("Connection check failed") > 0 }
        compose.onNodeWithTag("settings-check:redis").performScrollTo()
        shoot("settings-checks")
    }

    @Test fun settingsOther() {
        show { FixtureServer("system.server", settingsRoute = "other") }
        waitForTag("settings-page:other")
        shoot("settings-other")
    }

    // ---- Automation and system: Scheduled tasks, Nodes, Backups, Plugins (wave 2) ----------------------------------

    @Test fun scheduledTasks() {
        show { FixtureServer("work.jobs") }
        waitForText("Scan libraries")
        shoot("tasks")
    }

    @Test fun stopTaskConfirmation() {
        show { FixtureServer("work.jobs") }
        waitForText("Refresh metadata")
        compose.onNodeWithContentDescription("Actions for Refresh metadata").performClick()
        compose.onNodeWithText("Stop").performClick()
        waitForText("Stop Refresh metadata?")
        shoot("task-stop-confirm", dialog = true)
    }

    @Test fun taskPage() {
        show { FixtureServer("work.jobs") }
        waitForText("Database maintenance")
        compose.onNodeWithText("Database maintenance").performClick()
        waitForText("Recent runs")
        waitForText("Failed steps: Reindex")
        shoot("task-page")
    }

    @Test fun scheduleEditor() {
        show { FixtureServer("work.jobs") }
        waitForText("Scan libraries")
        compose.onNodeWithText("Scan libraries").performClick()
        waitForText("Edit schedule")
        compose.onNodeWithTag("task-edit-schedule").performScrollTo().performClick()
        waitForText("Schedule: Scan libraries")
        shoot("task-schedule-editor", dialog = true)
    }

    @Test fun nodes() {
        show { FixtureServer("playback.nodes") }
        waitForText("gpu-1")
        shoot("nodes")
    }

    @Test fun nodeDrainConfirmation() {
        show { FixtureServer("playback.nodes") }
        waitForText("gpu-1")
        compose.onNodeWithTag("node-1-menu").performClick()
        compose.onNodeWithTag("node-1-drain").performClick()
        waitForText("Drain gpu-1?")
        shoot("node-drain-confirm", dialog = true)
    }

    @Test fun backups() {
        show { FixtureServer("system.backups") }
        waitForText("Saved backups")
        shoot("backups")
    }

    @Test fun backupSettings() {
        show { FixtureServer("system.backups") }
        waitForText("Saved backups")
        compose.onNodeWithTag("backup-settings").performClick()
        waitForText("Back up every night")
        shoot("backup-settings", dialog = true)
    }

    @Test fun plugins() {
        show { FixtureServer("system.plugins") }
        waitForText("Tailscale access")
        shoot("plugins")
    }

    @Test fun pluginsNeedingAttention() {
        show { FixtureServer("system.plugins") }
        waitForText("Tailscale access")
        compose.onNodeWithTag("plugin-filter-NeedsAttention").performClick()
        compose.waitForIdle()
        shoot("plugins-needs-attention")
    }

    @Test fun pluginRestartConfirmation() {
        show { FixtureServer("system.plugins") }
        waitForText("Tailscale access")
        compose.onNodeWithTag("plugin-1-menu").performClick()
        compose.onNodeWithTag("plugin-1-restart").performClick()
        waitForText("Restart Tailscale access?")
        shoot("plugin-restart-confirm", dialog = true)
    }

    // ---- Libraries ----

    private fun waitForLibraries() = compose.waitUntil(30_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("library-1")).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun libraries() {
        show { FixtureServer("content.libraries") }
        waitForLibraries()
        shoot("libraries-top")
        compose.onNodeWithContentDescription("Check the mount of TV shows").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("mount-check")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-2").performScrollTo()
        shoot("libraries-warning-and-mount-check")
        compose.onNodeWithTag("library-3").performScrollTo()
        shoot("libraries-running-scan-and-refresh")
        compose.onNodeWithTag("recent-scans").performScrollTo()
        shoot("libraries-recent-scans")
    }

    @Test fun librariesDialogs() {
        show { FixtureServer("content.libraries") }
        waitForLibraries()
        compose.onNodeWithContentDescription("Stop scanning Music").performScrollTo().performClick()
        compose.waitForIdle()
        shoot("libraries-stop-dialog", dialog = true)
        compose.onNodeWithText("Keep scanning").performClick()
        compose.onNodeWithContentDescription("Refresh metadata of Movies").performScrollTo().performClick()
        compose.onNodeWithText("Refresh all metadata").performClick()
        compose.waitForIdle()
        shoot("libraries-refresh-dialog", dialog = true)
    }

    @Test fun librariesAllServers() {
        val first = com.bloemstudios.admin.fixtures.LibraryFixtures().client
        val second = com.bloemstudios.admin.fixtures.LibraryFixtures().client
        val viewModel = com.bloemstudios.admin.libraries.AggregateLibrariesViewModel()
        val a = viewModel.pageFor(com.bloemstudios.admin.servers.AdminServer("a", "https://a.test", "Bloem Home"))
        val b = viewModel.pageFor(com.bloemstudios.admin.servers.AdminServer("b", "https://b.test", "Bloem Office"))
        show {
            androidx.compose.runtime.LaunchedEffect(Unit) { a.refresh(first); b.refresh(second) }
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = com.bloemstudios.admin.ui.theme.VeilSpacing.CompactHorizontal),
            ) {
                com.bloemstudios.admin.ui.components.ServerGroupHeader("Bloem Home")
                com.bloemstudios.admin.libraries.LibraryServerContent(a, first)
                com.bloemstudios.admin.ui.components.ServerGroupHeader("Bloem Office")
                com.bloemstudios.admin.libraries.LibraryServerContent(b, second)
            }
        }
        waitForLibraries()
        shoot("libraries-all-servers")
    }

    // ---- Platform › Organizations -----------------------------------------------------------------------

    @Test fun platformOrganizations() {
        show { FixtureServer("platform.organizations") }
        shoot("platform-orgs-list")
    }

    @Test fun platformOrganizationsMenu() {
        show { FixtureServer("platform.organizations") }
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.waitForIdle()
        shoot("platform-orgs-menu")
    }

    @Test fun platformOrganization() {
        show { FixtureServer("platform.organization") }
        shoot("platform-org-top")
        scrollOrganizationTo("platform-plan")
        shoot("platform-org-plan")
        scrollOrganizationTo("platform-member-m5")
        shoot("platform-org-members")
    }

    @Test fun platformOrganizationSuspendConfirmation() {
        show { FixtureServer("platform.organization") }
        scrollOrganizationTo("platform-org-suspend"); compose.onNodeWithTag("platform-org-suspend").performClick()
        compose.waitForIdle()
        shootScreen("platform-org-suspend-confirm")
    }

    @Test fun platformOrganizationPlanPreview() {
        show { FixtureServer("platform.organization-plan") }
        scrollOrganizationTo("platform-plan-changes")
        shoot("platform-org-plan-preview")
        scrollOrganizationTo("platform-plan-apply"); compose.onNodeWithTag("platform-plan-apply").performClick()
        compose.waitForIdle()
        shootScreen("platform-org-plan-confirm")
    }

    @Test fun platformOrganizationsAllServers() {
        show { com.bloemstudios.admin.fixtures.FixtureAllServers("platform.organizations") }
        shoot("platform-orgs-all-servers")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
}
