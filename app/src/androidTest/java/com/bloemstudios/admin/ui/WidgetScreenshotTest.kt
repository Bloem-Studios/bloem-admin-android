package com.bloemstudios.admin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.widget.Detail
import com.bloemstudios.admin.widget.LibrariesContent
import com.bloemstudios.admin.widget.NodesContent
import com.bloemstudios.admin.widget.NoopEnv
import com.bloemstudios.admin.widget.NowPlayingContent
import com.bloemstudios.admin.widget.RequestsContent
import com.bloemstudios.admin.widget.ServerStatusContent
import com.bloemstudios.admin.widget.WidgetEnv
import com.bloemstudios.admin.widget.WidgetSamples
import com.bloemstudios.admin.widget.WidgetSize
import com.bloemstudios.admin.widget.WidgetState
import com.bloemstudios.admin.widget.openLinkAction
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Draws every widget from made-up sample content, at small, medium and large, in the dark and light schemes, and then each
 * state at medium. Glance composes the real content to RemoteViews, which are inflated, laid out and drawn over a stand-in
 * wallpaper, the way a launcher gets them. Nothing here contacts a server.
 *
 *   ./gradlew connectedMinifiedTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.WidgetScreenshotTest
 *   (PNGs: app/build/outputs/connected_android_test_additional_output/)
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class WidgetScreenshotTest {
    private val outDir: File by lazy {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)?.apply { mkdirs() }
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    /** What a 2 x 2, 4 x 2 and 4 x 4 cell widget measures on a typical phone. */
    private fun dp(size: WidgetSize) = when (size) {
        WidgetSize.Small -> DpSize(158.dp, 158.dp)
        WidgetSize.Medium -> DpSize(320.dp, 112.dp)
        WidgetSize.Large -> DpSize(320.dp, 276.dp)
    }

    private fun shoot(name: String, size: WidgetSize, themes: List<Boolean> = listOf(true, false), content: @Composable (WidgetEnv, Action) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (night in themes) {
            val configuration = android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            val context = instrumentation.targetContext.createConfigurationContext(configuration)
            val env = NoopEnv(context)
            val open = openLinkAction(context, "bloemadmin://all")
            val box = dp(size)
            val remoteViews = runBlocking {
                GlanceRemoteViews().compose(context, box) { content(env, open) }.remoteViews
            }
            val density = context.resources.displayMetrics.density
            val width = (box.width.value * density).toInt()
            val height = (box.height.value * density).toInt()
            val margin = (16 * density).toInt()
            val bitmap = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync {
                val view = remoteViews.apply(context, FrameLayout(context))
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (night) 0xFF1E2A38.toInt() else 0xFFB9C6D6.toInt()) // a stand-in wallpaper
                canvas.translate(margin.toFloat(), margin.toFloat())
                // The launcher clips a widget to its rounded outline; a plain draw does not.
                canvas.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16 * density, 16 * density, android.graphics.Path.Direction.CW) })
                view.draw(canvas)
            }
            File(outDir, "$name-${if (night) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val sizeName = mapOf(WidgetSize.Small to "small", WidgetSize.Medium to "medium", WidgetSize.Large to "large")

    @Test fun everyWidgetAtEverySize() {
        for (size in WidgetSize.entries) {
            val s = sizeName.getValue(size)
            shoot("server-status-$s", size) { env, open -> ServerStatusContent(WidgetSamples.status(), env, open) }
            shoot("now-playing-$s", size) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(), env, open) }
            shoot("requests-$s", size) { env, open -> RequestsContent(WidgetSamples.requests(), env, open) }
            if (size != WidgetSize.Small) {
                shoot("libraries-$s", size) { env, open -> LibrariesContent(WidgetSamples.libraries(), env, open) }
                shoot("nodes-$s", size) { env, open -> NodesContent(WidgetSamples.nodes(), env, open) }
            }
        }
    }

    @Test fun theLockScreenVersionsShowCountsOnly() {
        for (size in listOf(WidgetSize.Small, WidgetSize.Medium)) {
            val s = sizeName.getValue(size)
            shoot("now-playing-counts-only-$s", size) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(Detail.CountsOnly), env, open) }
            shoot("requests-counts-only-$s", size) { env, open -> RequestsContent(WidgetSamples.requests(Detail.CountsOnly), env, open) }
        }
        shoot("libraries-counts-only-medium", WidgetSize.Medium) { env, open -> LibrariesContent(WidgetSamples.libraries(Detail.CountsOnly), env, open) }
        shoot("nodes-counts-only-medium", WidgetSize.Medium) { env, open -> NodesContent(WidgetSamples.nodes(Detail.CountsOnly), env, open) }
    }

    @Test fun everyStateAtMedium() {
        val size = WidgetSize.Medium
        val lastHour = WidgetSamples.NOW - 3_600_000L
        fun <T> states(name: String, unsupported: String?, content: @Composable (WidgetState<T>, WidgetEnv, Action) -> Unit, sample: WidgetState<T>) {
            shoot("$name-state-loading", size, listOf(true)) { env, open -> content(WidgetState.Loading, env, open) }
            shoot("$name-state-signed-out", size, listOf(true)) { env, open -> content(WidgetState.SignedOut("Home"), env, open) }
            shoot("$name-state-unreachable", size, listOf(true)) { env, open ->
                content(WidgetState.Unreachable("Home", 1, (sample as WidgetState.Content<T>).data, lastHour), env, open)
            }
            if (unsupported != null) shoot("$name-state-unsupported", size, listOf(true)) { env, open -> content(WidgetState.Unsupported("Home"), env, open) }
            shoot("$name-state-empty", size, listOf(true)) { env, open -> content(WidgetState.Empty(null), env, open) }
            shoot("$name-state-stale", size, listOf(true)) { env, open -> content((sample as WidgetState.Content<T>).copy(staleAt = lastHour), env, open) }
        }
        states("now-playing", "yes", { s, e, o -> NowPlayingContent(s, e, o) }, WidgetSamples.nowPlaying())
        states("requests", "yes", { s, e, o -> RequestsContent(s, e, o) }, WidgetSamples.requests())
        states("libraries", "yes", { s, e, o -> LibrariesContent(s, e, o) }, WidgetSamples.libraries())
        states("nodes", "yes", { s, e, o -> NodesContent(s, e, o) }, WidgetSamples.nodes())
        shoot("server-status-state-loading", size, listOf(true)) { env, open -> ServerStatusContent(WidgetState.Loading, env, open) }
        shoot("server-status-state-no-servers", size, listOf(true)) { env, open -> ServerStatusContent(WidgetState.NoServers, env, open) }
        shoot("requests-state-action-failed", size, listOf(true)) { env, open ->
            val content = WidgetSamples.requests() as WidgetState.Content
            RequestsContent(content.copy(data = content.data.copy(failedAction = com.bloemstudios.admin.widget.ActionNotice("sample-home", approve = true, at = WidgetSamples.NOW))), env, open)
        }
    }
}
