package com.bloemstudios.admin.alerts

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.MainActivity
import com.bloemstudios.admin.links.DeepLink
import com.bloemstudios.admin.links.DeepLinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertNotifierIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun tappingAnAlertOpensThatServerThroughTheAppLink() {
        val intent = AlertNotifier.openServerIntent(context, "srv 1")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(DeepLinkTarget("srv 1", null), DeepLink.parse(intent.dataString))
    }

    @Test fun theIntentCarriesNoExtrasSoNothingButTheLinkTravels() {
        assertNull(AlertNotifier.openServerIntent(context, "srv-1").extras)
    }

    @Test fun theIntentStartsOrReusesTheAppsTask() {
        val flags = AlertNotifier.openServerIntent(context, "srv-1").flags
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
}
