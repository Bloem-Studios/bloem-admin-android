package com.bloemstudios.admin.auth

import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OAuthRedirectActivityLimitTest {
    private val prefix = "org.siloserver.silo:/auth/callback?code=c&state=s&server=x&iss=https://example.test&pad="

    @Before fun begin() { OAuthRedirects.end(); OAuthRedirects.takeOrphan(); OAuthRedirects.begin() }
    @After fun clean() { OAuthRedirects.end(); OAuthRedirects.takeOrphan() }

    private fun launch(uri: String) {
        Robolectric.buildActivity(OAuthRedirectActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(uri))).create()
    }

    @Test fun aRedirectOfExactlyTheLimitIsDelivered() {
        val uri = prefix + "a".repeat(OAuthRedirectActivity.MAX_REDIRECT_LENGTH - prefix.length)
        assertEquals(OAuthRedirectActivity.MAX_REDIRECT_LENGTH, uri.length)
        launch(uri)
        assertEquals(uri, OAuthRedirects.takeForSignIn())
    }

    @Test fun aLongerRedirectIsDroppedNotDelivered() {
        launch(prefix + "a".repeat(OAuthRedirectActivity.MAX_REDIRECT_LENGTH - prefix.length + 1))
        assertNull(OAuthRedirects.takeForSignIn())
        assertNull(OAuthRedirects.takeOrphan())
    }
}
