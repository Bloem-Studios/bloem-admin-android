# AndroidJUnitRunner is installed in a separate APK but resolves these shared
# dependencies through the target application at runtime.
-keep class androidx.tracing.Trace { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# Espresso (in the instrumentation APK) needs javax.inject.Provider. Firebase Messaging puts javax.inject in the target app, so
# the instrumentation APK leaves its own copy out and relies on the target's, which R8 would otherwise shrink away. Without
# this every Espresso-backed test fails with NoClassDefFoundError. minifiedTest only; release is unaffected.
-keep class javax.inject.** { *; }

# Compose UI-test resolves its runtime and UI APIs from the minified target APK.
# This is a minifiedTest-only cross-APK boundary and does not affect release.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }

# Compose's separate instrumentation APK calls Activity Compose's public
# setContent facade through the target APK.
-keep class androidx.activity.compose.ComponentActivityKt { *; }

# Compose installs owners across the target/test APK boundary.
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }

# The P0 instrumentation APK directly links these exact application classes.
# Retaining their names and members makes that external ABI explicit while the
# rest of the target stays release-minified.
-keep class com.bloemstudios.admin.R$string { *; }
-keep class com.bloemstudios.admin.lock.AppLockScreenKt { *; }
-keep class com.bloemstudios.admin.lock.AppSettingsScreenKt { *; }
-keep class com.bloemstudios.admin.navigation.CompactNavigationItem { *; }
-keep class com.bloemstudios.admin.navigation.CompactNavigationKt { *; }
-keep class com.bloemstudios.admin.ui.theme.VeilAdminThemeKt { *; }
-keep class com.bloemstudios.admin.ui.theme.VeilMotionKt { *; }
-keep class com.bloemstudios.admin.ui.components.VeilStateSurfaceKind { *; }
-keep class com.bloemstudios.admin.ui.components.VeilStateSurfaceAction { *; }
-keep class com.bloemstudios.admin.ui.components.VeilStateSurfaceKt { *; }
-keep class com.bloemstudios.admin.ui.shell.AdminDestination { *; }
-keep class com.bloemstudios.admin.ui.shell.AdminDestinationKt { *; }
-keep class com.bloemstudios.admin.ui.shell.AdminGroup { *; }
-keep class com.bloemstudios.admin.ui.shell.AdminShellContext { *; }
-keep class com.bloemstudios.admin.ui.shell.AdminShellKt { *; }
-keep class com.bloemstudios.admin.ui.shell.ServerContextHeaderKt { *; }
-keep class com.bloemstudios.admin.ui.shell.ShellInteractionLockKt { *; }

# Feature renderer instrumentation constructs and reads these exact production
# fixture/action types from the separately minified test APK. Keep members only
# on these exact types as a stable cross-APK ABI; class names remain obfuscated
# and release does not consume this minifiedTest-only rules file.
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.DashboardScreenKt { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.AdminStats { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.SystemResources { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.SystemStats { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.streams.AdminSession { *; }
# Dashboard section fixtures construct the insight models and read the section enum across APKs.
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.WatchProviderStats { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.BuildInfo { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.DashboardNode { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.DashboardLibrary { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.PlaybackBucket { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.PlaybackReliability { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.PlaybackActivity { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.TimeseriesPoint { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.Timeseries { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.TopTitle { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.TopProfile { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.TopActivity { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.HealthComponent { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.ServerHealth { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.SectionState { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.DashboardInsights { *; }
-keep class com.bloemstudios.admin.dashboard.DashboardSection { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.serverconfig.ServerStatus { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.users.AdminUser { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.streams.StreamActions { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.streams.AggregateStreamActions { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.activity.PlaybackHistoryEntry { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.network.ApiError { *; }

# Cross-APK entrypoint; its implementation still executes entirely inside
# the release-minified target and therefore exercises obfuscated contracts.
-keep class com.bloemstudios.admin.MinifiedRuntimeProbe { *; }

# Existing encryption instrumentation calls these target-APK APIs directly.
# Keep only that test-facing boundary; the distributable release variant does
# not consume this file.
-keep class com.bloemstudios.admin.servers.** { *; }

# Task 2 scope/navigation instrumentation cross-APK APIs.
-keep class com.bloemstudios.admin.MainActivityKt { *; }
-keep class com.bloemstudios.admin.navigation.ActivityScopePreferences { *; }
-keep class com.bloemstudios.admin.navigation.ActivityScopePreferencesKt { *; }
-keep class com.bloemstudios.admin.ui.shell.ActivityScopeOption { *; }
-keep class com.bloemstudios.admin.ui.shell.ActivityScopeControl { *; }
-keep class androidx.navigation.** { *; }
-keep class com.bloemstudios.admin.lock.AppLockPreferences { *; }

# Foreground lifecycle fixtures invoke this exact Compose facade across APKs.
-keep class com.bloemstudios.admin.realtime.ForegroundEffectKt { *; }
-keep class androidx.lifecycle.compose.LocalLifecycleOwnerKt { *; }
# Only these repository fixture constructor/callback types cross the test APK boundary.
-keep class com.bloemstudios.admin.realtime.LiveSessionsRepository { *; }
-keep class com.bloemstudios.admin.realtime.SessionTransport { *; }
-keep class com.bloemstudios.admin.network.ApiClient { *; }

# Task 5 aggregate renderer/source fixtures call these exact target-APK facades.
-keep class com.bloemstudios.admin.dashboard.AggregateDashboardScreenKt { *; }
-keep class com.bloemstudios.admin.streams.AggregateStreamsScreenKt { *; }
-keep class com.bloemstudios.admin.realtime.RealtimeState { *; }
-keep class com.bloemstudios.admin.realtime.SessionFailure { *; }
# Renderer fixtures read the existing spacing singleton across APKs.
-keep class com.bloemstudios.admin.ui.theme.VeilSpacing { *; }
# Expanded optional source-state renderer parameters must retain their exact test ABI.
-keep class com.bloemstudios.admin.streams.StreamsScreenKt { *; }
# The Now Playing row tests construct StreamRow and swap its clock across APKs.
-keep class com.bloemstudios.admin.streams.StreamRowKt { *; }

# Manual sampled refresh fixtures exercise the real ViewModel/helper across APKs.
-keep class com.bloemstudios.admin.dashboard.DashboardViewModel { *; }
-keep class com.bloemstudios.admin.dashboard.SampledRefreshFixture { *; }
# Final lifecycle regressions construct only this test-variant entrypoint across APKs.
-keep class com.bloemstudios.admin.dashboard.ManagementReadFixture { *; }

# OAuth redirect instrumentation reads the in-process redirect holder and launches the activity across APKs.
-keep class com.bloemstudios.admin.auth.OAuthRedirects { *; }
-keep class com.bloemstudios.admin.auth.OAuthRedirectActivity { *; }
# Add Server sign-in options instrumentation constructs these provider models across APKs.
-keep class com.bloemstudios.admin.auth.AuthProvider { *; }
-keep class com.bloemstudios.admin.auth.AuthProviders { *; }

# ReLoginFormTest drives ReLoginForm with a ktor MockEngine. ktor-client-mock is packaged in the target APK (and would be renamed by R8),
# so the mock engine plus only the Ktor types the test APK links directly are kept (not all of io.ktor).
-keep class io.ktor.client.engine.mock.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.client.engine.HttpClientEngine* { *; }
-keep class io.ktor.client.request.** { *; }
-keep class io.ktor.client.statement.** { *; }
-keep class io.ktor.client.call.** { *; }
-keep class io.ktor.util.** { *; }
-keep class io.ktor.utils.io.** { *; }
-dontwarn java.lang.management.**
-keep class com.bloemstudios.admin.network.ServerOrigin { *; }

# Organization-mode instrumentation constructs these exact types from the test APK: the screens'
# state and callbacks, the session and its Ktor-backed clients (fixtures answer through MockEngine).
# minifiedTest only; the release variant does not consume this file.
-keep class com.bloemstudios.admin.organization.** { *; }
-keep class com.bloemstudios.admin.auth.AccessTokenSnapshot { *; }
-keep class com.bloemstudios.admin.auth.UnauthorizedRecovery { *; }
-keep class com.bloemstudios.admin.network.ErrorText { *; }
-keep class com.bloemstudios.admin.network.ErrorText$* { *; }

# Organization web-parity screens: the instrumented tests build policy-check resources from kotlinx.serialization JSON values.
-keep class kotlinx.serialization.json.** { *; }

# Trend chart instrumentation constructs the window choices and drives the real view model through this fixture across APKs.
-keep class com.bloemstudios.admin.dashboard.ChartKind { *; }
-keep class com.bloemstudios.admin.dashboard.ChartRange { *; }
-keep class com.bloemstudios.admin.dashboard.ChartRanges { *; }
-keep class com.bloemstudios.admin.dashboard.TrendChartsFixture { *; }

# Requests page instrumentation constructs the page's models, state and flow, and calls its composables, across APKs.
# minifiedTest only; the release variant does not consume this file.
-keep class com.bloemstudios.admin.requests.** { *; }

# The API keys page instrumentation and the fixture screen construct the key model and draw the page across APKs.
-keep class com.bloemstudios.admin.apikeys.** { *; }

# The screenshot tour and the theme tests choose System, Light or Dark and draw the real screens from sample data across APKs.
-keep class com.bloemstudios.admin.ui.theme.ThemeMode { *; }
-keep class com.bloemstudios.admin.ui.theme.ThemePreferences { *; }
-keep class com.bloemstudios.admin.ui.theme.VeilPalette { *; }
-keep class com.bloemstudios.admin.dashboard.DashboardSectionsKt { *; }
-keep class com.bloemstudios.admin.dashboard.DashboardSectionsData { *; }
-keep class com.bloemstudios.admin.users.UsersScreenKt { *; }
-keep class com.bloemstudios.admin.ui.components.** { *; }
-keep class com.bloemstudios.admin.ui.theme.VeilColors { *; }
-keep class com.bloemstudios.admin.ui.theme.VeilPalette { *; }
-keep class com.bloemstudios.admin.lock.AppSettingsScreenKt { *; }
-keep class com.bloemstudios.admin.ui.theme.ThemeMode { *; }
-keep class com.bloemstudios.admin.ui.theme.VeilPalette$Companion { *; }

# Playback history, Logs and Audit: the fixture screen and the instrumented tests call these screens, the history filter
# state and the Logs hand-off across APKs. minifiedTest only; the release variant does not consume this file.
-keep class com.bloemstudios.admin.activity.ActivityScreenKt { *; }
-keep class com.bloemstudios.admin.activity.HistoryFilterUi { *; }
-keep class com.bloemstudios.admin.activity.HistoryFilter { *; }
-keep class com.bloemstudios.admin.activity.CompletionFilter { *; }
-keep class com.bloemstudios.admin.users.UserChoice { *; }
-keep class com.bloemstudios.admin.logs.LogsScreenKt { *; }
-keep class com.bloemstudios.admin.logs.LogsFocus { *; }
-keep class com.bloemstudios.admin.audit.AuditScreenKt { *; }
# The dashboard fixture builds the host's disks and GPUs.
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.DiskStats { *; }
-keepclassmembers,allowobfuscation class com.bloemstudios.admin.dashboard.GpuStats { *; }
# LibrariesScreenTest links the Libraries page directly.
-keep class com.bloemstudios.admin.libraries.LibrariesScreenKt { *; }
-keep class com.bloemstudios.admin.libraries.LibraryPage { *; }
-keep class com.bloemstudios.admin.libraries.ScanState { *; }
-keep class com.bloemstudios.admin.libraries.AggregateLibrariesViewModel { *; }
# Platform › Organizations instrumentation and the screenshot tour construct these exact types from the test APK: the pages'
# state, callbacks and the fixture models. minifiedTest only; the release variant does not consume this file.
-keep class com.bloemstudios.admin.platform.** { *; }
# The alerts section of App settings, the deep-link inbox and the navigation tests that drive them call these across APKs.
# minifiedTest only; the release variant does not consume this file.
-keep class com.bloemstudios.admin.alerts.AlertSettingsKt { *; }
-keep class com.bloemstudios.admin.alerts.AlertDeliveryStatus { *; }
-keep class com.bloemstudios.admin.links.** { *; }
-keep class com.bloemstudios.admin.alerts.AlertNotifier { *; }
-keep class com.bloemstudios.admin.alerts.AlertPreferences { *; }
-keep class com.bloemstudios.admin.alerts.AlertNotifier$Companion { *; }

# The screenshot tour renders the widget's Glance content with sample models from the test APK. minifiedTest only; the
# release variant does not consume this file.
-keep class com.bloemstudios.admin.widget.** { *; }
-keep class androidx.glance.** { *; }

# SettingsPagesUiTest, the screenshot tour and the fixture screen call ServerSettingsHost directly from the test APK. Left
# alone, R8 reorders and merges its parameters (the two lambdas), and the call fails with NoSuchMethodError, which kills
# the instrumentation process. minifiedTest only; the release variant does not consume this file.
-keep class com.bloemstudios.admin.serverconfig.SettingsHostKt { *; }

# The widget screenshot tests (WidgetScreenshotTest, WidgetSurfacesScreenshotTest) draw the widgets from sample data and store
# made-up statuses for the tiles, so they link the widget and status-store classes of the target directly.
-keep class com.bloemstudios.admin.widget.** { *; }
-keep class com.bloemstudios.admin.alerts.** { *; }
