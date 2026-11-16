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
import com.bloemstudios.admin.widget.LibrariesContent
import com.bloemstudios.admin.widget.NodesContent
import com.bloemstudios.admin.widget.NoopEnv
import com.bloemstudios.admin.widget.NowPlayingContent
import com.bloemstudios.admin.widget.RequestsContent
import com.bloemstudios.admin.widget.ServerStatusContent
import com.bloemstudios.admin.widget.WidgetEnv
import com.bloemstudios.admin.widget.Detail
import com.bloemstudios.admin.widget.WidgetSamples
import com.bloemstudios.admin.widget.WidgetState
import com.bloemstudios.admin.widget.openLinkAction
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Draws every widget at five shapes a person can drag one to (small, medium, large, wide and short, tall and narrow), in the
 * dark and light schemes, from made-up sample content. Glance composes the real content to RemoteViews at the exact size, which
 * are inflated, laid out and drawn over a stand-in wallpaper, the way a launcher gets them. Nothing here contacts a server.
 *
 *   ANDROID_SERIAL=<your own emulator> ./gradlew connectedMinifiedTestAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.WidgetResizeScreenshotTest
 *   (PNGs: app/build/outputs/connected_android_test_additional_output/)
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class WidgetResizeScreenshotTest {
    private val outDir: File by lazy {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)?.apply { mkdirs() }
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    /** What a launcher gives a 2 x 2, 4 x 2, 4 x 4, 4 x 1 and 2 x 4 cell widget on a typical phone. */
    private val shapes = listOf(
        "small" to DpSize(158.dp, 158.dp),
        "medium" to DpSize(320.dp, 112.dp),
        "large" to DpSize(320.dp, 276.dp),
        "wide-short" to DpSize(320.dp, 62.dp),
        "tall-narrow" to DpSize(158.dp, 276.dp),
    )

    private fun shoot(name: String, box: DpSize, fontScale: Float = 1f, content: @Composable (WidgetEnv, Action) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (night in listOf(true, false)) {
            val configuration = android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                this.fontScale = fontScale
            }
            val context = instrumentation.targetContext.createConfigurationContext(configuration)
            val env = NoopEnv(context)
            val open = openLinkAction(context, "bloemadmin://all")
            val remoteViews = runBlocking { GlanceRemoteViews().compose(context, box) { content(env, open) }.remoteViews }
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

    @org.junit.Before fun noLazyLists() { com.bloemstudios.admin.widget.WidgetRendering.lazyLists = false }

    @Test fun nowPlaying() {
        for ((shape, box) in shapes) shoot("now-playing-$shape", box) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(), env, open) }
        // Fewer streams than the height has room for: the cards share the height, and a footer fills the rest.
        shoot("now-playing-large-1-stream", shapes.first { it.first == "large" }.second) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(count = 1), env, open) }
        shoot("now-playing-large-2-streams", shapes.first { it.first == "large" }.second) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(count = 2), env, open) }
        shoot("now-playing-medium-1-stream", shapes.first { it.first == "medium" }.second) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(count = 1), env, open) }
        // A tablet: the whole path on every card, and bandwidth on every row.
        shoot("now-playing-xl", DpSize(520.dp, 420.dp)) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(), env, open) }
        // The largest text size: rows grow so no line is cut off.
        for ((shape, box) in shapes.filter { it.first in setOf("medium", "large") }) {
            shoot("now-playing-$shape-large-text", box, fontScale = 1.3f) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(), env, open) }
        }
        // The lock screen: counts only.
        shoot("now-playing-medium-counts-only", shapes.first { it.first == "medium" }.second) { env, open -> NowPlayingContent(WidgetSamples.nowPlaying(Detail.CountsOnly), env, open) }
    }

    @Test fun nowPlayingStates() {
        val box = shapes.first { it.first == "medium" }.second
        val lastHour = WidgetSamples.NOW - 3_600_000L
        shoot("now-playing-state-loading", box) { env, open -> NowPlayingContent(WidgetState.Loading, env, open) }
        shoot("now-playing-state-signed-out", box) { env, open -> NowPlayingContent(WidgetState.SignedOut("Bloem Home"), env, open) }
        shoot("now-playing-state-empty", box) { env, open -> NowPlayingContent(WidgetState.Empty(null), env, open) }
        shoot("now-playing-state-empty-stale", box) { env, open -> NowPlayingContent(WidgetState.Empty(lastHour), env, open) }
        shoot("now-playing-state-unsupported", box) { env, open -> NowPlayingContent(WidgetState.Unsupported("Bloem Home"), env, open) }
        shoot("now-playing-state-unreachable", box) { env, open ->
            NowPlayingContent(WidgetState.Unreachable("Bloem Home", 1, (WidgetSamples.nowPlaying() as WidgetState.Content).data, lastHour), env, open)
        }
        shoot("now-playing-state-stale", shapes.first { it.first == "large" }.second) { env, open ->
            NowPlayingContent((WidgetSamples.nowPlaying() as WidgetState.Content).copy(staleAt = lastHour), env, open)
        }
    }

    @Test fun serverStatus() {
        for ((shape, box) in shapes) shoot("server-status-$shape", box) { env, open -> ServerStatusContent(WidgetSamples.status(), env, open) }
    }

    @Test fun requests() {
        for ((shape, box) in shapes) shoot("requests-$shape", box) { env, open -> RequestsContent(WidgetSamples.requests(), env, open) }
    }

    @Test fun libraries() {
        for ((shape, box) in shapes) shoot("libraries-$shape", box) { env, open -> LibrariesContent(WidgetSamples.libraries(), env, open) }
    }

    @Test fun nodes() {
        for ((shape, box) in shapes) shoot("nodes-$shape", box) { env, open -> NodesContent(WidgetSamples.nodes(), env, open) }
    }
}
