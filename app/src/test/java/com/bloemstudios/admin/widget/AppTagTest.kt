package com.bloemstudios.admin.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which app a widget belongs to (docs/design.md, "Widgets"): the app's mark and name in the header before the widget's title, the mark alone
 * at small sizes, "Bloem Admin:" at the start of every gallery description, and no accent. Widget content still says "Silo".
 */
class AppTagTest {
    private val res = "src/main/res"
    private val widgetUi = File("src/main/java/com/bloemstudios/admin/widget/WidgetUi.kt").readText()

    private fun strings(): Map<String, String> = File("$res/values").listFiles { f -> f.extension == "xml" }!!.flatMap { file ->
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }.toMap()

    private val providers = listOf(
        "now_playing_widget_info.xml", "requests_widget_info.xml", "libraries_widget_info.xml", "nodes_widget_info.xml", "server_status_widget_info.xml",
    )

    @Test fun theHomeScreenNameIsBloemAdmin() {
        assertEquals("Bloem Admin", strings().getValue("app_name"))
    }

    @Test fun everyGalleryDescriptionStartsWithTheAppName() {
        val all = strings()
        providers.forEach { file ->
            val ref = Regex("""android:description="@string/([^"]+)"""").find(File("$res/xml/$file").readText())!!.groupValues[1]
            assertTrue("$file: ${all.getValue(ref)}", all.getValue(ref).startsWith("Bloem Admin: "))
        }
    }

    @Test fun theMarkIsTheAdminLauncherMarkInOneColour() {
        val mark = File("$res/drawable/widget_app_mark.xml").readText()
        // The same drawing as the themed launcher layer (a tulip with the server badge), cropped, and a single colour so it can be tinted.
        val launcher = File("$res/drawable/ic_launcher_monochrome.xml").readText()
        val tulip = Regex("""android:pathData="(M567\.0 263\.2[^"]+)"""").find(launcher)!!.groupValues[1]
        assertTrue("the same tulip path", tulip in mark)
        assertTrue("one colour", Regex("""(?:fill|stroke)Color="(#[0-9A-Fa-f]{8})"""").findAll(mark).map { it.groupValues[1] }.toSet().size == 1)
    }

    @Test fun theMarkIsOneToSixteenDpAndTheNameComesWhereThereIsRoom() {
        assertTrue(AppTagRules.MARK_DP in 14..16 && AppTagRules.MARK_SLIM_DP in 14..16)
        assertFalse("small and narrow: the mark alone", AppTagRules.showsName(158f))
        assertFalse(AppTagRules.showsName(250f))
        assertTrue("a 4-cell widget: mark and name", AppTagRules.showsName(320f))
        assertFalse("the server name needs more room still", AppTagRules.showsServer(320f))
        assertTrue(AppTagRules.showsServer(400f))
    }

    @Test fun theTagIsMutedAndNeverTheAccentOrTheTallyDot() {
        val tag = widgetUi.substringAfter("internal fun AppTag(").substringBefore("/** The small layout's top row")
        assertTrue(tag.contains("Tokens.Muted"))
        assertFalse(tag.contains("Tokens.Accent"))
        assertFalse(tag.contains("Tokens.Tally"))
        assertFalse(tag.contains("TallyDot"))
        assertTrue("label style: 11, weight 500, muted", widgetUi.contains("val LabelStyle get() = TextStyle(color = Tokens.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)"))
    }

    @Test fun everyHeaderAndEverySmallLayoutCarriesTheTag() {
        val header = widgetUi.substringAfter("internal fun PageHeader(").substringBefore("/** The widget's card")
        assertTrue("the header puts the tag before the title", header.indexOf("AppTag(") in 0 until header.indexOf("RowTitleStyle"))
        assertTrue("the small layout draws the mark alone", widgetUi.substringAfter("internal fun SmallHeader(").substringBefore("/**\n * The header of a page-style widget").contains("AppTag(0.dp"))
        val small = File("src/main/java/com/bloemstudios/admin/widget/ListCards.kt").readText() + File("src/main/java/com/bloemstudios/admin/widget/NowPlayingContent.kt").readText()
        assertEquals("both small layouts use it", 2, Regex("SmallHeader\\(").findAll(small).count())
    }

    @Test fun theContentStillSaysSiloWhereItNamesTheServerSoftware() {
        val all = strings()
        listOf("widget_now_playing_unsupported", "widget_requests_unsupported", "widget_libraries_unsupported", "widget_nodes_unsupported").forEach {
            assertTrue("$it: ${all.getValue(it)}", all.getValue(it).contains("Silo server"))
        }
        assertFalse("the content never calls the server software Bloem", all.filterKeys { it.startsWith("widget_") && "description" !in it && "picker" !in it }.values.any { "Bloem" in it && !it.startsWith("Bloem Admin") })
    }
}
