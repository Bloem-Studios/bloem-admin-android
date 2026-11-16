package com.bloemstudios.admin.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every admin widget can be dragged to any size, both ways, between sensible bounds, and is drawn at the exact size it gets. */
class WidgetProviderInfoTest {
    private val files = mapOf(
        WidgetKind.NowPlaying to "now_playing_widget_info.xml",
        WidgetKind.Requests to "requests_widget_info.xml",
        WidgetKind.Libraries to "libraries_widget_info.xml",
        WidgetKind.Nodes to "nodes_widget_info.xml",
        WidgetKind.ServerStatus to "server_status_widget_info.xml",
    )

    private fun attributes(name: String): Map<String, String> {
        val text = File("src/main/res/xml/$name").readText()
        return Regex("""android:(\w+)="([^"]*)"""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun dp(value: String?) = value!!.removeSuffix("dp").toInt()

    @Test fun everyKindHasAProvider() {
        assertEquals(WidgetKind.entries.toSet(), files.keys)
    }

    @Test fun everyWidgetResizesHorizontallyAndVertically() {
        files.forEach { (kind, file) -> assertEquals("$kind", "horizontal|vertical", attributes(file)["resizeMode"]) }
    }

    @Test fun everyWidgetHasMinimumAndMaximumResizeBoundsThatMakeSense() {
        files.forEach { (kind, file) ->
            val a = attributes(file)
            val minW = dp(a["minResizeWidth"]); val minH = dp(a["minResizeHeight"])
            val maxW = dp(a["maxResizeWidth"]); val maxH = dp(a["maxResizeHeight"])
            assertTrue("$kind can be made small", minW <= 120 && minH <= 70)
            assertTrue("$kind can be made big", maxW >= 400 && maxH >= 400)
            assertTrue("$kind: the default placement is inside the bounds", dp(a["minWidth"]) in minW..maxW && dp(a["minHeight"]) in minH..maxH)
        }
    }

    @Test fun theDefaultSizeIsFourByTwoCellsExceptServerStatus() {
        files.forEach { (kind, file) ->
            val a = attributes(file)
            val expected = if (kind == WidgetKind.ServerStatus) "2" to "2" else "4" to "2"
            assertEquals("$kind", expected, a["targetCellWidth"] to a["targetCellHeight"])
        }
    }

    @Test fun theWidgetsAreNotRefreshedByTheSystemClockBecauseTheirOwnJobDoesIt() {
        files.values.forEach { assertEquals("0", attributes(it)["updatePeriodMillis"]) }
    }

    @Test fun everyWidgetIsDrawnAtTheExactSizeTheLauncherGives() {
        val text = File("src/main/java/com/bloemstudios/admin/widget/StaffWidgets.kt").readText()
        assertTrue(text.contains("SizeMode.Exact"))
        assertTrue("no widget picks from a short list of fixed layouts", !text.contains("SizeMode.Responsive"))
    }
}
