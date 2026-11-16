package com.bloemstudios.admin.fixtures

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The minifiedTest app is built like release. It must not contain the debug-only fixture screen, must not declare it
 * in its manifest, and must not answer the bloemfixture:// link. (The sample data compiled into the test APK is not
 * part of the app being tested.)
 */
@RunWith(AndroidJUnit4::class)
class FixtureAbsentFromReleaseLikeBuildTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theFixtureActivityClassIsNotInTheApp() {
        val loadedFromApp = runCatching { context.classLoader.loadClass("com.bloemstudios.admin.fixtures.FixtureActivity") }
        assertTrue("FixtureActivity must not exist in a release-like build", loadedFromApp.isFailure)
    }

    @Test
    fun theManifestDoesNotDeclareTheFixtureActivity() {
        val declared = runCatching {
            context.packageManager.getActivityInfo(ComponentName(context.packageName, "com.bloemstudios.admin.fixtures.FixtureActivity"), 0)
        }
        assertTrue("FixtureActivity must not be declared", declared.isFailure)
        assertTrue(declared.exceptionOrNull() is PackageManager.NameNotFoundException)
    }

    @Test
    fun noActivityAnswersTheFixtureLink() {
        val link = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("bloemfixture://shell?page=live.streams"))
            .setPackage(context.packageName)
        val handlers = context.packageManager.queryIntentActivities(link, 0)
        assertFalse("nothing in the app handles bloemfixture://: $handlers", handlers.isNotEmpty())
        assertNotNull(context.packageName)
    }
}
