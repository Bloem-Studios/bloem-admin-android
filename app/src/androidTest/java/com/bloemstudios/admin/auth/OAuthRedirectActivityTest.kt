package com.bloemstudios.admin.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OAuthRedirectActivityTest {
    private val redirect =
        "org.siloserver.silo:/auth/callback?code=c&state=s&server=x&iss=https://example.test"

    @After fun clean() { OAuthRedirects.end(); OAuthRedirects.takeOrphan() }

    @Test
    fun deliversTheExactRedirectAndFinishes() {
        OAuthRedirects.begin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(redirect))
            .setClass(context, OAuthRedirectActivity::class.java)
        ActivityScenario.launch<OAuthRedirectActivity>(intent).use { scenario ->
            val deadline = System.currentTimeMillis() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertEquals(redirect, OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeOrphan())
    }
}
