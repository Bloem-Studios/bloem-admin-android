package com.bloemstudios.admin.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OAuthBrowserTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test fun anApplicationContextNeedsANewTask() {
        assertTrue(OAuthBrowser.needsNewTask(ApplicationProvider.getApplicationContext<Context>()))
    }

    @Test fun anActivityDoesNot() {
        assertFalse(OAuthBrowser.needsNewTask(activity))
    }

    @Test fun anActivityBehindWrappersDoesNot() {
        assertFalse(OAuthBrowser.needsNewTask(ContextWrapper(ContextWrapper(activity))))
    }

    @Test fun wrappersAroundTheApplicationStillNeedANewTask() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(OAuthBrowser.needsNewTask(ContextWrapper(ContextWrapper(app))))
    }
}
