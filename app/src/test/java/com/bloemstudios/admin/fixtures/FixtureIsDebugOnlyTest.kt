package com.bloemstudios.admin.fixtures

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture screen draws the app from invented data, so it must never ship. It lives in src/debug (activity and
 * manifest) and src/fixtures (sample data), and the build adds src/fixtures to the debug and androidTest source sets
 * only. These checks read the project files, so a release or minifiedTest build can't pick the screen up by accident.
 */
class FixtureIsDebugOnlyTest {
    private val app: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").exists() }

    private fun File.kotlinFiles(): List<File> = walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun noFixtureCodeOrSampleDataIsInTheMainSourceSet() {
        val inMain = File(app, "src/main").kotlinFiles().filter { it.name.startsWith("Fixture") || "package com.bloemstudios.admin.fixtures" in it.readText() }
        assertEquals("fixture files in src/main: $inMain", emptyList<File>(), inMain)
    }

    @Test
    fun theMainManifestDoesNotDeclareTheFixtureActivityOrItsLink() {
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        assertFalse(manifest, "FixtureActivity" in manifest)
        assertFalse(manifest, "bloemfixture" in manifest)
    }

    @Test
    fun theFixtureActivityIsDeclaredOnlyInTheDebugManifest() {
        val debug = File(app, "src/debug/AndroidManifest.xml").readText()
        assertTrue(debug, ".fixtures.FixtureActivity" in debug)
        listOf("release", "minifiedTest").forEach { variant ->
            val file = File(app, "src/$variant/AndroidManifest.xml")
            assertFalse("$variant manifest must not name the fixture screen", file.exists() && "FixtureActivity" in file.readText())
        }
        assertTrue(File(app, "src/debug/java/com/bloemstudios/admin/fixtures/FixtureActivity.kt").exists())
    }

    @Test
    fun theFixtureSourcesAreAddedToDebugAndTheTestApkOnly() {
        val gradle = File(app, "build.gradle.kts").readText()
        val lines = gradle.lines().filter { "src/fixtures/java" in it }
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines.toString(), lines.any { "getByName(\"debug\")" in it })
        assertTrue(lines.toString(), lines.any { "getByName(\"androidTest\")" in it })
        // The debug-only sample-data engine is a debug dependency, not a release one.
        assertTrue(gradle, "debugImplementation(\"io.ktor:ktor-client-mock" in gradle)
        assertFalse(gradle, "\n    implementation(\"io.ktor:ktor-client-mock" in gradle)
    }

    @Test
    fun thisBuildOfTheAppIsADebugOrTestBuildOnlyIfItCanSeeTheFixtureActivity() {
        // In the unit-test classpath (a debug build) the screen is present; the device test
        // FixtureAbsentFromReleaseLikeBuildTest asserts it is absent from the minifiedTest (release-like) app.
        val present = runCatching { Class.forName("com.bloemstudios.admin.fixtures.FixtureActivity") }.isSuccess
        assertTrue(present)
    }
}
