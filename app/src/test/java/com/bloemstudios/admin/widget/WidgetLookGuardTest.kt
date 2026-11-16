package com.bloemstudios.admin.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the widgets on the staff look: palette tokens only, Lucide icons only, and the manifest and previews all present. */
class WidgetLookGuardTest {
    private val module: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
    private val widgetSources = File(module, "src/main/java/com/bloemstudios/admin/widget").listFiles { f -> f.extension == "kt" }.orEmpty()

    @Test fun noWidgetHardCodesAColor() {
        // Colors come from VeilPalette through Tokens. A literal Color(0x...) in a widget would drift from the palette.
        val offenders = widgetSources.filter { Regex("""Color\(0x[0-9A-Fa-f]+\)""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("Use VeilPalette tokens in widgets", emptyList<String>(), offenders)
    }

    @Test fun everyWidgetImageIsALucideIconTheTallyDotOrTheAppMark() {
        // widget_app_mark is the admin app's mark for the header tag (docs/design.md, "Widgets").
        val allowed = Regex("""R\.drawable\.(lucide_[a-z0-9_]+|widget_tally_dot|widget_app_mark|ic_stat_bloem)""")
        val offenders = widgetSources.flatMap { file ->
            Regex("""R\.drawable\.[a-z0-9_]+""").findAll(file.readText()).map { it.value }.filterNot { allowed.matches(it) }.map { "${file.name}: $it" }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun everyWidgetIsDeclaredWithItsProviderAndPreview() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        listOf(
            "ServerStatusWidgetReceiver" to "server_status", "NowPlayingWidgetReceiver" to "now_playing", "RequestsWidgetReceiver" to "requests",
            "LibrariesWidgetReceiver" to "libraries", "NodesWidgetReceiver" to "nodes",
        ).forEach { (receiver, stem) ->
            assertTrue("$receiver is in the manifest", ".widget.$receiver" in manifest)
            val info = File(module, "src/main/res/xml/${stem}_widget_info.xml")
            assertTrue("$stem has a provider file", info.isFile)
            val text = info.readText()
            assertTrue("$stem is resizable", "resizeMode=\"horizontal|vertical\"" in text)
            assertTrue("$stem has a configuration activity", "WidgetConfigActivity" in text)
            assertTrue("$stem has a description", "android:description" in text)
            val preview = Regex("""android:previewImage="@drawable/([a-z_]+)"""").find(text)?.groupValues?.get(1)
            assertTrue("$stem has a fallback preview for launchers before Android 15", preview != null && File(module, "src/main/res/drawable/$preview.xml").isFile)
            assertTrue("$stem refreshes through WorkManager, never the platform timer", "android:updatePeriodMillis=\"0\"" in text)
        }
        assertTrue("both tiles are declared", ".widget.NowPlayingTileService" in manifest && ".widget.RequestsTileService" in manifest)
    }
}
