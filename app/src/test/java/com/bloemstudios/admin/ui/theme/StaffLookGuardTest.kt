package com.bloemstudios.admin.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the look in docs/design.md from drifting: Lucide icons only, Plus Jakarta Sans only,
 * and in-app copy that says Silo where it names the server software.
 */
class StaffLookGuardTest {
    /** The module folder: Gradle runs unit tests from `app/`; an IDE may run them from the repository root. */
    private val module: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    private fun sources(vararg sets: String): List<File> = sets
        .map { File(module, "src/$it") }
        .filter(File::isDirectory)
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .filter { it.name != "StaffLookGuardTest.kt" }

    private fun File.relative(): String = relativeTo(module).path

    @Test
    fun noMaterialIconRemains() {
        val offenders = sources("main", "test", "androidTest", "fixtures", "debug", "minifiedTest")
            .filter { file ->
                val text = file.readText()
                "androidx.compose.material.icons" in text || Regex("""\bIcons\.(Outlined|Filled|Rounded|Sharp|TwoTone|AutoMirrored|Default)\b""").containsMatchIn(text)
            }
            .map { it.relative() }
        assertEquals("Material icons are still used; use LucideIcon(R.drawable.lucide_*) instead", emptyList<String>(), offenders)

        val gradle = File(module, "build.gradle.kts").readText()
        assertTrue("The Material icon libraries must not be a dependency", "material-icons" !in gradle)
    }

    @Test
    fun everyIconDrawableIsLucide() {
        val drawables = File(module, "src/main/res/drawable").listFiles { f -> f.name.startsWith("lucide_") }.orEmpty()
        assertTrue("The Lucide drawables are missing", drawables.size >= 30)
        drawables.forEach { file ->
            val text = file.readText()
            assertTrue("${file.name} should name its Lucide source", "Lucide" in text)
            assertTrue("${file.name} should be stroke-only", "android:fillColor" !in text)
        }
    }

    @Test
    fun noSystemFontLeaksThrough() {
        val offenders = sources("main").filter { file ->
            Regex("""FontFamily\.(Monospace|SansSerif|Serif|Cursive|Default)\b""").containsMatchIn(file.readText())
        }.map { it.relative() }
        assertEquals("Only Plus Jakarta Sans may be used", emptyList<String>(), offenders)
    }

    @Test
    fun inAppCopyNamesSiloNotBloem() {
        // The app names itself "Bloem Admin" and never "Silo Admin"; "Silo" names the server software.
        // Any other use of "Bloem" must be listed here. "By Bloem" is on an organization's activity, a Bloem-only page that a plain
        // Silo server never shows.
        val allowed = setOf("organization_activity_by_bloem")
        val offenders = File(module, "src/main/res/values").listFiles { f -> f.name.endsWith("strings.xml") }.orEmpty()
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(file.readText())
                    .filter { Regex("""Bloem(?! Admin)""").containsMatchIn(it.groupValues[2]) && it.groupValues[1] !in allowed }
                    .map { "${file.name}: ${it.groupValues[1]}" }
                    .toList()
            }
        assertEquals("In-app copy says Silo for the server and Bloem Admin for the app", emptyList<String>(), offenders)

        val silo = File(module, "src/main/res/values").listFiles { f -> f.name.endsWith("strings.xml") }.orEmpty()
            .filter { "Silo Admin" in it.readText() }.map { it.name }
        assertEquals("The app is never called Silo Admin", emptyList<String>(), silo)
    }

    @Test
    fun captionsAndHelpTextHaveNoTrailingPeriod() {
        // Titles, labels, buttons and captions end without a period; only paragraphs (dialog bodies, messages) keep theirs.
        // Help text, notes and page descriptions are captions.
        val isCaption = Regex("""(_help|_note|^shell_page_.*)$""")
        val offenders = File(module, "src/main/res/values").listFiles { f -> f.name.endsWith("strings.xml") }.orEmpty()
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(file.readText())
                    .filter { m ->
                        val text = m.groupValues[2]
                        isCaption.containsMatchIn(m.groupValues[1]) && text.endsWith(".") && !text.endsWith("..") &&
                            !Regex("""[.!?]\s""").containsMatchIn(text.dropLast(1))
                    }
                    .map { "${file.name}: ${it.groupValues[1]}" }
                    .toList()
            }
        assertEquals("Captions end without a period", emptyList<String>(), offenders)
    }
}
