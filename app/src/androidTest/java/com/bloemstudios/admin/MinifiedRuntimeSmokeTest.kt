package com.bloemstudios.admin

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the release-equivalent shrunk APK, not the debug runtime. */
@RunWith(AndroidJUnit4::class)
class MinifiedRuntimeSmokeTest {
    @Test
    fun launchAndRepresentativeSerializedContractsSurviveShrinking() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                // Screenshots and screen recording are allowed: the window must not be secure.
                assertFalse(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                )
            }
        } finally {
            scenario.close()
        }

        assertTrue(MinifiedRuntimeProbe.verifySerializedContracts())
    }
}
